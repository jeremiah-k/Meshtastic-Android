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
package org.meshtastic.core.domain.usecase.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import okio.ByteString.Companion.toByteString
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.model.util.getChannelUrl
import org.meshtastic.core.repository.NodeRestartTracker
import org.meshtastic.core.testing.FakeRadioConfigRepository
import org.meshtastic.core.testing.FakeRadioController
import org.meshtastic.core.testing.FakeRadioInterfaceService
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config.BluetoothConfig
import org.meshtastic.proto.Config.DeviceConfig
import org.meshtastic.proto.Config.DisplayConfig
import org.meshtastic.proto.Config.LoRaConfig
import org.meshtastic.proto.Config.NetworkConfig
import org.meshtastic.proto.Config.PositionConfig
import org.meshtastic.proto.Config.PowerConfig
import org.meshtastic.proto.Config.SecurityConfig
import org.meshtastic.proto.DeviceProfile
import org.meshtastic.proto.LocalConfig
import org.meshtastic.proto.LocalModuleConfig
import org.meshtastic.proto.ModuleConfig.ExternalNotificationConfig
import org.meshtastic.proto.ModuleConfig.MQTTConfig
import org.meshtastic.proto.ModuleConfig.SerialConfig
import org.meshtastic.proto.User
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallProfileUseCaseTest {

    private lateinit var radioController: FakeRadioController
    private lateinit var radioInterfaceService: FakeRadioInterfaceService
    private lateinit var radioConfigRepository: FakeRadioConfigRepository
    private lateinit var restartTrackerScope: CoroutineScope
    private lateinit var restartTracker: NodeRestartTracker
    private lateinit var useCase: InstallProfileUseCase

    @BeforeTest
    fun setUp() {
        restartTrackerScope = CoroutineScope(SupervisorJob())
        radioController = FakeRadioController().apply { setConnectionState(ConnectionState.Connected) }
        radioInterfaceService =
            FakeRadioInterfaceService(restartTrackerScope).apply { setDeviceAddress("x00:11:22:33:44:55") }
        radioConfigRepository = FakeRadioConfigRepository()
        restartTracker = NodeRestartTracker(restartTrackerScope)
        useCase = InstallProfileUseCase(radioController, radioInterfaceService, radioConfigRepository, restartTracker)
    }

    @AfterTest
    fun tearDown() {
        restartTrackerScope.cancel()
    }

    @Test
    fun `empty profile performs no restart or writes`() = runTest {
        useCase(1234, DeviceProfile(), User(), isLocal = true)

        assertFalse(radioController.editSettingsCalled)
        assertTrue(radioController.adminOperations.isEmpty())
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `transaction on TCP completes without waiting for a transport departure`() = runTest {
        radioInterfaceService.setDeviceAddress("t192.0.2.1")
        val profile = DeviceProfile(config = LocalConfig(lora = LoRaConfig(region = LoRaConfig.RegionCode.US)))

        useCase(1234, profile, User(), isLocal = true)

        assertTrue(radioController.editSettingsCalled)
        assertEquals(ConnectionState.Connected, radioController.connectionState.value)
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `full profile commits ordinary settings before transport disruptive stages`() = runTest {
        val importedChannels =
            listOf(
                ChannelSettings(name = "Imported", psk = byteArrayOf(1).toByteString()),
                ChannelSettings(name = "Private", psk = byteArrayOf(2).toByteString()),
            )
        val channelUrl =
            ChannelSet(settings = importedChannels, lora_config = LoRaConfig(region = LoRaConfig.RegionCode.US))
                .getChannelUrl()
                .toString()
        radioConfigRepository.setChannelSet(
            ChannelSet(settings = listOf(ChannelSettings(name = "Old"), ChannelSettings(name = "Stale"))),
        )
        radioConfigRepository.setLocalConfigDirect(
            LocalConfig(lora = LoRaConfig(region = LoRaConfig.RegionCode.EU_868)),
        )

        radioController.onEditSettingsCommitted = { emitRestartCycle() }
        radioController.onStandaloneModuleConfig = { emitRestartCycle() }
        radioController.onStandaloneConfig = { emitRestartCycle() }

        val profile =
            DeviceProfile(
                long_name = "Full Node",
                short_name = "FULL",
                channel_url = channelUrl,
                config =
                LocalConfig(
                    device = DeviceConfig(),
                    position = PositionConfig(),
                    power = PowerConfig(),
                    network = NetworkConfig(),
                    display = DisplayConfig(),
                    lora = LoRaConfig(region = LoRaConfig.RegionCode.US),
                    bluetooth = BluetoothConfig(enabled = true),
                    security = SecurityConfig(),
                ),
                module_config =
                LocalModuleConfig(
                    mqtt = MQTTConfig(enabled = true),
                    serial = SerialConfig(enabled = true),
                    external_notification = ExternalNotificationConfig(enabled = true),
                ),
                fixed_position = org.meshtastic.proto.Position(latitude_i = 1, longitude_i = 2),
            )

        useCase(1234, profile, User(long_name = "Old"), isLocal = true)

        assertEquals(listOf("begin", "owner"), radioController.adminOperations.take(2))
        assertTrue(
            radioController.adminOperations.indexOf("commit") >
                radioController.adminOperations.indexOf("fixed-position"),
        )
        assertEquals(
            listOf(
                ExternalNotificationConfig(enabled = true),
                MQTTConfig(enabled = true),
                SerialConfig(enabled = true),
            ),
            radioController.moduleConfigs.mapNotNull { it.external_notification ?: it.mqtt ?: it.serial },
        )
        val commitIndex = radioController.adminOperations.indexOf("commit")
        val moduleOperationIndexes =
            radioController.adminOperations.mapIndexedNotNull { index, operation ->
                index.takeIf { operation.startsWith("module:") }
            }
        assertTrue(moduleOperationIndexes.first() < commitIndex)
        assertTrue(moduleOperationIndexes.drop(1).all { it > commitIndex })
        assertEquals(BluetoothConfig(enabled = true), radioController.localConfigs.last().bluetooth)
        assertEquals((0..7).toList(), radioController.localChannels.map(Channel::index))
        assertEquals(importedChannels, radioConfigRepository.currentChannelSet.settings)
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `bluetooth disable is the final stage and does not wait for an impossible reconnect`() = runTest {
        radioController.onEditSettingsCommitted = { emitRestartCycle() }
        radioController.onStandaloneConfig = {
            radioController.setConnectionState(ConnectionState.Disconnected)
            yield()
        }
        val profile =
            DeviceProfile(config = LocalConfig(device = DeviceConfig(), bluetooth = BluetoothConfig(enabled = false)))

        useCase(1234, profile, User(), isLocal = true)

        assertEquals(ConnectionState.Disconnected, radioController.connectionState.value)
        assertEquals(BluetoothConfig(enabled = false), radioController.localConfigs.last().bluetooth)
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `channel URL without profile LoRa restores its channel LoRa config`() = runTest {
        val channelLora = LoRaConfig(region = LoRaConfig.RegionCode.US, hop_limit = 4)
        val settings = listOf(ChannelSettings(name = "Imported", psk = byteArrayOf(1).toByteString()))
        val channelUrl = ChannelSet(settings = settings, lora_config = channelLora).getChannelUrl().toString()
        val profile = DeviceProfile(channel_url = channelUrl)
        radioConfigRepository.setLocalConfigDirect(
            LocalConfig(lora = LoRaConfig(region = LoRaConfig.RegionCode.EU_868)),
        )
        radioController.onEditSettingsCommitted = { emitRestartCycle() }

        useCase(1234, profile, User(), isLocal = true)

        assertEquals(channelLora, radioController.localConfigs.single().lora)
        assertEquals(settings, radioConfigRepository.currentChannelSet.settings)
    }

    @Test
    fun `owner fields fail before writes when current owner is unavailable`() = runTest {
        val profile = DeviceProfile(long_name = "Restored")

        assertFailsWith<IllegalArgumentException> { useCase(1234, profile, currentUser = null, isLocal = true) }

        assertTrue(radioController.adminOperations.isEmpty())
    }

    @Test
    fun `malformed channel URL fails before any device write`() = runTest {
        val profile = DeviceProfile(channel_url = "https://example.com/not-a-channel")

        assertFails { useCase(1234, profile, User(), isLocal = true) }

        assertTrue(radioController.adminOperations.isEmpty())
    }

    @Test
    fun `profile install rejects remote administration`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            useCase(1234, DeviceProfile(long_name = "Remote"), User(), isLocal = false)
        }

        assertTrue(radioController.adminOperations.isEmpty())
    }

    @Test
    fun `invoke installs is_unmessagable but never auto-installs is_licensed`() = runTest {
        radioController.onEditSettingsCommitted = { emitRestartCycle() }
        val profile = DeviceProfile(is_unmessagable = true, is_licensed = true)

        useCase(1234, profile, User(long_name = "Old"), isLocal = true)

        assertEquals(true, radioController.lastSetOwnerUser?.is_unmessagable)
        assertEquals(false, radioController.lastSetOwnerUser?.is_licensed)
    }

    private suspend fun emitRestartCycle() {
        radioController.setConnectionState(ConnectionState.Disconnected)
        yield()
        radioController.setConnectionState(ConnectionState.Connecting)
        yield()
        radioController.setConnectionState(ConnectionState.Connected)
        restartTracker.onConnected()
        yield()
    }
}
