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
import org.meshtastic.core.model.util.CHANNEL_REPLACEMENT_SLOT_COUNT
import org.meshtastic.core.model.util.getChannelReplacementList
import org.meshtastic.core.model.util.normalizeReplacementSettings
import org.meshtastic.core.model.util.toChannelSet
import org.meshtastic.proto.Channel
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
        currentUser: User?,
        activeTransport: DeviceType,
    ): ProfileInstallPlan {
        val channelRestore = prepareChannelRestore(profile, currentConfig)
        val pendingProfile = profile.withoutUnchangedFields(currentConfig, currentModuleConfig, currentUser)

        return ProfileInstallPlan(
            profile = pendingProfile,
            config = pendingProfile.config.withoutTransportSensitiveConfig(),
            moduleConfig = pendingProfile.module_config.withoutTransportDisruptiveModules(),
            channelRestore = channelRestore,
            transportPlan = pendingProfile.transportSensitivePlan(activeTransport),
        )
    }

    private fun prepareChannelRestore(profile: DeviceProfile, currentConfig: LocalConfig): ChannelRestore? {
        val channelUrl = profile.channel_url ?: return null
        val channelSet = CommonUri.parse(channelUrl).toChannelSet()
        require(channelSet.settings.isNotEmpty()) { "Imported channel set must contain at least one channel" }
        val currentLora = currentConfig.lora
        val identityLora = profile.config?.lora ?: channelSet.lora_config ?: currentLora
        val normalizedSettings = normalizeReplacementSettings(channelSet.settings, identityLora)
        require(normalizedSettings.size <= CHANNEL_REPLACEMENT_SLOT_COUNT) {
            "Imported channel set exceeds supported channel slot count"
        }
        val writes =
            getChannelReplacementList(
                new = normalizedSettings,
                minimumSlotCount = CHANNEL_REPLACEMENT_SLOT_COUNT,
                maximumSlotCount = CHANNEL_REPLACEMENT_SLOT_COUNT,
            )
        val loraConfig = channelSet.lora_config?.takeIf { profile.config?.lora == null && it != currentLora }
        return ChannelRestore(writes = writes, normalizedSettings = normalizedSettings, loraConfig = loraConfig)
    }
}

internal data class ProfileInstallPlan(
    val profile: DeviceProfile,
    val config: LocalConfig?,
    val moduleConfig: LocalModuleConfig?,
    val channelRestore: ChannelRestore?,
    val transportPlan: TransportSensitivePlan,
) {
    val hasTransactionalWrites: Boolean
        get() =
            profile.hasOwnerWrite() ||
                config != null ||
                profile.fixed_position != null ||
                moduleConfig != null ||
                channelRestore?.hasInstallableWrites == true
}

internal data class ChannelRestore(
    val writes: List<Channel>,
    val normalizedSettings: List<ChannelSettings>,
    val loraConfig: Config.LoRaConfig?,
) {
    val hasInstallableWrites: Boolean
        get() = writes.isNotEmpty() || loraConfig != null
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
)

internal sealed interface TransportSensitiveStage {
    val profileStage: ProfileInstallStage
    val expectsReconnect: Boolean

    data class ConfigWrite(
        override val profileStage: ProfileInstallStage,
        val config: Config,
        override val expectsReconnect: Boolean,
    ) : TransportSensitiveStage

    data class ModuleConfigWrite(
        override val profileStage: ProfileInstallStage,
        val config: ModuleConfig,
        override val expectsReconnect: Boolean,
    ) : TransportSensitiveStage
}

private fun DeviceProfile.transportSensitivePlan(activeTransport: DeviceType): TransportSensitivePlan {
    val stages = transportSensitiveStages(activeTransport)
    val configStages = stages.filterIsInstance<TransportSensitiveStage.ConfigWrite>()
    val groupedTerminalConfig =
        configStages.takeIf { writes -> writes.size > 1 && writes.any { !it.expectsReconnect } }.orEmpty()
    val individualStages = stages - groupedTerminalConfig.toSet()
    val (continuingStages, terminalStages) = individualStages.partition(TransportSensitiveStage::expectsReconnect)
    val terminalStageCount = terminalStages.size + if (groupedTerminalConfig.isEmpty()) 0 else 1

    check(terminalStageCount <= 1) { "Profile contains multiple settings that end the active transport" }

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
                    expectsReconnect = activeTransport != DeviceType.USB,
                ),
            )
        }
        config?.bluetooth?.let { bluetooth ->
            add(
                TransportSensitiveStage.ConfigWrite(
                    profileStage = ProfileInstallStage.BLUETOOTH,
                    config = Config(bluetooth = bluetooth),
                    expectsReconnect = activeTransport != DeviceType.BLE || bluetooth.enabled,
                ),
            )
        }
        config?.network?.let { network ->
            add(
                TransportSensitiveStage.ConfigWrite(
                    profileStage = ProfileInstallStage.NETWORK,
                    config = Config(network = network),
                    expectsReconnect = network.expectsActiveTransportReconnect(activeTransport),
                ),
            )
        }
    }

private fun Config.NetworkConfig.expectsActiveTransportReconnect(activeTransport: DeviceType): Boolean =
    when (activeTransport) {
        DeviceType.BLE -> !wifi_enabled && !eth_enabled
        DeviceType.TCP -> false
        DeviceType.USB -> true
    }
