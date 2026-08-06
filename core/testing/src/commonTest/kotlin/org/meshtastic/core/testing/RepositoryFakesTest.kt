/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.core.testing

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.meshtastic.core.database.entity.FirmwareRelease
import org.meshtastic.core.database.entity.QuickChatAction
import org.meshtastic.core.model.ConnectionEpochs
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.DeviceHardware
import org.meshtastic.core.repository.AwaitedSendResult
import org.meshtastic.core.repository.AwaitedSendStatus
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.meshtastic.core.model.Position as ModelPosition

class RepositoryFakesTest {

    @Test
    fun `FakeDeviceHardwareRepository returns seeded hardware and records calls`() = runTest {
        val repo = FakeDeviceHardwareRepository()
        val hw = DeviceHardware(hwModel = 42, hwModelSlug = "TEST", platformioTarget = "tlora")
        repo.setHardware(hwModel = 42, target = "tlora", device = hw)

        val hit = repo.getDeviceHardwareByModel(hwModel = 42, target = "tlora", forceRefresh = false)
        val miss = repo.getDeviceHardwareByModel(hwModel = 99)

        assertEquals(hw, hit.getOrNull())
        assertNull(miss.getOrNull())
        assertEquals(2, repo.recordedCalls.size)
        assertEquals(Triple(42, "tlora", false), repo.recordedCalls.first())
    }

    @Test
    fun `FakeFirmwareReleaseRepository emits stable and alpha releases`() = runTest {
        val repo = FakeFirmwareReleaseRepository()
        val stable = FirmwareRelease(id = "1.0", title = "1.0", pageUrl = "", zipUrl = "")
        val alpha = FirmwareRelease(id = "1.1-a", title = "1.1-a", pageUrl = "", zipUrl = "")

        repo.setStableRelease(stable)
        repo.setAlphaRelease(alpha)

        assertEquals(stable, repo.stableRelease.first())
        assertEquals(alpha, repo.alphaRelease.first())

        repo.invalidateCache()
        repo.invalidateCache()
        assertEquals(2, repo.invalidateCacheCalls)
    }

    @Test
    fun `FakeQuickChatActionRepository upsert delete and reorder`() = runTest {
        val repo = FakeQuickChatActionRepository()
        val a = QuickChatAction(uuid = 1L, name = "A", message = "hi", position = 0)
        val b = QuickChatAction(uuid = 2L, name = "B", message = "bye", position = 1)

        repo.upsert(a)
        repo.upsert(b)
        assertEquals(listOf(a, b), repo.getAllActions().first())

        repo.setItemPosition(uuid = 1L, newPos = 5)
        assertEquals(listOf(2L, 1L), repo.getAllActions().first().map { it.uuid })

        repo.delete(b)
        assertEquals(1, repo.currentActions.size)

        repo.deleteAll()
        assertTrue(repo.currentActions.isEmpty())
    }

    @Test
    fun `FakeQuickChatActionRepository delete compacts positions`() = runTest {
        val repo = FakeQuickChatActionRepository()
        val a = QuickChatAction(uuid = 1L, name = "A", message = "", position = 0)
        val b = QuickChatAction(uuid = 2L, name = "B", message = "", position = 1)
        val c = QuickChatAction(uuid = 3L, name = "C", message = "", position = 2)
        repo.upsert(a)
        repo.upsert(b)
        repo.upsert(c)

        repo.delete(b)

        // Matches real DAO's decrementPositionsAfter: positions must stay contiguous.
        assertEquals(listOf(1L to 0, 3L to 1), repo.currentActions.map { it.uuid to it.position })
    }

    @Test
    fun `FakeTracerouteSnapshotRepository roundtrips positions keyed by log uuid`() = runTest {
        val repo = FakeTracerouteSnapshotRepository()
        val positions = mapOf(1 to Position(latitude_i = 10), 2 to Position(latitude_i = 20))
        repo.upsertSnapshotPositions(logUuid = "log-1", requestId = 99, positions = positions)

        repo.getSnapshotPositions("log-1").test { assertEquals(positions, awaitItem()) }
        assertEquals(99, repo.lastRequestId("log-1"))
        assertNull(repo.lastRequestId("other"))
    }

