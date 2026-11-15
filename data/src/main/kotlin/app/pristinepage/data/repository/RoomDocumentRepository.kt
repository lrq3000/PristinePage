package app.pristinepage.data.repository

import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.core.pipeline.requireWellFormedUnicode
import app.pristinepage.core.recipe.AppearanceRecipe
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.data.db.*
import app.pristinepage.data.model.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable private data class UndoPointer(val pageId: String, val sourceId: String, val recipeRevision: Long)
@Serializable private data class RecipeUndoState(val version: Int = 1, val pages: List<UndoPointer>)

/** File/decode/render work is deliberately absent. Canonical/capability preflight finishes before a short metadata transaction, which rechecks every mutable pointer and publishes all revisions together. RoomMetadataAccess supplies actual Android rollback; host tests exercise this same business code through its database port. */
class RoomDocumentRepository @JvmOverloads constructor(private val metadata: MetadataAccess, private val admission: RecipeAdmission, private val clock: () -> Long = System::currentTimeMillis, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) : DocumentRepository {
    private val q: CatalogQueries get() = metadata.queries
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private class Conflict(val actual: Long) : RuntimeException()
    private data class MaskState(val pageId: String, val revision: Long, val links: List<RecipeMaskRow>, val identities: List<MaskRow>)
    private data class Prepared(val page: PageRow, val current: RecipeRevisionRow, val recipe: Recipe, val bytes: ByteArray, val hash: String, val masks: MaskState, val restored: RecipeRevisionRow?)

    override suspend fun createDocument(title: String): DocumentId = withContext(dispatcher) {
        try {
            val normalized = title.trim()
            if (normalized.isEmpty() || normalized.length > 160) reject(OperationErrorCode.INVALID_INPUT, "invalid_document_title")
            try { requireWellFormedUnicode(normalized) } catch (_: IllegalArgumentException) { reject(OperationErrorCode.INVALID_INPUT, "invalid_document_title") }
            val id = DocumentId(uuid()); val now = clock()
            metadata.transaction { q.insertDocument(DocumentRow(id.value, normalized, 1, "ACTIVE", now, now, null)) }
            id
        } catch (canceled: CancellationException) { throw canceled }
        catch (failure: RepositoryFailure) { throw failure }
        catch (_: Exception) { reject(OperationErrorCode.STORAGE_FAILED, "metadata_write_failed", retryable = true) }
    }

    override suspend fun saveRecipe(pageId: PageId, expectedRevision: Long, recipe: Recipe): SaveRecipeResult = withContext(dispatcher) {
        command<SaveRecipeResult>({ SaveRecipeResult.Conflict(it) }, { SaveRecipeResult.Rejected(it) }) {
            if (expectedRevision < 1) reject(OperationErrorCode.INVALID_INPUT, "invalid_recipe_revision", pageId.value)
            val prepared = prepare(pageId.value, recipe)
            metadata.transaction {
                val doc = activeDocument(prepared.page.documentId)
                val page = verifyLive(prepared, expectedRevision) { it.currentRecipeRevision }
                val now = clock(); val nextDoc = next(doc.documentRevision)
                val after = append(page, prepared.bytes, prepared.hash, prepared.masks.links, now)
                addUndo(doc.documentId, "RECIPE_EDIT", listOf(pointer(page)), listOf(pointer(after)), prepared.masks.links, nextDoc, now)
                updateDocument(doc.copy(documentRevision = nextDoc, updatedAt = now))
                SaveRecipeResult.Saved(pageId, after.currentRecipeRevision, nextDoc, prepared.hash)
            }
        }
    }

