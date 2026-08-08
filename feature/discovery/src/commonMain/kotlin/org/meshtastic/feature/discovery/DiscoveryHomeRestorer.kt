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
package org.meshtastic.feature.discovery

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.core.common.di.ApplicationCoroutineScope
import org.meshtastic.core.database.dao.DiscoveryDao
import org.meshtastic.core.database.entity.DiscoverySessionEntity
import org.meshtastic.core.database.entity.DiscoverySessionStatus
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.repository.MeshPrefs
import org.meshtastic.core.repository.PacketQueueRejectedException
import org.meshtastic.core.repository.RadioController
import org.meshtastic.core.repository.ServiceRepository
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import kotlin.time.Duration.Companion.seconds

internal data class DiscoveryHomeRestorePlan(
    val sessionId: Long,
    val deviceAddress: String?,
    val loraConfig: Config.LoRaConfig,
    val primaryChannel: ChannelSettings?,
    val restorePrimaryChannel: Boolean,
    val finalStatus: String,
)

private fun DiscoveryHomeRestorePlan.matchesDevice(deviceAddress: String?): Boolean =
    this.deviceAddress == deviceAddress

internal fun finalStatusForPendingRestore(
    completionStatus: String,
    default: String = DiscoverySessionStatus.RESTORED,
): String = when (completionStatus) {
    DiscoveryHomeRestorer.RESTORE_PENDING_STOPPED -> DiscoverySessionStatus.STOPPED
    DiscoveryHomeRestorer.RESTORE_PENDING_FAILED -> DiscoverySessionStatus.FAILED
    DiscoveryHomeRestorer.RESTORE_PENDING_COMPLETE -> DiscoverySessionStatus.COMPLETE
    else -> default
}

private suspend fun awaitRestoreResult(result: Deferred<Boolean>, timeout: kotlin.time.Duration): Boolean {
    val completed =
        withTimeoutOrNull(timeout) {
            val attempt = runCatching { result.await() }
            val failure = attempt.exceptionOrNull()
            when {
                failure == null -> attempt.getOrDefault(false)

                failure is CancellationException -> {
                    currentCoroutineContext().ensureActive()
                    false
                }

                failure !is Exception -> throw failure

                else -> {
                    Logger.w(failure) { "DiscoveryScanEngine: awaited home restore failed" }
                    false
                }
            }
        }
    return completed == true
}

