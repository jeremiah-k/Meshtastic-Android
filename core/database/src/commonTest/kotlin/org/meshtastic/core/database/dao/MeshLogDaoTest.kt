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
import org.meshtastic.core.database.MeshtasticDatabase
import org.meshtastic.core.database.entity.MeshLog
import org.meshtastic.core.database.getInMemoryDatabaseBuilder
import org.meshtastic.proto.PortNum
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MeshLogDaoTest {
    private lateinit var database: MeshtasticDatabase
    private lateinit var meshLogDao: MeshLogDao

    private val testFromNum = 42

    private fun logEntry(uuid: String, portNum: Int = PortNum.TELEMETRY_APP.value, time: Long) = MeshLog(
        uuid = uuid,
        message_type = "Packet",
        received_date = time,
        raw_message = "",
        fromNum = testFromNum,
        portNum = portNum,
    )

    @BeforeTest
    fun setUp() {
        database = getInMemoryDatabaseBuilder().build()
        meshLogDao = database.meshLogDao()
    }

    @AfterTest
    fun closeDb() {
        database.close()
    }

    @Test
    fun testDeleteLogsByUuidAtomicRemovesAll() = runTest {
        meshLogDao.insert(logEntry("log-1", time = 100))
        meshLogDao.insert(logEntry("log-2", time = 200))
        meshLogDao.insert(logEntry("log-3", time = 300))

        val uuids =
            meshLogDao.getLogsFrom(testFromNum, PortNum.TELEMETRY_APP.value, Int.MAX_VALUE).first().map { it.uuid }
        assertEquals(3, uuids.size)

        meshLogDao.deleteLogsByUuidAtomic(uuids)

        val remaining = meshLogDao.getLogsFrom(testFromNum, PortNum.TELEMETRY_APP.value, Int.MAX_VALUE).first()
        assertTrue(remaining.isEmpty())
    }

    @Test
    fun testDeleteLogsByUuidAtomicEmptyListIsNoOp() = runTest {
        meshLogDao.insert(logEntry("log-1", time = 100))
        meshLogDao.insert(logEntry("log-2", time = 200))

        val before = meshLogDao.getLogsFrom(testFromNum, PortNum.TELEMETRY_APP.value, Int.MAX_VALUE).first()
        assertEquals(2, before.size)

        meshLogDao.deleteLogsByUuidAtomic(emptyList())

        val after = meshLogDao.getLogsFrom(testFromNum, PortNum.TELEMETRY_APP.value, Int.MAX_VALUE).first()
        assertEquals(2, after.size)
    }

    @Test
    fun testDeleteLogsByUuidAtomicCrossesChunkBoundary() = runTest {
        // 1200 UUIDs exceeds SQLITE_MAX_BIND_PARAMETERS (999), forcing at least 2 chunks inside the
        // @Transaction. All must be deleted in one atomic batch; a partial commit would leave rows behind.
        val total = 1200
        for (i in 1..total) {
            meshLogDao.insert(logEntry("bulk-$i", time = i.toLong()))
        }
        // Insert one log from a different fromNum that should survive the delete.
        val survivor =
            MeshLog(
                uuid = "survivor",
                message_type = "Packet",
                received_date = 0L,
                raw_message = "",
                fromNum = 999,
                portNum = PortNum.TELEMETRY_APP.value,
            )
        meshLogDao.insert(survivor)

        val uuids =
            meshLogDao.getLogsFrom(testFromNum, PortNum.TELEMETRY_APP.value, Int.MAX_VALUE).first().map { it.uuid }
        assertEquals(total, uuids.size, "all bulk logs should be queryable before delete")

        meshLogDao.deleteLogsByUuidAtomic(uuids)

        val remaining = meshLogDao.getLogsFrom(testFromNum, PortNum.TELEMETRY_APP.value, Int.MAX_VALUE).first()
        assertTrue(remaining.isEmpty(), "all selected logs should be deleted across the chunk boundary")

        val survivorStillPresent = meshLogDao.getLogsFrom(999, PortNum.TELEMETRY_APP.value, Int.MAX_VALUE).first()
        assertEquals(1, survivorStillPresent.size, "unselected log from a different fromNum should remain")
    }

    @Test
    fun testGetLogsSnapshotReturnsMatchingLogs() = runTest {
        meshLogDao.insert(logEntry("log-1", portNum = PortNum.TELEMETRY_APP.value, time = 300))
        meshLogDao.insert(logEntry("log-2", portNum = PortNum.TELEMETRY_APP.value, time = 100))
        meshLogDao.insert(logEntry("log-3", portNum = PortNum.POSITION_APP.value, time = 200))

        val snapshot = meshLogDao.getLogsSnapshot(testFromNum, PortNum.TELEMETRY_APP.value, Int.MAX_VALUE)
        assertEquals(2, snapshot.size, "only telemetry logs should match")
        assertEquals(listOf("log-1", "log-2"), snapshot.map { it.uuid }, "telemetry logs in DESC order")
    }
}
