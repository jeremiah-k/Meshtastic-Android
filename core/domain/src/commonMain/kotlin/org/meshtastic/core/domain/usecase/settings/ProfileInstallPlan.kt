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
import org.meshtastic.core.model.util.ChannelReplacementPlan
import org.meshtastic.core.model.util.MalformedMeshtasticUrlException
import org.meshtastic.core.model.util.normalizeReplacementSettings
import org.meshtastic.core.model.util.toChannelReplacementPlan
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
        val pendingProfile = profile.withoutUnchangedFields(currentConfig, currentModuleConfig, currentUser)
        val channelSet =
            profile.channel_url?.let { url ->
                try {
                    CommonUri.parse(url).toChannelSet()
                } catch (e: IllegalArgumentException) {
                    throw MalformedMeshtasticUrlException("Invalid channel URL in device profile", e)
                }
            }
        val channelPlan =
            channelSet?.let {
                try {
                    it.toChannelReplacementPlan(
                        currentSettings = currentChannels,
                        fallbackLoraConfig = profile.config?.lora ?: currentConfig.lora,
                        requirePrimary = true,
                    )
                } catch (e: IllegalArgumentException) {
                    throw MalformedMeshtasticUrlException("Invalid channel set in device profile", e)
                }
            }
        // The handshake omits disabled slots, so only a full eight-slot byte-identical match proves the device already
        // carries the imported set; a shorter matching list must still be written.
        val channelsMatchCurrent =
            channelPlan?.normalizedSettings?.size == CHANNEL_REPLACEMENT_SLOT_COUNT &&
                channelPlan.normalizedSettings == currentChannels
        return ProfileInstallPlan(
            profile = pendingProfile,
            config = pendingProfile.config?.withoutTransportSensitiveConfig(),
            moduleConfig = pendingProfile.module_config.withoutTransportSensitiveModules(),
            channelPlan = channelPlan?.takeUnless { channelsMatchCurrent },
            // The channel URL's LoRa write is suppressed when the profile carries its own LoRa config: the explicit
            // config is installed by the ordinary config path and controls semantic channel identity.
            channelLoraWrite =
            channelSet?.lora_config?.takeIf { profile.config?.lora == null && it != currentConfig.lora },
            transportPlan = pendingProfile.transportSensitivePlan(activeTransport),
        )
    }
}

internal data class ProfileInstallPlan(
    val profile: DeviceProfile,
    val config: LocalConfig?,
    val moduleConfig: LocalModuleConfig?,
    val channelPlan: ChannelReplacementPlan?,
    val channelLoraWrite: Config.LoRaConfig?,
    val transportPlan: TransportSensitivePlan,
) {
    val hasTransactionalWrites: Boolean
        get() =
            profile.hasOwnerWrite() ||
                config != null ||
                profile.fixed_position != null ||
                moduleConfig != null ||
                channelPlan?.channelWrites?.isNotEmpty() == true ||
                channelLoraWrite != null

    /**
     * Whether the post-restart handshake still observes un-retained work from the transaction stage.
     *
     * [DeviceProfile.fixed_position] is excluded: the handshake reports the position-config flag rather than the fixed
     * coordinates, so a fixed position is attested by the transaction commit acknowledgement alone. The channel clause
     * compares the imported normalized compact set against a compact-normalized view of the [reportedChannels] the
     * device returned after the restart; a correctly retained compact set therefore no longer counts as outstanding
     * work even though the handshake reports disabled slots that the planner deliberately suppresses. The compact-set
     * admission guard inside [ProfileInstallPlanner.create] is preserved unchanged — that guard rejects a misleading
     * channel write during planning, while this check only inspects what survived the restart.
     */
    fun hasUnretainedObservableWrites(reportedChannels: List<ChannelSettings>): Boolean = profile.hasOwnerWrite() ||
        config != null ||
        moduleConfig != null ||
        (
            channelPlan?.channelWrites?.isNotEmpty() == true &&
                channelPlan.normalizedSettings != normalizeReplacementSettings(reportedChannels, channelLoraWrite)
            ) ||
        channelLoraWrite != null

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

    /** Whether any planned stage intentionally prevents the active transport from reconnecting. */
    val endsActiveTransport: Boolean
        get() = terminalStages.isNotEmpty() || groupedTerminalConfig.isNotEmpty()
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
