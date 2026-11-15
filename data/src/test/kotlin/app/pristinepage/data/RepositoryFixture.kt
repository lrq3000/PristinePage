package app.pristinepage.data

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.data.db.*
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import kotlinx.coroutines.Dispatchers

/** Only Android Room is substituted. Full production row types, canonical codec and repository business commands remain real; later actual Room rollback/process-death tests must validate this port's platform adapter. */
internal class MemoryMetadata : MetadataAccess, CatalogQueries {
    val documents = linkedMapOf<String, DocumentRow>()
    val pages = linkedMapOf<String, PageRow>()
    val sources = linkedMapOf<String, SourceRow>()
    val blobs = linkedMapOf<String, SourceBlobRow>()
    val recipes = linkedMapOf<Pair<String, Long>, RecipeRevisionRow>()
    val references = linkedMapOf<Triple<String, String, String>, SourceReferenceRow>()
    val undos = linkedMapOf<String, UndoEntryRow>()
    val masks = linkedMapOf<String, MaskRow>()
    val associations = linkedMapOf<Triple<String, Long, String>, RecipeMaskRow>()
    val maskReferences = linkedMapOf<Triple<String, String, String>, MaskReferenceRow>()
    val pendingImports = mutableSetOf<String>()
    var inTransaction = false
    var failRecipeInsertAt: Int? = null
    var failPageUpdateAt: Int? = null
    private var recipeWrites = 0
    private var pageWrites = 0
    override val queries: CatalogQueries get() = this

    @Synchronized override fun <T> transaction(action: () -> T): T {
        check(!inTransaction)
        val d = documents.toMap(); val p = pages.toMap(); val s = sources.toMap(); val b = blobs.toMap(); val r = recipes.toMap()
        val refs = references.toMap(); val u = undos.toMap(); val m = masks.toMap(); val a = associations.toMap(); val mr = maskReferences.toMap()
        inTransaction = true
        try { return action() }
        catch (failure: Throwable) {
            fun <K, V> restore(target: MutableMap<K, V>, before: Map<K, V>) { target.clear(); target.putAll(before) }
            restore(documents, d); restore(pages, p); restore(sources, s); restore(blobs, b); restore(recipes, r)
            restore(references, refs); restore(undos, u); restore(masks, m); restore(associations, a); restore(maskReferences, mr)
            throw failure
        } finally { inTransaction = false }
    }

    override fun document(id: String) = documents[id]
    override fun activePages(documentId: String) = pages.values.filter { it.documentId == documentId && it.state == "ACTIVE" }.sortedBy { it.ordinal }
    override fun page(id: String) = pages[id]
    override fun source(id: String) = sources[id]
    override fun sourceBlob(id: String) = blobs[id]
    override fun recipe(pageId: String, revision: Long) = recipes[pageId to revision]
    override fun maxRecipeRevision(pageId: String) = recipes.keys.filter { it.first == pageId }.maxOfOrNull { it.second } ?: 0
    override fun recipeMasks(pageId: String, revision: Long) = associations.values.filter { it.pageId == pageId && it.recipeRevision == revision }
    override fun mask(id: String) = masks[id]
    override fun undo(id: String) = undos[id]
    override fun hasPendingImport(documentId: String) = documentId in pendingImports
    override fun insertDocument(row: DocumentRow) { check(documents.putIfAbsent(row.documentId, row) == null) }
    override fun updateDocument(row: DocumentRow): Int { if (row.documentId !in documents) return 0; documents[row.documentId] = row; return 1 }
    override fun insertPage(row: PageRow) { check(pages.putIfAbsent(row.pageId, row) == null) }
    override fun updatePage(row: PageRow): Int { if (++pageWrites == failPageUpdateAt) throw IOException("synthetic ordinal update failure"); if (row.pageId !in pages) return 0; pages[row.pageId] = row; return 1 }
    override fun insertRecipe(row: RecipeRevisionRow) {
        if (++recipeWrites == failRecipeInsertAt) throw IOException("synthetic database write failure")
        check(recipes.putIfAbsent(row.pageId to row.recipeRevision, row) == null)
    }
    override fun insertReference(row: SourceReferenceRow): Long = if (references.putIfAbsent(Triple(row.ownerType, row.ownerId, row.sourceId), row) == null) 1 else -1
    override fun countSourceReferences(sourceId: String): Long = references.values.count { it.sourceId == sourceId }.toLong()
    override fun setSourceRefCount(sourceId: String, count: Long): Int { val row = sources[sourceId] ?: return 0; sources[sourceId] = row.copy(refCount = count); return 1 }
    override fun insertUndo(row: UndoEntryRow) { check(undos.putIfAbsent(row.undoId, row) == null) }
    override fun updateUndo(row: UndoEntryRow): Int { if (row.undoId !in undos) return 0; undos[row.undoId] = row; return 1 }
    override fun insertRecipeMask(row: RecipeMaskRow) { check(associations.putIfAbsent(Triple(row.pageId, row.recipeRevision, row.maskId), row) == null) }
    override fun insertMaskReference(row: MaskReferenceRow): Long = if (maskReferences.putIfAbsent(Triple(row.ownerType, row.ownerId, row.maskId), row) == null) 1 else -1
    override fun countMaskReferences(maskId: String): Long = associations.values.count { it.maskId == maskId }.toLong() + maskReferences.values.count { it.maskId == maskId }
    override fun setMaskRefCount(maskId: String, count: Long): Int { val row = masks[maskId] ?: return 0; masks[maskId] = row.copy(refCount = count); return 1 }
}

