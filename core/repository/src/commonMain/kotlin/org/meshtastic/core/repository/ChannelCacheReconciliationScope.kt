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
package org.meshtastic.core.repository

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.meshtastic.proto.ChannelSettings

/**
 * Tracks whether an authoritative channel write requires one local-cache reconciliation.
 *
 * Not thread-safe. Callers must invoke [markChannelWriteIssued] and [reconcileChannelCache] from one coroutine;
 * concurrent calls can issue more than one cache replacement.
 */
class ChannelCacheReconciliationScope
internal constructor(
    private val repository: RadioConfigRepository,
    private val normalizedSettings: List<ChannelSettings>,
) {
    private var channelWriteIssued = false
    private var reconciliationCompleted = false

    /** Marks that at least one channel command was accepted by the radio. */
    fun markChannelWriteIssued() {
        channelWriteIssued = true
    }

    /** Replaces the local channel cache once after the first accepted channel command. */
    suspend fun reconcileChannelCache() {
        if (!channelWriteIssued || reconciliationCompleted) return

        // Handshake persistence omits DISABLED slots, so replayed packets cannot reliably clear stale trailing
        // channels.
        // Carry failures out as values so coroutine stack-trace recovery cannot replace their identity while crossing
        // the NonCancellable context boundary.
        val replacement =
            withContext(NonCancellable) { runCatching { repository.replaceAllSettings(normalizedSettings) } }
        replacement.getOrThrow()
        reconciliationCompleted = true
    }
}

/**
 * Runs [block] with authoritative channel-cache cleanup on every normal, failed, or cancelled exit.
 *
 * A block failure remains primary. If cleanup also fails, the cleanup error is suppressed onto that original failure.
 */
suspend fun <T> RadioConfigRepository.withChannelCacheReconciliation(
    normalizedSettings: List<ChannelSettings>,
    block: suspend ChannelCacheReconciliationScope.() -> T,
): T {
    val reconciliation = ChannelCacheReconciliationScope(this, normalizedSettings)
    val blockResult = runCatching { reconciliation.block() }
    val reconciliationFailure = runCatching { reconciliation.reconcileChannelCache() }.exceptionOrNull()
    val primaryFailure = blockResult.exceptionOrNull()

    if (primaryFailure != null) {
        if (reconciliationFailure != null && reconciliationFailure !== primaryFailure) {
            primaryFailure.addSuppressed(reconciliationFailure)
        }
        throw primaryFailure
    }

    if (reconciliationFailure != null) throw reconciliationFailure
    return blockResult.getOrThrow()
}
