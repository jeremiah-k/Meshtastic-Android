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

import dev.mokkery.MockMode
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify
import dev.mokkery.verify.VerifyMode.Companion.exactly
import dev.mokkery.verifySuspend
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.repository.AwaitedSendStatus
import org.meshtastic.core.repository.MeshLogRepository
import org.meshtastic.core.repository.PacketRepository
import org.meshtastic.core.repository.RadioInterfaceService
import org.meshtastic.core.repository.ServiceRepository
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.QueueStatus
import org.meshtastic.proto.ToRadio
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PacketHandlerImplTest {

    private val packetRepository: PacketRepository = mock(MockMode.autofill)
    private val radioInterfaceService: RadioInterfaceService = mock(MockMode.autofill)
    private val meshLogRepository: MeshLogRepository = mock(MockMode.autofill)
    private val serviceRepository: ServiceRepository = mock(MockMode.autofill)

    private val connectionStateFlow = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var handler: PacketHandlerImpl

    @BeforeTest
    fun setUp() {
        every { serviceRepository.connectionState } returns connectionStateFlow
        every { radioInterfaceService.trySendToRadio(any()) } returns true

        handler =
            PacketHandlerImpl(
                lazy { packetRepository },
                radioInterfaceService,
                lazy { meshLogRepository },
                serviceRepository,
                testScope,
            )
    }

    @Test
    fun testInitialization() {
        assertNotNull(handler)
    }

    @Test
    fun `sendToRadio with ToRadio sends immediately`() {
        val toRadio = ToRadio(packet = MeshPacket(id = 123))

        handler.sendToRadio(toRadio)

        verify { radioInterfaceService.trySendToRadio(any()) }
    }

    @Test
    fun `sendToRadio with MeshPacket queues and sends when connected`() = runTest(testDispatcher) {
        val packet = MeshPacket(id = 456)
        connectionStateFlow.value = ConnectionState.Connected

        handler.sendToRadio(packet)
        testScheduler.runCurrent()

        verify { radioInterfaceService.trySendToRadio(any()) }
    }

    @Test
    fun `handleQueueStatus completes deferred`() = runTest(testDispatcher) {
        val packet = MeshPacket(id = 789)
        connectionStateFlow.value = ConnectionState.Connected

        handler.sendToRadio(packet)
        testScheduler.runCurrent()

        val status =
            QueueStatus(
                mesh_packet_id = 789,
                res = 0, // Success
                free = 1,
            )

        handler.handleQueueStatus(status)
        testScheduler.runCurrent()
    }

    @Test
    fun `handleQueueStatus treats ERRNO_SHOULD_RELEASE as success`() = runTest(testDispatcher) {
        // Firmware 2.8+ returns ErrorCode 35 (ERRNO_SHOULD_RELEASE) for self-addressed packets delivered
        // through the synchronous local loopback — a success, not a queue failure.
        connectionStateFlow.value = ConnectionState.Connected

        val result = async { handler.sendToRadioAndAwait(MeshPacket(id = 790)) }
        testScheduler.runCurrent()

        handler.handleQueueStatus(QueueStatus(mesh_packet_id = 790, res = 35, free = 16))
        testScheduler.runCurrent()

        assertTrue(result.await())
    }

    @Test
    fun `handleQueueStatus completes ERRNO_SHOULD_RELEASE even when queue is full`() = runTest(testDispatcher) {
        // Regression: a self-addressed local-loopback delivery (res=35) can coincide with a full TX queue (free=0).
        // The success+full early return must not swallow it, or the response hangs until TIMEOUT (the very stall
        // this fix targets). Only the plain res=0 "accepted, now full" echo should be skipped.
        connectionStateFlow.value = ConnectionState.Connected

        val result = async { handler.sendToRadioAndAwait(MeshPacket(id = 792)) }
        testScheduler.runCurrent()

        handler.handleQueueStatus(QueueStatus(mesh_packet_id = 792, res = 35, free = 0))
        testScheduler.runCurrent()

        assertTrue(result.await())
    }

    @Test
    fun `handleQueueStatus treats other nonzero res as failure`() = runTest(testDispatcher) {
        connectionStateFlow.value = ConnectionState.Connected

        val result = async { handler.sendToRadioAndAwait(MeshPacket(id = 791)) }
        testScheduler.runCurrent()

        handler.handleQueueStatus(QueueStatus(mesh_packet_id = 791, res = 33, free = 16))
        testScheduler.runCurrent()

        assertFalse(result.await())
    }

    @Test
    fun `await response timeout starts after earlier queued packets`() = runTest(testDispatcher) {
        connectionStateFlow.value = ConnectionState.Connected
        handler.sendToRadio(MeshPacket(id = 800))
        handler.sendToRadio(MeshPacket(id = 801))
        val result = async { handler.sendToRadioAndAwait(MeshPacket(id = 802)) }
        testScheduler.runCurrent()

        // Let both earlier packets consume their full response windows. The awaited packet has not timed out
        // because
        // it has not reached the head of the queue yet.
        testScheduler.advanceTimeBy(5_001)
        testScheduler.runCurrent()
        assertFalse(result.isCompleted)
        testScheduler.advanceTimeBy(5_001)
        testScheduler.runCurrent()
        assertFalse(result.isCompleted)

        handler.handleQueueStatus(QueueStatus(mesh_packet_id = 802, res = 0, free = 16))
        testScheduler.runCurrent()

        assertTrue(result.await())
    }

    @Test
    fun `stopping the queue completes an awaiting packet still behind the backlog`() = runTest(testDispatcher) {
        connectionStateFlow.value = ConnectionState.Connected
        handler.sendToRadio(MeshPacket(id = 803))
        val result = async { handler.sendToRadioAndAwaitResult(MeshPacket(id = 804)) }
        testScheduler.runCurrent()

        handler.stopPacketQueue()
        testScheduler.runCurrent()

        val stopped = result.await()
        assertEquals(AwaitedSendStatus.TRANSPORT_STOPPED, stopped.status)
        assertFalse(stopped.dispatched)
    }

    @Test
    fun `stopping transport reports an awaited packet was already dispatched`() = runTest(testDispatcher) {
        connectionStateFlow.value = ConnectionState.Connected
        val result = async { handler.sendToRadioAndAwaitResult(MeshPacket(id = 807)) }
        testScheduler.runCurrent()

        handler.stopPacketQueue()
        testScheduler.runCurrent()

        val stopped = result.await()
        assertEquals(AwaitedSendStatus.TRANSPORT_STOPPED, stopped.status)
        assertTrue(stopped.dispatched)
    }

    @Test
    fun `disconnect drains queued responses without restarting the processor`() = runTest(testDispatcher) {
        connectionStateFlow.value = ConnectionState.Connected
        val first = async { handler.sendToRadioAndAwaitResult(MeshPacket(id = 813)) }
        val queued = async { handler.sendToRadioAndAwaitResult(MeshPacket(id = 814)) }
        testScheduler.runCurrent()

        connectionStateFlow.value = ConnectionState.Disconnected
        handler.handleQueueStatus(QueueStatus(mesh_packet_id = 813, res = 0, free = 16))
        testScheduler.runCurrent()

        assertEquals(AwaitedSendStatus.ACCEPTED, first.await().status)
        val stopped = queued.await()
        assertEquals(AwaitedSendStatus.TRANSPORT_STOPPED, stopped.status)
        assertFalse(stopped.dispatched)
        verify(exactly(1)) { radioInterfaceService.trySendToRadio(any()) }
    }

    @Test
    fun `queue response timeout completes awaiting caller with failure`() = runTest(testDispatcher) {
        connectionStateFlow.value = ConnectionState.Connected
        val result = async { handler.sendToRadioAndAwaitResult(MeshPacket(id = 805)) }
        testScheduler.runCurrent()

        testScheduler.advanceTimeBy(5_001)
        testScheduler.runCurrent()

        val timedOut = result.await()
        assertEquals(AwaitedSendStatus.TIMED_OUT, timedOut.status)
        assertTrue(timedOut.dispatched)
    }

    @Test
    fun `transport refusing dispatch completes awaiting caller with failure`() = runTest(testDispatcher) {
        every { radioInterfaceService.trySendToRadio(any()) } returns false
        connectionStateFlow.value = ConnectionState.Connected

        val result = async { handler.sendToRadioAndAwaitResult(MeshPacket(id = 808)) }
        testScheduler.runCurrent()

        val failed = result.await()
        assertEquals(AwaitedSendStatus.SEND_FAILED, failed.status)
        assertFalse(failed.dispatched)
    }

    @Test
    fun `queue send failure completes awaiting caller with failure`() = runTest(testDispatcher) {
        every { radioInterfaceService.trySendToRadio(any()) } calls { error("test send failure") }
        connectionStateFlow.value = ConnectionState.Connected

        val result = async { handler.sendToRadioAndAwaitResult(MeshPacket(id = 806)) }
        testScheduler.runCurrent()

        val failed = result.await()
        assertEquals(AwaitedSendStatus.SEND_FAILED, failed.status)
        assertFalse(failed.dispatched)
    }

    @Test
    fun `cancelled queue handoff releases the current response reservation`() = runTest(testDispatcher) {
        every { radioInterfaceService.trySendToRadio(any()) } calls
            {
                throw CancellationException("transport stopped")
            }
        connectionStateFlow.value = ConnectionState.Connected

        val result = async { handler.sendToRadioAndAwaitResult(MeshPacket(id = 815)) }
        testScheduler.runCurrent()

        val stopped = result.await()
        assertEquals(AwaitedSendStatus.TRANSPORT_STOPPED, stopped.status)
        assertFalse(stopped.dispatched)
    }

    @Test
    fun `duplicate awaited packet id is rejected without replacing the original waiter`() = runTest(testDispatcher) {
        connectionStateFlow.value = ConnectionState.Connected
        val original = async { handler.sendToRadioAndAwaitResult(MeshPacket(id = 809)) }
        testScheduler.runCurrent()

        val duplicate = handler.sendToRadioAndAwaitResult(MeshPacket(id = 809))

        assertEquals(AwaitedSendStatus.SEND_FAILED, duplicate.status)
        assertFalse(duplicate.dispatched)
        assertFalse(original.isCompleted)

        handler.handleQueueStatus(QueueStatus(mesh_packet_id = 809, res = 0, free = 16))
        testScheduler.runCurrent()

        val accepted = original.await()
        assertEquals(AwaitedSendStatus.ACCEPTED, accepted.status)
        assertTrue(accepted.dispatched)
    }

    @Test
    fun `cancelling an awaiting caller does not release its queued packet id`() = runTest(testDispatcher) {
        connectionStateFlow.value = ConnectionState.Connected
        handler.sendToRadio(MeshPacket(id = 811))
        val awaiting = async { handler.sendToRadioAndAwaitResult(MeshPacket(id = 812)) }
        testScheduler.runCurrent()

        awaiting.cancelAndJoin()
        val duplicate = handler.sendToRadioAndAwaitResult(MeshPacket(id = 812))

        assertEquals(AwaitedSendStatus.SEND_FAILED, duplicate.status)
        assertFalse(duplicate.dispatched)

        handler.stopPacketQueue()
        testScheduler.runCurrent()
    }

    @Test
    fun `fire and forget rejects invalid packet ids without throwing or replacing queued work`() =
        runTest(testDispatcher) {
            connectionStateFlow.value = ConnectionState.Connected
            assertTrue(handler.sendToRadio(MeshPacket(id = 810)))
            testScheduler.runCurrent()

            assertFalse(handler.sendToRadio(MeshPacket(id = 810)))
            assertFalse(handler.sendToRadio(MeshPacket()))
            testScheduler.runCurrent()

            verify(exactly(1)) { radioInterfaceService.trySendToRadio(any()) }

            handler.stopPacketQueue()
            testScheduler.runCurrent()
        }

    @Test
    fun `handleQueueStatus property test`() = runTest(testDispatcher) {
        checkAll(Arb.int(0, 10), Arb.int(0, 32), Arb.int(0, 100000)) { res, free, packetId ->
            val status = QueueStatus(res = res, free = free, mesh_packet_id = packetId)

            // Ensure it doesn't crash on any input
            handler.handleQueueStatus(status)
            testScheduler.runCurrent()
        }
    }

    @Test
    fun `outgoing packets are logged with NODE_NUM_LOCAL`() = runTest(testDispatcher) {
        val packet = MeshPacket(id = 123, decoded = Data(portnum = PortNum.TEXT_MESSAGE_APP))
        val toRadio = ToRadio(packet = packet)

        handler.sendToRadio(toRadio)
        testScheduler.runCurrent()

        verifySuspend { meshLogRepository.insert(any()) }
    }
}
