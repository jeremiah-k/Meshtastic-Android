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
import kotlinx.atomicfu.atomic
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

    private val lifecycleMutex = Mutex()

    /** Invalidates connect attempts before or after ownership installation. Guarded by [lifecycleMutex]. */
    private var connectAttemptGeneration = 0L

    /** Identifies the currently installed peripheral without invalidating it for a pending replacement. */
    private var ownershipGeneration = 0L

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
        var owned: Peripheral? = null
        try {
            connectInternal(device) { owned = it }
        } catch (e: SupersededConnectionAttemptException) {
            closeAfterConnectFailure(owned, e)
        } catch (e: CancellationException) {
            closeAfterCancellation(owned, e)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            closeAfterConnectFailure(owned, e)
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ThrowsCount")
    private suspend fun connectInternal(device: BleDevice, onPeripheralCreated: (Peripheral) -> Unit) {
        val meshtasticDevice = device as? MeshtasticBleDevice ?: error("Unsupported BleDevice type: ${device::class}")
        val attemptGeneration = lifecycleMutex.withLock { ++connectAttemptGeneration }
        val autoConnect = atomic(meshtasticDevice.advertisement == null)

        /** Applies logging, observation exception handling, and platform config shared by both peripheral types. */
        fun PeripheralBuilder.commonConfig() {
            logging { applyConfig(loggingConfig, identifier = device.address.anonymize()) }
            observationExceptionHandler { cause ->
                Logger.w {
                    "[${device.address.anonymize()}] Observation failure suppressed " +
                        "(${cause::class.simpleName ?: "Exception"})"
                }
            }
            platformConfig(device) { autoConnect.value }
        }

        val p = peripheralFactory.create(meshtasticDevice) { commonConfig() }
        onPeripheralCreated(p)
        currentCoroutineContext().ensureActive()

        // Install ownership atomically with attempt validation. A disconnect or newer connect that wins first
        // invalidates the generation, so outer failure cleanup closes this peripheral without replacing the live one.
        val ownership =
            withContext(NonCancellable) {
                lifecycleMutex.withLock {
                    if (attemptGeneration != connectAttemptGeneration) {
                        OwnershipInstallResult(installed = false, previous = null, generation = null)
                    } else {
                        val old = peripheral
                        stateJob?.cancel()
                        connectionScope?.coroutineContext?.job?.cancel()
                        stateJob = null
                        connectionScope = null
                        peripheral = p
                        _deviceFlow.value = device
                        ActiveBleConnection.active = ActiveConnection(p, device.address)
                        ownershipGeneration += 1
                        OwnershipInstallResult(
                            installed = true,
                            previous = old.takeUnless { it === p },
                            generation = ownershipGeneration,
                        )
                    }
                }
            }
        if (!ownership.installed) throw SupersededConnectionAttemptException()
        val installedGeneration = checkNotNull(ownership.generation)
        closePeripheralBounded(ownership.previous, "replace")

        if (!isCurrentOwnedAttempt(p, attemptGeneration, installedGeneration)) {
            throw SupersededConnectionAttemptException()
        }

        var hasStartedConnecting = false
        val newStateJob =
            p.state
                .onEach { kableState ->
                    val mappedState = kableState.toBleConnectionState(hasStartedConnecting) ?: return@onEach
                    if (kableState is State.Connecting || kableState is State.Connected) {
                        hasStartedConnecting = true
                    }

                    publishStateIfOwned(p, installedGeneration, meshtasticDevice, mappedState)
                }
                .launchIn(scope)
        lifecycleMutex.withLock {
            if (peripheral === p && installedGeneration == ownershipGeneration) {
                stateJob = newStateJob
            } else {
                newStateJob.cancel()
            }
        }

        // Bounded to at most two attempts: direct connect, then autoConnect fallback when a fresh
        // advertisement was available. Advertisement-less devices start on the autoConnect path.
        // The outer reconnect loop (BleRadioTransport) owns the macro retry budget — see class kdoc.
        repeat(2) {
            if (!isCurrentOwnedAttempt(p, attemptGeneration, installedGeneration)) {
                throw SupersededConnectionAttemptException()
            }
            if (p.state.value is State.Connected) {
                if (!publishStateIfOwned(p, installedGeneration, meshtasticDevice, BleConnectionState.Connected)) {
                    throw SupersededConnectionAttemptException()
                }
                return
            }
            autoConnect.value =
                try {
                    val oldScope =
                        lifecycleMutex.withLock {
                            if (
                                peripheral !== p ||
                                attemptGeneration != connectAttemptGeneration ||
                                installedGeneration != ownershipGeneration
                            ) {
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
                    val installed =
                        lifecycleMutex.withLock {
                            if (
                                peripheral === p &&
                                attemptGeneration == connectAttemptGeneration &&
                                installedGeneration == ownershipGeneration
                            ) {
                                connectionScope = connectedScope
                                true
                            } else {
                                false
                            }
                        }
                    if (!installed) {
                        connectedScope.coroutineContext.job.cancel()
                        throw SupersededConnectionAttemptException()
                    }
                    false
                } catch (e: SupersededConnectionAttemptException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                    if (!isCurrentOwnedAttempt(p, attemptGeneration, installedGeneration)) {
                        throw SupersededConnectionAttemptException()
                    }
                    if (autoConnect.value) {
                        // The autoConnect fallback also failed. Publish Disconnected and let the outer reconnect loop
                        // own the macro retry budget.
                        Logger.w {
                            "[${device.address.anonymize()}] autoConnect also failed; deferring to outer reconnect loop"
                        }
                        publishStateIfOwned(
                            p,
                            installedGeneration,
                            meshtasticDevice,
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
            if (!isCurrentOwnedAttempt(p, attemptGeneration, installedGeneration)) {
                throw SupersededConnectionAttemptException()
            }
            publishStateIfOwned(
                p,
                installedGeneration,
                meshtasticDevice,
                BleConnectionState.Disconnected(DisconnectReason.ConnectionFailed),
            )
            throw NotConnectedException(
                "Failed to establish connection after bounded attempts (state=${p.state.value})",
            )
        }
        if (!publishStateIfOwned(p, installedGeneration, meshtasticDevice, BleConnectionState.Connected)) {
            throw SupersededConnectionAttemptException()
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override suspend fun connectAndAwait(device: BleDevice, timeout: Duration): BleConnectionState {
        var owned: Peripheral? = null
        val result =
            try {
                withTimeout(timeout) {
                    connectInternal(device) { owned = it }
                    BleConnectionState.Connected
                }
            } catch (_: TimeoutCancellationException) {
                BleConnectionState.Disconnected(DisconnectReason.Timeout)
            } catch (_: SupersededConnectionAttemptException) {
                BleConnectionState.Disconnected(DisconnectReason.ConnectionFailed)
            } catch (e: CancellationException) {
                closeAfterCancellation(owned, e)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Logger.w {
                    "[${device.address.anonymize()}] connectAndAwait failed (${e::class.simpleName ?: "Exception"})"
                }
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
        val localDisconnect = BleConnectionState.Disconnected(DisconnectReason.LocalDisconnect)
        val owned =
            lifecycleMutex.withLock {
                connectAttemptGeneration += 1
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

    private suspend fun publishStateIfOwned(
        owned: Peripheral,
        generation: Long,
        device: MeshtasticBleDevice,
        state: BleConnectionState,
    ): Boolean = lifecycleMutex.withLock {
        if (peripheral !== owned || generation != ownershipGeneration) return@withLock false
        device.updateState(state)
        _connectionState.value = state
        true
    }

    private suspend fun isCurrentOwnedAttempt(
        owned: Peripheral,
        attemptGeneration: Long,
        installedGeneration: Long,
    ): Boolean = lifecycleMutex.withLock {
        peripheral === owned &&
            attemptGeneration == connectAttemptGeneration &&
            installedGeneration == ownershipGeneration
    }

    private suspend fun closeAfterCancellation(owned: Peripheral?, cancellation: CancellationException): Nothing {
        if (owned != null) {
            runCatching { closeConnection(owned, BleConnectionState.Disconnected(DisconnectReason.Cancelled)) }
                .exceptionOrNull()
                ?.let(cancellation::addSuppressed)
        }
        throw cancellation
    }

    private suspend fun closeAfterConnectFailure(owned: Peripheral?, failure: Exception): Nothing {
        if (owned != null) {
            runCatching { closeConnection(owned, BleConnectionState.Disconnected(DisconnectReason.ConnectionFailed)) }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
        }
        throw failure
    }

    private suspend fun closeConnection(owned: Peripheral?, disconnectedState: BleConnectionState.Disconnected) =
        withContext(NonCancellable) {
            lifecycleMutex.withLock {
                if (owned != null && peripheral === owned) {
                    ownershipGeneration += 1
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
                }
            }

            closePeripheralBounded(owned, "disconnect")
        }

    internal class SupersededConnectionAttemptException : Exception("BLE connection attempt was superseded")

    private data class OwnershipInstallResult(val installed: Boolean, val previous: Peripheral?, val generation: Long?)

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
     * Safely disconnects and closes [target], logging any failures.
     *
     * Kable requires `close()` to release broadcast receivers on Android (Kable issue #359). Separate try/catch blocks
     * ensure `close()` always runs even if `disconnect()` throws.
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
