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
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.matcher.capture.capture
import dev.mokkery.mock
import dev.mokkery.verify
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.meshtastic.core.common.util.crc32
import org.meshtastic.core.model.MyNodeInfo
import org.meshtastic.core.model.Node
import org.meshtastic.core.model.NodeAddress
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.Notification
import org.meshtastic.core.repository.NotificationManager
import org.meshtastic.proto.DeviceMetrics
import org.meshtastic.proto.EnvironmentMetrics
import org.meshtastic.proto.HardwareModel
import org.meshtastic.proto.Telemetry
import org.meshtastic.proto.User
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.meshtastic.proto.NodeInfo as ProtoNodeInfo
import org.meshtastic.proto.Position as ProtoPosition

class NodeManagerImplTest {

    private val nodeRepository: NodeRepository = mock(MockMode.autofill)
    private val notificationManager: NotificationManager = mock(MockMode.autofill)
    private val testScope = TestScope()

    private lateinit var nodeManager: NodeManagerImpl

    @BeforeTest
    fun setUp() {
        nodeManager = NodeManagerImpl(nodeRepository, notificationManager, testScope)
        // Override the compose-resources formatter so notification dispatch is deterministic in the
        // plain-JVM test env (getStringSuspend does not resolve here). Tests that assert "no dispatch"
        // still hold: the override only changes the title, not whether dispatch fires.
        nodeManager.notificationTitleFormatter = { shortName -> "New node seen: $shortName" }
    }

    @Test
    fun `getOrCreateNode creates default user for unknown node`() {
        val nodeNum = 1234
        val result = nodeManager.getOrCreateNode(nodeNum)

        assertNotNull(result)
        assertEquals(nodeNum, result.num)
        assertTrue(result.user.long_name.startsWith("Meshtastic"))
        assertEquals(NodeAddress.numToDefaultId(nodeNum), result.user.id)
    }

    @Test
    fun `handleReceivedUser preserves existing user if incoming is default`() {
        val nodeNum = 1234
        val existingUser =
            User(id = "!12345678", long_name = "My Custom Name", short_name = "MCN", hw_model = HardwareModel.TLORA_V2)

        // Setup existing node
        nodeManager.updateNode(nodeNum) { it.copy(user = existingUser) }

        val incomingDefaultUser =
            User(id = "!12345678", long_name = "Meshtastic 5678", short_name = "5678", hw_model = HardwareModel.UNSET)

        nodeManager.handleReceivedUser(nodeNum, incomingDefaultUser)

        val result = nodeManager.nodeDBbyNodeNum[nodeNum]
        assertEquals("My Custom Name", result!!.user.long_name)
        assertEquals(HardwareModel.TLORA_V2, result.user.hw_model)
    }

    @Test
    fun `handleReceivedUser updates user if incoming is higher detail`() {
        val nodeNum = 1234
        // Use a non-UNSET hw_model so isUnknownUser=false (avoids new-node notification + getString)
        val existingUser =
            User(id = "!12345678", long_name = "Old Name", short_name = "ON", hw_model = HardwareModel.TLORA_V2)

        nodeManager.updateNode(nodeNum) { it.copy(user = existingUser) }

        val incomingDetailedUser =
            User(id = "!12345678", long_name = "Real User", short_name = "RU", hw_model = HardwareModel.TLORA_V1)

        nodeManager.handleReceivedUser(nodeNum, incomingDetailedUser)

        val result = nodeManager.nodeDBbyNodeNum[nodeNum]
        assertEquals("Real User", result!!.user.long_name)
        assertEquals(HardwareModel.TLORA_V1, result.user.hw_model)
    }

    @Test
    fun `handleReceivedPosition updates node position`() {
        val nodeNum = 1234
        val position = ProtoPosition(latitude_i = 450000000, longitude_i = 900000000)

        nodeManager.handleReceivedPosition(nodeNum, 9999, position, 0)

        val result = nodeManager.nodeDBbyNodeNum[nodeNum]
        assertNotNull(result)
        assertNotNull(result.position)
        assertEquals(450000000, result.position.latitude_i)
        assertEquals(900000000, result.position.longitude_i)
    }

    @Test
    fun `handleReceivedPosition with zero coordinates preserves last known location but updates satellites`() {
        val nodeNum = 1234
        val initialPosition = ProtoPosition(latitude_i = 450000000, longitude_i = 900000000, sats_in_view = 10)
        nodeManager.handleReceivedPosition(nodeNum, 9999, initialPosition, 1000000L)

        // Receive "zero" position with new satellite count
        val zeroPosition = ProtoPosition(latitude_i = 0, longitude_i = 0, sats_in_view = 5, time = 1001)
        nodeManager.handleReceivedPosition(nodeNum, 9999, zeroPosition, 1001000L)

        val result = nodeManager.nodeDBbyNodeNum[nodeNum]
        assertEquals(450000000, result!!.position.latitude_i)
        assertEquals(900000000, result.position.longitude_i)
        assertEquals(5, result.position.sats_in_view)
        assertEquals(1001, result.lastHeard)
    }

    @Test
    fun `handleReceivedPosition for local node ignores purely empty packets`() {
        val myNum = 1111
        val emptyPos = ProtoPosition(latitude_i = 0, longitude_i = 0, sats_in_view = 0, time = 0)

        nodeManager.handleReceivedPosition(myNum, myNum, emptyPos, 0)

        val result = nodeManager.nodeDBbyNodeNum[myNum]
        // Should still be null since the empty position for local node is ignored
        assertNull(result)
    }

    @Test
    fun `handleReceivedTelemetry updates lastHeard`() {
        val nodeNum = 1234
        nodeManager.updateNode(nodeNum) { it.copy(lastHeard = 1000) }

        val telemetry = Telemetry(time = 2000, device_metrics = DeviceMetrics(battery_level = 50))

        nodeManager.handleReceivedTelemetry(nodeNum, telemetry)

        val result = nodeManager.nodeDBbyNodeNum[nodeNum]
        assertEquals(2000, result!!.lastHeard)
    }

    @Test
    fun `handleReceivedTelemetry updates device metrics`() {
        val nodeNum = 1234
        val telemetry = Telemetry(device_metrics = DeviceMetrics(battery_level = 75, voltage = 3.8f))

        nodeManager.handleReceivedTelemetry(nodeNum, telemetry)

        val result = nodeManager.nodeDBbyNodeNum[nodeNum]
        assertNotNull(result!!.deviceMetrics)
        assertEquals(75, result.deviceMetrics.battery_level)
        assertEquals(3.8f, result.deviceMetrics.voltage)
    }

