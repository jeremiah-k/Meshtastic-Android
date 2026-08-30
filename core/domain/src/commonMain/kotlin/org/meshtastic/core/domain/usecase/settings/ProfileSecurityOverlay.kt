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

import okio.ByteString
import org.meshtastic.proto.Config

/**
 * Preserves node identity across a profile security restore.
 *
 * A device-profile export never carries the node's private key, but firmware treats a security write whose private key
 * is not 32 bytes as "generate new PKI keys" — silently re-keying (and, on firmware 2.8, renumbering) the node in the
 * middle of the edit transaction. The overlay applies the firmware security-write contract:
 * - a valid 32-byte imported private key wins; its public half is cleared so firmware derives it
 * - otherwise a valid current private key is overlaid to preserve the running identity; the current public key rides
 *   along only when it is itself 32 bytes, otherwise it is cleared for firmware derivation
 * - otherwise the requested security policy is returned unchanged, including any invalid key bytes, so firmware
 *   first-provisioning behavior applies
 *
 * Every other [Config.SecurityConfig] field (managed mode, serial policy, admin keys, debug/API policy) is preserved
 * verbatim, and the section is never dropped. No key material is logged or synthesized. Applied where incoming profile
 * Security survives the pruning seam in `ProfileInstallFields`, before the equality prune against the running config.
 */
internal fun Config.SecurityConfig.safeRestoreOverlay(current: Config.SecurityConfig?): Config.SecurityConfig = when {
    private_key.size == PRIVATE_KEY_BYTES -> copy(public_key = ByteString.EMPTY)

    current != null && current.private_key.size == PRIVATE_KEY_BYTES ->
        copy(
            private_key = current.private_key,
            public_key = current.public_key.takeIf { it.size == PRIVATE_KEY_BYTES } ?: ByteString.EMPTY,
        )

    else -> this
}

/** Number of bytes in a valid Meshtastic X25519 private key. */
private const val PRIVATE_KEY_BYTES = 32
