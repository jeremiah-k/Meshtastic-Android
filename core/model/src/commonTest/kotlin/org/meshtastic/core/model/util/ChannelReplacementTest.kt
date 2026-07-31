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
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.meshtastic.core.model.Channel as ModelChannel

class ChannelReplacementTest {

    @Test
    fun `shared plan rejects an empty channel set`() {
        assertFailsWith<IllegalArgumentException> {
            planChannelReplacement(channelSet = ChannelSet(), currentSettings = emptyList(), currentLoraConfig = null)
        }
    }

    @Test
    fun `shared plan rejects normalized settings beyond the supported slot count`() {
        val oversized =
            (0..CHANNEL_REPLACEMENT_SLOT_COUNT).map { index ->
                ChannelSettings(name = "Channel $index", psk = byteArrayOf(index.toByte(), 1).toByteString())
            }

        assertFailsWith<IllegalArgumentException> {
            planChannelReplacement(
                channelSet = ChannelSet(settings = oversized),
                currentSettings = emptyList(),
                currentLoraConfig = null,
            )
        }
    }

    @Test
    fun `shared plan skips a byte-identical channel set`() {
        val settings = (0 until CHANNEL_REPLACEMENT_SLOT_COUNT).map { ChannelSettings(name = "Channel $it") }

        val plan =
            planChannelReplacement(
                channelSet = ChannelSet(settings = settings),
                currentSettings = settings,
                currentLoraConfig = Config.LoRaConfig(),
            )

        assertTrue(plan.writes.isEmpty())
        assertFalse(plan.hasInstallableWrites)
        assertEquals(settings, plan.normalizedSettings)
    }

    @Test
    fun `shared plan rewrites a matching partial set to clear unobserved slots`() {
        val settings = listOf(ChannelSettings(name = "Primary"), ChannelSettings(name = "Private"))

        val plan =
            planChannelReplacement(
                channelSet = ChannelSet(settings = settings),
                currentSettings = settings,
                currentLoraConfig = null,
            )

        assertEquals(CHANNEL_REPLACEMENT_SLOT_COUNT, plan.writes.size)
        assertTrue(plan.hasInstallableWrites)
    }

    @Test
    fun `shared plan keeps authoritative writes when any current slot differs`() {
        val imported = listOf(ChannelSettings(name = "Primary"))

        val plan =
            planChannelReplacement(
                channelSet = ChannelSet(settings = imported),
                currentSettings = listOf(ChannelSettings(name = "Old")),
                currentLoraConfig = null,
            )

        assertEquals(CHANNEL_REPLACEMENT_SLOT_COUNT, plan.writes.size)
        assertTrue(plan.hasInstallableWrites)
    }

    @Test
    fun `explicit profile LoRa suppresses the channel URL LoRa write`() {
        val currentLora = Config.LoRaConfig(region = Config.LoRaConfig.RegionCode.US)
        val channelLora = Config.LoRaConfig(region = Config.LoRaConfig.RegionCode.EU_868)
        val settings = (0 until CHANNEL_REPLACEMENT_SLOT_COUNT).map { ChannelSettings(name = "Channel $it") }

        val plan =
            planChannelReplacement(
                channelSet = ChannelSet(settings = settings, lora_config = channelLora),
                currentSettings = settings,
                currentLoraConfig = currentLora,
                explicitLoraConfig = currentLora,
            )

        assertEquals(null, plan.loraConfig)
        assertFalse(plan.hasInstallableWrites)
    }

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
    fun `additions drop matches against existing and duplicates within incoming`() {
        val existing = ChannelSettings(name = "Primary", psk = byteArrayOf(1).toByteString())
        val fresh = ChannelSettings(name = "Fresh", psk = byteArrayOf(2).toByteString())

        val additions =
            getUniqueChannelAdditions(
                existing = listOf(existing),
                incoming = listOf(existing, fresh, fresh),
                loraConfig = ModelChannel.default.loraConfig,
            )

        assertEquals(listOf(fresh), additions)
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