    @Test
    fun `handleReceivedTelemetry updates environment metrics`() {
        val nodeNum = 1234
        val telemetry =
            Telemetry(environment_metrics = EnvironmentMetrics(temperature = 22.5f, relative_humidity = 45.0f))

        nodeManager.handleReceivedTelemetry(nodeNum, telemetry)

        val result = nodeManager.nodeDBbyNodeNum[nodeNum]
        assertNotNull(result!!.environmentMetrics)
        assertEquals(22.5f, result.environmentMetrics.temperature)
        assertEquals(45.0f, result.environmentMetrics.relative_humidity)
    }

    @Test
    fun `clear resets internal state`() {
        nodeManager.updateNode(1234) { it.copy(user = it.user.copy(long_name = "Test")) }
        nodeManager.clear()

        assertTrue(nodeManager.nodeDBbyNodeNum.isEmpty())
        assertNull(nodeManager.getNodeById("!000004d2"))
        assertNull(nodeManager.myNodeNum.value)
    }

    @Test
    fun `toNodeID returns broadcast ID for broadcast nodeNum`() {
        val result = nodeManager.toNodeID(NodeAddress.NODENUM_BROADCAST)
        assertEquals(NodeAddress.ID_BROADCAST, result)
    }

    @Test
    fun `toNodeID returns default hex ID for unknown node`() {
        val result = nodeManager.toNodeID(0x1234)
        assertEquals(NodeAddress.numToDefaultId(0x1234), result)
    }

    @Test
    fun `toNodeID returns user ID for known node`() {
        val nodeNum = 5678
        val userId = "!customid"
        nodeManager.updateNode(nodeNum) { it.copy(user = it.user.copy(id = userId)) }
        val result = nodeManager.toNodeID(nodeNum)
        assertEquals(userId, result)
    }

    @Test
    fun `removeByNodenum removes node from map`() {
        val nodeNum = 1234
        nodeManager.updateNode(nodeNum) {
            Node(num = nodeNum, user = User(id = "!testnode", long_name = "Test", short_name = "T"))
        }
        assertTrue(nodeManager.nodeDBbyNodeNum.containsKey(nodeNum))
        assertNotNull(nodeManager.getNodeById("!testnode"))

        nodeManager.removeByNodenum(nodeNum)

        assertTrue(!nodeManager.nodeDBbyNodeNum.containsKey(nodeNum))
        assertNull(nodeManager.getNodeById("!testnode"))
    }

    @Test
    fun `handleReceivedUser sets publicKey from user public_key`() {
        val nodeNum = 1234
        val pk = ByteArray(32) { (it + 1).toByte() }.toByteString()
        val existingUser =
            User(id = "!12345678", long_name = "Existing", short_name = "EX", hw_model = HardwareModel.TLORA_V2)
        nodeManager.updateNode(nodeNum) { it.copy(user = existingUser) }

        val incomingUser =
            User(
                id = "!12345678",
                long_name = "Updated",
                short_name = "UP",
                hw_model = HardwareModel.TLORA_V2,
                public_key = pk,
            )
        nodeManager.handleReceivedUser(nodeNum, incomingUser)

        val result = nodeManager.nodeDBbyNodeNum[nodeNum]!!
        assertEquals(pk, result.publicKey)
        assertEquals(pk, result.user.public_key)
        assertTrue(result.hasPKC)
    }

    @Test
    fun `handleReceivedUser sets empty publicKey when key mismatch clears user key`() {
        val nodeNum = 1234
        val existingPk = ByteArray(32) { (it + 1).toByte() }.toByteString()
        val existingUser =
            User(
                id = "!12345678",
                long_name = "Existing",
                short_name = "EX",
                hw_model = HardwareModel.TLORA_V2,
                public_key = existingPk,
            )
        nodeManager.updateNode(nodeNum) { it.copy(user = existingUser, publicKey = existingPk) }

        val differentPk = ByteArray(32) { (it + 10).toByte() }.toByteString()
        val incomingUser =
            User(
                id = "!12345678",
                long_name = "Updated",
                short_name = "UP",
                hw_model = HardwareModel.TLORA_V2,
                public_key = differentPk,
            )
        nodeManager.handleReceivedUser(nodeNum, incomingUser)

        val result = nodeManager.nodeDBbyNodeNum[nodeNum]!!
        // Key mismatch: newUser gets public_key cleared to EMPTY, and publicKey should match
        assertEquals(ByteString.EMPTY, result.publicKey)
        assertEquals(ByteString.EMPTY, result.user.public_key)
    }

    @Test
    fun `installNodeInfo sets publicKey from user public_key`() {
        val nodeNum = 5678
        val pk = ByteArray(32) { (it + 1).toByte() }.toByteString()
        val user =
            User(
                id = "!abcd1234",
                long_name = "Remote Node",
                short_name = "RN",
                hw_model = HardwareModel.HELTEC_V3,
                public_key = pk,
            )
        val info = ProtoNodeInfo(num = nodeNum, user = user, last_heard = 1000, channel = 0)

        nodeManager.installNodeInfo(info)

        val result = nodeManager.nodeDBbyNodeNum[nodeNum]!!
        assertEquals(pk, result.publicKey)
        assertEquals(pk, result.user.public_key)
        assertTrue(result.hasPKC)
    }

    @Test
    fun `installNodeInfo clears publicKey for licensed users`() {
        val nodeNum = 5678
        val pk = ByteArray(32) { (it + 1).toByte() }.toByteString()
        val user =
            User(
                id = "!abcd1234",
                long_name = "Licensed Op",
                short_name = "LO",
                hw_model = HardwareModel.HELTEC_V3,
                public_key = pk,
                is_licensed = true,
            )
        val info = ProtoNodeInfo(num = nodeNum, user = user, last_heard = 1000, channel = 0)

        nodeManager.installNodeInfo(info)

        val result = nodeManager.nodeDBbyNodeNum[nodeNum]!!
        assertEquals(ByteString.EMPTY, result.publicKey)
        assertEquals(ByteString.EMPTY, result.user.public_key)
    }

    @Test
    fun `getMyNodeInfo returns null when repository has no info`() {
        every { nodeRepository.myNodeInfo } returns MutableStateFlow(null)

        val result = nodeManager.getMyNodeInfo()

        assertNull(result)
    }

