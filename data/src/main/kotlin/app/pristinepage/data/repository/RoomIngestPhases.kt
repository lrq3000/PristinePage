package app.pristinepage.data.repository

import android.os.SystemClock
import app.pristinepage.data.db.FileTransactionRow
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.db.ImportSessionRow
import app.pristinepage.data.files.SealedSource
import app.pristinepage.data.journal.IngestJournalCodec
import app.pristinepage.data.model.*
import kotlinx.coroutines.CancellationException

/** Same-database journal participant. The caller holds copy admission outside Room; every execution write additionally checks the monotonic job fence. These methods never open/hash/move files, invoke a provider or decode pixels. */
class RoomIngestPhases @JvmOverloads constructor(private val metadata: MetadataAccess, private val sessions: ImportSessionQueries, private val journal: IngestPreparationQueries, private val jobs: JobLeaseQueries, private val phases: IngestPhaseQueries, private val auditClock: () -> Long = System::currentTimeMillis, private val leaseClock: () -> Long = SystemClock::elapsedRealtime) : IngestPhases {
    constructor(database: PristineDatabase) : this(RoomMetadataAccess(database), database.importSessions(), database.ingestPreparation(), database.jobLeases(), database.ingestPhases())

    override fun state(intent: IngestJournalIntent): FileTransactionState = guarded(intent) { metadata.transaction { FileTransactionState.valueOf(read(intent).state) } }

    override fun admit(intent: IngestJournalIntent): Boolean = guarded(intent) {
        metadata.transaction {
            val row = read(intent)
            if (row.state != "INTENT" || !live(intent)) return@transaction false
            val job = checkNotNull(jobs.job(intent.jobId.value))
            if (job.leaseOwner != null || job.leaseUntil != null) return@transaction false
            if (job.state == "ADMITTED") return@transaction true
            if (job.state != "PENDING") return@transaction false
            jobs.updateLease(job.copy(state = "ADMITTED", updatedAt = auditClock()), job.leaseGeneration) == 1
        }
    }

    override fun copying(intent: IngestJournalIntent, lease: JobLease): Boolean = transition(intent, lease, "INTENT") { it.copy(state = "COPYING", updatedAt = auditClock()) }

    override fun sealed(intent: IngestJournalIntent, lease: JobLease, sealed: SealedSource): Boolean {
        validateSeal(intent, sealed)
        val staged = sealed.staged
        return transition(intent, lease, "COPYING") { it.copy(state = "SEALED", sealSha256 = sealed.sealSha256, expectedByteSha256 = staged.byteSha256, expectedByteCount = staged.byteCount, updatedAt = auditClock()) }
    }

    override fun readmitSealedRetry(intent: IngestJournalIntent, recordedGeneration: Long, sealed: SealedSource): Boolean {
        validateSeal(intent, sealed)
        return guarded(intent) {
            metadata.transaction {
                val row = read(intent); val job = checkNotNull(jobs.job(intent.jobId.value))
                if (row.state != "RETRYABLE" || job.state !in setOf("RETRYABLE", "INTERRUPTED") || recordedGeneration < 1 || job.leaseGeneration != recordedGeneration || job.leaseOwner != null || job.leaseUntil != null || !live(intent)) return@transaction false
                val staged = sealed.staged
                if (row.sealSha256 != null && row.sealSha256 != sealed.sealSha256 || row.expectedByteSha256 != null && row.expectedByteSha256 != staged.byteSha256 || row.expectedByteCount != null && row.expectedByteCount != staged.byteCount) reject(intent, "ingest_seal_binding_mismatch")
                val child = checkNotNull(sessions.session(intent.sessionId.value)); val parent = checkNotNull(child.parentSessionId?.let(sessions::session))
                val pages = metadata.queries.activePages(intent.documentId.value)
                if (pages.size >= 100 || pages.map { it.ordinal } != pages.indices.toList() || ImportMembership.hash(pages) != parent.expectedMembershipToken || metadata.queries.page(intent.pageId.value) != null) return@transaction false
                val now = auditClock()
                // IO may have completed before its retryable failure was journaled. Restore SEALED and ADMITTED together from the actual retained proof; lease acquisition still needs a distinct currently ALIVE runner and increments the generation.
                if (phases.updateIngestPhase(row.copy(state = "SEALED", sealSha256 = sealed.sealSha256, expectedByteSha256 = staged.byteSha256, expectedByteCount = staged.byteCount, errorCode = null, updatedAt = now), "RETRYABLE") != 1) return@transaction false
                if (jobs.updateLease(job.copy(state = "ADMITTED", errorCode = null, updatedAt = now), recordedGeneration) != 1) lost(intent)
                true
            }
        }
    }