    override suspend fun applyBatchRecipes(documentId: DocumentId, expectedDocumentRevision: Long, changes: List<BatchRecipeChange>): DocumentMutationResult = withContext(dispatcher) {
        documentCommand {
            if (expectedDocumentRevision < 1 || changes.isEmpty() || changes.size > 100 || changes.map { it.pageId }.toSet().size != changes.size || changes.any { it.expectedRecipeRevision < 1 }) reject(OperationErrorCode.INVALID_INPUT, "invalid_recipe_batch", documentId.value)
            val prepared = changes.map { change ->
                prepare(change.pageId.value, change.recipe).also { if (it.page.documentId != documentId.value) reject(OperationErrorCode.INVALID_INPUT, "foreign_batch_page", change.pageId.value) }
            }
            metadata.transaction {
                val doc = activeDocument(documentId.value)
                if (doc.documentRevision != expectedDocumentRevision) throw Conflict(doc.documentRevision)
                // Validate every page before the first write. No loop of separately committed saveRecipe calls can satisfy this aggregate command.
                val live = prepared.mapIndexed { index, item -> verifyLive(item, changes[index].expectedRecipeRevision) { doc.documentRevision } }
                val changed = prepared.indices.filter { !prepared[it].bytes.contentEquals(prepared[it].current.canonicalRecipe) }
                if (changed.isEmpty()) reject(OperationErrorCode.INVALID_INPUT, "recipe_batch_has_no_changes", documentId.value)
                val now = clock(); val nextDoc = next(doc.documentRevision)
                val before = changed.map { pointer(live[it]) }
                val after = changed.map { index -> val item = prepared[index]; pointer(append(live[index], item.bytes, item.hash, item.masks.links, now)) }
                val undo = addUndo(doc.documentId, "BATCH_RECIPE_EDIT", before, after, changed.flatMap { prepared[it].masks.links }, nextDoc, now)
                updateDocument(doc.copy(documentRevision = nextDoc, updatedAt = now))
                DocumentMutationResult.Applied(nextDoc, after.map { PageId(it.pageId) }, undo)
            }
        }
    }

    override suspend fun duplicatePage(pageId: PageId, expectedDocumentRevision: Long): DocumentMutationResult = withContext(dispatcher) {
        documentCommand {
            val original = q.page(pageId.value) ?: reject(OperationErrorCode.SOURCE_UNAVAILABLE, "page_unavailable", pageId.value)
            val stored = supportedRecipe(original)
            val prepared = prepare(pageId.value, stored)
            metadata.transaction {
                val doc = activeDocument(original.documentId)
                if (doc.documentRevision != expectedDocumentRevision) throw Conflict(doc.documentRevision)
                if (q.hasPendingImport(doc.documentId)) reject(OperationErrorCode.REVISION_CONFLICT, "finish_import_before_membership_edit", doc.documentId, true)
                val source = verifyLive(prepared, original.currentRecipeRevision) { doc.documentRevision }
                val pages = q.activePages(doc.documentId)
                if (pages.size >= 100) reject(OperationErrorCode.RESOURCE_LIMIT, "document_page_limit", doc.documentId)
                if (pages.map { it.ordinal } != pages.indices.toList()) reject(OperationErrorCode.INTEGRITY_FAILED, "invalid_page_order", doc.documentId)
                val index = pages.indexOfFirst { it.pageId == pageId.value }
                if (index < 0) reject(OperationErrorCode.SOURCE_UNAVAILABLE, "page_unavailable", pageId.value)
                val now = clock(); val id = PageId(uuid()); val nextDoc = next(doc.documentRevision)
                // Real SQLite uniqueness is checked at each UPDATE, not just at transaction end. Move all existing rows to a disjoint range before assigning final contiguous ordinals.
                for (page in pages) updatePage(page.copy(ordinal = checkNotNull(page.ordinal) + pages.size + 1))
                val added = PageRow(id.value, doc.documentId, source.sourceId, 1, "ACTIVE", index + 1, null, now)
                q.insertPage(added)
                q.insertRecipe(RecipeRevisionRow(id.value, 1, source.sourceId, 1, prepared.bytes.copyOf(), prepared.hash, null, now))
                for ((position, page) in pages.withIndex()) updatePage(page.copy(ordinal = if (position <= index) position else position + 1))
                pinSource("PAGE", id.value, source.sourceId, now)
                pinSource("RECIPE_HISTORY", recipeOwner(id.value, 1), source.sourceId, now)
                copyMasks(prepared.masks.links, id.value, 1)
                updateDocument(doc.copy(documentRevision = nextDoc, updatedAt = now))
                // Reversible membership history is completed with the T-10 remove/reorder commands, not represented by an unusable Undo token here.
                DocumentMutationResult.Applied(nextDoc, listOf(id), null)
            }
        }
    }