    @Test
    fun `getMyNodeInfo synthesizes from repository and nodeDB`() {
        val myNum = 1234
        val repoInfo =
            MyNodeInfo(
                myNodeNum = myNum,
                hasGPS = false,
                model = "tbeam",
                firmwareVersion = "2.5.0",
                couldUpdate = false,
                shouldUpdate = false,
                currentPacketId = 100L,
                messageTimeoutMsec = 5000,
                minAppVersion = 30000,
                maxChannels = 8,
                hasWifi = false,
                channelUtilization = 0f,
                airUtilTx = 0f,
                deviceId = null,
            )
        every { nodeRepository.myNodeInfo } returns MutableStateFlow(repoInfo)

        // Add node with position (non-zero lat → hasGPS = true)
        nodeManager.handleReceivedPosition(myNum, myNum, ProtoPosition(latitude_i = 100), 0)
        nodeManager.updateNode(myNum) { it.copy(user = it.user.copy(id = "!mydevice", hw_model = HardwareModel.TBEAM)) }

        val result = nodeManager.getMyNodeInfo()

        assertNotNull(result)
        assertEquals(myNum, result.myNodeNum)
        assertTrue(result.hasGPS)
        assertEquals("tbeam", result.model)
        assertEquals("!mydevice", result.deviceId)
    }

    @Test
    fun `getMyNodeInfo falls back to nodeDB model when repository model is null`() {
        val myNum = 1234
        val repoInfo =
            MyNodeInfo(
                myNodeNum = myNum,
                hasGPS = false,
                model = null,
                firmwareVersion = "2.5.0",
                couldUpdate = false,
                shouldUpdate = false,
                currentPacketId = 100L,
                messageTimeoutMsec = 5000,
                minAppVersion = 30000,
                maxChannels = 8,
                hasWifi = false,
                channelUtilization = 0f,
                airUtilTx = 0f,
                deviceId = null,
            )
        every { nodeRepository.myNodeInfo } returns MutableStateFlow(repoInfo)

        nodeManager.updateNode(myNum) { it.copy(user = it.user.copy(hw_model = HardwareModel.HELTEC_V3)) }

        val result = nodeManager.getMyNodeInfo()

        assertNotNull(result)
        assertEquals("HELTEC_V3", result.model)
    }

    @Test
    fun `handleReceivedTelemetry with null metrics does not crash`() {
        val nodeNum = 1234
        nodeManager.updateNode(nodeNum) { it.copy(lastHeard = 1000) }

        // Telemetry with no metrics at all
        val telemetry = Telemetry(time = 3000)

        nodeManager.handleReceivedTelemetry(nodeNum, telemetry)

        val result = nodeManager.nodeDBbyNodeNum[nodeNum]
        assertNotNull(result)
        assertEquals(3000, result.lastHeard)
    }

    @Test
    fun `getMyId returns empty when disconnected`() {
        every { nodeRepository.myNodeInfo } returns MutableStateFlow(null)

        val result = nodeManager.getMyId()
        assertEquals("", result)
    }

    @Test
    fun `getMyId returns user ID when connected`() {
        val myNum = 1234
        nodeManager.setMyNodeNum(myNum)
        nodeManager.updateNode(myNum) { it.copy(user = it.user.copy(id = "!mynode42")) }

        val result = nodeManager.getMyId()
        assertEquals("!mynode42", result)
    }

    // ---------- Atomic identity reconciliation ----------
    //
    // Covers the CAS-loop reducer that replaced the legacy read-then-mutate sequence. Each test asserts typed
    // in-memory state AND the observable side effects (upsert / conditional delete / notification dispatch).

    private val validPk = ByteArray(32) { (it + 1).toByte() }.toByteString()

    private fun ByteString.canonicalNum(): Int = crc32().toInt()

    private fun makeKnownNode(num: Int, pk: ByteString, name: String = "Known"): Node = Node(
        num = num,
        user =
        User(
            id = "!${num.toString(16)}",
            long_name = name,
            short_name = name.take(3),
            hw_model = HardwareModel.TLORA_V2,
            public_key = pk,
        ),
        publicKey = pk,
    )

    private fun enableDbWrites() {
        nodeManager.setNodeDbReady(true)
        nodeManager.setAllowNodeDbWrites(true)
    }

    // 1. Established same-key duplicate

    @Test
    fun `established same-key duplicate removes stale number in memory preserves canonical no upsert`() {
        val oldNum = 1000
        val newNum = validPk.canonicalNum()
        nodeManager.updateNode(newNum) { makeKnownNode(newNum, validPk, "Migrated") }
        nodeManager.updateNode(oldNum) { makeKnownNode(oldNum, validPk, "Stale Established") }
        enableDbWrites()

        val staleUser =
            User(
                id = "!old",
                long_name = "Stale",
                short_name = "STL",
                hw_model = HardwareModel.TLORA_V2,
                public_key = validPk,
            )
        nodeManager.handleReceivedUser(oldNum, staleUser)
        testScope.advanceUntilIdle()

        assertNull(nodeManager.nodeDBbyNodeNum[oldNum])
        val canonical = nodeManager.nodeDBbyNodeNum[newNum]
        assertNotNull(canonical)
        assertEquals("Migrated", canonical!!.user.long_name)
        assertEquals(validPk, canonical.publicKey)
        verifySuspend(mode = VerifyMode.not) { nodeRepository.upsert(any()) }
        verify(mode = VerifyMode.not) { notificationManager.dispatch(any()) }
    }

    // 2. Placeholder duplicate

    @Test
    fun `placeholder duplicate removes placeholder in memory preserves canonical`() {
        val oldNum = 1000
        val newNum = validPk.canonicalNum()
        nodeManager.updateNode(newNum) { makeKnownNode(newNum, validPk, "Migrated") }
        val placeholderKey = ByteString.EMPTY
        val defaultId = NodeAddress.numToDefaultId(oldNum)
        nodeManager.updateNode(oldNum) {
            Node(
                num = oldNum,
                user =
                User(
                    id = defaultId,
                    long_name = "Meshtastic ${defaultId.takeLast(4)}",
                    short_name = defaultId.takeLast(4),
                    hw_model = HardwareModel.UNSET,
                    public_key = placeholderKey,
                ),
                publicKey = placeholderKey,
            )
        }
        enableDbWrites()

        val staleUser =
            User(
                id = "!old",
                long_name = "Stale",
                short_name = "STL",
                hw_model = HardwareModel.TLORA_V2,
                public_key = validPk,
            )
        nodeManager.handleReceivedUser(oldNum, staleUser)
        testScope.advanceUntilIdle()

        assertNull(nodeManager.nodeDBbyNodeNum[oldNum])
        val canonical = nodeManager.nodeDBbyNodeNum[newNum]
        assertNotNull(canonical)
        assertEquals("Migrated", canonical!!.user.long_name)
    }

