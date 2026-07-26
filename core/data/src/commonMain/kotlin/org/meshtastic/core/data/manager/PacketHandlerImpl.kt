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
import kotlinx.atomicfu.AtomicBoolean
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import org.meshtastic.core.common.util.handledLaunch
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.MeshLog
import org.meshtastic.core.model.MessageStatus
import org.meshtastic.core.model.RadioNotConnectedException
import org.meshtastic.core.model.util.toOneLineString
import org.meshtastic.core.model.util.toPIIString
import org.meshtastic.core.repository.AwaitedSendResult
import org.meshtastic.core.repository.AwaitedSendStatus
import org.meshtastic.core.repository.ConnectionStateProvider
import org.meshtastic.core.repository.MeshLogRepository
import org.meshtastic.core.repository.PacketHandler
import org.meshtastic.core.repository.PacketRepository
import org.meshtastic.core.repository.RadioInterfaceService
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.QueueStatus
import org.meshtastic.proto.ToRadio
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

@Suppress("TooManyFunctions")
@Single
class PacketHandlerImpl(
    private val packetRepository: Lazy<PacketRepository>,
    private val radioInterfaceService: RadioInterfaceService,
    private val meshLogRepository: Lazy<MeshLogRepository>,
    private val connectionStateProvider: ConnectionStateProvider,
    @Named("ServiceScope") private val scope: CoroutineScope,
) : PacketHandler {

    companion object {
        private val TIMEOUT = 5.seconds

        /**
         * Firmware-internal `ErrorCode` (MeshTypes.h `ERRNO_SHOULD_RELEASE`) leaked into `QueueStatus.res`: "no error,
         * but the packet should still be released". Firmware 2.8+ returns it for self-addressed packets, which are
         * delivered through the synchronous local loopback instead of the TX queue — a success. Note it numerically
         * collides with `Routing.Error.PKI_UNKNOWN_PUBKEY` (35); `QueueStatus.res` carries ErrorCode semantics, not
         * Routing.Error.
         */
        private const val ERRNO_SHOULD_RELEASE = 35
    }

    private var queueJob: Job? = null
    private var queueGeneration = 0L

    private val queueMutex = Mutex()
    private val queuedPackets = mutableListOf<MeshPacket>()

    // Set to true by stopPacketQueue() under queueMutex. Checked by startPacketQueueLocked()
    // and the queue processor's finally block to prevent restarting a stopped queue.
    private var queueStopped = false

    private val responseMutex = Mutex()

    private data class PendingResponse(
        val deferred: CompletableDeferred<AwaitedSendStatus> = CompletableDeferred(),
        val dispatched: AtomicBoolean = atomic(false),
    )

    private val queueResponse = mutableMapOf<Int, PendingResponse>()

    override fun sendToRadio(p: ToRadio) {
        dispatchToRadio(p)
    }

    private fun dispatchToRadio(p: ToRadio): Boolean {
        Logger.d { "Sending to radio ${p.toPIIString()}" }
        val dispatched = radioInterfaceService.trySendToRadio(p.encode())
        if (!dispatched) return false

        p.packet?.id?.let { changeStatus(it, MessageStatus.ENROUTE) }
        val packet = p.packet
        if (packet?.decoded != null) {
            val packetToSave =
                MeshLog(
                    uuid = Uuid.random().toString(),
                    message_type = "Packet",
                    received_date = nowMillis,
                    raw_message = packet.toString(),
                    fromNum = MeshLog.NODE_NUM_LOCAL,
                    portNum = packet.decoded?.portnum?.value ?: 0,
                    fromRadio = FromRadio(packet = packet),
                )
            insertMeshLog(packetToSave)
        }
        return true
    }

    /**
     * Enqueue [packet] for transmission. Order is preserved for sequential calls from the same coroutine (mutex
     * acquisition is uncontested between sequential calls). Transactional sequences that require strict ordering across
     * multiple calls — e.g. an `editSettings { … }` begin → writes → commit sequence — MUST be issued from a single
     * coroutine; concurrent senders share FIFO only at the per-call grain.
     */
    override suspend fun sendToRadio(packet: MeshPacket): Boolean {
        val pending = packet.takeIf { it.id != 0 }?.let { enqueuePacket(it) }
        when {
            packet.id == 0 -> Logger.w { "Dropping queued packet without an ID" }
            pending == null -> Logger.w { "Dropping duplicate queued packet id=${packet.id.toUInt()}" }
        }
        return pending != null
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override suspend fun sendToRadioAndAwaitResult(packet: MeshPacket): AwaitedSendResult {
        val pending = packet.takeIf { it.id != 0 }?.let { enqueuePacket(it) }
        if (pending == null) {
            if (packet.id == 0) {
                Logger.w { "Rejecting awaited packet without an ID" }
            } else {
                Logger.w { "Rejecting duplicate awaited packet id=${packet.id.toUInt()}" }
            }
            return AwaitedSendResult(status = AwaitedSendStatus.SEND_FAILED, dispatched = false)
        }
        return try {
            // The queue processor owns the response timeout and starts it only after this packet reaches the head of
            // the FIFO and is sent. A caller-level timeout here would incorrectly include time spent behind earlier
            // packets and can reject a valid transaction boundary before it has even left the phone.
            AwaitedSendResult(status = pending.deferred.await(), dispatched = pending.dispatched.value)
        } catch (e: CancellationException) {
            throw e // Preserve structured concurrency cancellation propagation.
        } catch (e: Exception) {
            Logger.d { "sendToRadioAndAwait packet id=${packet.id.toUInt()} failed: ${e.message}" }
            AwaitedSendResult(status = AwaitedSendStatus.SEND_FAILED, dispatched = pending.dispatched.value)
        } finally {
            // The queue lifecycle owns an incomplete reservation. In particular, cancellation while this packet is
            // still behind the FIFO backlog must not release its ID for a newer packet that the stale queue entry could
            // accidentally satisfy.
            if (pending.deferred.isCompleted) {
                responseMutex.withLock { if (queueResponse[packet.id] === pending) queueResponse.remove(packet.id) }
            }
        }
    }

    /**
     * Reserves [packet]'s non-zero ID and queues it as one atomic admission. The reservation spans both queued and
     * in-flight work, so a second sender cannot replace the original caller's [PendingResponse].
     */
    private suspend fun enqueuePacket(packet: MeshPacket): PendingResponse? = queueMutex.withLock {
        responseMutex.withLock responseLock@{
            if (queueResponse.containsKey(packet.id)) return@responseLock null

            val pending = PendingResponse()
            queueResponse[packet.id] = pending
            queueStopped = false // Allow queue to resume after a disconnect/reconnect cycle.
            queuedPackets.add(packet)
            startPacketQueueLocked()
            pending
        }
    }

    override fun stopPacketQueue() {
        // Run async so callers (non-suspend) don't block, but all mutations are
        // serialized under the same mutexes used by the queue processor and senders.
        scope.launch {
            Logger.i { "Stopping packet queueJob" }
            queueMutex.withLock {
                queueStopped = true
                queueJob?.cancel()
                queueJob = null
                queueGeneration++
                queuedPackets.clear()
                completePendingResponses(AwaitedSendStatus.TRANSPORT_STOPPED)
            }
        }
    }

    override fun handleQueueStatus(queueStatus: QueueStatus) {
        Logger.d { "[queueStatus] ${queueStatus.toOneLineString()}" }
        val (success, isFull, requestId) =
            with(queueStatus) { Triple(res == 0 || res == ERRNO_SHOULD_RELEASE, free == 0, mesh_packet_id) }
        // Only the plain res==0 "queue accepted, now full" echo is skipped here. ERRNO_SHOULD_RELEASE denotes a
        // synchronous local-loopback delivery that still needs its queueResponse completed, even when free==0, or it
        // would hang until TIMEOUT.
        if (queueStatus.res == 0 && isFull) return

        scope.launch {
            responseMutex.withLock {
                if (requestId != 0) {
                    queueResponse.remove(requestId)?.deferred?.complete(success.toAwaitedSendStatus())
                } else {
                    queueResponse.values
                        .firstOrNull { !it.deferred.isCompleted }
                        ?.deferred
                        ?.complete(success.toAwaitedSendStatus())
                }
            }
        }
    }

    override suspend fun removeResponse(dataRequestId: Int, complete: Boolean) {
        responseMutex.withLock {
            queueResponse.remove(dataRequestId)?.deferred?.complete(complete.toAwaitedSendStatus())
        }
    }

    /**
     * Starts the packet queue processor. Must be called while holding [queueMutex] to ensure the check-then-start is
     * atomic — preventing two concurrent callers from launching duplicate processors.
     */
    private fun startPacketQueueLocked() {
        if (queueStopped) return
        if (queueJob?.isActive == true) return
        val generation = ++queueGeneration
        queueJob =
            scope.handledLaunch {
                try {
                    while (connectionStateProvider.connectionState.value == ConnectionState.Connected) {
                        val packet = queueMutex.withLock { queuedPackets.removeFirstOrNull() } ?: break
                        @Suppress("TooGenericExceptionCaught", "SwallowedException")
                        try {
                            val response = sendPacket(packet)
                            Logger.d { "queueJob packet id=${packet.id.toUInt()} waiting" }
                            val status = withTimeout(TIMEOUT) { response.await() }
                            Logger.d { "queueJob packet id=${packet.id.toUInt()} status $status" }
                            // QueueStatus normally removes keyed responses before completing them. Keep cleanup here
                            // as well for local send failures and legacy ID-less statuses, which complete the deferred
                            // without removing its map entry.
                            responseMutex.withLock { queueResponse.remove(packet.id) }
                        } catch (e: TimeoutCancellationException) {
                            Logger.d { "queueJob packet id=${packet.id.toUInt()} timeout" }
                            // Complete an awaiting caller before removing the response. Its timeout starts here, after
                            // the packet is actually sent, rather than while it waits behind the existing FIFO backlog.
                            responseMutex.withLock {
                                queueResponse.remove(packet.id)?.deferred?.complete(AwaitedSendStatus.TIMED_OUT)
                            }
                        } catch (e: CancellationException) {
                            throw e // Preserve structured concurrency cancellation propagation.
                        } catch (e: Exception) {
                            Logger.d { "queueJob packet id=${packet.id.toUInt()} failed" }
                            responseMutex.withLock {
                                queueResponse.remove(packet.id)?.deferred?.complete(AwaitedSendStatus.SEND_FAILED)
                            }
                        }
                        // handleQueueStatus and stopPacketQueue also remove entries; every path is intentionally
                        // idempotent because the queue worker and caller can observe completion concurrently.
                    }
                } finally {
                    // Keep completion, replacement, and disconnect draining atomic with new admissions. An older
                    // cancelled processor must not clear a replacement job started for a newer generation.
                    withContext(NonCancellable) {
                        queueMutex.withLock {
                            if (generation != queueGeneration) return@withLock
                            queueJob = null
                            when {
                                queueStopped || !scope.isActive -> {
                                    queueStopped = true
                                    queuedPackets.clear()
                                    completePendingResponses(AwaitedSendStatus.TRANSPORT_STOPPED)
                                }

                                connectionStateProvider.connectionState.value == ConnectionState.Connected ->
                                    if (queuedPackets.isEmpty()) {
                                        // A normally completed worker has no pending response. Anything left here
                                        // belongs to an in-flight packet interrupted by cancellation or an unexpected
                                        // worker exit.
                                        completePendingResponses(AwaitedSendStatus.TRANSPORT_STOPPED)
                                    } else {
                                        startPacketQueueLocked()
                                    }

                                else -> {
                                    queueStopped = true
                                    queuedPackets.clear()
                                    completePendingResponses(AwaitedSendStatus.TRANSPORT_STOPPED)
                                }
                            }
                        }
                    }
                }
            }
    }

    private fun changeStatus(packetId: Int, m: MessageStatus) = scope.handledLaunch {
        if (packetId != 0) {
            getDataPacketById(packetId)?.let { p ->
                if (p.status == m) return@handledLaunch
                packetRepository.value.updateMessageStatus(p, m)
            }
        }
    }

    private suspend fun getDataPacketById(packetId: Int): DataPacket? = withTimeoutOrNull(1.seconds) {
        var dataPacket: DataPacket? = null
        while (dataPacket == null) {
            dataPacket = packetRepository.value.getPacketById(packetId)
            if (dataPacket == null) delay(100.milliseconds)
        }
        dataPacket
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun sendPacket(packet: MeshPacket): Deferred<AwaitedSendStatus> {
        val pending =
            checkNotNull(responseMutex.withLock { queueResponse[packet.id] }) {
                "Queued packet id=${packet.id.toUInt()} lost its response reservation"
            }
        try {
            requireConnected()
            pending.dispatched.value = dispatchToRadio(ToRadio(packet = packet))
            if (!pending.dispatched.value) {
                throw RadioNotConnectedException("No active transport accepted the packet")
            }
        } catch (ex: RadioNotConnectedException) {
            Logger.w(ex) { "sendToRadio skipped: Not connected to radio" }
            pending.deferred.complete(AwaitedSendStatus.SEND_FAILED)
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            Logger.e(ex) { "sendToRadio error: ${ex.message}" }
            pending.deferred.complete(AwaitedSendStatus.SEND_FAILED)
        }
        // Return a read-only Deferred view (kotlinx.coroutines 1.11+) so callers can await it without completing the
        // underlying response; cancellation is still exposed via Deferred/Job.
        return pending.deferred.asDeferred()
    }

    private fun requireConnected() {
        if (connectionStateProvider.connectionState.value != ConnectionState.Connected) {
            throw RadioNotConnectedException()
        }
    }

    private fun Boolean.toAwaitedSendStatus(): AwaitedSendStatus =
        if (this) AwaitedSendStatus.ACCEPTED else AwaitedSendStatus.REJECTED

    private suspend fun completePendingResponses(status: AwaitedSendStatus) {
        responseMutex.withLock {
            queueResponse.values.forEach { pending -> pending.deferred.complete(status) }
            queueResponse.clear()
        }
    }

    private fun insertMeshLog(packetToSave: MeshLog) {
        scope.handledLaunch {
            Logger.d {
                "insert: ${packetToSave.message_type} = " +
                    "${packetToSave.raw_message.toOneLineString()} from=${packetToSave.fromNum}"
            }
            meshLogRepository.value.insert(packetToSave)
        }
    }
}
