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

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.meshtastic.core.model.ConnectionEpochs
import org.meshtastic.core.model.ConnectionState

/**
 * Owns canonical connection state and its lifecycle epochs as one serialized transition.
 *
 * [MutableStateFlow] makes individual reads and writes thread-safe, but a state transition is a read-modify-write
 * across two flows. This holder prevents concurrent writers from losing or double-counting an epoch while preserving
 * duplicate transitions as no-ops.
 */
class ConnectionStateHolder(
    initialState: ConnectionState = ConnectionState.Disconnected,
    initialEpochs: ConnectionEpochs = ConnectionEpochs(),
) : ConnectionStateProvider {
    private val transitionLock = SynchronizedObject()
    private val mutableConnectionState = MutableStateFlow(initialState)
    private val mutableConnectionEpochs = MutableStateFlow(initialEpochs)

    override val connectionState: StateFlow<ConnectionState> = mutableConnectionState.asStateFlow()
    override val connectionEpochs: StateFlow<ConnectionEpochs> = mutableConnectionEpochs.asStateFlow()

    /** Applies [newState] and advances epochs exactly once when the state changes. */
    fun setConnectionState(newState: ConnectionState) {
        synchronized(transitionLock) {
            val previous = mutableConnectionState.value
            if (previous == newState) return

            mutableConnectionEpochs.value = mutableConnectionEpochs.value.advance(previous, newState)
            // Publish durable event evidence first. A collector that reacts to the state transition can then observe
            // the matching epochs immediately, even when a rapid reconnect follows before it resumes.
            mutableConnectionState.value = newState
        }
    }

    /** Restores a known baseline, primarily for reusable test fakes. */
    fun reset(state: ConnectionState = ConnectionState.Disconnected, epochs: ConnectionEpochs = ConnectionEpochs()) {
        synchronized(transitionLock) {
            mutableConnectionEpochs.value = epochs
            mutableConnectionState.value = state
        }
    }
}