    // 3. Different-key conflict

    @Test
    fun `different-key conflict preserves both nodes and emits no side effects`() {
        val fromNum = 1000
        val otherNum = 2000
        val differentPk = ByteArray(32) { (it + 50).toByte() }.toByteString()
        nodeManager.updateNode(otherNum) { makeKnownNode(otherNum, validPk, "Other") }
        nodeManager.updateNode(fromNum) { makeKnownNode(fromNum, differentPk, "Established") }
        enableDbWrites()

        val incomingUser =
            User(
                id = "!stale",
                long_name = "Stale",
                short_name = "STL",
                hw_model = HardwareModel.TLORA_V2,
                public_key = validPk,
            )
        nodeManager.handleReceivedUser(fromNum, incomingUser)
        testScope.advanceUntilIdle()

        val fromNode = nodeManager.nodeDBbyNodeNum[fromNum]
        assertNotNull(fromNode)
        assertEquals("Established", fromNode!!.user.long_name)
        assertEquals(differentPk, fromNode.publicKey)
        val otherNode = nodeManager.nodeDBbyNodeNum[otherNum]
        assertNotNull(otherNode)
        assertEquals("Other", otherNode!!.user.long_name)
        verifySuspend(mode = VerifyMode.not) { nodeRepository.upsert(any()) }
        verify(mode = VerifyMode.not) { notificationManager.dispatch(any()) }
    }

    // 4. Multiple same-key matches

    @Test
    fun `multiple same-key matches leaves all entries untouched`() {
        val nodeA = 1000
        val nodeB = 2000
        val fromNum = 3000
        nodeManager.updateNode(nodeA) { makeKnownNode(nodeA, validPk, "Alpha") }
        nodeManager.updateNode(nodeB) { makeKnownNode(nodeB, validPk, "Bravo") }
        enableDbWrites()

        val incomingUser =
            User(
                id = "!incoming",
                long_name = "Incoming",
                short_name = "INC",
                hw_model = HardwareModel.TLORA_V2,
                public_key = validPk,
            )
        nodeManager.handleReceivedUser(fromNum, incomingUser)
        testScope.advanceUntilIdle()

        // Nothing removed, nothing inserted at fromNum.
        assertNotNull(nodeManager.nodeDBbyNodeNum[nodeA])
        assertNotNull(nodeManager.nodeDBbyNodeNum[nodeB])
        assertNull(nodeManager.nodeDBbyNodeNum[fromNum])
        verifySuspend(mode = VerifyMode.not) { nodeRepository.upsert(any()) }
        verify(mode = VerifyMode.not) { notificationManager.dispatch(any()) }
    }

    // 5. Local authoritative renumber

    @Test
    fun `local authoritative renumber removes old same-key entries in memory upserts local no notification`() {
        val localNum = 5000
        val otherNum = 6000
        nodeManager.setMyNodeNum(localNum)
        nodeManager.updateNode(otherNum) { makeKnownNode(otherNum, validPk, "Old Same-Key") }
        enableDbWrites()

        val localUser =
            User(
                id = "!local",
                long_name = "Local Node",
                short_name = "LCL",
                hw_model = HardwareModel.TLORA_V2,
                public_key = validPk,
            )
        nodeManager.handleReceivedUser(localNum, localUser)
        testScope.advanceUntilIdle()

        assertNull(nodeManager.nodeDBbyNodeNum[otherNum])
        val localNode = nodeManager.nodeDBbyNodeNum[localNum]
        assertNotNull(localNode)
        assertEquals("Local Node", localNode!!.user.long_name)
        assertEquals(validPk, localNode.publicKey)
        verifySuspend(mode = VerifyMode.exactly(1)) { nodeRepository.upsert(any()) }
        verify(mode = VerifyMode.not) { notificationManager.dispatch(any()) }
    }

    // 6. Genuine new node

    @Test
    fun `genuine new node fires exactly one upsert and exactly one notification`() {
        val newNodeNum = 3000
        val newPk = ByteArray(32) { (it + 100).toByte() }.toByteString()
        val newUser =
            User(
                id = "!newnode",
                long_name = "New Node",
                short_name = "NEW",
                hw_model = HardwareModel.TLORA_V2,
                public_key = newPk,
            )
        enableDbWrites()

        val captured = mutableListOf<Notification>()
        every { notificationManager.dispatch(capture(captured)) } returns Unit

        nodeManager.handleReceivedUser(newNodeNum, newUser)
        testScope.advanceUntilIdle()

        val result = nodeManager.nodeDBbyNodeNum[newNodeNum]
        assertNotNull(result)
        assertEquals("New Node", result!!.user.long_name)
        assertEquals(newPk, result.publicKey)
        verifySuspend(mode = VerifyMode.exactly(1)) { nodeRepository.upsert(any()) }
        // Strengthen: capture the dispatched Notification and verify payload + routing fields, not just the call count.
        assertEquals(1, captured.size)
        val n = captured.first()
        assertEquals(newNodeNum, n.id)
        assertEquals("New Node", n.message)
        assertEquals(Notification.Category.NodeEvent, n.category)
        assertEquals("meshtastic://meshtastic/nodes/$newNodeNum", n.deepLinkUri)
    }

    // 7. Invalid / malformed keys (null, empty, ERROR) are treated as NoMatch

    @Test
    fun `invalid public keys skip identity matching and update normally`() {
        val existingNum = 7000
        val existingPk = ByteArray(32) { (it + 1).toByte() }.toByteString()
        nodeManager.updateNode(existingNum) { makeKnownNode(existingNum, existingPk, "Canonical") }
        enableDbWrites()

        // Three packets at a different number with keys that cannot identify a stable identity.
        // None of them should match the canonical node at 7000 or trigger stale cleanup.
        val targetNum = 8000

        // (a) null key
        nodeManager.handleReceivedUser(
            targetNum,
            User(id = "!a", long_name = "A", short_name = "A", hw_model = HardwareModel.TLORA_V2),
        )
        testScope.advanceUntilIdle()

        // (b) empty key
        nodeManager.handleReceivedUser(
            targetNum,
            User(
                id = "!b",
                long_name = "B",
                short_name = "B",
                hw_model = HardwareModel.TLORA_V2,
                public_key = ByteString.EMPTY,
            ),
        )
        testScope.advanceUntilIdle()

        // (c) ERROR key
        nodeManager.handleReceivedUser(
            targetNum,
            User(
                id = "!c",
                long_name = "C",
                short_name = "C",
                hw_model = HardwareModel.TLORA_V2,
                public_key = Node.ERROR_BYTE_STRING,
            ),
        )
        testScope.advanceUntilIdle()

        // Canonical node untouched (no stale cleanup).
        assertNotNull(nodeManager.nodeDBbyNodeNum[existingNum])
    }