    override suspend fun undoDocumentEdit(undoId: UndoId, expectedDocumentRevision: Long): DocumentMutationResult = withContext(dispatcher) {
        documentCommand {
            val entry = q.undo(undoId.value) ?: reject(OperationErrorCode.INVALID_INPUT, "undo_unavailable", undoId.value)
            if (entry.action !in setOf("RECIPE_EDIT", "BATCH_RECIPE_EDIT") || entry.state != "AVAILABLE" || entry.expiresAt <= clock()) reject(OperationErrorCode.INVALID_INPUT, "undo_unavailable", undoId.value)
            val before = decodeUndo(entry.beforeState); val after = decodeUndo(entry.afterState)
            if (before.pages.map { it.pageId } != after.pages.map { it.pageId }) reject(OperationErrorCode.INTEGRITY_FAILED, "invalid_undo_metadata", undoId.value)
            val targets = before.pages.map { old ->
                val current = q.page(old.pageId) ?: reject(OperationErrorCode.SOURCE_UNAVAILABLE, "page_unavailable", old.pageId)
                val row = q.recipe(old.pageId, old.recipeRevision) ?: reject(OperationErrorCode.INTEGRITY_FAILED, "recipe_history_missing", old.pageId)
                val recipe = decodeRecipe(row)
                val prepared = prepare(old.pageId, recipe, old.recipeRevision)
                if (old.sourceId != current.sourceId || row.sourceId != old.sourceId) reject(OperationErrorCode.INTEGRITY_FAILED, "undo_source_mismatch", old.pageId)
                prepared
            }
            metadata.transaction {
                val doc = activeDocument(entry.documentId)
                if (doc.documentRevision != expectedDocumentRevision) throw Conflict(doc.documentRevision)
                val liveEntry = q.undo(undoId.value) ?: reject(OperationErrorCode.INVALID_INPUT, "undo_unavailable", undoId.value)
                if (liveEntry.state != "AVAILABLE" || !liveEntry.beforeState.contentEquals(entry.beforeState) || !liveEntry.afterState.contentEquals(entry.afterState) || liveEntry.expiresAt <= clock()) throw Conflict(doc.documentRevision)
                val live = targets.mapIndexed { index, target ->
                    val page = verifyLive(target, target.page.currentRecipeRevision) { doc.documentRevision }
                    // Undo creates history, so a prior Undo may have restored this logical after-state at a newer physical revision. Fence the prepared live pointer, then compare complete effective content rather than requiring the old revision number.
                    if (!matchesAfterState(page, after.pages[index])) throw Conflict(doc.documentRevision)
                    if (page.documentId != doc.documentId || page.sourceId != after.pages[index].sourceId) reject(OperationErrorCode.INTEGRITY_FAILED, "undo_source_mismatch", page.pageId)
                    page
                }
                val now = clock(); val nextDoc = next(doc.documentRevision)
                for (index in targets.indices) { val target = targets[index]; append(live[index], target.bytes, target.hash, target.masks.links, now) }
                if (q.updateUndo(liveEntry.copy(state = "APPLIED")) != 1) reject(OperationErrorCode.INTEGRITY_FAILED, "undo_unavailable", undoId.value)
                updateDocument(doc.copy(documentRevision = nextDoc, updatedAt = now))
                DocumentMutationResult.Applied(nextDoc, live.map { PageId(it.pageId) }, null)
            }
        }
    }

    private fun prepare(pageId: String, recipe: Recipe, restoreRevision: Long? = null): Prepared {
        val page = q.page(pageId) ?: reject(OperationErrorCode.SOURCE_UNAVAILABLE, "page_unavailable", pageId)
        if (page.state != "ACTIVE") reject(OperationErrorCode.INVALID_INPUT, "page_not_active", pageId)
        val current = q.recipe(pageId, page.currentRecipeRevision) ?: reject(OperationErrorCode.INTEGRITY_FAILED, "recipe_history_missing", pageId)
        decodeRecipe(current)
        checkBinding(page, recipe)
        // A historical target is a complete recipe-and-mask state. Admit that state from the outset; current masks must never be substituted and swapped back after capability checking.
        val restored = restoreRevision?.let { revision ->
            val row = q.recipe(pageId, revision) ?: reject(OperationErrorCode.INTEGRITY_FAILED, "recipe_history_missing", pageId)
            decodeRecipe(row)
            if (row.sourceId != page.sourceId || !row.canonicalRecipe.contentEquals(RecipeCodec.encode(recipe))) reject(OperationErrorCode.INTEGRITY_FAILED, "recipe_history_changed", pageId)
            row
        }
        val masks = checkedMasks(page.sourceId, pageId, restoreRevision ?: page.currentRecipeRevision)
        if (recipe.geometry is GeometryRecipe.RoughCropDewarp || recipe.appearance is AppearanceRecipe.ProviderAppearanceV1) reject(OperationErrorCode.PROVIDER_UNAVAILABLE, "provider_asset_pinning_not_ready", pageId)
        admission.check(recipe, masks.links.any { it.role == "PROTECTION" })?.let { throw RepositoryFailure(it) }
        return Prepared(page, current, recipe, RecipeCodec.encode(recipe), RecipeCodec.hash(recipe), masks, restored)
    }

