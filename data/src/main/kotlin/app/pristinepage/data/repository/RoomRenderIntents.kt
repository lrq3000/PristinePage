package app.pristinepage.data.repository

import android.os.SystemClock
import app.pristinepage.core.pipeline.requireSha256
import app.pristinepage.data.db.FileTransactionRow
import app.pristinepage.data.db.JobRow
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.db.RenderedPageRow
import app.pristinepage.data.model.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** New bounded SQL for this consumer only; do not substitute an unguarded FileTransaction/Job entity read. */
internal interface RenderIntentQueries {
    fun renderJobLeaseReadIsBounded(jobId: String): Boolean?
    fun renderIntentReadIsBounded(jobId: String, attempt: Long, expectedPayloadBytes: Int): Boolean?
    fun renderIntentHasMalformedAttempts(jobId: String): Boolean
    fun renderIntentRow(jobId: String, attempt: Long): FileTransactionRow?
    fun insertRenderIntent(row: FileTransactionRow)
    fun markRenderIntentCopying(transactionId: String, jobId: String, attempt: Long, updatedAt: Long): Int
    fun advanceRenderFilePhase(previous: FileTransactionRow, next: FileTransactionRow): Int
}

internal data class RenderSealIdentity(val sealSha256: String, val byteSha256: String, val byteCount: Long)

internal data class RenderFilePhase(val state: FileTransactionState, val updatedAt: Long, val seal: RenderSealIdentity?)