    // 8. Same number same key is a normal update, not a cross-number stale

    @Test
    fun `same number same key updates normally without stale classification`() {
        val nodeNum = 9000
        nodeManager.updateNode(nodeNum) { makeKnownNode(nodeNum, validPk, "Original") }
        enableDbWrites()

        val updatedUser =
            User(
                id = "!${nodeNum.toString(16)}",
                long_name = "Updated",
                short_name = "UPD",
                hw_model = HardwareModel.TLORA_V2,
                public_key = validPk,
            )
        nodeManager.handleReceivedUser(nodeNum, updatedUser)
        testScope.advanceUntilIdle()

        val result = nodeManager.nodeDBbyNodeNum[nodeNum]
        assertNotNull(result)
        assertEquals("Updated", result!!.user.long_name)
        assertEquals(validPk, result.publicKey)
    }

    // 9. Stale duplicate issues no upsert for the stale number

    @Test
    fun `stale duplicate issues no upsert for the stale number`() {
        val oldNum = 1000
        val newNum = validPk.canonicalNum()
        nodeManager.updateNode(newNum) { makeKnownNode(newNum, validPk, "Canonical") }
        nodeManager.updateNode(oldNum) { makeKnownNode(oldNum, validPk, "Stale Dup") }
        enableDbWrites()

        val staleUser =
            User(
                id = "!old",
                long_name = "Stale Packet",
                short_name = "STL",
                hw_model = HardwareModel.TLORA_V2,
                public_key = validPk,
            )
        nodeManager.handleReceivedUser(oldNum, staleUser)
        testScope.advanceUntilIdle()

        assertNull(nodeManager.nodeDBbyNodeNum[oldNum])
        // No upsert reached the repository for the stale packet.
        verifySuspend(mode = VerifyMode.not) { nodeRepository.upsert(any()) }
    }

    // 10. No notification for stale / conflict / local outcomes

    @Test
    fun `notification never fires for stale conflict or local outcomes`() {
        // Stale: stale packet at old number with established canonical elsewhere.
        val staleOld = 1000
        val staleCanonical = validPk.canonicalNum()
        nodeManager.updateNode(staleCanonical) { makeKnownNode(staleCanonical, validPk, "Canonical") }
        nodeManager.updateNode(staleOld) { makeKnownNode(staleOld, validPk, "Old") }
        nodeManager.handleReceivedUser(
            staleOld,
            User(id = "!s", long_name = "S", short_name = "S", hw_model = HardwareModel.TLORA_V2, public_key = validPk),
        )

        // Conflict: different established identity at fromNum.
        val conflictFrom = 3000
        val conflictOther = 4000
        val conflictPk = ByteArray(32) { (it + 50).toByte() }.toByteString()
        nodeManager.updateNode(conflictOther) { makeKnownNode(conflictOther, validPk, "Other") }
        nodeManager.updateNode(conflictFrom) { makeKnownNode(conflictFrom, conflictPk, "Established") }
        nodeManager.handleReceivedUser(
            conflictFrom,
            User(id = "!c", long_name = "C", short_name = "C", hw_model = HardwareModel.TLORA_V2, public_key = validPk),
        )

        // Local: fromNum == myNodeNum, local-link authoritative update.
        val localNum = 5000
        val localGhost = 6000
        nodeManager.setMyNodeNum(localNum)
        nodeManager.updateNode(localGhost) { makeKnownNode(localGhost, validPk, "Ghost") }
        nodeManager.handleReceivedUser(
            localNum,
            User(id = "!l", long_name = "L", short_name = "L", hw_model = HardwareModel.TLORA_V2, public_key = validPk),
        )

        testScope.advanceUntilIdle()

        // No notification fired for any of the three outcomes.
        verify(mode = VerifyMode.not) { notificationManager.dispatch(any()) }
    }

    // 11. byId consistency after stale removal — when stale and canonical share a user ID,
    // removing the stale node must not orphan the canonical node in the byId index.

    @Test
    fun `byId index points to canonical survivor after stale removal shares user id`() {
        val oldNum = 1000
        val newNum = validPk.canonicalNum()
        val sharedUserId = "!shared"
        nodeManager.updateNode(newNum) {
            Node(
                num = newNum,
                user =
                User(
                    id = sharedUserId,
                    long_name = "Canonical",
                    short_name = "CAN",
                    hw_model = HardwareModel.TLORA_V2,
                    public_key = validPk,
                ),
                publicKey = validPk,
            )
        }
        nodeManager.updateNode(oldNum) {
            Node(
                num = oldNum,
                user =
                User(
                    id = sharedUserId,
                    long_name = "Stale",
                    short_name = "STL",
                    hw_model = HardwareModel.TLORA_V2,
                    public_key = validPk,
                ),
                publicKey = validPk,
            )
        }
        enableDbWrites()

        val staleUser =
            User(
                id = sharedUserId,
                long_name = "Stale",
                short_name = "STL",
                hw_model = HardwareModel.TLORA_V2,
                public_key = validPk,
            )
        nodeManager.handleReceivedUser(oldNum, staleUser)
        testScope.advanceUntilIdle()

        assertNull(nodeManager.nodeDBbyNodeNum[oldNum])
        val survivor = nodeManager.getNodeById(sharedUserId)
        assertNotNull(survivor)
        assertEquals(newNum, survivor!!.num)
    }

    // ── Additional identity reconciliation tests ────────────────────────────────

    // 12. Malformed key lengths (1, 31, 33) are rejected

