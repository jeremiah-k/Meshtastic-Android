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

import org.meshtastic.core.common.util.CommonUri
import org.meshtastic.core.model.DeviceType
import org.meshtastic.core.model.util.ChannelReplacementPlan
import org.meshtastic.core.model.util.planChannelReplacement
import org.meshtastic.core.model.util.toChannelSet
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceProfile
import org.meshtastic.proto.LocalConfig
import org.meshtastic.proto.LocalModuleConfig
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.User

internal object ProfileInstallPlanner {
    fun create(
        profile: DeviceProfile,
        currentConfig: LocalConfig,
        currentModuleConfig: LocalModuleConfig,
        currentChannels: List<ChannelSettings>,
        currentUser: User?,
        activeTransport: DeviceType,
    ): ProfileInstallPlan {
        val channelRestore = prepareChannelRestore(profile, currentConfig, currentChannels)
        val pendingProfile = profile.withoutUnchangedFields(currentConfig, currentModuleConfig, currentUser)

        return ProfileInstallPlan(
            profile = pendingProfile,
            config = pendingProfile.config.withoutTransportSensitiveConfig(),
            moduleConfig = pendingProfile.module_config.withoutTransportDisruptiveModules(),
            channelRestore = channelRestore,
            transportPlan = pendingProfile.transportSensitivePlan(activeTransport),
        )
    }

    private fun prepareChannelRestore(
        profile: DeviceProfile,
        currentConfig: LocalConfig,
        currentChannels: List<ChannelSettings>,
    ): ChannelReplacementPlan? {
        val channelUrl = profile.channel_url ?: return null
        return planChannelReplacement(
            channelSet = CommonUri.parse(channelUrl).toChannelSet(),
            currentSettings = currentChannels,
            currentLoraConfig = currentConfig.lora,
            explicitLoraConfig = profile.config?.lora,
        )
    }
}

internal data class ProfileInstallPlan(
    val profile: DeviceProfile,
    val config: LocalConfig?,
    val moduleConfig: LocalModuleConfig?,
    val channelRestore: ChannelReplacementPlan?,
    val transportPlan: TransportSensitivePlan,
) {
    val hasTransactionalWrites: Boolean
        get() =
            profile.hasOwnerWrite() ||
                config != null ||
                profile.fixed_position != null ||
                moduleConfig != null ||
                channelRestore?.hasInstallableWrites == true

    val stageCount: Int
        get() = (if (hasTransactionalWrites) 1 else 0) + transportPlan.stageCount
}

internal enum class ProfileInstallStage(val logName: String) {
    TRANSACTION("transaction"),
    MQTT("mqtt"),
    SERIAL("serial"),
    BLUETOOTH("bluetooth"),
    NETWORK("network"),
    TRANSPORT_CONFIG("terminal transport configuration"),
}

internal data class TransportSensitivePlan(
    val mqtt: ModuleConfig?,
    val continuingStages: List<TransportSensitiveStage>,
    val terminalStages: List<TransportSensitiveStage>,
    val groupedTerminalConfig: List<TransportSensitiveStage.ConfigWrite>,
) {
    val stageCount: Int
        get() =
            (if (mqtt != null) 1 else 0) +
                continuingStages.size +
                terminalStages.size +
                (if (groupedTerminalConfig.isNotEmpty()) 1 else 0)
}

internal sealed interface TransportSensitiveStage {
    val profileStage: ProfileInstallStage
    val activeTransportReconnects: Boolean

    data class ConfigWrite(
        override val profileStage: ProfileInstallStage,
        val config: Config,
        override val activeTransportReconnects: Boolean,
    ) : TransportSensitiveStage

    data class ModuleConfigWrite(
        override val profileStage: ProfileInstallStage,
        val config: ModuleConfig,
        override val activeTransportReconnects: Boolean,
    ) : TransportSensitiveStage
}

private fun DeviceProfile.transportSensitivePlan(activeTransport: DeviceType): TransportSensitivePlan {
    val stages = transportSensitiveStages(activeTransport)
    val configStages = stages.filterIsInstance<TransportSensitiveStage.ConfigWrite>()
    val hasTerminalConfigWrite = configStages.any { !it.activeTransportReconnects }
    // General-config writes share one firmware edit transaction when any of them ends the active transport. This lets
    // every requested config reach firmware before Bluetooth or Network tears the session down; continuing config
    // writes do not need a separate reconnect check because the grouped terminal boundary verifies the final outcome.
    val groupedTerminalConfig = configStages.takeIf { it.size > 1 && hasTerminalConfigWrite }.orEmpty()
    val individualStages = stages - groupedTerminalConfig.toSet()
    val (continuingStages, terminalStages) =
        individualStages.partition(TransportSensitiveStage::activeTransportReconnects)
    val terminalStageCount = terminalStages.size + if (groupedTerminalConfig.isEmpty()) 0 else 1

    require(terminalStageCount <= 1) {
        val names =
            (
                terminalStages.map { it.profileStage.logName } +
                    groupedTerminalConfig.filterNot { it.activeTransportReconnects }.map { it.profileStage.logName }
                )
                .distinct()
        "Profile contains multiple settings that end the active transport: $names"
    }

    return TransportSensitivePlan(
        mqtt = module_config?.mqtt?.let { ModuleConfig(mqtt = it) },
        continuingStages = continuingStages,
        terminalStages = terminalStages,
        groupedTerminalConfig = groupedTerminalConfig,
    )
}

private fun DeviceProfile.transportSensitiveStages(activeTransport: DeviceType): List<TransportSensitiveStage> =
    buildList {
        module_config?.serial?.let { serial ->
            add(
                TransportSensitiveStage.ModuleConfigWrite(
                    profileStage = ProfileInstallStage.SERIAL,
                    config = ModuleConfig(serial = serial),
                    activeTransportReconnects = activeTransport != DeviceType.USB,
                ),
            )
        }
        config?.bluetooth?.let { bluetooth ->
            add(
                TransportSensitiveStage.ConfigWrite(
                    profileStage = ProfileInstallStage.BLUETOOTH,
                    config = Config(bluetooth = bluetooth),
                    activeTransportReconnects = activeTransport != DeviceType.BLE || bluetooth.enabled,
                ),
            )
        }
        config?.network?.let { network ->
            add(
                TransportSensitiveStage.ConfigWrite(
                    profileStage = ProfileInstallStage.NETWORK,
                    config = Config(network = network),
                    activeTransportReconnects = network.activeTransportReconnects(activeTransport),
                ),
            )
        }
    }

private fun Config.NetworkConfig.activeTransportReconnects(activeTransport: DeviceType): Boolean =
    when (activeTransport) {
        DeviceType.BLE -> !wifi_enabled && !eth_enabled
        DeviceType.TCP -> false
        DeviceType.USB -> true
    }
