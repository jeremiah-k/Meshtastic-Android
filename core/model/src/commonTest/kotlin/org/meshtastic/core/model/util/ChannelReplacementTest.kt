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
package org.meshtastic.core.model.util

import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.meshtastic.core.model.Channel as ModelChannel

class ChannelReplacementTest {

    @Test
    fun `replacement rejects imported channels beyond the maximum slot count`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                getChannelReplacementList(
                    new = List(3) { index -> ChannelSettings(name = "Channel $index") },
                    currentSettings = emptyList(),
                    maximumSlotCount = 2,
                )
            }

        assertEquals("new.size (3) exceeds maximumSlotCount (2)", error.message)
    }

    @Test
    fun `replacement accepts an imported set exactly at the maximum slot count`() {
        val imported = List(2) { index -> ChannelSettings(name = "Channel $index") }

        val replacement =
            getChannelReplacementList(new = imported, currentSettings = emptyList(), maximumSlotCount = imported.size)

        assertEquals(listOf(Channel.Role.PRIMARY, Channel.Role.SECONDARY), replacement.map { it.role })
        assertEquals(imported, replacement.map { it.settings })
    }

    @Test
    fun `zero maximum slot count accepts only an empty replacement`() {
        assertEquals(
            emptyList(),
            getChannelReplacementList(new = emptyList(), currentSettings = emptyList(), maximumSlotCount = 0),
        )

        assertFailsWith<IllegalArgumentException> {
            getChannelReplacementList(
                new = listOf(ChannelSettings(name = "Primary")),
                currentSettings = emptyList(),
                maximumSlotCount = 0,
            )
        }
    }

    @Test
    fun `blank primary is emitted as the firmware default primary`() {
        val replacement =
            getChannelReplacementList(
                new = listOf(ChannelSettings()),
                currentSettings = listOf(ChannelSettings(name = "Old primary")),
            )

        assertEquals(Channel.Role.PRIMARY, replacement.single().role)
        assertEquals(ChannelSettings(), replacement.single().settings)
    }

    @Test
    fun `blank imported secondary is emitted as a disabled slot`() {
        val primary = ChannelSettings(name = "Primary")

        val replacement =
            getChannelReplacementList(
                new = listOf(primary, ChannelSettings()),
                currentSettings = listOf(primary, ChannelSettings(name = "Old secondary")),
            )

        assertEquals(Channel.Role.PRIMARY, replacement[0].role)
        assertEquals(Channel.Role.DISABLED, replacement[1].role)
        assertEquals(ChannelSettings(), replacement[1].settings)
    }

    @Test
    fun `dirty default-primary placeholders are canonicalized for firmware and cache`() {
        val dirtyPlaceholder = ChannelSettings(uplink_enabled = true, downlink_enabled = true)

        val replacement =
            getChannelReplacementList(
                new = listOf(dirtyPlaceholder),
                currentSettings = listOf(ChannelSettings(name = "Old primary")),
            )
        val normalized = normalizeReplacementSettings(listOf(dirtyPlaceholder), loraConfig = null)

        assertEquals(Channel.Role.PRIMARY, replacement.single().role)
        assertEquals(ChannelSettings(), replacement.single().settings)
        assertEquals(listOf(ChannelSettings()), normalized)
    }

    @Test
    fun `blank existing slot does not suppress a meaningful incoming channel`() {
        val incoming = ChannelSettings(name = "LongFast")

        val additions =
            getUniqueChannelAdditions(
                existing = listOf(ChannelSettings()),
                incoming = listOf(incoming),
                loraConfig = ModelChannel.default.loraConfig,
            )

        assertEquals(listOf(incoming), additions)
    }

    @Test
    fun `replacement disables stale trailing slots`() {
        val primary = ChannelSettings(name = "Primary")

        val replacement =
            getChannelReplacementList(
                new = listOf(primary),
                currentSettings = listOf(primary, ChannelSettings(name = "Old")),
            )

        assertEquals(listOf(Channel.Role.PRIMARY, Channel.Role.DISABLED), replacement.map(Channel::role))
        assertEquals(ChannelSettings(), replacement.last().settings)
    }

    @Test
    fun `empty authoritative set restores a default primary and disables trailing slots`() {
        val replacement =
            getChannelReplacementList(
                new = emptyList(),
                currentSettings = listOf(ChannelSettings(name = "Old primary"), ChannelSettings(name = "Old chat")),
            )

        assertEquals(listOf(Channel.Role.PRIMARY, Channel.Role.DISABLED), replacement.map(Channel::role))
        assertEquals(listOf(ChannelSettings(), ChannelSettings()), replacement.map(Channel::settings))
    }

    @Test
    fun `replacement rejects an invalid slot range`() {
        assertFailsWith<IllegalArgumentException> {
            getChannelReplacementList(
                new = listOf(ChannelSettings(name = "Primary")),
                currentSettings = emptyList(),
                minimumSlotCount = 2,
                maximumSlotCount = 1,
            )
        }
    }

    @Test
    fun `normalization removes blank and duplicate secondaries while retaining primary`() {
        val primary = ChannelSettings(name = "Primary", psk = byteArrayOf(1).toByteString())
        val secondary = ChannelSettings(name = "Secondary", psk = byteArrayOf(2).toByteString())

        val normalized =
            normalizeReplacementSettings(
                listOf(primary, ChannelSettings(), primary, secondary),
                ModelChannel.default.loraConfig,
            )

        assertEquals(listOf(primary, secondary), normalized)
    }

    @Test
    fun `normalization preserves a blank default primary without suppressing a public secondary`() {
        val blankPrimary = ChannelSettings()
        val publicSecondary = ChannelSettings(psk = byteArrayOf(1).toByteString())

        val normalized =
            normalizeReplacementSettings(listOf(blankPrimary, publicSecondary), ModelChannel.default.loraConfig)

        assertEquals(listOf(blankPrimary, publicSecondary), normalized)
    }

    @Test
    fun `normalization handles empty input and a missing LoRa config`() {
        assertEquals(emptyList(), normalizeReplacementSettings(emptyList(), loraConfig = null))
        val primary = ChannelSettings(name = "Primary")
        assertEquals(listOf(primary), normalizeReplacementSettings(listOf(primary), loraConfig = null))
    }
}