    @Test
    fun `malformed key lengths 1 31 33 are rejected and skip identity matching`() {
        val nodeNum = 1234
        val shortKey = ByteArray(1) { 1 }.toByteString()
        val almostKey = ByteArray(31) { 2 }.toByteString()
        val longKey = ByteArray(33) { 3 }.toByteString()

        // All malformed keys should be treated as no-key (normal update path).
        nodeManager.handleReceivedUser(
            nodeNum,
            User(
                id = "!short",
                long_name = "S",
                short_name = "S",
                hw_model = HardwareModel.TLORA_V2,
                public_key = shortKey,
            ),
        )
        var result = nodeManager.nodeDBbyNodeNum[nodeNum]
        assertEquals(ByteString.EMPTY, result!!.publicKey)

        nodeManager.handleReceivedUser(
            nodeNum,
            User(
                id = "!almost",
                long_name = "A",
                short_name = "A",
                hw_model = HardwareModel.TLORA_V2,
                public_key = almostKey,
            ),
        )
        result = nodeManager.nodeDBbyNodeNum[nodeNum]
        assertEquals(ByteString.EMPTY, result!!.publicKey)

        nodeManager.handleReceivedUser(
            nodeNum,
            User(
                id = "!long",
                long_name = "L",
                short_name = "L",
                hw_model = HardwareModel.TLORA_V2,
                public_key = longKey,
            ),
        )
        result = nodeManager.nodeDBbyNodeNum[nodeNum]
        assertEquals(ByteString.EMPTY, result!!.publicKey)
    }

    // 13. Invalid Node.publicKey falls back to valid User.public_key

    @Test
    fun `invalid Node publicKey falls back to valid User public_key`() {
        val nodeNum = 1234
        val validUserKey = ByteArray(32) { 42 }.toByteString()
        val invalidNodeKey = ByteArray(16) { 1 }.toByteString() // Wrong size

        nodeManager.handleReceivedUser(
            nodeNum,
            User(
                id = "!test",
                long_name = "Test",
                short_name = "T",
                hw_model = HardwareModel.TLORA_V2,
                public_key = validUserKey,
            ),
        )
        // Manually set an invalid Node.publicKey
        nodeManager.updateNode(nodeNum) { it.copy(publicKey = invalidNodeKey) }

        // resolveNodeStableKey should fall back to User.public_key
        val node = nodeManager.nodeDBbyNodeNum[nodeNum]
        val resolvedKey = nodeManager.resolveNodeStableKey(node!!)
        assertEquals(validUserKey, resolvedKey)
    }

    // 14. UNSET hardware with a different valid key is preserved (conflict)

    @Test
    fun `UNSET hardware with different valid key is preserved as conflict`() {
        val canonicalNum = 1000
        val conflictNum = 2000
        val canonicalKey = ByteArray(32) { 10 }.toByteString()
        val conflictKey = ByteArray(32) { 20 }.toByteString()

        // Canonical node with established identity
        nodeManager.updateNode(canonicalNum) { makeKnownNode(canonicalNum, canonicalKey, "Canonical") }

        // Conflict node with UNSET hardware but different valid key
        nodeManager.updateNode(conflictNum) {
            Node(
                num = conflictNum,
                user =
                User(
                    id = "!conflict",
                    long_name = "Conflict",
                    short_name = "CON",
                    hw_model = HardwareModel.UNSET,
                    public_key = conflictKey,
                ),
                publicKey = conflictKey,
            )
        }

        // Packet at conflictNum with canonicalKey should be treated as conflict (preserve both)
        nodeManager.handleReceivedUser(
            conflictNum,
            User(
                id = "!c",
                long_name = "C",
                short_name = "C",
                hw_model = HardwareModel.TLORA_V2,
                public_key = canonicalKey,
            ),
        )

        // Both nodes should still exist
        assertNotNull(nodeManager.nodeDBbyNodeNum[canonicalNum])
        assertNotNull(nodeManager.nodeDBbyNodeNum[conflictNum])
        assertEquals(canonicalKey, nodeManager.nodeDBbyNodeNum[canonicalNum]!!.publicKey)
        assertEquals(conflictKey, nodeManager.nodeDBbyNodeNum[conflictNum]!!.publicKey)
    }

    // 15. Custom incomplete identity is preserved

    @Test
    fun `custom incomplete identity is preserved over default incoming`() {
        val nodeNum = 1234
        val customUser =
            User(
                id = "!custom",
                long_name = "My Custom Name",
                short_name = "MCN",
                hw_model = HardwareModel.UNSET, // Incomplete hardware
            )
        nodeManager.updateNode(nodeNum) { it.copy(user = customUser) }

        // Incoming default user should not overwrite custom identity
        val defaultUser =
            User(
                id = NodeAddress.numToDefaultId(nodeNum),
                long_name = "Meshtastic ${nodeNum.toHex().takeLast(4)}",
                short_name = nodeNum.toHex().takeLast(4),
                hw_model = HardwareModel.UNSET,
            )
        nodeManager.handleReceivedUser(nodeNum, defaultUser)

        val result = nodeManager.nodeDBbyNodeNum[nodeNum]
        assertEquals("My Custom Name", result!!.user.long_name)
        assertEquals("MCN", result.user.short_name)
    }

    // 16. Three nodes sharing one user ID

    @Test
    fun `three nodes sharing one user ID selects deterministic representative`() {
        val userId = "!shared"
        val node1 = 1000
        val node2 = 2000
        val node3 = 3000

        // All three have the same user ID but different node numbers
        nodeManager.updateNode(node1) {
            Node(
                num = node1,
                user = User(id = userId, long_name = "N1", short_name = "N1", hw_model = HardwareModel.UNSET),
            )
        }
        nodeManager.updateNode(node2) {
            Node(
                num = node2,
                user = User(id = userId, long_name = "N2", short_name = "N2", hw_model = HardwareModel.TLORA_V2),
            )
        }
        nodeManager.updateNode(node3) {
            Node(
                num = node3,
                user = User(id = userId, long_name = "N3", short_name = "N3", hw_model = HardwareModel.UNSET),
            )
        }

        // byId should point to node2 (non-placeholder, lowest among non-placeholders)
        val representative = nodeManager.getNodeById(userId)
        assertNotNull(representative)
        assertEquals(node2, representative!!.num)
    }

    // 17. Old-ID survivor restoration after put

