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
import okio.ByteString.Companion.EMPTY
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Config.SecurityConfig
import org.meshtastic.proto.Config.SecurityConfig.PacketSignaturePolicy
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies the security restore policy across the full key-precedence/public-key-size matrix, including verbatim policy
 * and admin-key preservation in every branch.
 */
class ProfileSecurityOverlayTest {

    @Test
    fun `imported valid private key wins and the imported public key is always cleared`() {
        KEY_SIZES.forEach { importedPublicKeySize ->
            val imported =
                securityConfig(
                    privateKeySize = PRIVATE_KEY_BYTES,
                    publicKeySize = importedPublicKeySize,
                    isManaged = true,
                    serialEnabled = false,
                    debugLogApiEnabled = false,
                    adminChannelEnabled = false,
                    adminKeySizes = listOf(32, 31),
                    signaturePolicy = PacketSignaturePolicy.PACKET_SIGNATURE_POLICY_BALANCED,
                )
            val current = securityConfig(privateKeySize = PRIVATE_KEY_BYTES, publicKeySize = 32)

            val overlaid = imported.safeRestoreOverlay(current)

            assertEquals(imported.copy(public_key = EMPTY), overlaid)
        }
    }

    @Test
    fun `current valid private key overlays and the current public key is retained only when exactly 32 bytes`() {
        KEY_SIZES.forEach { currentPublicKeySize ->
            val imported =
                securityConfig(
                    privateKeySize = 16,
                    publicKeySize = 32,
                    isManaged = true,
                    serialEnabled = true,
                    adminKeySizes = listOf(32),
                    signaturePolicy = PacketSignaturePolicy.PACKET_SIGNATURE_POLICY_STRICT,
                )
            val current =
                securityConfig(
                    privateKeySize = PRIVATE_KEY_BYTES,
                    publicKeySize = currentPublicKeySize,
                    adminChannelEnabled = true,
                )

            val overlaid = imported.safeRestoreOverlay(current)

            assertEquals(
                imported.copy(
                    private_key = current.private_key,
                    public_key = if (currentPublicKeySize == PRIVATE_KEY_BYTES) current.public_key else EMPTY,
                ),
                overlaid,
            )
        }
    }

    @Test
    fun `requested security is unchanged when neither private key is valid`() {
        INVALID_KEY_SIZES.forEach { importedPrivateKeySize ->
            INVALID_KEY_SIZES.forEach { currentPrivateKeySize ->
                val imported =
                    securityConfig(
                        privateKeySize = importedPrivateKeySize,
                        publicKeySize = 16,
                        isManaged = true,
                        serialEnabled = true,
                        debugLogApiEnabled = true,
                        adminChannelEnabled = false,
                        adminKeySizes = listOf(31),
                        signaturePolicy = PacketSignaturePolicy.PACKET_SIGNATURE_POLICY_COMPATIBLE,
                    )
                // A valid current public key must not leak into the verbatim result.
                val current = securityConfig(privateKeySize = currentPrivateKeySize, publicKeySize = 32)

                assertEquals(imported, imported.safeRestoreOverlay(current))
            }
        }
    }

    @Test
    fun `requested security is unchanged when no current security snapshot exists`() {
        INVALID_KEY_SIZES.forEach { importedPrivateKeySize ->
            val imported = securityConfig(privateKeySize = importedPrivateKeySize, publicKeySize = 31, isManaged = true)

            assertEquals(imported, imported.safeRestoreOverlay(current = null))
        }
    }

    private companion object {
        const val PRIVATE_KEY_BYTES = 32

        /** Key sizes around the valid 32-byte boundary: empty, tiny, half, one short, valid, one long. */
        val KEY_SIZES = listOf(0, 1, 16, 31, 32, 33)
        val INVALID_KEY_SIZES = KEY_SIZES.filterNot { it == PRIVATE_KEY_BYTES }

        /** Deterministic single-byte fills keep raw key material out of test sources. */
        fun securityKey(size: Int, fill: Char): ByteString = ByteArray(size) { fill.code.toByte() }.toByteString()

        fun securityConfig(
            privateKeySize: Int,
            publicKeySize: Int = 0,
            isManaged: Boolean = false,
            serialEnabled: Boolean = false,
            debugLogApiEnabled: Boolean = false,
            adminChannelEnabled: Boolean = false,
            adminKeySizes: List<Int> = emptyList(),
            signaturePolicy: PacketSignaturePolicy = PacketSignaturePolicy.PACKET_SIGNATURE_POLICY_COMPATIBLE,
        ): SecurityConfig = SecurityConfig(
            private_key = securityKey(privateKeySize, fill = 'a'),
            public_key = securityKey(publicKeySize, fill = 'b'),
            is_managed = isManaged,
            serial_enabled = serialEnabled,
            debug_log_api_enabled = debugLogApiEnabled,
            admin_channel_enabled = adminChannelEnabled,
            admin_key = adminKeySizes.mapIndexed { index, size -> securityKey(size, fill = 'c' + index) },
            packet_signature_policy = signaturePolicy,
        )
    }
}
