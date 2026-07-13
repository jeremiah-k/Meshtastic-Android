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
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import okio.ByteString
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import org.meshtastic.core.common.util.clampTimestampToNow
import org.meshtastic.core.common.util.crc32
import org.meshtastic.core.common.util.handledLaunch
import org.meshtastic.core.model.MyNodeInfo
import org.meshtastic.core.model.Node
import org.meshtastic.core.model.NodeAddress
import org.meshtastic.core.model.util.NodeIdLookup
import org.meshtastic.core.repository.NodeManager
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.Notification
import org.meshtastic.core.repository.NotificationManager
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.getStringSuspend
import org.meshtastic.core.resources.new_node_seen
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.FirmwareEdition
import org.meshtastic.proto.HardwareModel
import org.meshtastic.proto.Paxcount
import org.meshtastic.proto.StatusMessage
import org.meshtastic.proto.Telemetry
import org.meshtastic.proto.User
import org.meshtastic.proto.NodeInfo as ProtoNodeInfo
import org.meshtastic.proto.Position as ProtoPosition

/**
 * Resolves a stable identity key from a raw [ByteString]. Returns null unless the key is exactly [Node.PUBLIC_KEY_SIZE]
 * bytes and is not [Node.ERROR_BYTE_STRING]. Used by the reducer to centralize key validation so no branch repeats the
 * size/error check inline.
 */
private fun resolveStableKey(key: ByteString?): ByteString? {
    if (key == null || key.size != Node.PUBLIC_KEY_SIZE || key == Node.ERROR_BYTE_STRING) return null
    return key
}

private val DEFAULT_NODE_NAME_REGEX = Regex("^Meshtastic [0-9a-fA-F]{4}$")

