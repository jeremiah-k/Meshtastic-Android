/*
 * Copyright (c) 2025 Meshtastic LLC
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

package com.geeksville.mesh.repository.radio

/**
 * Represents the granular state of the underlying transport connection (e.g., BLE GATT). This provides more detail than
 * the service-level [org.meshtastic.core.service.ConnectionState].
 */
enum class TransportState {
    /** The interface is idle and not attempting to connect. */
    IDLE,

    /** The interface is actively scanning for a device. (Future use) */
    SCANNING,

    /** A connection attempt has been initiated. */
    CONNECTING,

    /** GATT connection established; service discovery is in progress. This includes MTU negotiation. */
    DISCOVERING_SERVICES,

    /** Services have been discovered; subscribing to characteristics (notifications/indications). */
    SUBSCRIBING,

    /**
     * The connection is fully established, subscribed, and ready for data transmission. This is the "happy path" state.
     */
    READY,

    /**
     * The connection is experiencing issues (e.g., keep-alive failed) but is still active. A recovery attempt is likely
     * imminent.
     */
    DEGRADED,

    /** The connection was lost, and the system is actively trying to reconnect with backoff. */
    RECONNECTING,

    /** The connection is fully terminated and is not attempting to reconnect. */
    DISCONNECTED,
}
