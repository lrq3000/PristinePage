package app.pristinepage.data.repository

import app.pristinepage.data.db.FileTransactionRow
import app.pristinepage.data.db.JobRow
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.journal.IngestJournalCodec
import app.pristinepage.data.model.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** Records only the operation's private ownership before any file/provider action. A PENDING RECONCILE job is a durable intent, not resource admission or permission to start copying. The SourceStore/recovery consumer must acquire admission and its fenced owner separately. */
class RoomIngestPreparation @JvmOverloads constructor(private val metadata: MetadataAccess, private val sessions: ImportSessionQueries, private val ingest: IngestPreparationQueries, private val jobs: JobLeaseQueries, private val auditClock: () -> Long = System::currentTimeMillis) : IngestPreparation {
    constructor(database: PristineDatabase) : this(RoomMetadataAccess(database), database.importSessions(), database.ingestPreparation(), database.jobLeases())

    override fun prepare(sessionId: ImportSessionId, boundUriText: String): IngestJournalIntent {
        try {
            return metadata.transaction {
                val child = sessions.session(sessionId.value) ?: reject(OperationErrorCode.SESSION_EXPIRED, "import_session_unavailable", sessionId.value)
                val now = auditClock()
                if (child.kind != "ITEM" || child.parentSessionId == null || child.state !in setOf("BOUND", "INGESTING") || child.expiresAt <= now) reject(OperationErrorCode.SESSION_EXPIRED, "import_item_unavailable", sessionId.value)
                if (child.uriText == null || child.uriText != boundUriText) reject(OperationErrorCode.INVALID_INPUT, "import_reference_mismatch", sessionId.value)
                val parent = sessions.session(child.parentSessionId) ?: reject(OperationErrorCode.SESSION_EXPIRED, "import_session_unavailable", sessionId.value)
                if (parent.kind != "BATCH" || parent.parentSessionId != null || parent.state != "RESERVED" || parent.expiresAt <= now) reject(OperationErrorCode.SESSION_EXPIRED, "import_session_unavailable", sessionId.value)
                if (parent.documentId != child.documentId || child.plannedPageId == null || child.selectionOrdinal !in 0..99) reject(OperationErrorCode.INTEGRITY_FAILED, "invalid_import_reservation", sessionId.value)
                val doc = metadata.queries.document(child.documentId) ?: reject(OperationErrorCode.SOURCE_UNAVAILABLE, "document_unavailable", child.documentId)
                if (doc.lifecycle != "ACTIVE") reject(OperationErrorCode.SOURCE_UNAVAILABLE, "document_not_active", doc.documentId)
                val pages = metadata.queries.activePages(doc.documentId)
                if (pages.map { it.ordinal } != pages.indices.toList()) reject(OperationErrorCode.INTEGRITY_FAILED, "invalid_page_order", doc.documentId)
                if (ImportMembership.hash(pages) != parent.expectedMembershipToken) reject(OperationErrorCode.REVISION_CONFLICT, "import_membership_changed", sessionId.value)
                if (metadata.queries.page(child.plannedPageId) != null) reject(OperationErrorCode.INTEGRITY_FAILED, "import_page_already_exists", sessionId.value)
                child.transactionId?.let { transactionId ->
                    val row = ingest.fileTransaction(transactionId) ?: reject(OperationErrorCode.INTEGRITY_FAILED, "ingest_intent_missing", sessionId.value)
                    val intent = try { IngestJournalCodec.decodeIntent(row.payload) }
                    catch (_: IllegalArgumentException) { reject(OperationErrorCode.INTEGRITY_FAILED, "invalid_ingest_intent", sessionId.value) }
                    if (child.state != "INGESTING" || row.kind != "INGEST" || row.ownerId != child.sessionId || row.state !in setOf("INTENT", "COPYING", "SEALED", "MOVED", "RETRYABLE") || row.transactionId != intent.transactionId.value || row.attempt != intent.attempt || intent.sessionId.value != child.sessionId || intent.documentId.value != doc.documentId || intent.pageId.value != child.plannedPageId || row.stagingPath != stagingPath(intent) || row.finalPath != finalPath(intent)) reject(OperationErrorCode.INTEGRITY_FAILED, "ingest_intent_binding_mismatch", sessionId.value)
                    val job = jobs.job(intent.jobId.value) ?: reject(OperationErrorCode.INTEGRITY_FAILED, "ingest_job_missing", sessionId.value)
                    if (job.kind != "RECONCILE" || job.idempotenceKey != jobKey(intent) || job.canonicalInputSha256 != inputHash(row.payload) || !job.input.contentEquals(row.payload)) reject(OperationErrorCode.INTEGRITY_FAILED, "ingest_job_binding_mismatch", sessionId.value)
                    if (job.state !in setOf("PENDING", "ADMITTED", "RUNNING", "CHECKPOINTED", "WAITING_RESOURCE", "RETRYABLE", "INTERRUPTED")) reject(OperationErrorCode.SESSION_EXPIRED, "ingest_job_not_active", sessionId.value)
                    return@transaction intent
                }
                if (child.state != "BOUND") reject(OperationErrorCode.INTEGRITY_FAILED, "ingest_intent_missing", sessionId.value)
                if (pages.size >= 100) reject(OperationErrorCode.RESOURCE_LIMIT, "document_page_limit", doc.documentId)
                val intent = IngestJournalIntent(TransactionId(newId()), sessionId, DocumentId(doc.documentId), PageId(child.plannedPageId), SourceBlobId(newId()), SourceId(newId()), JobId(newId()), 1)
                val bytes = IngestJournalCodec.encodeIntent(intent)
                ingest.insertIngestJob(JobRow(intent.jobId.value, "RECONCILE", jobKey(intent), inputHash(bytes), null, null, bytes.copyOf(), "PENDING", 0, null, null, 0, null, null, now, now))
                ingest.insertFileTransaction(FileTransactionRow(intent.transactionId.value, "INGEST", child.sessionId, "INTENT", stagingPath(intent), finalPath(intent), null, null, null, bytes, intent.attempt, null, now))
                if (sessions.updateSession(child.copy(state = "INGESTING", transactionId = intent.transactionId.value)) != 1) reject(OperationErrorCode.INTEGRITY_FAILED, "import_item_unavailable", sessionId.value)
                intent
            }
        } catch (canceled: CancellationException) { throw canceled }
        catch (failure: RepositoryFailure) { throw failure }
        catch (_: Exception) { throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "ingest_intent_write_failed", sessionId.value)) }
    }

    private fun newId() = UUID.randomUUID().toString()
    private fun stagingPath(intent: IngestJournalIntent) = "staging/${intent.transactionId.value}/original.part"
    private fun finalPath(intent: IngestJournalIntent) = "sources/${intent.sourceBlobId.value}/original.bin"
    private fun jobKey(intent: IngestJournalIntent) = "ingest-v1:${intent.sessionId.value}:${intent.attempt}"
    private fun inputHash(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("pristinepage-ingest-input-v1\u0000".toByteArray(Charsets.UTF_8))
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        return digest.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun reject(code: OperationErrorCode, key: String, id: String): Nothing = throw RepositoryFailure(OperationError(code, false, key, id))
}