/** Owns process-lifetime restoration of the radio configuration captured before a discovery scan. */
internal class DiscoveryHomeRestorer(
    private val radioController: RadioController,
    private val serviceRepository: ServiceRepository,
    private val discoveryDao: DiscoveryDao,
    private val applicationScope: ApplicationCoroutineScope,
    private val meshPrefs: MeshPrefs,
) {
    private class RestoreStatus(initial: String) {
        private val status = MutableStateFlow(initial)

        var value: String
            get() = status.value
            set(value) {
                status.value = value
            }
    }

    private data class PendingRestore(
        val plan: DiscoveryHomeRestorePlan,
        val result: Deferred<Boolean>,
        val finalStatus: RestoreStatus,
    )

    private val pendingMutex = Mutex()
    private var pendingRestore: PendingRestore? = null

    /** A same-device scan cannot retune until a previously scheduled home restore has completed. */
    suspend fun awaitBeforeScan(deviceAddress: String?): Boolean {
        val pending = pendingMutex.withLock { pendingRestore }
        return when {
            pending == null -> true

            pending.plan.deviceAddress != deviceAddress -> {
                pending.result.cancel()
                pendingMutex.withLock { if (pendingRestore === pending) pendingRestore = null }
                true
            }

            else -> {
                Logger.i { "DiscoveryScanEngine: waiting for pending home restore before starting a new scan" }
                val restored = awaitRestoreResult(pending.result, START_WAIT_TIMEOUT)
                if (!restored) Logger.w { "DiscoveryScanEngine: home restore is still pending; deferring new scan" }
                restored
            }
        }
    }

    /** Registers a restore in the application scope. Repeated scheduling of the same active plan is idempotent. */
    suspend fun schedule(plan: DiscoveryHomeRestorePlan): Deferred<Boolean> {
        var superseded: PendingRestore? = null
        var created = false
        val pending =
            pendingMutex.withLock {
                val existing = pendingRestore
                if (existing != null && !existing.result.isCompleted && existing.plan.sessionId == plan.sessionId) {
                    existing
                } else {
                    superseded = existing?.takeUnless { it.result.isCompleted }
                    val finalStatus = RestoreStatus(plan.finalStatus)
                    val result =
                        applicationScope.async(start = CoroutineStart.LAZY) { restoreUntilComplete(plan, finalStatus) }
                    PendingRestore(plan, result, finalStatus).also {
                        pendingRestore = it
                        created = true
                    }
                }
            }
        superseded?.result?.cancel()
        if (created) {
            pending.result.invokeOnCompletion { cause ->
                if (cause != null && cause !is CancellationException) {
                    Logger.e(cause) { "DiscoveryScanEngine: background home restore failed unexpectedly" }
                }
                applicationScope.launch {
                    pendingMutex.withLock {
                        if (pendingRestore === pending && pending.result.isCompleted) pendingRestore = null
                    }
                }
            }
            pending.result.start()
        }
        return pending.result
    }

    /** Changes the status a still-running restore will publish after it succeeds. */
    suspend fun updateFinalStatus(sessionId: Long, finalStatus: String) {
        pendingMutex.withLock {
            pendingRestore?.takeIf { it.plan.sessionId == sessionId }?.finalStatus?.value = finalStatus
        }
    }

    /** Gives normal scan completion a bounded foreground opportunity while the process-lifetime job keeps running. */
    suspend fun awaitForeground(plan: DiscoveryHomeRestorePlan): Boolean =
        awaitRestoreResult(schedule(plan), FOREGROUND_RESTORE_TIMEOUT)

    /** Restores a persisted interrupted/pending session for the currently selected device. */
    suspend fun restorePersistedSession(session: DiscoverySessionEntity): Boolean {
        val loraConfig = session.homeLoraConfig ?: return false
        val plan =
            DiscoveryHomeRestorePlan(
                sessionId = session.id,
                deviceAddress = session.deviceAddress,
                loraConfig = loraConfig,
                primaryChannel = session.homePrimaryChannel,
                restorePrimaryChannel = session.homePrimaryChannel != null,
                finalStatus = finalStatusForPendingRestore(session.completionStatus),
            )
        return awaitForeground(plan)
    }

    private suspend fun restoreUntilComplete(plan: DiscoveryHomeRestorePlan, finalStatus: RestoreStatus): Boolean {
        var restored = false
        while (currentCoroutineContext().isActive && plan.matchesDevice(meshPrefs.deviceAddress.value) && !restored) {
            val attempt = runCatching { awaitConnected(plan) && applyHomeConfiguration(plan) }
            val failure = attempt.exceptionOrNull()
            if (failure is CancellationException) throw failure
            if (failure != null && failure !is Exception) throw failure
            restored = attempt.getOrDefault(false)
            if (restored) {
                finalizeRecoveredSessionBestEffort(plan.sessionId, finalStatus.value)
            } else if (plan.matchesDevice(meshPrefs.deviceAddress.value)) {
                when (failure) {
                    is PacketQueueRejectedException -> {
                        Logger.w(failure) {
                            "DiscoveryScanEngine: home restore rejected; retrying when admission recovers"
                        }
                        retryAfterAdmissionRejection(plan)
                    }

                    null -> delay(RETRY_DELAY)

                    else -> {
                        Logger.e(failure) { "DiscoveryScanEngine: home restore failed; waiting for reconnect" }
                        awaitReconnect(plan)
                    }
                }
            }
        }
        return restored
    }

    private suspend fun awaitConnected(plan: DiscoveryHomeRestorePlan): Boolean {
        if (!plan.matchesDevice(meshPrefs.deviceAddress.value)) return false
        if (serviceRepository.connectionState.value !is ConnectionState.Connected) {
            serviceRepository.connectionState.first {
                it is ConnectionState.Connected || !plan.matchesDevice(meshPrefs.deviceAddress.value)
            }
        }
        return plan.matchesDevice(meshPrefs.deviceAddress.value) &&
            serviceRepository.connectionState.value is ConnectionState.Connected
    }

    private suspend fun retryAfterAdmissionRejection(plan: DiscoveryHomeRestorePlan) {
        if (serviceRepository.connectionState.value is ConnectionState.Connected) {
            delay(RETRY_DELAY)
        } else {
            awaitConnected(plan)
        }
    }

    private suspend fun awaitReconnect(plan: DiscoveryHomeRestorePlan) {
        if (serviceRepository.connectionState.value is ConnectionState.Connected) {
            serviceRepository.connectionState.first { state ->
                state !is ConnectionState.Connected || !plan.matchesDevice(meshPrefs.deviceAddress.value)
            }
        }
        if (plan.matchesDevice(meshPrefs.deviceAddress.value)) awaitConnected(plan)
    }

    private suspend fun applyHomeConfiguration(plan: DiscoveryHomeRestorePlan): Boolean {
        var ownsDevice = plan.matchesDevice(meshPrefs.deviceAddress.value)
        if (ownsDevice && plan.restorePrimaryChannel) {
            val settings = plan.primaryChannel
            if (settings == null) {
                Logger.e {
                    "DiscoveryScanEngine: primary-channel restore required but no channel captured " +
                        "for session ${plan.sessionId}"
                }
                return false
            }
            radioController.setLocalChannel(Channel(index = 0, role = Channel.Role.PRIMARY, settings = settings))
            ownsDevice = plan.matchesDevice(meshPrefs.deviceAddress.value)
        }
        if (ownsDevice) {
            radioController.setLocalConfig(Config(lora = plan.loraConfig))
            Logger.i { "DiscoveryScanEngine: restored original LoRa config for session ${plan.sessionId}" }
            delay(POST_RESTORE_SETTLE_DELAY)
            ownsDevice = plan.matchesDevice(meshPrefs.deviceAddress.value)
        }
        return ownsDevice
    }

    private suspend fun finalizeRecoveredSessionBestEffort(sessionId: Long, finalStatus: String) {
        val result = runCatching { discoveryDao.updateSessionCompletionStatus(sessionId, finalStatus) }
        val failure = result.exceptionOrNull()
        if (failure is CancellationException) throw failure
        if (failure != null && failure !is Exception) throw failure
        if (failure != null) {
            Logger.e(failure) {
                "DiscoveryScanEngine: radio restored but terminal session persistence failed; keeping recovery row"
            }
        }
    }

    internal companion object {
        const val RESTORE_PENDING_COMPLETE = DiscoverySessionStatus.RESTORE_PENDING_COMPLETE
        const val RESTORE_PENDING_FAILED = DiscoverySessionStatus.RESTORE_PENDING_FAILED
        const val RESTORE_PENDING_STOPPED = DiscoverySessionStatus.RESTORE_PENDING_STOPPED
        private val RETRY_DELAY = 1.seconds
        private val START_WAIT_TIMEOUT = 15.seconds
        private val FOREGROUND_RESTORE_TIMEOUT = 90.seconds
        private val POST_RESTORE_SETTLE_DELAY = 3.seconds
    }
}
