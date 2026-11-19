package app.pristinepage.data.repository

import android.os.SystemClock
import app.pristinepage.core.pipeline.FallbackPolicy
import app.pristinepage.data.db.ExportSnapshotDao
import app.pristinepage.data.db.JobRow
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.RoomExportSnapshots.SnapshotRead
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The deliberate first assembly claim only. Neither the frozen references nor the acquired metadata retain render targets or authorize any file access. */
internal class RoomFreshExportAssembly private constructor(
    private val store: Store,
    private val liveness: LeaseOwnerLiveness,
    private val preflight: suspend (ExportSnapshot, List<JobId>) -> OperationError?,
    private val auditClock: () -> Long = System::currentTimeMillis,
    private val leaseClock: () -> Long = SystemClock::elapsedRealtime,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val lifetime: OrdinaryExportLifetime? = null,
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

    private suspend fun <T> retainIfScoped(id: String, block: suspend () -> T): T {
        val storeLifetime = lifetime
        return if (storeLifetime == null) block() else storeLifetime.retain(id, block)
    }

    /** Only this route can join an authenticated store snapshot with its exact persisted frozen ordinal list. */
    class Prepared private constructor(
        val snapshot: ExportSnapshot,
        val orderedJobIds: List<JobId>,
        val jobId: JobId,
        private val frozen: SnapshotRead,
        private val issuer: Store,
        private val lifetime: OrdinaryExportLifetime?,
    ) {
        private val input = ExportAssemblyInput.encode(snapshot.snapshotId, snapshot.snapshotHash, orderedJobIds)
        val key = ExportAssemblyInput.key(snapshot.snapshotId)
        val digest = ExportAssemblyInput.digest(input)
        fun inputBytes(): ByteArray = input.copyOf()

        fun requireIssuer(service: RoomFreshExportAssembly) {
            if (!issuer.sameIssuer(service.store) || lifetime !== service.lifetime) {
                reject(jobId.value, OperationErrorCode.INTEGRITY_FAILED, "export_assembly_store_mismatch")
            }
        }

        fun requireSameSnapshot(live: SnapshotRead) = frozen.requireSameContent(live)

        internal fun terminalSlot(store: PrivateImportStore.ExportComposition, ordinal: Int): Pair<JobId, ExportPageSnapshot> =
            terminalSlot(issuer is RoomStore && issuer.database === store.database && lifetime === store.lifetime, ordinal)

        @JvmSynthetic
        internal fun terminalSlot(store: SyntheticStore, lifetime: OrdinaryExportLifetime, ordinal: Int): Pair<JobId, ExportPageSnapshot> =
            terminalSlot(issuer === store && this.lifetime === lifetime, ordinal)

        private fun terminalSlot(sameIssuer: Boolean, ordinal: Int): Pair<JobId, ExportPageSnapshot> {
            val page = snapshot.pages.getOrNull(ordinal)
            val job = orderedJobIds.getOrNull(ordinal)
            if (!sameIssuer || page?.ordinal != ordinal || job == null) {
                reject(jobId.value, OperationErrorCode.INTEGRITY_FAILED, "terminal_png_retention_binding_invalid")
            }
            return job to page
        }

        fun matches(row: JobRow): Boolean = row.jobId == jobId.value && row.kind == "EXPORT_ASSEMBLY" &&
            row.snapshotId == snapshot.snapshotId.value && row.pageOrdinal == null && row.idempotenceKey == key &&
            row.canonicalInputSha256 == digest && row.input.contentEquals(input)

        companion object {
            suspend fun prepare(service: RoomFreshExportAssembly, id: ExportSnapshotId): Prepared =
                service.retainIfScoped(id.value) { prepareBody(service, id) }

            private suspend fun prepareBody(service: RoomFreshExportAssembly, id: ExportSnapshotId): Prepared = withContext(service.dispatcher) {
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
                Prepared(snapshot, references, jobId, captured, service.store, service.lifetime).also { coroutineContext.ensureActive() }
            }
        }
    }

    /** An admitted first-attempt row, not a reservation or stable file/target ownership. */
    class Admitted private constructor(val prepared: Prepared, private val row: JobRow) {
        fun matches(current: JobRow): Boolean = sameRow(row, current)

        companion object {
            suspend fun admit(service: RoomFreshExportAssembly, prepared: Prepared): Admitted =
                service.retainIfScoped(prepared.jobId.value) { admitBody(service, prepared) }

            private suspend fun admitBody(service: RoomFreshExportAssembly, prepared: Prepared): Admitted = withContext(service.dispatcher) {
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
    class Acquired private constructor(
        val admitted: Admitted,
        val lease: JobLease,
        private val issuingService: RoomFreshExportAssembly,
        private val running: JobRow,
    ) {
        /** The final reader is constructed privately by the existing retained entry; this is not an arbitrary callback or an attempt handle. */
        @JvmSynthetic
        internal suspend fun readRetainedTerminalSet(reader: RoomTerminalPngReads.Retained.AcquiredSetReader): List<RoomTerminalPngReads.Receipt> =
            reader.read(admitted.prepared, lease, ::checkTerminalSet, ::checkTerminalAttempt, ::copyTransaction, ::finishPrivateSet)

        private fun finishPrivateSet(context: CoroutineContext, completion: RoomPngPrivateCompletion.Set) {
            val service = issuingService
            val prepared = admitted.prepared
            val id = lease.jobId.value
            val active = { context.ensureActive() }
            lateinit var before: SnapshotRead
            var after: SnapshotRead? = null
            JobLeaseTransactions(service.store, service.store, service.auditClock, service.leaseClock).completeGuarded(lease,
                readAdmitted = {
                    if (after != null) {
                        service.readJob(prepared, "SUCCEEDED", running.attempt, active = active, afterWrite = true)
                    } else {
                        service.readJob(prepared, "RUNNING", running.attempt, running.leaseOwner, running.leaseUntil, active).also {
                            if (!sameRow(running, it)) conflict(id)
                        }
                    }
                },
                live = {
                    // Checked again after the exact SUCCEEDED read, before the helper checks the original unconsumed deadline and owning context.
                    after?.requireRetained(service.auditClock())
                    service.liveness.state(lease.owner) == LeaseOwnerState.ALIVE
                },
                active = active,
                commitMetadata = {
                    before = service.liveSnapshot(prepared)
                    completion.write(before.record, service.auditClock) { checkTerminalClocks(before, context) }
                },
                verifyMetadata = { audit ->
                    completion.verify(audit, active)
                    val final = SnapshotRead.capture(service.store, prepared.snapshot.snapshotId, service.auditClock)
                    prepared.requireSameSnapshot(final)
                    if (final.record.state != "PRIVATE_READY" || final.record.retainedUntil != before.record.retainedUntil) invalid(id)
                    after = final
                },
            )
            // Delivery is separate from the transaction: cancellation here cannot roll back saved state.
            active()
        }

        private fun checkTerminalSet(context: CoroutineContext): () -> Unit {
            val service = issuingService
            val prepared = admitted.prepared
            val id = lease.jobId.value
            context.ensureActive()
            val live = service.storage(id) {
                service.store.transaction {
                    val snapshot = service.liveSnapshot(prepared)
                    service.requireJobInput(prepared) { context.ensureActive() }
                    service.requireJobPhase(id, "RUNNING", running.attempt, running.leaseOwner, running.leaseUntil)
                    // Retention must hold after the last bounded projection/comparison, not only at snapshot admission.
                    checkTerminalClocks(snapshot, context)
                    snapshot
                }
            }
            return { checkTerminalClocks(live, context) }
        }

        /** Ordinal boundaries use indexed scalar input/phase checks, not a second full snapshot or Job projection. */
        private fun checkTerminalAttempt(context: CoroutineContext) {
            context.ensureActive()
            val id = lease.jobId.value
            issuingService.storage(id) {
                issuingService.store.transaction {
                    issuingService.requireJobInput(admitted.prepared) { context.ensureActive() }
                    issuingService.requireJobPhase(id, "RUNNING", running.attempt, running.leaseOwner, running.leaseUntil)
                }
            }
            context.ensureActive()
        }

        private fun checkTerminalClocks(snapshot: SnapshotRead, context: CoroutineContext) {
            checkClocks(context, snapshot::requireRetained)
        }

        /** Only the privately formed traversal receives this transaction entry. It runs on the original issuer, never on a caller-selected writer. */
        private fun copyTransaction(context: CoroutineContext, full: Boolean, write: (Long, () -> Unit) -> Unit) {
            val service = issuingService
            val id = lease.jobId.value
            val prepared = admitted.prepared
            context.ensureActive()
            service.storage(id) {
                service.store.transaction {
                    val snapshot = if (full) service.liveSnapshot(prepared) else null
                    val checkpoint = {
                        context.ensureActive()
                        service.requireJobInput(prepared) { context.ensureActive() }
                        service.requireJobPhase(id, "RUNNING", running.attempt, running.leaseOwner, running.leaseUntil)
                        // Every write also checks the live header, including cancellation/retention changes between short phase transactions.
                        val snapshotId = prepared.snapshot.snapshotId.value
                        if (service.store.snapshotMetadataIsBounded(snapshotId) != true) invalid(id)
                        val header = service.store.snapshot(snapshotId) ?: invalid(id)
                        if (header.snapshotId != snapshotId || header.snapshotSha256 != prepared.snapshot.snapshotHash) invalid(id)
                        if (header.state == "CANCELED") reject(id, OperationErrorCode.CANCELED, "export_snapshot_canceled")
                        if (header.state != "FROZEN") reject(id, OperationErrorCode.REVISION_CONFLICT, "export_snapshot_not_fresh")
                        checkClocks(context) { now ->
                            if (snapshot != null) snapshot.requireRetained(now)
                            if (header.retainedUntil <= now) reject(id, OperationErrorCode.SESSION_EXPIRED, "export_snapshot_expired")
                        }
                    }
                    checkpoint()
                    val audit = service.auditClock()
                    if (audit < 0) invalid(id)
                    write(audit, checkpoint)
                    checkpoint()
                }
            }
            context.ensureActive()
        }

        private fun checkClocks(context: CoroutineContext, requireRetained: (Long) -> Unit) {
            context.ensureActive()
            val id = lease.jobId.value
            if (issuingService.liveness.state(lease.owner) != LeaseOwnerState.ALIVE) {
                reject(id, OperationErrorCode.INVALID_INPUT, "job_owner_not_alive")
            }
            // Only the detached, post-CAS observation is used here; each metadata fence must separately match its actual RUNNING phase.
            if (JobLeaseFence.current(running, lease, issuingService.leaseClock()) == null) {
                reject(id, OperationErrorCode.REVISION_CONFLICT, "job_lease_lost")
            }
            requireRetained(issuingService.auditClock())
            context.ensureActive()
        }

        companion object {
            suspend fun acquire(service: RoomFreshExportAssembly, admitted: Admitted, owner: String, durationMillis: Long): Acquired =
                service.retainIfScoped(admitted.prepared.jobId.value) { acquireBody(service, admitted, owner, durationMillis) }

            private suspend fun acquireBody(service: RoomFreshExportAssembly, admitted: Admitted, owner: String, durationMillis: Long): Acquired {
                currentCoroutineContext().ensureActive()
                val prepared = admitted.prepared
                prepared.requireIssuer(service)
                lateinit var live: SnapshotRead
                lateinit var observedRunning: JobRow
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
                        observedRunning = written
                        written
                    },
                )
                currentCoroutineContext().ensureActive()
                return when (result) {
                    is AcquireLeaseResult.Granted -> Acquired(admitted, result.lease, service, observedRunning)
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
        requireJobInput(prepared, active)
        requireJobPhase(id, state, attempt, owner, until, afterWrite)
        active()
        val row = store.job(id) ?: invalid(id)
        if (!prepared.matches(row) || row.state != state || row.attempt != attempt || row.leaseGeneration != attempt ||
            row.leaseOwner != owner || row.leaseUntil != until || row.checkpoint != null || row.errorCode != null
        ) invalid(id)
        return row.copy(input = row.input.copyOf())
    }

    private fun requireJobInput(prepared: Prepared, active: () -> Unit) {
        val id = prepared.jobId.value
        // Separate statements: a scalar equality result does not bound the BLOB comparison, and SQL AND does not promise evaluation order.
        if (store.renderJobIdByKey(prepared.key) != id || store.assemblyBounds(id) != true) invalid(id)
        active()
        if (store.exactInputMatches(id, prepared.snapshot.snapshotId.value, prepared.key, prepared.digest, prepared.inputBytes()) != true) invalid(id)
    }

    private fun requireJobPhase(id: String, state: String, attempt: Long, owner: String?, until: Long?, afterWrite: Boolean = false) {
        if (store.leaseBounds(id) != true) invalid(id)
        if (store.phaseMatches(id, state, attempt, owner, until) != true) {
            if (afterWrite) invalid(id) else conflict(id)
        }
    }

    private fun <T> storage(id: String, action: () -> T): T = try { action() }
    catch (canceled: CancellationException) { throw canceled }
    catch (failure: RepositoryFailure) { throw failure }
    catch (_: Exception) { throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "export_assembly_metadata_failed", id)) }

    companion object {
        internal fun forOrdinaryExport(
            store: PrivateImportStore.ExportComposition, preflight: suspend (ExportSnapshot, List<JobId>) -> OperationError?,
        ): RoomFreshExportAssembly = RoomFreshExportAssembly(RoomStore(store.database), store.owners, preflight, lifetime = store.lifetime)

        @JvmSynthetic
        internal fun forSyntheticTests(
            store: SyntheticStore, liveness: LeaseOwnerLiveness, preflight: suspend (ExportSnapshot, List<JobId>) -> OperationError?,
            auditClock: () -> Long, leaseClock: () -> Long, dispatcher: CoroutineDispatcher, lifetime: OrdinaryExportLifetime? = null,
        ): RoomFreshExportAssembly = RoomFreshExportAssembly(store, liveness, preflight, auditClock, leaseClock, dispatcher, lifetime)

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