    private fun verifyLive(prepared: Prepared, expectedRevision: Long, conflictRevision: (PageRow) -> Long): PageRow {
        val page = q.page(prepared.page.pageId) ?: reject(OperationErrorCode.SOURCE_UNAVAILABLE, "page_unavailable", prepared.page.pageId)
        if (page.currentRecipeRevision != expectedRevision) throw Conflict(conflictRevision(page))
        if (page.state != "ACTIVE" || page.documentId != prepared.page.documentId || page.sourceId != prepared.page.sourceId) reject(OperationErrorCode.INVALID_INPUT, "page_membership_changed", page.pageId)
        checkBinding(page, prepared.recipe)
        val current = q.recipe(page.pageId, page.currentRecipeRevision) ?: reject(OperationErrorCode.INTEGRITY_FAILED, "recipe_history_missing", page.pageId)
        if (current.recipeSchemaVersion != prepared.current.recipeSchemaVersion || current.recipeSha256 != prepared.current.recipeSha256 || !current.canonicalRecipe.contentEquals(prepared.current.canonicalRecipe)) reject(OperationErrorCode.INTEGRITY_FAILED, "recipe_history_changed", page.pageId)
        prepared.restored?.let { expected ->
            val restored = q.recipe(expected.pageId, expected.recipeRevision) ?: reject(OperationErrorCode.INTEGRITY_FAILED, "recipe_history_missing", page.pageId)
            if (restored.sourceId != expected.sourceId || restored.recipeSchemaVersion != expected.recipeSchemaVersion || restored.recipeSha256 != expected.recipeSha256 || !restored.canonicalRecipe.contentEquals(expected.canonicalRecipe)) reject(OperationErrorCode.INTEGRITY_FAILED, "recipe_history_changed", page.pageId)
        }
        // Reference counts may legitimately change between admission and commit; immutable mask identity and its association may not. Revalidate the entire captured metadata set before any command writes, without opening mask files under Room's writer lock.
        if (checkedMasks(page.sourceId, prepared.masks.pageId, prepared.masks.revision) != prepared.masks) reject(OperationErrorCode.INTEGRITY_FAILED, "mask_history_changed", page.pageId)
        return page
    }

    private fun append(page: PageRow, bytes: ByteArray, hash: String, masks: List<RecipeMaskRow>, now: Long): PageRow {
        val revision = next(q.maxRecipeRevision(page.pageId))
        q.insertRecipe(RecipeRevisionRow(page.pageId, revision, page.sourceId, 1, bytes.copyOf(), hash, page.currentRecipeRevision, now))
        val updated = page.copy(currentRecipeRevision = revision)
        updatePage(updated)
        pinSource("RECIPE_HISTORY", recipeOwner(page.pageId, revision), page.sourceId, now)
        copyMasks(masks, page.pageId, revision)
        return updated
    }

    private fun matchesAfterState(page: PageRow, expected: UndoPointer): Boolean {
        if (page.sourceId != expected.sourceId) return false
        val current = q.recipe(page.pageId, page.currentRecipeRevision) ?: return false
        val after = q.recipe(expected.pageId, expected.recipeRevision) ?: return false
        if (current.sourceId != after.sourceId || current.recipeSchemaVersion != after.recipeSchemaVersion || current.recipeSha256 != after.recipeSha256 || !current.canonicalRecipe.contentEquals(after.canonicalRecipe)) return false
        fun maskIdentity(revision: Long) = checkedMasks(page.sourceId, page.pageId, revision).links.map { it.maskId to it.role }.toSet()
        return maskIdentity(page.currentRecipeRevision) == maskIdentity(expected.recipeRevision)
    }

