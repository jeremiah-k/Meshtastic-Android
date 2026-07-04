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
package org.meshtastic.feature.firmware.ota

import co.touchlab.kermit.Logger
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.core.common.util.ioDispatcher
import org.meshtastic.core.common.util.safeCatching

/**
 * Listens for the ESP32 OTA loader's UDP discovery broadcast so the host can learn the device's post-reboot IP.
 *
 * After rebooting into OTA mode the loader emits a UDP broadcast to `255.255.255.255:[port]` every ~1 second. DHCP may
 * assign a different IP in OTA mode than in normal operation, so connecting to the previously-known IP can fail; this
 * discovery resolves the actual address.
 */
internal object WifiOtaDiscovery {
    /**
     * Listens for the OTA loader's UDP discovery broadcast on [port] and returns the sender's IP address. Returns
     * `null` on timeout, bind failure, or any other receive error — callers fall back to the original IP.
     */
    suspend fun discoverOtaDevice(port: Int = DEFAULT_PORT, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String? =
        withContext(ioDispatcher) {
            // ponytail: No MulticastLock acquired — that is an Android-only API and this is commonMain. All-ones
            // limited broadcasts (255.255.255.255) are typically delivered without it; if a specific device filters
            // them, add an expect/actual multicast-lock wrapper and acquire it for the duration of [receive].
            safeCatching<String?> {
                withTimeoutOrNull(timeoutMs) {
                    val selector = SelectorManager(ioDispatcher)
                    val socket = aSocket(selector).udp().bind(InetSocketAddress("0.0.0.0", port))
                    try {
                        Logger.i { "WiFi OTA: Listening for OTA device discovery broadcast on port $port" }
                        val datagram = socket.receive()
                        val discoveredIp = datagram.address.hostname
                        Logger.i { "WiFi OTA: Discovered OTA device at $discoveredIp" }
                        discoveredIp
                    } finally {
                        socket.close()
                        selector.close()
                    }
                }
            }
                .getOrNull()
        }

    private const val DEFAULT_PORT = 3232
    private const val DEFAULT_TIMEOUT_MS = 15_000L
}
