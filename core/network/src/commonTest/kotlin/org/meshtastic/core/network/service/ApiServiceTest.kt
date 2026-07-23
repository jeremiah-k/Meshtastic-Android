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
package org.meshtastic.core.network.service

import kotlinx.serialization.SerializationException
import org.meshtastic.core.model.FirmwareTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ApiServiceTest {
    @Test
    fun `firmware release manifest decoder accepts release asset JSON and unknown fields`() {
        val manifest =
            decodeFirmwareReleaseManifest(
                """
                {
                  "version": "2.7.26.54e0d8d",
                  "targets": [
                    {"board": "t-deck", "platform": "esp32", "future": true}
                  ],
                  "unknown": "ignored"
                }
                """
                    .trimIndent(),
            )

        assertEquals("2.7.26.54e0d8d", manifest.version)
        assertEquals(listOf(FirmwareTarget(board = "t-deck", platform = "esp32")), manifest.targets)
    }

    @Test
    fun `firmware release manifest decoder rejects malformed JSON`() {
        assertFailsWith<SerializationException> { decodeFirmwareReleaseManifest("not-json") }
    }
}
