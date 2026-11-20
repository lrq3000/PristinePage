package app.pristinepage.data.repository

import app.pristinepage.core.pipeline.requireWellFormedUnicode
import app.pristinepage.data.db.AtelierDraftDao
import app.pristinepage.data.db.AtelierDraftRow
import app.pristinepage.data.db.DocumentRow
import app.pristinepage.data.model.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Trusted staging owner. Operations manipulate private metadata only, never grant or open a URI. Not exposed to launcher navigation until publish and retention are verified. */
internal class RoomAtelierDrafts(
    private val metadata: MetadataAccess,
    private val drafts: AtelierDraftDao,
    private val sessions: RoomImportSessions,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        // Bound interrupted/failed as well as successful items until the reference-aware retired draft lifecycle is available.
        const val MAX_ITEMS_PER_DRAFT = 8
        const val MAX_COPY_ATTEMPTS_PER_DRAFT = 8
        private const val MAX_HIDDEN_DRAFTS = 2
    }

    suspend fun resume(): AtelierDraftRow? = command {
        val open = drafts.unfinished()
        require(open.size <= 1)
        open.singleOrNull()?.also(::validate)
    }

    suspend fun view(id: String? = null): AtelierDraftView? = command {
        val row = if (id == null) {
            val open = drafts.unfinished()
            require(open.size <= 1)
            open.singleOrNull()
        } else {
            DocumentId(id)
            drafts.draft(id)?.takeIf { it.state == "ACQUIRING" || it.state == "EDITING" }
        }
        if (row == null) return@command null
        validate(row)
        val bounds = metadata.queries as? EditablePageQueries ?: refuse(OperationErrorCode.PROVIDER_UNAVAILABLE, "metadata_guards_unavailable")
        require(bounds.activePagesReadIsBounded(row.stagingDocumentId))
        val ordered = metadata.queries.activePages(row.stagingDocumentId)
        validatePageOrder(row, ordered)
        val pending = bounds.activeImportIds(row.stagingDocumentId)
        require(pending.isEmpty() || pending.single() == row.activeBatchId && row.state == "ACQUIRING")
        AtelierDraftView(row.draftId, row.generation, row.targetDocumentId?.let(::DocumentId),
            row.state == "EDITING", row.step, row.selectedPageId?.let(::PageId),
            pending.singleOrNull()?.let(::ImportSessionId), ordered.mapIndexed { index, page ->
                DocumentPage(PageId(page.pageId), SourceId(page.sourceId), page.currentRecipeRevision, index)
            })
    }

    suspend fun create(title: String, target: DocumentId?, expectedRevision: Long?): AtelierDraftRow = command { context ->
        val name = title.trim()
        if (name.length !in 1..160 || (target == null) != (expectedRevision == null)) refuse(OperationErrorCode.INVALID_INPUT, "invalid_draft_input")
        try { requireWellFormedUnicode(name) } catch (_: IllegalArgumentException) { refuse(OperationErrorCode.INVALID_INPUT, "invalid_document_title") }
        if (drafts.unfinished().isNotEmpty()) refuse(OperationErrorCode.REVISION_CONFLICT, "unfinished_draft_exists")
        if (drafts.hiddenCount() >= MAX_HIDDEN_DRAFTS) refuse(OperationErrorCode.RESOURCE_LIMIT, "draft_retention_limit")
        if (target != null) {
            if (drafts.savedDocumentIsHidden(target.value) != false) refuse(OperationErrorCode.SOURCE_UNAVAILABLE, "document_unavailable")
            val current = metadata.queries.document(target.value) ?: refuse(OperationErrorCode.SOURCE_UNAVAILABLE, "document_unavailable")
            if (current.lifecycle != "ACTIVE" || current.deletedAt != null) refuse(OperationErrorCode.SOURCE_UNAVAILABLE, "document_unavailable")
            if (current.documentRevision != expectedRevision) refuse(OperationErrorCode.REVISION_CONFLICT, "document_revision_changed")
        }
        val draftId = UUID.randomUUID().toString()
        val staging = UUID.randomUUID().toString()
        val now = clock()
        context.ensureActive()
        metadata.queries.insertDocument(DocumentRow(staging, name, 1, "ACTIVE", now, now, null))
        AtelierDraftRow(draftId, staging, target?.value, expectedRevision, "ACQUIRING", 1, 0, null, null, now)
            .also(drafts::insert)
    }

    suspend fun reserve(draftId: String, generation: Long): ImportSessionId = command { context ->
        val draft = current(draftId, generation, "ACQUIRING")
        if (drafts.attemptedItems(draft.stagingDocumentId) >= MAX_ITEMS_PER_DRAFT) refuse(OperationErrorCode.RESOURCE_LIMIT, "document_page_limit")
        val album = metadata.queries.document(draft.stagingDocumentId) ?: refuse(OperationErrorCode.INTEGRITY_FAILED, "draft_album_missing")
        val batch = sessions.beginLocked(DocumentId(album.documentId), album.documentRevision, null, context)
        write(draft.copy(activeBatchId = batch.parentId.value))
        batch.parentId
    }

    suspend fun finish(draftId: String, generation: Long): AtelierDraftRow = command {
        val draft = current(draftId, generation, "ACQUIRING")
        requireSettled(draft)
        val pages = metadata.queries.activePages(draft.stagingDocumentId)
        if (pages.isEmpty()) refuse(OperationErrorCode.INVALID_INPUT, "draft_has_no_pages")
        validatePageOrder(draft, pages)
        val selected = draft.selectedPageId?.takeIf { id -> pages.any { it.pageId == id } } ?: pages.first().pageId
        write(draft.copy(state = "EDITING", generation = next(draft), step = 0, selectedPageId = selected, activeBatchId = null))
    }

    suspend fun checkpoint(draftId: String, generation: Long, pageId: PageId, step: Int): AtelierDraftRow = command {
        val draft = current(draftId, generation, "EDITING")
        if (step !in 0..2 || kotlin.math.abs(step - draft.step) > 1) refuse(OperationErrorCode.INVALID_INPUT, "invalid_draft_step")
        val page = metadata.queries.page(pageId.value)
        if (page?.documentId != draft.stagingDocumentId || page.state != "ACTIVE") refuse(OperationErrorCode.SOURCE_UNAVAILABLE, "page_unavailable")
        write(draft.copy(step = step, selectedPageId = pageId.value))
    }

    suspend fun reopenAcquisition(draftId: String, generation: Long): AtelierDraftRow = command {
        val draft = current(draftId, generation, "EDITING")
        write(draft.copy(state = "ACQUIRING", generation = next(draft), step = 0, activeBatchId = null))
    }

    suspend fun abandon(draftId: String, generation: Long): AtelierDraftRow = command {
        val draft = current(draftId, generation, "ACQUIRING", "EDITING")
        requireSettled(draft)
        // The journal still records these owners. Cancellation is not a reason to delete their original files.
        write(draft.copy(state = "ABANDONED", generation = next(draft), selectedPageId = null, activeBatchId = null))
    }

    private fun current(id: String, generation: Long, vararg states: String): AtelierDraftRow {
        DocumentId(id)
        val row = drafts.draft(id) ?: refuse(OperationErrorCode.SESSION_EXPIRED, "draft_unavailable")
        validate(row)
        if (row.draftId != id || row.generation != generation || row.state !in states) refuse(OperationErrorCode.REVISION_CONFLICT, "draft_changed")
        return row
    }

    private fun validate(row: AtelierDraftRow) {
        DocumentId(row.draftId); DocumentId(row.stagingDocumentId)
        row.targetDocumentId?.let(::DocumentId)
        row.selectedPageId?.let(::PageId)
        row.activeBatchId?.let(::ImportSessionId)
        require(row.state in setOf("ACQUIRING", "EDITING", "ABANDONED") && row.generation > 0 && row.step in 0..2)
        require((row.targetDocumentId == null) == (row.targetRevision == null) && (row.targetRevision ?: 1) > 0)
        require(row.committedDocumentId == null && row.receiptPageIds == null)
    }

    private fun requireSettled(row: AtelierDraftRow) {
        if (metadata.queries.hasPendingImport(row.stagingDocumentId)) refuse(OperationErrorCode.REVISION_CONFLICT, "finish_import_before_editing")
    }

    private fun validatePageOrder(draft: AtelierDraftRow, pages: List<app.pristinepage.data.db.PageRow>) {
        require(pages.size <= MAX_ITEMS_PER_DRAFT)
        pages.forEachIndexed { index, page ->
            PageId(page.pageId)
            require(page.documentId == draft.stagingDocumentId && page.state == "ACTIVE" && page.ordinal == index)
        }
    }

    private fun next(row: AtelierDraftRow) = Math.addExact(row.generation, 1L)

    private fun write(row: AtelierDraftRow): AtelierDraftRow {
        require(drafts.update(row) == 1)
        return row
    }

    private suspend fun <T> command(operation: (CoroutineContext) -> T): T = withContext(Dispatchers.IO) {
        try {
            val context = coroutineContext
            metadata.transaction {
                context.ensureActive()
                operation(context).also { context.ensureActive() }
            }
        } catch (canceled: CancellationException) { throw canceled }
        catch (failure: RepositoryFailure) { throw failure }
        catch (_: IllegalArgumentException) { refuse(OperationErrorCode.INTEGRITY_FAILED, "invalid_draft_metadata") }
        catch (_: ArithmeticException) { refuse(OperationErrorCode.RESOURCE_LIMIT, "draft_generation_limit") }
        catch (_: Exception) { refuse(OperationErrorCode.STORAGE_FAILED, "draft_storage_failed") }
    }

    private fun refuse(code: OperationErrorCode, reason: String): Nothing = throw RepositoryFailure(OperationError(code, code == OperationErrorCode.STORAGE_FAILED, reason, null))
}