/** Implementation of [NodeManager] that maintains an in-memory database of the mesh. */
@Suppress("LongParameterList", "TooManyFunctions", "CyclomaticComplexMethod")
@Single(binds = [NodeManager::class, NodeIdLookup::class])
class NodeManagerImpl(
    private val nodeRepository: NodeRepository,
    private val notificationManager: NotificationManager,
    @Named("ServiceScope") private val scope: CoroutineScope,
) : NodeManager {

    /**
     * Resolves the stable identity key from a stored [Node], preferring [Node.publicKey] and falling back to
     * [User.public_key] only when the primary field is not itself a valid stable key.
     */
    internal fun resolveNodeStableKey(node: Node): ByteString? =
        resolveStableKey(node.publicKey) ?: resolveStableKey(node.user.public_key)

    // Two indices over the same node set: byNum is the canonical store (mesh-level identifier), byId is a secondary
    // index for O(1) user-ID lookup. Both are held in a single atomic ref so updates are observed consistently.
    internal data class NodeIndex(
        val byNum: PersistentMap<Int, Node> = persistentMapOf(),
        val byId: PersistentMap<String, Node> = persistentMapOf(),
    ) {
        fun put(num: Int, node: Node, preferredNum: Int? = null): NodeIndex {
            val previous = byNum[num]
            val nextByNum = byNum.putting(num, node)
            var nextById = byId
            val affectedIds = setOfNotNull(previous?.user?.id, node.user.id).filter { it.isNotEmpty() }
            for (id in affectedIds) {
                val candidates = nextByNum.entries.filter { it.value.user.id == id }
                val representative = chooseRepresentative(candidates, preferredNum)
                nextById =
                    if (representative == null) {
                        nextById.removing(id)
                    } else {
                        nextById.putting(id, representative.value)
                    }
            }
            return NodeIndex(byNum = nextByNum, byId = nextById)
        }

        /**
         * Removes [num] from both indices. When the removed node's user ID was the [byId] representative and another
         * surviving node shares that ID, [preferredNum] wins when present; otherwise the stable fallback selects the
         * replacement.
         */
        fun remove(num: Int, preferredNum: Int? = null): NodeIndex {
            val previous = byNum[num] ?: return this
            val newByNum = byNum.removing(num)
            var newById = byId
            if (previous.user.id.isNotEmpty() && (byId[previous.user.id] === previous || preferredNum != null)) {
                val survivors = newByNum.entries.filter { it.value.user.id == previous.user.id }
                val survivor = chooseRepresentative(survivors, preferredNum)
                newById =
                    if (survivor != null) {
                        newById.putting(previous.user.id, survivor.value)
                    } else {
                        newById.removing(previous.user.id)
                    }
            }
            return NodeIndex(byNum = newByNum, byId = newById)
        }

        companion object {
            /**
             * Determines whether a [Node] is a generated placeholder (default identity with no established identity).
             * Used by both the representative selector and the stale-packet reducer to ensure consistent
             * classification.
             */
            internal fun isGeneratedPlaceholder(node: Node): Boolean {
                val nodeKey = resolveStableKey(node.publicKey) ?: resolveStableKey(node.user.public_key)
                return nodeKey == null &&
                    node.user.hw_model == HardwareModel.UNSET &&
                    node.user.id == NodeAddress.numToDefaultId(node.num) &&
                    node.user.long_name.matches(DEFAULT_NODE_NAME_REGEX)
            }

            private val representativeComparator =
                compareByDescending<Map.Entry<Int, Node>> {
                    resolveStableKey(it.value.publicKey) != null ||
                        resolveStableKey(it.value.user.public_key) != null ||
                        it.value.user.hw_model != HardwareModel.UNSET
                }
                    .thenBy { it.key }

            /**
             * Selects a deterministic representative from [candidates] sharing the same user ID. If [preferredNum] is
             * provided and present in candidates, it is selected (used for stale replay and local reconciliation).
             * Otherwise, prefer an established key or hardware identity, then the lower node number.
             */
            internal fun chooseRepresentative(
                candidates: List<Map.Entry<Int, Node>>,
                preferredNum: Int?,
            ): Map.Entry<Int, Node>? = preferredNum?.let { preferred -> candidates.firstOrNull { it.key == preferred } }
                ?: candidates.minWithOrNull(representativeComparator)

            fun fromByNum(nodes: Map<Int, Node>): NodeIndex {
                var byNum = persistentMapOf<Int, Node>()
                for ((n, node) in nodes) {
                    byNum = byNum.putting(n, node)
                }
                // Build byId deterministically: for duplicate user IDs, choose representative by stable tie-break.
                val byIdEntries =
                    byNum.entries
                        .groupBy { it.value.user.id }
                        .filterKeys { it.isNotEmpty() }
                        .mapValues { (_, entries) ->
                            chooseRepresentative(entries.toList(), preferredNum = null)!!.value
                        }
                var byId = persistentMapOf<String, Node>()
                for ((id, node) in byIdEntries) {
                    byId = byId.putting(id, node)
                }
                return NodeIndex(byNum, byId)
            }
        }
    }

    private val nodeIndex = atomic(NodeIndex())

    override val nodeDBbyNodeNum: Map<Int, Node>
        get() = nodeIndex.value.byNum

    override fun getNodeById(id: String): Node? = nodeIndex.value.byId[id]

    override val isNodeDbReady = MutableStateFlow(false)
    override val allowNodeDbWrites = MutableStateFlow(false)

    override fun setNodeDbReady(ready: Boolean) {
        isNodeDbReady.value = ready
    }

    override fun setAllowNodeDbWrites(allowed: Boolean) {
        allowNodeDbWrites.value = allowed
    }

    override val myNodeNum = MutableStateFlow<Int?>(null)

    override fun setMyNodeNum(num: Int?) {
        myNodeNum.value = num
    }

    override val myDeviceId = MutableStateFlow<String?>(null)

    override fun setMyDeviceId(id: String?) {
        myDeviceId.value = id
    }

    override val firmwareEdition = MutableStateFlow<FirmwareEdition?>(null)

    override fun setFirmwareEdition(edition: FirmwareEdition?) {
        firmwareEdition.value = edition
    }

    companion object {
        private const val TIME_MS_TO_S = 1000L
        private const val GENERATED_NODE_NAME_SUFFIX_LENGTH = 4
    }

    override fun loadCachedNodeDB() {
        scope.handledLaunch {
            val nodes = nodeRepository.nodeDBbyNum.first()
            nodeIndex.value = NodeIndex.fromByNum(nodes)
            if (myNodeNum.value == null) {
                myNodeNum.value = nodeRepository.myNodeInfo.value?.myNodeNum
            }
        }
    }

    override fun clear() {
        nodeIndex.value = NodeIndex()
        isNodeDbReady.value = false
        allowNodeDbWrites.value = false
        myNodeNum.value = null
        myDeviceId.value = null
        firmwareEdition.value = null
    }

    override fun getMyNodeInfo(): MyNodeInfo? {
        val mi = nodeRepository.myNodeInfo.value ?: return null
        val myNode = nodeIndex.value.byNum[mi.myNodeNum]
        return MyNodeInfo(
            myNodeNum = mi.myNodeNum,
            hasGPS = (myNode?.position?.latitude_i ?: 0) != 0,
            model = mi.model ?: myNode?.user?.hw_model?.name,
            firmwareVersion = mi.firmwareVersion,
            couldUpdate = mi.couldUpdate,
            shouldUpdate = mi.shouldUpdate,
            currentPacketId = mi.currentPacketId,
            messageTimeoutMsec = mi.messageTimeoutMsec,
            minAppVersion = mi.minAppVersion,
            maxChannels = mi.maxChannels,
            hasWifi = mi.hasWifi,
            channelUtilization = 0f,
            airUtilTx = 0f,
            deviceId = mi.deviceId ?: myNode?.user?.id,
        )
    }

    override fun getMyId(): String {
        val num = myNodeNum.value ?: nodeRepository.myNodeInfo.value?.myNodeNum ?: return ""
        return nodeIndex.value.byNum[num]?.user?.id ?: ""
    }

    override fun removeByNodenum(nodeNum: Int) {
        nodeIndex.update { it.remove(nodeNum) }
    }

    internal fun getOrCreateNode(n: Int, channel: Int = 0): Node =
        nodeIndex.value.byNum[n] ?: createDefaultNode(n, channel)

    override fun updateNode(nodeNum: Int, channel: Int, transform: (Node) -> Node) {
        // Perform read + transform inside update{} to ensure atomicity.
        // Without this, concurrent calls for the same nodeNum could read the same snapshot
        // and the last writer would silently overwrite the other's changes.
        var next: Node? = null
        nodeIndex.update { index ->
            val current = index.byNum[nodeNum] ?: getOrCreateNode(nodeNum, channel)
            val transformed = transform(current)
            next = transformed
            index.put(nodeNum, transformed)
        }
        val result = next ?: return

        if (result.user.id.isNotEmpty() && isNodeDbReady.value) {
            scope.handledLaunch { nodeRepository.upsert(result) }
        }
    }

    override fun handleReceivedUser(fromNum: Int, p: User, channel: Int, manuallyVerified: Boolean) {
        while (true) {
            val before = nodeIndex.value
            val myNum = myNodeNum.value // Read fresh on each retry
            val transition = reduceReceivedUser(before, fromNum, p, channel, manuallyVerified, myNum)
            // Guard against myNodeNum changing between the reduction and the CAS — a handshake renumber
            // could reclassify a local packet as remote or vice versa.
            if (myNodeNum.value != myNum) continue
            if (nodeIndex.compareAndSet(before, transition.after)) {
                applyReceivedUserEffects(transition)
                return
            }
        }
    }

    override fun handleReceivedPosition(fromNum: Int, myNodeNum: Int, p: ProtoPosition, defaultTime: Long) {
        val isZeroPos = (p.latitude_i ?: 0) == 0 && (p.longitude_i ?: 0) == 0
        @Suppress("ComplexCondition")
        if (myNodeNum == fromNum && isZeroPos && p.sats_in_view == 0 && p.time == 0) {
            Logger.d { "Ignoring empty position update for the local node" }
            return
        }

        updateNode(fromNum) { node ->
            val rawPosTime = if (p.time != 0) p.time else (defaultTime / TIME_MS_TO_S).toInt()
            val posTime = clampTimestampToNow(rawPosTime)
            val newLastHeard = maxOf(node.lastHeard, posTime)

            val newPos =
                if (isZeroPos) {
                    p.copy(
                        time = posTime,
                        latitude_i = node.position.latitude_i,
                        longitude_i = node.position.longitude_i,
                        altitude = p.altitude ?: node.position.altitude,
                        sats_in_view = p.sats_in_view,
                    )
                } else {
                    p.copy(time = posTime)
                }

            node.copy(position = newPos, lastHeard = newLastHeard)
        }
    }

    override fun handleReceivedTelemetry(fromNum: Int, telemetry: Telemetry) {
        updateNode(fromNum) { node ->
            var nextNode = node
            telemetry.device_metrics?.let { nextNode = nextNode.copy(deviceMetrics = it) }
            telemetry.environment_metrics?.let { nextNode = nextNode.copy(environmentMetrics = it) }
            telemetry.power_metrics?.let { nextNode = nextNode.copy(powerMetrics = it) }
            telemetry.air_quality_metrics?.let { nextNode = nextNode.copy(airQualityMetrics = it) }
            val telemetryTime = if (telemetry.time != 0) telemetry.time else node.lastHeard
            val newLastHeard = clampTimestampToNow(maxOf(node.lastHeard, telemetryTime))
            nextNode.copy(lastHeard = newLastHeard)
        }
    }

    override fun handleReceivedPaxcounter(fromNum: Int, p: Paxcount) {
        updateNode(fromNum) { it.copy(paxcounter = p) }
    }

    override fun handleReceivedNodeStatus(fromNum: Int, s: StatusMessage) {
        updateNodeStatus(fromNum, s.status)
    }

    override fun updateNodeStatus(nodeNum: Int, status: String?) {
        updateNode(nodeNum) { it.copy(nodeStatus = status?.takeIf { s -> s.isNotEmpty() }) }
    }

    override fun installNodeInfo(info: ProtoNodeInfo) {
        updateNode(info.num) { node ->
            var next = node
            val user = info.user
            if (user != null) {
                if (shouldPreserveExistingUser(node.user, user)) {
                    // keep existing names
                } else {
                    var newUser =
                        user.let { if (it.is_licensed == true) it.copy(public_key = ByteString.EMPTY) else it }
                    if (info.via_mqtt && !newUser.long_name.endsWith(" (MQTT)")) {
                        newUser = newUser.copy(long_name = "${newUser.long_name} (MQTT)")
                    }
                    next = next.copy(user = newUser, publicKey = newUser.public_key)
                }
            }
            val position = info.position
            if (position != null) {
                val clampedPos = position.copy(time = clampTimestampToNow(position.time))
                next = next.copy(position = clampedPos)
            }
            next =
                next.copy(
                    lastHeard = clampTimestampToNow(info.last_heard),
                    deviceMetrics = info.device_metrics ?: next.deviceMetrics,
                    channel = info.channel,
                    viaMqtt = info.via_mqtt,
                    hopsAway = info.hops_away ?: -1,
                    isFavorite = info.is_favorite,
                    isIgnored = info.is_ignored,
                    isMuted = info.is_muted,
                    signsPackets = info.has_xeddsa_signed,
                )
            next
        }
    }

    override fun insertMetadata(nodeNum: Int, metadata: DeviceMetadata) {
        scope.handledLaunch { nodeRepository.insertMetadata(nodeNum, metadata) }
    }

    private fun shouldPreserveExistingUser(existing: User, incoming: User): Boolean {
        val isDefaultName = incoming.long_name.matches(DEFAULT_NODE_NAME_REGEX)
        val isDefaultHwModel = incoming.hw_model == HardwareModel.UNSET
        val hasCustomIdentity =
            existing.id != incoming.id ||
                existing.long_name != incoming.long_name ||
                existing.short_name != incoming.short_name
        val hasExistingUser =
            existing.id.isNotEmpty() && (existing.hw_model != HardwareModel.UNSET || hasCustomIdentity)
        return hasExistingUser && isDefaultName && isDefaultHwModel
    }

    /**
     * Typed persistence effect produced by [reduceReceivedUser].
     * - [None] — no persistence (stale / conflict / ambiguous outcomes).
     * - [Upsert] — single node upsert (normal update / genuine new node / local authoritative).
     */
    private sealed interface PersistenceEffect {
        data object None : PersistenceEffect

        data class Upsert(val node: Node) : PersistenceEffect
    }

    /**
     * Result of classifying one [User] packet against an in-memory [NodeIndex] snapshot. The reducer is pure: it only
     * reads [before] and the packet fields, and produces the next index plus the side effects to apply once the CAS
     * commits. Safe to re-evaluate on every retry.
     */
    private data class ReceivedUserTransition(
        val after: NodeIndex,
        /** Typed persistence effect to apply once the CAS commits (see [PersistenceEffect]). */
        val persistence: PersistenceEffect,
        /** Node to fire a "new node seen" notification for (null if not a genuine new node). */
        val notifyNode: Node?,
    )

    /**
     * Pure reducer that classifies an incoming [User] packet against the [before] snapshot and returns the next index
     * plus queued side effects. No logging, no DB calls, no coroutine launches — deterministic and safe to re-run on
     * CAS retry.
     *
     * Classification:
     * 1. [fromNum] == [myNum] → local-link authoritative: remove ALL other same-key entries, update local, no notify.
     * 2. [p.public_key] is null/empty/ERROR → NoMatch: normal update.
     * 3. Otherwise the resolved key's canonical firmware-2.8 num (`crc32(public_key).toInt()`) decides direction:
     *     - Multiple other same-key candidates → ambiguous: preserve all, ignore packet.
     *     - Exactly one other same-key candidate:
     *         - Incoming at the canonical num, other not canonical → remove the other noncanonical entry, accept/
     *           transform/upsert the incoming packet, prefer [fromNum], no notification.
     *         - Other at the canonical num, incoming not canonical → remove the incoming stale entry (placeholder or
     *           same-key), prefer the other num, no persistence, no notification.
     *         - Neither num canonical → direction unknown: preserve both, ignore packet.
     *         - Either slot carrying a different established valid key → conflict: preserve both, ignore packet.
     *     - No other same-key candidate → NoMatch: normal update.
     */
    @Suppress("CyclomaticComplexMethod", "ReturnCount", "LongMethod")
    private fun reduceReceivedUser(
        before: NodeIndex,
        fromNum: Int,
        p: User,
        channel: Int,
        manuallyVerified: Boolean,
        myNum: Int?,
    ): ReceivedUserTransition {
        val resolvedKey = resolveStableKey(p.public_key)

        if (fromNum == myNum) {
            // Local-link data is authoritative. Remove ALL other same-key entries in-memory, then update the local
            // node. Durable renumbering is handled by the config-install migration — no repository delete calls.
            val otherSameKeyNums =
                if (resolvedKey == null) {
                    emptyList()
                } else {
                    before.byNum.entries
                        .filter { (otherNum, otherNode) ->
                            otherNum != fromNum && resolveNodeStableKey(otherNode) == resolvedKey
                        }
                        .map { it.key }
                }
            val afterRemovals = otherSameKeyNums.fold(before) { idx, num -> idx.remove(num) }
            val localNode = afterRemovals.byNum[fromNum] ?: createDefaultNode(fromNum, channel)
            val transformed = transformUserNode(localNode, p, channel, manuallyVerified)
            return ReceivedUserTransition(
                after = afterRemovals.put(fromNum, transformed, preferredNum = fromNum),
                persistence = PersistenceEffect.Upsert(transformed),
                notifyNode = null,
            )
        }

        // Non-local: identity matching against other nodes in the snapshot. Direction is decided by the firmware-2.8
        // canonical node num (crc32 of the resolved public key) — the slot whose num matches the key is the canonical
        // home; the other same-key sighting is a stale replay that must yield.
        if (resolvedKey != null) {
            val canonicalNum = resolvedKey.crc32().toInt()
            val matches =
                before.byNum.entries.filter { (otherNum, otherNode) ->
                    otherNum != fromNum && resolveNodeStableKey(otherNode) == resolvedKey
                }

            if (matches.size > 1) {
                // Ambiguous: multiple other candidates carry the same key. Don't pick arbitrarily.
                return ReceivedUserTransition(before, PersistenceEffect.None, null)
            }

            if (matches.size == 1) {
                val other = matches.single()
                val otherIsCanonical = other.key == canonicalNum
                val incomingIsCanonical = fromNum == canonicalNum
                val existing = before.byNum[fromNum]
                val existingKey = existing?.let { resolveNodeStableKey(it) }
                val isGeneratedPlaceholder = existing != null && NodeIndex.isGeneratedPlaceholder(existing)
                // The incoming slot is open for canonical-direction reconciliation when absent, a generated
                // placeholder, or already carrying the same key. A different established valid key is a conflict.
                val incomingSlotIsOpen = existing == null || isGeneratedPlaceholder || existingKey == resolvedKey

                if (incomingIsCanonical && !otherIsCanonical) {
                    // Packet arrived at the canonical num; the other same-key entry is a stale noncanonical sighting.
                    if (!incomingSlotIsOpen) {
                        // Different established identity at the canonical num → conflict, preserve both.
                        return ReceivedUserTransition(before, PersistenceEffect.None, null)
                    }
                    // Remove the noncanonical same-key entry, accept/transform/upsert the incoming packet, prefer the
                    // canonical num as the byId representative, no notification.
                    val afterRemoval = before.remove(other.key)
                    val baseNode = afterRemoval.byNum[fromNum] ?: createDefaultNode(fromNum, channel)
                    val transformed = transformUserNode(baseNode, p, channel, manuallyVerified)
                    return ReceivedUserTransition(
                        after = afterRemoval.put(fromNum, transformed, preferredNum = fromNum),
                        persistence = PersistenceEffect.Upsert(transformed),
                        notifyNode = null,
                    )
                }

                if (otherIsCanonical && !incomingIsCanonical) {
                    // Other entry sits at the canonical num; the incoming packet at fromNum is a stale noncanonical
                    // replay. Remove the incoming slot only when it is open; a different established key is a conflict.
                    if (!incomingSlotIsOpen) {
                        return ReceivedUserTransition(before, PersistenceEffect.None, null)
                    }
                    return ReceivedUserTransition(
                        after = before.remove(fromNum, preferredNum = other.key),
                        persistence = PersistenceEffect.None,
                        notifyNode = null,
                    )
                }

                // Neither num is canonical (both-canonical is impossible since other.key != fromNum): direction
                // unknown. Preserve both, no persistence, no notification.
                return ReceivedUserTransition(before, PersistenceEffect.None, null)
            }
        }

        // NoMatch: normal update path.
        val existing = before.byNum[fromNum] ?: createDefaultNode(fromNum, channel)
        val isNewNode = existing.isUnknownUser && p.hw_model != HardwareModel.UNSET
        val shouldPreserve = shouldPreserveExistingUser(existing.user, p)
        val transformed = transformUserNode(existing, p, channel, manuallyVerified)
        val notify = if (isNewNode && !shouldPreserve) transformed else null
        return ReceivedUserTransition(
            after = before.put(fromNum, transformed),
            persistence = PersistenceEffect.Upsert(transformed),
            notifyNode = notify,
        )
    }

    /** Pure factory for a placeholder [Node] at [num]. Kept side-effect-free so the reducer can call it safely. */
    private fun createDefaultNode(num: Int, channel: Int): Node {
        val userId = NodeAddress.numToDefaultId(num)
        return Node(
            num = num,
            user =
            User(
                id = userId,
                long_name = "Meshtastic ${userId.takeLast(GENERATED_NODE_NAME_SUFFIX_LENGTH)}",
                short_name = userId.takeLast(GENERATED_NODE_NAME_SUFFIX_LENGTH),
                hw_model = HardwareModel.UNSET,
            ),
            channel = channel,
        )
    }

    /**
     * Pure transform of an existing [node] under an incoming [User] packet (extracted from the legacy updateNode path).
     */
    private fun transformUserNode(node: Node, p: User, channel: Int, manuallyVerified: Boolean): Node {
        val shouldPreserve = shouldPreserveExistingUser(node.user, p)
        return if (shouldPreserve) {
            node.copy(channel = channel, manuallyVerified = manuallyVerified)
        } else {
            val sanitizedUser = if (resolveStableKey(p.public_key) == null) p.copy(public_key = ByteString.EMPTY) else p
            val keyMatch = !node.hasPKC || node.user.public_key == sanitizedUser.public_key
            val newUser = if (keyMatch) sanitizedUser else sanitizedUser.copy(public_key = ByteString.EMPTY)
            node.copy(
                user = newUser,
                publicKey = newUser.public_key,
                channel = channel,
                manuallyVerified = manuallyVerified,
            )
        }
    }

    /**
     * Applies the side effects queued by [reduceReceivedUser] exactly once after the CAS commits, dispatching on the
     * typed [PersistenceEffect]. Each effect is launched in a way that survives CAS retries — the reducer already
     * de-duplicated them into a single [ReceivedUserTransition].
     *
     * Launch shape per outcome:
     * - [PersistenceEffect.Upsert] — single upsert, gated on DB ready (a fresh node identity must land even when the
     *   destructive-write gate is still closed).
     * - [PersistenceEffect.None] — no persistence (ambiguous / conflict / stale outcomes).
     */
    private fun applyReceivedUserEffects(transition: ReceivedUserTransition) {
        when (val p = transition.persistence) {
            is PersistenceEffect.None -> Unit

            is PersistenceEffect.Upsert -> {
                if (p.node.user.id.isNotEmpty() && isNodeDbReady.value) {
                    scope.handledLaunch { nodeRepository.upsert(p.node) }
                }
            }
        }
        transition.notifyNode?.let { node ->
            scope.handledLaunch {
                notificationManager.dispatch(
                    Notification(
                        title = notificationTitleFormatter(node.user.short_name),
                        message = node.user.long_name,
                        category = Notification.Category.NodeEvent,
                        id = node.num,
                        deepLinkUri = "meshtastic://meshtastic/nodes/${node.num}",
                    ),
                )
            }
        }
    }

    /**
     * Test seam over the notification title; production resolves compose-resources lazily inside the dispatch
     * coroutine.
     */
    internal var notificationTitleFormatter: suspend (String) -> String = { shortName ->
        getStringSuspend(Res.string.new_node_seen, shortName)
    }

    override fun toNodeID(nodeNum: Int): String = if (nodeNum == NodeAddress.NODENUM_BROADCAST) {
        NodeAddress.ID_BROADCAST
    } else {
        nodeIndex.value.byNum[nodeNum]?.user?.id ?: NodeAddress.numToDefaultId(nodeNum)
    }
}
