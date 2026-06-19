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
package org.meshtastic.core.data.manager

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import org.meshtastic.core.common.util.handledLaunch
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.common.util.nowSeconds
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.model.DeviceType
import org.meshtastic.core.model.TelemetryType
import org.meshtastic.core.repository.AppWidgetUpdater
import org.meshtastic.core.repository.CommandSender
import org.meshtastic.core.repository.DataPair
import org.meshtastic.core.repository.HandshakeConstants
import org.meshtastic.core.repository.HistoryManager
import org.meshtastic.core.repository.MeshConnectionManager
import org.meshtastic.core.repository.MeshLocationManager
import org.meshtastic.core.repository.MeshNotificationManager
import org.meshtastic.core.repository.MeshWorkerManager
import org.meshtastic.core.repository.MqttManager
import org.meshtastic.core.repository.NodeManager
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.PacketHandler
import org.meshtastic.core.repository.PacketRepository
import org.meshtastic.core.repository.PlatformAnalytics
import org.meshtastic.core.repository.RadioConfigRepository
import org.meshtastic.core.repository.RadioInterfaceService
import org.meshtastic.core.repository.ServiceRepository
import org.meshtastic.core.repository.SessionManager
import org.meshtastic.core.repository.UiPrefs
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Config
import org.meshtastic.proto.Telemetry
import org.meshtastic.proto.ToRadio
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

