package app.pristinepage.data.repository

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.data.db.RecipeRevisionRow
import app.pristinepage.data.db.SourceRow
import app.pristinepage.data.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Read-only editor metadata over the existing Room owner. No URI/file/decode/render/pin operation occurs here; original-byte proof remains SourceDecoder's responsibility under the caller's retained page/history owner. */
class RoomEditablePages @JvmOverloads constructor(private val metadata: MetadataAccess, private val bounds: EditablePageQueries, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) : EditablePages {
    private data class Snapshot(val documentId: DocumentId, val documentRevision: Long, val revision: RecipeRevisionRow, val source: SourceRow)

    override suspend fun load(documentId: DocumentId, pageId: PageId): EditablePage = loadAuthorized(pageId) {
        if (metadata.isHiddenAtelierDraft(documentId.value)) refuse(OperationErrorCode.SOURCE_UNAVAILABLE, "document_unavailable", documentId.value)
        documentId
    }

    /** A stale wizard action cannot borrow ordinary lookup or another draft's current recipe. Subsequent writes still need their own transactional generation/revision checks. */
    internal suspend fun loadForDraft(draftId: String, generation: Long, pageId: PageId): EditablePage = loadAuthorized(pageId) {
        requireOpaqueId(draftId)
        require(generation > 0)
        val staging = metadata.atelierEditingDocument(draftId, generation)
            ?: refuse(OperationErrorCode.SOURCE_UNAVAILABLE, "draft_unavailable", draftId)
        DocumentId(staging)
    }

    private suspend fun loadAuthorized(pageId: PageId, resolveDocument: () -> DocumentId): EditablePage = withContext(dispatcher) {
        try {
            coroutineContext.ensureActive()
            val snapshot = metadata.transaction {
                val documentId = resolveDocument()
                when (bounds.documentReadIsBounded(documentId.value)) {
                    null -> refuse(OperationErrorCode.SOURCE_UNAVAILABLE, "document_unavailable", documentId.value)
                    false -> refuse(OperationErrorCode.INTEGRITY_FAILED, "editable_page_metadata_invalid", pageId.value)
                    true -> Unit
                }
                val q = metadata.queries
                val document = q.document(documentId.value) ?: refuse(OperationErrorCode.INTEGRITY_FAILED, "editable_page_metadata_invalid", pageId.value)
                require(document.documentId == documentId.value && document.documentRevision > 0)
                if (document.lifecycle != "ACTIVE") refuse(OperationErrorCode.SOURCE_UNAVAILABLE, "document_unavailable", documentId.value)
                require(document.deletedAt == null)
                val count = bounds.activePageCount(documentId.value)
                require(count in 0L..100L && bounds.activePagesReadIsBounded(documentId.value))
                val pages = q.activePages(documentId.value)
                require(pages.size.toLong() == count)
                val seen = HashSet<String>()
                for ((ordinal,page) in pages.withIndex()) {
                    PageId(page.pageId); SourceId(page.sourceId)
                    require(page.documentId == documentId.value && page.state == "ACTIVE" && page.removedAt == null && page.ordinal == ordinal && page.currentRecipeRevision > 0 && seen.add(page.pageId))
                }
                val page = pages.singleOrNull { it.pageId == pageId.value } ?: refuse(OperationErrorCode.SOURCE_UNAVAILABLE, "page_unavailable", pageId.value)
                if (bounds.recipeReadIsBounded(page.pageId,page.currentRecipeRevision) != true) refuse(OperationErrorCode.INTEGRITY_FAILED, "editable_recipe_metadata_invalid", pageId.value)
                if (bounds.sourceReadIsBounded(page.sourceId) != true) refuse(OperationErrorCode.INTEGRITY_FAILED, "editable_source_metadata_invalid", pageId.value)
                val source = q.source(page.sourceId) ?: refuse(OperationErrorCode.INTEGRITY_FAILED, "editable_source_metadata_invalid", pageId.value)
                val revision = q.recipe(page.pageId,page.currentRecipeRevision) ?: refuse(OperationErrorCode.INTEGRITY_FAILED, "editable_recipe_metadata_invalid", pageId.value)
                require(revision.pageId == page.pageId && revision.recipeRevision == page.currentRecipeRevision && revision.sourceId == page.sourceId && source.sourceId == page.sourceId && source.state == "READY")
                if (revision.recipeSchemaVersion != 1) refuse(OperationErrorCode.UNSUPPORTED_VERSION, "stored_recipe_unsupported", pageId.value)
                // Do not pretend a masked page is unmasked merely because its durable mask reader is unfinished. EXISTS never projects the mask paths or payloads.
                if (bounds.recipeHasMasks(page.pageId,page.currentRecipeRevision)) refuse(OperationErrorCode.PROVIDER_UNAVAILABLE, "editable_masks_unavailable", pageId.value)
                coroutineContext.ensureActive()
                Snapshot(documentId,document.documentRevision,revision.copy(canonicalRecipe = revision.canonicalRecipe.copyOf()),source)
            }
            // Canonical decode/hash is bounded by the scalar BLOB extent guard and runs after releasing the metadata transaction. The snapshot remains immutable even if a later edit wins CAS.
            val row = snapshot.revision
            val recipe = try { RecipeCodec.decode(row.canonicalRecipe) } catch (_: IllegalArgumentException) { refuse(OperationErrorCode.UNSUPPORTED_VERSION, "stored_recipe_unreadable", pageId.value) }
            require(RecipeCodec.encode(recipe).contentEquals(row.canonicalRecipe) && RecipeCodec.hash(recipe) == row.recipeSha256)
            val source = snapshot.source
            require(recipe.geometry.source == SourceBinding(source.pixelSha256,PixelSize(source.width,source.height),source.conversionFingerprint))
            coroutineContext.ensureActive()
            EditablePage(snapshot.documentId,snapshot.documentRevision,pageId,SourceId(source.sourceId),row.recipeRevision,row.recipeSha256,recipe)
        } catch (canceled: CancellationException) { throw canceled }
        catch (failure: RepositoryFailure) { throw failure }
        catch (_: IllegalArgumentException) { refuse(OperationErrorCode.INTEGRITY_FAILED, "editable_page_metadata_invalid", pageId.value) }
        catch (_: Exception) { refuse(OperationErrorCode.STORAGE_FAILED, "editable_page_read_failed", pageId.value,true) }
    }

    private fun refuse(code: OperationErrorCode, key: String, id: String, retryable: Boolean = false): Nothing = throw RepositoryFailure(OperationError(code,retryable,key,id))
}
