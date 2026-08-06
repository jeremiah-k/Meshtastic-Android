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

import org.meshtastic.proto.Config.BluetoothConfig
import org.meshtastic.proto.DeviceProfile
import org.meshtastic.proto.LocalConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileRestoreHintsTest {
    @Test
    fun `local enabled Bluetooth restore suggests repair troubleshooting`() {
        val profile = DeviceProfile(config = LocalConfig(bluetooth = BluetoothConfig(enabled = true)))

        assertTrue(profile.shouldSuggestBluetoothRepair(isLocal = true))
    }

    @Test
    fun `absent or disabled Bluetooth restore does not suggest repair troubleshooting`() {
        assertFalse(DeviceProfile().shouldSuggestBluetoothRepair(isLocal = true))
        assertFalse(
            DeviceProfile(config = LocalConfig(bluetooth = BluetoothConfig(enabled = false)))
                .shouldSuggestBluetoothRepair(isLocal = true),
        )
    }

    @Test
    fun `remote Bluetooth restore does not suggest local pairing repair`() {
        val profile = DeviceProfile(config = LocalConfig(bluetooth = BluetoothConfig(enabled = true)))

        assertFalse(profile.shouldSuggestBluetoothRepair(isLocal = false))
    }
}