    @Test
    fun `FakeDatabaseManager associates only the active address`() = runTest {
        val manager = FakeDatabaseManager()
        manager.switchActiveDatabase("active")

        manager.associateDevice(address = "stale", nodeNum = 1, deviceId = "old", isSessionActive = { true })
        assertNull(manager.lastAssociatedAddress)
        assertNull(manager.lastAssociatedNode)
        assertNull(manager.lastAssociatedDeviceId)

        manager.associateDevice(address = "active", nodeNum = 2, deviceId = "inactive", isSessionActive = { false })
        assertNull(manager.lastAssociatedAddress)
        assertNull(manager.lastAssociatedNode)
        assertNull(manager.lastAssociatedDeviceId)

        manager.associateDevice(address = "active", nodeNum = 3, deviceId = "fresh", isSessionActive = { true })
        assertEquals("active", manager.lastAssociatedAddress)
        assertEquals(3, manager.lastAssociatedNode)
        assertEquals("fresh", manager.lastAssociatedDeviceId)
    }

    @Test
    fun `service repository fake preserves connection epoch semantics`() {
        val repository = FakeServiceRepository()

        repository.setConnectionState(ConnectionState.Connected)
        assertEquals(ConnectionEpochs(completedHandshakes = 1), repository.connectionEpochs.value)

        repository.setConnectionState(ConnectionState.Connected)
        assertEquals(ConnectionEpochs(completedHandshakes = 1), repository.connectionEpochs.value)

        repository.setConnectionState(ConnectionState.Connecting)
        assertEquals(
            ConnectionEpochs(
                departures = 1,
                completedHandshakes = 1,
                handshakesAtLastDeparture = 1,
                lastDepartureState = ConnectionState.Connecting,
            ),
            repository.connectionEpochs.value,
        )

        repository.setConnectionState(ConnectionState.Connected)
        assertEquals(
            ConnectionEpochs(
                departures = 1,
                completedHandshakes = 2,
                handshakesAtLastDeparture = 1,
                lastDepartureState = ConnectionState.Connecting,
            ),
            repository.connectionEpochs.value,
        )
    }

    @Test
    fun `FakeCommandSender captures shared evidence and resets it`() = runTest {
        val sender = FakeCommandSender()

        sender.sendData(DataPacket(to = null, channel = 0, text = "test"))
        sender.sendLockdownPassphrase("secret", boots = 2, hours = 3, maxSessionSeconds = 4, disable = true)
        sender.sendLockNow()

        assertEquals(1, sender.sentPackets.size)
        assertEquals("secret", sender.lastPassphrase)
        assertEquals(2, sender.lastBoots)
        assertEquals(3, sender.lastHours)
        assertEquals(4, sender.lastMaxSessionSeconds)
        assertTrue(sender.lastDisable)
        assertTrue(sender.lockNowCalled)

        sender.reset()

        assertTrue(sender.sentPackets.isEmpty())
        assertNull(sender.lastPassphrase)
        assertEquals(0, sender.lastBoots)
        assertEquals(0, sender.lastHours)
        assertEquals(0, sender.lastMaxSessionSeconds)
        assertFalse(sender.lastDisable)
        assertFalse(sender.lockNowCalled)
    }

    @Test
    fun `FakeCommandSender preserves admin metadata and records only dispatched awaited sends`() = runTest {
        val sender = FakeCommandSender()
        val message = AdminMessage(commit_edit_settings = true)
        sender.awaitedSendResult = AwaitedSendResult(AwaitedSendStatus.TRANSPORT_STOPPED, dispatched = false)

        sender.sendAdminAwaitResult(destNum = 7, requestId = 8, wantResponse = true) { message }

        assertEquals(listOf(AdminInvocation(7, 8, true, message)), sender.adminInvocations)
        assertTrue(sender.sentAdminMessages.isEmpty())

        sender.awaitedSendResult = AwaitedSendResult(AwaitedSendStatus.ACCEPTED, dispatched = true)
        sender.sendAdminAwaitResult(destNum = 9, requestId = 10, wantResponse = false) { message }

        assertEquals(AdminInvocation(9, 10, false, message), sender.adminInvocations.last())
        assertEquals(listOf(message), sender.sentAdminMessages)
    }

