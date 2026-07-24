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

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.core.annotation.Single
import org.meshtastic.core.common.util.CommonUri
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.model.DeviceType
import org.meshtastic.core.model.Position
import org.meshtastic.core.model.util.CHANNEL_REPLACEMENT_SLOT_COUNT
import org.meshtastic.core.model.util.getChannelReplacementList
import org.meshtastic.core.model.util.normalizeReplacementSettings
import org.meshtastic.core.model.util.toChannelSet
import org.meshtastic.core.repository.AdminEditScope
import org.meshtastic.core.repository.NodeRestartTracker
import org.meshtastic.core.repository.RadioConfigRepository
import org.meshtastic.core.repository.RadioController
import org.meshtastic.core.repository.RadioInterfaceService
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceProfile
import org.meshtastic.proto.LocalConfig
import org.meshtastic.proto.LocalModuleConfig
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.User
import kotlin.time.Duration.Companion.seconds

/** Installs a local device profile using firmware-compatible, restart-aware phases. */
@Single
open class InstallProfileUseCase
constructor(
    private val radioController: RadioController,
    private val radioInterfaceService: RadioInterfaceService,
    private val radioConfigRepository: RadioConfigRepository,
    private val nodeRestartTracker: NodeRestartTracker,
) {
    private val installMutex = Mutex()

    /**
     * Installs [profile] onto the locally connected radio at [destNum].
     *
     * Firmware edit transactions defer normal config persistence until `commit_edit_settings`, but MQTT and Serial
     * configuration deliberately disable Bluetooth as soon as each command is processed. Those transport-disruptive
     * commands therefore cannot be sent inside the transaction: the link can disappear before the remaining writes and
     * commit reach the device. Bluetooth configuration has the same terminal-stage constraint when it disables the
     * transport used for the restore.
     *
     * The profile is applied in this order:
     * 1. owner, channels, ordinary config, fixed position, and non-disruptive modules in one edit transaction;
     * 2. MQTT as a standalone restart stage;
     * 3. Serial as a standalone restart stage;
     * 4. Bluetooth last, so disabling Bluetooth cannot strand any remaining profile writes.
     *
     * Every restart-causing stage observes a complete connection departure before continuing. Stages that need another
     * write also wait for the post-reboot application handshake to return to [ConnectionState.Connected].
     */
    open suspend operator fun invoke(destNum: Int, profile: DeviceProfile, currentUser: User?, isLocal: Boolean) =
        installMutex.withLock {
            require(isLocal) { "Device profiles can only be installed on the locally connected node" }
            require(radioController.connectionState.value is ConnectionState.Connected) {
                "A connected local node is required to install a device profile"
            }
            val activeTransport =
                checkNotNull(radioInterfaceService.getDeviceAddress()?.let(DeviceType::fromAddress)) {
                    "The connected node transport is unavailable"
                }

            validateOwnerRestore(profile, currentUser)
            val channelRestore = prepareChannelRestore(profile)
            val transactionalModuleConfig = profile.module_config.withoutTransportDisruptiveModules()
            val transactionalConfig = profile.config.withoutBluetooth()
            val hasTransactionalWrites =
                hasOwnerWrite(profile) ||
                    transactionalConfig != null ||
                    profile.fixed_position != null ||
                    transactionalModuleConfig != null ||
                    channelRestore != null

            if (hasTransactionalWrites) {
                // commit_edit_settings disables Bluetooth but does not reboot. BLE must complete a reconnect handshake;
                // TCP and USB remain usable, so waiting for a departure on those transports would time out a valid
                // import.
                val transactionInterruptsTransport = activeTransport == DeviceType.BLE
                runInstallStage(
                    stage = ProfileInstallStage.TRANSACTION,
                    expectDeparture = transactionInterruptsTransport,
                    expectReconnect = transactionInterruptsTransport,
                ) {
                    radioController.editSettings(destNum) {
                        installOwner(profile, currentUser)
                        installConfig(transactionalConfig)
                        installChannels(channelRestore)
                        installFixedPosition(profile.fixed_position)
                        installModuleConfig(transactionalModuleConfig)
                    }
                }
                channelRestore?.let { restore ->
                    withContext(NonCancellable) { radioConfigRepository.replaceAllSettings(restore.normalizedSettings) }
                }
            }

            profile.module_config?.mqtt?.let { mqtt ->
                runInstallStage(ProfileInstallStage.MQTT, expectDeparture = true, expectReconnect = true) {
                    radioController.setModuleConfig(
                        destNum,
                        ModuleConfig(mqtt = mqtt),
                        radioController.generatePacketId(),
                    )
                }
            }
            profile.module_config?.serial?.let { serial ->
                runInstallStage(ProfileInstallStage.SERIAL, expectDeparture = true, expectReconnect = true) {
                    radioController.setModuleConfig(
                        destNum,
                        ModuleConfig(serial = serial),
                        radioController.generatePacketId(),
                    )
                }
            }
            profile.config?.bluetooth?.let { bluetooth ->
                runInstallStage(
                    stage = ProfileInstallStage.BLUETOOTH,
                    expectDeparture = true,
                    expectReconnect = bluetooth.enabled,
                ) {
                    radioController.setConfig(
                        destNum,
                        Config(bluetooth = bluetooth),
                        radioController.generatePacketId(),
                    )
                }
            }
        }

    private suspend fun prepareChannelRestore(profile: DeviceProfile): ChannelRestore? {
        val channelUrl = profile.channel_url ?: return null
        val channelSet = CommonUri.parse(channelUrl).toChannelSet()
        val currentLora = radioConfigRepository.localConfigFlow.first().lora
        val identityLora = profile.config?.lora ?: channelSet.lora_config ?: currentLora
        val normalizedSettings = normalizeReplacementSettings(channelSet.settings, identityLora)
        require(normalizedSettings.size <= CHANNEL_REPLACEMENT_SLOT_COUNT) {
            "Imported channel set exceeds supported channel slot count"
        }
        val currentSettings = radioConfigRepository.channelSetFlow.first().settings
        val writes =
            getChannelReplacementList(
                new = normalizedSettings,
                currentSettings = currentSettings,
                minimumSlotCount = CHANNEL_REPLACEMENT_SLOT_COUNT,
                maximumSlotCount = CHANNEL_REPLACEMENT_SLOT_COUNT,
            )
        val loraConfig = channelSet.lora_config?.takeIf { profile.config?.lora == null && it != currentLora }
        return ChannelRestore(writes = writes, normalizedSettings = normalizedSettings, loraConfig = loraConfig)
    }

    private suspend fun runInstallStage(
        stage: ProfileInstallStage,
        expectDeparture: Boolean,
        expectReconnect: Boolean,
        action: suspend () -> Unit,
    ) {
        require(!expectReconnect || expectDeparture) { "A reconnect cannot be required without a departure" }
        Logger.i { "Installing device profile stage=${stage.logName}" }
        if (!expectDeparture) {
            action()
            Logger.i { "Installed device profile stage=${stage.logName}" }
            return
        }

        runRestartingStage(stage, expectReconnect, action)
    }

    private suspend fun runRestartingStage(
        stage: ProfileInstallStage,
        expectReconnect: Boolean,
        action: suspend () -> Unit,
    ) = coroutineScope {
        nodeRestartTracker.expectRestart()
        val restartObserver =
            async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(PROFILE_STAGE_TIMEOUT) {
                    radioController.connectionState.dropWhile { it is ConnectionState.Connected }.first()
                    if (expectReconnect) {
                        radioController.connectionState.first { it is ConnectionState.Connected }
                    }
                }
            }
        var completed = false
        try {
            action()
            restartObserver.await()
            nodeRestartTracker.onConnected()
            completed = true
            Logger.i { "Installed device profile stage=${stage.logName}" }
        } finally {
            restartObserver.cancel()
            if (!completed && radioController.connectionState.value is ConnectionState.Connected) {
                nodeRestartTracker.onConnected()
            }
        }
    }

    private fun validateOwnerRestore(profile: DeviceProfile, currentUser: User?) {
        require(!hasOwnerWrite(profile) || currentUser != null) {
            "The connected node owner must be loaded before restoring owner fields"
        }
    }

    private fun hasOwnerWrite(profile: DeviceProfile): Boolean =
        profile.long_name != null || profile.short_name != null || profile.is_unmessagable != null

    // is_licensed is deliberately not installed here: enabling ham mode is a dedicated onboarding flow
    // (set_ham_mode — rewrites the owner, disables encryption, applies tx power/frequency) that a plain
    // set_owner would bypass, leaving the radio flagged licensed without those required side effects.
    private suspend fun AdminEditScope.installOwner(profile: DeviceProfile, currentUser: User?) {
        if (hasOwnerWrite(profile)) {
            setOwner(
                checkNotNull(currentUser)
                    .copy(
                        long_name = profile.long_name ?: currentUser.long_name,
                        short_name = profile.short_name ?: currentUser.short_name,
                        is_unmessagable = profile.is_unmessagable ?: currentUser.is_unmessagable,
                    ),
            )
        }
    }

    private suspend fun AdminEditScope.installConfig(config: LocalConfig?) {
        config?.let { localConfig ->
            localConfig.device?.let { setConfig(Config(device = it)) }
            localConfig.position?.let { setConfig(Config(position = it)) }
            localConfig.power?.let { setConfig(Config(power = it)) }
            localConfig.network?.let { setConfig(Config(network = it)) }
            localConfig.display?.let { setConfig(Config(display = it)) }
            localConfig.lora?.let { setConfig(Config(lora = it)) }
            localConfig.security?.let { setConfig(Config(security = it)) }
        }
    }

    private suspend fun AdminEditScope.installChannels(restore: ChannelRestore?) {
        restore?.writes?.forEach { setChannel(it) }
        restore?.loraConfig?.let { setConfig(Config(lora = it)) }
    }

    private suspend fun AdminEditScope.installFixedPosition(fixedPosition: org.meshtastic.proto.Position?) {
        fixedPosition?.let { setFixedPosition(Position(it)) }
    }

    private suspend fun AdminEditScope.installModuleConfig(moduleConfig: LocalModuleConfig?) {
        moduleConfig?.let { localModuleConfig ->
            localModuleConfig.external_notification?.let { setModuleConfig(ModuleConfig(external_notification = it)) }
            localModuleConfig.store_forward?.let { setModuleConfig(ModuleConfig(store_forward = it)) }
            localModuleConfig.range_test?.let { setModuleConfig(ModuleConfig(range_test = it)) }
            localModuleConfig.telemetry?.let { setModuleConfig(ModuleConfig(telemetry = it)) }
            localModuleConfig.canned_message?.let { setModuleConfig(ModuleConfig(canned_message = it)) }
            localModuleConfig.audio?.let { setModuleConfig(ModuleConfig(audio = it)) }
            localModuleConfig.remote_hardware?.let { setModuleConfig(ModuleConfig(remote_hardware = it)) }
            localModuleConfig.neighbor_info?.let { setModuleConfig(ModuleConfig(neighbor_info = it)) }
            localModuleConfig.ambient_lighting?.let { setModuleConfig(ModuleConfig(ambient_lighting = it)) }
            localModuleConfig.detection_sensor?.let { setModuleConfig(ModuleConfig(detection_sensor = it)) }
            localModuleConfig.paxcounter?.let { setModuleConfig(ModuleConfig(paxcounter = it)) }
            localModuleConfig.statusmessage?.let { setModuleConfig(ModuleConfig(statusmessage = it)) }
            localModuleConfig.tak?.let { setModuleConfig(ModuleConfig(tak = it)) }
        }
    }

    private data class ChannelRestore(
        val writes: List<Channel>,
        val normalizedSettings: List<ChannelSettings>,
        val loraConfig: Config.LoRaConfig?,
    )

    private enum class ProfileInstallStage(val logName: String) {
        TRANSACTION("transaction"),
        MQTT("mqtt"),
        SERIAL("serial"),
        BLUETOOTH("bluetooth"),
    }

    private companion object {
        val PROFILE_STAGE_TIMEOUT = 90.seconds
    }
}

private fun LocalConfig?.withoutBluetooth(): LocalConfig? =
    this?.copy(bluetooth = null)?.takeUnless { it == LocalConfig() }

private fun LocalModuleConfig?.withoutTransportDisruptiveModules(): LocalModuleConfig? =
    this?.copy(mqtt = null, serial = null)?.takeUnless { it == LocalModuleConfig() }
