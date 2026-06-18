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
package org.meshtastic.core.data.manager

import dev.mokkery.MockMode
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify
import dev.mokkery.verify.VerifyMode.Companion.exactly
import dev.mokkery.verifySuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.Node
import org.meshtastic.core.repository.AppWidgetUpdater
import org.meshtastic.core.repository.CommandSender
import org.meshtastic.core.repository.HistoryManager
import org.meshtastic.core.repository.MeshLocationManager
import org.meshtastic.core.repository.MeshNotificationManager
import org.meshtastic.core.repository.MeshWorkerManager
import org.meshtastic.core.repository.MqttManager
import org.meshtastic.core.repository.NodeManager
import org.meshtastic.core.repository.PacketHandler
import org.meshtastic.core.repository.PacketRepository
import org.meshtastic.core.repository.PlatformAnalytics
import org.meshtastic.core.repository.RadioConfigRepository
import org.meshtastic.core.repository.RadioInterfaceService
import org.meshtastic.core.repository.ServiceRepository
import org.meshtastic.core.repository.SessionManager
import org.meshtastic.core.repository.UiPrefs
import org.meshtastic.core.testing.FakeNodeRepository
import org.meshtastic.proto.Config
import org.meshtastic.proto.LocalConfig
import org.meshtastic.proto.LocalModuleConfig
import org.meshtastic.proto.ModuleConfig
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MeshConnectionManagerImplTest {
    private val radioInterfaceService = mock<RadioInterfaceService>(MockMode.autofill)
    private val serviceRepository = mock<ServiceRepository>(MockMode.autofill)

    private val serviceNotifications = mock<MeshNotificationManager>(MockMode.autofill)
    private val uiPrefs = mock<UiPrefs>(MockMode.autofill)
    private val packetHandler = mock<PacketHandler>(MockMode.autofill)
    private val nodeRepository = FakeNodeRepository()
    private val locationManager = mock<MeshLocationManager>(MockMode.autofill)
    private val mqttManager = mock<MqttManager>(MockMode.autofill)
    private val historyManager = mock<HistoryManager>(MockMode.autofill)
    private val radioConfigRepository = mock<RadioConfigRepository>(MockMode.autofill)
    private val commandSender = mock<CommandSender>(MockMode.autofill)
    private val sessionManager = mock<SessionManager>(MockMode.autofill)
    private val nodeManager = mock<NodeManager>(MockMode.autofill)
    private val analytics = mock<PlatformAnalytics>(MockMode.autofill)
    private val packetRepository = mock<PacketRepository>(MockMode.autofill)
    private val workerManager = mock<MeshWorkerManager>(MockMode.autofill)
    private val appWidgetUpdater = mock<AppWidgetUpdater>(MockMode.autofill)

    private val dataPacket = DataPacket(id = 456, time = 0L, to = "0", from = "0", bytes = null, dataType = 0)

    private val radioConnectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val connectionStateFlow = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val localConfigFlow = MutableStateFlow(LocalConfig())
    private val moduleConfigFlow = MutableStateFlow(LocalModuleConfig())

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var manager: MeshConnectionManagerImpl

    @BeforeTest
    fun setUp() {
        every { radioInterfaceService.connectionState } returns radioConnectionState
        every { radioConfigRepository.localConfigFlow } returns localConfigFlow
        every { radioConfigRepository.moduleConfigFlow } returns moduleConfigFlow
        every { serviceRepository.connectionState } returns connectionStateFlow
        every { serviceRepository.setConnectionState(any()) } calls
            { call ->
                connectionStateFlow.value = call.arg<ConnectionState>(0)
            }
        every { serviceNotifications.updateServiceStateNotification(any(), any()) } returns Unit
        everySuspend { commandSender.sendAdmin(any(), any(), any(), any()) } returns Unit
        every { packetHandler.stopPacketQueue() } returns Unit
        every { locationManager.stop() } returns Unit
        every { mqttManager.stop() } returns Unit
        every { nodeManager.nodeDBbyNodeNum } returns emptyMap<Int, Node>()
        every { packetHandler.sendToRadio(any<org.meshtastic.proto.ToRadio>()) } returns Unit
    }

    private fun createManager(scope: CoroutineScope): MeshConnectionManagerImpl = MeshConnectionManagerImpl(
        radioInterfaceService,
        serviceRepository,
        serviceNotifications,
        uiPrefs,
        packetHandler,
        nodeRepository,
        locationManager,
        mqttManager,
        historyManager,
        radioConfigRepository,
        commandSender,
        sessionManager,
        nodeManager,
        analytics,
        packetRepository,
        workerManager,
        appWidgetUpdater,
        DataLayerHeartbeatSender(packetHandler),
        scope,
    )

    @AfterTest fun tearDown() = Unit

    @Test
    fun `Connected state triggers broadcast and config start`() = runTest(testDispatcher) {
        manager = createManager(backgroundScope)
        radioConnectionState.value = ConnectionState.Connected
        advanceUntilIdle()

        assertEquals(
            ConnectionState.Connecting,
            serviceRepository.connectionState.value,
            "State should be Connecting after radio Connected",
        )
    }

    @Test
    fun `Connected state sends pre-handshake heartbeat before config request`() = runTest(testDispatcher) {
        val sentPackets = mutableListOf<org.meshtastic.proto.ToRadio>()
        every { packetHandler.sendToRadio(any<org.meshtastic.proto.ToRadio>()) } calls
            { call ->
                sentPackets.add(call.arg(0))
            }

        manager = createManager(backgroundScope)
        radioConnectionState.value = ConnectionState.Connected
        // Advance past PRE_HANDSHAKE_SETTLE_MS (100ms) but NOT the 30s stall guard timeout
        advanceTimeBy(200)

        // First ToRadio should be a heartbeat, second should be want_config_id
        assertEquals(2, sentPackets.size, "Expected heartbeat + want_config_id, got ${sentPackets.size} packets")
        val heartbeat = sentPackets[0]
        val wantConfig = sentPackets[1]

        assertEquals(true, heartbeat.heartbeat != null, "First packet should be a heartbeat")
        assertEquals(true, heartbeat.heartbeat!!.nonce != 0, "Heartbeat should have a non-zero nonce")
        assertEquals(
            org.meshtastic.core.repository.HandshakeConstants.CONFIG_NONCE,
            wantConfig.want_config_id,
            "Second packet should be want_config_id with CONFIG_NONCE",
        )
    }

    @Test
    fun `Disconnect during pre-handshake settle cancels config start`() = runTest(testDispatcher) {
        val sentPackets = mutableListOf<org.meshtastic.proto.ToRadio>()
        every { packetHandler.sendToRadio(any<org.meshtastic.proto.ToRadio>()) } calls
            { call ->
                sentPackets.add(call.arg(0))
            }
        every { nodeManager.nodeDBbyNodeNum } returns emptyMap()

        manager = createManager(backgroundScope)
        radioConnectionState.value = ConnectionState.Connected
        // Advance only 50ms — within the 100ms settle window
        advanceTimeBy(50)

        // Should have sent only the heartbeat so far, not want_config_id
        assertEquals(1, sentPackets.size, "Only heartbeat should be sent before settle completes")

        // Disconnect before the settle delay completes — should cancel the pending config start
        radioConnectionState.value = ConnectionState.Disconnected
        advanceTimeBy(200)

        // The want_config_id should NOT have been sent because the job was cancelled
        val configPackets = sentPackets.filter { it.want_config_id != null }
        assertEquals(0, configPackets.size, "want_config_id should not be sent after disconnect")
    }

    @Test
    fun `Disconnected state stops services`() = runTest(testDispatcher) {
        every { nodeManager.nodeDBbyNodeNum } returns emptyMap()
        manager = createManager(backgroundScope)
        // Transition to Connected first so that Disconnected actually does something
        radioConnectionState.value = ConnectionState.Connected
        advanceUntilIdle()

        radioConnectionState.value = ConnectionState.Disconnected
        advanceUntilIdle()

        assertEquals(
            ConnectionState.Disconnected,
            serviceRepository.connectionState.value,
            "State should be Disconnected after radio Disconnected",
        )
        verify { packetHandler.stopPacketQueue() }
        verify { locationManager.stop() }
        verify { mqttManager.stop() }
    }

    @Test
    fun `DeviceSleep behavior when power saving is off maps to Disconnected`() = runTest(testDispatcher) {
        // Power saving disabled + Role CLIENT
        val config =
            LocalConfig(
                power = Config.PowerConfig(is_power_saving = false),
                device = Config.DeviceConfig(role = Config.DeviceConfig.Role.CLIENT),
            )
        every { radioConfigRepository.localConfigFlow } returns flowOf(config)
        every { nodeManager.nodeDBbyNodeNum } returns emptyMap()

        manager = createManager(backgroundScope)
        advanceUntilIdle()

        radioConnectionState.value = ConnectionState.DeviceSleep
        advanceUntilIdle()

        assertEquals(
            ConnectionState.Disconnected,
            serviceRepository.connectionState.value,
            "State should be Disconnected when power saving is off",
        )
    }

    @Test
    fun `DeviceSleep behavior when power saving is on stays in DeviceSleep`() = runTest(testDispatcher) {
        // Power saving enabled
        val config = LocalConfig(power = Config.PowerConfig(is_power_saving = true))
        every { radioConfigRepository.localConfigFlow } returns flowOf(config)

        manager = createManager(backgroundScope)
        advanceUntilIdle()

        radioConnectionState.value = ConnectionState.DeviceSleep
        advanceUntilIdle()

        assertEquals(
            ConnectionState.DeviceSleep,
            serviceRepository.connectionState.value,
            "State should stay in DeviceSleep when power saving is on",
        )
    }

    @Test
    fun `onRadioConfigLoaded enqueues queued packets and sets time`() = runTest(testDispatcher) {
        manager = createManager(backgroundScope)
        val packetId = 456
        everySuspend { packetRepository.getQueuedPackets() } returns listOf(dataPacket)
        every { workerManager.enqueueSendMessage(any()) } returns Unit

        manager.onRadioConfigLoaded()
        advanceUntilIdle()

        verify { workerManager.enqueueSendMessage(packetId) }
    }

    @Test
    fun `onNodeDbReady starts MQTT and requests history`() = runTest(testDispatcher) {
        val moduleConfig =
            LocalModuleConfig(
                mqtt = ModuleConfig.MQTTConfig(enabled = true, proxy_to_client_enabled = true),
                store_forward = ModuleConfig.StoreForwardConfig(enabled = true),
            )
        moduleConfigFlow.value = moduleConfig
        everySuspend { commandSender.requestTelemetry(any(), any(), any()) } returns Unit
        every { nodeManager.myNodeNum } returns MutableStateFlow(123)
        every { mqttManager.startProxy(any(), any()) } returns Unit
        everySuspend { historyManager.requestHistoryReplay(any(), any(), any(), any()) } returns Unit
        every { nodeManager.getMyNodeInfo() } returns null

        manager = createManager(backgroundScope)
        manager.onNodeDbReady()
        advanceUntilIdle()

        verify { mqttManager.startProxy(true, true) }
        verifySuspend { historyManager.requestHistoryReplay(any(), any(), any(), any()) }
    }

    @Test
    fun `DeviceSleep timeout is capped at MAX_SLEEP_TIMEOUT_SECONDS for high ls_secs`() = runTest(testDispatcher) {
        // Router with ls_secs=3600 — previously this created a 3630s timeout.
        // With the cap, it should be clamped to 300s.
        val config =
            LocalConfig(
                power = Config.PowerConfig(is_power_saving = true, ls_secs = 3600),
                device = Config.DeviceConfig(role = Config.DeviceConfig.Role.ROUTER),
            )
        every { radioConfigRepository.localConfigFlow } returns flowOf(config)
        every { nodeManager.nodeDBbyNodeNum } returns emptyMap()

        manager = createManager(backgroundScope)
        advanceUntilIdle()

        // Transition to Connected then DeviceSleep
        radioConnectionState.value = ConnectionState.Connected
        advanceUntilIdle()
        radioConnectionState.value = ConnectionState.DeviceSleep
        advanceUntilIdle()

        assertEquals(
            ConnectionState.DeviceSleep,
            serviceRepository.connectionState.value,
            "Should be in DeviceSleep initially",
        )

        // Advance 300 seconds (the cap) + 1 second to trigger the timeout.
        advanceTimeBy(301_000L)

        assertEquals(
            ConnectionState.Disconnected,
            serviceRepository.connectionState.value,
            "Should transition to Disconnected after capped timeout (300s), not the raw 3630s",
        )
    }

    @Test
    fun `rapid state transitions are serialized by connectionMutex`() = runTest(testDispatcher) {
        // Power saving enabled so DeviceSleep is preserved (not mapped to Disconnected)
        val config = LocalConfig(power = Config.PowerConfig(is_power_saving = true))
        every { radioConfigRepository.localConfigFlow } returns flowOf(config)
        every { nodeManager.nodeDBbyNodeNum } returns emptyMap()

        // Record every state transition so we can verify ordering
        val observed = mutableListOf<ConnectionState>()
        every { serviceRepository.setConnectionState(any()) } calls
            { call ->
                val state = call.arg<ConnectionState>(0)
                observed.add(state)
                connectionStateFlow.value = state
            }

        manager = createManager(backgroundScope)
        advanceUntilIdle()

        // Rapid-fire: Connected -> DeviceSleep -> Disconnected without yielding between them.
        // Without the Mutex, the intermediate DeviceSleep could be missed or applied out of order.
        radioConnectionState.value = ConnectionState.Connected
        radioConnectionState.value = ConnectionState.DeviceSleep
        radioConnectionState.value = ConnectionState.Disconnected
        advanceUntilIdle()

        // Verify final state
        assertEquals(
            ConnectionState.Disconnected,
            serviceRepository.connectionState.value,
            "Final state should be Disconnected after rapid transitions",
        )

        // Verify that all intermediate states were observed in correct order.
        // Connected triggers handleConnected() which sets Connecting (handshake start),
        // then DeviceSleep, then Disconnected.
        assertEquals(
            listOf(ConnectionState.Connecting, ConnectionState.DeviceSleep, ConnectionState.Disconnected),
            observed,
            "State transitions should be serialized in order: Connecting -> DeviceSleep -> Disconnected",
        )
    }

    @Test
    fun `concurrent sleep-timeout and radio state change are serialized`() {
        val standardDispatcher = StandardTestDispatcher()
        runTest(standardDispatcher) {
            // Power saving enabled with a short ls_secs so the sleep timeout fires quickly
            val config = LocalConfig(power = Config.PowerConfig(is_power_saving = true, ls_secs = 1))
            every { radioConfigRepository.localConfigFlow } returns flowOf(config)
            every { nodeManager.nodeDBbyNodeNum } returns emptyMap()

            val observed = mutableListOf<ConnectionState>()
            every { serviceRepository.setConnectionState(any()) } calls
                { call ->
                    val state = call.arg<ConnectionState>(0)
                    observed.add(state)
                    connectionStateFlow.value = state
                }

            manager = createManager(backgroundScope)
            advanceUntilIdle()

            // Transition to Connected -> DeviceSleep to start the sleep timer
            radioConnectionState.value = ConnectionState.Connected
            advanceUntilIdle()
            radioConnectionState.value = ConnectionState.DeviceSleep
            advanceUntilIdle()

            observed.clear()

            // Before the sleep timeout fires, emit Connected from the radio (simulating device
            // waking up). Then let the timeout fire. The mutex ensures they don't race.
            radioConnectionState.value = ConnectionState.Connected
            // Advance past the sleep timeout (ls_secs=1 + 30s base = 31s)
            advanceTimeBy(32_000L)
            advanceUntilIdle()

            // The Connected transition should have cancelled the sleep timeout, so we should
            // end up in Connecting (from handleConnected), NOT Disconnected (from timeout).
            assertEquals(
                ConnectionState.Connecting,
                serviceRepository.connectionState.value,
                "Connected should cancel the sleep timeout; final state should be Connecting",
            )
        }
    }

    @Test
    fun `Stage 1 config stall after retry timeout triggers transport restart and ends Disconnected`() =
        runTest(testDispatcher) {
            manager = createManager(backgroundScope)
            // Disconnected -> Connected: handleConnected() sets Connecting, sends pre-handshake
            // heartbeat, and (after PRE_HANDSHAKE_SETTLE_MS=100ms) calls startConfigOnly() which
            // arms the Stage 1 stall guard (HANDSHAKE_TIMEOUT_STAGE1 = 30s).
            radioConnectionState.value = ConnectionState.Connected
            advanceTimeBy(200)
            advanceUntilIdle()

            // Pre-condition: Stage 1 is in flight — manager is Connecting and a ToRadio has been sent
            // (heartbeat + want_config_id). Use at-least-one here so the test isn't brittle on the
            // exact packet count.
            assertEquals(
                ConnectionState.Connecting,
                serviceRepository.connectionState.value,
                "Manager should be Connecting after radio Connected",
            )
            verify { packetHandler.sendToRadio(any<org.meshtastic.proto.ToRadio>()) }

            // Advance past HANDSHAKE_TIMEOUT_STAGE1 (30s) + HANDSHAKE_RETRY_TIMEOUT (15s) WITHOUT
            // any config arrival. The stall-retry-exceeded branch must fire: the production code
            // runs BOTH transitions inside one sibling recovery job — onConnectionChanged(Disconnected)
            // FIRST, then restartTransport() — so the fresh Connected emission from restartTransport
            // arrives with app-level state already Disconnected and is not ignored by the
            // redundant-Connecting guard in onConnectionChanged.
            advanceTimeBy(46_000L)
            advanceUntilIdle()

            verifySuspend(exactly(1)) { radioInterfaceService.restartTransport() }
            assertEquals(
                ConnectionState.Disconnected,
                serviceRepository.connectionState.value,
                "Stage 1 stall should end in Disconnected after restart is requested",
            )
        }

    @Test
    fun `Handshake stall recovery orders app disconnect before transport restart emissions`() =
        runTest(testDispatcher) {
            // This test locks in the ordering invariant of the stall-retry-exceeded recovery
            // sibling: onConnectionChanged(Disconnected) runs FIRST, then restartTransport().
            // We deliberately do NOT stub restartTransport() — the default mock no-op leaves it
            // as a pure boundary call so the sibling's two phases can be observed independently.
            //
            // After the stall fires and the sibling completes, we MANUALLY replay the
            // transport-level emissions that the real restartTransport() would produce:
            //   - DeviceSleep (onDisconnect(isPermanent=false) on the old transport)
            //   - Connected   (the new transport's onConnect callback)
            // Under the FIXED ordering, the fresh Connected arrives with app state already
            // Disconnected, bypasses the redundant-Connecting guard in onConnectionChanged,
            // and re-enters handleConnected → state returns to Connecting.
            // Under the BROKEN (old) ordering — restartTransport() BEFORE Disconnected — the
            // fresh Connected would arrive while app state was still Connecting, the redundant-
            // Connecting guard would drop it, and the state would never return to Connecting.
            //
            // Restructured to be deterministic on JVM CI: rather than relying on a stubbed
            // restartTransport() lambda whose StateFlow side-effect emissions race with the
            // flow collector under Mokkery, the test body itself drives the emissions in order.
            manager = createManager(backgroundScope)
            // Disconnected -> Connected: handleConnected() sets Connecting, sends pre-handshake
            // heartbeat, and (after PRE_HANDSHAKE_SETTLE_MS=100ms) calls startConfigOnly() which
            // arms the Stage 1 stall guard (HANDSHAKE_TIMEOUT_STAGE1 = 30s).
            radioConnectionState.value = ConnectionState.Connected
            advanceTimeBy(200)
            advanceUntilIdle()

            // Pre-condition: Stage 1 is in flight.
            assertEquals(
                ConnectionState.Connecting,
                serviceRepository.connectionState.value,
                "Manager should be Connecting after radio Connected",
            )

            // Advance past HANDSHAKE_TIMEOUT_STAGE1 (30s) + HANDSHAKE_RETRY_TIMEOUT (15s) WITHOUT
            // any config arrival. The stall-retry-exceeded branch fires the recovery sibling:
            // onConnectionChanged(Disconnected) FIRST, then restartTransport() (default mock
            // no-op, so nothing re-arms a stall guard and advanceUntilIdle is safe here).
            advanceTimeBy(46_000L)
            advanceUntilIdle()

            verifySuspend(exactly(1)) { radioInterfaceService.restartTransport() }
            assertEquals(
                ConnectionState.Disconnected,
                serviceRepository.connectionState.value,
                "Sibling must run onConnectionChanged(Disconnected) BEFORE restartTransport() — " +
                    "proves the app-level Disconnected transition landed",
            )

            // Manually replay the transport-level restart signals that the real restartTransport()
            // would emit. DeviceSleep corresponds to onDisconnect(isPermanent=false) on the old
            // transport; Connected corresponds to the new transport's onConnect callback. With
            // UnconfinedTestDispatcher each emission is collected synchronously inline, so no
            // advanceUntilIdle() is needed between them — and none is safe AFTER the Connected
            // emission, because handleConnected re-arms a fresh Stage 1 stall guard and
            // advanceUntilIdle would advance virtual time past it (and every subsequent re-arm),
            // looping the recovery and obscuring the single-shot ordering this test locks in.
            radioConnectionState.value = ConnectionState.DeviceSleep
            radioConnectionState.value = ConnectionState.Connected

            assertEquals(
                ConnectionState.Connecting,
                serviceRepository.connectionState.value,
                "Fresh Connected emission must re-enter handleConnected (NOT be ignored by the " +
                    "redundant-Connecting guard) because the app-level Disconnected transition " +
                    "already ran BEFORE restartTransport's transport cycle — this is the ordering " +
                    "invariant under test",
            )
        }

    @Test
    fun `Stage 2 node-info stall after retry timeout triggers transport restart`() = runTest(testDispatcher) {
        manager = createManager(backgroundScope)
        radioConnectionState.value = ConnectionState.Connected
        // Pre-handshake settle completes; Stage 1 stall guard armed.
        advanceTimeBy(200)
        advanceUntilIdle()

        // Drive the connection into Stage 2. In production this is done by the config-flow
        // manager once Stage 1 config arrives; here we invoke it directly. startNodeInfoOnly()
        // cancels the Stage 1 stall guard and arms Stage 2 (HANDSHAKE_TIMEOUT_STAGE2 = 60s).
        manager.startNodeInfoOnly()
        advanceUntilIdle()

        assertEquals(
            ConnectionState.Connecting,
            serviceRepository.connectionState.value,
            "Manager should still be Connecting entering Stage 2",
        )

        // Advance past HANDSHAKE_TIMEOUT_STAGE2 (60s) + HANDSHAKE_RETRY_TIMEOUT (15s) WITHOUT
        // invoking onNodeDbReady(). The stall-retry-exceeded branch must fire.
        advanceTimeBy(76_000L)
        advanceUntilIdle()

        verifySuspend(exactly(1)) { radioInterfaceService.restartTransport() }
        assertEquals(
            ConnectionState.Disconnected,
            serviceRepository.connectionState.value,
            "Stage 2 stall should end in Disconnected after restart is requested",
        )
    }

    @Test
    fun `Handshake completing before stall timeout does not trigger transport restart`() = runTest(testDispatcher) {
        // Stubs required by onNodeDbReady() (full handshake completion path).
        everySuspend { commandSender.requestTelemetry(any(), any(), any()) } returns Unit
        every { nodeManager.myNodeNum } returns MutableStateFlow(123)
        every { mqttManager.startProxy(any(), any()) } returns Unit
        everySuspend { historyManager.requestHistoryReplay(any(), any(), any(), any()) } returns Unit
        every { nodeManager.getMyNodeInfo() } returns null

        manager = createManager(backgroundScope)
        radioConnectionState.value = ConnectionState.Connected
        // Pre-handshake settle completes; Stage 1 stall guard armed.
        advanceTimeBy(200)
        advanceUntilIdle()

        // Simulate the full handshake completing (config arrives + NodeDB becomes ready).
        // onNodeDbReady() cancels handshakeTimeout, so the stall-retry-exceeded branch can
        // never run even if virtual time later crosses the stage windows.
        manager.onNodeDbReady()
        advanceUntilIdle()

        // Advance well past BOTH stage windows + retry (Stage 1: 30s+15s, Stage 2: 60s+15s).
        advanceTimeBy(120_000L)
        advanceUntilIdle()

        verifySuspend(exactly(0)) { radioInterfaceService.restartTransport() }
    }
}