@Suppress("LongParameterList", "TooManyFunctions")
@Single
class MeshConnectionManagerImpl(
    private val radioInterfaceService: RadioInterfaceService,
    private val serviceRepository: ServiceRepository,
    private val serviceNotifications: MeshNotificationManager,
    private val uiPrefs: UiPrefs,
    private val packetHandler: PacketHandler,
    private val nodeRepository: NodeRepository,
    private val locationManager: MeshLocationManager,
    private val mqttManager: MqttManager,
    private val historyManager: HistoryManager,
    private val radioConfigRepository: RadioConfigRepository,
    private val commandSender: CommandSender,
    private val sessionManager: SessionManager,
    private val nodeManager: NodeManager,
    private val analytics: PlatformAnalytics,
    private val packetRepository: PacketRepository,
    private val workerManager: MeshWorkerManager,
    private val appWidgetUpdater: AppWidgetUpdater,
    private val heartbeatSender: DataLayerHeartbeatSender,
    @Named("ServiceScope") private val scope: CoroutineScope,
) : MeshConnectionManager {
    /**
     * Serializes [onConnectionChanged] to prevent TOCTOU races when multiple coroutines emit state transitions
     * concurrently (e.g. flow collector vs. sleep-timeout coroutine).
     */
    private val connectionMutex = Mutex()

    private var preHandshakeJob: Job? = null
    private var sleepTimeout: Job? = null
    private var locationRequestsJob: Job? = null
    private var handshakeTimeout: Job? = null
    private var connectTimeMsec = 0L
    private var connectionRestored = false

    init {
        // Bridge transport-level state into the canonical app-level state.
        // This is the ONLY consumer of RadioInterfaceService.connectionState — it applies
        // light-sleep policy and handshake awareness before writing to ServiceRepository.
        radioInterfaceService.connectionState.onEach(::onRadioConnectionState).launchIn(scope)

        // Ensure notification title and content stay in sync with state changes
        serviceRepository.connectionState.onEach { updateStatusNotification() }.launchIn(scope)

        scope.launch {
            try {
                appWidgetUpdater.updateAll()
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Logger.e(e) { "Failed to kickstart LocalStatsWidget" }
            }
        }

        nodeRepository.myNodeInfo
            .onEach { myNodeEntity ->
                locationRequestsJob?.cancel()
                if (myNodeEntity != null) {
                    locationRequestsJob =
                        uiPrefs
                            .shouldProvideNodeLocation(myNodeEntity.myNodeNum)
                            .onEach { shouldProvide ->
                                if (shouldProvide) {
                                    locationManager.start(scope) { pos -> commandSender.sendPosition(pos) }
                                } else {
                                    locationManager.stop()
                                }
                            }
                            .launchIn(scope)
                }
            }
            .launchIn(scope)
    }

    /**
     * Bridges a transport-level [ConnectionState] into the canonical app-level state.
     *
     * Applies light-sleep policy (power-saving / router role) to decide whether a [ConnectionState.DeviceSleep] event
     * should be surfaced as sleep or as a full disconnect, then delegates to [onConnectionChanged] for the actual state
     * transition.
     */
    private suspend fun onRadioConnectionState(newState: ConnectionState) {
        val localConfig = radioConfigRepository.localConfigFlow.first()
        val isRouter = localConfig.device?.role == Config.DeviceConfig.Role.ROUTER
        val lsEnabled = localConfig.power?.is_power_saving == true || isRouter

        val effectiveState =
            when (newState) {
                is ConnectionState.Connected -> ConnectionState.Connected

                is ConnectionState.DeviceSleep ->
                    if (lsEnabled) ConnectionState.DeviceSleep else ConnectionState.Disconnected

                is ConnectionState.Connecting -> ConnectionState.Connecting

                is ConnectionState.Disconnected -> ConnectionState.Disconnected
            }
        onConnectionChanged(effectiveState)
    }

    private suspend fun onConnectionChanged(c: ConnectionState, fromState: ConnectionState? = null): Boolean =
        connectionMutex.withLock {
            val current = serviceRepository.connectionState.value
            if (fromState != null && current != fromState) {
                Logger.d { "Skipping connection transition $current -> $c, expected current state $fromState" }
                return@withLock false
            }
            if (current == c) return@withLock false

            // If the transport reports 'Connected', but we are already in the middle of a handshake (Connecting)
            if (c is ConnectionState.Connected && current is ConnectionState.Connecting) {
                Logger.d { "Ignoring redundant transport connection signal while handshake is in progress" }
                return@withLock false
            }

            Logger.i { "onConnectionChanged: $current -> $c" }

            sleepTimeout?.cancel()
            sleepTimeout = null
            preHandshakeJob?.cancel()
            preHandshakeJob = null
            handshakeTimeout?.cancel()
            handshakeTimeout = null

            when (c) {
                is ConnectionState.Connecting -> serviceRepository.setConnectionState(ConnectionState.Connecting)
                is ConnectionState.Connected -> handleConnected()
                is ConnectionState.DeviceSleep -> handleDeviceSleep()
                is ConnectionState.Disconnected -> handleDisconnected()
            }
            true
        }

    private fun handleConnected() {
        // Track whether this connection was restored from device sleep (vs. a fresh connect),
        // matching Apple's "connectionRestored" attribute for cross-platform DataDog parity.
        connectionRestored = serviceRepository.connectionState.value is ConnectionState.DeviceSleep
        // The service state remains 'Connecting' until config is fully loaded
        if (serviceRepository.connectionState.value != ConnectionState.Connected) {
            serviceRepository.setConnectionState(ConnectionState.Connecting)
        }
        connectTimeMsec = nowMillis

        // Send a wake-up heartbeat before the config request. The firmware may be in a
        // power-saving state where the NimBLE callback context needs warming up. The 100ms
        // delay ensures the heartbeat BLE write is enqueued before the want_config_id
        // (sendToRadio is fire-and-forget through async coroutine launches).
        preHandshakeJob =
            scope.handledLaunch {
                heartbeatSender.sendHeartbeat("pre-handshake")
                delay(PRE_HANDSHAKE_SETTLE_MS)
                Logger.i { "Starting mesh handshake (Stage 1)" }
                startConfigOnly()
            }
    }

    private fun startHandshakeStallGuard(stage: Int, timeout: Duration, action: () -> Unit) {
        handshakeTimeout?.cancel()
        val fastTransport = isFastRecoveryTransport()
        // On TCP/USB the firmware handshake completes in roughly 1s when healthy (logs show),
        // while a wedged socket takes the full ~30s transport read timeout without any further
        // progress. The aggressive 12s fast timeout recovers a stuck session quickly; BLE keeps
        // the original generous budget because its GATT latency is high and variable.
        val effectiveTimeout = if (fastTransport) FAST_HANDSHAKE_TIMEOUT else timeout
        handshakeTimeout =
            scope.handledLaunch {
                delay(effectiveTimeout)
                if (serviceRepository.connectionState.value !is ConnectionState.Connecting) {
                    return@handledLaunch
                }
                if (fastTransport) {
                    // Fast transports recover more reliably by re-establishing the transport
                    // than by re-sending want_config_id on a wedged socket — the firmware's
                    // per-connection dedup would silently drop the retry. Skip the retry branch
                    // and go straight to the deterministic two-phase recovery sibling.
                    Logger.e {
                        "Handshake stall detected at Stage $stage on fast transport — " +
                            "requesting forced transport restart"
                    }
                    runSiblingHandshakeRecovery()
                    return@handledLaunch
                }
                // BLE path: attempt one retry. Note: the firmware silently drops identical
                // consecutive writes (per-connection dedup). If the first want_config_id was
                // received and the stall is on our side, the retry will be dropped and the
                // reconnect below will trigger instead — which is the right recovery in that
                // case.
                Logger.w { "Handshake stall detected at Stage $stage — retrying, then reconnecting if still stalled" }
                action()
                delay(HANDSHAKE_RETRY_TIMEOUT)
                if (serviceRepository.connectionState.value is ConnectionState.Connecting) {
                    Logger.e { "Handshake still stalled after retry, requesting forced transport restart" }
                    runSiblingHandshakeRecovery()
                }
            }
    }

    /**
     * Launches the deterministic two-phase stall-recovery sibling used by both the BLE retry-exceeded branch and the
     * TCP/USB fast-recovery branch of [startHandshakeStallGuard].
     *
     * Phase 1 flips the app-level state from Connecting to Disconnected first, guarded by the connection mutex so a
     * just-completed handshake cannot be torn down after winning the race. Phase 2 then calls
     * [RadioInterfaceService.restartTransport], whose emissions (DeviceSleep → Connected) now arrive from app-level
     * Disconnected, bypass the redundant-Connecting guard in [onConnectionChanged], and re-enter [handleConnected] to
     * restart the handshake cleanly.
     *
     * We MUST NOT call [onConnectionChanged] from the [handshakeTimeout] coroutine after launching the sibling:
     * [onConnectionChanged] cancels handshakeTimeout (the very job running this code), and any work chained after the
     * launch is not guaranteed to run. We MUST ALSO NOT leave the explicit Disconnected call in this coroutine after
     * the sibling launch — otherwise the sibling's restart emissions (DeviceSleep, then Connected) can arrive while the
     * app-level state is still Connecting, causing [onConnectionChanged]'s redundant-Connected-while-Connecting guard
     * to ignore the fresh Connected emission. That leaves the app Disconnected while transport is Connected — the same
     * split-brain this restart path is meant to break.
     *
     * By the time the sibling runs, handshakeTimeout has already completed naturally (it launched the sibling and
     * returned), so the cancellation [onConnectionChanged] would attempt is a no-op on an already-completed job — and
     * because the sibling is parented to `scope`, not to handshakeTimeout, it survives independently.
     */
    private fun runSiblingHandshakeRecovery() {
        scope.handledLaunch {
            // Surface the forced-recovery progress to the UI before the app-level Disconnected
            // transition lands, so the user sees "Reconnecting…" rather than a stale
            // "Loading node list" while the transport is being torn down and re-established.
            //
            // This progress is intentionally NOT cleared on the recovery's Disconnected window
            // (i.e. NOT in handleDisconnected or this sibling). If recovery fails permanently,
            // "Reconnecting…" may persist on the Disconnected screen until the user retries or
            // navigates away. That leak is semantically accurate UX — the app is genuinely
            // still attempting to reconnect — and clearing it here would race the deliberate
            // UX signal: handleDisconnected runs synchronously after this call inside the same
            // onConnectionChanged transition, so any clear there would clobber the signal before
            // restartTransport runs. Clearing stale progress is instead left to the next genuine
            // connection attempt's Connecting transition (handleConnected), avoiding any conflict
            // with the recovery's RECONNECTING indicator.
            serviceRepository.setConnectionProgress("Reconnecting…")
            val disconnected = onConnectionChanged(ConnectionState.Disconnected, fromState = ConnectionState.Connecting)
            if (disconnected && serviceRepository.connectionState.value is ConnectionState.Disconnected) {
                radioInterfaceService.restartTransport()
            }
        }
    }

    private fun tearDownConnection() {
        packetHandler.stopPacketQueue()
        sessionManager.clearAll() // Prevent stale per-node passkeys on reconnect.
        locationManager.stop()
        mqttManager.stop()
    }

    private fun handleDeviceSleep() {
        serviceRepository.setConnectionState(ConnectionState.DeviceSleep)
        tearDownConnection()

        if (connectTimeMsec != 0L) {
            val now = nowMillis
            val duration = now - connectTimeMsec
            connectTimeMsec = 0L
            analytics.track(
                EVENT_CONNECTED_SECONDS,
                DataPair(EVENT_CONNECTED_SECONDS, duration.milliseconds.toDouble(DurationUnit.SECONDS)),
            )
        }

        sleepTimeout =
            scope.handledLaunch {
                try {
                    val localConfig = radioConfigRepository.localConfigFlow.first()
                    val rawTimeout = (localConfig.power?.ls_secs ?: 0) + DEVICE_SLEEP_TIMEOUT_SECONDS
                    // Cap the timeout so routers or power-saving configs (ls_secs=3600) don't
                    // leave the UI stuck in DeviceSleep for over an hour.
                    val timeout = rawTimeout.coerceAtMost(MAX_SLEEP_TIMEOUT_SECONDS)
                    Logger.d { "Waiting for sleeping device, timeout=$timeout secs (raw=$rawTimeout)" }
                    delay(timeout.seconds)
                    Logger.w { "Device timed out, setting disconnected" }
                    onConnectionChanged(ConnectionState.Disconnected)
                } catch (_: CancellationException) {
                    Logger.d { "device sleep timeout cancelled" }
                }
            }
    }

    private fun handleDisconnected() {
        serviceRepository.setConnectionState(ConnectionState.Disconnected)
        tearDownConnection()

        analytics.track(
            EVENT_MESH_DISCONNECT,
            DataPair(KEY_NUM_NODES, nodeManager.nodeDBbyNodeNum.size),
            DataPair(KEY_NUM_ONLINE, nodeManager.nodeDBbyNodeNum.values.count { it.isOnline }),
        )
        analytics.track(EVENT_NUM_NODES, DataPair(KEY_NUM_NODES, nodeManager.nodeDBbyNodeNum.size))
    }

    override fun startConfigOnly() {
        val action = { packetHandler.sendToRadio(ToRadio(want_config_id = HandshakeConstants.CONFIG_NONCE)) }
        startHandshakeStallGuard(1, HANDSHAKE_TIMEOUT_STAGE1, action)
        action()
    }

    override fun startNodeInfoOnly() {
        val action = { packetHandler.sendToRadio(ToRadio(want_config_id = HandshakeConstants.NODE_INFO_NONCE)) }
        startHandshakeStallGuard(2, HANDSHAKE_TIMEOUT_STAGE2, action)
        action()
    }

    override fun onRadioConfigLoaded() {
        scope.handledLaunch {
            val queuedPackets = packetRepository.getQueuedPackets()
            queuedPackets.forEach { packet ->
                try {
                    workerManager.enqueueSendMessage(packet.id)
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    Logger.e(e) { "Failed to enqueue queued packet worker" }
                }
            }
        }
    }

    override suspend fun onNodeDbReady() {
        handshakeTimeout?.cancel()
        handshakeTimeout = null

        val myNodeNum = nodeManager.myNodeNum.value ?: 0
        // Set device time now that the full node picture is ready. Sending this during Stage 1
        // (onRadioConfigLoaded) introduced GATT write contention with the Stage 2 node-info burst.
        commandSender.sendAdmin(myNodeNum) { AdminMessage(set_time_only = nowSeconds.toInt()) }

        // Proactively seed the session passkey. The firmware embeds session_passkey in every
        // admin *response* (wantResponse=true), but set_time_only has no response. A get_owner
        // request is the lightest way to trigger a response and populate the passkey cache so
        // that subsequent write operations don't fail with ADMIN_BAD_SESSION_KEY.
        commandSender.sendAdmin(myNodeNum, wantResponse = true) { AdminMessage(get_owner_request = true) }

        // Start MQTT if enabled
        scope.handledLaunch {
            val moduleConfig = radioConfigRepository.moduleConfigFlow.first()
            mqttManager.startProxy(
                moduleConfig.mqtt?.enabled == true,
                moduleConfig.mqtt?.proxy_to_client_enabled == true,
            )
        }

        reportConnection()

        // Request history
        scope.handledLaunch {
            val moduleConfig = radioConfigRepository.moduleConfigFlow.first()
            moduleConfig.store_forward?.let {
                historyManager.requestHistoryReplay("onNodeDbReady", myNodeNum, it, "Unknown")
            }
        }

        // Request immediate LocalStats and DeviceMetrics update on connection with proper request IDs
        commandSender.requestTelemetry(commandSender.generatePacketId(), myNodeNum, TelemetryType.LOCAL_STATS.ordinal)
        commandSender.requestTelemetry(commandSender.generatePacketId(), myNodeNum, TelemetryType.DEVICE.ordinal)
    }

    /**
     * Synchronously cancels the transport-aware handshake watchdog the moment Stage 2 completes (NODE_INFO_NONCE
     * received). Does NOT replicate [onNodeDbReady]'s post-NodeDB side effects (analytics, MQTT start, history replay,
     * telemetry requests) — those remain gated on [onNodeDbReady] at the end of the async DB install block.
     *
     * See [MeshConnectionManager.onHandshakeComplete] for the full rationale.
     */
    override fun onHandshakeComplete() {
        handshakeTimeout?.cancel()
        handshakeTimeout = null
    }

    private fun reportConnection() {
        val myNode = nodeManager.getMyNodeInfo()
        val radioModel = DataPair(KEY_RADIO_MODEL, myNode?.model ?: "unknown")
        analytics.track(
            EVENT_MESH_CONNECT,
            DataPair(KEY_NUM_NODES, nodeManager.nodeDBbyNodeNum.size),
            DataPair(KEY_NUM_ONLINE, nodeManager.nodeDBbyNodeNum.values.count { it.isOnline }),
            radioModel,
        )

        // DataDog RUM custom action matching Apple's "connect" event for cross-platform analytics.
        val transportType = radioInterfaceService.getDeviceAddress()?.let { DeviceType.fromAddress(it)?.name }
        analytics.trackConnect(
            firmwareVersion = myNode?.firmwareVersion,
            transportType = transportType,
            hardwareModel = myNode?.model,
            nodes = nodeManager.nodeDBbyNodeNum.size,
            connectionRestored = connectionRestored,
        )
    }

    override fun updateTelemetry(t: Telemetry) {
        t.local_stats?.let { nodeRepository.updateLocalStats(it) }
        updateStatusNotification(t)
    }

    /**
     * True when the active transport is a TCP or USB serial connection — i.e. a transport whose firmware handshake
     * reliably completes in roughly 1s when healthy and therefore benefits from aggressive silent-restart on stall.
     * Uses the same [DeviceType.fromAddress] pattern as [reportConnection] for transport classification. BLE is
     * excluded because its GATT latency budget is high and variable enough that the long-and-retry stall-guard budgets
     * remain the right trade-off.
     */
    private fun isFastRecoveryTransport(): Boolean =
        radioInterfaceService.getDeviceAddress()?.let { DeviceType.fromAddress(it) } in
            setOf(DeviceType.TCP, DeviceType.USB)

    override fun onHandshakeProgress() {
        // No-op outside the fast-recovery envelope: BLE retains the long stall-guard budget
        // because its GATT latency is variable enough that progress signals are unreliable.
        if (!isFastRecoveryTransport()) return
        // Only re-arm while a handshake is in flight. Once Connected/Disconnected/Sleep the
        // caller has missed the window and the watchdog should not be (re-)armed.
        if (serviceRepository.connectionState.value !is ConnectionState.Connecting) return
        // Cancel any in-flight fast watchdog and re-arm it with the full fast timeout. This
        // keeps the watchdog quiet as long as meaningful progress keeps arriving within the
        // window, while a true stall still fires on schedule.
        handshakeTimeout?.cancel()
        handshakeTimeout =
            scope.handledLaunch {
                delay(FAST_HANDSHAKE_TIMEOUT)
                if (serviceRepository.connectionState.value !is ConnectionState.Connecting) {
                    return@handledLaunch
                }
                Logger.e {
                    "Fast-handshake watchdog expired after progress stalled — " + "requesting forced transport restart"
                }
                runSiblingHandshakeRecovery()
            }
    }

    override fun updateStatusNotification(telemetry: Telemetry?) {
        serviceNotifications.updateServiceStateNotification(
            serviceRepository.connectionState.value,
            telemetry = telemetry,
        )
    }

    companion object {
        private const val DEVICE_SLEEP_TIMEOUT_SECONDS = 30

        // Maximum time (in seconds) to wait for a sleeping device before declaring it
        // disconnected, regardless of the device's ls_secs configuration. Without this
        // cap, routers (ls_secs=3600) leave the UI in DeviceSleep for over an hour.
        private const val MAX_SLEEP_TIMEOUT_SECONDS = 300

        /**
         * Delay between the pre-handshake heartbeat and the want_config_id send.
         *
         * Ensures the heartbeat BLE write completes and the firmware's NimBLE callback context is warmed up before the
         * config request arrives. 100ms is well above observed ESP32 task scheduling latency (~10–50ms) while adding
         * negligible connection latency.
         */
        private const val PRE_HANDSHAKE_SETTLE_MS = 100L

        private val HANDSHAKE_TIMEOUT_STAGE1 = 30.seconds

        /**
         * Stage 2 drains the full node database, which can be significantly larger than Stage 1 config on big meshes.
         * 60 s matches the meshtastic-client SDK timeout and avoids premature stall-guard triggers on meshes with 50+
         * nodes.
         */
        private val HANDSHAKE_TIMEOUT_STAGE2 = 60.seconds

        // Shorter window for the retry attempt: if the device genuinely didn't receive the
        // first want_config_id the retry completes within a few seconds. Waiting another 30s
        // before reconnecting just delays recovery unnecessarily.
        private val HANDSHAKE_RETRY_TIMEOUT = 15.seconds

        /**
         * Transport-aware fast-recovery timeout for the handshake stall guard, applied only to TCP and USB serial
         * transports.
         *
         * Production logs on TCP/USB show a healthy firmware handshake completes in roughly 1 second, while a wedged
         * socket sits idle for the full transport-level read timeout (~30s) without any further progress. 12s sits
         * comfortably above the healthy success envelope and well below the transport read timeout, so firing a silent
         * [RadioInterfaceService.restartTransport] at 12s recovers a stuck TCP/USB session quickly without
         * false-positiving on healthy connections.
         *
         * BLE is intentionally excluded — its GATT latency budget is variable enough that the existing
         * [HANDSHAKE_TIMEOUT_STAGE1] (30s) + [HANDSHAKE_RETRY_TIMEOUT] (15s) and [HANDSHAKE_TIMEOUT_STAGE2]
         * (60s) + [HANDSHAKE_RETRY_TIMEOUT] (15s) budgets remain the right trade-off.
         */
        private val FAST_HANDSHAKE_TIMEOUT = 12.seconds

        private const val EVENT_CONNECTED_SECONDS = "connected_seconds"
        private const val EVENT_MESH_DISCONNECT = "mesh_disconnect"
        private const val EVENT_NUM_NODES = "num_nodes"
        private const val EVENT_MESH_CONNECT = "mesh_connect"

        private const val KEY_NUM_NODES = "num_nodes"
        private const val KEY_NUM_ONLINE = "num_online"
        private const val KEY_RADIO_MODEL = "radio_model"
    }
}
