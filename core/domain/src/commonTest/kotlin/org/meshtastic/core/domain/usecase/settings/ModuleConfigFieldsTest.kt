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

import org.meshtastic.proto.LocalModuleConfig
import org.meshtastic.proto.ModuleConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModuleConfigFieldsTest {
    @Test
    fun `ordinary writes exclude transport sensitive arms`() {
        val config =
            LocalModuleConfig(
                mqtt = ModuleConfig.MQTTConfig(enabled = true),
                serial = ModuleConfig.SerialConfig(enabled = true),
                telemetry = ModuleConfig.TelemetryConfig(device_update_interval = 30),
            )

        assertEquals(listOf(ModuleConfig(telemetry = config.telemetry)), config.moduleConfigs())
        assertEquals(3, config.moduleConfigs(includeTransportSensitive = true).size)
    }

    @Test
    fun `unchanged filtering and cache updates use the same arm registry`() {
        val current = LocalModuleConfig(telemetry = ModuleConfig.TelemetryConfig(device_update_interval = 30))
        val updated =
            LocalModuleConfig(
                telemetry = current.telemetry,
                neighbor_info = ModuleConfig.NeighborInfoConfig(enabled = true),
            )

        val changed = updated.withoutUnchangedModuleFields(current)

        assertEquals(updated.neighbor_info, changed?.neighbor_info)
        assertNull(changed?.telemetry)
        assertEquals(updated, current.updatedWithModuleConfig(ModuleConfig(neighbor_info = updated.neighbor_info)))
    }
}
