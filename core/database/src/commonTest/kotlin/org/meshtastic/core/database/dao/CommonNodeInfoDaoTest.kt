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
package org.meshtastic.core.database.dao

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.database.MeshtasticDatabase
import org.meshtastic.core.database.entity.DeviceMetadata
import org.meshtastic.core.database.entity.MetadataEntity
import org.meshtastic.core.database.entity.MyNodeEntity
import org.meshtastic.core.database.entity.NodeEntity
import org.meshtastic.core.database.getInMemoryDatabaseBuilder
import org.meshtastic.core.testing.setupTestContext
import org.meshtastic.proto.HardwareModel
import org.meshtastic.proto.User
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

abstract class CommonNodeInfoDaoTest {
    private lateinit var database: MeshtasticDatabase
    private lateinit var dao: NodeInfoDao

    private val myNodeInfo: MyNodeEntity =
        MyNodeEntity(
            myNodeNum = 42424242,
            model = "TBEAM",
            firmwareVersion = "2.5.0",
            couldUpdate = false,
            shouldUpdate = false,
            currentPacketId = 1L,
            messageTimeoutMsec = 300000,
            minAppVersion = 1,
            maxChannels = 8,
            hasWifi = false,
        )

    suspend fun createDb() {
        setupTestContext()
        database = getInMemoryDatabaseBuilder().build()
        dao = database.nodeInfoDao()
        dao.setMyNodeInfo(myNodeInfo)
    }

    @AfterTest
    fun closeDb() {
        database.close()
    }

    @Test
    fun testGetMyNodeInfo() = runTest {
        createDb()
        val info = dao.getMyNodeInfo().first()
        assertNotNull(info)
        assertEquals(myNodeInfo.myNodeNum, info.myNodeNum)
    }

    @Test
    fun testUpsertNode() = runTest {
        createDb()
        val node =
            NodeEntity(
                num = 1234,
                user = User(long_name = "Test Node", id = "!test", hw_model = org.meshtastic.proto.HardwareModel.TBEAM),
                lastHeard = (nowMillis / 1000).toInt(),
            )
        dao.upsert(node)
        val result = dao.getNodeByNum(1234)
        assertNotNull(result)
        assertEquals("Test Node", result.node.longName)
    }

    @Test
    fun testNodeDBbyNum() = runTest {
        createDb()
        val node1 = NodeEntity(num = 1, user = User(id = "!1"))
        val node2 = NodeEntity(num = 2, user = User(id = "!2"))
        dao.putAll(listOf(node1, node2))

        val nodes = dao.nodeDBbyNum().first()
        assertEquals(2, nodes.size)
        assertTrue(nodes.containsKey(1))
        assertTrue(nodes.containsKey(2))
    }

    @Test
    fun testDeleteNode() = runTest {
        createDb()
        val node = NodeEntity(num = 1, user = User(id = "!1"))
        dao.upsert(node)
        dao.deleteNode(1)
        val result = dao.getNodeByNum(1)
        assertEquals(null, result)
    }

    @Test
    fun testClearNodeInfo() = runTest {
        createDb()
        val node1 = NodeEntity(num = 1, user = User(id = "!1"), isFavorite = true)
        val node2 = NodeEntity(num = 2, user = User(id = "!2"), isFavorite = false)
        dao.putAll(listOf(node1, node2))

        dao.clearNodeInfo(preserveFavorites = true)
        val nodes = dao.nodeDBbyNum().first()
        assertEquals(1, nodes.size)
        assertTrue(nodes.containsKey(1))
    }

    // ── Local-node-key-rotation migration (firmware 2.7→2.8) ───────────────

    /**
     * Builds a 32-byte [okio.ByteString] distinct per [seed] so tests can give different nodes different keys (or share
     * a key) without accidental collisions.
     */
    private fun fullPublicKey(seed: Byte) = ByteArray(NodeInfoDao.KEY_SIZE) { seed }.toByteString()

    private fun myNodeEntityFor(num: Int) = MyNodeEntity(
        myNodeNum = num,
        model = "TBEAM",
        firmwareVersion = "2.8.0",
        couldUpdate = false,
        shouldUpdate = false,
        currentPacketId = 1L,
        messageTimeoutMsec = 300000,
        minAppVersion = 1,
        maxChannels = 8,
        hasWifi = false,
    )

    private fun localIncomingNode(num: Int, publicKey: okio.ByteString) = NodeEntity(
        num = num,
        user =
        User(
            id = "!local",
            long_name = "Local Node",
            short_name = "LOC",
            hw_model = HardwareModel.TBEAM,
            public_key = publicKey,
        ),
        lastHeard = (nowMillis / 1000).toInt(),
    )

