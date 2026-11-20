package app.pristinepage.data.repository

import app.pristinepage.data.db.FileTransactionRow
import app.pristinepage.data.db.JobRow
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.journal.IngestJournalCodec
import app.pristinepage.data.model.*
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** Creates a fresh exclusive-copy identity while preserving the old journal/files and reserved page. No file IO, provider open, native proof refresh or resource admission occurs here; the consuming recovery must establish the unsealed-file precondition outside Room first. */
class RoomIngestAttempts @JvmOverloads constructor(private val metadata: MetadataAccess, private val sessions: ImportSessionQueries, private val journal: IngestPreparationQueries, private val jobs: JobLeaseQueries, private val phases: IngestPhaseQueries, private val liveness: LeaseOwnerLiveness, private val auditClock: () -> Long = System::currentTimeMillis) : IngestAttempts {
    constructor(database: PristineDatabase, liveness: LeaseOwnerLiveness) : this(RoomMetadataAccess(database), database.importSessions(), database.ingestPreparation(), database.jobLeases(), database.ingestPhases(), liveness)

    override fun restartUnsealed(intent: IngestJournalIntent, recordedGeneration: Long, recordedOwner: String?): IngestJournalIntent? {
        try {
            require(recordedGeneration >= 0); recordedOwner?.let(::requireOpaqueId)
            return metadata.transaction {
                val row = journal.fileTransaction(intent.transactionId.value) ?: reject(intent, "ingest_intent_missing")
                val job = jobs.job(intent.jobId.value) ?: reject(intent, "ingest_job_missing")
                if (row.state !in setOf("COPYING", "RETRYABLE", "QUARANTINED")) return@transaction null
                validate(intent, row, job)
                if (job.leaseGeneration != recordedGeneration) return@transaction null
                val child = sessions.session(intent.sessionId.value) ?: return@transaction null
                val parent = child.parentSessionId?.let(sessions::session) ?: return@transaction null
                val doc = metadata.queries.document(intent.documentId.value) ?: return@transaction null
                val now = auditClock()
                if (child.kind != "ITEM" || child.state != "INGESTING" || child.documentId != intent.documentId.value || child.plannedPageId != intent.pageId.value || child.selectionOrdinal !in 0..99 || child.uriText == null || child.expiresAt <= now || parent.kind != "BATCH" || parent.parentSessionId != null || parent.documentId != child.documentId || parent.state != "RESERVED" || parent.expiresAt <= now || doc.lifecycle != "ACTIVE") return@transaction null
                val pages = metadata.queries.activePages(doc.documentId)
                if (pages.size >= 100 || pages.map { it.ordinal } != pages.indices.toList() || ImportMembership.hash(pages) != parent.expectedMembershipToken || metadata.queries.page(intent.pageId.value) != null) return@transaction null
                // A duplicate request can observe the one immediate successor, but must never change a newer session pointer or allocate another generation. Full source completion/replay remains its separate consumer's responsibility.
                if (child.transactionId != intent.transactionId.value) {
                    if (row.state != "QUARANTINED" || job.state != "INTERRUPTED" || job.leaseOwner != null || job.leaseUntil != null) return@transaction null
                    val current = child.transactionId?.let(journal::fileTransaction) ?: return@transaction null
                    if (current.state !in setOf("INTENT", "COPYING", "SEALED", "MOVED", "RETRYABLE")) return@transaction null
                    val next = IngestJournalCodec.decodeIntent(current.payload)
                    if (intent.attempt == Long.MAX_VALUE || next.attempt != intent.attempt + 1 || next.sessionId != intent.sessionId || next.documentId != intent.documentId || next.pageId != intent.pageId) return@transaction null
                    validate(next, current, jobs.job(next.jobId.value) ?: reject(intent, "ingest_job_missing"))
                    return@transaction next
                }
                if (row.state == "QUARANTINED" || row.sealSha256 != null || row.expectedByteSha256 != null || row.expectedByteCount != null) return@transaction null
                if (recordedOwner == null) {
                    if (job.state !in setOf("RETRYABLE", "INTERRUPTED") || job.leaseOwner != null || job.leaseUntil != null) return@transaction null
                } else {
                    if (recordedGeneration < 1 || job.state !in setOf("RUNNING", "CHECKPOINTED") || job.leaseOwner != recordedOwner || job.leaseUntil == null || liveness.state(recordedOwner) != LeaseOwnerState.ENDED) return@transaction null
                }
                val attempt = try { Math.addExact(intent.attempt, 1) }
                catch (_: ArithmeticException) { throw RepositoryFailure(OperationError(OperationErrorCode.RESOURCE_LIMIT, false, "ingest_attempt_limit", intent.sessionId.value)) }
                val next = IngestJournalIntent(TransactionId(id()), intent.sessionId, intent.documentId, intent.pageId, SourceBlobId(id()), SourceId(id()), JobId(id()), attempt)
                // QUARANTINED retires unvalidated evidence in place; this is not a file move, byte-corruption verdict or cancellation of the user's ITEM. CAS failure after either write aborts the entire metadata transaction.
                if (phases.updateIngestPhase(row.copy(state = "QUARANTINED", updatedAt = now), row.state) != 1) lost(intent)
                if (jobs.updateLease(job.copy(state = "INTERRUPTED", leaseOwner = null, leaseUntil = null, updatedAt = now), recordedGeneration) != 1) lost(intent)
                insertIngestIntent(metadata, journal, next, now)
                if (sessions.updateSession(child.copy(transactionId = next.transactionId.value, errorCode = null)) != 1) lost(intent)
                next
            }
        } catch (canceled: CancellationException) { throw canceled }
        catch (failure: RepositoryFailure) { throw failure }
        catch (_: IllegalArgumentException) { reject(intent, "invalid_ingest_attempt") }
        catch (_: Exception) { throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "ingest_attempt_write_failed", intent.sessionId.value)) }
    }

    private fun validate(intent: IngestJournalIntent, row: FileTransactionRow, job: JobRow) {
        val input = IngestJournalCodec.encodeIntent(intent)
        if (row.transactionId != intent.transactionId.value || row.kind != "INGEST" || row.ownerId != intent.sessionId.value || row.attempt != intent.attempt || row.stagingPath != "staging/${intent.transactionId.value}/original.part" || row.finalPath != "sources/${intent.sourceBlobId.value}/original.bin" || !row.payload.contentEquals(input) || job.jobId != intent.jobId.value || job.kind != "RECONCILE" || job.idempotenceKey != IngestJobIdentity.key(intent) || job.canonicalInputSha256 != IngestJobIdentity.hash(input) || !job.input.contentEquals(input) || job.snapshotId != null || job.pageOrdinal != null) reject(intent, "ingest_attempt_binding_mismatch")
    }
    private fun id() = UUID.randomUUID().toString()
    private fun reject(intent: IngestJournalIntent, key: String): Nothing = throw RepositoryFailure(OperationError(OperationErrorCode.INTEGRITY_FAILED, false, key, intent.sessionId.value))
    private fun lost(intent: IngestJournalIntent): Nothing = throw RepositoryFailure(OperationError(OperationErrorCode.REVISION_CONFLICT, false, "ingest_attempt_changed", intent.sessionId.value))
}