    @Test
    fun `FakeRadioController preserves configuration and fixed-position evidence until reset`() = runTest {
        val controller = FakeRadioController()
        val local = Config(device = Config.DeviceConfig())
        val admin = Config(lora = Config.LoRaConfig(hop_limit = 5))
        val module = ModuleConfig(serial = ModuleConfig.SerialConfig(enabled = true))
        val position = ModelPosition(latitude = 1.0, longitude = 2.0, altitude = 3)

        controller.setLocalConfig(local)
        controller.setConfig(destNum = 7, config = admin, packetId = 8)
        controller.setModuleConfig(destNum = 7, config = module, packetId = 9)
        controller.setFixedPosition(destNum = 7, position = position)
        controller.setConnectionState(ConnectionState.Connected)

        assertEquals(
            listOf(
                FakeRadioController.ConfigWrite(destination = null, config = local),
                FakeRadioController.ConfigWrite(destination = 7, config = admin),
            ),
            controller.configWrites,
        )
        assertEquals(listOf(local), controller.localConfigs)
        assertEquals(listOf(admin), controller.adminConfigs)
        assertEquals(local, controller.lastLocalConfig)
        assertEquals(
            listOf(FakeRadioController.ModuleConfigWrite(destination = 7, config = module)),
            controller.moduleConfigWrites,
        )
        assertEquals(listOf(module), controller.moduleConfigs)
        assertEquals(listOf(7), controller.moduleConfigDestinations)
        assertEquals(listOf(position), controller.fixedPositions)
        assertEquals(ConnectionState.Connected, controller.connectionState.value)
        assertEquals(ConnectionEpochs(completedHandshakes = 1), controller.connectionEpochs.value)

        controller.reset()

        assertTrue(controller.configWrites.isEmpty())
        assertTrue(controller.localConfigs.isEmpty())
        assertTrue(controller.adminConfigs.isEmpty())
        assertTrue(controller.moduleConfigWrites.isEmpty())
        assertTrue(controller.moduleConfigs.isEmpty())
        assertTrue(controller.moduleConfigDestinations.isEmpty())
        assertTrue(controller.fixedPositions.isEmpty())
        assertEquals(ConnectionState.Disconnected, controller.connectionState.value)
        assertEquals(ConnectionEpochs(), controller.connectionEpochs.value)
    }

    @Test
    fun `FakeRadioController scopes standalone hooks per write`() = runTest {
        val controller = FakeRadioController()
        val transactionStarted = CompletableDeferred<Unit>()
        val finishTransaction = CompletableDeferred<Unit>()
        val standaloneHooks = mutableListOf<String>()
        controller.onStandaloneConfig = { standaloneHooks += "config" }
        controller.onStandaloneModuleConfig = { standaloneHooks += "module" }

        val transaction = async {
            controller.editSettings(destNum = 7) {
                transactionStarted.complete(Unit)
                finishTransaction.await()
                setConfig(Config(device = Config.DeviceConfig()))
                setModuleConfig(ModuleConfig(serial = ModuleConfig.SerialConfig(enabled = true)))
            }
        }
        transactionStarted.await()

        controller.setConfig(destNum = 8, config = Config(power = Config.PowerConfig()), packetId = 1)
        controller.setModuleConfig(
            destNum = 8,
            config = ModuleConfig(mqtt = ModuleConfig.MQTTConfig(enabled = true)),
            packetId = 2,
        )
        finishTransaction.complete(Unit)
        transaction.await()

        assertEquals(listOf("config", "module"), standaloneHooks)
    }

    @Test
    fun `FakeRadioController commits after block failure and preserves failure precedence`() = runTest {
        val controller = FakeRadioController()
        val blockFailure = IllegalStateException("write failed")
        val commitFailure = IllegalArgumentException("commit failed")
        controller.onEditSettingsCommitted = { throw commitFailure }

        val failure = assertFailsWith<IllegalStateException> { controller.editLocalSettings { throw blockFailure } }

        assertSame(blockFailure, failure)
        val suppressedCommitFailure = assertIs<IllegalArgumentException>(failure.suppressedExceptions.single())
        assertEquals(commitFailure.message, suppressedCommitFailure.message)
        assertEquals(listOf("begin", "commit"), controller.adminOperations)
    }

    @Test
    fun `FakeRadioConfigRepository tracks channel set and module config`() = runTest {
        val repo = FakeRadioConfigRepository()
        val a = ChannelSettings(name = "A")
        val b = ChannelSettings(name = "B")

        repo.replaceAllSettings(listOf(a, b))
        assertEquals(listOf(a, b), repo.currentChannelSet.settings)

        repo.updateChannelSettings(Channel(index = 1, settings = ChannelSettings(name = "B2")))
        assertEquals("B2", repo.currentChannelSet.settings[1].name)

        repo.clearChannelSet()
        assertTrue(repo.currentChannelSet.settings.isEmpty())
    }
}
