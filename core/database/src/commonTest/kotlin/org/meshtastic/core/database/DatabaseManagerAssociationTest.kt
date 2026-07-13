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
package org.meshtastic.core.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import org.meshtastic.core.database.entity.MyNodeEntity
import org.meshtastic.core.di.CoroutineDispatchers
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Verifies the merge association state machine in [DatabaseManager]: writer-drain timeout restores the source DB so the
 * merge retries on the next connect, cancellation during the drain is cleaned up without leaking waiters, and a
 * successful merge persists all routing metadata (device claim, node claim, address alias) in one atomic DataStore
 * edit.
 *
 * Uses [UnconfinedTestDispatcher] so background coroutines launched by [DatabaseManager] run eagerly, and
 * [advanceTimeBy] to drive the [DatabaseManager.WRITER_DRAIN_TIMEOUT_MS] timeout deterministically.
 */
class DatabaseManagerAssociationTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var dataStoreScope: CoroutineScope

    private lateinit var tmpDir: Path
    private lateinit var armableDs: ArmableDataStore
    private lateinit var dispatchers: CoroutineDispatchers
    private lateinit var manager: TestDatabaseManager

    @BeforeTest
    fun setUp() {
        tmpDir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "dbManagerAssocTest-${Uuid.random()}"
        FileSystem.SYSTEM.createDirectories(tmpDir)
        dataStoreScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val realDatastore =
            PreferenceDataStoreFactory.createWithPath(
                scope = dataStoreScope,
                produceFile = { tmpDir / "test.preferences_pb" },
            )
        armableDs = ArmableDataStore(realDatastore)
        dispatchers = CoroutineDispatchers(testDispatcher, testDispatcher, testDispatcher)
        manager = TestDatabaseManager(armableDs, dispatchers)
    }

    @AfterTest
    fun tearDown() = runTest(testDispatcher) {
        manager.close()
        dataStoreScope.cancel()
        runCurrent()
        FileSystem.SYSTEM.deleteRecursively(tmpDir)
    }

    /**
     * Overrides [buildDatabase] to create distinct in-memory DBs per [dbName]. Each call to
     * [getInMemoryDatabaseBuilder] returns a new Room instance with its own connection, so
     * [dbCache][DatabaseManager.dbCache] entries for different names are genuinely separate databases.
     */
    private class TestDatabaseManager(
        datastore: DataStore<Preferences>,
        private val testDispatchers: CoroutineDispatchers,
    ) : DatabaseManager(datastore, testDispatchers) {
        override fun buildDatabase(dbName: String): MeshtasticDatabase = getInMemoryDatabaseBuilder().build()

        override val limitedIo: kotlinx.coroutines.CoroutineDispatcher
            get() = testDispatchers.default

        /**
         * No-op: in-memory test databases have no filesystem directory, so LRU eviction, legacy-DB cleanup, and FTS
         * search-index backfill would crash on Android host tests trying to access platform singletons.
         */
        override fun schedulePostSwitchMaintenance(dbName: String, db: MeshtasticDatabase) = Unit

        /** When non-null, [mergeDatabases] throws this before the merge commits, simulating a pre-commit failure. */
        var failMergeWith: Exception? = null

        override suspend fun mergeDatabases(source: MeshtasticDatabase, dest: MeshtasticDatabase, sourceName: String) {
            failMergeWith?.let { throw it }
            super.mergeDatabases(source, dest, sourceName)
        }
    }

    /** Builds a minimal [MyNodeEntity] carrying only [num] for association/merge write-leak checks. */
    private fun myNode(num: Int) = MyNodeEntity(
        myNodeNum = num,
        model = null,
        firmwareVersion = null,
        couldUpdate = false,
        shouldUpdate = false,
        currentPacketId = 0L,
        messageTimeoutMsec = 0,
        minAppVersion = 0,
        maxChannels = 0,
        hasWifi = false,
    )

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /** Hex-encoded [deviceId] as [deviceDbPrefKey] would produce it. */
    private fun deviceKeyHex(deviceId: String): String = deviceId.encodeUtf8().hex()

    /**
     * Sets up two switched databases (addrA claimed as canonical, addrB active) and returns the two DB instances plus
     * the claimed canonical DB name.
     */
    private suspend fun setupTwoDatabases(): Pair<MeshtasticDatabase, MeshtasticDatabase> {
        manager.switchActiveDatabase("addrA")
        manager.associateDevice(nodeNum = 123, deviceId = "deadbeefdeadbeef")
        val dbA = manager.currentDb.value

        manager.switchActiveDatabase("addrB")
        val dbB = manager.currentDb.value

        return dbA to dbB
    }

    // ── Tests ────────────────────────────────────────────────────────────────────

    /**
     * 1 + 2 combined: A writer holding the source DB's withDb lane causes the drain to time out, aborting the merge and
     * restoring the source. After releasing the writer, a retry succeeds — the merge commits, dest becomes canonical,
     * and the active DB switches.
     */
    @Test
    fun drainTimeoutRestoresSourceAndRetrySucceeds() = runTest(testDispatcher) {
        val (dbA, dbB) = setupTwoDatabases()
        assertEquals(dbB, manager.currentDb.value, "addrB's DB should be active before association")

        // Hold a withDb writer on addrB's DB so drainWriters can't complete.
        val gate = CompletableDeferred<Unit>()
        val writerJob = launch {
            manager.withDb {
                gate.await()
                null
            }
        }

        // associateDevice should reach the merge branch, publish dest, then time out on the drain.
        val associateJob = launch { manager.associateDevice(nodeNum = 123, deviceId = "deadbeefdeadbeef") }

        // Advance virtual time past WRITER_DRAIN_TIMEOUT_MS (5_000ms) to trigger the drain timeout.
        advanceTimeBy(5_501)
        assertTrue(associateJob.isCompleted, "associateDevice should complete after drain timeout")
        assertEquals(dbB, manager.currentDb.value, "source (addrB) should be restored after drain timeout")

        // Release the writer gate so the source DB quiesces.
        gate.complete(Unit)
        writerJob.join()

        // Retry: with no writer in-flight, the drain succeeds and the merge commits.
        manager.associateDevice(nodeNum = 123, deviceId = "deadbeefdeadbeef")

        assertEquals(
            dbA,
            manager.currentDb.value,
            "dest (addrA's canonical DB) should be active after successful merge",
        )
    }

    /**
     * 3: Cancelling [associateDevice] during the writer-drain wait propagates [CancellationException], restores the
     * source DB, and does not leak the drain waiter — a subsequent retry succeeds.
     */
    @Test
    fun cancellationDuringDrainRestoresSource() = runTest(testDispatcher) {
        val (dbA, dbB) = setupTwoDatabases()

        val gate = CompletableDeferred<Unit>()
        val writerJob = launch {
            manager.withDb {
                gate.await()
                null
            }
        }

        val associateJob = launch { manager.associateDevice(nodeNum = 123, deviceId = "deadbeefdeadbeef") }

        // Cancel while suspended in drainWriters (before the 5s timeout fires).
        associateJob.cancelAndJoin()

        assertTrue(associateJob.isCancelled, "associateDevice coroutine should be cancelled")
        assertEquals(
            dbB,
            manager.currentDb.value,
            "source should be restored after cancellation (NonCancellable publishActiveDb)",
        )

        // Release the writer and retry — if the drain waiter leaked, the retry would also stall.
        gate.complete(Unit)
        writerJob.join()

        manager.associateDevice(nodeNum = 123, deviceId = "deadbeefdeadbeef")

        assertEquals(
            dbA,
            manager.currentDb.value,
            "retry should succeed after cancellation cleanup — dest is canonical",
        )
    }

    /**
     * 4: A successful association persists device, node, and address routing metadata in one atomic DataStore edit, all
     * pointing to the same canonical DB.
     */
    @Test
    fun atomicRoutingMetadataAllPointToCanonical() = runTest(testDispatcher) {
        manager.switchActiveDatabase("addrA")
        manager.associateDevice(nodeNum = 123, deviceId = "deadbeefdeadbeef")
        manager.switchActiveDatabase("addrB")

        // No writer is held, so the drain succeeds immediately and the merge commits.
        manager.associateDevice(nodeNum = 123, deviceId = "deadbeefdeadbeef")

        val prefs = armableDs.data.first()
        val deviceClaim = prefs[stringPreferencesKey("device_db_for:${deviceKeyHex("deadbeefdeadbeef")}")]
        val nodeClaim = prefs[stringPreferencesKey("node_db_for:123")]
        val addrClaim = prefs[stringPreferencesKey("addr_db_for:ADDRB")]

        assertNotNull(deviceClaim, "device-id claim should be persisted")
        assertNotNull(nodeClaim, "node-num claim should be persisted")
        assertNotNull(addrClaim, "address alias should be persisted")
        assertEquals(deviceClaim, nodeClaim, "device and node claims point to the same DB")
        assertEquals(deviceClaim, addrClaim, "address alias points to the same canonical DB")
    }

    // ── Post-merge cancellation and routing-recovery tests ─────────────────────

    /**
     * Armable DataStore wrapper that can inject faults deterministically.
     *
     * Modes:
     * - [Mode.Normal]: delegates normally.
     * - [Mode.FailBeforeCommit]: throws [exception] before delegating, then resets to Normal.
     * - [Mode.FailAfterCommit]: delegates first (durably commits), then throws [exception], then resets to Normal.
     *
     * Each fault fires once; subsequent edits succeed normally. This avoids fragile edit-count assumptions.
     */
    private class ArmableDataStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        override val data
            get() = delegate.data

        sealed interface Mode {
            data object Normal : Mode

            data class FailBeforeCommit(val exception: Exception) : Mode

            data class FailAfterCommit(val exception: Exception) : Mode
        }

        var mode: Mode = Mode.Normal
        private var faultFired = false

        @Suppress("TooGenericExceptionThrown")
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            when (val m = mode) {
                is Mode.Normal -> delegate.updateData(transform)

                is Mode.FailBeforeCommit -> {
                    if (!faultFired) {
                        faultFired = true
                        mode = Mode.Normal
                        throw m.exception
                    }
                    delegate.updateData(transform)
                }

                is Mode.FailAfterCommit -> {
                    val result = delegate.updateData(transform)
                    if (!faultFired) {
                        faultFired = true
                        mode = Mode.Normal
                        throw m.exception
                    }
                    result
                }
            }

        fun reset() {
            mode = Mode.Normal
            faultFired = false
        }

        fun armFailBeforeCommit(exception: Exception) {
            mode = Mode.FailBeforeCommit(exception)
            faultFired = false
        }

        fun armFailAfterCommit(exception: Exception) {
            mode = Mode.FailAfterCommit(exception)
            faultFired = false
        }
    }

    /**
     * Commit-then-cancel: the routing DataStore edit commits durably, then the wrapper throws CancellationException.
     * Destination must remain active, all routing metadata must agree, and cancellation must propagate.
     */
    @Test
    fun cancellationAfterMergeCommitKeepsDestination() = runTest(testDispatcher) {
        val (dbA, dbB) = setupTwoDatabases()
        assertEquals(dbB, manager.currentDb.value, "addrB should be active before association")

        armableDs.armFailAfterCommit(CancellationException("post-merge cancel"))

        var caught: CancellationException? = null
        try {
            manager.associateDevice(nodeNum = 123, deviceId = "deadbeefdeadbeef")
        } catch (e: CancellationException) {
            caught = e
        }
        assertNotNull(caught, "CancellationException should propagate after merge commit")
        assertEquals(dbA, manager.currentDb.value, "destination must remain active — source is NOT restored")

        // Routing metadata was durably committed before the wrapper threw. All claims must agree.
        val prefs = armableDs.data.first()
        val deviceClaim = prefs[stringPreferencesKey("device_db_for:${deviceKeyHex("deadbeefdeadbeef")}")]
        val nodeClaim = prefs[stringPreferencesKey("node_db_for:123")]
        val addrClaim = prefs[stringPreferencesKey("addr_db_for:ADDRB")]
        assertNotNull(deviceClaim, "device claim committed")
        assertNotNull(nodeClaim, "node claim committed")
        assertNotNull(addrClaim, "address alias committed")
        assertEquals(deviceClaim, nodeClaim, "device and node claims agree")
        assertEquals(deviceClaim, addrClaim, "address alias agrees with claims")
    }

    /**
     * DataStore failure after merge: routing edit never commits. Destination stays active (marker exists), source is
     * not retired. Before retry, address alias is absent. After retry, all routing metadata agrees.
     */
    @Test
    fun dataStoreFailureAfterMergeKeepsDestinationAndRepairsOnRetry() = runTest(testDispatcher) {
        val (dbA, dbB) = setupTwoDatabases()

        armableDs.armFailBeforeCommit(RuntimeException("simulated DataStore failure"))

        manager.associateDevice(nodeNum = 123, deviceId = "deadbeefdeadbeef")
        assertEquals(dbA, manager.currentDb.value, "dest must remain active — source is NOT restored")

        // Before retry: routing metadata was NOT persisted (FailBeforeCommit prevented the edit).
        val prefsBefore = armableDs.data.first()
        assertNull(
            prefsBefore[stringPreferencesKey("addr_db_for:ADDRB")],
            "address alias must be absent before repair — routing edit failed",
        )

        // Retry repairs routing metadata via the already-unified branch.
        manager.associateDevice(nodeNum = 123, deviceId = "deadbeefdeadbeef")

        val prefsAfter = armableDs.data.first()
        val deviceClaim = prefsAfter[stringPreferencesKey("device_db_for:${deviceKeyHex("deadbeefdeadbeef")}")]
        val nodeClaim = prefsAfter[stringPreferencesKey("node_db_for:123")]
        val addrClaim = prefsAfter[stringPreferencesKey("addr_db_for:ADDRB")]
        assertNotNull(deviceClaim, "device claim repaired")
        assertNotNull(nodeClaim, "node claim repaired")
        assertNotNull(addrClaim, "address alias repaired")
        assertEquals(deviceClaim, nodeClaim, "device and node claims agree after repair")
        assertEquals(deviceClaim, addrClaim, "address alias agrees after repair")
    }

    /**
     * After a post-merge routing failure, new writes land in destination (not source), proving source is never
     * reactivated past the merge-commit boundary.
     */
    @Test
    fun postMergeWriteLandsInDestination() = runTest(testDispatcher) {
        val (dbA, dbB) = setupTwoDatabases()

        armableDs.armFailBeforeCommit(RuntimeException("simulated DataStore failure"))
        manager.associateDevice(nodeNum = 123, deviceId = "deadbeefdeadbeef")

        val destDb = manager.currentDb.value
        assertEquals(dbA, destDb, "destination is dbA (the canonical DB)")

        manager.withDb {
            it.nodeInfoDao()
                .setMyNodeInfo(
                    MyNodeEntity(
                        myNodeNum = 999,
                        model = null,
                        firmwareVersion = null,
                        couldUpdate = false,
                        shouldUpdate = false,
                        currentPacketId = 0L,
                        messageTimeoutMsec = 0,
                        minAppVersion = 0,
                        maxChannels = 0,
                        hasWifi = false,
                    ),
                )
        }

        // The write exists in destination.
        val destMyNode = destDb.nodeInfoDao().getMyNodeInfo().first()
        assertNotNull(destMyNode, "post-merge write landed in destination")
        assertEquals(999, destMyNode?.myNodeNum, "write is durable in destination")

        // The write does NOT exist in source.
        val sourceMyNode = dbB.nodeInfoDao().getMyNodeInfo().first()
        assertNull(sourceMyNode, "source must not have the post-merge write")
    }

    // ── Writer-admission gate tests ─────────────────────────────────────────────
    //
    // These exercise the admission barrier added so a new `withDb` writer that arrives during an association attempt
    // never captures a DB that is being retired (source) or one that does not yet exist (dest). Source stays canonical
    // until the merge commits; the gate blocks the writer and releases it onto `source` on abort or `dest` on commit.

    /**
     * A writer that arrives while the drain is in progress is blocked at the admission gate — its block has not run —
     * and is released onto destination once the merge commits.
     */
    @Test
    fun writerArrivingDuringDrainIsBlockedAndReleasedToDestOnCommit() = runTest(testDispatcher) {
        val (dbA, dbB) = setupTwoDatabases()

        // Hold a pre-existing source writer so the drain stays pending and a new writer can arrive into the gate.
        val preGate = CompletableDeferred<Unit>()
        val preWriter = launch {
            manager.withDb {
                preGate.await()
                null
            }
        }
        val associateJob = launch { manager.associateDevice(nodeNum = 123, deviceId = "deadbeefdeadbeef") }

        val newWriterStarted = CompletableDeferred<Unit>()
        val newWriterDb = CompletableDeferred<MeshtasticDatabase>()
        val newWriterJob = launch {
            manager.withDb { db ->
                newWriterStarted.complete(Unit)
                newWriterDb.complete(db)
                null
            }
        }

        // The new writer must be suspended at the admission gate, not running its block.
        assertFalse(newWriterStarted.isCompleted, "new writer must block at the admission gate during the drain")
        assertFalse(newWriterDb.isCompleted, "new writer must not have captured a DB yet")

        // Release the pre-existing writer: drain completes, merge commits, gate releases onto dest.
        preGate.complete(Unit)
        newWriterJob.join()
        associateJob.join()
        preWriter.join()

        assertTrue(newWriterStarted.isCompleted, "new writer must be released after the merge commits")
        assertEquals(dbA, newWriterDb.getCompleted(), "new writer released onto destination after successful merge")
        assertEquals(dbA, manager.currentDb.value, "destination is canonical after commit")
    }

    /** A successful merge releases the blocked writer onto destination (not source). */
    @Test
    fun successfulMergeReleasesBlockedWriterOntoDestination() = runTest(testDispatcher) {
        val (dbA, dbB) = setupTwoDatabases()

        val preGate = CompletableDeferred<Unit>()
        val preWriter = launch {
            manager.withDb {
                preGate.await()
                null
            }
        }
        val associateJob = launch { manager.associateDevice(nodeNum = 123, deviceId = "deadbeefdeadbeef") }

        val newWriterDb = CompletableDeferred<MeshtasticDatabase>()
        val newWriterJob = launch {
            manager.withDb { db ->
                newWriterDb.complete(db)
                null
            }
        }

        assertFalse(newWriterDb.isCompleted, "new writer must block during the drain")
        preGate.complete(Unit)
        val usedDb = newWriterDb.await()
        assertEquals(dbA, usedDb, "blocked writer released onto destination after successful merge")
        assertEquals(dbA, manager.currentDb.value, "destination is canonical after commit")

        associateJob.join()
        preWriter.join()
        newWriterJob.join()
    }

    /** A pre-commit merge failure releases the blocked writer onto source; the destination is never activated. */
    @Test
    fun preCommitMergeFailureReleasesBlockedWriterOntoSource() = runTest(testDispatcher) {
        val (dbA, dbB) = setupTwoDatabases()

        manager.failMergeWith = RuntimeException("simulated pre-commit merge failure")

        val preGate = CompletableDeferred<Unit>()
        val preWriter = launch {
            manager.withDb {
                preGate.await()
                null
            }
        }
        val associateJob = launch { manager.associateDevice(nodeNum = 123, deviceId = "deadbeefdeadbeef") }

        val newWriterDb = CompletableDeferred<MeshtasticDatabase>()
        val newWriterJob = launch {
            manager.withDb { db ->
                newWriterDb.complete(db)
                null
            }
        }

        preGate.complete(Unit)
        val usedDb = newWriterDb.await()
        assertEquals(dbB, usedDb, "blocked writer released onto source after pre-commit merge failure")
        associateJob.join()
        assertEquals(dbB, manager.currentDb.value, "source remains active after merge failure")

        preWriter.join()
        newWriterJob.join()
    }

    /**
     * Cancelling the attempt while a writer is blocked at the gate releases the gate onto source and leaves no waiter
     * leaked.
     */
    @Test
    fun cancellationReleasesAdmissionGateOntoSource() = runTest(testDispatcher) {
        val (dbA, dbB) = setupTwoDatabases()

        val preGate = CompletableDeferred<Unit>()
        val preWriter = launch {
            manager.withDb {
                preGate.await()
                null
            }
        }
        val associateJob = launch { manager.associateDevice(nodeNum = 123, deviceId = "deadbeefdeadbeef") }

        val newWriterDb = CompletableDeferred<MeshtasticDatabase>()
        val newWriterJob = launch {
            manager.withDb { db ->
                newWriterDb.complete(db)
                null
            }
        }

        associateJob.cancelAndJoin()
        val usedDb = newWriterDb.await()
        assertEquals(dbB, usedDb, "gate released onto source when the attempt is cancelled")
        assertEquals(dbB, manager.currentDb.value, "source active after cancellation")

        preGate.complete(Unit)
        preWriter.join()
        newWriterJob.join()
    }

    /**
     * After a full association cycle with an admission-gated writer, the writer tracker holds no live writers and no
     * pending drain waiters — counts and waiters are balanced.
     */
    @Test
    fun writerCountsAndDrainWaitersRemainBalanced() = runTest(testDispatcher) {
        val (dbA, dbB) = setupTwoDatabases()

        val preGate = CompletableDeferred<Unit>()
        val preWriter = launch {
            manager.withDb {
                preGate.await()
                null
            }
        }
        val associateJob = launch { manager.associateDevice(nodeNum = 123, deviceId = "deadbeefdeadbeef") }
        val newWriterJob = launch { manager.withDb { _ -> null } }

        preGate.complete(Unit)
        associateJob.join()
        preWriter.join()
        newWriterJob.join()

        val (writers, waiters) = manager.debugWriterCounts()
        assertEquals(0, writers, "no leaked writers after association")
        assertEquals(0, waiters, "no pending drain waiters after association")
    }

    // ── Logical retirement regression ───────────────────────────────────────────

    /**
     * After a successful merge, the retired source database must remain open — app-wide consumers may still hold
     * references to the published source Room instance. A direct DAO read through the retained source reference must
     * succeed, proving the pool was not physically closed during the process. Destination must be canonical and
     * writer/waiter counts must be balanced.
     */
    @Test
    fun sourcePoolStaysOpenAfterLogicalRetirement() = runTest(testDispatcher) {
        val (dbA, dbB) = setupTwoDatabases()
        assertEquals(dbB, manager.currentDb.value, "addrB's DB should be active before association")
        val sourceDb = manager.currentDb.value

        // Successful merge logically retires the source but does not close its pool.
        manager.associateDevice(nodeNum = 123, deviceId = "deadbeefdeadbeef")
        assertEquals(dbA, manager.currentDb.value, "destination must be canonical after successful merge")

        // Direct DAO read on the retained source reference — must succeed (pool still open).
        val sourceMyNode = sourceDb.nodeInfoDao().getMyNodeInfo().first()
        assertNull(sourceMyNode, "source read must succeed (pool open); source content is empty by default")

        val (writers, waiters) = manager.debugWriterCounts()
        assertEquals(0, writers, "no leaked writers after association")
        assertEquals(0, waiters, "no pending drain waiters after association")
    }

    /**
     * [DatabaseManager.close] must be idempotent after logical retirement: calling close twice must not throw. The
     * first close physically cleans the logically-retired source; the second close is a safe no-op because the cache
     * and retirement set are already empty.
     */
    @Test
    fun closeIsIdempotentAndCleansRetiredSources() = runTest(testDispatcher) {
        setupTwoDatabases()
        manager.associateDevice(nodeNum = 123, deviceId = "deadbeefdeadbeef")

        manager.close()
        manager.close()
    }
}
