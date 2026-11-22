package app.pristinepage.data.repository

import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.data.db.*
import app.pristinepage.data.files.SourceFiles
import app.pristinepage.data.journal.IngestJournalCodec
import app.pristinepage.data.model.*
import java.io.IOException
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Only consumes an already MOVED, inspected source. Hash/readback is outside Room; visibility, ownership and journal/job completion share the subsequent short transaction. SourceStore still owns provider copy/admission/inspection/phase transitions; this helper neither performs nor claims them. */
class RoomIngestCompletion @JvmOverloads constructor(private val metadata: MetadataAccess, private val sessions: ImportSessionQueries, private val journal: IngestPreparationQueries, private val sources: IngestCompletionQueries, private val jobs: JobLeaseQueries, private val files: SourceFiles, private val auditClock: () -> Long = System::currentTimeMillis, private val leaseClock: () -> Long = SystemClock::elapsedRealtime, private val dispatcher: CoroutineDispatcher = Dispatchers.IO, private val drafts: AtelierDraftDao? = null) : IngestCompletion, IngestReplay {
    constructor(database: PristineDatabase, files: SourceFiles) : this(RoomMetadataAccess(database), database.importSessions(), database.ingestPreparation(), database.ingestCompletion(), database.jobLeases(), files, drafts = database.atelierDrafts())
    private val completion = JobLeaseTransactions(metadata, jobs, auditClock, leaseClock)

    override suspend fun commit(intent: IngestJournalIntent, lease: JobLease): IngestResult = process(intent, lease)
    override suspend fun replay(intent: IngestJournalIntent): IngestResult = process(intent, null)

