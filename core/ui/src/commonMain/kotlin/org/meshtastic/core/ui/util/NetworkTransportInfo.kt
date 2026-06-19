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

/**
 * Transport-type snapshot of a single system network. Filled in by the platform-specific `isWifiUnavailable` actual
 * (Android's `ConnectivityManager.getNetworkCapabilities`); kept platform-agnostic so the "is any local network
 * present?" reduction is unit-testable from `commonTest` without an Android runtime.
 */
internal data class NetworkTransportInfo(val hasWifi: Boolean, val hasEthernet: Boolean)

/**
 * Returns `true` if any of the provided [networks] exposes a Wi-Fi or Ethernet transport — the two transports that
 * carry a LAN suitable for NSD/mDNS device discovery. Drives the `wifiUnavailable` recovery banner shown by
 * `ConnectionsScreen`: as long as *any* current network is Wi-Fi or Ethernet, NSD scans have a LAN, regardless of
 * whether the system has selected one of them as the default route. The previous implementation only inspected the
 * default network, so the banner stayed stuck whenever Android kept cellular as default (or Wi-Fi was connected but
 * unvalidated).
 */
internal fun anyLocalNetworkAvailable(networks: List<NetworkTransportInfo>): Boolean =
    networks.any { it.hasWifi || it.hasEthernet }
