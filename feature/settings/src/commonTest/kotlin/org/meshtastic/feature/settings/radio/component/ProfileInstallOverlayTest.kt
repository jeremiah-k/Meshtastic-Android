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
package org.meshtastic.feature.settings.radio.component

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.getString
import org.meshtastic.core.resources.profile_install_in_progress
import org.meshtastic.core.resources.profile_install_preparing
import org.meshtastic.core.resources.profile_install_stage_label
import org.meshtastic.feature.settings.radio.ProfileInstallState
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ProfileInstallOverlayTest {

    @Test
    fun `idle install state does not show the overlay`() = runComposeUiTest {
        setContent { ProfileInstallOverlay(state = ProfileInstallState.Idle) }

        onNodeWithText(getString(Res.string.profile_install_preparing)).assertDoesNotExist()
        onNodeWithText(getString(Res.string.profile_install_in_progress)).assertDoesNotExist()
    }

    @Test
    fun `preparing install explains that the radio may reboot`() = runComposeUiTest {
        setContent { ProfileInstallOverlay(state = ProfileInstallState.Preparing) }

        onNodeWithText(getString(Res.string.profile_install_preparing)).assertIsDisplayed()
        onNodeWithText(getString(Res.string.profile_install_in_progress)).assertIsDisplayed()
    }

    @Test
    fun `staged install shows bounded stage and percentage progress`() = runComposeUiTest {
        setContent { ProfileInstallOverlay(state = ProfileInstallState.Installing(currentStage = 5, totalStages = 4)) }

        onNodeWithText(getString(Res.string.profile_install_stage_label, 4, 4)).assertIsDisplayed()
        onNodeWithText("100%").assertIsDisplayed()
        onNodeWithText(getString(Res.string.profile_install_in_progress)).assertIsDisplayed()
    }
}