    override fun moved(intent: IngestJournalIntent, lease: JobLease): Boolean = transition(intent, lease, "SEALED") {
        if (it.sealSha256 == null || it.expectedByteSha256 == null || it.expectedByteCount == null) reject(intent, "ingest_seal_missing")
        it.copy(state = "MOVED", updatedAt = auditClock())
    }

    override fun failed(intent: IngestJournalIntent, lease: JobLease, error: OperationError): Boolean = guarded(intent) {
        metadata.transaction {
            val row = read(intent)
            val job = JobLeaseFence.current(jobs, lease, leaseClock()) ?: return@transaction false
            if (lease.jobId != intent.jobId || row.state !in setOf("INTENT", "COPYING", "SEALED", "MOVED")) return@transaction false
            val child = sessions.session(intent.sessionId.value) ?: reject(intent, "ingest_session_binding_mismatch")
            val parent = child.parentSessionId?.let(sessions::session) ?: reject(intent, "ingest_session_binding_mismatch")
            if (child.kind != "ITEM" || child.documentId != intent.documentId.value || child.plannedPageId != intent.pageId.value || child.transactionId != intent.transactionId.value || parent.kind != "BATCH" || parent.parentSessionId != null || parent.documentId != child.documentId) reject(intent, "ingest_session_binding_mismatch")
            val terminalStates = setOf("CANCELED", "FAILED", "QUARANTINED")
            if (child.state !in terminalStates && child.state != "INGESTING" || parent.state !in terminalStates && parent.state != "RESERVED") reject(intent, "ingest_session_binding_mismatch")
            // An independent cancellation/terminal session result wins over a later provider error. Do not reopen a canceled item or overwrite its reason while unwinding owned IO.
            val prior = if (parent.state in terminalStates) parent else child.takeIf { it.state in terminalStates }
            val code = prior?.let { if (it.state == "CANCELED") OperationErrorCode.CANCELED else OperationErrorCode.entries.firstOrNull { code -> code.name == it.errorCode } ?: OperationErrorCode.PROCESSING_FAILED } ?: error.code
            val state = prior?.state ?: when { code == OperationErrorCode.CANCELED -> "CANCELED"; error.retryable -> "RETRYABLE"; code == OperationErrorCode.INTEGRITY_FAILED -> "QUARANTINED"; else -> "FAILED" }
            val now = auditClock()
            if (phases.updateIngestPhase(row.copy(state = state, errorCode = code.name, updatedAt = now), row.state) != 1) return@transaction false
            if (state != "RETRYABLE") settleTerminalItem(intent, child, parent, state, code)
            // Throw after the first write on fence/CAS loss: both scheduling and journal outcome must roll back, never only one of them.
            if (JobLeaseFence.current(jobs, lease, leaseClock()) == null || jobs.updateLease(job.copy(state = if (state == "QUARANTINED") "FAILED" else state, leaseOwner = null, leaseUntil = null, errorCode = code.name, updatedAt = now), lease.generation) != 1) lost(intent)
            true
        }
    }

    private fun settleTerminalItem(intent: IngestJournalIntent, child: ImportSessionRow, parent: ImportSessionRow, state: String, code: OperationErrorCode) {
        if (sessions.updateSession(child.copy(state = state, errorCode = code.name, uriText = null)) != 1) reject(intent, "ingest_session_binding_mismatch")
        val children = sessions.children(parent.sessionId)
        if (children.isEmpty() || children.any { it.kind != "ITEM" || it.documentId != intent.documentId.value }) reject(intent, "ingest_session_binding_mismatch")
        if (parent.state == "RESERVED" && children.all { it.state in setOf("COMMITTED", "CANCELED", "FAILED", "QUARANTINED") }) {
            // COMMITTED here means a finished batch with retained successes, not that every item succeeded. Per-item outcomes remain authoritative; an all-failed/all-canceled batch is never called committed.
            val finished = when { children.any { it.state == "COMMITTED" } -> "COMMITTED"; children.all { it.state == "CANCELED" } -> "CANCELED"; else -> "FAILED" }
            if (sessions.updateSession(parent.copy(state = finished, errorCode = if (finished == "COMMITTED") null else code.name)) != 1) reject(intent, "ingest_session_binding_mismatch")
        }
    }

