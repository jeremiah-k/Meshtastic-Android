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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Single
import org.meshtastic.core.model.ConnectionLifecycle
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.model.DeviceType
import org.meshtastic.core.model.util.ChannelReplacementPlan
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.NodeRestartTracker
import org.meshtastic.core.repository.RadioConfigRepository
import org.meshtastic.core.repository.RadioController
import org.meshtastic.core.repository.RadioInterfaceService
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceProfile
import org.meshtastic.proto.LocalConfig
import org.meshtastic.proto.LocalModuleConfig
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.User
import kotlin.time.Duration.Companion.seconds

/** Progress for a staged device-profile installation. */
data class ProfileInstallProgress(val currentStage: Int, val totalStages: Int)

/** Installs a local device profile using firmware-compatible, restart-aware phases. */
@Single
open class InstallProfileUseCase
constructor(
    private val radioController: RadioController,
    private val radioInterfaceService: RadioInterfaceService,
    private val radioConfigRepository: RadioConfigRepository,
    private val nodeRepository: NodeRepository,
    private val nodeRestartTracker: NodeRestartTracker,
) {
    private val installMutex = Mutex()

    /**
     * Installs [profile] onto the locally connected radio at [destNum].
     *
     * Firmware edit transactions defer normal config persistence until `commit_edit_settings`, but firmware versions
     * already in the field can interrupt Bluetooth as soon as MQTT or Serial configuration is processed. Those
     * transport-sensitive commands therefore cannot be sent inside the transaction: a BLE link can disappear before the
     * remaining writes and commit reach the device. Standalone transport-sensitive writes do make firmware reboot, but
     * the ordinary transaction commit does not, so the transaction stage requests the reboot explicitly through the
     * admin reboot command once the commit reply has returned, and every stage completes the same fresh application
     * handshake before the next write.
     *
     * After the transaction stage reconnects, the freshly synchronized device state is re-planned against the profile.
     * A device that came back without the transaction — a crash mid-commit, for example — is re-sent once, and a second
     * loss fails the install explicitly, so a reverted device is never reported as installed and later
     * transport-sensitive stages never run against it. Secret key bytes are excluded from that verification: only the
     * commit acknowledgement attests key material.
     *
     * The profile is applied in this order:
     * 1. owner, channels, non-terminal config, fixed position, and non-transport-sensitive modules in one edit
     *    transaction, verified against the post-restart state;
     * 2. MQTT as a standalone stage;
     * 3. configuration for transports other than the active one;
     * 4. the active transport's own configuration last, because it may prevent that transport from reconnecting.
     *
     * Bluetooth and Network are always kept out of the ordinary transaction. If more than one general-config write
     * would end the active transport, those writes share one final edit transaction so firmware accepts all of them
     * before the connection disappears. This covers, for example, enabling Wi-Fi while disabling Bluetooth over BLE.
     *
     * When the final stage intentionally ends the active transport while connected over BLE, the transport session is
     * closed through the existing disconnect path instead of being left to unbounded automatic reconnection — firmware
     * answers such a write by releasing the Bluetooth controller, so the radio never advertises on this transport again
     * — and the install reports [ProfileInstallOutcome.TransportHandedOff].
     *
     * Every stage observes the firmware restart. Stages that need another write also wait for the application handshake
     * to return to [ConnectionState.Connected], then resolve the current local node number so a security restore that
     * changes identity cannot strand later writes on the old destination.
     *
     * @throws IllegalArgumentException when the install is not local, no connected node is available, the destination
     *   does not match the connected local node, or owner fields are requested without a loaded owner.
     * @throws IllegalStateException when the transport or lifecycle changes mid-admission, the device snapshot cannot
     *   be read, a stage's restart or reconnect never happens, or the device did not retain the restored transaction
     *   after one verification retry.
     * @throws org.meshtastic.core.model.util.MalformedMeshtasticUrlException when the profile channel URL or channel
     *   set is invalid. This is raised before the edit transaction opens, so no partial profile is applied.
     */
    open suspend operator fun invoke(
        destNum: Int,
        profile: DeviceProfile,
        currentUser: User?,
        isLocal: Boolean,
        onProgress: (ProfileInstallProgress) -> Unit = {},
    ): ProfileInstallOutcome = installMutex.withLock {
        require(isLocal) { "Device profiles can only be installed on the locally connected node" }
        val admissionLifecycle = radioController.connectionLifecycle.value
        require(admissionLifecycle.state is ConnectionState.Connected) {
            "A connected local node is required to install a device profile"
        }
        val admissionAddress =
            checkNotNull(radioInterfaceService.getDeviceAddress()) { "The connected node transport is unavailable" }
        val activeTransport =
            checkNotNull(DeviceType.fromAddress(admissionAddress)) { "The connected node transport is unavailable" }
        require(destNum == currentLocalNodeNum()) {
            "The profile destination no longer matches the connected local node"
        }

        validateOwnerRestore(profile, currentUser)
        val (currentConfig, currentModuleConfig, currentChannels) =
            checkNotNull(radioConfigRepository.readDeviceSnapshot()) {
                "Timed out waiting for the connected device configuration before profile installation"
            }
        requireInstallationAdmissionCurrent(admissionLifecycle, admissionAddress, destNum)
        val plan =
            ProfileInstallPlanner.create(
                profile = profile,
                currentConfig = currentConfig,
                currentModuleConfig = currentModuleConfig,
                currentChannels = currentChannels,
                currentUser = currentUser,
                activeTransport = activeTransport,
            )

        val progress = ProfileInstallProgressReporter(plan.stageCount, onProgress)
        if (plan.hasTransactionalWrites) {
            progress.startNextStage()
            installTransactionStage(
                profile = profile,
                currentUser = currentUser,
                config = plan.config,
                moduleConfig = plan.moduleConfig,
                channelPlan = plan.channelPlan,
                channelLoraWrite = plan.channelLoraWrite,
                activeTransport = activeTransport,
            )
        }

        installTransportSensitiveStages(plan.transportPlan, progress)

        // The final write intentionally ended the active transport. Firmware tears the link down and, on
        // ESP32-classic, releases the Bluetooth controller entirely, so the radio never advertises on this
        // transport
        // again. Stop the transport's automatic reconnection instead of spinning on connection failures, close the
        // expected-restart window so the UI shows the honest disconnected state, and report the handoff so the user
        // reconnects through the radio's new transport.
        if (activeTransport == DeviceType.BLE && plan.transportPlan.endsActiveTransport) {
            nodeRestartTracker.onConnected()
            radioInterfaceService.disconnect()
            ProfileInstallOutcome.TransportHandedOff
        } else {
            ProfileInstallOutcome.Completed
        }
    }

    /**
     * Applies the ordinary edit transaction, verifies it survived the restart, and re-sends it once if the device came
     * back without it.
     *
     * Firmware's `commit_edit_settings` persists atomically but does not reboot, so the stage requests the reboot
     * explicitly through the admin reboot command after the commit reply has returned; firmware that reboots at commit
     * supplies its own departure and the redundant reboot request is skipped. The stage then mirrors issued channel and
     * LoRa writes into the local cache so the next handshake cannot merge replayed channel packets into the pre-import
     * state, and re-plans the profile against the freshly synchronized device state. A device that rebooted without the
     * transaction (a crash mid-commit, for example) is re-sent once; a second loss fails the install instead of
     * reporting success, so later transport-sensitive stages never run on reverted state.
     */
    private suspend fun installTransactionStage(
        profile: DeviceProfile,
        currentUser: User?,
        config: LocalConfig?,
        moduleConfig: LocalModuleConfig?,
        channelPlan: ChannelReplacementPlan?,
        channelLoraWrite: Config.LoRaConfig?,
        activeTransport: DeviceType,
    ) {
        runTransactionAttempt(profile, currentUser, config, moduleConfig, channelPlan, channelLoraWrite)
        if (transactionRetained(profile, currentUser, activeTransport)) {
            return
        }
        Logger.w { "Device reported pre-transaction state after the transaction stage; re-sending it once" }
        runTransactionAttempt(profile, currentUser, config, moduleConfig, channelPlan, channelLoraWrite)
        if (!transactionRetained(profile, currentUser, activeTransport)) {
            throw IllegalStateException("The device did not retain the restored device profile transaction")
        }
    }

    private suspend fun runTransactionAttempt(
        profile: DeviceProfile,
        currentUser: User?,
        config: LocalConfig?,
        moduleConfig: LocalModuleConfig?,
        channelPlan: ChannelReplacementPlan?,
        channelLoraWrite: Config.LoRaConfig?,
    ) {
        runInstallStage(stage = ProfileInstallStage.TRANSACTION, expectReconnect = true, rebootAfterCommit = true) {
            radioController.editLocalSettings {
                installOwner(profile, currentUser)
                installConfig(config)
                installChannels(channelPlan, channelLoraWrite)
                installFixedPosition(profile.fixed_position)
                installModuleConfig(moduleConfig)
            }
            // Refresh before waiting for the reboot handshake. Incoming channel packets can then only refine the
            // imported set instead of merging into the pre-import cache.
            refreshLocalChannelCache(channelPlan, channelLoraWrite)
        }
    }

    /**
     * Re-plans the profile against the freshly synchronized device state and reports whether the transaction stage
     * still has observable work. Secret security key bytes are redacted symmetrically from both sides before the
     * comparison, so the check never touches key material: key retention is attested by the commit acknowledgement, and
     * a crash that voids the transaction is caught through the observable configuration its atomic save revert takes
     * down with it. The owner is compared against the identity the device reports after the restart handshake, so owner
     * fields the transaction applied are recognized as retained.
     */
    private suspend fun transactionRetained(
        profile: DeviceProfile,
        currentUser: User?,
        activeTransport: DeviceType,
    ): Boolean {
        val (config, moduleConfig, channels) =
            checkNotNull(radioConfigRepository.readDeviceSnapshot()) {
                "Timed out waiting for the device configuration after the transaction stage"
            }
        val freshPlan =
            ProfileInstallPlanner.create(
                profile = profile.withoutSecretSecurityBytes(),
                currentConfig = config.withoutSecretSecurityBytes(),
                currentModuleConfig = moduleConfig,
                currentChannels = channels,
                currentUser = nodeRepository.nodeDBbyNum.value[currentLocalNodeNum()]?.user ?: currentUser,
                activeTransport = activeTransport,
            )
        return !freshPlan.hasUnretainedObservableWrites(channels)
    }

    private suspend fun refreshLocalChannelCache(
        channelPlan: ChannelReplacementPlan?,
        channelLoraWrite: Config.LoRaConfig?,
    ) {
        val settings = channelPlan?.channelWrites?.takeIf { it.isNotEmpty() }?.let { channelPlan.normalizedSettings }
        if (settings != null || channelLoraWrite != null) {
            withContext(NonCancellable) {
                radioConfigRepository.updateChannelSet(settingsList = settings, loraConfig = channelLoraWrite)
            }
        }
    }

    private suspend fun installTransportSensitiveStages(
        plan: TransportSensitivePlan,
        progress: ProfileInstallProgressReporter,
    ) {
        plan.mqtt?.let { mqtt ->
            progress.startNextStage()
            installModuleConfigStage(ProfileInstallStage.MQTT, mqtt, expectReconnect = true)
        }

        (plan.continuingStages + plan.terminalStages).forEach { stage ->
            progress.startNextStage()
            when (stage) {
                is TransportSensitiveStage.ConfigWrite ->
                    installConfigStage(stage.profileStage, stage.config, stage.activeTransportReconnects)

                is TransportSensitiveStage.ModuleConfigWrite ->
                    installModuleConfigStage(stage.profileStage, stage.config, stage.activeTransportReconnects)
            }
        }

        if (plan.groupedTerminalConfig.isNotEmpty()) {
            progress.startNextStage()
            runInstallStage(ProfileInstallStage.TRANSPORT_CONFIG, expectReconnect = false) {
                radioController.editLocalSettings { plan.groupedTerminalConfig.forEach { setConfig(it.config) } }
            }
        }
    }

    private suspend fun installConfigStage(stage: ProfileInstallStage, config: Config, expectReconnect: Boolean) =
        runInstallStage(stage, expectReconnect) {
            radioController.setConfig(currentLocalNodeNum(), config, radioController.generatePacketId())
        }

    private suspend fun installModuleConfigStage(
        stage: ProfileInstallStage,
        config: ModuleConfig,
        expectReconnect: Boolean,
    ) = runInstallStage(stage, expectReconnect) {
        radioController.setModuleConfig(currentLocalNodeNum(), config, radioController.generatePacketId())
    }

    private suspend fun runInstallStage(
        stage: ProfileInstallStage,
        expectReconnect: Boolean,
        rebootAfterCommit: Boolean = false,
        action: suspend () -> Unit,
    ) {
        Logger.i { "Installing device profile stage=${stage.logName}" }
        // Capture before the write: firmware can disconnect synchronously while action() is committing, and sampling
        // afterward would miss that real stage departure. The post-departure handshake ordering check below prevents an
        // older reconnect from satisfying this stage even when rapid lifecycle states are conflated.
        val baselineLifecycle = radioController.connectionLifecycle.value
        nodeRestartTracker.expectRestart(PROFILE_DEPARTURE_TIMEOUT + PROFILE_RECONNECT_TIMEOUT)
        var completed = false
        try {
            action()
            if (rebootAfterCommit && radioController.connectionLifecycle.value == baselineLifecycle) {
                // The commit reply has returned and the device never departed, so this firmware persists at commit
                // without rebooting. Request the restart explicitly so a healthy transaction does not sit until the
                // next incidental disconnect; firmware that departs at commit fails the check above and supplies
                // its own departure below.
                radioController.reboot(currentLocalNodeNum(), radioController.generatePacketId())
            }
            val departureEpochs =
                checkNotNull(
                    withTimeoutOrNull(PROFILE_DEPARTURE_TIMEOUT) {
                        radioController.connectionLifecycle.first {
                            it.epochs.departures > baselineLifecycle.epochs.departures
                        }
                    },
                ) {
                    "Device did not begin the ${stage.logName} profile restart"
                }
            if (expectReconnect) {
                checkNotNull(
                    withTimeoutOrNull(PROFILE_RECONNECT_TIMEOUT) {
                        radioController.connectionLifecycle.first { lifecycle ->
                            lifecycle.epochs.departures >= departureEpochs.epochs.departures &&
                                lifecycle.epochs.completedHandshakes > lifecycle.epochs.handshakesAtLastDeparture
                        }
                    },
                ) {
                    "Device did not reconnect after the ${stage.logName} profile stage"
                }
            }
            if (expectReconnect) nodeRestartTracker.onConnected()
            completed = true
            Logger.i { "Installed device profile stage=${stage.logName}" }
        } finally {
            if (!completed) nodeRestartTracker.onConnected()
        }
    }

    private fun requireInstallationAdmissionCurrent(
        admissionLifecycle: ConnectionLifecycle,
        admissionAddress: String,
        destNum: Int,
    ) {
        check(radioController.connectionLifecycle.value == admissionLifecycle) {
            "The connected radio lifecycle changed while preparing profile installation"
        }
        check(radioInterfaceService.getDeviceAddress() == admissionAddress) {
            "The connected radio transport changed while preparing profile installation"
        }
        require(destNum == currentLocalNodeNum()) {
            "The profile destination changed while preparing profile installation"
        }
    }

    private fun currentLocalNodeNum(): Int =
        checkNotNull(nodeRepository.myNodeInfo.value?.myNodeNum) { "The connected local node identity is unavailable" }

    private class ProfileInstallProgressReporter(
        private val totalStages: Int,
        private val onProgress: (ProfileInstallProgress) -> Unit,
    ) {
        private var currentStage = 0

        fun startNextStage() {
            currentStage += 1
            onProgress(ProfileInstallProgress(currentStage, totalStages))
        }
    }

    private companion object {
        // Firmware normally begins its seven-second reboot promptly. Bound only the departure separately so a rejected
        // MQTT/Serial payload fails visibly instead of consuming the full reconnection allowance without ever
        // rebooting.
        val PROFILE_DEPARTURE_TIMEOUT = 20.seconds
        val PROFILE_RECONNECT_TIMEOUT = 90.seconds
    }
}
