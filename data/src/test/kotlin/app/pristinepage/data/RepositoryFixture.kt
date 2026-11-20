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
internal class MemoryMetadata : MetadataAccess, CatalogQueries, EditablePageQueries {
    val hiddenDraftDocuments = mutableSetOf<String>()
    override fun isHiddenAtelierDraft(documentId: String): Boolean {
        check(inTransaction)
        return documentId in hiddenDraftDocuments
    }
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
    var afterRecipeInsert: () -> Unit = {}
    var beforeRecipeMasksRead: () -> Unit = {}
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
    override fun retainedDocumentIdsAfter(objectId: String?, limit: Int): List<String> {
        check(inTransaction); check(limit in 1..64)
        return documents.keys.filter { objectId == null || it > objectId }.sorted().take(limit)
    }
    override fun activePages(documentId: String) = pages.values.filter { it.documentId == documentId && it.state == "ACTIVE" }.sortedBy { it.ordinal }
    override fun page(id: String) = pages[id]
    override fun source(id: String) = sources[id]
    override fun sourceBlob(id: String) = blobs[id]
    // Kotlin rows cannot represent SQLite storage-class/Int-narrowing adversaries. These checks model bounded fixture rows only; actual DAO SQL/native Room tests remain the raw-boundary evidence. Every guard still requires the same metadata transaction as its consumer.
    private fun text(value: String, maximum: Int) = value.toByteArray(Charsets.UTF_8).size in 1..maximum
    private fun id(value: String) = value.length == 36 && text(value, 36)
    override fun pageReadIsBounded(pageId: String): Boolean? {
        check(inTransaction)
        return pages[pageId]?.let { id(it.pageId) && id(it.documentId) && id(it.sourceId) && it.currentRecipeRevision > 0 && ((it.state == "ACTIVE" && it.ordinal in 0..99 && it.removedAt == null) || (it.state == "REMOVED" && it.ordinal == null && it.removedAt != null)) }
    }
    override fun documentReadIsBounded(documentId: String): Boolean? {
        check(inTransaction)
        return documents[documentId]?.let { id(it.documentId) && it.title.length <= 160 && text(it.title, 640) && it.documentRevision > 0 && it.lifecycle in setOf("ACTIVE", "TOMBSTONED", "PURGING") }
    }
    override fun sourceReadIsBounded(id: String): Boolean? {
        check(inTransaction)
        return sources[id]?.let { s ->
            val b = blobs[s.sourceBlobId]
            id(s.sourceId) && id(s.sourceBlobId) && s.state == "READY" && s.pixelSha256.length == 64 && text(s.pixelSha256,64) && s.width in 1..12000 && s.height in 1..12000 && s.width.toLong()*s.height <= 48_000_000 && s.exifOrientation in 1..8 && s.conversionFingerprint.length in 1..128 && text(s.conversionFingerprint,512) && s.refCount >= 0 && (s.profileSha256 == null || (s.profileSha256.length == 64 && text(s.profileSha256,64))) &&
                b != null && id(b.sourceBlobId) && b.state == "READY" && b.byteSha256.length == 64 && text(b.byteSha256,64) && b.byteCount in 1..104857600L && b.mime in setOf("image/png","image/jpeg","image/webp") && b.encodedWidth in 1..12000 && b.encodedHeight in 1..12000 && b.encodedWidth.toLong()*b.encodedHeight <= 48_000_000 && b.relativePath == "sources/${b.sourceBlobId}/original.bin" && b.sourceCount >= 0 && id(b.ingestTransactionId)
        }
    }
    override fun sourceBlobLinkIsMissing(id: String): Boolean? {
        check(inTransaction)
        val key = sources[id]?.sourceBlobId ?: return null
        return runCatching { requireOpaqueId(key) }.isSuccess && key !in blobs
    }
    override fun recipeReadIsBounded(pageId: String, revision: Long): Boolean? {
        check(inTransaction)
        return recipes[pageId to revision]?.let { id(it.pageId) && id(it.sourceId) && it.recipeRevision > 0 && it.recipeSchemaVersion > 0 && it.canonicalRecipe.size in 1..524288 && it.recipeSha256.length == 64 && text(it.recipeSha256,64) && (it.parentRevision == null || (it.parentRevision > 0 && it.parentRevision < it.recipeRevision)) }
    }
    override fun recipeBlobByteCount(pageId: String, revision: Long): Long? {
        check(inTransaction)
        return recipes[pageId to revision]?.canonicalRecipe?.size?.toLong()
    }
    override fun recipeHasMasks(pageId: String, revision: Long): Boolean { check(inTransaction); return associations.values.any { it.pageId == pageId && it.recipeRevision == revision } }
    override fun activePageCount(documentId: String): Long { check(inTransaction); return activePages(documentId).size.toLong() }
    override fun activePagesReadIsBounded(documentId: String): Boolean { check(inTransaction); val rows = activePages(documentId); return rows.size <= 100 && rows.all { pageReadIsBounded(it.pageId) == true } && rows.map { it.ordinal } == rows.indices.toList() }
    override fun activeDocumentIdsAfter(after: String?, limit: Int): List<String> { check(inTransaction); return documents.values.filter { it.lifecycle == "ACTIVE" && (after == null || it.documentId > after) }.sortedBy { it.documentId }.take(limit).map { it.documentId } }
    override fun activeImportIds(documentId: String): List<String> = error("Recipe fixture has no import-session rows")
    override fun recipe(pageId: String, revision: Long) = recipes[pageId to revision]
    override fun maxRecipeRevision(pageId: String) = recipes.keys.filter { it.first == pageId }.maxOfOrNull { it.second } ?: 0
    override fun recipeMasks(pageId: String, revision: Long): List<RecipeMaskRow> {
        beforeRecipeMasksRead()
        return associations.values.filter { it.pageId == pageId && it.recipeRevision == revision }
    }
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
        afterRecipeInsert()
    }
    override fun insertReference(row: SourceReferenceRow): Long = if (references.putIfAbsent(Triple(row.ownerType, row.ownerId, row.sourceId), row) == null) 1 else -1
    override fun countSourceReferences(sourceId: String): Long = references.values.count { it.sourceId == sourceId }.toLong()
    override fun setSourceRefCount(sourceId: String, count: Long): Int { val row = sources[sourceId] ?: return 0; sources[sourceId] = row.copy(refCount = count); return 1 }
    override fun insertUndo(row: UndoEntryRow) { check(undos.putIfAbsent(row.undoId, row) == null) }
    override fun updateUndo(row: UndoEntryRow): Int { if (row.undoId !in undos) return 0; undos[row.undoId] = row; return 1 }
    override fun countUnexpiredUndoEntries(documentId: String): Long = undos.values.count { it.documentId == documentId && it.state in setOf("AVAILABLE", "APPLIED") }.toLong()
    override fun expireUndoEntries(documentId: String, now: Long): Int {
        check(inTransaction)
        val newest = undos.values.filter { it.documentId == documentId }.sortedWith(compareByDescending<UndoEntryRow> { it.appliedDocumentRevision }.thenByDescending { it.undoId }).take(20).map { it.undoId }.toSet()
        val expired = undos.values.filter { it.documentId == documentId && it.state in setOf("AVAILABLE", "APPLIED") && (it.expiresAt <= now || it.undoId !in newest) }
        for (row in expired) undos[row.undoId] = row.copy(state = "EXPIRED")
        return expired.size
    }
    override fun undoIsWithinWindow(documentId: String, undoId: String): Boolean = undos.values.filter { it.documentId == documentId }.sortedWith(compareByDescending<UndoEntryRow> { it.appliedDocumentRevision }.thenByDescending { it.undoId }).take(20).any { it.undoId == undoId }
    override fun insertRecipeMask(row: RecipeMaskRow) { check(associations.putIfAbsent(Triple(row.pageId, row.recipeRevision, row.maskId), row) == null) }
    override fun insertMaskReference(row: MaskReferenceRow): Long = if (maskReferences.putIfAbsent(Triple(row.ownerType, row.ownerId, row.maskId), row) == null) 1 else -1
    override fun countMaskReferences(maskId: String): Long = associations.values.count { it.maskId == maskId }.toLong() + maskReferences.values.count { it.maskId == maskId }
    override fun setMaskRefCount(maskId: String, count: Long): Int { val row = masks[maskId] ?: return 0; masks[maskId] = row.copy(refCount = count); return 1 }
}

internal class RepositoryFixture(count: Int = 2, clock: () -> Long = { 1000L }, admission: ((Recipe, Boolean) -> OperationError?)? = null) {
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
        repository = try { type.constructors.single { it.parameterCount == 4 }.newInstance(metadata, gate, clock, Dispatchers.Unconfined) as DocumentRepository }
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
