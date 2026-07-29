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
package org.meshtastic.feature.settings.radio

import org.meshtastic.core.model.Capabilities
import org.meshtastic.proto.LoRaRegionPresetMap

/** Firmware capabilities and the region-preset map only when that firmware supports the map contract. */
internal data class LoRaRegionPresetContext(
    val capabilities: Capabilities,
    val regionPresetMap: LoRaRegionPresetMap?,
)

internal fun RadioConfigState.loRaRegionPresetContext(): LoRaRegionPresetContext {
    val capabilities = Capabilities(metadata?.firmware_version)
    return LoRaRegionPresetContext(
        capabilities = capabilities,
        regionPresetMap = loraRegionPresetMap.takeIf { capabilities.supportsLoraRegionPresetMap },
    )
}
