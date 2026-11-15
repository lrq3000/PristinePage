package app.pristinepage.data.repository

import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.journal.IngestJournalCodec
import app.pristinepage.data.model.*
import kotlinx.coroutines.CancellationException

/** Resolves only the original immutable input. No provider/file IO, new ID, admission, lease or page write occurs. The caller must still verify the completed receipt/file through IngestReplay or execute admitted work through SourceStore. */
class RoomIngestLookup(private val metadata: MetadataAccess, private val sessions: ImportSessionQueries, private val journal: IngestPreparationQueries, private val jobs: IngestLookupQueries) : IngestOperationLookup {
    constructor(database: PristineDatabase) : this(RoomMetadataAccess(database), database.importSessions(), database.ingestPreparation(), database.ingestPreparation())

    override fun find(sessionId: ImportSessionId): IngestJournalIntent? {
        try {
            return metadata.transaction {
                val child = sessions.session(sessionId.value) ?: reject(OperationErrorCode.SESSION_EXPIRED, "import_item_unavailable", sessionId)
                if (child.kind != "ITEM" || child.parentSessionId == null) reject(OperationErrorCode.INVALID_INPUT, "invalid_import_reservation", sessionId)
                val transaction = child.transactionId
                if (transaction == null) {
                    if (child.state in setOf("INGESTING", "COMMITTED")) reject(OperationErrorCode.INTEGRITY_FAILED, "ingest_intent_missing", sessionId)
                    return@transaction null
                }
                if (child.state !in setOf("INGESTING", "COMMITTED", "CANCELED", "FAILED", "QUARANTINED")) reject(OperationErrorCode.INTEGRITY_FAILED, "ingest_session_binding_mismatch", sessionId)
                val row = journal.fileTransaction(transaction) ?: reject(OperationErrorCode.INTEGRITY_FAILED, "ingest_intent_missing", sessionId)
                if (row.kind != "INGEST" || row.ownerId != child.sessionId || row.attempt < 1) reject(OperationErrorCode.INTEGRITY_FAILED, "ingest_intent_binding_mismatch", sessionId)
                val job = jobs.ingestJobByKey("ingest-v1:${sessionId.value}:${row.attempt}") ?: reject(OperationErrorCode.INTEGRITY_FAILED, "ingest_job_missing", sessionId)
                val intent = try { IngestJournalCodec.decodeIntent(job.input) }
                catch (_: IllegalArgumentException) { reject(OperationErrorCode.INTEGRITY_FAILED, "invalid_ingest_intent", sessionId) }
                val parent = sessions.session(child.parentSessionId) ?: reject(OperationErrorCode.INTEGRITY_FAILED, "ingest_session_binding_mismatch", sessionId)
                if (intent.sessionId != sessionId || intent.transactionId.value != transaction || intent.documentId.value != child.documentId || intent.pageId.value != child.plannedPageId || intent.attempt != row.attempt || parent.kind != "BATCH" || parent.parentSessionId != null || parent.documentId != child.documentId || row.stagingPath != "staging/${intent.transactionId.value}/original.part" || row.finalPath != "sources/${intent.sourceBlobId.value}/original.bin") reject(OperationErrorCode.INTEGRITY_FAILED, "ingest_intent_binding_mismatch", sessionId)
                if (job.jobId != intent.jobId.value || job.kind != "RECONCILE" || job.idempotenceKey != IngestJobIdentity.key(intent) || job.canonicalInputSha256 != IngestJobIdentity.hash(job.input) || job.snapshotId != null || job.pageOrdinal != null) reject(OperationErrorCode.INTEGRITY_FAILED, "ingest_job_binding_mismatch", sessionId)
                if ((child.state == "COMMITTED") != (row.state == "COMMITTED") || (row.state == "COMMITTED" && job.state != "SUCCEEDED")) reject(OperationErrorCode.INTEGRITY_FAILED, "ingest_completion_binding_mismatch", sessionId)
                // COMMITTED payload is a receipt, not a rewritten intent. Looking it up by guessing that payload's shape would lose original job identity; all other known precommit payloads must still be that exact canonical intent.
                if (row.state != "COMMITTED" && !row.payload.contentEquals(job.input)) reject(OperationErrorCode.INTEGRITY_FAILED, "ingest_intent_binding_mismatch", sessionId)
                intent
            }
        } catch (canceled: CancellationException) { throw canceled }
        catch (failure: RepositoryFailure) { throw failure }
        catch (_: Exception) { throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "ingest_lookup_failed", sessionId.value)) }
    }

    private fun reject(code: OperationErrorCode, key: String, session: ImportSessionId): Nothing = throw RepositoryFailure(OperationError(code, false, key, session.value))
}