    @Test
    fun `old ID survivor restored after put changes user ID`() {
        val nodeNum = 1234
        val oldUserId = "!old"
        val newUserId = "!new"
        val survivorNum = 5678

        // Two nodes with old user ID
        nodeManager.updateNode(nodeNum) {
            Node(
                num = nodeNum,
                user = User(id = oldUserId, long_name = "Old", short_name = "OLD", hw_model = HardwareModel.TLORA_V2),
            )
        }
        nodeManager.updateNode(survivorNum) {
            Node(
                num = survivorNum,
                user =
                User(id = oldUserId, long_name = "Survivor", short_name = "SUR", hw_model = HardwareModel.TLORA_V2),
            )
        }

        // Change nodeNum's user ID
        nodeManager.updateNode(nodeNum) {
            it.copy(user = it.user.copy(id = newUserId, long_name = "New", short_name = "NEW"))
        }

        // byId for oldUserId should now point to survivorNum
        val survivor = nodeManager.getNodeById(oldUserId)
        assertNotNull(survivor)
        assertEquals(survivorNum, survivor!!.num)

        // byId for newUserId should point to nodeNum
        val newNode = nodeManager.getNodeById(newUserId)
        assertNotNull(newNode)
        assertEquals(nodeNum, newNode!!.num)
    }

    // 18. fromByNum insertion-order independence

    @Test
    fun `fromByNum is independent of insertion order`() {
        val userId = "!shared"
        val node1 = 1000
        val node2 = 2000

        val nodes12 =
            mapOf(
                node1 to
                    Node(
                        num = node1,
                        user = User(id = userId, long_name = "N1", short_name = "N1", hw_model = HardwareModel.UNSET),
                    ),
                node2 to
                    Node(
                        num = node2,
                        user = User(
                            id = userId,
                            long_name = "N2",
                            short_name = "N2",
                            hw_model = HardwareModel.TLORA_V2,
                        ),
                    ),
            )
        val nodes21 =
            mapOf(
                node2 to
                    Node(
                        num = node2,
                        user = User(
                            id = userId,
                            long_name = "N2",
                            short_name = "N2",
                            hw_model = HardwareModel.TLORA_V2,
                        ),
                    ),
                node1 to
                    Node(
                        num = node1,
                        user = User(id = userId, long_name = "N1", short_name = "N1", hw_model = HardwareModel.UNSET),
                    ),
            )

        val index12 = NodeManagerImpl.NodeIndex.fromByNum(nodes12)
        val index21 = NodeManagerImpl.NodeIndex.fromByNum(nodes21)

        // Both should produce the same byId representative (node2, non-placeholder)
        assertEquals(index12.byId[userId]!!.num, index21.byId[userId]!!.num)
        assertEquals(node2, index12.byId[userId]!!.num)
    }

    // 19. Preferred canonical mapping after stale removal

    @Test
    fun `preferred canonical remains mapped after stale removal`() {
        val userId = "!shared"
        val canonicalKey = ByteArray(32) { 41 }.toByteString()
        val competingKey = ByteArray(32) { 42 }.toByteString()
        val preferredNum = canonicalKey.canonicalNum()
        val competingNum = 500
        val staleNum = 1000

        nodeManager.updateNode(competingNum) {
            Node(
                num = competingNum,
                user =
                User(
                    id = userId,
                    long_name = "Fallback Winner",
                    short_name = "FBK",
                    hw_model = HardwareModel.TLORA_V2,
                    public_key = competingKey,
                ),
                publicKey = competingKey,
            )
        }
        nodeManager.updateNode(preferredNum) {
            Node(
                num = preferredNum,
                user =
                User(
                    id = userId,
                    long_name = "Preferred",
                    short_name = "PRE",
                    hw_model = HardwareModel.TLORA_V2,
                    public_key = canonicalKey,
                ),
                publicKey = canonicalKey,
            )
        }
        nodeManager.updateNode(staleNum) {
            Node(
                num = staleNum,
                user =
                User(
                    id = userId,
                    long_name = "Stale",
                    short_name = "STL",
                    hw_model = HardwareModel.TLORA_V2,
                    public_key = canonicalKey,
                ),
                publicKey = canonicalKey,
            )
        }

        nodeManager.handleReceivedUser(
            staleNum,
            User(
                id = userId,
                long_name = "Stale Replay",
                short_name = "STL",
                hw_model = HardwareModel.TLORA_V2,
                public_key = canonicalKey,
            ),
        )

        assertNull(nodeManager.nodeDBbyNodeNum[staleNum])
        assertNotNull(nodeManager.nodeDBbyNodeNum[competingNum])
        assertNotNull(nodeManager.nodeDBbyNodeNum[preferredNum])
        val representative = nodeManager.getNodeById(userId)
        assertNotNull(representative)
        assertEquals(preferredNum, representative!!.num)
    }

    // 20. Accepted local node remains the byId representative

    @Test
    fun `accepted local node remains byId representative`() {
        val userId = "!shared"
        val remoteNum = 1000
        val localNum = 2000

        nodeManager.setMyNodeNum(localNum)
        nodeManager.updateNode(localNum) {
            Node(
                num = localNum,
                user = User(id = userId, long_name = "Local", short_name = "LOC", hw_model = HardwareModel.TLORA_V2),
            )
        }
        nodeManager.updateNode(remoteNum) {
            Node(
                num = remoteNum,
                user = User(id = userId, long_name = "Remote", short_name = "REM", hw_model = HardwareModel.TLORA_V2),
            )
        }

        assertEquals(remoteNum, nodeManager.getNodeById(userId)!!.num)
        nodeManager.handleReceivedUser(
            localNum,
            User(id = userId, long_name = "Local Updated", short_name = "LOC", hw_model = HardwareModel.TLORA_V2),
        )

        assertNotNull(nodeManager.nodeDBbyNodeNum[remoteNum])
        val representative = nodeManager.getNodeById(userId)
        assertNotNull(representative)
        assertEquals(localNum, representative!!.num)
    }

    // 21. Exact notification title, message, ID, category, and deep link

    @Test
    fun `notification has exact title message ID category and deep link`() {
        enableDbWrites()
        val nodeNum = 1234
        val user = User(id = "!test", long_name = "Test User", short_name = "TST", hw_model = HardwareModel.TLORA_V2)

        nodeManager.handleReceivedUser(nodeNum, user)
        testScope.advanceUntilIdle()

        verify {
            notificationManager.dispatch(
                Notification(
                    title = "New node seen: TST",
                    message = "Test User",
                    category = Notification.Category.NodeEvent,
                    id = nodeNum,
                    deepLinkUri = "meshtastic://meshtastic/nodes/$nodeNum",
                ),
            )
        }
    }

    // 22. Incoming at the canonical num removes the noncanonical same-key entry and upserts incoming (no notify).

