package app.pristinepage.data.repository

import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.journal.IngestJournalCodec
import app.pristinepage.data.model.*
import kotlinx.coroutines.CancellationException

/** Metadata readmission only, after the recovery consumer holds a fresh resource permit. Liveness must be a bounded cached/registry lookup, never process/filesystem IO under Room. ENDED is positive irreversible completion evidence; wall/elapsed expiry is deliberately not a substitute, including after reboot. */
class RoomIngestReadmission @JvmOverloads constructor(private val metadata: MetadataAccess, private val sessions: ImportSessionQueries, private val journal: IngestPreparationQueries, private val jobs: JobLeaseQueries, private val liveness: LeaseOwnerLiveness, private val auditClock: () -> Long = System::currentTimeMillis) : IngestReadmission {
    constructor(database: PristineDatabase, liveness: LeaseOwnerLiveness) : this(RoomMetadataAccess(database), database.importSessions(), database.ingestPreparation(), database.jobLeases(), liveness)

    override fun readmitEnded(intent: IngestJournalIntent, recorded: JobLease): Boolean {
        try {
            val input = IngestJournalCodec.encodeIntent(intent)
            return metadata.transaction {
                if (recorded.jobId != intent.jobId) reject(intent, "ingest_job_binding_mismatch")
                val row = journal.fileTransaction(intent.transactionId.value) ?: reject(intent, "ingest_intent_missing")
                val job = jobs.job(intent.jobId.value) ?: reject(intent, "ingest_job_missing")
                if (row.transactionId != intent.transactionId.value || row.kind != "INGEST" || row.ownerId != intent.sessionId.value || row.attempt != intent.attempt || row.stagingPath != "staging/${intent.transactionId.value}/original.part" || row.finalPath != "sources/${intent.sourceBlobId.value}/original.bin" || (row.state != "COMMITTED" && !row.payload.contentEquals(input)) || job.kind != "RECONCILE" || job.idempotenceKey != IngestJobIdentity.key(intent) || job.canonicalInputSha256 != IngestJobIdentity.hash(input) || !job.input.contentEquals(input) || job.snapshotId != null || job.pageOrdinal != null) reject(intent, "ingest_intent_binding_mismatch")
                if (row.state !in setOf("INTENT", "COPYING", "SEALED", "MOVED") || job.state !in setOf("RUNNING", "CHECKPOINTED") || job.leaseOwner != recorded.owner || job.leaseGeneration != recorded.generation) return@transaction false
                if (liveness.state(recorded.owner) != LeaseOwnerState.ENDED) return@transaction false
                val child = sessions.session(intent.sessionId.value) ?: return@transaction false
                val parent = child.parentSessionId?.let(sessions::session) ?: return@transaction false
                val doc = metadata.queries.document(intent.documentId.value) ?: return@transaction false
                val now = auditClock()
                if (child.kind != "ITEM" || child.state != "INGESTING" || child.transactionId != intent.transactionId.value || child.plannedPageId != intent.pageId.value || child.documentId != intent.documentId.value || child.expiresAt <= now || parent.kind != "BATCH" || parent.parentSessionId != null || parent.documentId != child.documentId || parent.state != "RESERVED" || parent.expiresAt <= now || doc.lifecycle != "ACTIVE") return@transaction false
                val pages = metadata.queries.activePages(doc.documentId)
                if (pages.size >= 100 || pages.map { it.ordinal } != pages.indices.toList() || ImportMembership.hash(pages) != parent.expectedMembershipToken) return@transaction false
                // No new runner, generation or execution attempt is minted here. The consumer must next register a fresh owner and acquireAdmitted, whose incremented generation fences the old snapshot. Existing file/journal state remains untouched until verified recovery handles it.
                jobs.updateLease(job.copy(state = "ADMITTED", leaseOwner = null, leaseUntil = null, errorCode = null, updatedAt = now), recorded.generation) == 1
            }
        } catch (canceled: CancellationException) { throw canceled }
        catch (failure: RepositoryFailure) { throw failure }
        catch (_: Exception) { throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "ingest_readmission_failed", intent.sessionId.value)) }
    }

    private fun reject(intent: IngestJournalIntent, key: String): Nothing = throw RepositoryFailure(OperationError(OperationErrorCode.INTEGRITY_FAILED, false, key, intent.sessionId.value))
}
