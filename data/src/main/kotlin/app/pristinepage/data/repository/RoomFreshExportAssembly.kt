package app.pristinepage.data.repository

import android.os.SystemClock
import app.pristinepage.core.pipeline.FallbackPolicy
import app.pristinepage.data.db.ExportSnapshotDao
import app.pristinepage.data.db.JobRow
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.RoomExportSnapshots.SnapshotRead
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The deliberate first assembly claim only. Neither the frozen references nor the acquired metadata retain render targets or authorize any file access. */
internal class RoomFreshExportAssembly private constructor(
    private val store: Store,
    liveness: LeaseOwnerLiveness,
    private val preflight: suspend (ExportSnapshot, List<JobId>) -> OperationError?,
    private val auditClock: () -> Long = System::currentTimeMillis,
    leaseClock: () -> Long = SystemClock::elapsedRealtime,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(database: PristineDatabase, liveness: LeaseOwnerLiveness, preflight: suspend (ExportSnapshot, List<JobId>) -> OperationError?) : this(
        RoomStore(database), liveness, preflight,
    )

    private val assembly = RoomExportAssemblyReads(store, store::assemblyBounds, store::assemblyIdentity, dispatcher, store::renderJobIdByKey)
    private val leases = RoomJobLeases(store, store, liveness, auditClock, leaseClock, dispatcher)

    /** All reads and writes, including the lease CAS, run in this participant's transaction. */
    sealed class Store : MetadataAccess, ExportSnapshotQueries, JobLeaseQueries {
        abstract fun assemblyBounds(id: String): Boolean?
        abstract fun assemblyIdentity(id: String): AssemblyJobIdentityRow?
        abstract fun exactInputMatches(id: String, snapshot: String, key: String, digest: String, input: ByteArray): Boolean?
        abstract fun leaseBounds(id: String): Boolean?
        abstract fun phaseMatches(id: String, state: String, attempt: Long, owner: String?, until: Long?): Boolean?
        abstract fun admitPending(id: String, snapshot: String, key: String, digest: String, input: ByteArray, previousUpdatedAt: Long, updatedAt: Long): Int

        // Use exact database identity for independent Room adapters, and exact participant identity for host fixtures.
        fun sameIssuer(other: Store): Boolean =
            this === other || (this is RoomStore && other is RoomStore && database === other.database)
    }

    private class RoomStore private constructor(val database: PristineDatabase, private val dao: ExportSnapshotDao) :
        Store(), MetadataAccess by RoomMetadataAccess(database), ExportSnapshotQueries by dao, JobLeaseQueries by database.jobLeases() {
        constructor(database: PristineDatabase) : this(database, database.exportSnapshots())
        override fun assemblyBounds(id: String) = dao.assemblyJobReadIsBounded(id)
        override fun assemblyIdentity(id: String) = dao.assemblyJobIdentity(id)
        override fun exactInputMatches(id: String, snapshot: String, key: String, digest: String, input: ByteArray) =
            dao.freshAssemblyInputMatches(id, snapshot, key, digest, input)
        override fun leaseBounds(id: String) = dao.renderJobLeaseReadIsBounded(id)
        override fun phaseMatches(id: String, state: String, attempt: Long, owner: String?, until: Long?) =
            dao.freshAssemblyPhaseMatches(id, state, attempt, owner, until)
        override fun admitPending(id: String, snapshot: String, key: String, digest: String, input: ByteArray, previousUpdatedAt: Long, updatedAt: Long) =
            dao.admitPendingAssembly(id, snapshot, key, digest, input, previousUpdatedAt, updatedAt)
    }

    /** Host tests supply one coherent synthetic transaction participant, never independently injectable production DAOs. */
    abstract class SyntheticStore : Store()

    suspend fun prepare(snapshotId: ExportSnapshotId): Prepared = Prepared.prepare(this, snapshotId)
    suspend fun admit(prepared: Prepared): Admitted = Admitted.admit(this, prepared)
    suspend fun acquire(admitted: Admitted, owner: String, durationMillis: Long): Acquired =
        Acquired.acquire(this, admitted, owner, durationMillis)

    /** Only this route can join an authenticated store snapshot with its exact persisted frozen ordinal list. */
    class Prepared private constructor(
        val snapshot: ExportSnapshot,
        val orderedJobIds: List<JobId>,
        val jobId: JobId,
        private val frozen: SnapshotRead,
        private val issuer: Store,
    ) {
        private val input = ExportAssemblyInput.encode(snapshot.snapshotId, snapshot.snapshotHash, orderedJobIds)
        val key = ExportAssemblyInput.key(snapshot.snapshotId)
        val digest = ExportAssemblyInput.digest(input)
        fun inputBytes(): ByteArray = input.copyOf()

        fun requireIssuer(service: RoomFreshExportAssembly) {
            if (!issuer.sameIssuer(service.store)) {
                reject(jobId.value, OperationErrorCode.INTEGRITY_FAILED, "export_assembly_store_mismatch")
            }
        }

        fun requireSameSnapshot(live: SnapshotRead) = frozen.requireSameContent(live)

        fun matches(row: JobRow): Boolean = row.jobId == jobId.value && row.kind == "EXPORT_ASSEMBLY" &&
            row.snapshotId == snapshot.snapshotId.value && row.pageOrdinal == null && row.idempotenceKey == key &&
            row.canonicalInputSha256 == digest && row.input.contentEquals(input)

        companion object {
            suspend fun prepare(service: RoomFreshExportAssembly, id: ExportSnapshotId): Prepared = withContext(service.dispatcher) {
                coroutineContext.ensureActive()
                val captured = service.storage(id.value) {
                    service.store.transaction { service.capture(id, detach = true) }
                }
                coroutineContext.ensureActive()
                val snapshot = service.storage(id.value, captured::authenticate)
                // Metadata prefilter only; the render proof owns exact ordinary descriptor/recipe validation before terminal I/O.
                if (snapshot.settings.format != ExportFormat.PNG || snapshot.settings.acceptedDegraded.isNotEmpty() ||
                    snapshot.pages.any { it.fallbackPolicy != FallbackPolicy.FAIL || !it.masks.contentEquals("[]".encodeToByteArray()) ||
                        it.assetSetHash != OrdinaryExportProfile.emptyAssetSetHash }
                ) reject(id.value, OperationErrorCode.PROVIDER_UNAVAILABLE, "export_assembly_ordinary_png_required")
                val (jobId, references) = service.assembly.loadForSnapshot(snapshot)
                Prepared(snapshot, references, jobId, captured, service.store).also { coroutineContext.ensureActive() }
            }
        }
    }

    /** An admitted first-attempt row, not a reservation or stable file/target ownership. */
    class Admitted private constructor(val prepared: Prepared, private val row: JobRow) {
        fun matches(current: JobRow): Boolean = sameRow(row, current)

        companion object {
            suspend fun admit(service: RoomFreshExportAssembly, prepared: Prepared): Admitted = withContext(service.dispatcher) {
                val active = { coroutineContext.ensureActive() }
                active()
                prepared.requireIssuer(service)
                val refusal = service.preflight(prepared.snapshot, prepared.orderedJobIds)
                active()
                refusal?.let { throw RepositoryFailure(it) }
                val id = prepared.jobId.value
                service.storage(id) {
                    service.store.transaction {
                        active()
                        val live = service.liveSnapshot(prepared)
                        val row = service.readJob(prepared, "PENDING", active = active)
                        val updatedAt = service.auditClock()
                        live.requireRetained(updatedAt)
                        active()
                        when (service.store.admitPending(id, prepared.snapshot.snapshotId.value, prepared.key, prepared.digest,
                            prepared.inputBytes(), row.updatedAt, updatedAt)) {
                            0 -> conflict(id)
                            1 -> Unit
                            else -> invalid(id)
                        }
                        val written = service.readJob(prepared, "ADMITTED", active = active, afterWrite = true)
                        if (!sameRow(row.copy(state = "ADMITTED", updatedAt = updatedAt), written)) invalid(id)
                        service.requireRetainedAfterFullRead(prepared)
                        active()
                        Admitted(prepared, written)
                    }
                }.also { active() } // Cancellation after commit prevents delivery, not the already durable write.
            }
        }
    }

    /** Carries the original admission and exact result of its guarded lease write. No upgrade from a separately obtained bare lease. */
    class Acquired private constructor(val admitted: Admitted, val lease: JobLease) {
        companion object {
            suspend fun acquire(service: RoomFreshExportAssembly, admitted: Admitted, owner: String, durationMillis: Long): Acquired {
                currentCoroutineContext().ensureActive()
                val prepared = admitted.prepared
                prepared.requireIssuer(service)
                lateinit var live: SnapshotRead
                val result = service.leases.acquireGuarded(prepared.jobId, owner, durationMillis,
                    read = { active ->
                        active()
                        live = service.liveSnapshot(prepared)
                        service.readJob(prepared, "ADMITTED", active = active).also {
                            if (!admitted.matches(it)) conflict(prepared.jobId.value)
                        }
                    },
                    beforeWrite = { running -> live.requireRetained(running.updatedAt) },
                    afterWrite = { running, active ->
                        val written = service.readJob(prepared, "RUNNING", running.attempt, running.leaseOwner, running.leaseUntil, active, afterWrite = true)
                        if (!sameRow(running, written)) invalid(prepared.jobId.value)
                        service.requireRetainedAfterFullRead(prepared)
                        written
                    },
                )
                currentCoroutineContext().ensureActive()
                return when (result) {
                    is AcquireLeaseResult.Granted -> Acquired(admitted, result.lease)
                    is AcquireLeaseResult.Busy -> conflict(prepared.jobId.value)
                    is AcquireLeaseResult.Rejected -> throw RepositoryFailure(result.error)
                }
            }
        }
    }

    private fun capture(id: ExportSnapshotId, detach: Boolean = false): SnapshotRead =
        SnapshotRead.capture(store, id, auditClock, freshOnly = true, detach = detach)

    private fun liveSnapshot(prepared: Prepared): SnapshotRead =
        capture(prepared.snapshot.snapshotId).also(prepared::requireSameSnapshot)

    private fun requireRetainedAfterFullRead(prepared: Prepared) {
        val live = liveSnapshot(prepared)
        // capture checks before page materialization; equality can be crossed during the projection or byte comparison.
        live.requireRetained(auditClock())
    }

    /** Exact indexed recheck, scalar input/lease/phase bounds, then the full row that the existing writer actually uses. */
    private fun readJob(
        prepared: Prepared, state: String, attempt: Long = 0, owner: String? = null, until: Long? = null,
        active: () -> Unit, afterWrite: Boolean = false,
    ): JobRow {
        val id = prepared.jobId.value
        // Separate statements: a scalar equality result does not bound the BLOB comparison, and SQL AND does not promise evaluation order.
        if (store.renderJobIdByKey(prepared.key) != id || store.assemblyBounds(id) != true) invalid(id)
        active()
        if (store.exactInputMatches(id, prepared.snapshot.snapshotId.value, prepared.key, prepared.digest, prepared.inputBytes()) != true ||
            store.leaseBounds(id) != true) invalid(id)
        if (store.phaseMatches(id, state, attempt, owner, until) != true) {
            if (afterWrite) invalid(id) else conflict(id)
        }
        active()
        val row = store.job(id) ?: invalid(id)
        if (!prepared.matches(row) || row.state != state || row.attempt != attempt || row.leaseGeneration != attempt ||
            row.leaseOwner != owner || row.leaseUntil != until || row.checkpoint != null || row.errorCode != null
        ) invalid(id)
        return row.copy(input = row.input.copyOf())
    }

    private fun <T> storage(id: String, action: () -> T): T = try { action() }
    catch (canceled: CancellationException) { throw canceled }
    catch (failure: RepositoryFailure) { throw failure }
    catch (_: Exception) { throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "export_assembly_metadata_failed", id)) }

    companion object {
        @JvmSynthetic
        internal fun forSyntheticTests(
            store: SyntheticStore, liveness: LeaseOwnerLiveness, preflight: suspend (ExportSnapshot, List<JobId>) -> OperationError?,
            auditClock: () -> Long, leaseClock: () -> Long, dispatcher: CoroutineDispatcher,
        ): RoomFreshExportAssembly = RoomFreshExportAssembly(store, liveness, preflight, auditClock, leaseClock, dispatcher)

        private fun sameRow(expected: JobRow, actual: JobRow): Boolean =
            expected == actual.copy(input = expected.input) && expected.input.contentEquals(actual.input)

        private fun invalid(id: String): Nothing =
            reject(id, OperationErrorCode.INTEGRITY_FAILED, "export_assembly_job_metadata_invalid")

        private fun conflict(id: String): Nothing =
            reject(id, OperationErrorCode.REVISION_CONFLICT, "export_assembly_first_attempt_unavailable")

        private fun reject(id: String, code: OperationErrorCode, key: String): Nothing =
            throw RepositoryFailure(OperationError(code, false, key, id))
    }
}
