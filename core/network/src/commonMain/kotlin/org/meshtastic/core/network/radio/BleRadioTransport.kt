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
@file:Suppress("TooManyFunctions", "TooGenericExceptionCaught")

package org.meshtastic.core.network.radio

import co.touchlab.kermit.Logger
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.core.ble.BleConnection
import org.meshtastic.core.ble.BleConnectionFactory
import org.meshtastic.core.ble.BleConnectionState
import org.meshtastic.core.ble.BleDevice
import org.meshtastic.core.ble.BleScanner
import org.meshtastic.core.ble.BleWriteType
import org.meshtastic.core.ble.BluetoothRepository
import org.meshtastic.core.ble.DisconnectReason
import org.meshtastic.core.ble.MeshtasticBleConstants.SERVICE_UUID
import org.meshtastic.core.ble.MeshtasticRadioProfile
import org.meshtastic.core.ble.classifyBleException
import org.meshtastic.core.ble.retryBleOperation
import org.meshtastic.core.ble.toMeshtasticRadioProfile
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.model.RadioNotConnectedException
import org.meshtastic.core.model.util.anonymize
import org.meshtastic.core.network.transport.HeartbeatSender
import org.meshtastic.core.repository.RadioInterfaceService
import org.meshtastic.core.repository.RadioTransport
import org.meshtastic.core.repository.RadioTransportCallback
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val SCAN_RETRY_COUNT = 3
private const val MAX_LOG_CAUSE_DEPTH = 6
private val SCAN_RETRY_DELAY = 1.seconds
private val CONNECTION_TIMEOUT = 15.seconds

/**
 * Delay after writing a heartbeat before re-polling FROMRADIO.
 *
 * The ESP32 firmware processes TORADIO writes asynchronously (NimBLE callback → FreeRTOS main task queue →
 * `handleToRadio()` → `heartbeatReceived = true`). The immediate drain trigger in
 * [KableMeshtasticRadioProfile.sendToRadio] fires before this completes, so the `queueStatus` response is not yet
 * available. 200 ms is well above observed ESP32 task scheduling latency (~10–50 ms) while remaining imperceptible to
 * the user.
 */
private val HEARTBEAT_DRAIN_DELAY = 200.milliseconds

/**
 * Bounded scan duration used by both discovery paths in [findDevice]:
 * - Bonded devices get one address-filtered scan before falling back to the bonded handle.
 * - Non-bonded retries each use this duration.
 *
 * Keeping the bonded path to one scanner registration is important on Android, which throttles applications that start
 * BLE scans too frequently. A single 5s window still covers multiple advertising intervals for typical power-save slots
 * (~1–2s each), resolves immediately when the target advertises, and avoids consuming two scan starts per reconnect. If
 * the scan misses, [findDevice] falls back to the bonded handle and [attemptConnection] keeps that patient
 * `autoConnect` path bounded through [CONNECTION_TIMEOUT].
 */
internal val SCAN_TIMEOUT = 5.seconds
private val GATT_CLEANUP_TIMEOUT = 5.seconds

/**
 * Bounded wait for the connectionState StateFlow to reflect Connected after connectAndAwait returns.
 *
 * In normal operation the observer coroutine lags by milliseconds. If a fatal session failure fires before Connected is
 * observed and the StateFlow was already at a stale Disconnected value (no re-emit), this timeout prevents
 * [attemptConnection] from hanging indefinitely.
 */
private val CONNECTED_GATE_TIMEOUT = 5.seconds

/**
 * Bounded wait for FROMNUM CCCD subscription before proceeding with the handshake.
 *
 * In normal operation the observe callback fires within milliseconds. If a fatal fromRadio failure fires before
 * subscriptionReady completes, this timeout prevents a permanent hang.
 */
private val SUBSCRIPTION_READY_TIMEOUT = 5.seconds

/**
 * Delay after onConnect before downgrading BLE connection priority to Balanced.
 *
 * The initial config drain (fromRadio burst) typically completes within 2–5 seconds on most devices, but slower radios
 * (ESP32 with large node databases, many channels, or dense position history) can take significantly longer. 30 seconds
 * provides generous margin while still ensuring we don't sustain the 7.5 ms connection interval indefinitely, which
 * significantly increases battery draw on both the phone and the radio.
 */
private val PRIORITY_DOWNGRADE_DELAY = 30.seconds

/**
 * Settle delay after disconnecting to let the BLE stack release GATT resources before reconnecting post cache
 * invalidation.
 */
private val POST_INVALIDATION_RECONNECT_DELAY = 500.milliseconds