    private fun transition(intent: IngestJournalIntent, lease: JobLease, expected: String, update: (FileTransactionRow) -> FileTransactionRow): Boolean = guarded(intent) {
        metadata.transaction {
            val row = read(intent)
            if (lease.jobId != intent.jobId || JobLeaseFence.current(jobs, lease, leaseClock()) == null || row.state != expected || !live(intent)) return@transaction false
            if (phases.updateIngestPhase(update(row), expected) != 1) return@transaction false
            if (JobLeaseFence.current(jobs, lease, leaseClock()) == null) lost(intent)
            true
        }
    }

    private fun read(intent: IngestJournalIntent): FileTransactionRow {
        val row = journal.fileTransaction(intent.transactionId.value) ?: reject(intent, "ingest_intent_missing")
        val job = jobs.job(intent.jobId.value) ?: reject(intent, "ingest_job_missing")
        val input = IngestJournalCodec.encodeIntent(intent)
        if (row.transactionId != intent.transactionId.value || row.kind != "INGEST" || row.ownerId != intent.sessionId.value || row.attempt != intent.attempt || row.stagingPath != "staging/${intent.transactionId.value}/original.part" || row.finalPath != "sources/${intent.sourceBlobId.value}/original.bin" || (row.state != "COMMITTED" && !row.payload.contentEquals(input)) || job.kind != "RECONCILE" || job.idempotenceKey != IngestJobIdentity.key(intent) || job.canonicalInputSha256 != IngestJobIdentity.hash(input) || !job.input.contentEquals(input) || job.snapshotId != null || job.pageOrdinal != null) reject(intent, "ingest_intent_binding_mismatch")
        return row
    }

    /** Validate bounded already-produced metadata only; actual payload hashing/native proof belongs outside the metadata writer. */
    private fun validateSeal(intent: IngestJournalIntent, sealed: SealedSource) {
        val seal = IngestJournalCodec.decodeSeal(sealed.metadata, intent); val staged = sealed.staged
        if (staged.transactionId != intent.transactionId.value || staged.sourceBlobId != intent.sourceBlobId.value || seal.sourceByteHash != staged.byteSha256 || seal.byteCount != staged.byteCount) reject(intent, "ingest_seal_binding_mismatch")
        app.pristinepage.core.pipeline.requireSha256(sealed.sealSha256)
    }

    private fun live(intent: IngestJournalIntent): Boolean {
        val child = sessions.session(intent.sessionId.value) ?: return false
        val parent = child.parentSessionId?.let(sessions::session) ?: return false
        val doc = metadata.queries.document(intent.documentId.value) ?: return false
        val now = auditClock()
        return child.kind == "ITEM" && child.state == "INGESTING" && child.documentId == intent.documentId.value && child.plannedPageId == intent.pageId.value && child.transactionId == intent.transactionId.value && child.expiresAt > now && parent.kind == "BATCH" && parent.parentSessionId == null && parent.documentId == child.documentId && parent.state == "RESERVED" && parent.expiresAt > now && doc.lifecycle == "ACTIVE"
    }

    private fun reject(intent: IngestJournalIntent, key: String): Nothing = throw RepositoryFailure(OperationError(OperationErrorCode.INTEGRITY_FAILED, false, key, intent.sessionId.value))
    private fun lost(intent: IngestJournalIntent): Nothing = throw RepositoryFailure(OperationError(OperationErrorCode.REVISION_CONFLICT, false, "job_lease_lost", intent.jobId.value))
    private inline fun <T> guarded(intent: IngestJournalIntent, block: () -> T): T = try { block() }
    catch (canceled: CancellationException) { throw canceled }
    catch (failure: RepositoryFailure) { throw failure }
    catch (_: Exception) { throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "ingest_phase_write_failed", intent.sessionId.value)) }
}
