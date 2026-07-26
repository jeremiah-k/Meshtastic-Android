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

import org.meshtastic.core.model.Position
import org.meshtastic.core.repository.AdminEditScope
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceProfile
import org.meshtastic.proto.LocalConfig
import org.meshtastic.proto.LocalModuleConfig
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.User

// is_licensed is deliberately excluded: enabling ham mode is a dedicated onboarding flow with additional side effects.
internal suspend fun AdminEditScope.installOwner(profile: DeviceProfile, currentUser: User?) {
    if (profile.hasOwnerWrite()) {
        setOwner(
            checkNotNull(currentUser)
                .copy(
                    long_name = profile.long_name ?: currentUser.long_name,
                    short_name = profile.short_name ?: currentUser.short_name,
                    is_unmessagable = profile.is_unmessagable ?: currentUser.is_unmessagable,
                ),
        )
    }
}

internal suspend fun AdminEditScope.installConfig(config: LocalConfig?) {
    config?.installableConfigs()?.forEach { setConfig(it) }
}

internal fun LocalConfig.installableConfigs(): List<Config> = listOfNotNull(
    device?.let { Config(device = it) },
    position?.let { Config(position = it) },
    power?.let { Config(power = it) },
    display?.let { Config(display = it) },
    lora?.let { Config(lora = it) },
    security?.let { Config(security = it) },
)

/** Writes an authoritative channel replacement and reports each successfully issued channel command. */
internal suspend fun AdminEditScope.installChannels(restore: ChannelRestore?, onWriteIssued: () -> Unit) {
    restore?.writes?.forEach { channel ->
        setChannel(channel)
        onWriteIssued()
    }
    restore?.loraConfig?.let { setConfig(Config(lora = it)) }
}

internal suspend fun AdminEditScope.installFixedPosition(fixedPosition: org.meshtastic.proto.Position?) {
    fixedPosition?.let { setFixedPosition(Position(it)) }
}

/** Writes module configurations safe for the ordinary transaction; MQTT and Serial are staged separately. */
internal suspend fun AdminEditScope.installModuleConfig(moduleConfig: LocalModuleConfig?) {
    moduleConfig?.installableModuleConfigs()?.forEach { setModuleConfig(it) }
}

@Suppress("CyclomaticComplexMethod")
internal fun LocalModuleConfig.installableModuleConfigs(): List<ModuleConfig> = listOfNotNull(
    external_notification?.let { ModuleConfig(external_notification = it) },
    store_forward?.let { ModuleConfig(store_forward = it) },
    range_test?.let { ModuleConfig(range_test = it) },
    telemetry?.let { ModuleConfig(telemetry = it) },
    canned_message?.let { ModuleConfig(canned_message = it) },
    audio?.let { ModuleConfig(audio = it) },
    remote_hardware?.let { ModuleConfig(remote_hardware = it) },
    neighbor_info?.let { ModuleConfig(neighbor_info = it) },
    ambient_lighting?.let { ModuleConfig(ambient_lighting = it) },
    detection_sensor?.let { ModuleConfig(detection_sensor = it) },
    paxcounter?.let { ModuleConfig(paxcounter = it) },
    statusmessage?.let { ModuleConfig(statusmessage = it) },
    traffic_management?.let { ModuleConfig(traffic_management = it) },
    tak?.let { ModuleConfig(tak = it) },
    mesh_beacon?.let { ModuleConfig(mesh_beacon = it) },
)
