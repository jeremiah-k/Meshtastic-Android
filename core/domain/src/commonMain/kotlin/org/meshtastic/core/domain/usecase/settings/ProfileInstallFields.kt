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

import okio.ByteString
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
            // The restore policy (safeRestoreOverlay) overlays identity keys first, so an incoming section whose
            // overlaid form already matches the running config is pruned like any other unchanged section.
            security =
            incoming.security?.safeRestoreOverlay(current.security)?.takeUnless { it == current.security },
        )
        .takeIf { it.installableConfigs().isNotEmpty() || it.network != null || it.bluetooth != null }
}

/** Removes module sections already reported by the current handshake, including transport-sensitive modules. */
private fun LocalModuleConfig?.withoutUnchangedFields(current: LocalModuleConfig): LocalModuleConfig? =
    this?.withoutUnchangedModuleFields(current)

internal fun LocalConfig?.withoutTransportSensitiveConfig(): LocalConfig? =
    this?.copy(bluetooth = null, network = null)?.takeIf { it.hasInstallableWrites() }

internal fun LocalConfig.hasInstallableWrites(): Boolean = installableConfigs().isNotEmpty()

internal fun LocalModuleConfig?.withoutTransportSensitiveModules(): LocalModuleConfig? =
    this?.copy(mqtt = null, serial = null)?.takeIf { it.hasInstallableWrites() }

internal fun LocalModuleConfig.hasInstallableWrites(): Boolean = moduleConfigs().isNotEmpty()

/**
 * Replaces security key bytes with empty values so a comparison never touches secret material. Applied symmetrically to
 * the requested and the device-reported config during post-restart transaction verification: key retention is attested
 * by the transaction commit acknowledgement alone, and only the observable policy around the keys is re-checked.
 *
 * Wire-generated [copy] only preserves the explicitly listed fields, so every other config field must be passed through
 * here; otherwise the post-restart planner sees a stripped profile and reclassifies a correctly retained section as
 * outstanding work.
 */
internal fun LocalConfig.withoutSecretSecurityBytes(): LocalConfig = copy(
    device = device,
    position = position,
    power = power,
    network = network,
    display = display,
    lora = lora,
    bluetooth = bluetooth,
    version = version,
    security = security?.copy(private_key = ByteString.EMPTY, public_key = ByteString.EMPTY),
)

/** The profile-side half of [withoutSecretSecurityBytes]: redacts the key bytes carried by the requested config. */
internal fun DeviceProfile.withoutSecretSecurityBytes(): DeviceProfile =
    copy(config = config?.withoutSecretSecurityBytes())
