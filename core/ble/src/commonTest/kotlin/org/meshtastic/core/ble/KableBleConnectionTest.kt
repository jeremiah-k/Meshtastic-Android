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

import app.cash.turbine.test
import com.juul.kable.Advertisement
import com.juul.kable.NotConnectedException
import com.juul.kable.Peripheral
import com.juul.kable.State
import dev.mokkery.MockMode
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.answering.sequentiallyReturns
import dev.mokkery.answering.throws
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.mock
import dev.mokkery.verify
import dev.mokkery.verify.VerifyMode.Companion.exactly
import dev.mokkery.verifySuspend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class KableBleConnectionTest {

    @AfterTest
    fun tearDown() {
        ActiveBleConnection.active = null
    }

    @Test
    fun `cancelled stale attempt cannot close a newer peripheral`() = runTest {
        val oldState = MutableStateFlow<State>(State.Connecting.Bluetooth)
        val newState = MutableStateFlow<State>(State.Connected(backgroundScope))
        val oldPeripheral: Peripheral = mock(MockMode.autofill)
        val newPeripheral: Peripheral = mock(MockMode.autofill)
        val oldConnectStarted = CompletableDeferred<Unit>()
        every { oldPeripheral.state } returns oldState
        every { newPeripheral.state } returns newState
        everySuspend { oldPeripheral.connect() } calls
            {
                oldConnectStarted.complete(Unit)
                awaitCancellation()
            }
        everySuspend { oldPeripheral.disconnect() } returns Unit
        everySuspend { newPeripheral.disconnect() } returns Unit
        every { oldPeripheral.close() } returns Unit
        every { newPeripheral.close() } returns Unit
        val peripherals = ArrayDeque(listOf(oldPeripheral, newPeripheral))
        val connection =
            KableBleConnection(
                scope = backgroundScope,
                loggingConfig = BleLoggingConfig.Release,
                peripheralFactory = KablePeripheralFactory { _, _ -> peripherals.removeFirst() },
            )
        val oldDevice = MeshtasticBleDevice("AA:BB:CC:DD:EE:01")
        val newDevice = MeshtasticBleDevice("AA:BB:CC:DD:EE:02")

        val oldAttempt = backgroundScope.launch { connection.connectAndAwait(oldDevice, 30.seconds) }
        oldConnectStarted.await()
        assertEquals(BleConnectionState.Connected, connection.connectAndAwait(newDevice, 1.seconds))
        oldAttempt.cancelAndJoin()
        runCurrent()

        assertSame(newPeripheral, ActiveBleConnection.active?.peripheral)
        assertSame(newDevice, connection.device)
        assertEquals(BleConnectionState.Connected, connection.connectionState.value)
        verifySuspend(exactly(0)) { newPeripheral.disconnect() }
        verify(exactly(0)) { newPeripheral.close() }
    }

    @Test
    fun `disconnect cleanup cannot clear a replacement device`() = runTest {
        val oldState = MutableStateFlow<State>(State.Connected(backgroundScope))
        val newState = MutableStateFlow<State>(State.Connected(backgroundScope))
        val oldPeripheral: Peripheral = mock(MockMode.autofill)
        val newPeripheral: Peripheral = mock(MockMode.autofill)
        val oldDisconnectStarted = CompletableDeferred<Unit>()
        val releaseOldDisconnect = CompletableDeferred<Unit>()
        every { oldPeripheral.state } returns oldState
        every { newPeripheral.state } returns newState
        everySuspend { oldPeripheral.disconnect() } calls
            {
                oldDisconnectStarted.complete(Unit)
                releaseOldDisconnect.await()
            }
        everySuspend { newPeripheral.disconnect() } returns Unit
        every { oldPeripheral.close() } returns Unit
        every { newPeripheral.close() } returns Unit
        val peripherals = ArrayDeque(listOf(oldPeripheral, newPeripheral))
        val connection =
            KableBleConnection(
                scope = backgroundScope,
                loggingConfig = BleLoggingConfig.Release,
                peripheralFactory = KablePeripheralFactory { _, _ -> peripherals.removeFirst() },
            )
        val oldDevice = MeshtasticBleDevice("AA:BB:CC:DD:EE:01")
        val newDevice = MeshtasticBleDevice("AA:BB:CC:DD:EE:02")
        assertEquals(BleConnectionState.Connected, connection.connectAndAwait(oldDevice, 1.seconds))

        val disconnect = backgroundScope.launch { connection.disconnect() }
        oldDisconnectStarted.await()
        assertEquals(BleConnectionState.Connected, connection.connectAndAwait(newDevice, 1.seconds))
        releaseOldDisconnect.complete(Unit)
        disconnect.join()
        runCurrent()

        assertSame(newPeripheral, ActiveBleConnection.active?.peripheral)
        assertSame(newDevice, connection.device)
        assertEquals(BleConnectionState.Connected, connection.connectionState.value)
        verifySuspend(exactly(0)) { newPeripheral.disconnect() }
        verify(exactly(0)) { newPeripheral.close() }
    }

    @Test
    fun `disconnect publishes local terminal state to the connection and tracked device`() = runTest {
        val state = MutableStateFlow<State>(State.Connected(backgroundScope))
        val peripheral: Peripheral = mock(MockMode.autofill)
        every { peripheral.state } returns state
        everySuspend { peripheral.disconnect() } returns Unit
        every { peripheral.close() } returns Unit
        val connection =
            KableBleConnection(
                scope = backgroundScope,
                loggingConfig = BleLoggingConfig.Release,
                peripheralFactory = KablePeripheralFactory { _, _ -> peripheral },
            )
        val device = MeshtasticBleDevice("AA:BB:CC:DD:EE:01")
        val localDisconnect = BleConnectionState.Disconnected(DisconnectReason.LocalDisconnect)
        assertEquals(BleConnectionState.Connected, connection.connectAndAwait(device, 1.seconds))

        connection.disconnect()

        assertEquals(localDisconnect, connection.connectionState.value)
        assertEquals(localDisconnect, device.state.value)
        assertNull(connection.device)
        assertNull(ActiveBleConnection.active)
        verifySuspend(exactly(1)) { peripheral.disconnect() }
        verify(exactly(1)) { peripheral.close() }
    }

    @Test
    fun `disconnect invalidates an attempt before peripheral ownership is installed`() = runTest {
        val device = MeshtasticBleDevice("AA:BB:CC:DD:EE:01")

        // connect() surfaces the supersession as SupersededConnectionAttemptException.
        val fixture = deferredPeripheralFixture(backgroundScope)
        val attempt = backgroundScope.async { runCatching { fixture.connection.connect(device) } }
        fixture.factoryStarted.await()
        fixture.connection.disconnect()
        fixture.releaseFactory.complete(Unit)
        val failure = attempt.await().exceptionOrNull()

        assertIs<KableBleConnection.SupersededConnectionAttemptException>(failure)
        assertEquals(
            BleConnectionState.Disconnected(DisconnectReason.LocalDisconnect),
            fixture.connection.connectionState.value,
        )
        assertNull(fixture.connection.device)
        assertNull(ActiveBleConnection.active)
        verifySuspend(exactly(1)) { fixture.peripheral.disconnect() }
        verify(exactly(1)) { fixture.peripheral.close() }

        // The same supersession maps to a retryable failure through the connectAndAwait wrapper.
        val wrapperFixture = deferredPeripheralFixture(backgroundScope)
        val wrapperAttempt = backgroundScope.async { wrapperFixture.connection.connectAndAwait(device, 5.seconds) }
        wrapperFixture.factoryStarted.await()
        wrapperFixture.connection.disconnect()
        wrapperFixture.releaseFactory.complete(Unit)

        assertEquals(BleConnectionState.Disconnected(DisconnectReason.ConnectionFailed), wrapperAttempt.await())
        assertEquals(
            BleConnectionState.Disconnected(DisconnectReason.LocalDisconnect),
            wrapperFixture.connection.connectionState.value,
        )
        assertNull(wrapperFixture.connection.device)
        assertNull(ActiveBleConnection.active)
        verifySuspend(exactly(1)) { wrapperFixture.peripheral.disconnect() }
        verify(exactly(1)) { wrapperFixture.peripheral.close() }
    }

    @Test
    fun `replacement continues after superseded peripheral teardown times out`() = runTest {
        val oldState = MutableStateFlow<State>(State.Connected(backgroundScope))
        val newState = MutableStateFlow<State>(State.Connected(backgroundScope))
        val oldPeripheral: Peripheral = mock(MockMode.autofill)
        val newPeripheral: Peripheral = mock(MockMode.autofill)
        every { oldPeripheral.state } returns oldState
        every { newPeripheral.state } returns newState
        everySuspend { oldPeripheral.disconnect() } calls { awaitCancellation() }
        everySuspend { newPeripheral.disconnect() } returns Unit
        every { oldPeripheral.close() } returns Unit
        every { newPeripheral.close() } returns Unit
        val peripherals = ArrayDeque(listOf(oldPeripheral, newPeripheral))
        val connection =
            KableBleConnection(
                scope = backgroundScope,
                loggingConfig = BleLoggingConfig.Release,
                peripheralFactory = KablePeripheralFactory { _, _ -> peripherals.removeFirst() },
            )
        val oldDevice = MeshtasticBleDevice("AA:BB:CC:DD:EE:01")
        val newDevice = MeshtasticBleDevice("AA:BB:CC:DD:EE:02")
        assertEquals(BleConnectionState.Connected, connection.connectAndAwait(oldDevice, 1.seconds))

        assertEquals(BleConnectionState.Connected, connection.connectAndAwait(newDevice, 5.seconds))

        assertSame(newPeripheral, ActiveBleConnection.active?.peripheral)
        assertSame(newDevice, connection.device)
        assertEquals(BleConnectionState.Connected, connection.connectionState.value)
        verify(exactly(1)) { oldPeripheral.close() }
        verifySuspend(exactly(0)) { newPeripheral.disconnect() }
        verify(exactly(0)) { newPeripheral.close() }
    }

    @Test
    fun `connect failure releases peripheral ownership before rethrowing`() = runTest {
        val connectedState = MutableStateFlow<State>(State.Connected(backgroundScope))
        val survivor: Peripheral = mock(MockMode.autofill)
        every { survivor.state } returns connectedState
        everySuspend { survivor.disconnect() } returns Unit
        every { survivor.close() } returns Unit
        val failingState = MutableStateFlow<State>(State.Disconnected(status = null))
        val peripheral: Peripheral = mock(MockMode.autofill)
        every { peripheral.state } returns failingState
        everySuspend { peripheral.connect() } throws NotConnectedException("connect failed")
        everySuspend { peripheral.disconnect() } returns Unit
        every { peripheral.close() } returns Unit
        var factoryCalls = 0
        val connection =
            KableBleConnection(
                scope = backgroundScope,
                loggingConfig = BleLoggingConfig.Release,
                peripheralFactory =
                KablePeripheralFactory { _, _ ->
                    factoryCalls += 1
                    when (factoryCalls) {
                        1 -> survivor
                        2 -> throw IllegalStateException("factory failed")
                        else -> peripheral
                    }
                },
            )
        val activeDevice = MeshtasticBleDevice("AA:BB:CC:DD:EE:01")
        val replacementDevice = MeshtasticBleDevice("AA:BB:CC:DD:EE:02", advertisement = mock(MockMode.autofill))
        assertEquals(BleConnectionState.Connected, connection.connectAndAwait(activeDevice, 1.seconds))

        // A replacement that fails before creating a peripheral leaves the previous active connection intact.
        assertFailsWith<IllegalStateException> { connection.connect(replacementDevice) }
        assertSame(survivor, ActiveBleConnection.active?.peripheral)
        assertSame(activeDevice, connection.device)
        assertEquals(BleConnectionState.Connected, connection.connectionState.value)
        // The failed attempt released its unclaimed generation, so the survivor still publishes terminal state.
        connectedState.value = State.Disconnected(status = null)
        runCurrent()
        assertEquals(BleConnectionState.Disconnected(DisconnectReason.Unknown), connection.connectionState.value)

        val failure = assertFailsWith<NotConnectedException> { connection.connect(replacementDevice) }

        assertEquals("connect failed", failure.message)
        assertNull(connection.device)
        assertEquals(
            BleConnectionState.Disconnected(DisconnectReason.ConnectionFailed),
            connection.connectionState.value,
        )
        assertEquals(BleConnectionState.Disconnected(DisconnectReason.ConnectionFailed), replacementDevice.state.value)
        assertNull(ActiveBleConnection.active)
        // The stepped-over survivor and the failed attempt's own peripheral each close exactly once.
        verifySuspend(exactly(1)) { survivor.disconnect() }
        verify(exactly(1)) { survivor.close() }
        verifySuspend(exactly(1)) { peripheral.disconnect() }
        verify(exactly(1)) { peripheral.close() }
    }

    @Test
    fun `bounded connect publishes failure when Kable returns without connecting`() = runTest {
        val state = MutableStateFlow<State>(State.Disconnected(status = null))
        val firstScope = CoroutineScope(backgroundScope.coroutineContext + Job())
        val secondScope = CoroutineScope(backgroundScope.coroutineContext + Job())
        val peripheral: Peripheral = mock(MockMode.autofill)
        every { peripheral.state } returns state
        everySuspend { peripheral.connect() } sequentiallyReturns listOf(firstScope, secondScope)
        everySuspend { peripheral.disconnect() } returns Unit
        every { peripheral.close() } returns Unit
        val connection =
            KableBleConnection(
                scope = backgroundScope,
                loggingConfig = BleLoggingConfig.Release,
                peripheralFactory = KablePeripheralFactory { _, _ -> peripheral },
            )
        val device = MeshtasticBleDevice("AA:BB:CC:DD:EE:01", advertisement = mock(MockMode.autofill))

        val failure = assertFailsWith<NotConnectedException> { connection.connect(device) }

        assertTrue(failure.message.orEmpty().contains("bounded attempts"))
        assertEquals(
            BleConnectionState.Disconnected(DisconnectReason.ConnectionFailed),
            connection.connectionState.value,
        )
        assertNull(connection.device)
        assertNull(ActiveBleConnection.active)
        verifySuspend(exactly(1)) { peripheral.disconnect() }
        verify(exactly(1)) { peripheral.close() }
    }

    @Test
    fun `peripheral teardown during connect surfaces as retryable supersession not caller cancellation`() = runTest {
        val oldState = MutableStateFlow<State>(State.Connecting.Bluetooth)
        val newState = MutableStateFlow<State>(State.Connected(backgroundScope))
        val oldPeripheral: Peripheral = mock(MockMode.autofill)
        val newPeripheral: Peripheral = mock(MockMode.autofill)
        val oldConnectStarted = CompletableDeferred<Unit>()
        // Simulates Kable unwinding the in-flight connect() when the replacement tears the old peripheral down.
        val oldTeardownRequested = Job()
        every { oldPeripheral.state } returns oldState
        every { newPeripheral.state } returns newState
        everySuspend { oldPeripheral.connect() } calls
            {
                oldConnectStarted.complete(Unit)
                oldTeardownRequested.join()
                throw CancellationException("Peripheral disconnected during connect")
            }
        everySuspend { oldPeripheral.disconnect() } calls { oldTeardownRequested.cancel() }
        everySuspend { newPeripheral.disconnect() } returns Unit
        every { oldPeripheral.close() } returns Unit
        every { newPeripheral.close() } returns Unit
        val peripherals = ArrayDeque(listOf(oldPeripheral, newPeripheral))
        val connection =
            KableBleConnection(
                scope = backgroundScope,
                loggingConfig = BleLoggingConfig.Release,
                peripheralFactory = KablePeripheralFactory { _, _ -> peripherals.removeFirst() },
            )
        val oldDevice = MeshtasticBleDevice("AA:BB:CC:DD:EE:01")
        val newDevice = MeshtasticBleDevice("AA:BB:CC:DD:EE:02")

        val oldAttempt = backgroundScope.async { connection.connectAndAwait(oldDevice, 30.seconds) }
        oldConnectStarted.await()
        assertEquals(BleConnectionState.Connected, connection.connectAndAwait(newDevice, 1.seconds))

        // The attempt's caller is still active, so the peripheral-side cancellation must surface as a retryable
        // Disconnected result — never as a thrown CancellationException.
        assertEquals(BleConnectionState.Disconnected(DisconnectReason.ConnectionFailed), oldAttempt.await())
        assertSame(newPeripheral, ActiveBleConnection.active?.peripheral)
        assertSame(newDevice, connection.device)
        assertEquals(BleConnectionState.Connected, connection.connectionState.value)
        verifySuspend(exactly(0)) { newPeripheral.disconnect() }
        verify(exactly(0)) { newPeripheral.close() }
        // At-most-once close: the replacement's stepped-over teardown is the old peripheral's sole closer; the stale
        // attempt's failure cleanup identity-fails against the installed replacement and must not close a second time.
        verifySuspend(exactly(1)) { oldPeripheral.disconnect() }
        verify(exactly(1)) { oldPeripheral.close() }
    }

    @Test
    fun `superseded cancellation publishes the retryable failure it returns`() = runTest {
        val connection =
            KableBleConnection(
                scope = backgroundScope,
                loggingConfig = BleLoggingConfig.Release,
                peripheralFactory =
                KablePeripheralFactory { _, _ -> throw CancellationException("peripheral replaced mid-create") },
            )
        val device = MeshtasticBleDevice("AA:BB:CC:DD:EE:01")

        val result = connection.connectAndAwait(device, 1.seconds)

        // The returned state is retryable, so the device must not be told the attempt was caller-cancelled.
        assertEquals(BleConnectionState.Disconnected(DisconnectReason.ConnectionFailed), result)
        assertEquals(BleConnectionState.Disconnected(DisconnectReason.ConnectionFailed), device.state.value)
        assertNull(connection.device)
        assertNull(ActiveBleConnection.active)
    }

    @Test
    fun `cancelled disconnect still completes cleanup`() = runTest {
        val state = MutableStateFlow<State>(State.Connected(backgroundScope))
        val peripheral: Peripheral = mock(MockMode.autofill)
        val disconnectStarted = CompletableDeferred<Unit>()
        val releaseDisconnect = CompletableDeferred<Unit>()
        every { peripheral.state } returns state
        everySuspend { peripheral.disconnect() } calls
            {
                disconnectStarted.complete(Unit)
                releaseDisconnect.await()
            }
        every { peripheral.close() } returns Unit
        val connection =
            KableBleConnection(
                scope = backgroundScope,
                loggingConfig = BleLoggingConfig.Release,
                peripheralFactory = KablePeripheralFactory { _, _ -> peripheral },
            )
        val device = MeshtasticBleDevice("AA:BB:CC:DD:EE:01")
        val localDisconnect = BleConnectionState.Disconnected(DisconnectReason.LocalDisconnect)
        assertEquals(BleConnectionState.Connected, connection.connectAndAwait(device, 1.seconds))

        val disconnectJob = backgroundScope.launch { connection.disconnect() }
        disconnectStarted.await()
        disconnectJob.cancel()
        // Releasing the parked teardown must let the NonCancellable disconnect finish its cleanup.
        releaseDisconnect.complete(Unit)
        disconnectJob.join()
        runCurrent()

        assertEquals(localDisconnect, connection.connectionState.value)
        assertEquals(localDisconnect, device.state.value)
        assertNull(connection.device)
        assertNull(ActiveBleConnection.active)
        verifySuspend(exactly(1)) { peripheral.disconnect() }
        verify(exactly(1)) { peripheral.close() }
    }

    @Test
    fun `scan emits ble device for discovered advertisement`() = runTest {
        val advertisement: Advertisement = mock(MockMode.autofill)
        val scanner =
            TestKableBleScanner(
                scanResults =
                flowOf(
                    KableScanResult(
                        identifier = "AA:BB:CC:DD:EE:FF",
                        name = "Meshtastic",
                        advertisement = advertisement,
                    ),
                ),
            )

        val result = scanner.scan(timeout = 1.seconds).first()

        val device = assertIs<MeshtasticBleDevice>(result)
        assertEquals("AA:BB:CC:DD:EE:FF", device.address)
        assertEquals("Meshtastic", device.name)
        assertSame(advertisement, device.advertisement)
    }

    @Test
    fun `scan reserves one platform start before collecting advertisements`() = runTest {
        val reservationEvent = "scan-start-reserved"
        val collectionEvent = "advertisements-collected"
        val events = mutableListOf<String>()
        val scanner =
            TestKableBleScanner(
                scanResults = flow { events += collectionEvent },
                scanStartLimiter = BleScanStartLimiter { events += reservationEvent },
            )

        scanner.scan(timeout = 1.seconds).toList()

        assertEquals(1, events.count { it == reservationEvent })
        assertEquals(listOf(reservationEvent, collectionEvent), events)
    }

    @Test
    fun `expired timeout does not reserve a platform start`() = runTest {
        var reservations = 0
        val scanner =
            TestKableBleScanner(scanResults = emptyFlow(), scanStartLimiter = BleScanStartLimiter { reservations += 1 })

        scanner.scan(timeout = 0.seconds).toList()

        assertEquals(0, reservations)
    }

    @Test
    fun `timeout terminates scan`() = runTest {
        var cancelled = false
        val scanner =
            TestKableBleScanner(
                scanResults =
                flow {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                },
            )
        val collected = mutableListOf<BleDevice>()

        val job = backgroundScope.launch { scanner.scan(timeout = 1.seconds).toList(collected) }

        advanceTimeBy(1.seconds.inWholeMilliseconds + 1)
        advanceUntilIdle()
        job.join()

        assertTrue(job.isCompleted)
        assertTrue(cancelled)
        assertTrue(collected.isEmpty())
    }

    @Test
    fun `service uuid filter is applied`() = runTest {
        val serviceUuid = Uuid.parse("12345678-1234-1234-1234-1234567890ab")
        val scanner = TestKableBleScanner(scanResults = emptyFlow())

        scanner.scan(timeout = 1.seconds, serviceUuid = serviceUuid).toList()

        assertEquals(KableScanFilter.ServiceUuid(serviceUuid), scanner.lastFilter)
    }

    @Test
    fun `address filter is used natively only where the platform supports it`() {
        val address = "AA:BB:CC:DD:EE:FF"

        assertEquals(
            KableScanFilter.Address(address),
            resolveKableScanFilter(serviceUuid = null, address = address, supportsAddressFilter = true),
        )
        // Without native support the scan must stay unfiltered rather than carry a filter that matches nothing.
        assertEquals(
            KableScanFilter.None,
            resolveKableScanFilter(serviceUuid = null, address = address, supportsAddressFilter = false),
        )
    }

    @Test
    fun `service uuid wins over address when the platform cannot filter by address`() {
        val serviceUuid = Uuid.parse("12345678-1234-1234-1234-1234567890ab")
        val address = "AA:BB:CC:DD:EE:FF"

        assertEquals(
            KableScanFilter.Address(address),
            resolveKableScanFilter(serviceUuid = serviceUuid, address = address, supportsAddressFilter = true),
        )
        // Kable's btleplug backend matches address filters against a hardcoded null, so an address filter here would
        // silently yield no advertisements at all and BLE connect could never find the device.
        assertEquals(
            KableScanFilter.ServiceUuid(serviceUuid),
            resolveKableScanFilter(serviceUuid = serviceUuid, address = address, supportsAddressFilter = false),
        )
    }

    @Test
    fun `scan narrows to the requested address regardless of the native filter`() = runTest {
        val wanted = "AA:BB:CC:DD:EE:FF"
        val scanner =
            TestKableBleScanner(
                scanResults =
                flowOf(
                    KableScanResult(identifier = "11:22:33:44:55:66", name = "Other", advertisement = null),
                    KableScanResult(identifier = wanted, name = "Meshtastic", advertisement = null),
                    KableScanResult(identifier = "77:88:99:AA:BB:CC", name = "Another", advertisement = null),
                ),
            )

        scanner.scan(timeout = 1.seconds, address = wanted).test {
            assertEquals(wanted, awaitItem().address)
            awaitComplete()
        }
    }

    @Test
    fun `scan matches the requested address case-insensitively`() = runTest {
        // A second, non-matching advertisement keeps this honest: a scan that ignored the address entirely would
        // emit both, so the assertion proves the filter ran *and* that it matched across case.
        val scanner =
            TestKableBleScanner(
                scanResults =
                flowOf(
                    KableScanResult(identifier = "11:22:33:44:55:66", name = "Other", advertisement = null),
                    KableScanResult(identifier = "aa:bb:cc:dd:ee:ff", name = "Meshtastic", advertisement = null),
                ),
            )

        scanner.scan(timeout = 1.seconds, address = "AA:BB:CC:DD:EE:FF").test {
            assertEquals("aa:bb:cc:dd:ee:ff", awaitItem().address)
            awaitComplete()
        }
    }

    @Test
    fun `scan without an address emits every advertisement`() = runTest {
        val scanner =
            TestKableBleScanner(
                scanResults =
                flowOf(
                    KableScanResult(identifier = "11:22:33:44:55:66", name = "One", advertisement = null),
                    KableScanResult(identifier = "77:88:99:AA:BB:CC", name = "Two", advertisement = null),
                ),
            )

        scanner.scan(timeout = 1.seconds).test {
            assertEquals("11:22:33:44:55:66", awaitItem().address)
            assertEquals("77:88:99:AA:BB:CC", awaitItem().address)
            awaitComplete()
        }
    }

    @Test
    fun `scan wraps Android scanner registration failure`() = runTest {
        val scanner =
            TestKableBleScanner(
                scanResults = flow { throw IllegalStateException("Failed to start scan as app cannot be registered") },
            )

        val failure = assertFailsWith<BleScanStartException> { scanner.scan(timeout = 1.seconds).toList() }

        assertEquals(BleScanStartFailureReason.ApplicationRegistrationFailed, failure.reason)
    }

    @Test
    fun `scan wraps nested scanner registration failure`() = runTest {
        val innerCause = IllegalStateException("Failed to start scan as app cannot be registered")
        val scanner =
            TestKableBleScanner(scanResults = flow { throw IllegalStateException("Outer scanner failure", innerCause) })

        val failure = assertFailsWith<BleScanStartException> { scanner.scan(timeout = 1.seconds).toList() }

        assertEquals(BleScanStartFailureReason.ApplicationRegistrationFailed, failure.reason)
    }

    @Test
    fun `scan wraps Android scanning too frequently failure`() = runTest {
        val message = "Failed. App is scanning too frequently"
        val scanner = TestKableBleScanner(scanResults = flow { throw IllegalStateException(message) })

        val failure = assertFailsWith<BleScanStartException> { scanner.scan(timeout = 1.seconds).toList() }

        assertEquals(BleScanStartFailureReason.ScanningTooFrequently, failure.reason)
    }

    @Test
    fun `scan wraps missing scan permission failure`() = runTest {
        val message = "Missing required android.permission.ACCESS_COARSE_LOCATION for scanning"
        val scanner = TestKableBleScanner(scanResults = flow { throw IllegalStateException(message) })

        val failure = assertFailsWith<BleScanStartException> { scanner.scan(timeout = 1.seconds).toList() }

        assertEquals(BleScanStartFailureReason.MissingScanPermission, failure.reason)
    }

    @Test
    fun `scan preserves unrelated illegal state failure`() = runTest {
        val scanner =
            TestKableBleScanner(scanResults = flow { throw IllegalStateException("Unexpected scanner state") })

        val failure = assertFailsWith<IllegalStateException> { scanner.scan(timeout = 1.seconds).toList() }

        assertEquals("Unexpected scanner state", failure.message)
    }

    private fun deferredPeripheralFixture(scope: CoroutineScope): DeferredPeripheralFixture {
        val state = MutableStateFlow<State>(State.Disconnected(status = null))
        val peripheral: Peripheral = mock(MockMode.autofill)
        val factoryStarted = CompletableDeferred<Unit>()
        val releaseFactory = CompletableDeferred<Unit>()
        every { peripheral.state } returns state
        everySuspend { peripheral.disconnect() } returns Unit
        every { peripheral.close() } returns Unit
        val connection =
            KableBleConnection(
                scope = scope,
                loggingConfig = BleLoggingConfig.Release,
                peripheralFactory =
                KablePeripheralFactory { _, _ ->
                    factoryStarted.complete(Unit)
                    releaseFactory.await()
                    peripheral
                },
            )
        return DeferredPeripheralFixture(connection, peripheral, factoryStarted, releaseFactory)
    }

    private data class DeferredPeripheralFixture(
        val connection: KableBleConnection,
        val peripheral: Peripheral,
        val factoryStarted: CompletableDeferred<Unit>,
        val releaseFactory: CompletableDeferred<Unit>,
    )

    private class TestKableBleScanner(
        private val scanResults: Flow<KableScanResult>,
        private val scanStartLimiter: BleScanStartLimiter = NoOpBleScanStartLimiter,
    ) : KableBleScanner(BleLoggingConfig.Release) {
        var lastFilter: KableScanFilter? = null
            private set

        override suspend fun reserveScanStart() = scanStartLimiter.reserveStart()

        override fun advertisements(filter: KableScanFilter): Flow<KableScanResult> {
            lastFilter = filter
            return scanResults
        }
    }
}
