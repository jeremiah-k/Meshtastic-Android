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
package org.meshtastic.core.ble

import co.touchlab.kermit.Logger
import com.juul.kable.NotConnectedException
import com.juul.kable.Peripheral
import com.juul.kable.PeripheralBuilder
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import com.juul.kable.writeWithoutResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.core.common.util.ioDispatcher
import org.meshtastic.core.model.util.anonymize
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Creates the platform [Peripheral] for a device. Internal seam so ownership transitions are testable in commonTest.
 */
internal fun interface KablePeripheralFactory {
    suspend fun create(device: MeshtasticBleDevice, builderAction: PeripheralBuilder.() -> Unit): Peripheral
}

private object DefaultKablePeripheralFactory : KablePeripheralFactory {
    override suspend fun create(device: MeshtasticBleDevice, builderAction: PeripheralBuilder.() -> Unit): Peripheral =
        device.advertisement?.let { advertisement -> Peripheral(advertisement, builderAction) }
            ?: createPeripheral(device.address, builderAction)
}

/** [BleService] implementation backed by a Kable [Peripheral] for a specific GATT service. */
class KableBleService(private val peripheral: Peripheral, private val serviceUuid: Uuid) : BleService {
    override fun hasCharacteristic(characteristic: BleCharacteristic): Boolean = peripheral.services.value?.any { svc ->
        svc.serviceUuid == serviceUuid && svc.characteristics.any { it.characteristicUuid == characteristic.uuid }
    } == true

    override fun discoveredCharacteristicUuids(): List<Uuid> = peripheral.services.value
        ?.find { it.serviceUuid == serviceUuid }
        ?.characteristics
        ?.map { it.characteristicUuid } ?: emptyList()

    override fun observe(characteristic: BleCharacteristic) =
        peripheral.observe(characteristicOf(serviceUuid, characteristic.uuid))

    override fun observe(characteristic: BleCharacteristic, onSubscription: suspend () -> Unit) =
        peripheral.observe(characteristicOf(serviceUuid, characteristic.uuid), onSubscription)

    override suspend fun read(characteristic: BleCharacteristic): ByteArray =
        peripheral.read(characteristicOf(serviceUuid, characteristic.uuid))

    override fun preferredWriteType(characteristic: BleCharacteristic): BleWriteType {
        val service = peripheral.services.value?.find { it.serviceUuid == serviceUuid }
        val char = service?.characteristics?.find { it.characteristicUuid == characteristic.uuid }
        return if (char?.properties?.writeWithoutResponse == true) {
            BleWriteType.WITHOUT_RESPONSE
        } else {
            BleWriteType.WITH_RESPONSE
        }
    }

    override suspend fun write(characteristic: BleCharacteristic, data: ByteArray, writeType: BleWriteType) {
        peripheral.write(
            characteristicOf(serviceUuid, characteristic.uuid),
            data,
            when (writeType) {
                BleWriteType.WITH_RESPONSE -> WriteType.WithResponse
                BleWriteType.WITHOUT_RESPONSE -> WriteType.WithoutResponse
            },
        )
    }
}

/**
 * [BleConnection] implementation using Kable for cross-platform BLE communication.
 *
 * Manages peripheral lifecycle, connection state tracking, and GATT service profile access.
 *
 * Connection attempts follow Kable's recommended pattern from the SensorTag sample: use a direct connect when a fresh
 * advertisement is available, then fall back to `autoConnect = true` on failure. Advertisement-less devices start on
 * the `autoConnect` path. At most two attempts are made per [connect] call — the caller ([BleRadioTransport]) owns the
 * macro-level retry/backoff loop.
 *
 * ## Ownership model
 * Peripheral ownership is guarded by [lifecycleMutex] around a single [attemptGeneration] counter. The generation is
 * bumped at connect entry and at disconnect entry; an attempt stays valid only while it still owns the installed
 * peripheral of its recorded generation. Every teardown targets the attempt's captured [Peripheral] instance — never
 * the shared field — and ownership is cleared only under the mutex identity check, so a stale attempt can neither kill
 * nor clear a newer connection, and exactly one terminal [BleConnectionState.Disconnected] is published per ownership
 * clear. Each peripheral is also closed at most once by application logic: whoever performs the identity-checked clear
 * bounded-closes the captured instance, while a superseded attempt closes only its never-installed orphan at the
 * create/install checkpoints. No platform call (peripheral creation, [Peripheral.connect], [Peripheral.disconnect],
 * [Peripheral.close]) and no bounded wait ever runs while the mutex is held. Teardown is bounded by
 * [PERIPHERAL_TEARDOWN_TIMEOUT] inside [NonCancellable], so [disconnect] completes — and still closes the peripheral —
 * even when its own caller is cancelled.
 */
