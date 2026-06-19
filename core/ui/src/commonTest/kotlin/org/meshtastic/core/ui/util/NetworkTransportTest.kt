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
package org.meshtastic.core.ui.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit coverage for the platform-agnostic reduction that drives `isWifiUnavailable()` on Android. `ConnectivityManager`
 * itself is not unit-testable here (it needs an Android runtime), so these cases exercise the pure helper that the
 * Android actual delegates to. Each case mirrors a real connectivity snapshot the ConnectionsScreen banner must react
 * correctly to.
 */
class NetworkTransportTest {
    @Test
    fun wifi_network_present_then_local_available() {
        // Single Wi-Fi network: banner should clear.
        assertTrue(anyLocalNetworkAvailable(listOf(NetworkTransportInfo(hasWifi = true, hasEthernet = false))))
    }

    @Test
    fun ethernet_network_present_then_local_available() {
        // Ethernet (e.g. desktop dock / Android tablet on wired LAN) also carries NSD/mDNS traffic.
        assertTrue(anyLocalNetworkAvailable(listOf(NetworkTransportInfo(hasWifi = false, hasEthernet = true))))
    }

    @Test
    fun only_cellular_network_then_local_unavailable() {
        // Cellular-only: no LAN, banner should show.
        assertFalse(anyLocalNetworkAvailable(listOf(NetworkTransportInfo(hasWifi = false, hasEthernet = false))))
    }

    @Test
    fun wifi_present_as_non_default_alongside_cellular_then_local_available() {
        // The regression case: cellular is the system default (or Wi-Fi is unvalidated), so the
        // previous `activeNetwork` check missed Wi-Fi. With allNetworks scanning, the banner clears.
        val cellular = NetworkTransportInfo(hasWifi = false, hasEthernet = false)
        val wifi = NetworkTransportInfo(hasWifi = true, hasEthernet = false)
        assertTrue(anyLocalNetworkAvailable(listOf(cellular, wifi)))
    }

    @Test
    fun no_networks_then_local_unavailable() {
        // Airplane mode / no connectivity at all.
        assertFalse(anyLocalNetworkAvailable(emptyList()))
    }

    @Test
    fun wifi_lost_across_all_networks_then_local_unavailable() {
        // Previously had Wi-Fi, now every tracked network lacks both local transports: banner returns.
        val allDropped =
            listOf(
                NetworkTransportInfo(hasWifi = false, hasEthernet = false),
                NetworkTransportInfo(hasWifi = false, hasEthernet = false),
            )
        assertFalse(anyLocalNetworkAvailable(allDropped))
    }

    @Test
    fun wifi_restored_as_non_default_after_loss_then_local_available() {
        // Symmetric to `wifi_lost_...`: after Wi-Fi returns (even as a non-default network), banner
        // clears again. Encoded as a state transition through the pure function.
        val duringOutage = listOf(NetworkTransportInfo(hasWifi = false, hasEthernet = false))
        assertFalse(anyLocalNetworkAvailable(duringOutage))
        val afterRecovery =
            listOf(
                NetworkTransportInfo(hasWifi = false, hasEthernet = false),
                NetworkTransportInfo(hasWifi = true, hasEthernet = false),
            )
        assertTrue(anyLocalNetworkAvailable(afterRecovery))
    }
}