    /**
     * Migration scenario: firmware 2.7→2.8 transition reports the same physical local node with a new node number, the
     * same 32-byte public key, and an existing row under the old number. installConfig must migrate the row to the new
     * number, preserve app-local fields, move metadata, retain unrelated remote rows, and store the new MyNodeInfo.
     */
    @Test
    fun testLocalNodeKeyRotationMigratesRowAndPreservesAppLocalFields() = runTest {
        createDb()

        val oldNum = 42424242
        val newNum = 42424243
        val sharedKey = fullPublicKey(seed = 0x42)

        // Old local row under oldNum carrying app-local fields the firmware cannot repopulate.
        // publicKey is set explicitly because putAll bypasses the validation path that would otherwise
        // copy user.public_key into the denormalized public_key column.
        val oldRow =
            NodeEntity(
                num = oldNum,
                user =
                User(
                    id = "!old",
                    long_name = "Old Local",
                    short_name = "OLD",
                    hw_model = HardwareModel.TBEAM,
                    public_key = sharedKey,
                ),
                publicKey = sharedKey,
                longName = "Old Local",
                shortName = "OLD",
                isFavorite = true,
                notes = "field-notes",
                powerChannelLabels = listOf("ch0", "ch1"),
                manuallyVerified = true,
                isIgnored = false,
                isMuted = false,
            )
        dao.putAll(listOf(oldRow))
        dao.upsert(MetadataEntity(num = oldNum, proto = DeviceMetadata(firmware_version = "2.7.0"), timestamp = 1L))

        // Unrelated remote row that must be retained verbatim.
        val remoteNum = 555
        val remoteNode =
            NodeEntity(
                num = remoteNum,
                user = User(id = "!remote", long_name = "Remote", short_name = "REM", hw_model = HardwareModel.TBEAM),
            )
        dao.putAll(listOf(remoteNode))

        val newMi = myNodeEntityFor(newNum)
        val incomingLocal = localIncomingNode(newNum, sharedKey).copy(isIgnored = true, isMuted = true)
        dao.installConfig(newMi, listOf(incomingLocal, remoteNode))

        // Old row removed, new row inserted under newNum.
        assertNull(dao.getNodeByNum(oldNum), "Old local row must be removed")
        val migrated = dao.getNodeByNum(newNum)
        assertNotNull(migrated, "New local row must be inserted under the new node number")
        assertEquals("Local Node", migrated.node.longName)
        assertEquals(sharedKey, migrated.node.publicKey)

        // App-local fields preserved from the old row.
        assertTrue(migrated.node.isFavorite, "isFavorite must be preserved from the old row")
        assertEquals("field-notes", migrated.node.notes, "notes must be preserved from the old row")
        assertEquals(listOf("ch0", "ch1"), migrated.node.powerChannelLabels, "powerChannelLabels must be preserved")
        assertTrue(migrated.node.manuallyVerified, "manuallyVerified must be preserved from the old row")

        // Incoming values are authoritative for current firmware/network state including ignored/muted.
        assertTrue(migrated.node.isIgnored, "Incoming isIgnored value must be authoritative")
        assertTrue(migrated.node.isMuted, "Incoming isMuted value must be authoritative")

        // Metadata moved from old to new number.
        assertNull(dao.getMetadataByNum(oldNum), "Old metadata must be removed")
        val movedMeta = dao.getMetadataByNum(newNum)
        assertNotNull(movedMeta, "Metadata must be re-keyed to the new node number")
        assertEquals("2.7.0", movedMeta.proto.firmware_version)

        // Unrelated remote retained.
        val retainedRemote = dao.getNodeByNum(remoteNum)
        assertNotNull(retainedRemote, "Unrelated remote node must be retained")
        assertEquals("Remote", retainedRemote.node.longName)

        // MyNodeInfo now names the new number.
        assertEquals(newNum, dao.getMyNodeInfo().first()?.myNodeNum)
    }

