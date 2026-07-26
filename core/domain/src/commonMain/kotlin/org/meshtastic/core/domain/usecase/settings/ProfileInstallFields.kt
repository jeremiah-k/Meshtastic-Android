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

import org.meshtastic.proto.DeviceProfile
import org.meshtastic.proto.LocalConfig
import org.meshtastic.proto.LocalModuleConfig
import org.meshtastic.proto.User

internal fun validateOwnerRestore(profile: DeviceProfile, currentUser: User?) {
    require(!profile.hasOwnerWrite() || currentUser != null) {
        "The connected node owner must be loaded before restoring owner fields"
    }
}

internal fun DeviceProfile.hasOwnerWrite(): Boolean = long_name != null || short_name != null || is_unmessagable != null

/** Removes owner/config/module fields that already match the completed handshake snapshot. */
internal fun DeviceProfile.withoutUnchangedFields(
    currentConfig: LocalConfig,
    currentModuleConfig: LocalModuleConfig,
    currentUser: User?,
): DeviceProfile = copy(
    long_name = long_name?.takeUnless { it == currentUser?.long_name },
    short_name = short_name?.takeUnless { it == currentUser?.short_name },
    is_unmessagable = is_unmessagable?.takeUnless { it == currentUser?.is_unmessagable },
    config = config.withoutUnchangedFields(currentConfig),
    module_config = module_config.withoutUnchangedFields(currentModuleConfig),
)

/** Removes profile config sections already reported by the current handshake. */
private fun LocalConfig?.withoutUnchangedFields(current: LocalConfig): LocalConfig? = this?.let { incoming ->
    incoming
        .copy(
            device = incoming.device?.takeUnless { it == current.device },
            position = incoming.position?.takeUnless { it == current.position },
            power = incoming.power?.takeUnless { it == current.power },
            network = incoming.network?.takeUnless { it == current.network },
            display = incoming.display?.takeUnless { it == current.display },
            lora = incoming.lora?.takeUnless { it == current.lora },
            bluetooth = incoming.bluetooth?.takeUnless { it == current.bluetooth },
            security = incoming.security?.takeUnless { it == current.security },
        )
        .takeIf { it.hasProfileWrites() }
}

/** Removes module sections already reported by the current handshake, including transport-disruptive modules. */
private fun LocalModuleConfig?.withoutUnchangedFields(current: LocalModuleConfig): LocalModuleConfig? =
    this?.let { incoming ->
        incoming
            .copy(
                mqtt = incoming.mqtt?.takeUnless { it == current.mqtt },
                serial = incoming.serial?.takeUnless { it == current.serial },
                external_notification =
                incoming.external_notification?.takeUnless { it == current.external_notification },
                store_forward = incoming.store_forward?.takeUnless { it == current.store_forward },
                range_test = incoming.range_test?.takeUnless { it == current.range_test },
                telemetry = incoming.telemetry?.takeUnless { it == current.telemetry },
                canned_message = incoming.canned_message?.takeUnless { it == current.canned_message },
                audio = incoming.audio?.takeUnless { it == current.audio },
                remote_hardware = incoming.remote_hardware?.takeUnless { it == current.remote_hardware },
                neighbor_info = incoming.neighbor_info?.takeUnless { it == current.neighbor_info },
                ambient_lighting = incoming.ambient_lighting?.takeUnless { it == current.ambient_lighting },
                detection_sensor = incoming.detection_sensor?.takeUnless { it == current.detection_sensor },
                paxcounter = incoming.paxcounter?.takeUnless { it == current.paxcounter },
                statusmessage = incoming.statusmessage?.takeUnless { it == current.statusmessage },
                traffic_management = incoming.traffic_management?.takeUnless { it == current.traffic_management },
                tak = incoming.tak?.takeUnless { it == current.tak },
                mesh_beacon = incoming.mesh_beacon?.takeUnless { it == current.mesh_beacon },
            )
            .takeIf { it.hasProfileWrites() }
    }

internal fun LocalConfig?.withoutTransportSensitiveConfig(): LocalConfig? =
    this?.copy(bluetooth = null, network = null)?.takeIf { it.hasInstallableWrites() }

internal fun LocalConfig.hasInstallableWrites(): Boolean = installableConfigs().isNotEmpty()

private fun LocalConfig.hasProfileWrites(): Boolean = hasInstallableWrites() || network != null || bluetooth != null

internal fun LocalModuleConfig?.withoutTransportDisruptiveModules(): LocalModuleConfig? =
    this?.copy(mqtt = null, serial = null)?.takeIf { it.hasInstallableWrites() }

internal fun LocalModuleConfig.hasInstallableWrites(): Boolean = installableModuleConfigs().isNotEmpty()

private fun LocalModuleConfig.hasProfileWrites(): Boolean = hasInstallableWrites() || mqtt != null || serial != null