    private fun addUndo(documentId: String, action: String, before: List<UndoPointer>, after: List<UndoPointer>, masks: List<RecipeMaskRow>, revision: Long, now: Long): UndoId {
        val id = UndoId(uuid())
        val expires = try { Math.addExact(now, 86_400_000L) } catch (_: ArithmeticException) { reject(OperationErrorCode.RESOURCE_LIMIT, "retention_clock_overflow") }
        q.insertUndo(UndoEntryRow(id.value, documentId, encodeUndo(before), encodeUndo(after), action, revision, expires, "AVAILABLE"))
        for (source in before.map { it.sourceId }.distinct()) pinSource("UNDO", id.value, source, now)
        for (maskId in masks.map { it.maskId }.distinct()) {
            q.insertMaskReference(MaskReferenceRow("UNDO", id.value, maskId, now))
            if (q.setMaskRefCount(maskId, q.countMaskReferences(maskId)) != 1) reject(OperationErrorCode.INTEGRITY_FAILED, "mask_unavailable", maskId)
        }
        return id
    }

    private fun pinSource(type: String, owner: String, sourceId: String, now: Long) {
        val source = q.source(sourceId) ?: reject(OperationErrorCode.SOURCE_UNAVAILABLE, "source_unavailable", sourceId)
        if (source.state != "READY") reject(OperationErrorCode.INTEGRITY_FAILED, "source_not_ready", sourceId)
        q.insertReference(SourceReferenceRow(type, owner, sourceId, now))
        if (q.setSourceRefCount(sourceId, q.countSourceReferences(sourceId)) != 1) reject(OperationErrorCode.INTEGRITY_FAILED, "source_unavailable", sourceId)
    }

    private fun copyMasks(masks: List<RecipeMaskRow>, pageId: String, revision: Long) {
        for (mask in masks) {
            q.insertRecipeMask(mask.copy(pageId = pageId, recipeRevision = revision))
            if (q.setMaskRefCount(mask.maskId, q.countMaskReferences(mask.maskId)) != 1) reject(OperationErrorCode.INTEGRITY_FAILED, "mask_unavailable", mask.maskId)
        }
    }

    private fun checkedMasks(sourceId: String, pageId: String, revision: Long): MaskState {
        val masks = q.recipeMasks(pageId, revision).sortedBy { it.maskId }
        val source = q.source(sourceId) ?: reject(OperationErrorCode.SOURCE_UNAVAILABLE, "source_unavailable", sourceId)
        val identities = masks.map { link ->
            val mask = q.mask(link.maskId) ?: reject(OperationErrorCode.INTEGRITY_FAILED, "mask_unavailable", link.maskId)
            if (mask.sourceId != sourceId || mask.state != "READY" || mask.width != source.width || mask.height != source.height || mask.kind != link.role || mask.kind !in setOf("PROTECTION", "PAGE_COVERAGE")) reject(OperationErrorCode.INTEGRITY_FAILED, "mask_binding_mismatch", link.maskId)
            mask.copy(refCount = 0)
        }
        return MaskState(pageId, revision, masks, identities)
    }

    private fun checkBinding(page: PageRow, recipe: Recipe) {
        val source = q.source(page.sourceId) ?: reject(OperationErrorCode.SOURCE_UNAVAILABLE, "source_unavailable", page.sourceId)
        if (source.state != "READY") reject(OperationErrorCode.INTEGRITY_FAILED, "source_not_ready", page.sourceId)
        val binding = recipe.geometry.source
        if (binding.sourcePixelHash != source.pixelSha256 || binding.sourceSize.width != source.width || binding.sourceSize.height != source.height || binding.orientationColorConversionVersion != source.conversionFingerprint) reject(OperationErrorCode.INVALID_INPUT, "recipe_source_mismatch", page.pageId)
    }

