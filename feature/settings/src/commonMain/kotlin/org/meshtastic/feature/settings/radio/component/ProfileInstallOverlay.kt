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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.common.util.MetricFormatter
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.profile_install_in_progress
import org.meshtastic.core.resources.profile_install_preparing
import org.meshtastic.core.resources.profile_install_stage_label
import org.meshtastic.feature.settings.radio.ProfileInstallState

/**
 * Full-screen progress overlay for the staged device-profile install flow. Mirrors [LoadingOverlay]'s visual style
 * (faded scrim, centered CircularProgressIndicator with percent label, status caption) so the in-progress UX is
 * consistent with other response-state-driven flows. The overlay fades in once [state] is non-Idle and fades out when
 * the install returns to Idle.
 */
@Composable
fun ProfileInstallOverlay(state: ProfileInstallState, modifier: Modifier = Modifier) {
    val visible = state !is ProfileInstallState.Idle
    var displayedState by remember { mutableStateOf(if (visible) state else ProfileInstallState.Preparing) }
    LaunchedEffect(state) { if (state !is ProfileInstallState.Idle) displayedState = state }

    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = OVERLAY_ALPHA))
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(pass = PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            ProfileInstallOverlayContent(displayedState)
        }
    }
}

/**
 * Centered overlay content: the indeterminate or determinate progress spinner with its percentage label, the
 * stage/status caption, and the persistent "install in progress" note. Extracted so [ProfileInstallOverlay] only owns
 * visibility, stage retention, and input blocking.
 */
@Composable
private fun ProfileInstallOverlayContent(displayedState: ProfileInstallState) {
    Column(
        modifier = Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        val total = (displayedState as? ProfileInstallState.Installing)?.totalStages?.coerceAtLeast(1) ?: 1
        val current = (displayedState as? ProfileInstallState.Installing)?.currentStage?.coerceIn(0, total) ?: 0
        val isPreparing = displayedState is ProfileInstallState.Preparing
        val isInstalling = displayedState is ProfileInstallState.Installing
        val progress by
            animateFloatAsState(
                targetValue = if (isInstalling) current.toFloat() / total.toFloat() else 0f,
                label = "profile-install-progress",
            )

        Box(contentAlignment = Alignment.Center) {
            // Indeterminate during Preparing, determinate once Installing reports a stage count.
            if (isInstalling) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(80.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text(
                    text = MetricFormatter.percent(progress * PERCENTAGE_FACTOR, decimalPlaces = 0),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(80.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }

        Text(
            text = profileInstallStatusText(displayedState, current, total),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )

        if (isPreparing || isInstalling) {
            Text(
                text = stringResource(Res.string.profile_install_in_progress),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Returns the localized status caption for the install overlay's current state. Extracted so the `when` branches all
 * land on a single `String` return type; without this the compiler can't unify the parameterized stringResource call
 * with the unconditional branches.
 */
@Composable
private fun profileInstallStatusText(state: ProfileInstallState, current: Int, total: Int): String = when (state) {
    ProfileInstallState.Idle -> ""
    ProfileInstallState.Preparing -> stringResource(Res.string.profile_install_preparing)
    is ProfileInstallState.Installing -> stringResource(Res.string.profile_install_stage_label, current, total)
}

private const val OVERLAY_ALPHA = 0.6f
private const val PERCENTAGE_FACTOR = 100
