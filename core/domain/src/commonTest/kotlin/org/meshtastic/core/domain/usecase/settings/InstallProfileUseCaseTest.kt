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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import okio.ByteString.Companion.toByteString
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.model.Node
import org.meshtastic.core.model.util.CHANNEL_REPLACEMENT_SLOT_COUNT
import org.meshtastic.core.model.util.MalformedMeshtasticUrlException
import org.meshtastic.core.model.util.getChannelUrl
import org.meshtastic.core.repository.NodeRestartTracker
import org.meshtastic.core.repository.PacketQueueRejectedException
import org.meshtastic.core.repository.RadioConfigRepository
import org.meshtastic.core.testing.FakeNodeRepository
import org.meshtastic.core.testing.FakeRadioConfigRepository
import org.meshtastic.core.testing.FakeRadioController
import org.meshtastic.core.testing.FakeRadioInterfaceService
import org.meshtastic.core.testing.TestDataFactory
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
import org.meshtastic.proto.ModuleConfig.MeshBeaconConfig
import org.meshtastic.proto.ModuleConfig.SerialConfig
import org.meshtastic.proto.ModuleConfig.TrafficManagementConfig
import org.meshtastic.proto.User
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallProfileUseCaseTest {

    private lateinit var radioController: FakeRadioController
    private lateinit var radioInterfaceService: FakeRadioInterfaceService
    private lateinit var radioConfigRepository: FakeRadioConfigRepository
    private lateinit var nodeRepository: FakeNodeRepository
    private lateinit var testDispatcher: TestDispatcher
    private lateinit var restartTrackerScope: CoroutineScope
    private lateinit var restartTracker: NodeRestartTracker
    private lateinit var useCase: InstallProfileUseCase

    @BeforeTest
    fun setUp() {
        testDispatcher = StandardTestDispatcher()
        restartTrackerScope = CoroutineScope(SupervisorJob() + testDispatcher)
        radioController = FakeRadioController().apply { setConnectionState(ConnectionState.Connected) }
        radioInterfaceService =
            FakeRadioInterfaceService(restartTrackerScope).apply { setDeviceAddress("x00:11:22:33:44:55") }
        radioConfigRepository = FakeRadioConfigRepository()
        nodeRepository =
            FakeNodeRepository().apply { setMyNodeInfo(TestDataFactory.createMyNodeInfo(myNodeNum = 1234)) }
        restartTracker = NodeRestartTracker(restartTrackerScope)
        useCase =
            InstallProfileUseCase(
                radioController,
                radioInterfaceService,
                radioConfigRepository,
                nodeRepository,
                restartTracker,
            )
    }

    @AfterTest
    fun tearDown() {
        restartTrackerScope.cancel()
    }

    @Test
    fun `empty profile performs no restart or writes`() = runTest(testDispatcher) {
        useCase(1234, DeviceProfile(), User(), isLocal = true)

        assertNoDeviceWrites()
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `concurrent profile installations are serialized without interleaved writes`() = runTest(testDispatcher) {
        val firstCommitReached = CompletableDeferred<Unit>()
        val releaseFirstCommit = CompletableDeferred<Unit>()
        var commitCount = 0
        radioController.onEditSettingsCommitted = {
            commitCount += 1
            if (commitCount == 1) {
                firstCommitReached.complete(Unit)
                releaseFirstCommit.await()
            }
            commitAndRestart()
        }

        val first = async { useCase(1234, DeviceProfile(long_name = "First"), User(), isLocal = true) }
        firstCommitReached.await()
        val second = async { useCase(1234, DeviceProfile(short_name = "Second"), User(), isLocal = true) }
        testScheduler.runCurrent()

        assertEquals(1, radioController.adminOperations.count { it == "begin" })
        assertEquals(listOf("begin", "owner", "commit"), radioController.adminOperations)

        releaseFirstCommit.complete(Unit)
        first.await()
        second.await()

        assertEquals(2, radioController.adminOperations.count { it == "begin" })
        assertEquals(
            listOf("begin", "owner", "commit", "begin", "owner", "commit"),
            radioController.adminOperations,
        )
    }

    @Test
    fun `cancellation while waiting for restart clears the expected-restart state`() = runTest(testDispatcher) {
        val departureObserved = CompletableDeferred<Unit>()
        radioController.onEditSettingsCommitted = {
            emitDeparture()
            departureObserved.complete(Unit)
        }
        val installation = launch { useCase(1234, DeviceProfile(long_name = "Cancelled"), User(), isLocal = true) }

        departureObserved.await()
        assertTrue(restartTracker.restartExpected.value)
        installation.cancelAndJoin()

        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `profile installation reports each planned stage`() = runTest(testDispatcher) {
        radioController.onEditSettingsCommitted = { commitAndRestart() }
        radioController.onStandaloneModuleConfig = { emitRestartCycle() }
        val progress = mutableListOf<ProfileInstallProgress>()
        val profile =
            DeviceProfile(
                long_name = "Updated node",
                module_config = LocalModuleConfig(mqtt = MQTTConfig(enabled = true)),
            )

        useCase(1234, profile, User(long_name = "Old node"), isLocal = true, progress::add)

        assertEquals(listOf(ProfileInstallProgress(1, 2), ProfileInstallProgress(2, 2)), progress)
    }

    @Test
    fun `profile snapshot wait is bounded before any device write`() = runTest(testDispatcher) {
        val stalledRepository =
            object : RadioConfigRepository by radioConfigRepository {
                override val localConfigFlow = MutableSharedFlow<LocalConfig>()
            }
        useCase = useCaseWith(stalledRepository)

        val result = async { runCatching { useCase(1234, DeviceProfile(), User(), isLocal = true) } }
        advanceTimeBy(10_001)

        val failure = result.await().exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure.message.orEmpty().contains("Timed out waiting"))
        assertNoDeviceWrites()
    }

    @Test
    fun `profile snapshot is rejected after connection lifecycle rollover`() = runTest(testDispatcher) {
        val snapshotStarted = CompletableDeferred<Unit>()
        val releaseSnapshot = CompletableDeferred<Unit>()
        useCase = useCaseWith(gatedConfigRepository(snapshotStarted, releaseSnapshot))

        val result = async { runCatching { useCase(1234, DeviceProfile(), User(), isLocal = true) } }
        snapshotStarted.await()
        radioController.setConnectionState(ConnectionState.Disconnected)
        radioController.setConnectionState(ConnectionState.Connected)
        releaseSnapshot.complete(Unit)

        val failure = result.await().exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure.message.orEmpty().contains("lifecycle changed"))
        assertNoDeviceWrites()
    }

    @Test
    fun `profile snapshot is rejected after transport selection changes`() = runTest(testDispatcher) {
        val snapshotStarted = CompletableDeferred<Unit>()
        val releaseSnapshot = CompletableDeferred<Unit>()
        useCase = useCaseWith(gatedConfigRepository(snapshotStarted, releaseSnapshot))

        val result = async { runCatching { useCase(1234, DeviceProfile(), User(), isLocal = true) } }
        snapshotStarted.await()
        radioInterfaceService.setDeviceAddress("t192.0.2.1")
        releaseSnapshot.complete(Unit)

        val failure = result.await().exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure.message.orEmpty().contains("transport changed"))
        assertNoDeviceWrites()
    }

    @Test
    fun `transaction on TCP waits for the firmware reboot and handshake`() = runTest(testDispatcher) {
        radioInterfaceService.setDeviceAddress("t192.0.2.1")
        radioController.onEditSettingsCommitted = { commitAndRestart() }
        val profile = DeviceProfile(config = LocalConfig(lora = LoRaConfig(region = LoRaConfig.RegionCode.US)))

        useCase(1234, profile, User(), isLocal = true)

        assertTrue(radioController.editSettingsCalled)
        assertEquals(ConnectionState.Connected, radioController.connectionState.value)
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `fast restart cycle cannot be lost to connection state conflation`() = runTest(testDispatcher) {
        radioController.onEditSettingsCommitted = {
            applyTransactionToDeviceState()
            emitFastRestartCycle()
        }
        val profile = DeviceProfile(config = LocalConfig(device = DeviceConfig()))
        val baselineEpochs = radioController.connectionEpochs.value

        useCase(1234, profile, User(), isLocal = true)

        assertEquals(ConnectionState.Connected, radioController.connectionState.value)
        assertEquals(baselineEpochs.departures + 1, radioController.connectionEpochs.value.departures)
        assertEquals(
            baselineEpochs.completedHandshakes + 1,
            radioController.connectionEpochs.value.completedHandshakes,
        )
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `reconnect must follow the latest departure rather than an older handshake`() = runTest(testDispatcher) {
        val allowLatestReconnect = CompletableDeferred<Unit>()
        radioController.onEditSettingsCommitted = {
            applyTransactionToDeviceState()
            emitFastRestartCycle()
            radioController.setConnectionState(ConnectionState.Disconnected)
            backgroundScope.launch {
                allowLatestReconnect.await()
                radioController.setConnectionState(ConnectionState.Connected)
            }
        }
        radioController.onStandaloneModuleConfig = { emitFastRestartCycle() }
        val profile =
            DeviceProfile(
                config = LocalConfig(device = DeviceConfig()),
                module_config = LocalModuleConfig(mqtt = MQTTConfig(enabled = true)),
            )

        val installation = async { useCase(1234, profile, User(), isLocal = true) }
        testScheduler.runCurrent()

        assertFalse(installation.isCompleted)
        assertTrue(radioController.moduleConfigWrites.isEmpty())

        allowLatestReconnect.complete(Unit)
        testScheduler.runCurrent()
        installation.await()

        assertEquals(MQTTConfig(enabled = true), radioController.allModuleConfigs.single().mqtt)
    }

    @Test
    fun `version-only remnants do not open an empty transaction`() = runTest(testDispatcher) {
        radioController.onStandaloneModuleConfig = { emitFastRestartCycle() }
        radioController.onStandaloneConfig = { emitDeparture() }
        val profile =
            DeviceProfile(
                config = LocalConfig(bluetooth = BluetoothConfig(enabled = false), version = 1),
                module_config = LocalModuleConfig(mqtt = MQTTConfig(enabled = true), version = 1),
            )

        val outcome = useCase(1234, profile, User(), isLocal = true)

        assertFalse(radioController.editSettingsCalled)
        assertEquals(listOf("module:update", "config:update"), radioController.adminOperations)
        // Disabling Bluetooth over BLE ends the session intentionally: reconnection is stopped, not retried.
        assertEquals(ProfileInstallOutcome.TransportHandedOff, outcome)
        assertTrue(radioInterfaceService.disconnectCalled)
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `unchanged profile config is skipped without restart or writes`() = runTest(testDispatcher) {
        val currentConfig =
            LocalConfig(
                device = DeviceConfig(),
                position = PositionConfig(),
                power = PowerConfig(),
                network = NetworkConfig(wifi_enabled = true, wifi_ssid = "existing"),
                display = DisplayConfig(compass_north_top = true),
                lora = LoRaConfig(region = LoRaConfig.RegionCode.US),
                bluetooth = BluetoothConfig(enabled = true),
                security = SecurityConfig(),
                version = 7,
            )
        val currentModuleConfig =
            LocalModuleConfig(
                mqtt = MQTTConfig(enabled = true),
                serial = SerialConfig(enabled = true),
                external_notification = ExternalNotificationConfig(enabled = true),
                traffic_management = TrafficManagementConfig(position_min_interval_secs = 30),
                mesh_beacon = MeshBeaconConfig(broadcast_message = "existing beacon"),
                version = 9,
            )
        radioConfigRepository.setLocalConfigDirect(currentConfig)
        radioConfigRepository.setLocalModuleConfigDirect(currentModuleConfig)

        val currentUser = User(long_name = "Current User", short_name = "CUR", is_unmessagable = true)
        useCase(
            1234,
            DeviceProfile(
                long_name = currentUser.long_name,
                short_name = currentUser.short_name,
                is_unmessagable = currentUser.is_unmessagable,
                config = currentConfig,
                module_config = currentModuleConfig,
            ),
            currentUser,
            isLocal = true,
        )

        assertNoDeviceWrites()
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `mixed profile sends only changed ordinary and transport settings`() = runTest(testDispatcher) {
        val currentConfig =
            LocalConfig(
                device = DeviceConfig(),
                display = DisplayConfig(compass_north_top = false),
                network = NetworkConfig(wifi_enabled = false),
                bluetooth = BluetoothConfig(enabled = true),
            )
        val currentModuleConfig =
            LocalModuleConfig(
                mqtt = MQTTConfig(enabled = true),
                serial = SerialConfig(enabled = false),
                external_notification = ExternalNotificationConfig(enabled = true),
                mesh_beacon = MeshBeaconConfig(broadcast_message = "old beacon"),
            )
        radioConfigRepository.setLocalConfigDirect(currentConfig)
        radioConfigRepository.setLocalModuleConfigDirect(currentModuleConfig)
        radioController.onEditSettingsCommitted = { commitAndRestart() }
        radioController.onStandaloneModuleConfig = { emitRestartCycle() }

        useCase(
            1234,
            DeviceProfile(
                config = currentConfig.copy(display = DisplayConfig(compass_north_top = true)),
                module_config =
                currentModuleConfig.copy(
                    serial = SerialConfig(enabled = true),
                    mesh_beacon = MeshBeaconConfig(broadcast_message = "new beacon"),
                ),
            ),
            User(),
            isLocal = true,
        )

        assertEquals(
            listOf(DisplayConfig(compass_north_top = true)),
            radioController.configWrites.mapNotNull { it.config.display },
        )
        assertEquals(
            listOf(MeshBeaconConfig(broadcast_message = "new beacon"), SerialConfig(enabled = true)),
            radioController.allModuleConfigs.mapNotNull { it.mesh_beacon ?: it.serial },
        )
        assertEquals(
            listOf("begin", "config:update", "module:update", "commit", "module:update"),
            radioController.adminOperations,
        )
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `traffic management profile is restored inside the ordinary transaction`() = runTest(testDispatcher) {
        val trafficManagement = TrafficManagementConfig(position_min_interval_secs = 30)
        radioController.onEditSettingsCommitted = { commitAndRestart() }

        useCase(
            1234,
            DeviceProfile(module_config = LocalModuleConfig(traffic_management = trafficManagement)),
            User(),
            isLocal = true,
        )

        assertTrue(radioController.editSettingsCalled)
        assertEquals(trafficManagement, radioController.localModuleConfigs.single().traffic_management)
        assertEquals(listOf("begin", "module:update", "commit"), radioController.adminOperations)
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `matching channel profile skips the transaction and restart`() = runTest(testDispatcher) {
        val settings =
            (0 until CHANNEL_REPLACEMENT_SLOT_COUNT).map { index ->
                ChannelSettings(name = "Channel $index", psk = byteArrayOf(index.toByte()).toByteString())
            }
        radioConfigRepository.setChannelSet(ChannelSet(settings = settings))
        val profile = DeviceProfile(channel_url = ChannelSet(settings = settings).getChannelUrl().toString())

        useCase(1234, profile, User(), isLocal = true)

        assertNoDeviceWrites()
        assertEquals(settings, radioConfigRepository.currentChannelSet.settings)
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `TCP profile restores network last after reconnecting from earlier stages`() = runTest(testDispatcher) {
        val mqtt = MQTTConfig(enabled = true)
        val serial = SerialConfig(enabled = true)
        val bluetooth = BluetoothConfig(enabled = true)
        val network = NetworkConfig(wifi_enabled = true, wifi_ssid = "new-network")
        val profile =
            DeviceProfile(
                config = LocalConfig(network = network, bluetooth = bluetooth),
                module_config = LocalModuleConfig(mqtt = mqtt, serial = serial),
            )
        radioInterfaceService.setDeviceAddress("t192.0.2.1")
        radioController.onStandaloneModuleConfig = { emitRestartCycle() }
        radioController.onEditSettingsCommitted = { emitDeparture() }

        useCase(1234, profile, User(), isLocal = true)

        assertEquals(listOf(mqtt, serial), radioController.allModuleConfigs.mapNotNull { it.mqtt ?: it.serial })
        assertEquals(bluetooth, radioController.configWrites.first().config.bluetooth)
        assertEquals(network, radioController.configWrites.last().config.network)
        assertEquals(
            listOf("module:update", "module:update", "begin", "config:update", "config:update", "commit"),
            radioController.adminOperations,
        )
        assertEquals(ConnectionState.Disconnected, radioController.connectionState.value)
        assertTrue(restartTracker.restartExpected.value)
    }

    @Test
    fun `USB profile restores serial last after reconnecting from earlier stages`() = runTest(testDispatcher) {
        val mqtt = MQTTConfig(enabled = true)
        val serial = SerialConfig(enabled = true)
        val bluetooth = BluetoothConfig(enabled = true)
        val network = NetworkConfig(wifi_enabled = true, wifi_ssid = "new-network")
        val profile =
            DeviceProfile(
                config = LocalConfig(bluetooth = bluetooth, network = network),
                module_config = LocalModuleConfig(mqtt = mqtt, serial = serial),
            )
        radioInterfaceService.setDeviceAddress("s/dev/ttyUSB0")
        radioController.onStandaloneModuleConfig = { config ->
            if (config.serial != null) emitDeparture() else emitRestartCycle()
        }
        radioController.onStandaloneConfig = { emitRestartCycle() }

        useCase(1234, profile, User(), isLocal = true)

        assertEquals(listOf(mqtt, serial), radioController.allModuleConfigs.mapNotNull { it.mqtt ?: it.serial })
        assertEquals(listOf(bluetooth, null), radioController.configWrites.map { it.config.bluetooth })
        assertEquals(listOf(null, network), radioController.configWrites.map { it.config.network })
        assertEquals(
            listOf("module:update", "config:update", "config:update", "module:update"),
            radioController.adminOperations,
        )
        assertEquals(ConnectionState.Disconnected, radioController.connectionState.value)
        assertTrue(restartTracker.restartExpected.value)
    }

    @Test
    fun `BLE terminal transport settings commit together before the connection ends`() = runTest(testDispatcher) {
        val bluetooth = BluetoothConfig(enabled = false)
        val network = NetworkConfig(wifi_enabled = true, wifi_ssid = "new-network")
        radioController.onEditSettingsCommitted = { emitDeparture() }

        val outcome =
            useCase(
                1234,
                DeviceProfile(config = LocalConfig(bluetooth = bluetooth, network = network)),
                User(),
                isLocal = true,
            )

        assertEquals(listOf(bluetooth, null), radioController.configWrites.map { it.config.bluetooth })
        assertEquals(listOf(null, network), radioController.configWrites.map { it.config.network })
        assertEquals(listOf("begin", "config:update", "config:update", "commit"), radioController.adminOperations)
        assertEquals(ConnectionState.Disconnected, radioController.connectionState.value)
        // The final write intentionally handed the active transport off. Production closes the expected-restart
        // window
        // before disconnecting so the UI shows the honest disconnected state instead of leaving a stale window
        // open.
        assertEquals(ProfileInstallOutcome.TransportHandedOff, outcome)
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `rejected standalone module config fails after the bounded departure wait`() = runTest(testDispatcher) {
        val failure =
            assertFailsWith<IllegalStateException> {
                useCase(
                    1234,
                    DeviceProfile(module_config = LocalModuleConfig(mqtt = MQTTConfig(enabled = true))),
                    User(),
                    isLocal = true,
                )
            }

        assertEquals("Device did not begin the mqtt profile restart", failure.message)
        assertEquals(20_000L, testScheduler.currentTime)
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `failed reconnect clears the expected restart window while disconnected`() = runTest(testDispatcher) {
        radioController.onStandaloneModuleConfig = { emitDeparture() }

        val failure =
            assertFailsWith<IllegalStateException> {
                useCase(
                    1234,
                    DeviceProfile(module_config = LocalModuleConfig(mqtt = MQTTConfig(enabled = true))),
                    User(),
                    isLocal = true,
                )
            }

        assertEquals("Device did not reconnect after the mqtt profile stage", failure.message)
        assertEquals(ConnectionState.Disconnected, radioController.connectionState.value)
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `full profile commits ordinary settings before transport disruptive stages`() = runTest(testDispatcher) {
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
        radioController.onEditSettingsCommitted = { commitAndRestart() }
        radioController.onStandaloneModuleConfig = {
            assertEquals(importedChannels, radioConfigRepository.currentChannelSet.settings)
            emitRestartCycle()
        }
        radioController.onStandaloneConfig = { emitRestartCycle() }

        useCase(1234, fullProfile(channelUrl), User(long_name = "Old"), isLocal = true)

        assertFullProfileWriteOrder()
        assertFullProfileResult(importedChannels)
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `transport stages retarget the current local identity after each reconnect`() = runTest(testDispatcher) {
        radioConfigRepository.setLocalConfigDirect(
            LocalConfig(security = SecurityConfig(private_key = ByteArray(32) { 7 }.toByteString())),
        )
        radioController.onEditSettingsCommitted = {
            nodeRepository.setMyNodeInfo(TestDataFactory.createMyNodeInfo(myNodeNum = 5678))
            applyTransactionToDeviceState()
            emitRestartCycle()
        }
        radioController.onStandaloneModuleConfig = { moduleConfig ->
            val nextNodeNum = if (moduleConfig.mqtt != null) 9012 else 3456
            nodeRepository.setMyNodeInfo(TestDataFactory.createMyNodeInfo(myNodeNum = nextNodeNum))
            emitRestartCycle()
        }
        radioController.onStandaloneConfig = { emitRestartCycle() }
        val profile =
            DeviceProfile(
                config =
                LocalConfig(
                    security = SecurityConfig(is_managed = true),
                    bluetooth = BluetoothConfig(enabled = true),
                ),
                module_config =
                LocalModuleConfig(mqtt = MQTTConfig(enabled = true), serial = SerialConfig(enabled = true)),
            )

        useCase(1234, profile, User(), isLocal = true)

        assertEquals(listOf(0), radioController.editSettingsDestinations)
        assertEquals(listOf(5678, 9012), radioController.moduleConfigDestinations.takeLast(2))
        assertEquals(3456, radioController.configWrites.last().destination)
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `security restore overlays the running private key when the imported key is invalid`() =
        runTest(testDispatcher) {
            radioConfigRepository.setLocalConfigDirect(
                LocalConfig(
                    security = SecurityConfig(private_key = securityKey(32, 7), public_key = securityKey(32, 9)),
                ),
            )
            radioController.onEditSettingsCommitted = { commitAndRestart() }
            val importedAdminKeys = listOf(securityKey(32, 1), securityKey(32, 2))
            val requested =
                SecurityConfig(
                    private_key = securityKey(16, 5),
                    is_managed = true,
                    admin_channel_enabled = true,
                    admin_key = importedAdminKeys,
                )
            val profile = DeviceProfile(config = LocalConfig(security = requested))

            useCase(1234, profile, User(), isLocal = true)

            val written = radioController.configWrites.single { it.config.security != null }.config.security!!
            assertEquals(securityKey(32, 7), written.private_key)
            assertEquals(securityKey(32, 9), written.public_key)
            assertEquals(true, written.is_managed)
            assertEquals(true, written.admin_channel_enabled)
            assertEquals(importedAdminKeys, written.admin_key)
            assertEquals(securityKey(16, 5), profile.config?.security?.private_key)
        }

    @Test
    fun `security restore sends an imported valid private key and clears the public key`() = runTest(testDispatcher) {
        radioConfigRepository.setLocalConfigDirect(
            LocalConfig(
                security = SecurityConfig(private_key = securityKey(32, 7), public_key = securityKey(32, 9)),
            ),
        )
        radioController.onEditSettingsCommitted = { commitAndRestart() }
        val profile =
            DeviceProfile(
                config =
                LocalConfig(
                    security =
                    SecurityConfig(
                        private_key = securityKey(32, 2),
                        public_key = securityKey(32, 8),
                        is_managed = true,
                        packet_signature_policy =
                        SecurityConfig.PacketSignaturePolicy.PACKET_SIGNATURE_POLICY_BALANCED,
                    ),
                ),
            )

        useCase(1234, profile, User(), isLocal = true)

        val written = radioController.configWrites.single { it.config.security != null }.config.security!!
        assertEquals(securityKey(32, 2), written.private_key)
        assertEquals(securityKey(0, 0), written.public_key)
        assertEquals(true, written.is_managed)
        assertEquals(
            SecurityConfig.PacketSignaturePolicy.PACKET_SIGNATURE_POLICY_BALANCED,
            written.packet_signature_policy,
        )
    }

    @Test
    fun `security restore is skipped when the overlaid section matches the running config`() = runTest(testDispatcher) {
        radioConfigRepository.setLocalConfigDirect(
            LocalConfig(security = SecurityConfig(private_key = securityKey(32, 7), is_managed = true)),
        )
        val profile =
            DeviceProfile(
                config = LocalConfig(security = SecurityConfig(private_key = securityKey(16, 5), is_managed = true)),
            )

        useCase(1234, profile, User(), isLocal = true)

        assertNoDeviceWrites()
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `security restore is not written when the profile has no security section`() = runTest(testDispatcher) {
        radioConfigRepository.setLocalConfigDirect(
            LocalConfig(security = SecurityConfig(private_key = securityKey(32, 7))),
        )
        radioController.onEditSettingsCommitted = { commitAndRestart() }
        val profile = DeviceProfile(config = LocalConfig(lora = LoRaConfig(region = LoRaConfig.RegionCode.US)))

        useCase(1234, profile, User(), isLocal = true)

        assertTrue(radioController.configWrites.none { it.config.security != null })
    }

    @Test
    fun `security restore sends requested security unchanged when neither side has a valid private key`() =
        runTest(testDispatcher) {
            radioConfigRepository.setLocalConfigDirect(
                LocalConfig(
                    security = SecurityConfig(private_key = securityKey(16, 6), public_key = securityKey(31, 7)),
                ),
            )
            radioController.onEditSettingsCommitted = { commitAndRestart() }
            val requested =
                SecurityConfig(
                    private_key = securityKey(31, 1),
                    public_key = securityKey(16, 2),
                    is_managed = true,
                    serial_enabled = false,
                    admin_channel_enabled = false,
                    packet_signature_policy = SecurityConfig.PacketSignaturePolicy.PACKET_SIGNATURE_POLICY_STRICT,
                )
            val profile = DeviceProfile(config = LocalConfig(security = requested))

            useCase(1234, profile, User(), isLocal = true)

            assertEquals(requested, radioController.configWrites.single { it.config.security != null }.config.security)
        }

    @Test
    fun `profile install rejects a stale local destination before writes`() = runTest(testDispatcher) {
        nodeRepository.setMyNodeInfo(TestDataFactory.createMyNodeInfo(myNodeNum = 5678))

        assertFailsWith<IllegalArgumentException> {
            useCase(1234, DeviceProfile(long_name = "Stale"), User(), isLocal = true)
        }

        assertNoDeviceWrites()
    }

    @Test
    fun `bluetooth disable is the final stage and does not wait for an impossible reconnect`() =
        runTest(testDispatcher) {
            radioController.onEditSettingsCommitted = { commitAndRestart() }
            radioController.onStandaloneConfig = {
                radioController.setConnectionState(ConnectionState.Disconnected)
                yield()
            }
            val profile =
                DeviceProfile(
                    config = LocalConfig(device = DeviceConfig(), bluetooth = BluetoothConfig(enabled = false)),
                )

            val outcome = useCase(1234, profile, User(), isLocal = true)

            assertEquals(ProfileInstallOutcome.TransportHandedOff, outcome)
            assertTrue(radioInterfaceService.disconnectCalled)
            assertEquals(ConnectionState.Disconnected, radioController.connectionState.value)
            assertEquals(BluetoothConfig(enabled = false), radioController.configWrites.last().config.bluetooth)
            assertFalse(restartTracker.restartExpected.value)
        }

    @Test
    fun `unchanged explicit profile LoRa still overrides channel URL LoRa`() = runTest(testDispatcher) {
        val currentLora = LoRaConfig(region = LoRaConfig.RegionCode.US, hop_limit = 3)
        val channelLora = LoRaConfig(region = LoRaConfig.RegionCode.EU_868, hop_limit = 5)
        val settings = listOf(ChannelSettings(name = "Imported", psk = byteArrayOf(1).toByteString()))
        val channelUrl = ChannelSet(settings = settings, lora_config = channelLora).getChannelUrl().toString()
        radioConfigRepository.setLocalConfigDirect(LocalConfig(lora = currentLora))
        radioController.onEditSettingsCommitted = { commitAndRestart() }

        useCase(
            1234,
            DeviceProfile(config = LocalConfig(lora = currentLora), channel_url = channelUrl),
            User(),
            isLocal = true,
        )

        assertTrue(radioController.configWrites.none { it.config.lora != null })
        assertEquals(
            (0 until CHANNEL_REPLACEMENT_SLOT_COUNT).toList(),
            radioController.localChannels.map(Channel::index),
        )
        assertEquals(settings, radioConfigRepository.currentChannelSet.settings)
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `changed explicit profile LoRa is written exactly once when a channel URL is restored`() =
        runTest(testDispatcher) {
            val currentLora = LoRaConfig(region = LoRaConfig.RegionCode.EU_868, hop_limit = 3)
            val profileLora = LoRaConfig(region = LoRaConfig.RegionCode.US, hop_limit = 5)
            val channelLora = LoRaConfig(region = LoRaConfig.RegionCode.ANZ, hop_limit = 2)
            val settings = listOf(ChannelSettings(name = "Imported", psk = byteArrayOf(1).toByteString()))
            val channelUrl = ChannelSet(settings = settings, lora_config = channelLora).getChannelUrl().toString()
            radioConfigRepository.setLocalConfigDirect(LocalConfig(lora = currentLora))
            radioController.onEditSettingsCommitted = { commitAndRestart() }

            useCase(
                1234,
                DeviceProfile(config = LocalConfig(lora = profileLora), channel_url = channelUrl),
                User(),
                isLocal = true,
            )

            assertEquals(listOf(profileLora), radioController.configWrites.mapNotNull { it.config.lora })
            assertEquals(settings, radioConfigRepository.currentChannelSet.settings)
        }

    @Test
    fun `channel URL without profile LoRa restores its channel LoRa config`() = runTest(testDispatcher) {
        val channelLora = LoRaConfig(region = LoRaConfig.RegionCode.US, hop_limit = 4)
        val settings = listOf(ChannelSettings(name = "Imported", psk = byteArrayOf(1).toByteString()))
        val channelUrl = ChannelSet(settings = settings, lora_config = channelLora).getChannelUrl().toString()
        val profile = DeviceProfile(channel_url = channelUrl)
        radioConfigRepository.setLocalConfigDirect(
            LocalConfig(lora = LoRaConfig(region = LoRaConfig.RegionCode.EU_868)),
        )
        radioController.onEditSettingsCommitted = { commitAndRestart() }

        useCase(1234, profile, User(), isLocal = true)

        assertEquals(channelLora, radioController.configWrites.single().config.lora)
        assertEquals(settings, radioConfigRepository.currentChannelSet.settings)
    }

    @Test
    fun `owner fields fail before writes when current owner is unavailable`() = runTest(testDispatcher) {
        val profile = DeviceProfile(long_name = "Restored")

        assertFailsWith<IllegalArgumentException> { useCase(1234, profile, currentUser = null, isLocal = true) }

        assertNoDeviceWrites()
    }

    @Test
    fun `malformed channel URL fails before any device write`() = runTest(testDispatcher) {
        val profile = DeviceProfile(channel_url = "https://example.com/not-a-channel")

        assertFailsWith<MalformedMeshtasticUrlException> { useCase(1234, profile, User(), isLocal = true) }

        assertNoDeviceWrites()
    }

    @Test
    fun `channel URL without payload fails before any device write`() = runTest(testDispatcher) {
        val profile = DeviceProfile(channel_url = ChannelSet().getChannelUrl().toString())

        assertFailsWith<MalformedMeshtasticUrlException> { useCase(1234, profile, User(), isLocal = true) }

        assertNoDeviceWrites()
    }

    @Test
    fun `profile install rejects remote administration`() = runTest(testDispatcher) {
        assertFailsWith<IllegalArgumentException> {
            useCase(1234, DeviceProfile(long_name = "Remote"), User(), isLocal = false)
        }

        assertNoDeviceWrites()
    }

    @Test
    fun `invoke installs is_unmessagable but never auto-installs is_licensed`() = runTest(testDispatcher) {
        radioController.onEditSettingsCommitted = { commitAndRestart() }
        val profile = DeviceProfile(is_unmessagable = true, is_licensed = true)

        useCase(1234, profile, User(long_name = "Old"), isLocal = true)

        assertEquals(true, radioController.lastSetOwnerUser?.is_unmessagable)
        assertEquals(false, radioController.lastSetOwnerUser?.is_licensed)
    }

    @Test
    fun `fixed position queue rejection aborts profile installation without committing cache`() =
        runTest(testDispatcher) {
            val rejection = PacketQueueRejectedException("Fixed position")
            radioController.onSetFixedPosition = { _, _ -> throw rejection }
            val profile = DeviceProfile(fixed_position = org.meshtastic.proto.Position(latitude_i = 1, longitude_i = 1))

            assertFailsWith<PacketQueueRejectedException> { useCase(1234, profile, User(), isLocal = true) }

            assertTrue(radioController.localChannels.isEmpty())
            assertFalse(restartTracker.restartExpected.value)
        }

    @Test
    fun `crash-reverted transaction is retried once and fails before transport stages`() = runTest(testDispatcher) {
        // The device departs and comes back, but boots into its pre-transaction state: the interrupted save was
        // voided. Nothing is applied to the reported device state across the whole handshake.
        radioController.onEditSettingsCommitted = { emitRestartCycle() }
        val profile =
            DeviceProfile(
                config = LocalConfig(lora = LoRaConfig(region = LoRaConfig.RegionCode.US)),
                module_config = LocalModuleConfig(mqtt = MQTTConfig(enabled = true)),
            )

        val failure = assertFailsWith<IllegalStateException> { useCase(1234, profile, User(), isLocal = true) }

        assertEquals("The device did not retain the restored device profile transaction", failure.message)
        // Exactly one bounded retry of the transaction stage, then an explicit failure.
        assertEquals(2, radioController.adminOperations.count { it == "begin" })
        // Transport-sensitive stages never run on the reverted device.
        assertTrue(radioController.moduleConfigWrites.none { it.config.mqtt != null })
        assertTrue(radioController.configWrites.none { it.config.network != null })
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `retained transaction is reported installed without a retry`() = runTest(testDispatcher) {
        radioController.onEditSettingsCommitted = { commitAndRestart() }
        val profile = DeviceProfile(config = LocalConfig(lora = LoRaConfig(region = LoRaConfig.RegionCode.US)))

        val outcome = useCase(1234, profile, User(), isLocal = true)

        assertEquals(ProfileInstallOutcome.Completed, outcome)
        assertEquals(1, radioController.adminOperations.count { it == "begin" })
        assertTrue(radioController.reboots.isEmpty())
        assertTrue(radioController.configWrites.any { it.config.lora?.region == LoRaConfig.RegionCode.US })
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `healthy transaction requests an explicit reboot when commit does not reboot`() = runTest(testDispatcher) {
        // Restore-stack firmware saves at commit and only restarts when told to; without the explicit reboot the
        // stage would sit until the departure timeout.
        radioController.onEditSettingsCommitted = {}
        radioController.onRebooted = {
            applyTransactionToDeviceState()
            emitRestartCycle()
        }
        val profile = DeviceProfile(config = LocalConfig(lora = LoRaConfig(region = LoRaConfig.RegionCode.US)))

        val outcome = useCase(1234, profile, User(), isLocal = true)

        assertEquals(ProfileInstallOutcome.Completed, outcome)
        assertEquals(listOf(1234), radioController.reboots)
        assertEquals(1, radioController.adminOperations.count { it == "begin" })
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `intentional WiFi handoff over BLE stops automatic reconnection with a terminal outcome`() =
        runTest(testDispatcher) {
            // Enabling Wi-Fi on ESP32-classic firmware releases the Bluetooth controller at the next boot, so the
            // radio never advertises on this transport again. The installer must end the session deliberately
            // instead of leaving unbounded BLE retries running.
            radioController.onStandaloneConfig = { emitDeparture() }
            val profile =
                DeviceProfile(config = LocalConfig(network = NetworkConfig(wifi_enabled = true, wifi_ssid = "home")))

            val outcome = useCase(1234, profile, User(), isLocal = true)

            assertEquals(ProfileInstallOutcome.TransportHandedOff, outcome)
            assertTrue(radioInterfaceService.disconnectCalled)
            assertFalse(radioController.editSettingsCalled)
            assertEquals(ConnectionState.Disconnected, radioController.connectionState.value)
            assertFalse(restartTracker.restartExpected.value)
        }

    private fun useCaseWith(repository: RadioConfigRepository) =
        InstallProfileUseCase(radioController, radioInterfaceService, repository, nodeRepository, restartTracker)

    private fun gatedConfigRepository(started: CompletableDeferred<Unit>, release: CompletableDeferred<Unit>) =
        object : RadioConfigRepository by radioConfigRepository {
            override val localConfigFlow = flow {
                started.complete(Unit)
                release.await()
                emit(LocalConfig())
            }
        }

    private fun fullProfile(channelUrl: String) = DeviceProfile(
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
            mesh_beacon = MeshBeaconConfig(broadcast_message = "restored beacon"),
        ),
        fixed_position = org.meshtastic.proto.Position(latitude_i = 1, longitude_i = 2),
    )

    private fun assertFullProfileWriteOrder() {
        assertEquals(listOf("begin", "owner"), radioController.adminOperations.take(2))
        val commitIndex = radioController.adminOperations.indexOf("commit")
        assertTrue(commitIndex > radioController.adminOperations.indexOf("fixed-position"))
        val moduleOperationIndexes =
            radioController.adminOperations.mapIndexedNotNull { index, operation ->
                index.takeIf { operation.startsWith("module:") }
            }
        assertEquals(radioController.allModuleConfigs.size, moduleOperationIndexes.size)
        val firstTransportSensitiveModuleIndex =
            radioController.allModuleConfigs.indexOfFirst { it.mqtt != null || it.serial != null }
        assertTrue(firstTransportSensitiveModuleIndex > 0)
        assertTrue(moduleOperationIndexes.take(firstTransportSensitiveModuleIndex).all { it < commitIndex })
        assertTrue(moduleOperationIndexes.drop(firstTransportSensitiveModuleIndex).all { it > commitIndex })
    }

    private fun assertFullProfileResult(importedChannels: List<ChannelSettings>) {
        assertEquals(
            listOf(
                ExternalNotificationConfig(enabled = true),
                MeshBeaconConfig(broadcast_message = "restored beacon"),
                MQTTConfig(enabled = true),
                SerialConfig(enabled = true),
            ),
            radioController.allModuleConfigs.mapNotNull {
                it.external_notification ?: it.mesh_beacon ?: it.mqtt ?: it.serial
            },
        )
        assertEquals(
            BluetoothConfig(enabled = true),
            radioController.configWrites.mapNotNull { it.config.bluetooth }.last(),
        )
        assertEquals(NetworkConfig(), radioController.configWrites.mapNotNull { it.config.network }.last())
        assertEquals(1e-7, radioController.fixedPositions.single().latitude, absoluteTolerance = 1e-12)
        assertEquals(2e-7, radioController.fixedPositions.single().longitude, absoluteTolerance = 1e-12)
        assertEquals(
            (0 until CHANNEL_REPLACEMENT_SLOT_COUNT).toList(),
            radioController.localChannels.map(Channel::index),
        )
        assertEquals(importedChannels, radioConfigRepository.currentChannelSet.settings)
    }

    private fun assertNoDeviceWrites() {
        assertFalse(radioController.editSettingsCalled)
        assertTrue(radioController.adminOperations.isEmpty())
        assertTrue(radioController.configWrites.isEmpty())
        assertTrue(radioController.localChannels.isEmpty())
        assertTrue(radioController.allModuleConfigs.isEmpty())
        assertTrue(radioController.fixedPositions.isEmpty())
        assertEquals(null, radioController.lastSetOwnerUser)
    }

    /**
     * Models the device accepting the transaction: staged writes become the device state that the post-restart
     * handshake reports. Tests that leave the device state unchanged model a crash-reverted transaction instead.
     */
    private suspend fun applyTransactionToDeviceState() {
        var config = radioConfigRepository.currentLocalConfig
        radioController.configWrites
            .filter { it.destination == null }
            .forEach { write ->
                config =
                    config.copy(
                        device = write.config.device ?: config.device,
                        position = write.config.position ?: config.position,
                        power = write.config.power ?: config.power,
                        network = write.config.network ?: config.network,
                        display = write.config.display ?: config.display,
                        lora = write.config.lora ?: config.lora,
                        bluetooth = write.config.bluetooth ?: config.bluetooth,
                        security = write.config.security ?: config.security,
                    )
            }
        radioConfigRepository.setLocalConfigDirect(config)
        var moduleConfig = radioConfigRepository.currentModuleConfig
        radioController.moduleConfigWrites
            .filter { it.destination == null }
            .forEach { write -> moduleConfig = moduleConfig.updatedWithModuleConfig(write.config) }
        radioConfigRepository.setLocalModuleConfigDirect(moduleConfig)
        radioController.localChannels.forEach { channel -> radioConfigRepository.updateChannelSettings(channel) }
        radioController.ownerWrites
            .lastOrNull()
            ?.takeIf { it.destination == null }
            ?.let { write ->
                nodeRepository.myNodeInfo.value?.myNodeNum?.let { num ->
                    nodeRepository.upsert(Node(num = num, user = write.user))
                }
            }
    }

    /** A transaction the device accepted: commit, state application, restart, and a fresh handshake. */
    private suspend fun commitAndRestart() {
        applyTransactionToDeviceState()
        emitRestartCycle()
    }

    private suspend fun emitRestartCycle() {
        radioController.setConnectionState(ConnectionState.Disconnected)
        yield()
        radioController.setConnectionState(ConnectionState.Connecting)
        yield()
        radioController.setConnectionState(ConnectionState.Connected)
        yield()
    }

    private fun emitFastRestartCycle() {
        radioController.setConnectionState(ConnectionState.Disconnected)
        radioController.setConnectionState(ConnectionState.Connecting)
        radioController.setConnectionState(ConnectionState.Connected)
    }

    private suspend fun emitDeparture() {
        radioController.setConnectionState(ConnectionState.Disconnected)
        yield()
    }

    /** Deterministic single-byte fills keep raw key material out of test sources. */
    private fun securityKey(size: Int, fill: Byte) = ByteArray(size) { fill }.toByteString()
}
