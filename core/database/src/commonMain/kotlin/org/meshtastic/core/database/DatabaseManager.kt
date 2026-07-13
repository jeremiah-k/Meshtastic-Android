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
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import org.meshtastic.core.common.util.normalizeAddress
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.di.CoroutineDispatchers
import kotlin.concurrent.Volatile
import org.meshtastic.core.common.database.DatabaseManager as SharedDatabaseManager

/** Manages per-device Room database instances for node data, with LRU eviction. */
@Single(binds = [DatabaseProvider::class, SharedDatabaseManager::class])
@Suppress("TooManyFunctions")
@OptIn(ExperimentalCoroutinesApi::class)
open class DatabaseManager(
    @Named("DatabaseDataStore") private val datastore: DataStore<Preferences>,
    private val dispatchers: CoroutineDispatchers,
) : DatabaseProvider,
    SharedDatabaseManager {

    private val managerScope = CoroutineScope(SupervisorJob() + dispatchers.default)
    private val mutex = Mutex()

    // Per-source write barrier for merges. `withDb` deliberately does NOT take [mutex] (hot path), so a merge under
    // [mutex] must still drain any in-flight writer that captured the source DB before folding it away — otherwise a
    // late-committing write is lost when the source is retired. This dedicated lock (never held across a drain await,
    // so it can't deadlock the merge) tracks live `withDb` blocks per captured DB instance and the writer-admission
    // gate. The gate is armed at the start of an association attempt: while it is pending, [beginWrite] blocks new
    // writers instead of letting them capture a DB, so a new `withDb` can never write to `source` once it is being
    // retired, nor land on `dest` before the merge commits. The gate completes with `source` if the attempt aborts
    // (drain timeout, cancellation, or pre-commit merge failure) and with `dest` once the merge commits — source is
    // never restored after the merge commits. The lock is released before any suspend (drain await, gate await, Room
    // work, merge work, or DataStore work), so it can't deadlock any of them.
    private val writerTrackerMutex = Mutex()
    private val activeWriters = mutableMapOf<MeshtasticDatabase, Int>()
    private val drainWaiters = mutableMapOf<MeshtasticDatabase, MutableList<CompletableDeferred<Unit>>>()

    // Armed at the start of an association attempt; null otherwise. A non-null gate blocks [beginWrite] until the
    // attempt resolves. It completes with the canonical DB (source on abort, dest on commit) so blocked writers resume
    // against the right instance. Never read or written outside [writerTrackerMutex].
    private var writerGate: CompletableDeferred<MeshtasticDatabase>? = null

    private val cacheLimitKey = intPreferencesKey(DatabaseConstants.CACHE_LIMIT_KEY)
    private val legacyCleanedKey = booleanPreferencesKey(DatabaseConstants.LEGACY_DB_CLEANED_KEY)

    private fun lastUsedKey(dbName: String) = longPreferencesKey("db_last_used:$dbName")

    private fun addrDbKey(address: String?) =
        stringPreferencesKey("${DatabaseConstants.ADDR_DB_FOR_PREFIX}${normalizeAddress(address)}")

    private var backfillJob: Job? = null

    @Volatile private var hasDelayedFirstDeviceBackfill = false

    override val cacheLimit: StateFlow<Int> =
        datastore.data
            .map { it[cacheLimitKey] ?: DatabaseConstants.DEFAULT_CACHE_LIMIT }
            .stateIn(managerScope, SharingStarted.Eagerly, DatabaseConstants.DEFAULT_CACHE_LIMIT)

    override fun getCurrentCacheLimit(): Int = cacheLimit.value

    override fun setCacheLimit(limit: Int) {
        val clamped = limit.coerceIn(DatabaseConstants.MIN_CACHE_LIMIT, DatabaseConstants.MAX_CACHE_LIMIT)
        managerScope.launch {
            datastore.edit { it[cacheLimitKey] = clamped }
            // Enforce asynchronously with current active DB protected
            enforceCacheLimit(activeDbName = currentDbName)
        }
    }

    private val dbCache = mutableMapOf<String, MeshtasticDatabase>()

    /** Databases merged and logically retired but kept open — app-wide consumers may still hold references. */
    private val logicallyRetired = mutableSetOf<String>()

    private val _currentDb = MutableStateFlow(getOrOpenDatabase(DatabaseConstants.DEFAULT_DB_NAME))

    /**
     * The currently active database. The default DB is opened eagerly at construction and every internal publication
     * ([switchActiveDatabase], association rollback/release, active-DB reopen recovery) writes [_currentDb] directly,
     * so [currentDb].value reflects the new instance on the same program step — no `stateIn`/`filterNotNull` derivation
     * that would delay visibility to a coroutine dispatch.
     *
     * Room's `onOpen` callback is itself lazy (not invoked until the first query), so construction only allocates the
     * builder and connection pool — actual I/O is deferred.
     */
    override val currentDb: StateFlow<MeshtasticDatabase> = _currentDb.asStateFlow()

    private val _currentAddress = MutableStateFlow<String?>(null)
    val currentAddress: StateFlow<String?> = _currentAddress

    /**
     * Name of the currently active database. Tracked explicitly rather than recomputed from the address, because
     * cross-transport aliasing ([associateDevice]) decouples the two: a secondary transport's address maps to the DB
     * claimed by the first transport, which `buildDbName(address)` would never produce. Written under [mutex].
     */
    @Volatile private var currentDbName: String = DatabaseConstants.DEFAULT_DB_NAME

    /** Initialize the active database for [address]. */
    suspend fun init(address: String?) {
        switchActiveDatabase(address)
    }

    /**
     * Returns a cached [MeshtasticDatabase] or builds a new one for [dbName]. The caller must hold [mutex] when
     * modifying [dbCache] concurrently; however, this helper is also used from [currentDb]'s `initialValue` where the
     * mutex is not yet relevant (single-threaded construction).
     */
    private fun getOrOpenDatabase(dbName: String): MeshtasticDatabase =
        dbCache.getOrPut(dbName) { buildDatabase(dbName) }

    /**
     * Builds a new [MeshtasticDatabase] for [dbName]. Tests override this to control file placement (temp directory
     * instead of the platform data dir). Production delegates to the platform-specific [getDatabaseBuilder].
     */
    protected open fun buildDatabase(dbName: String): MeshtasticDatabase = getDatabaseBuilder(dbName).build()

    /**
     * Resolves the DB name to use for [address], honoring a cross-transport alias when one exists. A secondary
     * transport (e.g. TCP) that has been unified with a node points at the DB the first transport (e.g. BLE) claimed;
     * without an alias this falls back to the address-hashed name — today's default — for a first-time or primary
     * connection. See [associateDevice].
     */
    private suspend fun resolveDbName(address: String?): String {
        val fallback = buildDbName(address)
        if (fallback == DatabaseConstants.DEFAULT_DB_NAME) return fallback
        return datastore.data.first()[addrDbKey(address)] ?: fallback
    }

    /** Switch active database to the one associated with [address]. Serialized via mutex. */
    override suspend fun switchActiveDatabase(address: String?) = mutex.withLock {
        val dbName = resolveDbName(address)

        // Remember the previously active DB name (any) so we can record its last-used time as well.
        val previousDbName = currentDbName

        // Fast path: no-op if already on this address
        if (_currentAddress.value == address) {
            markLastUsed(dbName)
            return@withLock
        }

        // Build/open Room DB off the main thread
        val db = withContext(dispatchers.io) { getOrOpenDatabase(dbName) }

        // Emit the new DB BEFORE closing the old ones. flatMapLatest collectors on
        // currentDb will cancel their in-flight queries on the previous database once
        // the new value is emitted. Closing the old pool first would race with those
        // collectors, causing "Connection pool is closed" crashes.
        _currentDb.value = db
        _currentAddress.value = address
        currentDbName = dbName
        markLastUsed(dbName)
        // Also mark the previous DB as used "just now" so LRU has an accurate, recent timestamp
        markLastUsed(previousDbName)

        // Do NOT close the previous DB synchronously here. Even though _currentDb has been
        // updated, in-flight `withDb` calls may still hold a reference to the old database
        // (captured before the emission). Closing the connection pool while those queries are
        // executing causes "Connection pool is closed" crashes. Instead, let LRU eviction
        // (enforceCacheLimit) handle cleanup — it only runs on databases that are not the
        // active target and have not been used recently.

        schedulePostSwitchMaintenance(dbName = dbName, db = db)

        Logger.i { "Switched active DB to ${anonymizeDbName(dbName)} for address ${anonymizeAddress(address)}" }
    }

    /**
     * Schedules deferred maintenance that runs after switching the active database. Posts work to [managerScope] on
     * [dispatchers.io] so the switch path is not blocked by filesystem or search-index I/O.
     *
     * In production this schedules LRU cache-limit enforcement, legacy-DB cleanup, and FTS search-index backfill.
     * In-memory test fixtures override it to no-op because they do not have a filesystem-backed database directory and
     * must not access platform context singletons (e.g. `ContextServices.app`).
     */
    protected open fun schedulePostSwitchMaintenance(dbName: String, db: MeshtasticDatabase) {
        // Defer LRU eviction so switch is not blocked by filesystem work
        managerScope.launch(dispatchers.io) { enforceCacheLimit(activeDbName = dbName) }

        // One-time cleanup: remove legacy DB if present and not active
        managerScope.launch(dispatchers.io) { cleanupLegacyDbIfNeeded(activeDbName = dbName) }

        // Backfill FTS search index for any text messages missing messageText.
        // On the first real device DB, defer this so it does not starve the single DB connection while
        // the UI is collecting startup flows. The default DB should not consume the cold-start delay.
        val shouldDelayBackfill = dbName != DatabaseConstants.DEFAULT_DB_NAME && !hasDelayedFirstDeviceBackfill
        if (shouldDelayBackfill) hasDelayedFirstDeviceBackfill = true
        scheduleSearchIndexBackfill(dbName = dbName, db = db, shouldDelayBackfill = shouldDelayBackfill)
    }

    @Suppress("TooGenericExceptionCaught", "CyclomaticComplexMethod", "LongMethod")
    override suspend fun associateDevice(nodeNum: Int, deviceId: String?) {
        mutex.withLock {
            val sourceName = currentDbName
            // Never claim or merge into the sentinel "no device" DB.
            if (sourceName == DatabaseConstants.DEFAULT_DB_NAME) return@withLock

            // The device-id claim is the durable one (node numbers renumber under firmware 2.8); the
            // node-num claim stays as the fallback for hardware without a device id, for lockdown
            // sessions (device_id zeroed), and for claims written by older app versions. Writes always
            // refresh both keys so either lookup path resolves on the next connection.
            val deviceKey = validDeviceIdOrNull(deviceId)?.let(::deviceDbPrefKey)
            val nodeKey = nodeDbPrefKey(nodeNum)
            val prefs = datastore.data.first()
            val claimed = resolveDbClaim(prefs, deviceKey, nodeKey)
            suspend fun writeClaims(dbName: String) = datastore.edit {
                deviceKey?.let { key -> it[key] = dbName }
                it[nodeKey] = dbName
            }

            when {
                claimed == null -> {
                    // First transport to learn this device: its current DB becomes the device's canonical DB.
                    // No address alias is needed — a primary connection already resolves to this DB via buildDbName.
                    writeClaims(sourceName)
                    Logger.i { "Claimed ${anonymizeDbName(sourceName)} as canonical DB for node $nodeNum" }
                }

                claimed == sourceName -> {
                    // Already unified — backfill or refresh any stale/missing routing metadata atomically.
                    // This also repairs a post-merge routing failure (merge committed but DataStore edit failed):
                    // the next connect reaches this branch and writes claims + alias in one edit without
                    // re-copying source (the merge marker prevents duplicate data).
                    val address = _currentAddress.value
                    val needsDeviceKey = deviceKey != null && prefs[deviceKey] != sourceName
                    val needsNodeKey = prefs[nodeKey] != sourceName
                    val needsAlias = prefs[addrDbKey(address)] != sourceName
                    if (needsDeviceKey || needsNodeKey || needsAlias) {
                        datastore.edit {
                            deviceKey?.let { key -> if (needsDeviceKey) it[key] = sourceName }
                            if (needsNodeKey) it[nodeKey] = sourceName
                            if (needsAlias) it[addrDbKey(address)] = sourceName
                            it[lastUsedKey(sourceName)] = nowMillis
                        }
                        Logger.i { "Refreshed routing metadata for ${anonymizeDbName(sourceName)}" }
                    }
                }

                else -> {
                    // Secondary transport reached an already-known node: fold this DB into the canonical one,
                    // switch the active DB to it, alias this address to it, and retire the now-merged source.
                    val source = _currentDb.value
                    val dest = withContext(dispatchers.io) { getOrOpenDatabase(claimed) }

                    // Arm the writer-admission gate BEFORE any drain/merge work. Source stays canonical (active) for
                    // the
                    // whole attempt, so a new `withDb` writer that arrives now blocks here (outside
                    // [writerTrackerMutex])
                    // instead of capturing `source` (which is about to be retired) or `dest` (which doesn't exist yet).
                    // The gate completes with `source` if the attempt aborts, or `dest` once the merge commits — it can
                    // never release a writer onto a DB that is being torn down.
                    val gate = CompletableDeferred<MeshtasticDatabase>()
                    writerTrackerMutex.withLock { writerGate = gate }
                    val transportAddress = _currentAddress.value

                    // Releases blocked writers onto `source` (the attempt aborted; source is still canonical). Runs
                    // under NonCancellable so a cancelled attempt still unblocks its waiters instead of leaking them.
                    suspend fun releaseGateToSource() {
                        withContext(NonCancellable) {
                            writerTrackerMutex.withLock {
                                writerGate = null
                                _currentDb.value = source
                                currentDbName = sourceName
                            }
                            gate.complete(source)
                        }
                    }

                    // Releases blocked writers onto `dest` (the merge committed; dest is now canonical).
                    suspend fun releaseGateToDest() {
                        withContext(NonCancellable) {
                            writerTrackerMutex.withLock {
                                writerGate = null
                                _currentDb.value = dest
                                currentDbName = claimed
                            }
                            gate.complete(dest)
                        }
                    }

                    // ── Phase 1: Drain source writers (cancellable — safe to release onto source before merge
                    // commits).
                    val drained =
                        try {
                            withContext(dispatchers.io) { drainWriters(source, sourceName) }
                        } catch (e: CancellationException) {
                            releaseGateToSource()
                            throw e
                        }
                    if (!drained) {
                        releaseGateToSource()
                        Logger.w {
                            "Aborted merge of ${anonymizeDbName(sourceName)} into ${anonymizeDbName(claimed)}: " +
                                "writer drain timed out; kept ${anonymizeDbName(sourceName)} active"
                        }
                        return@withLock
                    }

                    // ── Phase 2: Merge + routing finalization (NonCancellable).
                    // After mergeDatabases commits, a marker in dest makes retries skip the data copy.
                    // Source must NEVER be reactivated past that point — post-merge writes to source would be lost
                    // when a retry sees the marker and skips the copy. This phase runs under NonCancellable so no
                    // cancellation window separates merge commit from routing finalization.
                    var mergeCommitted = false
                    try {
                        withContext(NonCancellable + dispatchers.io) {
                            mergeDatabases(source, dest, sourceName)
                            mergeCommitted = true
                            // Persist ALL routing metadata in one atomic DataStore edit.
                            datastore.edit {
                                deviceKey?.let { key -> it[key] = claimed }
                                it[nodeKey] = claimed
                                it[addrDbKey(transportAddress)] = claimed
                                it[lastUsedKey(claimed)] = nowMillis
                            }
                            Logger.i {
                                "Unified ${anonymizeDbName(
                                    sourceName,
                                )} into ${anonymizeDbName(claimed)} for node $nodeNum"
                            }
                            // Dest is canonical. Release blocked writers onto it, then retire source off the critical
                            // path.
                            releaseGateToDest()
                            managerScope.launch(dispatchers.io) { retireDatabase(sourceName) }
                        }
                    } catch (e: CancellationException) {
                        // NonCancellable suppresses parent cancellation, but a child could throw this.
                        // Don't restore source after merge commit — release blocked writers onto dest.
                        if (!mergeCommitted) {
                            releaseGateToSource()
                        } else {
                            releaseGateToDest()
                        }
                        throw e
                    } catch (e: Exception) {
                        if (!mergeCommitted) {
                            // merge() failed — transaction rolled back, no marker. Safe to release onto source.
                            releaseGateToSource()
                            Logger.w(e) {
                                "Merge into ${anonymizeDbName(claimed)} failed; " +
                                    "kept ${anonymizeDbName(sourceName)} active"
                            }
                        } else {
                            // Merge committed but routing metadata persistence failed. Destination stays active
                            // (merge marker exists); source is NOT retired. The already-unified branch on the
                            // next connect repairs claims/alias atomically without re-copying source.
                            releaseGateToDest()
                            Logger.w(e) {
                                "Routing metadata for ${anonymizeDbName(claimed)} failed after merge commit; " +
                                    "destination remains active, routing will be repaired on next connect"
                            }
                        }
                        return@withLock
                    }
                    // Propagate any cancellation that was suppressed during the NonCancellable finalization phase.
                    currentCoroutineContext().ensureActive()
                }
            }
        }
    }

    /**
     * Logically retires a database whose contents have been merged into another.
     *
     * Physical close/delete is deferred — the merged source was published through [currentDb]; app-wide Flow, Paging,
     * UI, worker, and one-shot read consumers may still hold its Room instance. Physically closing it now can surface
     * "Connection pool is closed" to those readers (see [switchActiveDatabase] and [reopenActiveDatabaseIfStillCurrent]
     * no-sync-close discipline). [close] or a later process performs the physical teardown.
     */
    private suspend fun retireDatabase(dbName: String) = mutex.withLock {
        logicallyRetired.add(dbName)
        Logger.i { "Logically retired merged DB ${anonymizeDbName(dbName)}; physical cleanup deferred" }
    }

    /**
     * Closes and removes a cached database by name. Safe to call even if the database was already closed or not in the
     * cache. Does NOT delete the underlying file — the database can be re-opened on next access.
     *
     * On JVM/Desktop, Room KMP has no auto-close timeout (Android-only API), so idle databases hold open SQLite
     * connections (5 per WAL-mode DB) indefinitely until explicitly closed. This method is the primary mechanism for
     * releasing those connections when a database is no longer the active target.
     */
    protected open suspend fun closeCachedDatabase(dbName: String) {
        val removed = dbCache.remove(dbName) ?: return
        runCatching { removed.close() }
            .onFailure { Logger.w(it) { "Failed to close cached database ${anonymizeDbName(dbName)}" } }
        Logger.d { "Closed inactive database ${anonymizeDbName(dbName)} to free connections" }
    }

    /**
     * Reopens the active database under [mutex], but only if it hasn't switched since the caller snapshotted it.
     *
     * The replaced Room instance is intentionally left open for the rest of the process. [currentDb] reads [_currentDb]
     * directly, so every publication is visible to app-wide collectors on the same program step — but there is no
     * deterministic handoff point where every collector has stopped using the previous instance.
     *
     * Returns the reopened DB, or null if another coroutine already switched to a different device.
     */
    private suspend fun reopenActiveDatabaseIfStillCurrent(
        expectedDb: MeshtasticDatabase,
        expectedDbName: String,
    ): MeshtasticDatabase? = mutex.withLock {
        if (_currentDb.value !== expectedDb || currentDbName != expectedDbName) return null

        val cached = dbCache[expectedDbName]
        if (cached !== expectedDb) {
            Logger.w { "withDb: active DB cache entry changed before reopen; skipping active DB reopen" }
            return null
        }

        // Build a fresh instance directly (not through getOrPut) before touching the cache,
        // so a failed or cancelled build leaves the existing cache entry and _currentDb consistent.
        val reopened = withContext(dispatchers.io) { getDatabaseBuilder(expectedDbName).build() }
        dbCache[expectedDbName] = reopened
        _currentDb.value = reopened

        // Intentionally do not close expectedDb here. The public currentDb Flow exposes _currentDb directly,
        // so downstream flatMapLatest collectors may still be using the replaced Room instance after this
        // function emits the reopened DB. Closing the old pool here can surface "Connection pool is closed"
        // to app-wide DB observers that do not have closed-pool recovery. This mirrors switchActiveDatabase's
        // no-sync-close discipline; the leaked pool is bounded by rare active-DB reopen recovery events and
        // is reclaimed on process death. Revisit once switching DB observers have explicit closed-pool
        // resubscribe/retry handling.

        reopened
    }

    // Short-term runtime containment: route withDb entry through a single-lane dispatcher to narrow the Room/SQLite
    // connection-pool churn window seen during device/firmware update flows. Room suspend DAOs may continue on Room's
    // own executor after suspension, so this is not a strict global DB-I/O serialization guarantee. Preserve bounded
    // one-shot DB-critical blocks through cancellation, then re-check cancellation so stale callers do not continue
    // after the DB releases. Long-lived Flow/Paging reads must stay out of withDb; revisit after direct currentDb.value
    // callers are audited and safe DB concurrency can be restored.
    protected open val limitedIo: CoroutineDispatcher by lazy { dispatchers.io.limitedParallelism(1) }

    /** Execute [block] with the current DB instance. Retries once if the pool closes during a DB switch. */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    override suspend fun <T> withDb(block: suspend (MeshtasticDatabase) -> T): T? {
        val queuedAt = nowMillis
        return withContext(limitedIo) {
            val queuedMillis = nowMillis - queuedAt
            if (queuedMillis >= WITH_DB_SLOW_OPERATION_MS) {
                Logger.w { "withDb waited ${queuedMillis}ms for the temporary DB containment lane" }
            }

            val startedAt = nowMillis
            try {
                withCurrentDb(block)
            } finally {
                val elapsedMillis = nowMillis - startedAt
                if (elapsedMillis >= WITH_DB_SLOW_OPERATION_MS) {
                    Logger.w {
                        "withDb callback took ${elapsedMillis}ms on the temporary DB containment lane; persistent " +
                            "slow logs indicate DB access path should be revisited"
                    }
                }
            }
        }
    }

    /**
     * Atomically snapshots the canonical active DB (held by [_currentDb], which is initialized to the default DB at
     * construction) and registers a writer against it.
     *
     * If an association attempt is in flight, the writer-admission gate is armed. The caller snapshots that gate under
     * [writerTrackerMutex], awaits it outside the lock, then retries admission from the beginning. Selecting the active
     * database and registering the writer happen in the same critical section, so an association cannot arm its gate
     * between those operations. This guarantees a new `withDb` never writes to a DB that is being retired, nor lands on
     * `dest` before its data exists.
     */
    private suspend fun beginWrite(): MeshtasticDatabase {
        while (true) {
            var admittedDb: MeshtasticDatabase? = null
            val gate =
                writerTrackerMutex.withLock {
                    val pendingGate = writerGate
                    if (pendingGate == null) {
                        val db = _currentDb.value
                        activeWriters[db] = (activeWriters[db] ?: 0) + 1
                        admittedDb = db
                    }
                    pendingGate
                }
            admittedDb?.let {
                return it
            }
            gate?.await()
        }
    }

    /**
     * Registers a writer against a specific [db] — used by the withDb retry paths, whose target is a recovered/new
     * instance rather than the snapshotted active DB, so their writes stay visible to a concurrent drain too.
     */
    private suspend fun registerWriter(db: MeshtasticDatabase) =
        writerTrackerMutex.withLock { activeWriters[db] = (activeWriters[db] ?: 0) + 1 }

    /** Deregisters a writer and releases any merge waiting for [db] to quiesce. Cancellation-safe (see call site). */
    private suspend fun endWrite(db: MeshtasticDatabase) = writerTrackerMutex.withLock {
        val remaining = (activeWriters[db] ?: 1) - 1
        if (remaining <= 0) {
            activeWriters.remove(db)
            drainWaiters.remove(db)?.forEach { it.complete(Unit) }
        } else {
            activeWriters[db] = remaining
        }
    }

    /**
     * Folds [source] into [dest]. Override in tests to inject merge failures. Production delegates to
     * [DatabaseMerger.merge]; the merge runs in a single transaction so a crash rolls back cleanly and the destination
     * is never left half-merged.
     */
    protected open suspend fun mergeDatabases(
        source: MeshtasticDatabase,
        dest: MeshtasticDatabase,
        sourceName: String,
    ) {
        DatabaseMerger.merge(source, dest, sourceName)
    }

    /**
     * Test-only snapshot of the writer tracker: total live writers and total pending drain waiters. Both are zero once
     * every association attempt has released its gate and drained its source — a non-zero pair after a quiescent period
     * indicates a leaked writer or waiter.
     */
    internal suspend fun debugWriterCounts(): Pair<Int, Int> =
        writerTrackerMutex.withLock { activeWriters.values.sum() to drainWaiters.values.sumOf { it.size } }

    /**
     * Suspends until every writer that captured [db] before this call has finished, so a merge never snapshots [db]
     * while a write is still in flight (and then loses it when [db] is retired). Bounded by [WRITER_DRAIN_TIMEOUT_MS]
     * so a wedged writer can't pin the merge — and [mutex] — forever.
     *
     * Returns `true` if all writers drained (or none were active), `false` on timeout. The caller must abort the merge
     * and roll the active DB back to source on `false`.
     *
     * The waiter is removed in a [finally] block on every exit path — success, timeout, and external cancellation — so
     * a stale [CompletableDeferred] never leaks into [drainWaiters]. The cleanup runs under [NonCancellable] so
     * cancellation during cleanup doesn't skip the removal.
     */
    @Suppress("ReturnCount")
    private suspend fun drainWriters(db: MeshtasticDatabase, dbName: String): Boolean {
        val waiter =
            writerTrackerMutex.withLock {
                if ((activeWriters[db] ?: 0) == 0) return true
                CompletableDeferred<Unit>().also { drainWaiters.getOrPut(db) { mutableListOf() }.add(it) }
            }
        try {
            val drained = withTimeoutOrNull(WRITER_DRAIN_TIMEOUT_MS) { waiter.await() }
            if (drained == null) {
                Logger.w { "Timed out draining writers on ${anonymizeDbName(dbName)} before merge" }
                return false
            }
            return true
        } finally {
            // Remove our waiter on every exit path. On success, endWrite may have already removed the
            // entire list — the removal is idempotent. On timeout or cancellation, the waiter is still
            // registered and must be cleaned up so a late endWrite doesn't complete a dead deferred.
            withContext(NonCancellable) {
                writerTrackerMutex.withLock {
                    val list = drainWaiters[db]
                    if (list != null) {
                        list.remove(waiter)
                        if (list.isEmpty()) drainWaiters.remove(db)
                    }
                }
            }
        }
    }

    @Suppress("ReturnCount", "ThrowsCount", "TooGenericExceptionCaught", "CyclomaticComplexMethod")
    private suspend fun <T> withCurrentDb(block: suspend (MeshtasticDatabase) -> T): T? {
        val db = beginWrite()
        val active = currentDbName
        markLastUsed(active)
        try {
            return runCancellableDbBlock(db, block)
        } catch (e: CancellationException) {
            throw e // Preserve structured concurrency cancellation propagation.
        } catch (e: Exception) {
            // If the active database switched while we held a reference to the old one,
            // and the exception indicates a closed pool/connection, retry with the new DB.
            val retryDb = _currentDb.value
            if (retryDb !== db && isDbClosedException(e)) {
                Logger.w { "withDb: database closed during switch (${e.message}), retrying with current DB" }
                return retryRegisteredDbBlock(retryDb, e, block)
            }

            // Same active DB but Room's connection pool is wedged — reopen onto a fresh active instance once.
            if (retryDb === db && isDbPoolAcquireTimeoutException(e)) {
                val reopened = reopenActiveDatabaseIfStillCurrent(db, active)
                val recoveredDb = reopened ?: _currentDb.value.takeIf { it !== db } ?: throw e
                Logger.w {
                    if (reopened != null) {
                        "withDb: reopened active DB after transient Room connection-pool timeout"
                    } else {
                        "withDb: active DB switched during timeout recovery; retrying with current DB"
                    }
                }
                return retryRegisteredDbBlock(recoveredDb, e, block)
            }

            throw e
        } finally {
            // NonCancellable so a cancelled withDb still deregisters — a leaked +1 would make every future
            // drain on this DB instance time out.
            withContext(NonCancellable) { endWrite(db) }
        }
    }

    /**
     * Retries [block] against [db] — the recovered/new instance a withDb retry targets instead of the DB it originally
     * registered against. Registers a writer on [db] for the duration so the retry write stays visible to a concurrent
     * merge draining [db], with the same NonCancellable deregistration guarantee as [withCurrentDb]'s outer
     * registration (which remains held on the original DB until that finally runs — the overlap is harmless, counts
     * balance per instance). Any retry failure carries the original failure [cause] as a suppressed exception.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> retryRegisteredDbBlock(
        db: MeshtasticDatabase,
        cause: Exception,
        block: suspend (MeshtasticDatabase) -> T,
    ): T {
        registerWriter(db)
        try {
            return runCancellableDbBlock(db, block)
        } catch (retryCancel: CancellationException) {
            throw retryCancel
        } catch (retryEx: Exception) {
            retryEx.addSuppressed(cause)
            throw retryEx
        } finally {
            withContext(NonCancellable) { endWrite(db) }
        }
    }

    private suspend fun <T> runCancellableDbBlock(db: MeshtasticDatabase, block: suspend (MeshtasticDatabase) -> T): T {
        // Keep withDb callbacks bounded and one-shot: NonCancellable can hold the containment lane until this returns.
        currentCoroutineContext().ensureActive()
        val result = withContext(NonCancellable) { block(db) }
        currentCoroutineContext().ensureActive()
        return result
    }

    private fun isDbClosedException(e: Exception): Boolean = isDbPoolAcquireTimeoutException(e) ||
        generateSequence<Throwable>(e) { it.cause }
            .any { throwable ->
                val msg = throwable.message?.lowercase() ?: return@any false
                val hasDbContext = DB_TERMS.any { it in msg }
                ("closed" in msg && hasDbContext) || "database is locked" in msg || "sqlite_busy" in msg
            }

    internal companion object {
        private const val BACKFILL_COLD_START_DELAY_MS = 2_000L
        private const val WITH_DB_SLOW_OPERATION_MS = 1_000L

        /**
         * Upper bound on how long a merge waits for in-flight writers on the source DB to drain (see [drainWriters]).
         */
        private const val WRITER_DRAIN_TIMEOUT_MS = 5_000L
        val DB_TERMS = listOf("pool", "database", "connection", "sqlite")

        private const val ROOM_POOL_ACQUIRE_TIMEOUT_PHRASE = "timed out attempting to acquire"
        private const val ROOM_READER_CONNECTION_PHRASE = "reader connection"
        private const val ROOM_WRITER_CONNECTION_PHRASE = "writer connection"

        /**
         * Room KMP currently exposes pool-acquire timeouts as exception message text instead of a stable common typed
         * signal. Keep this fallback narrow so BLE/GATT/transport connection errors do not trigger DB reopen recovery.
         */
        private fun isRoomPoolAcquireTimeoutMessage(message: String): Boolean =
            ROOM_POOL_ACQUIRE_TIMEOUT_PHRASE in message &&
                (ROOM_READER_CONNECTION_PHRASE in message || ROOM_WRITER_CONNECTION_PHRASE in message)

        fun isDbPoolAcquireTimeoutException(e: Exception): Boolean = generateSequence<Throwable>(e) { it.cause }
            .any { throwable ->
                val msg = throwable.message?.lowercase() ?: return@any false
                isRoomPoolAcquireTimeoutMessage(msg)
            }
    }

    /**
     * Returns true if a database exists for the given device address. Android Room stores DB files without an
     * extension; JVM/iOS append `.db`. We check both to stay platform-agnostic.
     */
    override fun hasDatabaseFor(address: String?): Boolean {
        if (address.isNullOrBlank() || address == "n") return false
        val dbName = buildDbName(address)
        return dbFileExists(dbName)
    }

    private fun dbFileExists(dbName: String): Boolean {
        val dir = getDatabaseDirectory()
        val fs = getFileSystem()
        return fs.exists(dir.resolve(dbName)) || fs.exists(dir.resolve("$dbName.db"))
    }

    private fun dbFileMetadataMillis(dbName: String): Long? {
        val dir = getDatabaseDirectory()
        val fs = getFileSystem()
        return fs.metadataOrNull(dir.resolve(dbName))?.lastModifiedAtMillis
            ?: fs.metadataOrNull(dir.resolve("$dbName.db"))?.lastModifiedAtMillis
    }

    private fun markLastUsed(dbName: String) {
        managerScope.launch { datastore.edit { it[lastUsedKey(dbName)] = nowMillis } }
    }

    private suspend fun lastUsed(dbName: String): Long {
        val key = lastUsedKey(dbName)
        val v = datastore.data.first()[key] ?: 0L
        return if (v == 0L) {
            dbFileMetadataMillis(dbName) ?: 0L
        } else {
            v
        }
    }

    private fun listExistingDbNames(): List<String> {
        val dir = getDatabaseDirectory()
        val fs = getFileSystem()
        if (!fs.exists(dir)) return emptyList()

        return fs.list(dir)
            .asSequence()
            .map { it.name }
            .filter { it.startsWith(DatabaseConstants.DB_PREFIX) }
            // Skip Room-internal sidecar files (-wal/-shm/-journal) and lock files so each DB appears exactly once.
            .filterNot { it.endsWith("-wal") || it.endsWith("-shm") || it.endsWith("-journal") || it.endsWith(".lck") }
            .map { it.removeSuffix(".db") }
            .distinct()
            .toList()
    }

    private suspend fun enforceCacheLimit(activeDbName: String) = mutex.withLock {
        val limit = getCurrentCacheLimit()
        val all = listExistingDbNames()
        // Only enforce the limit over device-specific DBs; exclude legacy and default DBs
        val deviceDbs =
            all.filterNot {
                it in logicallyRetired ||
                    it == DatabaseConstants.LEGACY_DB_NAME ||
                    it == DatabaseConstants.DEFAULT_DB_NAME
            }

        if (deviceDbs.size <= limit) return@withLock
        val usageSnapshot = deviceDbs.associateWith { lastUsed(it) }
        val victims = selectEvictionVictims(deviceDbs, activeDbName, limit, usageSnapshot)

        victims.forEach { name ->
            runCatching {
                // runCatching intentional: best-effort cleanup must not abort on cancellation
                closeCachedDatabase(name)
                deleteDatabase(name)
                datastore.edit { it.remove(lastUsedKey(name)) }
            }
                .onSuccess { Logger.i { "Evicted cached DB ${anonymizeDbName(name)}" } }
                .onFailure { Logger.w(it) { "Failed to evict database ${anonymizeDbName(name)}" } }
        }
    }

    private suspend fun cleanupLegacyDbIfNeeded(activeDbName: String) = mutex.withLock {
        val cleaned = datastore.data.first()[legacyCleanedKey] ?: false
        if (cleaned) return@withLock

        val legacy = DatabaseConstants.LEGACY_DB_NAME
        if (legacy == activeDbName) {
            datastore.edit { it[legacyCleanedKey] = true }
            return@withLock
        }

        if (dbFileExists(legacy)) {
            runCatching {
                // runCatching intentional: best-effort cleanup must not abort on cancellation
                closeCachedDatabase(legacy)
                deleteDatabase(legacy)
            }
                .onSuccess { Logger.i { "Deleted legacy DB ${anonymizeDbName(legacy)}" } }
                .onFailure { Logger.w(it) { "Failed to delete legacy database ${anonymizeDbName(legacy)}" } }
        }
        datastore.edit { it[legacyCleanedKey] = true }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun scheduleSearchIndexBackfill(dbName: String, db: MeshtasticDatabase, shouldDelayBackfill: Boolean) {
        backfillJob?.cancel()
        backfillJob =
            managerScope.launch(dispatchers.io) {
                try {
                    if (shouldDelayBackfill) delay(BACKFILL_COLD_START_DELAY_MS)
                    if (_currentDb.value !== db) return@launch
                    backfillSearchIndexIfNeeded(db)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(e) { "Failed to backfill search index for ${anonymizeDbName(dbName)}" }
                }
            }
    }

    /**
     * Backfills [Packet.messageText] for existing text-message packets that predate the FTS5 schema, then rebuilds the
     * FTS index so search covers historical messages. The text is decoded in Kotlin from each packet's payload (see
     * [PacketDao.backfillMessageTexts]); it cannot be read in SQL because the message body is stored as serialized
     * `bytes`, not a `text` JSON field.
     */
    private suspend fun backfillSearchIndexIfNeeded(db: MeshtasticDatabase) {
        val needsBackfill = db.packetDao().countPacketsNeedingBackfill() > 0
        if (!needsBackfill) return

        // Perform the write operations inside NonCancellable to prevent
        // connection pool leaks due to coroutine cancellation.
        withContext(NonCancellable) {
            val count = db.packetDao().backfillMessageTexts()
            if (count > 0) {
                Logger.i { "Backfilled $count messages for FTS search index" }
                db.packetDao().rebuildFtsIndex()
                Logger.i { "FTS search index rebuild complete" }
            }
        }
    }

    /** Closes all open databases, cancels background work, and physically cleans logically-retired sources. */
    suspend fun close() {
        backfillJob?.cancel()
        backfillJob = null
        managerScope.cancel()
        // Wait for manager-owned jobs, then transfer cache ownership while serialized against external switches and
        // associations. Close outside the mutex so Room teardown cannot block other mutex cleanup.
        managerScope.coroutineContext[Job]?.join()
        val cachedDatabases =
            mutex.withLock {
                val cached = dbCache.values.distinct()
                dbCache.clear()
                cached
            }
        cachedDatabases.forEach { db ->
            runCatching { db.close() }.onFailure { Logger.w(it) { "Failed to close database during shutdown" } }
        }
        // Physically delete logically-retired source databases now that no application consumer should remain.
        // The Room instances were already closed from the ownership snapshot above; this loop removes the
        // orphaned files and their last-used metadata. Idempotent: a later process also cleans these via
        // cache-limit eviction since routing metadata points at the destination.
        mutex.withLock {
            logicallyRetired.forEach { name ->
                runCatching {
                    deleteDatabase(name)
                    datastore.edit { it.remove(lastUsedKey(name)) }
                }
                    .onSuccess { Logger.i { "Physically retired merged DB ${anonymizeDbName(name)}" } }
                    .onFailure { Logger.w(it) { "Failed to physically retire merged DB ${anonymizeDbName(name)}" } }
            }
            logicallyRetired.clear()
        }
    }
}
