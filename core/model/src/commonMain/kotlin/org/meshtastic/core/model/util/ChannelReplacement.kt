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

import okio.ByteString
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.core.model.Channel as ModelChannel

/** Firmware channel files expose one primary plus seven secondary slots. */
const val CHANNEL_REPLACEMENT_SLOT_COUNT = 8

/**
 * Builds the authoritative channel writes needed to replace a radio's complete channel set.
 *
 * Every imported slot is written, and any remaining firmware slots are explicitly disabled so stale channels cannot
 * survive a restore. Values in [currentSettings] are intentionally ignored; only its size contributes to the number of
 * trailing slots that must be cleared.
 */
fun getChannelReplacementList(
    new: List<ChannelSettings>,
    currentSettings: List<ChannelSettings>,
    minimumSlotCount: Int = 0,
    maximumSlotCount: Int = Int.MAX_VALUE,
): List<Channel> = buildList {
    require(minimumSlotCount <= maximumSlotCount) { "minimumSlotCount must be <= maximumSlotCount" }
    val minimumLastIndex = minimumSlotCount.coerceAtLeast(0) - 1
    val maximumLastIndex = maximumSlotCount.coerceAtLeast(0) - 1
    val endIndex = maxOf(currentSettings.lastIndex, new.lastIndex, minimumLastIndex).coerceAtMost(maximumLastIndex)
    if (endIndex < 0) return@buildList

    for (index in 0..endIndex) {
        add(
            Channel(
                role =
                when (index) {
                    0 -> if (new.isEmpty()) Channel.Role.DISABLED else Channel.Role.PRIMARY
                    in 1..new.lastIndex -> Channel.Role.SECONDARY
                    else -> Channel.Role.DISABLED
                },
                index = index,
                settings = new.getOrNull(index) ?: ChannelSettings(),
            ),
        )
    }
}

/**
 * Removes blank secondary placeholders and semantic duplicates from an authoritative channel replacement.
 *
 * The primary slot is always retained. Secondary channels are compared using their effective name and expanded PSK
 * under [loraConfig], matching the identity used by firmware.
 */
fun normalizeReplacementSettings(
    settings: List<ChannelSettings>,
    loraConfig: Config.LoRaConfig?,
): List<ChannelSettings> {
    if (settings.size <= 1) return settings

    val effectiveLora = loraConfig ?: Config.LoRaConfig()
    val primary = settings.first()
    val seen = mutableSetOf<ChannelIdentity>()
    if (!primary.isPlaceholder()) seen.add(primary.channelIdentity(effectiveLora))

    return buildList {
        add(primary)
        for (index in 1..settings.lastIndex) {
            val candidate = settings[index]
            val identity = candidate.takeUnless { it.isPlaceholder() }?.channelIdentity(effectiveLora)
            if (identity != null && seen.add(identity)) add(candidate)
        }
    }
}

private fun ChannelSettings.isPlaceholder(): Boolean = name.isNullOrBlank() && psk.size == 0

private data class ChannelIdentity(val name: String, val psk: ByteString) {
    override fun toString(): String = "ChannelIdentity(name=$name, psk=<redacted>)"
}

private fun ChannelSettings.channelIdentity(loraConfig: Config.LoRaConfig): ChannelIdentity {
    val channel = ModelChannel(settings = this, loraConfig = loraConfig)
    return ChannelIdentity(name = channel.name, psk = channel.psk)
}