    @Test
    fun `incoming canonical removes noncanonical same-key entry and upserts incoming no notify`() {
        val canonicalKey = ByteArray(32) { (it + 7).toByte() }.toByteString()
        val canonicalNum = canonicalKey.canonicalNum()
        val staleNum = 7777 // arbitrary noncanonical num distinct from canonicalNum
        // Pre-existing entry at the noncanonical num carries the same key.
        nodeManager.updateNode(staleNum) { makeKnownNode(staleNum, canonicalKey, "Ghost") }
        // Placeholder at the canonical num so the incoming slot is "open" for reconciliation.
        val placeholderId = NodeAddress.numToDefaultId(canonicalNum)
        nodeManager.updateNode(canonicalNum) {
            Node(
                num = canonicalNum,
                user =
                User(
                    id = placeholderId,
                    long_name = "Meshtastic ${placeholderId.takeLast(4)}",
                    short_name = placeholderId.takeLast(4),
                    hw_model = HardwareModel.UNSET,
                ),
                publicKey = ByteString.EMPTY,
            )
        }
        enableDbWrites()

        nodeManager.handleReceivedUser(
            canonicalNum,
            User(
                id = "!canonical",
                long_name = "Canonical",
                short_name = "CAN",
                hw_model = HardwareModel.TLORA_V2,
                public_key = canonicalKey,
            ),
        )
        testScope.advanceUntilIdle()

        assertNull(nodeManager.nodeDBbyNodeNum[staleNum])
        val canonical = nodeManager.nodeDBbyNodeNum[canonicalNum]
        assertNotNull(canonical)
        assertEquals("Canonical", canonical!!.user.long_name)
        assertEquals(canonicalKey, canonical.publicKey)
        verifySuspend(mode = VerifyMode.exactly(1)) { nodeRepository.upsert(any()) }
        verify(mode = VerifyMode.not) { notificationManager.dispatch(any()) }
    }

    // 23. Incoming canonical absent from index entirely creates from packet, removes noncanonical, no notify.

    @Test
    fun `incoming canonical absent from index creates from packet removes noncanonical no notify`() {
        val canonicalKey = ByteArray(32) { (it + 17).toByte() }.toByteString()
        val canonicalNum = canonicalKey.canonicalNum()
        val staleNum = 8888 // arbitrary noncanonical num
        nodeManager.updateNode(staleNum) { makeKnownNode(staleNum, canonicalKey, "Stale Noncanonical") }
        enableDbWrites()

        nodeManager.handleReceivedUser(
            canonicalNum,
            User(
                id = "!fresh",
                long_name = "Fresh Canonical",
                short_name = "FRS",
                hw_model = HardwareModel.TLORA_V2,
                public_key = canonicalKey,
            ),
        )
        testScope.advanceUntilIdle()

        assertNull(nodeManager.nodeDBbyNodeNum[staleNum])
        val canonical = nodeManager.nodeDBbyNodeNum[canonicalNum]
        assertNotNull(canonical)
        assertEquals("Fresh Canonical", canonical!!.user.long_name)
        assertEquals(canonicalKey, canonical.publicKey)
        verifySuspend(mode = VerifyMode.exactly(1)) { nodeRepository.upsert(any()) }
        verify(mode = VerifyMode.not) { notificationManager.dispatch(any()) }
    }

    // 24. Neither num canonical preserves both with no side effects.

    @Test
    fun `neither num canonical preserves both no persistence no notification`() {
        val key = ByteArray(32) { (it + 23).toByte() }.toByteString()
        // Pick two nums guaranteed distinct from the canonical num and from each other.
        val canonicalNum = key.canonicalNum()
        val a = if (canonicalNum != 1111) 1111 else 1112
        val b = if (canonicalNum != 2222) 2222 else 2223

        nodeManager.updateNode(a) { makeKnownNode(a, key, "Alpha") }
        nodeManager.updateNode(b) { makeKnownNode(b, key, "Bravo") }
        enableDbWrites()

        nodeManager.handleReceivedUser(
            a,
            User(
                id = "!a",
                long_name = "Alpha Packet",
                short_name = "ALA",
                hw_model = HardwareModel.TLORA_V2,
                public_key = key,
            ),
        )
        testScope.advanceUntilIdle()

        // Both preserved with their original names; no persistence, no notification.
        val alpha = nodeManager.nodeDBbyNodeNum[a]
        val bravo = nodeManager.nodeDBbyNodeNum[b]
        assertNotNull(alpha)
        assertNotNull(bravo)
        assertEquals("Alpha", alpha!!.user.long_name)
        assertEquals("Bravo", bravo!!.user.long_name)
        verifySuspend(mode = VerifyMode.not) { nodeRepository.upsert(any()) }
        verify(mode = VerifyMode.not) { notificationManager.dispatch(any()) }
    }

    // 25. Different established valid key at the canonical num is preserved as a conflict.

    @Test
    fun `different established key at canonical num preserved as conflict`() {
        val canonicalKey = ByteArray(32) { (it + 31).toByte() }.toByteString()
        val establishedKey = ByteArray(32) { (it + 32).toByte() }.toByteString()
        val canonicalNum = canonicalKey.canonicalNum()
        val noncanonicalNum = 3333 // arbitrary; bears canonicalKey
        nodeManager.updateNode(noncanonicalNum) { makeKnownNode(noncanonicalNum, canonicalKey, "Noncanonical Holder") }
        // Canonical num is occupied by a node with a DIFFERENT valid key.
        nodeManager.updateNode(canonicalNum) { makeKnownNode(canonicalNum, establishedKey, "Established") }
        enableDbWrites()

        nodeManager.handleReceivedUser(
            canonicalNum,
            User(
                id = "!c",
                long_name = "Claimant",
                short_name = "CLM",
                hw_model = HardwareModel.TLORA_V2,
                public_key = canonicalKey,
            ),
        )
        testScope.advanceUntilIdle()

        // Conflict: both preserved with their original keys; no persistence, no notification.
        val atCanonical = nodeManager.nodeDBbyNodeNum[canonicalNum]
        val atNoncanonical = nodeManager.nodeDBbyNodeNum[noncanonicalNum]
        assertNotNull(atCanonical)
        assertNotNull(atNoncanonical)
        assertEquals(establishedKey, atCanonical!!.publicKey)
        assertEquals("Established", atCanonical.user.long_name)
        assertEquals(canonicalKey, atNoncanonical!!.publicKey)
        verifySuspend(mode = VerifyMode.not) { nodeRepository.upsert(any()) }
        verify(mode = VerifyMode.not) { notificationManager.dispatch(any()) }
    }

    private fun Int.toHex(): String = this.toString(16).padStart(8, '0')
}