    /** Migration also repairs an already-broken DB where MyNodeInfo was already advanced to the new number. */
    @Test
    fun testLocalNodeKeyRotationRepairsAlreadyAdvancedMyNodeInfo() = runTest {
        createDb()

        val oldNum = 42424242
        val newNum = 99999999
        val sharedKey = fullPublicKey(seed = 0x11)

        dao.putAll(
            listOf(
                NodeEntity(
                    num = oldNum,
                    user =
                    User(
                        id = "!old",
                        long_name = "Old",
                        short_name = "OLD",
                        hw_model = HardwareModel.TBEAM,
                        public_key = sharedKey,
                    ),
                    publicKey = sharedKey,
                    longName = "Old",
                    shortName = "OLD",
                    isFavorite = true,
                    manuallyVerified = true,
                ),
            ),
        )
        // Simulate the broken state: MyNodeInfo already advanced to the new number while the node table
        // still contains only the old public-key-matching row.
        dao.clearMyNodeInfo()
        dao.setMyNodeInfo(myNodeEntityFor(newNum))

        val newMi = myNodeEntityFor(newNum)
        dao.installConfig(newMi, listOf(localIncomingNode(newNum, sharedKey)))

        assertNull(dao.getNodeByNum(oldNum), "Old row must still be removed when repairing the broken DB state")
        val migrated = dao.getNodeByNum(newNum)
        assertNotNull(migrated, "New local row must be inserted even when MyNodeInfo was already advanced")
        assertTrue(migrated.node.isFavorite, "App-local fields must still be preserved during repair")
        assertTrue(migrated.node.manuallyVerified, "App-local fields must still be preserved during repair")
        assertEquals(newNum, dao.getMyNodeInfo().first()?.myNodeNum)
    }

    /**
     * Duplicate public keys on non-local nodes must keep using the existing security behavior — the incoming local node
     * with its own distinct key is migrated (or inserted) without being mistaken for either conflicting remote, and the
     * conflict-protection path runs normally on the remote batch.
     */
    @Test
    fun testDuplicatePublicKeysOnRemoteNodesDoesNotTriggerLocalMigration() = runTest {
        createDb()

        val localNum = 42424242
        val localKey = fullPublicKey(seed = 0x01)
        val sharedRemoteKey = fullPublicKey(seed = 0x02)

        // Two existing REMOTE rows that share a public key — pure duplicate-key remote conflict, NOT a
        // local-node rotation. The local-node migration conditions must not match: the incoming local
        // node's key (localKey) does not collide with any row.
        dao.putAll(
            listOf(
                NodeEntity(
                    num = 700,
                    user = User(id = "!r1", long_name = "R1", short_name = "R1", public_key = sharedRemoteKey),
                    publicKey = sharedRemoteKey,
                    longName = "R1",
                    shortName = "R1",
                ),
                NodeEntity(
                    num = 701,
                    user = User(id = "!r2", long_name = "R2", short_name = "R2", public_key = sharedRemoteKey),
                    publicKey = sharedRemoteKey,
                    longName = "R2",
                    shortName = "R2",
                ),
            ),
        )

        val incomingLocal = localIncomingNode(localNum, localKey)
        dao.installConfig(myNodeEntityFor(localNum), listOf(incomingLocal))

        // Local row inserted under its own number with its own key.
        val localRow = dao.getNodeByNum(localNum)
        assertNotNull(localRow, "Local node must be inserted under its own number")
        assertEquals(localKey, localRow.node.publicKey)

        // Both conflicting remote rows remain (existing security behavior: do not silently drop either).
        assertNotNull(dao.getNodeByNum(700), "Remote conflict row 700 must be retained by existing behavior")
        assertNotNull(dao.getNodeByNum(701), "Remote conflict row 701 must be retained by existing behavior")
    }

    /**
     * Ambiguous duplicate-key matches (more than one existing row sharing the incoming local key) must NOT trigger the
     * migration — falling back to the existing batch validation behavior so we never guess which row to migrate from.
     */
    @Test
    fun testAmbiguousDuplicateKeyMatchesDoNotTriggerLocalMigration() = runTest {
        createDb()

        val newLocalNum = 12345
        val sharedKey = fullPublicKey(seed = 0x33)

        // Two pre-existing rows share the SAME public key that the incoming local will report — the
        // migration must refuse the ambiguous case (size != 1).
        dao.putAll(
            listOf(
                NodeEntity(
                    num = 800,
                    user = User(id = "!a", long_name = "A", short_name = "A", public_key = sharedKey),
                    publicKey = sharedKey,
                    longName = "A",
                    shortName = "A",
                ),
                NodeEntity(
                    num = 801,
                    user = User(id = "!b", long_name = "B", short_name = "B", public_key = sharedKey),
                    publicKey = sharedKey,
                    longName = "B",
                    shortName = "B",
                ),
            ),
        )

        val incomingLocal = localIncomingNode(newLocalNum, sharedKey)
        dao.installConfig(myNodeEntityFor(newLocalNum), listOf(incomingLocal))

        // Migration did NOT run: no row under the new number was inserted; existing rows untouched.
        assertNull(dao.getNodeByNum(newLocalNum), "Ambiguous case must not insert a row under the new number")
        assertNotNull(dao.getNodeByNum(800), "Existing row 800 must be retained (no migration)")
        assertNotNull(dao.getNodeByNum(801), "Existing row 801 must be retained (no migration)")
    }
}
