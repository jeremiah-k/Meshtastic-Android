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
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.core.model.Channel as ModelChannel

/** Firmware channel files expose one primary plus seven secondary slots. */
const val CHANNEL_REPLACEMENT_SLOT_COUNT = 8

/** One authoritative channel replacement, reduced to only writes that would change device state. */
data class ChannelReplacementPlan(
    val writes: List<Channel>,
    val normalizedSettings: List<ChannelSettings>,
    val loraConfig: Config.LoRaConfig?,
) {
    val hasInstallableWrites: Boolean
        get() = writes.isNotEmpty() || loraConfig != null
}

/**
 * Plans the shared REPLACE behavior used by QR imports and device-profile restores.
 *
 * [explicitLoraConfig] is the profile-level LoRa config when one exists. It controls semantic channel identity and
 * suppresses the channel URL's LoRa write because the profile config is installed by its normal config path. Channel
 * slots remain authoritative when they differ. A no-op is safe only when all eight active slots are observable and
 * byte-identical: the local cache intentionally omits disabled slots, so a shorter matching list cannot prove that
 * hidden trailing slots are already canonical.
 */
fun planChannelReplacement(
    channelSet: ChannelSet,
    currentSettings: List<ChannelSettings>,
    currentLoraConfig: Config.LoRaConfig?,
    explicitLoraConfig: Config.LoRaConfig? = null,
): ChannelReplacementPlan {
    require(channelSet.settings.isNotEmpty()) { "Imported channel set must contain at least one channel" }
    val identityLoraConfig = explicitLoraConfig ?: channelSet.lora_config ?: currentLoraConfig
    val normalizedSettings = normalizeReplacementSettings(channelSet.settings, identityLoraConfig)
    require(normalizedSettings.size <= CHANNEL_REPLACEMENT_SLOT_COUNT) {
        "Imported channel set exceeds supported channel slot count"
    }
    val allSlotsAreObservableAndEqual =
        normalizedSettings.size == CHANNEL_REPLACEMENT_SLOT_COUNT && normalizedSettings == currentSettings
    val writes =
        if (allSlotsAreObservableAndEqual) {
            emptyList()
        } else {
            getChannelReplacementList(
                new = normalizedSettings,
                minimumSlotCount = CHANNEL_REPLACEMENT_SLOT_COUNT,
                maximumSlotCount = CHANNEL_REPLACEMENT_SLOT_COUNT,
            )
        }
    val loraConfig = channelSet.lora_config?.takeIf { explicitLoraConfig == null && it != currentLoraConfig }
    return ChannelReplacementPlan(writes = writes, normalizedSettings = normalizedSettings, loraConfig = loraConfig)
}

/**
 * Builds the authoritative channel writes needed to replace a radio's complete channel set.
 *
 * Every imported slot is written, and any remaining firmware slots are explicitly disabled so stale channels cannot
 * survive a restore. A blank primary means the firmware's default primary; blank secondary entries are disabled. Values
 * in [currentSettings] are intentionally ignored; only its size contributes to the number of trailing slots that must
 * be cleared. Inputs larger than [maximumSlotCount] are rejected rather than silently truncated.
 */
fun getChannelReplacementList(
    new: List<ChannelSettings>,
    currentSettings: List<ChannelSettings> = emptyList(),
    minimumSlotCount: Int = 0,
    maximumSlotCount: Int = Int.MAX_VALUE,
): List<Channel> = buildList {
    require(minimumSlotCount <= maximumSlotCount) { "minimumSlotCount must be <= maximumSlotCount" }
    require(new.size <= maximumSlotCount.coerceAtLeast(0)) {
        "new.size (${new.size}) exceeds maximumSlotCount ($maximumSlotCount)"
    }
    val minimumLastIndex = minimumSlotCount.coerceAtLeast(0) - 1
    val maximumLastIndex = maximumSlotCount.coerceAtLeast(0) - 1
    val endIndex = maxOf(currentSettings.lastIndex, new.lastIndex, minimumLastIndex).coerceAtMost(maximumLastIndex)
    if (endIndex < 0) return@buildList

    for (index in 0..endIndex) {
        val settings = new.getOrNull(index)?.takeUnless { it.isPlaceholder() }
        add(
            Channel(
                role =
                when {
                    index == 0 -> Channel.Role.PRIMARY
                    settings == null -> Channel.Role.DISABLED
                    else -> Channel.Role.SECONDARY
                },
                index = index,
                settings = settings ?: ChannelSettings(),
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
): List<ChannelSettings> = buildList {
    if (settings.isNotEmpty()) {
        val effectiveLora = loraConfig ?: Config.LoRaConfig()
        val primary = settings.first().takeUnless { it.isPlaceholder() } ?: ChannelSettings()
        val seen = mutableSetOf<ChannelIdentity>()
        if (!primary.isPlaceholder()) seen.add(primary.channelIdentity(effectiveLora))

        add(primary)
        for (index in 1..settings.lastIndex) {
            val candidate = settings[index]
            val identity = candidate.takeUnless { it.isPlaceholder() }?.channelIdentity(effectiveLora)
            if (identity != null && seen.add(identity)) add(candidate)
        }
    }
}

/**
 * Returns incoming channels that are neither blank placeholders nor semantic duplicates of existing or earlier entries.
 *
 * Blank existing slots are ignored when seeding identities so a disabled slot cannot suppress a real incoming channel.
 * Identity uses each channel's effective name and expanded PSK under [loraConfig], matching firmware behavior.
 */
fun getUniqueChannelAdditions(
    existing: List<ChannelSettings>,
    incoming: List<ChannelSettings>,
    loraConfig: Config.LoRaConfig,
): List<ChannelSettings> {
    val seen = existing.filterNot { it.isPlaceholder() }.map { it.channelIdentity(loraConfig) }.toMutableSet()
    return incoming.filter { candidate ->
        !candidate.isPlaceholder() && seen.add(candidate.channelIdentity(loraConfig))
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
