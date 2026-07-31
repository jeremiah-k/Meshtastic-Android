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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Single
import org.meshtastic.core.model.ConnectionLifecycle
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.model.DeviceType
import org.meshtastic.core.model.util.ChannelReplacementPlan
import org.meshtastic.core.repository.ChannelCacheReconciliationScope
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.NodeRestartTracker
import org.meshtastic.core.repository.RadioConfigRepository
import org.meshtastic.core.repository.RadioController
import org.meshtastic.core.repository.RadioInterfaceService
import org.meshtastic.core.repository.withChannelCacheReconciliation
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
     * remaining writes and commit reach the device. Every committed edit and standalone config write also schedules a
     * firmware reboot, regardless of transport, so each non-terminal stage must complete a fresh application handshake
     * before the next write.
     *
     * The profile is applied in this order:
     * 1. owner, channels, non-terminal config, fixed position, and non-transport-sensitive modules in one edit
     *    transaction;
     * 2. MQTT as a standalone stage;
     * 3. configuration for transports other than the active one;
     * 4. the active transport's own configuration last, because it may prevent that transport from reconnecting.
     *
     * Bluetooth and Network are always kept out of the ordinary transaction. If more than one general-config write
     * would end the active transport, those writes share one final edit transaction so firmware accepts all of them
     * before the connection disappears. This covers, for example, enabling Wi-Fi while disabling Bluetooth over BLE.
     *
     * Every stage observes the firmware restart. Stages that need another write also wait for the application handshake
     * to return to [ConnectionState.Connected], then resolve the current local node number so a security restore that
     * changes identity cannot strand later writes on the old destination.
     */
    open suspend operator fun invoke(
        destNum: Int,
        profile: DeviceProfile,
        currentUser: User?,
        isLocal: Boolean,
        onProgress: (ProfileInstallProgress) -> Unit = {},
    ) = installMutex.withLock {
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
            checkNotNull(
                withTimeoutOrNull(PROFILE_SNAPSHOT_TIMEOUT) {
                    combine(
                        radioConfigRepository.localConfigFlow,
                        radioConfigRepository.moduleConfigFlow,
                        radioConfigRepository.channelSetFlow,
                    ) { config, moduleConfig, channelSet ->
                        Triple(config, moduleConfig, channelSet.settings)
                    }
                        .first()
                },
            ) {
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
                profile = plan.profile,
                currentUser = currentUser,
                config = plan.config,
                moduleConfig = plan.moduleConfig,
                channelRestore = plan.channelRestore,
            )
        }

        installTransportSensitiveStages(plan.transportPlan, progress)
    }

    /**
     * Applies the ordinary edit transaction while keeping the local channel cache aligned with commands that were
     * actually issued. A failure before the first channel command leaves the cache untouched. After a successful send,
     * the imported authoritative set is reconciled on both normal and exceptional exits.
     */
    private suspend fun installTransactionStage(
        profile: DeviceProfile,
        currentUser: User?,
        config: LocalConfig?,
        moduleConfig: LocalModuleConfig?,
        channelRestore: ChannelReplacementPlan?,
    ) {
        suspend fun runStage(reconciliation: ChannelCacheReconciliationScope?) {
            runInstallStage(stage = ProfileInstallStage.TRANSACTION, expectReconnect = true) {
                radioController.editLocalSettings {
                    installOwner(profile, currentUser)
                    installConfig(config)
                    installChannels(channelRestore) { reconciliation?.markChannelWriteIssued() }
                    installFixedPosition(profile.fixed_position)
                    installModuleConfig(moduleConfig)
                }
                // Reconcile before waiting for the reboot handshake. Incoming channel packets can then only refine the
                // authoritative imported set instead of merging into the pre-import cache.
                reconciliation?.reconcileChannelCache()
            }
        }

        if (channelRestore == null) {
            runStage(reconciliation = null)
        } else {
            radioConfigRepository.withChannelCacheReconciliation(channelRestore.normalizedSettings) { runStage(this) }
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
            if (!completed) nodeRestartTracker.cancelExpectedRestart()
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
        val PROFILE_SNAPSHOT_TIMEOUT = 10.seconds

        // Firmware normally begins its seven-second reboot promptly. Bound only the departure separately so a rejected
        // MQTT/Serial payload fails visibly instead of consuming the full reconnection allowance without ever
        // rebooting.
        val PROFILE_DEPARTURE_TIMEOUT = 20.seconds
        val PROFILE_RECONNECT_TIMEOUT = 90.seconds
    }
}