/**
 * A [RadioTransport] implementation for BLE devices using the common BLE abstractions (which are powered by Kable).
 *
 * This class handles the high-level connection lifecycle for Meshtastic radios over BLE, including:
 * - Bonding and discovery.
 * - Automatic reconnection logic.
 * - MTU and connection parameter monitoring.
 * - Routing raw byte packets between the radio and [RadioTransportCallback].
 *
 * @param scope The coroutine scope to use for launching coroutines.
 * @param scanner The BLE scanner.
 * @param bluetoothRepository The Bluetooth repository.
 * @param connectionFactory The BLE connection factory.
 * @param callback The [RadioTransportCallback] to use for handling radio events.
 * @param address The BLE address of the device to connect to.
 */
class BleRadioTransport(
    private val scope: CoroutineScope,
    private val scanner: BleScanner,
    private val bluetoothRepository: BluetoothRepository,
    private val connectionFactory: BleConnectionFactory,
    private val callback: RadioTransportCallback,
    private val address: String,
) : RadioTransport {

    private val anonymizedAddress = address.anonymize()
    private val logAddress = "[$anonymizedAddress]"

    // Detached cleanup scope for last-ditch GATT teardown from the exception handler.
    // Must NOT be a child of `scope`: when an uncaught exception fires in connectionScope,
    // upper layers often tear down `scope` immediately. Launching cleanup on `scope` then
    // races the cancellation and may never start, leaking BluetoothGatt (status 133 on
    // the next reconnect). This scope is cancelled in close() once our own disconnect
    // has completed and the safety net is no longer needed.
    private val cleanupScope: CoroutineScope = CoroutineScope(SupervisorJob() + scope.coroutineContext.minusKey(Job))

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Logger.w { "$logAddress Uncaught exception in connectionScope cause=${throwable.safeLogType()}" }
        if (throwable !is CancellationException) {
            // Record the cause BEFORE the CAS so there is no window where another coroutine
            // sees sessionFailed == true but sessionFailureCause == null. first-cause is
            // preserved: a concurrent loser only overwrites if still null.
            recordSessionFailureCause(throwable)
            if (sessionFailed.compareAndSet(expect = false, update = true)) {
                radioService = null
                isFullyConnected = false
                val (isPermanent, msg) = throwable.toDisconnectReason()
                callback.onDisconnect(isPermanent, errorMessage = msg)
            }
        }
        cleanupScope.launch {
            try {
                bleConnection.disconnect()
            } catch (e: Exception) {
                Logger.w { "$logAddress Failed to disconnect in exception handler cause=${e.safeLogType()}" }
            }
        }
    }

    private val connectionScope: CoroutineScope =
        CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext.job) + exceptionHandler)
    private val bleConnection: BleConnection = connectionFactory.create(connectionScope, address)
    private val writeMutex: Mutex = Mutex()

    @Volatile private var connectionStartTime: Long = 0

    private val packetsReceived = atomic(0)

    private val packetsSent = atomic(0)

    private val bytesReceived = atomic(0L)

    private val bytesSent = atomic(0L)

    @Volatile private var isFullyConnected = false
    private var connectionJob: Job? = null

    // Guards against duplicate callbacks when multiple writes or a write + fromRadio failure
    // fire against the same stale session. Reset at the start of each attemptConnection().
    // Write-path failures are serialized by writeMutex; fromRadio/logRadio .catch handlers run
    // on the flow collector coroutine and may race. Atomic CAS guarantees first-writer-wins —
    // only the caller that wins the compareAndSet(false, true) fires onDisconnect.
    private val sessionFailed = atomic(false)

    // Captures the exception that caused handleFailure() to tear down the session, so
    // attemptConnection() can distinguish an internal-failure disconnect from a genuine
    // LocalDisconnect (user-initiated or clean close). When non-null, the reconnect policy
    // treats the disconnect as unintentional and applies backoff escalation.
    @Volatile private var sessionFailureCause: Throwable? = null

    // Never give up while the user has this device selected. Higher layers (SharedRadioInterfaceService)
    // own the explicit-disconnect lifecycle and will close() us when the user picks a different device or
    // toggles the connection off; until then, retry forever with the policy's exponential-backoff cap (60 s).
    private val reconnectPolicy = BleReconnectPolicy(maxFailures = Int.MAX_VALUE)

    private val heartbeatSender =
        HeartbeatSender(
            sendToRadio = ::handleSendToRadio,
            afterHeartbeat = {
                delay(HEARTBEAT_DRAIN_DELAY)
                radioService?.requestDrain()
            },
            logTag = anonymizedAddress,
        )

    override fun start() {
        connect()
    }

    // --- Connection & Discovery Logic ---

    /** Robustly finds the device. Checks bonded devices, preferring a fresh scan result when available. */
    @Suppress("ReturnCount")
    private suspend fun findDevice(): BleDevice {
        val bondedDevice =
            bluetoothRepository.state.value.bondedDevices.firstOrNull { it.address.equals(address, ignoreCase = true) }

        if (bondedDevice != null) {
            // Use one bounded, address-filtered scan. Splitting this into a short scan plus an escalated scan consumed
            // two Android scanner registrations per reconnect and could hit SCAN_FAILED_SCANNING_TOO_FREQUENTLY when a
            // user switched devices while the reconnect policy and the Connections screen were also scanning.
            Logger.i { "$logAddress Bonded device found; scanning once for a fresh advertisement" }
            scanForFreshDevice(SCAN_TIMEOUT)?.let {
                Logger.i { "$logAddress Fresh advertisement found; using scanned device" }
                return it
            }

            // If the scan misses, fall back to the bonded handle. Bonded-only devices have no fresh advertisement, so
            // Kable uses autoConnect=true and Android can patiently wait for the device to advertise again.
            // This remains bounded by CONNECTION_TIMEOUT in connectAndAwait(), after which BleReconnectPolicy owns
            // retry/backoff.
            Logger.w {
                "$logAddress No fresh advertisement within $SCAN_TIMEOUT; " +
                    "falling back to bonded handle for bounded autoConnect"
            }
            return bondedDevice
        }

        // Non-bonded path: preserve existing retry behavior (SCAN_RETRY_COUNT attempts at SCAN_TIMEOUT).
        Logger.i { "$logAddress Device not found in bonded list, scanning" }
        repeat(SCAN_RETRY_COUNT) { attempt ->
            scanForFreshDevice(SCAN_TIMEOUT)?.let {
                return it
            }
            if (attempt < SCAN_RETRY_COUNT - 1) {
                delay(SCAN_RETRY_DELAY)
            }
        }
        Logger.w { "$logAddress Selected radio was not found after BLE scan retries" }
        throw RadioNotConnectedException("Selected radio was not found")
    }

    /**
     * Performs a single BLE scan attempt for the selected [address] and returns the first matching [BleDevice], or null
     * if the scan times out or fails.
     *
     * One scan attempt only — no retry, no backoff. Both bonded and non-bonded paths in [findDevice] share this
     * primitive so retry policy stays centralized:
     * - Bonded: one address-filtered [SCAN_TIMEOUT] attempt before [findDevice] returns the bonded handle.
     * - Non-bonded: [SCAN_RETRY_COUNT] attempts at [SCAN_TIMEOUT] with [SCAN_RETRY_DELAY] between attempts.
     *
     * The outer [withTimeoutOrNull] is binding: the scanner receives [timeout] as a hint, but this coroutine resumes on
     * its own schedule regardless of when (or whether) the scanner honors it.
     *
     * [CancellationException] is rethrown — coroutine cancellation must never be swallowed.
     */
    private suspend fun scanForFreshDevice(timeout: Duration): BleDevice? = try {
        withTimeoutOrNull(timeout) {
            // Pass both service UUID and address; the scanner picks whichever filter the platform can honour
            // (address natively on Android, service UUID elsewhere) and narrows to the address itself.
            scanner.scan(timeout = timeout, serviceUuid = SERVICE_UUID, address = address).first {
                it.address.equals(address, ignoreCase = true)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.v { "$logAddress Scan failed (timeout=$timeout) cause=${e.safeLogType()}" }
        null
    }

    private fun connect() {
        connectionJob =
            connectionScope.launch {
                reconnectPolicy.execute(
                    attempt = {
                        try {
                            attemptConnection()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            val failureTime = (nowMillis - connectionStartTime).milliseconds
                            Logger.w { "$logAddress Failed to connect after $failureTime cause=${e.safeLogType()}" }
                            BleReconnectPolicy.Outcome.Failed(e)
                        }
                    },
                    onTransientDisconnect = { error ->
                        // Guard: if handleFailure already emitted the disconnect callback for this
                        // session (sessionFailed CAS won), don't emit a duplicate from the policy.
                        // Silent recovery: no errorMessage — the reconnect loop is still retrying, so
                        // a modal dialog would just confuse the user. The warning log is the
                        // observability surface for this transient event.
                        if (!sessionFailed.value) {
                            error?.let { failure ->
                                Logger.w {
                                    "$logAddress BLE reconnect attempt failed; continuing automatic retry " +
                                        "cause=${failure.safeLogType()}"
                                }
                            }
                            callback.onDisconnect(isPermanent = false)
                        }
                    },
                    onPermanentDisconnect = { error ->
                        if (!sessionFailed.value) {
                            val msg = error?.toDisconnectReason()?.second ?: "Device unreachable"
                            callback.onDisconnect(isPermanent = true, errorMessage = msg)
                        }
                    },
                )
            }
    }

    /**
     * Performs a single BLE connect-and-wait cycle.
     *
     * Finds the device, bonds if needed, connects, discovers services, and waits for disconnect. Returns a
     * [BleReconnectPolicy.Outcome] describing how the connection ended.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    private suspend fun attemptConnection(): BleReconnectPolicy.Outcome {
        connectionStartTime = nowMillis
        sessionFailed.value = false
        sessionFailureCause = null
        Logger.i { "$logAddress BLE connection attempt started" }

        val device = findDevice()

        bondDeviceBeforeConnect(device)

        val state = bleConnection.connectAndAwait(device, CONNECTION_TIMEOUT)

        if (state !is BleConnectionState.Connected) {
            Logger.w { "$logAddress BLE connection completed without a connected state: $state" }
            throw RadioNotConnectedException("Failed to connect to the selected radio")
        }

        // Post-OTA GATT cache invalidation: the device rebooted with potentially different
        // BLE service table handles. Refresh Android's cache and reconnect to force fresh
        // service discovery before proceeding with profile setup.
        val radioServiceForCache = callback as? RadioInterfaceService
        if (radioServiceForCache?.consumeGattCacheInvalidationRequest() == true) {
            val invalidated = bleConnection.invalidateServiceCache()
            Logger.d { "$logAddress Post-OTA GATT cache invalidation requested: $invalidated" }
            if (invalidated) {
                Logger.i { "$logAddress Reconnecting after GATT cache refresh to force service rediscovery" }
                bleConnection.disconnect()
                delay(POST_INVALIDATION_RECONNECT_DELAY)
                val reconnectState = bleConnection.connectAndAwait(device, CONNECTION_TIMEOUT)
                if (reconnectState !is BleConnectionState.Connected) {
                    throw RadioNotConnectedException(
                        "Failed to reconnect after post-OTA GATT cache refresh (state=$reconnectState)",
                    )
                }
                Logger.i { "$logAddress Reconnected after GATT cache refresh" }
            }
        }

        val gattConnectedAt = nowMillis
        isFullyConnected = true
        onConnected()

        discoverServicesAndSetupCharacteristics()

        // If a fatal session failure (fromRadio/logRadio error) forced disconnect during setup,
        // skip the Connected gate — return a retryable failure so BleReconnectPolicy handles it.
        val setupFailure = sessionFailureCause
        if (setupFailure != null) {
            Logger.w {
                "$logAddress Session failed during profile setup — returning failed outcome " +
                    "cause=${setupFailure.safeLogType()}"
            }
            return BleReconnectPolicy.Outcome.Failed(setupFailure)
        }

        // Wait for the StateFlow to actually reflect Connected before watching for the next
        // Disconnected. connectAndAwait returns synchronously based on the underlying Kable
        // peripheral state, but our _connectionState observer runs on a separate coroutine and
        // may lag. Without this gate the next .first { Disconnected } below could match the
        // *previous* cycle's stale Disconnected value and fire immediately, breaking reconnect.
        //
        // RACE GUARD: A fatal fromRadio/logRadio failure can land AFTER the sessionFailureCause
        // check above but BEFORE connectionState reaches Connected. handleFailure() forces
        // bleConnection.disconnect(), which should emit Disconnected — but if the StateFlow was
        // already at a stale Disconnected value, it may NOT re-emit (StateFlow suppresses
        // duplicate values). A bounded timeout ensures we cannot hang: if Connected doesn't
        // arrive within CONNECTED_GATE_TIMEOUT, we check sessionFailureCause and return a
        // retryable Failed outcome.
        val connectedReached =
            withTimeoutOrNull(CONNECTED_GATE_TIMEOUT) {
                bleConnection.connectionState.first { it is BleConnectionState.Connected }
            }
        if (connectedReached == null) {
            val failure = sessionFailureCause ?: RuntimeException("Timed out waiting for Connected state gate")
            Logger.w {
                "$logAddress Session failed before Connected gate — returning failed outcome " +
                    "cause=${failure.safeLogType()}"
            }
            // CRITICAL: force GATT cleanup before returning Failed so we don't start a new
            // attempt over an uncleared session. Without this, a timeout caused by flow lag or
            // a stale-Disconnected state mismatch would leave a live/half-live GATT handle behind.
            radioService = null
            isFullyConnected = false
            withContext(NonCancellable) {
                try {
                    bleConnection.disconnect()
                } catch (ignored: Exception) {
                    Logger.w {
                        "$logAddress disconnect() failed during Connected-gate timeout cleanup " +
                            "cause=${ignored.safeLogType()}"
                    }
                }
            }
            return BleReconnectPolicy.Outcome.Failed(failure)
        }

        // Suspend until the next Disconnected emission. We deliberately do NOT wrap this in a
        // coroutineScope { launchIn(...); first(...) } pattern: launching a hot StateFlow
        // collector inside coroutineScope hangs the scope after .first returns (the launched
        // collector never completes naturally, and coroutineScope waits for all children).
        val disconnectedState =
            bleConnection.connectionState.filterIsInstance<BleConnectionState.Disconnected>().first()
        val disconnectReason = disconnectedState.reason
        if (isFullyConnected) {
            isFullyConnected = false
            onDisconnected()
        }

        Logger.i { "$logAddress BLE connection dropped (reason: $disconnectReason), preparing to reconnect" }

        // Internal session failures (write/read exceptions that triggered handleFailure →
        // disconnect) must NOT be treated as intentional/user disconnects — the reconnect policy
        // needs to escalate backoff for these.
        val internalFailure = sessionFailureCause
        if (internalFailure != null) {
            Logger.w {
                "$logAddress Session forced disconnect due to internal failure cause=${internalFailure.safeLogType()}"
            }
        }
        val wasIntentional =
            if (internalFailure != null) {
                false
            } else {
                disconnectReason is DisconnectReason.LocalDisconnect
            }
        val connectionUptime = (nowMillis - gattConnectedAt).milliseconds
        val wasStable = connectionUptime >= reconnectPolicy.minStableConnection

        if (!wasStable && !wasIntentional) {
            Logger.w {
                "$logAddress Connection lasted only $connectionUptime " +
                    "(< ${reconnectPolicy.minStableConnection}) — treating as unstable"
            }
        }

        return BleReconnectPolicy.Outcome.Disconnected(wasStable = wasStable, wasIntentional = wasIntentional)
    }

    private suspend fun bondDeviceBeforeConnect(device: BleDevice) {
        if (bluetoothRepository.isBonded(address)) return

        // Bond before connecting: firmware may require an encrypted link, and without a bond Android fails with
        // status 5 or 133. Non-Android targets use repository-specific no-op behavior.
        Logger.i { "$logAddress Device not bonded, initiating bonding" }
        try {
            bluetoothRepository.bond(device)
            Logger.i { "$logAddress Bonding successful" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // bond() failed. Android may still have recorded the bond, in which case we can safely continue into GATT
            // setup. If the device is still not bonded, continuing would fail later with a cryptic status (5/133), so
            // stop now and let BleReconnectPolicy own the retry/backoff.
            if (bluetoothRepository.isBonded(address)) {
                Logger.w {
                    "$logAddress Bonding reported failure but device is bonded; continuing cause=${e.safeLogType()}"
                }
            } else {
                Logger.w {
                    "$logAddress Bonding failed and device is still not bonded; stopping connection attempt " +
                        "cause=${e.safeLogType()}"
                }
                throw RadioNotConnectedException("Bonding failed and device is still not bonded", e)
            }
        }
    }

    private suspend fun onConnected() {
        try {
            bleConnection.deviceFlow.first()?.let { device ->
                val rssi = retryBleOperation(tag = anonymizedAddress) { device.readRssi() }
                Logger.d { "$logAddress Connection confirmed. Initial RSSI: ${rssi?.let { "$it dBm" } ?: "unknown"}" }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w { "$logAddress Failed to read initial connection RSSI cause=${e.safeLogType()}" }
        }
    }

    private fun onDisconnected() {
        radioService = null
        // Atomic first-writer-wins: if handleFailure already claimed this session's failure
        // callback (or another onDisconnected raced), CAS returns false and we skip the
        // duplicate. The forced disconnect() from handleFailure causes Kable to emit
        // Disconnected, which routes here — without this guard the UI would see two
        // onDisconnect calls (one with the real error message from handleFailure, one
        // generic from here).
        val firstWriter = sessionFailed.compareAndSet(expect = false, update = true)
        Logger.i { "$logAddress BLE disconnected - ${formatSessionStats()}" }
        if (firstWriter) {
            // Signal immediately so the UI reflects the disconnect while reconnect continues.
            callback.onDisconnect(isPermanent = false)
        }
    }

    @Suppress("LongMethod", "ThrowsCount")
    private suspend fun discoverServicesAndSetupCharacteristics() {
        try {
            bleConnection.profile(serviceUuid = SERVICE_UUID) { service ->
                val radioService = service.toMeshtasticRadioProfile()

                radioService.fromRadio
                    .onEach { packet ->
                        Logger.v { "$logAddress Received packet fromRadio (${packet.size} bytes)" }
                        dispatchPacket(packet)
                    }
                    .catch { e ->
                        Logger.w { "$logAddress Error in fromRadio flow cause=${e.safeLogType()}" }
                        handleFailure(e)
                    }
                    .launchIn(this)

                radioService.logRadio
                    .onEach { packet ->
                        Logger.v { "$logAddress Received packet logRadio (${packet.size} bytes)" }
                        dispatchPacket(packet)
                    }
                    .catch { e ->
                        Logger.w { "$logAddress Error in logRadio flow cause=${e.safeLogType()}" }
                        handleFailure(e)
                    }
                    .launchIn(this)

                this@BleRadioTransport.radioService = radioService

                Logger.i { "$logAddress Profile service active and characteristics subscribed" }

                // Wait for FROMNUM CCCD write before triggering the Meshtastic handshake.
                // Bounded: if fromRadio fails before subscriptionReady completes, handleFailure
                // sets sessionFailureCause. The timeout also prevents a hang if FROMNUM observe
                // never completes for reasons other than a fatal exception (e.g., firmware doesn't
                // send CCCD confirmation). We MUST abort setup on timeout — proceeding without
                // subscription readiness creates a half-initialized session.
                val subscriptionReady =
                    withTimeoutOrNull(SUBSCRIPTION_READY_TIMEOUT) {
                        radioService.awaitSubscriptionReady()
                        true
                    } ?: false
                if (!subscriptionReady || sessionFailed.value) {
                    val cause =
                        sessionFailureCause ?: RuntimeException("Timed out waiting for FROMNUM subscription readiness")
                    Logger.w {
                        val reason = if (!subscriptionReady) "timed out" else "failed"
                        "$logAddress Subscription wait $reason — aborting setup cause=${cause.safeLogType()}"
                    }
                    throw cause
                }

                // Log negotiated MTU for diagnostics
                val maxLen = bleConnection.maximumWriteValueLength(BleWriteType.WITHOUT_RESPONSE)
                Logger.i { "$logAddress BLE Radio Session Ready. Max write length (WITHOUT_RESPONSE): $maxLen bytes" }

                requestHighPriorityAndScheduleDowngrade()

                // Guard: if handleFailure fired during setup (e.g., fromRadio/logRadio fatal
                // exception after subscriptionReady completed but before this line), do NOT call
                // onConnect — it would set Connected state on a dead session. handleFailure has
                // already emitted the disconnect callback.
                //
                // ORDERING NOTE: callback.onConnect() is emitted here, BEFORE attemptConnection()
                // re-confirms the link via the Connected gate (see CONNECTED_GATE_TIMEOUT). In the
                // rare case the gate times out (connectionState observer-coroutine lag, or a stale
                // Disconnected value the StateFlow does not re-emit), the UI has briefly seen
                // Connected. This is deliberate and acceptable:
                //   - The gate-timeout path returns Outcome.Failed, which drives BleReconnectPolicy
                //     (Retry backoff immediately; onTransientDisconnect → DeviceSleep once
                //     consecutiveFailures reaches failureThreshold, default 3).
                //   - The timeout path nulls radioService and forces bleConnection.disconnect()
                //     under NonCancellable, so handleSendToRadio() fails fast against a null
                //     service and the next attempt starts over a clean GATT handle.
                // The net worst case is a brief Connected indication while the transport cycles a
                // sub-threshold retry — a cosmetic UX lag, not a correctness or data issue.
                // Deferring onConnect until after the gate would require a structural refactor of
                // the profile-setup callback and introduce its own races, so the current ordering
                // is retained.
                if (!sessionFailed.value) {
                    this@BleRadioTransport.callback.onConnect()
                } else {
                    Logger.w { "$logAddress Session failed during setup — skipping onConnect" }
                }
            }
        } catch (e: CancellationException) {
            // Scope was cancelled externally — still ensure GATT cleanup runs so we don't
            // leak a BluetoothGatt handle and trigger GATT status 133 on the next attempt.
            radioService = null
            isFullyConnected = false
            withContext(NonCancellable) {
                try {
                    bleConnection.disconnect()
                } catch (ignored: Exception) {
                    Logger.w {
                        "$logAddress disconnect() failed during cancellation cleanup cause=${ignored.safeLogType()}"
                    }
                }
            }
            throw e
        } catch (e: Exception) {
            Logger.w { "$logAddress Profile service discovery or operation failed cause=${e.safeLogType()}" }
            // Clear stale state so the next attempt starts clean — if failure happened after
            // radioService assignment but before callback.onConnect(), stale state would survive.
            radioService = null
            isFullyConnected = false
            withContext(NonCancellable) {
                try {
                    bleConnection.disconnect()
                } catch (ignored: Exception) {
                    Logger.w { "$logAddress disconnect() failed after profile error cause=${ignored.safeLogType()}" }
                }
            }
            throw e // Re-throw so attemptConnection() returns Outcome.Failed(e) for policy backoff.
        }
    }

    /**
     * Requests high BLE connection priority for the initial config burst, then schedules a downgrade to balanced
     * priority after [PRIORITY_DOWNGRADE_DELAY] to conserve battery.
     */
    private suspend fun CoroutineScope.requestHighPriorityAndScheduleDowngrade() {
        if (bleConnection.requestHighConnectionPriority()) {
            Logger.d { "$logAddress Requested high BLE connection priority" }
            // Wait for the connection parameter update before starting heavy traffic.
            delay(1.seconds)
        }
        launch {
            delay(PRIORITY_DOWNGRADE_DELAY)
            if (bleConnection.requestBalancedConnectionPriority()) {
                Logger.d { "$logAddress Downgraded to balanced BLE connection priority" }
            }
        }
    }

    @Volatile private var radioService: MeshtasticRadioProfile? = null

    // --- RadioTransport Implementation ---

    /**
     * Sends a packet to the radio with retry support.
     *
     * Write-failure policy: any non-cancellation exception from a failed write (after exhausting [retryBleOperation]
     * retries) is treated as fatal to the current BLE session. Production logs show long-running sessions eventually
     * failing writes with [NotConnectedException] after hundreds of successful writes — by that point the GATT handle
     * is stale and retrying in-place cannot recover. Calling [handleFailure] forces a full GATT teardown + reconnect
     * cycle, which is the only reliable recovery for a dead session. Transient single-write blips are absorbed by
     * [retryBleOperation]'s 3-attempt retry before reaching this catch.
     *
     * @param p The packet to send.
     */
    override fun handleSendToRadio(p: ByteArray) {
        // Fast-path check: skip coroutine launch entirely if no transport is active.
        if (radioService == null) {
            Logger.w { "$logAddress toRadio characteristic unavailable, can't send data" }
            return
        }
        connectionScope.launch {
            writeMutex.withLock {
                // Re-read radioService UNDER the lock — handleFailure may have nulled it
                // between the outer check and lock acquisition. Without this, a queued send
                // can retry writes against a stale/dead profile.
                val currentService =
                    radioService
                        ?: run {
                            Logger.w { "$logAddress toRadio characteristic cleared during write queue" }
                            return@withLock
                        }
                try {
                    retryBleOperation(tag = anonymizedAddress, retryWhile = { currentService === radioService }) {
                        currentService.sendToRadio(p)
                    }
                    val sent = packetsSent.incrementAndGet()
                    val txBytes = bytesSent.addAndGet(p.size.toLong())
                    Logger.v {
                        "$logAddress Wrote packet #$sent " + "to toRadio (${p.size} bytes, total TX: $txBytes bytes)"
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Guard: only call handleFailure if this write was against the CURRENT session.
                    // If radioService was replaced (new reconnect cycle) or cleared (handleFailure
                    // already ran), this is a stale write from the old session — silently discard.
                    if (currentService === radioService) {
                        Logger.w {
                            "$logAddress Failed to write packet to toRadioCharacteristic after " +
                                "${packetsSent.value} successful writes cause=${e.safeLogType()}"
                        }
                        handleFailure(e)
                    } else {
                        Logger.d {
                            "$logAddress Stale write failure ignored because the session was replaced " +
                                "cause=${e.safeLogType()}"
                        }
                    }
                }
            }
        }
    }

    override fun keepAlive() {
        // Delegate to HeartbeatSender which sends a ToRadio heartbeat with a unique nonce
        // so the firmware resets its power-saving idle timer. After sending, it schedules
        // a delayed re-drain to pick up the queueStatus response.
        connectionScope.launch { heartbeatSender.sendHeartbeat() }
    }

    /** Closes the connection to the device. */
    override suspend fun close() {
        Logger.i { "$logAddress Disconnecting. ${formatSessionStats()}" }
        connectionScope.cancel()
        // GATT cleanup must run under NonCancellable so a cancelled caller cannot skip it,
        // which would leak BluetoothGatt and trigger status 133 on the next reconnect.
        // Using withContext (not runBlocking) keeps the caller's thread free — this is
        // critical when close() is invoked from the main thread during process shutdown.
        withContext(NonCancellable) {
            try {
                withTimeoutOrNull(GATT_CLEANUP_TIMEOUT) { bleConnection.disconnect() }
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Logger.w { "$logAddress Failed to disconnect in close() cause=${e.safeLogType()}" }
            }
        }
        // Our own disconnect succeeded — the exception-handler safety net is no longer
        // needed. Cancel the detached cleanup scope so it doesn't outlive us in tests
        // or process lifetime.
        cleanupScope.cancel()
    }

    private fun dispatchPacket(packet: ByteArray) {
        val received = packetsReceived.incrementAndGet()
        val rxBytes = bytesReceived.addAndGet(packet.size.toLong())
        Logger.v { "$logAddress Dispatching packet #$received " + "(${packet.size} bytes, total RX: $rxBytes bytes)" }
        callback.handleFromRadio(packet)
    }

    /**
     * Preserves the first session-failure cause across concurrent failures. Called before the [sessionFailed] CAS in
     * [handleFailure] and [exceptionHandler] to eliminate the window where [sessionFailed] is true but
     * [sessionFailureCause] is still null.
     */
    private fun recordSessionFailureCause(throwable: Throwable) {
        if (sessionFailureCause == null) sessionFailureCause = throwable
    }

    private fun handleFailure(throwable: Throwable) {
        // CancellationException signals intentional scope cancellation (close() called).
        // Never surface it as a user-facing disconnect error.
        if (throwable is CancellationException) return

        // Record the cause BEFORE the CAS so there is no window where another coroutine
        // sees sessionFailed == true but sessionFailureCause == null. first-cause is
        // preserved: a concurrent loser only overwrites if still null.
        recordSessionFailureCause(throwable)

        // Deduplicate via atomic CAS: only the first failure per connection attempt triggers
        // session teardown. Heartbeat writes that arrive after the first failure must not spam
        // callbacks. compareAndSet(false, true) returns true iff THIS caller is the first.
        if (!sessionFailed.compareAndSet(expect = false, update = true)) return

        // Tear down stale session state immediately so future writes fail-fast without retrying
        // against a dead GATT handle.
        radioService = null
        isFullyConnected = false

        val (isPermanent, msg) = throwable.toDisconnectReason()
        // Silent recovery for non-permanent failures: the transport tears down stale GATT state
        // and reconnects automatically, so surfacing a modal for a transient session failure is
        // confusing UX. Permanent failures (pairing, missing characteristic, etc.) remain
        // user-facing.
        callback.onDisconnect(isPermanent, errorMessage = if (isPermanent) msg else null)

        Logger.w { "$logAddress Session failure — forcing cleanup for reconnect cause=${throwable.safeLogType()}" }

        // Force GATT disconnect on the detached cleanupScope (matching the pattern used by
        // the exceptionHandler defined above). This causes Kable's connectionState
        // to emit Disconnected, unblocking attemptConnection so BleReconnectPolicy iterates.
        cleanupScope.launch {
            try {
                bleConnection.disconnect()
            } catch (e: Exception) {
                Logger.w { "$logAddress Failed to disconnect after session failure cause=${e.safeLogType()}" }
            }
        }
    }

    /** Formats a one-line session statistics summary for logging. */
    private fun formatSessionStats(): String {
        val uptime = if (connectionStartTime > 0) nowMillis - connectionStartTime else 0
        return "Uptime: ${uptime}ms, " +
            "Packets RX: ${packetsReceived.value} (${bytesReceived.value} bytes), " +
            "Packets TX: ${packetsSent.value} (${bytesSent.value} bytes)"
    }

    private fun Throwable.toDisconnectReason(): Pair<Boolean, String> {
        classifyBleException()?.let {
            return it.isPermanent to it.message
        }

        val msg =
            when (this) {
                is RadioNotConnectedException -> this.message ?: "Device not found"

                is NoSuchElementException,
                is IllegalArgumentException,
                -> "Required characteristic missing"

                else -> this.message ?: this::class.simpleName ?: "Unknown"
            }
        return false to msg
    }

    /** Retains actionable BLE status and exception type without forwarding platform text or device identifiers. */
    private fun Throwable.safeLogType(): String {
        val outerType = this::class.simpleName ?: "Exception"
        var current: Throwable? = this
        var classifiedType: String? = null
        var remainingDepth = MAX_LOG_CAUSE_DEPTH
        while (current != null && classifiedType == null && remainingDepth > 0) {
            val failure = current
            val info = failure.classifyBleException()
            if (info != null) {
                val bleType = failure::class.simpleName ?: "BleException"
                val classified = info.gattStatus?.let { "$bleType(status=$it)" } ?: bleType
                classifiedType = if (failure === this) classified else "$outerType(cause=$classified)"
            } else {
                current = failure.cause
            }
            remainingDepth--
        }
        return classifiedType ?: outerType
    }
}