/** The caller retains the already verified frozen owners and the acquired same-boot runner scope. Invoke outside other transactions. No method touches files, changes job state, or admits resource usage. */
internal class RoomRenderIntents(
    private val metadata: MetadataAccess,
    private val jobs: JobLeaseQueries,
    private val journal: RenderIntentQueries,
    private val jobBounds: (String, Int) -> Boolean?,
    private val pinSourceIds: (String) -> List<String>,
    private val liveness: LeaseOwnerLiveness,
    private val auditClock: () -> Long = System::currentTimeMillis,
    private val leaseClock: () -> Long = SystemClock::elapsedRealtime,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(database: PristineDatabase, liveness: LeaseOwnerLiveness) : this(
        RoomMetadataAccess(database), database.jobLeases(), database.exportSnapshots(),
        database.exportSnapshots()::renderJobReadIsBounded, database.exportSnapshots()::boundedRenderJobPinSourceIds,
        liveness,
    )

    /** Exact same-attempt replay returns the original IDs even if copying has already started. It does not grant another copy. */
    suspend fun prepare(proof: RoomFrozenRenderReads.Verified, lease: JobLease): RenderFileIntent = guarded(proof, lease) { active ->
        val existing = read(proof, lease)
        if (existing != null) {
            val intent = authenticate(proof, lease, existing)
            active()
            requireCurrent(proof, lease)
            intent
        } else {
            // An INTEGER-key miss cannot see malformed siblings on v1 SQLite. Never allocate around ambiguous owner history.
            if (journal.renderIntentHasMalformedAttempts(lease.jobId.value)) corrupt(lease)
            val intent = bind(proof, lease, TransactionId(newId()), RenderId(newId()))
            val row = FileTransactionRow(intent.transactionId.value, "RENDER", lease.jobId.value, "INTENT",
                intent.stagingPath, intent.finalPath, null, null, null, intent.journalPayload(), intent.attempt, null, auditClock())
            active()
            journal.insertRenderIntent(row)
            finish(proof, lease, intent, "INTENT", row.updatedAt, active)
            intent
        }
    }

    /** Only the successful INTENT -> COPYING commit grants permission to start this attempt's writer. A caller must still retain exclusive ownership and perform file IO separately. */
    suspend fun copying(proof: RoomFrozenRenderReads.Verified, lease: JobLease, intent: RenderFileIntent) = guarded(proof, lease) { active ->
        if (intent.jobId != lease.jobId || intent.leaseOwner != lease.owner || intent.generation != lease.generation || intent.attempt != lease.generation) lost(lease)
        val row = read(proof, lease) ?: lost(lease)
        if (authenticate(proof, lease, row) != intent) corrupt(lease)
        if (row.state != "INTENT") lost(lease)
        val now = auditClock()
        active()
        if (journal.markRenderIntentCopying(intent.transactionId.value, lease.jobId.value, intent.attempt, now) != 1) lost(lease)
        finish(proof, lease, intent, "COPYING", now, active)
    }

    internal class ExecutionAdmission(val phase: RenderFilePhase, val checkpoint: () -> Unit)

    /** Consume the verifier's exact live MOVED borrow. Only JobLeaseTransactions starts a transaction; these same-DB participants do bounded SQL and local checks only. */
    internal fun commitMoved(
        moved: RoomRenderFileExecution.VerifiedMoved, records: RenderCompletionQueries,
    ) {
        moved.claim(this)
        val (proof, lease, intent) = Triple(moved.proof, moved.lease, moved.intent)
        val seal = checkNotNull(moved.phase.seal)
        val execution = moved.execution
        if (!execution.matches(proof, moved.rasterSha256)) corrupt(lease)
        val ready = RenderedPageRow(intent.renderId.value, intent.jobId.value, intent.sourceId.value, intent.pageId.value, intent.recipeRevision,
            execution.cacheKey, execution.bytes(), execution.sha256, intent.finalPath, seal.byteSha256, moved.rasterSha256,
            seal.byteCount, execution.size.width, execution.size.height, "OK", "READY")
        val active = moved.active
        JobLeaseTransactions(metadata, jobs, auditClock, leaseClock).completeGuarded(lease,
            readAdmitted = {
                val row = readJob(proof, lease)
                if (row.attempt != lease.generation || pinSourceIds(lease.jobId.value).singleOrNull() != proof.pinSourceId) corrupt(lease)
                row
            },
            live = { liveness.state(lease.owner) == LeaseOwnerState.ALIVE }, active = active,
            commitMetadata = {
                val before = read(proof, lease) ?: lost(lease)
                if (executionBinding(proof, lease, intent, before) != moved.phase) lost(lease)
                if (records.renderCompletionOccupied(ready.renderId, ready.jobId, ready.cacheKey, ready.relativePath)) corrupt(lease)
                val updatedAt = auditClock()
                active()
                records.insertReadyRender(ready)
                active()
                if (records.commitMovedRender(before, updatedAt) != 1) lost(lease)
                before.copy(state = "COMMITTED", updatedAt = updatedAt)
            },
            verifyMetadata = { expected ->
                val written = read(proof, lease) ?: corrupt(lease)
                if (!sameJournal(written, expected) || !records.readyRenderMatches(ready)) corrupt(lease)
            },
        )
    }

    /** Synchronous on the executor's IO context. The returned checkpoint captures only the bounded admitted row; it does not query Room. */
    fun executionPhase(
        proof: RoomFrozenRenderReads.Verified, lease: JobLease, intent: RenderFileIntent, active: () -> Unit,
        expected: RenderFilePhase? = null, advanceTo: FileTransactionState? = null, seal: RenderSealIdentity? = null,
    ): ExecutionAdmission = transact(proof, lease, active) {
        val before = read(proof, lease) ?: lost(lease)
        val phase = executionBinding(proof, lease, intent, before)
        if (expected != null && phase != expected) lost(lease)
        if (advanceTo == null) {
            ExecutionAdmission(phase, requireCurrent(proof, lease, active))
        } else {
            // Readback must match every field (including the v1 payload bytes). Throw inside the transaction on any failure after CAS.
            checkNotNull(expected)
            val after = before.copy(state = advanceTo.name, updatedAt = auditClock(), sealSha256 = seal?.sealSha256,
                expectedByteSha256 = seal?.byteSha256, expectedByteCount = seal?.byteCount)
            requireCurrent(proof, lease, active)
            if (journal.advanceRenderFilePhase(before, after) != 1) lost(lease)
            val stored = read(proof, lease) ?: corrupt(lease)
            if (!sameJournal(stored, after)) corrupt(lease)
            val written = executionBinding(proof, lease, intent, stored)
            ExecutionAdmission(written, requireCurrent(proof, lease, active))
        }
    }

    private fun executionBinding(
        proof: RoomFrozenRenderReads.Verified, lease: JobLease, intent: RenderFileIntent, row: FileTransactionRow,
    ): RenderFilePhase {
        if (authenticateBinding(proof, lease, row) != intent) corrupt(lease)
        val seal = when (row.state) {
            "INTENT", "COPYING" -> {
                requireUnsealed(lease, row)
                null
            }
            "SEALED", "MOVED" -> {
                val count = row.expectedByteCount ?: corrupt(lease)
                if (row.errorCode != null || count <= 0) corrupt(lease)
                val sealHash = row.sealSha256 ?: corrupt(lease)
                val byteHash = row.expectedByteSha256 ?: corrupt(lease)
                try {
                    requireSha256(sealHash)
                    requireSha256(byteHash)
                } catch (_: IllegalArgumentException) {
                    corrupt(lease)
                }
                RenderSealIdentity(sealHash, byteHash, count)
            }
            "CANCELED" -> canceled(lease)
            "COMMITTED", "RETRYABLE", "QUARANTINED", "FAILED" -> lost(lease)
            else -> corrupt(lease)
        }
        return RenderFilePhase(FileTransactionState.valueOf(row.state), row.updatedAt, seal)
    }

    private suspend fun <T> guarded(proof: RoomFrozenRenderReads.Verified, lease: JobLease, block: (() -> Unit) -> T): T = withContext(dispatcher) {
        val active: () -> Unit = { coroutineContext.ensureActive() }
        transact(proof, lease, active) { block(active) }
    }

    private fun <T> transact(proof: RoomFrozenRenderReads.Verified, lease: JobLease, active: () -> Unit, block: () -> T): T =
        try {
            active()
            if (proof.jobId != lease.jobId) lost(lease)
            metadata.transaction {
                requireCurrent(proof, lease, active)
                block()
            }
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (failure: RepositoryFailure) {
            throw failure
        } catch (_: Exception) {
            refuse(lease, OperationErrorCode.STORAGE_FAILED, "render_intent_storage_failed", retryable = true)
        }

    private fun readJob(proof: RoomFrozenRenderReads.Verified, lease: JobLease): JobRow {
        val id = lease.jobId.value
        when (jobBounds(id, proof.inputBytes)) {
            null -> lost(lease)
            false -> corrupt(lease)
            true -> Unit
        }
        if (journal.renderJobLeaseReadIsBounded(id) != true) corrupt(lease)
        val row = jobs.job(id) ?: corrupt(lease)
        if (!proof.matches(row)) corrupt(lease)
        if (row.state == "CANCELED") canceled(lease)
        return row
    }

    private fun requireCurrent(proof: RoomFrozenRenderReads.Verified, lease: JobLease, active: () -> Unit = {}): () -> Unit {
        val row = readJob(proof, lease)
        val pinMatches = pinSourceIds(lease.jobId.value).singleOrNull() == proof.pinSourceId
        // Sample after the last SQL read, but report stale lease before pin/attempt mismatches. Room COMMIT still follows this sample.
        val checkpoint: () -> Unit = {
            if (liveness.state(lease.owner) != LeaseOwnerState.ALIVE) lost(lease)
            if (JobLeaseFence.current(row, lease, leaseClock()) == null) lost(lease)
            active()
        }
        checkpoint()
        // The ordinary producer initializes both counters to zero; the unchanged grant advances both together. No other attempt is carried by a JobLease value.
        if (row.attempt != lease.generation || !pinMatches) corrupt(lease)
        return checkpoint
    }

    private fun sameJournal(actual: FileTransactionRow, expected: FileTransactionRow): Boolean =
        actual.payload.contentEquals(expected.payload) && actual.copy(payload = expected.payload) == expected

    private fun read(proof: RoomFrozenRenderReads.Verified, lease: JobLease): FileTransactionRow? {
        val attempt = lease.generation
        return when (journal.renderIntentReadIsBounded(proof.jobId.value, attempt, RenderFileIntent.payloadBytes)) {
            null -> null
            false -> corrupt(lease)
            true -> journal.renderIntentRow(proof.jobId.value, attempt) ?: corrupt(lease)
        }
    }

    private fun authenticate(proof: RoomFrozenRenderReads.Verified, lease: JobLease, row: FileTransactionRow): RenderFileIntent {
        val intent = authenticateBinding(proof, lease, row)
        when (row.state) {
            "CANCELED" -> canceled(lease)
            "INTENT", "COPYING" -> requireUnsealed(lease, row)
            "SEALED", "MOVED", "COMMITTED", "RETRYABLE", "QUARANTINED", "FAILED" -> lost(lease)
            else -> corrupt(lease)
        }
        return intent
    }

    private fun requireUnsealed(lease: JobLease, row: FileTransactionRow) {
        if (row.sealSha256 != null || row.expectedByteSha256 != null || row.expectedByteCount != null || row.errorCode != null) corrupt(lease)
    }

    private fun authenticateBinding(proof: RoomFrozenRenderReads.Verified, lease: JobLease, row: FileTransactionRow): RenderFileIntent {
        val intent = try {
            val path = checkNotNull(row.finalPath)
            bind(proof, lease, TransactionId(row.transactionId), RenderId(path.removePrefix("renders/").removeSuffix("/page.bin")))
        } catch (_: IllegalArgumentException) {
            corrupt(lease)
        } catch (_: IllegalStateException) {
            corrupt(lease)
        }
        if (row.kind != "RENDER" || row.ownerId != lease.jobId.value || row.attempt != intent.attempt ||
            row.stagingPath != intent.stagingPath || row.finalPath != intent.finalPath || !row.payload.contentEquals(intent.journalPayload())) corrupt(lease)
        return intent
    }

    private fun bind(proof: RoomFrozenRenderReads.Verified, lease: JobLease, transactionId: TransactionId, renderId: RenderId): RenderFileIntent {
        val page = proof.page
        return RenderFileIntent(transactionId, renderId, proof.jobId, lease.owner, lease.generation, lease.generation,
            proof.canonicalInputSha256, page.pageId, page.sourceId, page.sourceBlobId, SourceId(proof.pinSourceId),
            page.byteHash, page.pixelHash, page.recipeRevision, page.recipeHash, page.output)
    }

    private fun finish(proof: RoomFrozenRenderReads.Verified, lease: JobLease, intent: RenderFileIntent, state: String, updatedAt: Long, active: () -> Unit) {
        // Throw inside Room after either write. Returning failure instead would commit the first write on expiry, cancel, or lost exact binding.
        val row = read(proof, lease) ?: corrupt(lease)
        if (authenticate(proof, lease, row) != intent || row.state != state || row.updatedAt != updatedAt) corrupt(lease)
        requireCurrent(proof, lease)
        active()
    }

    private fun corrupt(lease: JobLease): Nothing = refuse(lease, OperationErrorCode.INTEGRITY_FAILED, "render_intent_binding_invalid")
    private fun lost(lease: JobLease): Nothing = refuse(lease, OperationErrorCode.REVISION_CONFLICT, "render_intent_not_current")
    private fun canceled(lease: JobLease): Nothing = refuse(lease, OperationErrorCode.CANCELED, "render_intent_canceled")
    private fun refuse(lease: JobLease, code: OperationErrorCode, key: String, retryable: Boolean = false): Nothing =
        throw RepositoryFailure(OperationError(code, retryable, key, lease.jobId.value))
}