internal class RepositoryFixture(count: Int = 2, admission: ((Recipe, Boolean) -> OperationError?)? = null) {
    val metadata = MemoryMetadata()
    val document = DocumentId(UUID.randomUUID().toString())
    val source = SourceId(UUID.randomUUID().toString())
    val pageIds = List(count) { PageId(UUID.randomUUID().toString()) }
    val binding = SourceBinding("a".repeat(64), PixelSize(3, 2), "srgb-v1")
    val initial = Recipe(geometry = GeometryRecipe.None(binding))
    val repository: DocumentRepository

    init {
        metadata.documents[document.value] = DocumentRow(document.value, "Synthetic", 1, "ACTIVE", 0, 0, null)
        val blob = UUID.randomUUID().toString()
        metadata.sources[source.value] = SourceRow(source.value, blob, binding.sourcePixelHash, 3, 2, 1, "srgb-v1", null, count * 2L, "READY", 0)
        metadata.blobs[blob] = SourceBlobRow(blob, "b".repeat(64), 4, "image/png", 3, 2, "sources/$blob/original.bin", "READY", 1, UUID.randomUUID().toString(), 0)
        for ((ordinal, page) in pageIds.withIndex()) {
            metadata.pages[page.value] = PageRow(page.value, document.value, source.value, 1, "ACTIVE", ordinal, null, 0)
            metadata.recipes[page.value to 1L] = RecipeRevisionRow(page.value, 1, source.value, 1, RecipeCodec.encode(initial), RecipeCodec.hash(initial), null, 0)
            for ((ownerType, ownerId) in listOf("PAGE" to page.value, "RECIPE_HISTORY" to "recipe-v1/${page.value}/1")) {
                val ref = SourceReferenceRow(ownerType, ownerId, source.value, 0)
                metadata.references[Triple(ownerType, ownerId, source.value)] = ref
            }
        }
        val type = try { Class.forName("app.pristinepage.data.repository.RoomDocumentRepository") }
        catch (error: ClassNotFoundException) { throw AssertionError("Transactional document repository is absent", error) }
        val gate = RecipeAdmission { recipe, protected ->
            check(!metadata.inTransaction) { "Expensive/capability preflight must finish before the write transaction" }
            admission?.invoke(recipe, protected)
        }
        repository = try { type.constructors.single { it.parameterCount == 4 }.newInstance(metadata, gate, { 1000L }, Dispatchers.Unconfined) as DocumentRepository }
        catch (error: InvocationTargetException) { throw error.targetException }
    }

    fun turned(q: Int) = initial.copy(geometry = initial.geometry.withOutputQuarterTurns(q))
    fun current(page: PageId): Recipe {
        val row = checkNotNull(metadata.pages[page.value])
        return RecipeCodec.decode(checkNotNull(metadata.recipes[page.value to row.currentRecipeRevision]).canonicalRecipe)
    }
    fun change(page: PageId, q: Int, revision: Long = 1) = BatchRecipeChange(page, revision, turned(q))

    fun protectionMask(page: PageId, revision: Long, attach: Boolean = true): String {
        val id = UUID.randomUUID().toString()
        metadata.masks[id] = MaskRow(id, source.value, "PROTECTION", "b".repeat(64), 3, 2, "masks/$id/mask.bin", 1, if (attach) 1 else 0, "READY")
        if (attach) metadata.associations[Triple(page.value, revision, id)] = RecipeMaskRow(page.value, revision, id, "PROTECTION")
        metadata.references[Triple("MASK", id, source.value)] = SourceReferenceRow("MASK", id, source.value, 0)
        metadata.sources[source.value] = metadata.sources[source.value]!!.copy(refCount = metadata.countSourceReferences(source.value))
        return id
    }
}
