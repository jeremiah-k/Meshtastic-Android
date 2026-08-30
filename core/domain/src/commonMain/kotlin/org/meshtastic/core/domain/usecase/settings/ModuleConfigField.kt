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

/** Exhaustive registry for every installable [ModuleConfig] one-of arm. */
internal enum class ModuleConfigField(val transportSensitive: Boolean = false) {
    MQTT(transportSensitive = true),
    SERIAL(transportSensitive = true),
    EXTERNAL_NOTIFICATION,
    STORE_FORWARD,
    RANGE_TEST,
    TELEMETRY,
    CANNED_MESSAGE,
    AUDIO,
    REMOTE_HARDWARE,
    NEIGHBOR_INFO,
    AMBIENT_LIGHTING,
    DETECTION_SENSOR,
    PAXCOUNTER,
    STATUSMESSAGE,
    TRAFFIC_MANAGEMENT,
    TAK,
    MESH_BEACON,
    ;

    @Suppress("CyclomaticComplexMethod")
    fun extract(config: LocalModuleConfig): ModuleConfig? = when (this) {
        MQTT -> config.mqtt?.let { ModuleConfig(mqtt = it) }
        SERIAL -> config.serial?.let { ModuleConfig(serial = it) }
        EXTERNAL_NOTIFICATION -> config.external_notification?.let { ModuleConfig(external_notification = it) }
        STORE_FORWARD -> config.store_forward?.let { ModuleConfig(store_forward = it) }
        RANGE_TEST -> config.range_test?.let { ModuleConfig(range_test = it) }
        TELEMETRY -> config.telemetry?.let { ModuleConfig(telemetry = it) }
        CANNED_MESSAGE -> config.canned_message?.let { ModuleConfig(canned_message = it) }
        AUDIO -> config.audio?.let { ModuleConfig(audio = it) }
        REMOTE_HARDWARE -> config.remote_hardware?.let { ModuleConfig(remote_hardware = it) }
        NEIGHBOR_INFO -> config.neighbor_info?.let { ModuleConfig(neighbor_info = it) }
        AMBIENT_LIGHTING -> config.ambient_lighting?.let { ModuleConfig(ambient_lighting = it) }
        DETECTION_SENSOR -> config.detection_sensor?.let { ModuleConfig(detection_sensor = it) }
        PAXCOUNTER -> config.paxcounter?.let { ModuleConfig(paxcounter = it) }
        STATUSMESSAGE -> config.statusmessage?.let { ModuleConfig(statusmessage = it) }
        TRAFFIC_MANAGEMENT -> config.traffic_management?.let { ModuleConfig(traffic_management = it) }
        TAK -> config.tak?.let { ModuleConfig(tak = it) }
        MESH_BEACON -> config.mesh_beacon?.let { ModuleConfig(mesh_beacon = it) }
    }

    @Suppress("CyclomaticComplexMethod")
    fun merge(target: LocalModuleConfig, config: ModuleConfig): LocalModuleConfig = when (this) {
        MQTT -> config.mqtt?.let { target.copy(mqtt = it) } ?: target

        SERIAL -> config.serial?.let { target.copy(serial = it) } ?: target

        EXTERNAL_NOTIFICATION ->
            config.external_notification?.let { target.copy(external_notification = it) } ?: target

        STORE_FORWARD -> config.store_forward?.let { target.copy(store_forward = it) } ?: target

        RANGE_TEST -> config.range_test?.let { target.copy(range_test = it) } ?: target

        TELEMETRY -> config.telemetry?.let { target.copy(telemetry = it) } ?: target

        CANNED_MESSAGE -> config.canned_message?.let { target.copy(canned_message = it) } ?: target

        AUDIO -> config.audio?.let { target.copy(audio = it) } ?: target

        REMOTE_HARDWARE -> config.remote_hardware?.let { target.copy(remote_hardware = it) } ?: target

        NEIGHBOR_INFO -> config.neighbor_info?.let { target.copy(neighbor_info = it) } ?: target

        AMBIENT_LIGHTING -> config.ambient_lighting?.let { target.copy(ambient_lighting = it) } ?: target

        DETECTION_SENSOR -> config.detection_sensor?.let { target.copy(detection_sensor = it) } ?: target

        PAXCOUNTER -> config.paxcounter?.let { target.copy(paxcounter = it) } ?: target

        STATUSMESSAGE -> config.statusmessage?.let { target.copy(statusmessage = it) } ?: target

        TRAFFIC_MANAGEMENT -> config.traffic_management?.let { target.copy(traffic_management = it) } ?: target

        TAK -> config.tak?.let { target.copy(tak = it) } ?: target

        MESH_BEACON -> config.mesh_beacon?.let { target.copy(mesh_beacon = it) } ?: target
    }
}

internal fun LocalModuleConfig.moduleConfigs(includeTransportSensitive: Boolean = false): List<ModuleConfig> =
    ModuleConfigField.entries
        .asSequence()
        .filter { includeTransportSensitive || !it.transportSensitive }
        .mapNotNull { it.extract(this) }
        .toList()

internal fun LocalModuleConfig.withoutUnchangedModuleFields(current: LocalModuleConfig): LocalModuleConfig? {
    val changed =
        ModuleConfigField.entries.mapNotNull { field ->
            field.extract(this)?.takeUnless { it == field.extract(current) }
        }
    return changed
        .fold(LocalModuleConfig()) { result, config -> result.updatedWithModuleConfig(config) }
        .takeIf { changed.isNotEmpty() }
}

/**
 * Applies every populated [ModuleConfig] arm to this aggregate using the same exhaustive registry as profile restore.
 */
internal fun LocalModuleConfig.updatedWithModuleConfig(config: ModuleConfig): LocalModuleConfig =
    ModuleConfigField.entries.fold(this) { result, field -> field.merge(result, config) }
