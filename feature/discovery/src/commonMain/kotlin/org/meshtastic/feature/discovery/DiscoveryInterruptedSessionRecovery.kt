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
import org.meshtastic.core.database.dao.DiscoveryDao
import org.meshtastic.core.database.entity.DiscoverySessionStatus
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.repository.MeshPrefs
import org.meshtastic.core.repository.ServiceRepository

/** Restores persisted discovery sessions only while their original radio still owns the recovery. */
internal class DiscoveryInterruptedSessionRecovery(
    private val serviceRepository: ServiceRepository,
    private val discoveryDao: DiscoveryDao,
    private val homeRestorer: DiscoveryHomeRestorer,
    private val meshPrefs: MeshPrefs,
    private val isScanActive: suspend () -> Boolean,
) {
    suspend fun watch(onRestored: suspend (homePreset: String) -> Unit = {}) {
        serviceRepository.connectionState.collect { state ->
            if (state is ConnectionState.Connected) {
                val result = runCatching { restoreIfAny() }
                val failure = result.exceptionOrNull()
                if (failure is CancellationException) throw failure
                if (failure != null && failure !is Exception) throw failure
                if (failure != null) {
                    Logger.e(failure) { "DiscoveryScanEngine: interrupted-session restore failed; will retry" }
                }
                result.getOrNull()?.let { homePreset -> onRestored(homePreset) }
            }
        }
    }

    private suspend fun restoreIfAny(): String? {
        val address = meshPrefs.deviceAddress.value
        val session =
            if (address != null && !isScanActive()) {
                discoveryDao.getInterruptedSession(address)
            } else {
                null
            }
        val recoverable = session?.takeIf { !isScanActive() && meshPrefs.deviceAddress.value == address }

        return when {
            recoverable == null -> null

            recoverable.homeLoraConfig == null -> {
                discoveryDao.updateSession(recoverable.copy(completionStatus = DiscoverySessionStatus.UNRESTORABLE))
                null
            }

            else -> {
                Logger.w { "DiscoveryScanEngine: restoring home config after interrupted session ${recoverable.id}" }
                val restored = homeRestorer.restorePersistedSession(recoverable)
                recoverable.homePreset.takeIf { restored }
            }
        }
    }
}
