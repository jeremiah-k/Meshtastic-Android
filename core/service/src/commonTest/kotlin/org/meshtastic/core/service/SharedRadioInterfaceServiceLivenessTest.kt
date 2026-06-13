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
package org.meshtastic.core.service

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import dev.mokkery.MockMode
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.model.DeviceType
import org.meshtastic.core.network.repository.NetworkRepository
import org.meshtastic.core.repository.PlatformAnalytics
import org.meshtastic.core.repository.RadioTransport
import org.meshtastic.core.repository.RadioTransportFactory
import org.meshtastic.core.testing.FakeBluetoothRepository
import org.meshtastic.core.testing.FakeRadioPrefs
import org.meshtastic.core.testing.FakeRadioTransport
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Service-level tests for [SharedRadioInterfaceService] liveness detection.
 *
 * Uses a controllable clock via [SharedRadioInterfaceService.clockMillis] so [onConnect], [handleFromRadio], and
 * [checkLiveness] all share one coherent time source — no mixing of real wall-clock with test time.
 *
 * A counting transport factory returns a fresh [FakeRadioTransport] per createTransport() call so we can observe how
 * many restarts actually occurred.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedRadioInterfaceServiceLivenessTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val dispatchers = CoroutineDispatchers(io = testDispatcher, main = testDispatcher, default = testDispatcher)

    @BeforeTest
    fun setUp() {
        // processLifecycle.coroutineScope uses Dispatchers.Main.immediate internally;
        // JVM tests must install a Main dispatcher or get IllegalStateException.
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        // CRITICAL: Destroy the lifecycle to cancel processLifecycle.coroutineScope and all
        // leaked collectors (devAddr, bluetoothRepository.state, networkRepository.networkAvailable).
        // Without this, those infinite flow collectors keep the forked test JVM alive after tests
        // complete, causing Gradle to hang at subsequent :core:*:allTests tasks.
        processLifecycleOwner.destroy()
        // Allow pending cancellation to propagate before resetting the Main dispatcher.
        // Without this, resetMain() can prevent the cancellation from reaching collectors.
        testDispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    private val bluetoothRepository = FakeBluetoothRepository()
    private val radioPrefs = FakeRadioPrefs()

    private val networkRepository: NetworkRepository = mock(MockMode.autofill)
    private val analytics: PlatformAnalytics = mock(MockMode.autofill)

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)

        init {
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
        }

        override val lifecycle: Lifecycle
            get() = registry
    }

    private val processLifecycleOwner = TestLifecycleOwner()

    /**
     * Test-only [RadioTransport] whose [close] suspends on a [CompletableDeferred] gate.
     *
     * The liveness restart path calls `stopTransportLocked` → `currentTransport.close()` inside a launched coroutine.
     * With the default [FakeRadioTransport], `close()` returns without suspending, so under
     * [UnconfinedTestDispatcher] the entire restart completes synchronously during `checkLiveness()` and a second
     * `checkLiveness()` never observes an in-flight restart. By awaiting a gate inside `close()`, this fake holds the
     * restart genuinely suspended mid-flight, letting a test deterministically exercise the in-flight overlap window
     * and prove the second check does not stack another restart/close.
     *
     * The gate is shared across instances; once completed by the test, any pending or subsequent `close()` resumes
     * immediately.
     */
    private class GatedFakeRadioTransport(
        private val closeGate: CompletableDeferred<Unit>,
    ) : RadioTransport {
        var closeCalled = false
            private set
        var closeCount = 0
            private set
        var closeCompletedCount = 0
            private set

        // Liveness restart skips the polite-disconnect frame (sendPoliteDisconnect = false), so no
        // outbound data is expected; satisfy the contract with a no-op.
        override fun handleSendToRadio(p: ByteArray) = Unit

        override suspend fun close() {
            closeCalled = true
            closeCount++
            // Suspend here until the test releases the gate, holding the restart in-flight.
            closeGate.await()
            closeCompletedCount++
        }
    }

    /** Controllable clock — tests advance this manually so all time comparisons are deterministic. */
    private var clock: Long = 0L

    /** Tracks all transports created by the factory so we can count restarts and inspect sent data. */
    private val createdTransports = mutableListOf<FakeRadioTransport>()
    private val transportFactory: RadioTransportFactory = mock(MockMode.autofill)

    /**
     * Creates a [SharedRadioInterfaceService] with a controllable clock and a factory that returns a fresh
     * [FakeRadioTransport] per createTransport() call. After construction, calls [connect] then explicitly [onConnect]
     * to bring the service to Connected state (FakeRadioTransport does not call onConnect itself).
     *
     * Pass [transportProvider] to swap in a custom test double (e.g. a suspending-close fake) instead of the default
     * [FakeRadioTransport]; the default records each created transport in [createdTransports].
     */
    private fun createConnectedService(
        address: String,
        transportProvider: () -> RadioTransport = { FakeRadioTransport().also { createdTransports.add(it) } },
    ): SharedRadioInterfaceService {
        every { networkRepository.networkAvailable } returns MutableStateFlow(true)
        every { networkRepository.resolvedList } returns MutableSharedFlow()
        every { analytics.isPlatformServicesAvailable } returns false
        every { transportFactory.supportedDeviceTypes } returns listOf(DeviceType.BLE)
        every { transportFactory.isMockTransport() } returns false
        every { transportFactory.isAddressValid(any()) } returns true
        every { transportFactory.toInterfaceAddress(any(), any()) } returns address
        every { transportFactory.createTransport(any(), any()) } calls
            {
                transportProvider()
            }

        radioPrefs.setDevAddr(address)

        val service =
            SharedRadioInterfaceService(
                dispatchers = dispatchers,
                bluetoothRepository = bluetoothRepository,
                networkRepository = networkRepository,
                processLifecycle = processLifecycleOwner.lifecycle,
                radioPrefs = radioPrefs,
                transportFactory = transportFactory,
                analytics = analytics,
            )
        service.clockMillis = { clock }
        service.connect()
        service.onConnect()
        return service
    }

    // ─── BLE: Liveness timeout triggers recovery ───────────────────────────────────────────────

    @Test
    fun `BLE liveness timeout closes old transport and creates fresh one`() = runTest(testDispatcher) {
        clock = 0L
        val service = createConnectedService("xAA:BB:CC:DD:EE:FF")
        assertEquals(1, createdTransports.size, "Initial connect should create one transport")

        clock = 65_000L
        service.checkLiveness()
        advanceUntilIdle()

        assertEquals(2, createdTransports.size, "Liveness restart should create exactly one fresh transport")
        assertTrue(createdTransports.first().closeCalled, "Old transport must be closed")
        assertEquals(1, createdTransports.first().closeCount, "Old transport closed exactly once")
    }

    @Test
    fun `BLE liveness restart does not emit permanent Disconnected`() = runTest(testDispatcher) {
        clock = 0L
        val service = createConnectedService("xAA:BB:CC:DD:EE:FF")

        // Capture all state transitions during the liveness recovery
        val stateEmissions = mutableListOf<ConnectionState>()
        val collectJob = backgroundScope.launch { service.connectionState.collect { stateEmissions.add(it) } }

        clock = 65_000L
        service.checkLiveness()
        advanceUntilIdle()

        collectJob.cancel()

        // Recovery must NEVER emit permanent Disconnected
        assertFalse(
            ConnectionState.Disconnected in stateEmissions,
            "Automatic recovery must not emit permanent Disconnected state " + "(emitted: $stateEmissions)",
        )
    }

    @Test
    fun `BLE liveness restart does not send polite disconnect into zombie transport`() = runTest(testDispatcher) {
        clock = 0L
        val service = createConnectedService("xAA:BB:CC:DD:EE:FF")
        val oldTransport = createdTransports.first()

        oldTransport.sentData.clear()

        clock = 65_000L
        service.checkLiveness()
        advanceUntilIdle()

        assertTrue(
            oldTransport.sentData.isEmpty(),
            "Polite disconnect frame must NOT be sent into zombie transport during liveness restart",
        )
    }

    @Test
    fun `BLE repeated liveness checks do not stack restarts`() = runTest(testDispatcher) {
        clock = 0L
        val service = createConnectedService("xAA:BB:CC:DD:EE:FF")

        clock = 65_000L
        service.checkLiveness()
        advanceUntilIdle()

        clock = 66_000L
        service.checkLiveness()
        advanceUntilIdle()

        val firstTransportCloses = createdTransports.firstOrNull()?.closeCount ?: 0
        assertEquals(1, firstTransportCloses, "First transport should be closed exactly once (no stacking)")
    }

    @Test
    fun `BLE in-flight liveness restart prevents overlapping restart via isRestarting`() = runTest(testDispatcher) {
        // Deterministic in-flight overlap: a GatedFakeRadioTransport holds the first restart
        // genuinely suspended inside stopTransportLocked → close() (awaiting closeGate). This
        // removes reliance on UnconfinedTestDispatcher scheduling so the overlap window is real.
        //
        // The first checkLiveness() flips state to DeviceSleep and CAS-sets isRestarting before
        // launching the restart coroutine, which then suspends in close(). The second
        // checkLiveness() is issued while that restart is still suspended and must NOT begin
        // another close/create cycle.
        val gatedTransports = mutableListOf<GatedFakeRadioTransport>()
        val closeGate = CompletableDeferred<Unit>()
        val transportProvider: () -> RadioTransport = {
            GatedFakeRadioTransport(closeGate).also { gatedTransports.add(it) }
        }

        clock = 0L
        val service = createConnectedService("xAA:BB:CC:DD:EE:FF", transportProvider)
        assertEquals(1, gatedTransports.size, "Initial connect should create one transport")
        val initialTransport = gatedTransports.first()

        // Past the 60s threshold → first checkLiveness triggers a restart whose close() suspends
        // on closeGate. Under UnconfinedTestDispatcher the launched restart runs eagerly up to the
        // suspension point, so by the time checkLiveness() returns the restart is in-flight.
        clock = 65_000L
        service.checkLiveness()

        // Issue a second checkLiveness() while the first restart is still suspended in close().
        // Do NOT advanceUntilIdle here — the overlap must happen with the first restart in-flight.
        clock = 65_001L
        service.checkLiveness()

        // While the first restart is suspended: exactly one transport created so far, and close()
        // was entered exactly once and has NOT completed. The second check started no new cycle.
        assertEquals(
            1,
            gatedTransports.size,
            "Second check must not create a transport while the first restart is in-flight",
        )
        assertTrue(initialTransport.closeCalled, "First transport close must have been entered by the restart")
        assertEquals(1, initialTransport.closeCount, "close() entered exactly once (no stacking of close calls)")
        assertEquals(
            0,
            initialTransport.closeCompletedCount,
            "close() must still be suspended (restart held in-flight) before releasing the gate",
        )

        // Release the gate: the suspended restart resumes, completes stopTransportLocked, and
        // startTransportLocked creates the single fresh transport. isRestarting is reset in the
        // finally block.
        closeGate.complete(Unit)
        advanceUntilIdle()

        // Exactly 2 transports: 1 initial + 1 restart. A stacking bug would produce 3+.
        assertEquals(
            2,
            gatedTransports.size,
            "Exactly one fresh transport created after the restart resumes (1 initial + 1 restart)",
        )
        assertEquals(
            1,
            initialTransport.closeCount,
            "First transport still closed exactly once after restart completes",
        )
        assertEquals(1, initialTransport.closeCompletedCount, "First transport close completed exactly once")
    }

    // ─── Non-BLE: Liveness does not mutate state ───────────────────────────────────────────────

    @Test
    fun `non-BLE transport liveness timeout does not close transport or change state`() = runTest(testDispatcher) {
        clock = 0L
        val service = createConnectedService("t192.168.1.100")
        val stateBefore = service.connectionState.value

        clock = 65_000L
        service.checkLiveness()
        advanceUntilIdle()

        assertEquals(stateBefore, service.connectionState.value, "Non-BLE state must not change")
        assertFalse(createdTransports.first().closeCalled, "Non-BLE transport must NOT be closed")
        assertEquals(1, createdTransports.size, "No restart should occur for non-BLE transport")
    }

    // ─── handleFromRadio resets the liveness timer ──────────────────────────────────────────────

    @Test
    fun `inbound data resets liveness timer so timeout does not fire`() = runTest(testDispatcher) {
        clock = 0L
        val service = createConnectedService("xAA:BB:CC:DD:EE:FF")

        // Advance 30s, then receive data (resets lastDataReceivedMillis to clock=30s)
        clock = 30_000L
        service.handleFromRadio(byteArrayOf(1, 2, 3))

        // 30s since last data → within 60s threshold → should NOT fire
        clock = 60_000L
        service.checkLiveness()
        assertFalse(
            createdTransports.first().closeCalled,
            "Liveness must not fire when silence is within threshold after inbound data",
        )

        // 66s since last data (at t=30s) → past 60s threshold → should fire
        clock = 96_000L
        service.checkLiveness()
        advanceUntilIdle()
        assertTrue(
            createdTransports.first().closeCalled,
            "Liveness should fire after silence exceeds threshold since last inbound data",
        )
    }

    @Test
    fun `BLE liveness does not fire when connection state is not Connected`() = runTest(testDispatcher) {
        clock = 0L
        val service = createConnectedService("xAA:BB:CC:DD:EE:FF")

        service.onDisconnect(isPermanent = true)
        assertFalse(service.connectionState.value == ConnectionState.Connected)

        clock = 65_000L
        service.checkLiveness()
        advanceUntilIdle()
        assertFalse(createdTransports.first().closeCalled, "Liveness must not fire when not Connected")
    }
}