    private suspend fun process(intent: IngestJournalIntent, lease: JobLease?): IngestResult = withContext(dispatcher) {
        var observed = FileTransactionState.MOVED
        try {
            if (lease != null && lease.jobId != intent.jobId) reject(OperationErrorCode.INTEGRITY_FAILED, "ingest_job_binding_mismatch", intent)
            coroutineContext.ensureActive()
            val before = metadata.transaction { readJournal(intent).also { checkTerminalOutcomes(it, intent) } }
            observed = FileTransactionState.valueOf(before.state)
            if (observed !in setOf(FileTransactionState.MOVED, FileTransactionState.COMMITTED)) return@withContext IngestResult.Pending(intent.sessionId, observed)
            if (lease == null && observed != FileTransactionState.COMMITTED) return@withContext IngestResult.Pending(intent.sessionId, observed)
            val receipt = if (observed == FileTransactionState.COMMITTED) IngestJournalCodec.decodeCommit(before.payload, intent) else null
            if (receipt == null && IngestJournalCodec.decodeIntent(before.payload) != intent) reject(OperationErrorCode.INTEGRITY_FAILED, "ingest_intent_binding_mismatch", intent)
            val recovered = files.recover(intent.transactionId.value, before.sealSha256 ?: reject(OperationErrorCode.INTEGRITY_FAILED, "ingest_seal_missing", intent)) ?: reject(OperationErrorCode.INTEGRITY_FAILED, "ingest_seal_missing", intent)
            val seal = IngestJournalCodec.decodeSeal(recovered.metadata, intent)
            if (seal.sourceByteHash != recovered.staged.byteSha256 || seal.byteCount != recovered.staged.byteCount || seal.sourceByteHash != before.expectedByteSha256 || seal.byteCount != before.expectedByteCount || recovered.staged.sourceBlobId != intent.sourceBlobId.value || !files.finalFile(intent.sourceBlobId.value).isFile || (receipt != null && receipt.seal != seal)) reject(OperationErrorCode.INTEGRITY_FAILED, "ingest_source_binding_mismatch", intent)
            coroutineContext.ensureActive()
            if (receipt != null) return@withContext metadata.transaction { replay(intent, before, receipt) }
            if (lease == null) return@withContext IngestResult.Pending(intent.sessionId, observed)
            val verifyDate = if (seal.captureDate == null) null else {
                {
                    coroutineContext.ensureActive()
                    verifyCaptureProjection(seal)
                    coroutineContext.ensureActive()
                }
            }
            val result = completion.completeWithReadback(lease, {
                coroutineContext.ensureActive()
                val live = readJournal(intent)
                checkTerminalOutcomes(live, intent)
                if (live.state != "MOVED" || !sameJournal(live, before)) reject(OperationErrorCode.INTEGRITY_FAILED, "ingest_journal_changed", intent)
                validateJob(intent, committed = false)
                publish(intent, live, seal)
            }, verifyDate)
            if (result != null) return@withContext result
            // Another same-attempt call may have committed between preflight and the lease fence. Reuse its exact receipt, never rerun page writes after job success.
            metadata.transaction {
                val live = readJournal(intent)
                checkTerminalOutcomes(live, intent)
                if (live.state == "COMMITTED") {
                    val committed = IngestJournalCodec.decodeCommit(live.payload, intent)
                    if (committed.seal != seal) reject(OperationErrorCode.INTEGRITY_FAILED, "ingest_source_binding_mismatch", intent)
                    replay(intent, live, committed)
                } else IngestResult.Pending(intent.sessionId, FileTransactionState.valueOf(live.state))
            }
        } catch (canceled: CancellationException) { throw canceled }
        catch (failure: RepositoryFailure) {
            if (failure.error.code == OperationErrorCode.STORAGE_FAILED && failure.error.retryable) IngestResult.Pending(intent.sessionId, observed)
            else IngestResult.Rejected(intent.sessionId, failure.error)
        } catch (_: IllegalArgumentException) { IngestResult.Rejected(intent.sessionId, OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "invalid_ingest_metadata", intent.sessionId.value)) }
        catch (_: IOException) { IngestResult.Pending(intent.sessionId, observed) }
        catch (_: Exception) { IngestResult.Rejected(intent.sessionId, OperationError(OperationErrorCode.STORAGE_FAILED, true, "ingest_commit_failed", intent.sessionId.value)) }
    }

    private fun readJournal(intent: IngestJournalIntent): FileTransactionRow {
        val row = journal.fileTransaction(intent.transactionId.value) ?: reject(OperationErrorCode.INTEGRITY_FAILED, "ingest_intent_missing", intent)
        if (row.kind != "INGEST" || row.ownerId != intent.sessionId.value || row.attempt != intent.attempt || row.stagingPath != "staging/${intent.transactionId.value}/original.part" || row.finalPath != "sources/${intent.sourceBlobId.value}/original.bin") reject(OperationErrorCode.INTEGRITY_FAILED, "ingest_intent_binding_mismatch", intent)
        return row
    }

    private fun sameJournal(a: FileTransactionRow, b: FileTransactionRow) = a.sealSha256 == b.sealSha256 && a.expectedByteSha256 == b.expectedByteSha256 && a.expectedByteCount == b.expectedByteCount && a.payload.contentEquals(b.payload)

    private fun validateJob(intent: IngestJournalIntent, committed: Boolean): JobRow {
        val job = jobs.job(intent.jobId.value) ?: reject(OperationErrorCode.INTEGRITY_FAILED, "ingest_job_missing", intent)
        val bytes = IngestJournalCodec.encodeIntent(intent)
        if (job.kind != "RECONCILE" || job.idempotenceKey != IngestJobIdentity.key(intent) || job.canonicalInputSha256 != IngestJobIdentity.hash(bytes) || !job.input.contentEquals(bytes) || job.snapshotId != null || job.pageOrdinal != null) reject(OperationErrorCode.INTEGRITY_FAILED, "ingest_job_binding_mismatch", intent)
        if (committed && (job.state != "SUCCEEDED" || job.leaseOwner != null || job.leaseUntil != null)) reject(OperationErrorCode.INTEGRITY_FAILED, "ingest_job_binding_mismatch", intent)
        return job
    }

    /** Terminal current-attempt outcomes are not interrupted work. Preserve known safe codes, never a raw exception string, and do not retry the failed attempt just because its error category also occurs on retryable IO. */
    private fun terminal(state: String, persisted: String?, intent: IngestJournalIntent) {
        val fallback = when (state) {
            "CANCELED" -> OperationErrorCode.CANCELED
            "FAILED" -> OperationErrorCode.PROCESSING_FAILED
            "QUARANTINED" -> OperationErrorCode.INTEGRITY_FAILED
            else -> return
        }
        val code = if (state == "CANCELED") fallback else OperationErrorCode.entries.firstOrNull { it.name == persisted } ?: fallback
        throw RepositoryFailure(OperationError(code, false, "ingest_terminal_outcome", intent.sessionId.value))
    }

    private fun checkTerminalOutcomes(row: FileTransactionRow, intent: IngestJournalIntent) {
        terminal(row.state, row.errorCode, intent)
        if (row.state == "COMMITTED") return
        val job = validateJob(intent, committed = false)
        terminal(job.state, job.errorCode, intent)
        if (job.state == "SUCCEEDED") reject(OperationErrorCode.INTEGRITY_FAILED, "ingest_job_binding_mismatch", intent)
        val child = sessions.session(intent.sessionId.value) ?: reject(OperationErrorCode.SESSION_EXPIRED, "import_item_unavailable", intent)
        terminal(child.state, child.errorCode, intent)
        val parent = child.parentSessionId?.let(sessions::session) ?: reject(OperationErrorCode.SESSION_EXPIRED, "import_session_unavailable", intent)
        terminal(parent.state, parent.errorCode, intent)
    }

    private fun publish(intent: IngestJournalIntent, row: FileTransactionRow, seal: IngestJournalSeal): IngestResult.Committed {
        val q = metadata.queries; val now = auditClock()
        val child = sessions.session(intent.sessionId.value) ?: reject(OperationErrorCode.SESSION_EXPIRED, "import_item_unavailable", intent)
        val parent = child.parentSessionId?.let(sessions::session) ?: reject(OperationErrorCode.SESSION_EXPIRED, "import_session_unavailable", intent)
        if (child.kind != "ITEM" || child.state != "INGESTING" || child.transactionId != intent.transactionId.value || child.plannedPageId != intent.pageId.value || child.documentId != intent.documentId.value || child.expiresAt <= now || parent.kind != "BATCH" || parent.state != "RESERVED" || parent.documentId != child.documentId || parent.expiresAt <= now) reject(OperationErrorCode.SESSION_EXPIRED, "import_session_unavailable", intent)
        if (drafts?.refusesDraftIngest(intent.documentId.value, parent.sessionId) == true) {
            reject(OperationErrorCode.SESSION_EXPIRED, "import_session_unavailable", intent)
        }
        val doc = q.document(intent.documentId.value) ?: reject(OperationErrorCode.SOURCE_UNAVAILABLE, "document_unavailable", intent)
        if (doc.lifecycle != "ACTIVE") reject(OperationErrorCode.SOURCE_UNAVAILABLE, "document_not_active", intent)
        val old = q.activePages(doc.documentId)
        if (old.size >= 100) reject(OperationErrorCode.RESOURCE_LIMIT, "document_page_limit", intent)
        if (old.map { it.ordinal } != old.indices.toList() || ImportMembership.hash(old) != parent.expectedMembershipToken) reject(OperationErrorCode.REVISION_CONFLICT, "import_membership_changed", intent)
        if (q.page(intent.pageId.value) != null || q.source(intent.sourceId.value) != null || sources.sourceBlob(intent.sourceBlobId.value) != null) reject(OperationErrorCode.INTEGRITY_FAILED, "ingest_identity_already_exists", intent)
        val siblings = sessions.children(parent.sessionId)
        if (siblings.map { it.selectionOrdinal } != siblings.indices.toList() || siblings.any { it.documentId != doc.documentId || it.kind != "ITEM" || it.plannedPageId == null }) reject(OperationErrorCode.INTEGRITY_FAILED, "invalid_import_reservation", intent)
        val committed = siblings.filter { it.state == "COMMITTED" }
        val committedIds = committed.map { it.plannedPageId!! }.toSet()
        if (committed.any { item -> old.none { it.pageId == item.plannedPageId } }) reject(OperationErrorCode.INTEGRITY_FAILED, "import_page_unavailable", intent)
        val base = old.filter { it.pageId !in committedIds }
        val insertion = if (parent.anchorPageId == null) base.size else base.indexOfFirst { it.pageId == parent.anchorPageId }.also { if (it < 0) reject(OperationErrorCode.REVISION_CONFLICT, "import_anchor_unavailable", intent) } + 1
        val block = (committed + child).sortedBy { it.selectionOrdinal }.map { it.plannedPageId!! }
        val ordered = base.take(insertion).map { it.pageId } + block + base.drop(insertion).map { it.pageId }
        val revision = try { Math.addExact(doc.documentRevision, 1) } catch (_: ArithmeticException) { reject(OperationErrorCode.RESOURCE_LIMIT, "document_revision_limit", intent) }
        val inspection = seal.inspection
        sources.insertSourceBlob(SourceBlobRow(intent.sourceBlobId.value, seal.sourceByteHash, seal.byteCount, inspection.mime, inspection.encodedSize.width, inspection.encodedSize.height, row.finalPath!!, "READY", 1, intent.transactionId.value, now))
        seal.captureDate?.let { sources.insertCaptureProjection(intent.sourceBlobId.value, seal.sourceByteHash, it.epochDay) }
        sources.insertSource(SourceRow(intent.sourceId.value, intent.sourceBlobId.value, inspection.sourcePixelHash, inspection.normalizedSize.width, inspection.normalizedSize.height, inspection.exifOrientation, inspection.conversionFingerprint, inspection.profileSha256, 0, "READY", now))
        // SQLite uniqueness is immediate. Move existing ordinals into a disjoint temporary range; none of these states can escape the same metadata transaction.
        for (page in old) if (q.updatePage(page.copy(ordinal = page.ordinal!! + 100)) != 1) reject(OperationErrorCode.INTEGRITY_FAILED, "import_page_unavailable", intent)
        val page = PageRow(intent.pageId.value, doc.documentId, intent.sourceId.value, 1, "ACTIVE", 200, null, now)
        val recipe = Recipe(geometry = GeometryRecipe.None(SourceBinding(inspection.sourcePixelHash, inspection.normalizedSize, inspection.conversionFingerprint)))
        q.insertPage(page)
        q.insertRecipe(RecipeRevisionRow(page.pageId, 1, page.sourceId, recipe.schemaVersion, RecipeCodec.encode(recipe), RecipeCodec.hash(recipe), null, now))
        q.insertReference(SourceReferenceRow("PAGE", page.pageId, page.sourceId, now))
        q.insertReference(SourceReferenceRow("RECIPE_HISTORY", "recipe-v1/${page.pageId}/1", page.sourceId, now))
        if (q.setSourceRefCount(page.sourceId, q.countSourceReferences(page.sourceId)) != 1) reject(OperationErrorCode.INTEGRITY_FAILED, "source_unavailable", intent)
        val byId = (old + page).associateBy { it.pageId }
        val finalPages = ordered.mapIndexed { ordinal, id -> checkNotNull(byId[id]).copy(ordinal = ordinal) }
        for (updated in finalPages) if (q.updatePage(updated) != 1) reject(OperationErrorCode.INTEGRITY_FAILED, "import_page_unavailable", intent)
        if (q.updateDocument(doc.copy(documentRevision = revision, updatedAt = now)) != 1) reject(OperationErrorCode.INTEGRITY_FAILED, "document_unavailable", intent)
        if (sessions.updateSession(child.copy(state = "COMMITTED", uriText = null, errorCode = null)) != 1) reject(OperationErrorCode.INTEGRITY_FAILED, "import_item_unavailable", intent)
        val finished = siblings.all { it.sessionId == child.sessionId || it.state in setOf("COMMITTED", "CANCELED", "FAILED", "QUARANTINED") }
        if (sessions.updateSession(parent.copy(expectedMembershipToken = ImportMembership.hash(finalPages), state = if (finished) "COMMITTED" else "RESERVED")) != 1) reject(OperationErrorCode.INTEGRITY_FAILED, "import_session_unavailable", intent)
        val receipt = IngestJournalCommit(seal, revision)
        if (sources.updateFileTransaction(row.copy(state = "COMMITTED", payload = IngestJournalCodec.encodeCommit(receipt), errorCode = null, updatedAt = now), "MOVED") != 1) reject(OperationErrorCode.INTEGRITY_FAILED, "ingest_journal_changed", intent)
        return result(receipt)
    }

    private fun replay(intent: IngestJournalIntent, before: FileTransactionRow, receipt: IngestJournalCommit): IngestResult.Committed {
        val live = readJournal(intent)
        if (live.state != "COMMITTED" || !sameJournal(live, before)) reject(OperationErrorCode.INTEGRITY_FAILED, "ingest_journal_changed", intent)
        // For v2, refuse a mismatched backing digest without materializing the SourceBlob row.
        verifyCaptureProjection(receipt.seal)
        val source = metadata.queries.source(intent.sourceId.value) ?: reject(OperationErrorCode.INTEGRITY_FAILED, "source_unavailable", intent)
        val blob = sources.sourceBlob(intent.sourceBlobId.value) ?: reject(OperationErrorCode.INTEGRITY_FAILED, "source_unavailable", intent)
        val page = metadata.queries.page(intent.pageId.value) ?: reject(OperationErrorCode.SESSION_EXPIRED, "import_page_unavailable", intent)
        val inspection = receipt.seal.inspection
        if (page.sourceId != source.sourceId || page.documentId != intent.documentId.value || source.sourceBlobId != blob.sourceBlobId || source.state != "READY" || blob.state != "READY" || source.pixelSha256 != inspection.sourcePixelHash || source.width != inspection.normalizedSize.width || source.height != inspection.normalizedSize.height || source.conversionFingerprint != inspection.conversionFingerprint || source.profileSha256 != inspection.profileSha256 || source.exifOrientation != inspection.exifOrientation || blob.byteSha256 != receipt.seal.sourceByteHash || blob.byteCount != receipt.seal.byteCount || blob.mime != inspection.mime || blob.encodedWidth != inspection.encodedSize.width || blob.encodedHeight != inspection.encodedSize.height || blob.relativePath != before.finalPath || blob.ingestTransactionId != intent.transactionId.value) reject(OperationErrorCode.INTEGRITY_FAILED, "ingest_source_binding_mismatch", intent)
        val child = sessions.session(intent.sessionId.value) ?: reject(OperationErrorCode.INTEGRITY_FAILED, "ingest_session_binding_mismatch", intent)
        val parent = child.parentSessionId?.let(sessions::session) ?: reject(OperationErrorCode.INTEGRITY_FAILED, "ingest_session_binding_mismatch", intent)
        if (child.kind != "ITEM" || child.state != "COMMITTED" || child.transactionId != intent.transactionId.value || child.plannedPageId != intent.pageId.value || child.documentId != intent.documentId.value || parent.kind != "BATCH" || parent.parentSessionId != null || parent.documentId != intent.documentId.value) reject(OperationErrorCode.INTEGRITY_FAILED, "ingest_session_binding_mismatch", intent)
        validateJob(intent, committed = true)
        return result(receipt)
    }

    private fun verifyCaptureProjection(seal: IngestJournalSeal) {
        val date = seal.captureDate ?: return
        if (!sources.captureProjectionMatches(seal.intent.sourceBlobId.value, seal.sourceByteHash, date.epochDay))
            reject(OperationErrorCode.INTEGRITY_FAILED, "ingest_capture_date_mismatch", seal.intent)
    }

    private fun result(receipt: IngestJournalCommit): IngestResult.Committed {
        val intent = receipt.seal.intent
        return IngestResult.Committed(intent.sessionId, intent.documentId, intent.pageId, intent.sourceId, receipt.seal.sourceByteHash, receipt.seal.inspection.sourcePixelHash, receipt.documentRevision)
    }
    private fun reject(code: OperationErrorCode, key: String, intent: IngestJournalIntent): Nothing = throw RepositoryFailure(OperationError(code, false, key, intent.sessionId.value))
}
