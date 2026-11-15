package app.pristinepage.data.repository

import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.data.db.*
import app.pristinepage.data.model.*

/** A short synchronous metadata transaction. Callers run IO outside this block; Room supplies the real rollback/serialization boundary and host tests substitute only the unavailable Android database. */
interface MetadataAccess {
    val queries: CatalogQueries
    fun <T> transaction(action: () -> T): T
}

interface CatalogQueries {
    fun document(id: String): DocumentRow?
    fun retainedDocumentIdsAfter(objectId: String?, limit: Int): List<String>
    fun activePages(documentId: String): List<PageRow>
    fun page(id: String): PageRow?
    fun source(id: String): SourceRow?
    fun sourceBlob(id: String): SourceBlobRow?
    fun recipe(pageId: String, revision: Long): RecipeRevisionRow?
    fun maxRecipeRevision(pageId: String): Long
    fun recipeMasks(pageId: String, revision: Long): List<RecipeMaskRow>
    fun mask(id: String): MaskRow?
    fun undo(id: String): UndoEntryRow?
    fun hasPendingImport(documentId: String): Boolean
    fun insertDocument(row: DocumentRow)
    fun updateDocument(row: DocumentRow): Int
    fun insertPage(row: PageRow)
    fun updatePage(row: PageRow): Int
    fun insertRecipe(row: RecipeRevisionRow)
    fun insertReference(row: SourceReferenceRow): Long
    fun countSourceReferences(sourceId: String): Long
    fun setSourceRefCount(sourceId: String, count: Long): Int
    fun insertUndo(row: UndoEntryRow)
    fun updateUndo(row: UndoEntryRow): Int
    fun countUnexpiredUndoEntries(documentId: String): Long
    fun expireUndoEntries(documentId: String, now: Long): Int
    fun undoIsWithinWindow(documentId: String, undoId: String): Boolean
    fun insertRecipeMask(row: RecipeMaskRow)
    fun insertMaskReference(row: MaskReferenceRow): Long
    fun countMaskReferences(maskId: String): Long
    fun setMaskRefCount(maskId: String, count: Long): Int
}

/** Inject the renderer's bounded structural/capability preflight; it runs before the write transaction and cannot turn a gated provider into an available one. Source/membership/revisions are rechecked transactionally by data. */
fun interface RecipeAdmission {
    fun check(recipe: Recipe, hasProtection: Boolean): OperationError?
}

/** Growing T-03 command surface; export/session/recovery contracts are added with their implementations before task acceptance, never as successful no-op stubs. */
interface DocumentRepository {
    suspend fun createDocument(title: String): DocumentId
    suspend fun reconcile(): RecoveryReport
    suspend fun saveRecipe(pageId: PageId, expectedRevision: Long, recipe: Recipe): SaveRecipeResult
    suspend fun applyBatchRecipes(documentId: DocumentId, expectedDocumentRevision: Long, changes: List<BatchRecipeChange>): DocumentMutationResult
    suspend fun duplicatePage(pageId: PageId, expectedDocumentRevision: Long): DocumentMutationResult
    suspend fun renameDocument(documentId: DocumentId, expectedDocumentRevision: Long, title: String): DocumentMutationResult
    suspend fun reorderPages(documentId: DocumentId, expectedDocumentRevision: Long, pageIds: List<PageId>): DocumentMutationResult
    suspend fun rotatePage(pageId: PageId, expectedRecipeRevision: Long, clockwiseQuarterTurns: Int): DocumentMutationResult
    suspend fun removePage(pageId: PageId, expectedDocumentRevision: Long): DocumentMutationResult
    suspend fun undoDocumentEdit(undoId: UndoId, expectedDocumentRevision: Long): DocumentMutationResult
}