@Suppress("TooManyFunctions")
class KableBleConnection
internal constructor(
    private val scope: CoroutineScope,
    private val loggingConfig: BleLoggingConfig,
    private val peripheralFactory: KablePeripheralFactory,
) : BleConnection {

    constructor(
        scope: CoroutineScope,
        loggingConfig: BleLoggingConfig,
    ) : this(scope, loggingConfig, DefaultKablePeripheralFactory)

    @Volatile private var peripheral: Peripheral? = null

    @Volatile private var stateJob: Job? = null

    @Volatile private var connectionScope: CoroutineScope? = null

    /** Serializes ownership transitions; never held across a platform call or a bounded wait. */
    private val lifecycleMutex = Mutex()

    /** Invalidates in-flight attempts. Bumped at connect entry and disconnect entry. */
    private var attemptGeneration = 0L

    companion object {
        /** Settle delay between a direct connect failure and the autoConnect fallback attempt. */
        private val AUTOCONNECT_FALLBACK_DELAY = 1.seconds

        /** Bounds best-effort GATT teardown so a wedged old peripheral cannot stall reconnect indefinitely. */
        private val PERIPHERAL_TEARDOWN_TIMEOUT = 2.seconds
    }

    private val _deviceFlow = MutableStateFlow<BleDevice?>(null)
    override val deviceFlow: StateFlow<BleDevice?> = _deviceFlow.asStateFlow()

    override val device: BleDevice?
        get() = _deviceFlow.value

    private val _connectionState =
        MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected(DisconnectReason.Unknown))
    override val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    override suspend fun connect(device: BleDevice) {
        val meshtasticDevice = device as? MeshtasticBleDevice ?: error("Unsupported BleDevice type: ${device::class}")
        var owned: Peripheral? = null
        try {
            connectInternal(meshtasticDevice) { owned = it }
        } catch (e: SupersededConnectionAttemptException) {
            closeAfterConnectFailure(meshtasticDevice, owned, e)
        } catch (e: CancellationException) {
            closeAfterCancellation(meshtasticDevice, owned, e)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            closeAfterConnectFailure(meshtasticDevice, owned, e)
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ThrowsCount")
    private suspend fun connectInternal(device: MeshtasticBleDevice, onPeripheralCreated: (Peripheral) -> Unit) {
        val (previousGen, attemptGen) =
            lifecycleMutex.withLock {
                val previous = attemptGeneration
                attemptGeneration += 1
                previous to attemptGeneration
            }
        var autoConnect = device.advertisement == null

        /** Applies logging, observation exception handling, and platform config shared by both peripheral types. */
        fun PeripheralBuilder.commonConfig() {
            logging { applyConfig(loggingConfig, identifier = device.address.anonymize()) }
            observationExceptionHandler { cause ->
                Logger.w {
                    "[${device.address.anonymize()}] Observation failure suppressed " +
                        "(${cause::class.simpleName ?: "Exception"})"
                }
            }
            platformConfig(device) { autoConnect }
        }

        val p =
            try {
                peripheralFactory.create(device) { commonConfig() }
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                // A pre-install exit must restore the installed owner's generation or its state observer stays muted.
                withContext(NonCancellable) { releaseUnclaimedGeneration(attemptGen, previousGen) }
                throw e
            }
        onPeripheralCreated(p)
        try {
            currentCoroutineContext().ensureActive()
        } catch (e: CancellationException) {
            withContext(NonCancellable) { releaseUnclaimedGeneration(attemptGen, previousGen) }
            // Created but never installed: no replacer or disconnect knows this instance, so this attempt is its
            // sole closer. The caller's failure cleanup below identity-fails against the field and safely skips.
            closePeripheralBounded(p, "orphan")
            throw e
        }

        // Install ownership atomically with attempt validation. A disconnect or newer connect that wins first
        // invalidates the generation; this attempt then closes its never-installed orphan and throws Superseded.
        var previous: Peripheral? = null
        val installed =
            withContext(NonCancellable) {
                lifecycleMutex.withLock {
                    if (attemptGen != attemptGeneration) {
                        false
                    } else {
                        previous = peripheral
                        stateJob?.cancel()
                        connectionScope?.coroutineContext?.job?.cancel()
                        stateJob = null
                        connectionScope = null
                        peripheral = p
                        _deviceFlow.value = device
                        ActiveBleConnection.active = ActiveConnection(p, device.address)
                        true
                    }
                }
            }
        if (!installed) {
            // Never registered, so neither a replacer's stepped-over close nor a disconnect targets this instance;
            // close it here. The outer failure cleanup identity-fails against the installed owner and skips.
            closePeripheralBounded(p, "orphan")
            throw SupersededConnectionAttemptException()
        }
        // Outside the lock: closes only the stepped-over peripheral, never the just-installed one.
        closePeripheralBounded(previous, "replace")

        if (!ownsAttempt(p, attemptGen)) throw SupersededConnectionAttemptException()

        // A peripheral already Connecting or Connected at observer launch has started connecting; StateFlow
        // conflation may otherwise deliver a terminal Disconnected as the collector's first emission, muting it.
        var hasStartedConnecting = p.state.value is State.Connecting || p.state.value is State.Connected
        val newStateJob =
            p.state
                .onEach { kableState ->
                    val mappedState = kableState.toBleConnectionState(hasStartedConnecting) ?: return@onEach
                    if (kableState is State.Connecting || kableState is State.Connected) {
                        hasStartedConnecting = true
                    }

                    publishStateIfOwned(p, attemptGen, device, mappedState)
                }
                .launchIn(scope)
        lifecycleMutex.withLock {
            if (peripheral === p && attemptGen == attemptGeneration) {
                stateJob = newStateJob
            } else {
                newStateJob.cancel()
            }
        }

        // Bounded to at most two attempts: direct connect, then autoConnect fallback when a fresh
        // advertisement was available. Advertisement-less devices start on the autoConnect path.
        // The outer reconnect loop (BleRadioTransport) owns the macro retry budget — see class kdoc.
        repeat(2) {
            if (!ownsAttempt(p, attemptGen)) throw SupersededConnectionAttemptException()
            if (p.state.value is State.Connected) {
                if (!publishStateIfOwned(p, attemptGen, device, BleConnectionState.Connected)) {
                    throw SupersededConnectionAttemptException()
                }
                return
            }
            autoConnect =
                try {
                    val oldScope =
                        lifecycleMutex.withLock {
                            if (peripheral !== p || attemptGen != attemptGeneration) {
                                throw SupersededConnectionAttemptException()
                            }
                            connectionScope.also { connectionScope = null }
                        }
                    oldScope?.let { scopeToCancel ->
                        Logger.d {
                            "[${device.address.anonymize()}] Cancelling previous connectionScope before reconnect"
                        }
                        scopeToCancel.coroutineContext.job.cancel()
                    }
                    val connectedScope = p.connect()
                    val stored =
                        lifecycleMutex.withLock {
                            if (peripheral === p && attemptGen == attemptGeneration) {
                                connectionScope = connectedScope
                                true
                            } else {
                                false
                            }
                        }
                    if (!stored) {
                        connectedScope.coroutineContext.job.cancel()
                        throw SupersededConnectionAttemptException()
                    }
                    false
                } catch (e: SupersededConnectionAttemptException) {
                    throw e
                } catch (@Suppress("SwallowedException") e: CancellationException) {
                    // A CancellationException with the caller still active means this attempt's peripheral was torn
                    // down by a replacement — a retryable supersession, not caller shutdown.
                    currentCoroutineContext().ensureActive()
                    throw SupersededConnectionAttemptException()
                } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                    if (!ownsAttempt(p, attemptGen)) {
                        throw SupersededConnectionAttemptException()
                    }
                    if (autoConnect) {
                        // The autoConnect fallback also failed. Publish Disconnected and let the outer reconnect loop
                        // own the macro retry budget.
                        Logger.w {
                            "[${device.address.anonymize()}] autoConnect also failed; deferring to outer reconnect loop"
                        }
                        publishStateIfOwned(
                            p,
                            attemptGen,
                            device,
                            BleConnectionState.Disconnected(DisconnectReason.ConnectionFailed),
                        )
                        throw e
                    }
                    Logger.d { "[${device.address.anonymize()}] Direct connect failed, falling back to autoConnect" }
                    delay(AUTOCONNECT_FALLBACK_DELAY)
                    true
                }
        }
        // Bounded loop may exit without reaching Connected if both connect() calls
        // returned without throwing but state hasn't settled. The original while loop
        // would have kept iterating; the bounded loop defers to the outer reconnect policy.
        // Guard against false-positive Connected by verifying state here.
        if (p.state.value !is State.Connected) {
            if (!ownsAttempt(p, attemptGen)) throw SupersededConnectionAttemptException()
            publishStateIfOwned(
                p,
                attemptGen,
                device,
                BleConnectionState.Disconnected(DisconnectReason.ConnectionFailed),
            )
            throw NotConnectedException(
                "Failed to establish connection after bounded attempts (state=${p.state.value})",
            )
        }
        if (!publishStateIfOwned(p, attemptGen, device, BleConnectionState.Connected)) {
            throw SupersededConnectionAttemptException()
        }
    }

    /**
     * Restores the generation the installed owner recorded before this attempt bumped it. Only exits that happen before
     * ownership is installed call this; the guard skips when a newer attempt or a disconnect has already claimed the
     * counter, so the surviving peripheral's state observer keeps publishing through its own generation.
     */
    private suspend fun releaseUnclaimedGeneration(attemptGen: Long, previousGen: Long) {
        lifecycleMutex.withLock { if (attemptGeneration == attemptGen) attemptGeneration = previousGen }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override suspend fun connectAndAwait(device: BleDevice, timeout: Duration): BleConnectionState {
        val meshtasticDevice = device as? MeshtasticBleDevice ?: error("Unsupported BleDevice type: ${device::class}")
        var owned: Peripheral? = null
        val result =
            try {
                withTimeout(timeout) {
                    connectInternal(meshtasticDevice) { owned = it }
                    BleConnectionState.Connected
                }
            } catch (_: TimeoutCancellationException) {
                meshtasticDevice.updateState(BleConnectionState.Disconnected(DisconnectReason.Timeout))
                BleConnectionState.Disconnected(DisconnectReason.Timeout)
            } catch (_: SupersededConnectionAttemptException) {
                meshtasticDevice.updateState(BleConnectionState.Disconnected(DisconnectReason.ConnectionFailed))
                BleConnectionState.Disconnected(DisconnectReason.ConnectionFailed)
            } catch (e: CancellationException) {
                // Mirror connect()'s discriminator: a caller-active CancellationException means the attempt lost the
                // ownership race to a replacement (retryable), not caller shutdown.
                try {
                    currentCoroutineContext().ensureActive()
                } catch (callerCancellation: CancellationException) {
                    closeAfterCancellation(meshtasticDevice, owned, callerCancellation)
                }
                meshtasticDevice.updateState(BleConnectionState.Disconnected(DisconnectReason.ConnectionFailed))
                BleConnectionState.Disconnected(DisconnectReason.ConnectionFailed)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Logger.w {
                    "[${device.address.anonymize()}] connectAndAwait failed (${e::class.simpleName ?: "Exception"})"
                }
                meshtasticDevice.updateState(BleConnectionState.Disconnected(DisconnectReason.ConnectionFailed))
                BleConnectionState.Disconnected(DisconnectReason.ConnectionFailed)
            }

        if (result is BleConnectionState.Disconnected) {
            // A failed Kable connect can leave the physical GATT connected. Release this attempt before the outer
            // reconnect policy starts another scan or connection.
            closeConnection(owned, result)
        }
        return result
    }

    override suspend fun disconnect() {
        // NonCancellable: completing cleanup is disconnect()'s contract, even against its own canceller. A cancelled
        // disconnect that skipped the generation bump or the ownership clear would leave a live GATT the transport
        // believes is gone; the bounded teardown below caps how long completion can take.
        withContext(NonCancellable) {
            val localDisconnect = BleConnectionState.Disconnected(DisconnectReason.LocalDisconnect)
            val owned =
                lifecycleMutex.withLock {
                    attemptGeneration += 1
                    val current = peripheral
                    if (current == null) {
                        (_deviceFlow.value as? MeshtasticBleDevice)?.updateState(localDisconnect)
                        _connectionState.value = localDisconnect
                        _deviceFlow.value = null
                    }
                    current
                }
            if (owned != null) {
                closeConnection(owned, localDisconnect)
            }
        }
    }

    /** True when [owned] is still the installed peripheral of the still-current attempt generation. */
    private suspend fun ownsAttempt(owned: Peripheral, generation: Long): Boolean =
        lifecycleMutex.withLock { peripheral === owned && generation == attemptGeneration }

    /**
     * Publishes [state] only while [owned] still holds ownership of [generation]; attempt-local, never cross-attempt.
     */
    private suspend fun publishStateIfOwned(
        owned: Peripheral,
        generation: Long,
        device: MeshtasticBleDevice,
        state: BleConnectionState,
    ): Boolean = lifecycleMutex.withLock {
        if (peripheral !== owned || generation != attemptGeneration) {
            false
        } else {
            device.updateState(state)
            _connectionState.value = state
            true
        }
    }

    private suspend fun closeAfterCancellation(
        device: MeshtasticBleDevice,
        owned: Peripheral?,
        cancellation: CancellationException,
    ): Nothing {
        // The attempt's own device object is attempt-scoped, so its terminal state is published unconditionally;
        // only the shared connection state is gated on still owning the peripheral.
        device.updateState(BleConnectionState.Disconnected(DisconnectReason.Cancelled))
        if (owned != null) {
            runCatching { closeConnection(owned, BleConnectionState.Disconnected(DisconnectReason.Cancelled)) }
                .exceptionOrNull()
                ?.let(cancellation::addSuppressed)
        }
        throw cancellation
    }

    private suspend fun closeAfterConnectFailure(
        device: MeshtasticBleDevice,
        owned: Peripheral?,
        failure: Exception,
    ): Nothing {
        device.updateState(BleConnectionState.Disconnected(DisconnectReason.ConnectionFailed))
        if (owned != null) {
            runCatching { closeConnection(owned, BleConnectionState.Disconnected(DisconnectReason.ConnectionFailed)) }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
        }
        throw failure
    }

    /**
     * Clears [owned]'s registration exactly once (identity-checked under the mutex, publishing exactly one terminal
     * [disconnectedState]) and bounded-closes the captured instance outside the lock only when that clear happened. The
     * clear winner is the instance's sole closer: a stale caller whose peripheral was already replaced — or whose
     * peripheral was never installed — skips both the clear and the close, because whoever won the ownership race
     * already closed the instance (a superseded attempt closes its never-installed orphan at the create/install
     * checkpoints).
     */
    private suspend fun closeConnection(owned: Peripheral?, disconnectedState: BleConnectionState.Disconnected) =
        withContext(NonCancellable) {
            val cleared =
                lifecycleMutex.withLock {
                    if (owned != null && peripheral === owned) {
                        // Publish before cancelling the collector so downstream consumers cannot miss the terminal
                        // state when the peripheral's own Disconnected emission races teardown.
                        (_deviceFlow.value as? MeshtasticBleDevice)?.updateState(disconnectedState)
                        _connectionState.value = disconnectedState
                        stateJob?.cancel()
                        connectionScope?.coroutineContext?.job?.cancel()
                        stateJob = null
                        connectionScope = null
                        peripheral = null
                        if (ActiveBleConnection.active?.peripheral === owned) {
                            ActiveBleConnection.active = null
                        }
                        _deviceFlow.value = null
                        true
                    } else {
                        false
                    }
                }

            // At-most-once close: the mutex serializes the identity decision, so concurrent teardown racing on the
            // same instance can never both observe cleared == true.
            if (cleared) closePeripheralBounded(owned, "disconnect")
        }

    internal class SupersededConnectionAttemptException : Exception("BLE connection attempt was superseded")

    @Suppress("ThrowsCount")
    override suspend fun <T> profile(
        serviceUuid: Uuid,
        timeout: Duration,
        setup: suspend CoroutineScope.(BleService) -> T,
    ): T {
        val p = peripheral ?: error("Not connected")
        val cScope = connectionScope ?: error("No active connection scope")
        val service = KableBleService(p, serviceUuid)
        return withTimeout(timeout) {
            // Shared BLE profile guard: wait for Kable service discovery before handing out the service, and map a
            // connection-scope shutdown during caller setup to NotConnectedException instead of waiting for timeout.
            withContext(ioDispatcher) {
                val profileExecution = async {
                    p.services.first { it != null }
                    cScope.setup(service)
                }

                val disconnectHandle =
                    cScope.coroutineContext.job.invokeOnCompletion {
                        profileExecution.cancel(CancellationException("Connection lost during BLE profile execution"))
                    }

                try {
                    profileExecution.await()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    if (!cScope.coroutineContext.job.isActive) {
                        throw NotConnectedException("Connection lost during BLE profile execution")
                    }
                    throw e
                } finally {
                    disconnectHandle.dispose()
                    profileExecution.cancel()
                }
            }
        }
    }

    override fun maximumWriteValueLength(writeType: BleWriteType): Int? = peripheral?.negotiatedMaxWriteLength()

    override fun requestHighConnectionPriority(): Boolean = peripheral?.requestHighConnectionPriority() == true

    override fun requestBalancedConnectionPriority(): Boolean = peripheral?.requestBalancedConnectionPriority() == true

    override fun invalidateServiceCache(): Boolean = peripheral?.refreshGattCache() == true

    /**
     * Disconnects and closes [target] with the teardown bounded by [PERIPHERAL_TEARDOWN_TIMEOUT]. Runs outside the
     * lifecycle mutex and only on the captured [target], so a stale closer can never touch a newer peripheral.
     */
    private suspend fun closePeripheralBounded(target: Peripheral?, tag: String) {
        if (target == null) return
        val completed =
            withContext(NonCancellable) {
                withTimeoutOrNull(PERIPHERAL_TEARDOWN_TIMEOUT) {
                    safeClosePeripheral(target, tag)
                    true
                } ?: false
            }
        if (!completed) Logger.w { "[$tag] Timed out closing peripheral" }
    }

    /**
     * Safely disconnects and closes [target], logging any failures.
     *
     * Kable requires `close()` to release broadcast receivers on Android (Kable issue #359). Separate try/catch blocks
     * ensure `close()` always runs even if `disconnect()` throws.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun safeClosePeripheral(target: Peripheral, tag: String) {
        // Deferred rethrow instead of a throw inside finally: detekt forbids exceptions from finally blocks,
        // and a close() CancellationException must not discard a disconnect() cancellation.
        var cancellation: CancellationException? = null
        try {
            target.disconnect()
        } catch (_: NotConnectedException) {
            // Silence "Disconnect requested" which Kable throws if already disconnected.
        } catch (e: CancellationException) {
            cancellation = e
        } catch (e: Exception) {
            Logger.w { "[$tag] Failed to disconnect peripheral (${e::class.simpleName ?: "Exception"})" }
        }
        try {
            target.close()
        } catch (e: CancellationException) {
            cancellation = cancellation ?: e
        } catch (e: Exception) {
            Logger.w { "[$tag] Failed to close peripheral (${e::class.simpleName ?: "Exception"})" }
        }
        cancellation?.let { throw it }
    }
}
