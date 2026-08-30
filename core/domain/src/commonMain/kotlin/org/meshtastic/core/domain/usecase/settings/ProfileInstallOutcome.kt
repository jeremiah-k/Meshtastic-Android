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

/** Terminal outcome of a staged device-profile installation. */
sealed interface ProfileInstallOutcome {
    /** Every planned stage completed and the radio reconnected on the active transport. */
    data object Completed : ProfileInstallOutcome

    /**
     * The final stage intentionally removed the active transport (for example enabling Wi-Fi while connected over BLE,
     * which ESP32-classic firmware answers by releasing the Bluetooth controller). Automatic reconnection on the
     * retired transport was stopped; the radio must be reached through its new transport.
     */
    data object TransportHandedOff : ProfileInstallOutcome
}
