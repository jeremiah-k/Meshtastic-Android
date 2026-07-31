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

import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.LoRaPresetGroup
import org.meshtastic.proto.LoRaRegionPresetMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoRaRegionPresetContextTest {
    private val regionPresetMap = LoRaRegionPresetMap(groups = listOf(LoRaPresetGroup()))

    @Test
    fun `pre 2_8 firmware ignores an available region preset map`() {
        val state =
            RadioConfigState(
                metadata = DeviceMetadata(firmware_version = "2.7.12"),
                loraRegionPresetMap = regionPresetMap,
            )

        val context = state.loRaRegionPresetContext()

        assertFalse(context.capabilities.supportsLoraRegionPresetMap)
        assertNull(context.regionPresetMap)
    }

    @Test
    fun `2_8 firmware exposes the region preset map`() {
        val state =
            RadioConfigState(
                metadata = DeviceMetadata(firmware_version = "2.8.0"),
                loraRegionPresetMap = regionPresetMap,
            )

        val context = state.loRaRegionPresetContext()

        assertTrue(context.capabilities.supportsLoraRegionPresetMap)
        assertEquals(regionPresetMap, context.regionPresetMap)
    }

    @Test
    fun `missing metadata hides an available region preset map`() {
        val context = RadioConfigState(loraRegionPresetMap = regionPresetMap).loRaRegionPresetContext()

        assertFalse(context.capabilities.supportsLoraRegionPresetMap)
        assertNull(context.regionPresetMap)
    }

    @Test
    fun `2_8 firmware remains supported when the map is absent`() {
        val context = RadioConfigState(metadata = DeviceMetadata(firmware_version = "2.8.0")).loRaRegionPresetContext()

        assertTrue(context.capabilities.supportsLoraRegionPresetMap)
        assertNull(context.regionPresetMap)
    }
}
