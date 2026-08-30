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

import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.core.repository.RadioConfigRepository
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.LocalConfig
import org.meshtastic.proto.LocalModuleConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How long a profile install stage waits for the connected device to report its configuration. */
internal val PROFILE_SNAPSHOT_TIMEOUT: Duration = 10.seconds

/**
 * Reads the device configuration snapshot a profile install stage plans against: the local config, the local module
 * config, and the current channel set. Returns `null` if the device does not answer within [PROFILE_SNAPSHOT_TIMEOUT].
 */
internal suspend fun RadioConfigRepository.readDeviceSnapshot():
    Triple<LocalConfig, LocalModuleConfig, List<ChannelSettings>>? =
    withTimeoutOrNull(PROFILE_SNAPSHOT_TIMEOUT) {
        combine(localConfigFlow, moduleConfigFlow, channelSetFlow) { config, moduleConfig, channelSet ->
            Triple(config, moduleConfig, channelSet.settings)
        }
            .first()
    }