    private fun supportedRecipe(page: PageRow): Recipe = decodeRecipe(q.recipe(page.pageId, page.currentRecipeRevision) ?: reject(OperationErrorCode.INTEGRITY_FAILED, "recipe_history_missing", page.pageId))
    private fun decodeRecipe(row: RecipeRevisionRow): Recipe {
        if (row.recipeSchemaVersion != 1) reject(OperationErrorCode.UNSUPPORTED_VERSION, "stored_recipe_unsupported", row.pageId)
        val recipe = try { RecipeCodec.decode(row.canonicalRecipe) } catch (_: IllegalArgumentException) { reject(OperationErrorCode.UNSUPPORTED_VERSION, "stored_recipe_unreadable", row.pageId) }
        if (!RecipeCodec.encode(recipe).contentEquals(row.canonicalRecipe) || RecipeCodec.hash(recipe) != row.recipeSha256) reject(OperationErrorCode.INTEGRITY_FAILED, "stored_recipe_integrity_failed", row.pageId)
        return recipe
    }

    private fun activeDocument(id: String): DocumentRow {
        val document = q.document(id) ?: reject(OperationErrorCode.SOURCE_UNAVAILABLE, "document_unavailable", id)
        if (document.lifecycle != "ACTIVE" || document.documentRevision < 1) reject(OperationErrorCode.SOURCE_UNAVAILABLE, "document_not_active", id)
        return document
    }
    private fun updatePage(row: PageRow) { if (q.updatePage(row) != 1) reject(OperationErrorCode.INTEGRITY_FAILED, "page_unavailable", row.pageId) }
    private fun updateDocument(row: DocumentRow) { if (q.updateDocument(row) != 1) reject(OperationErrorCode.INTEGRITY_FAILED, "document_unavailable", row.documentId) }
    private fun pointer(page: PageRow) = UndoPointer(page.pageId, page.sourceId, page.currentRecipeRevision)
    private fun recipeOwner(page: String, revision: Long): String = "recipe-v1/$page/$revision"
    private fun encodeUndo(pages: List<UndoPointer>): ByteArray = json.encodeToString(RecipeUndoState.serializer(), RecipeUndoState(pages = pages)).toByteArray(Charsets.UTF_8)
    private fun decodeUndo(bytes: ByteArray): RecipeUndoState {
        if (bytes.size !in 1..524288) reject(OperationErrorCode.INTEGRITY_FAILED, "invalid_undo_metadata")
        val state = try { json.decodeFromString(RecipeUndoState.serializer(), bytes.toString(Charsets.UTF_8)) } catch (_: Exception) { reject(OperationErrorCode.INTEGRITY_FAILED, "invalid_undo_metadata") }
        if (state.version != 1 || state.pages.isEmpty() || state.pages.size > 100 || state.pages.map { it.pageId }.toSet().size != state.pages.size) reject(OperationErrorCode.INTEGRITY_FAILED, "invalid_undo_metadata")
        try { for (page in state.pages) { PageId(page.pageId); SourceId(page.sourceId); require(page.recipeRevision >= 1) } }
        catch (_: IllegalArgumentException) { reject(OperationErrorCode.INTEGRITY_FAILED, "invalid_undo_metadata") }
        return state
    }
    private fun next(value: Long): Long {
        if (value < 1) reject(OperationErrorCode.INTEGRITY_FAILED, "invalid_stored_revision")
        return try { Math.addExact(value, 1L) } catch (_: ArithmeticException) { reject(OperationErrorCode.RESOURCE_LIMIT, "revision_limit") }
    }
    private fun uuid(): String = UUID.randomUUID().toString()
    private fun reject(code: OperationErrorCode, key: String, id: String? = null, retryable: Boolean = false): Nothing = throw RepositoryFailure(OperationError(code, retryable, key, id))
    private inline fun <T> command(conflict: (Long) -> T, rejected: (OperationError) -> T, block: () -> T): T = try { block() }
    catch (canceled: CancellationException) { throw canceled }
    catch (conflictResult: Conflict) { conflict(conflictResult.actual) }
    catch (failure: RepositoryFailure) { rejected(failure.error) }
    catch (_: Exception) { rejected(OperationError(OperationErrorCode.STORAGE_FAILED, true, "metadata_write_failed", null)) }
    private fun documentCommand(block: () -> DocumentMutationResult): DocumentMutationResult = command({ DocumentMutationResult.Conflict(it) }, { DocumentMutationResult.Rejected(it) }, block)
}
