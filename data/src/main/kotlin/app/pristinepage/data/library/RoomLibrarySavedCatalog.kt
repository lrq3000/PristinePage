package app.pristinepage.data.library

import androidx.sqlite.db.SupportSQLiteDatabase
import app.pristinepage.core.library.LibraryScanName
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.requireSha256
import app.pristinepage.core.pipeline.requireWellFormedUnicode
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.data.db.DocumentRow
import app.pristinepage.data.db.LibraryScanNameRow
import app.pristinepage.data.db.PageRow
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.db.RecipeRevisionRow
import app.pristinepage.data.db.SourceBlobRow
import app.pristinepage.data.db.SourceCaptureDayRow
import app.pristinepage.data.db.SourceReferenceRow
import app.pristinepage.data.db.SourceRow
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.RoomMetadataAccess
import app.pristinepage.data.repository.RoomOcrSearch
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** App-private saved metadata projection only. No input file is opened, and a scratch database written here is not a backup until its objects and closed catalog are verified. */
internal class RoomLibrarySavedCatalog(private val database: PristineDatabase) {
    private val metadata = RoomMetadataAccess(database)
    private val catalog = database.catalog()
    private val names = database.libraryScanNames()
    private val ocr = RoomOcrSearch(database)

    internal class Snapshot(
        albums: List<Album>, sources: List<SourceRow>, originals: List<SourceBlobRow>, days: List<SourceCaptureDayRow>,
    ) {
        val albums = owned(albums)
        val sources = owned(sources)
        val originals = owned(originals)
        val days = owned(days)
    }

    internal class Album(val row: DocumentRow, pages: List<Page>) { val pages = owned(pages) }
    internal class Page(val row: PageRow, val filename: String, revisions: List<RecipeRevisionRow>, val savedOcr: LibrarySavedOcr?) {
        private val admitted = revisions.map { it.copy(canonicalRecipe = it.canonicalRecipe.copyOf()) }
        val revisions: List<RecipeRevisionRow> get() = admitted.map { it.copy(canonicalRecipe = it.canonicalRecipe.copyOf()) }
        val revisionCount: Int get() = admitted.size
    }

    suspend fun capture(): Snapshot = withContext(Dispatchers.IO) {
        val checkpoint = { coroutineContext.ensureActive() }
        metadata.transaction {
            val sql = database.openHelper.readableDatabase
            LibrarySnapshotAdmission.verifyInTransaction(sql, checkpoint)
            val originals = LinkedHashMap<String, SourceBlobRow>()
            val sources = LinkedHashMap<String, SourceRow>()
            val days = ArrayList<SourceCaptureDayRow>()
            var metadataBytes = 0L
            var pageCount = 0
            fun charge(bytes: Long) {
                check(bytes >= 0)
                metadataBytes += bytes
                if (metadataBytes > 16L * 1024 * 1024) limit()
            }
            val ids = catalog.activeDocumentIdsAfter(null, MAX_ALBUMS + 1)
            if (ids.size > MAX_ALBUMS) limit()
            val albums = ids.map { id ->
                checkpoint()
                val documentId = DocumentId(id)
                if (metadata.isHiddenAtelierDraft(id)) invalid()
                if (catalog.documentReadIsBounded(id) != true || !catalog.activePagesReadIsBounded(id)) invalid()
                val album = catalog.document(id)?.takeIf { it.lifecycle == "ACTIVE" && it.deletedAt == null } ?: invalid()
                requireWellFormedUnicode(album.title)
                if (album.title.isBlank() || album.title.length > 160) invalid()
                val active = catalog.activePages(id)
                val retained = removedPages(sql, id, MAX_PAGES - pageCount - active.size)
                val rows = active + retained
                pageCount += rows.size
                if (pageCount > MAX_PAGES) limit()
                val existing = rows.associateWith { page ->
                    names.storedName(page.pageId, id)?.also { if (!metadata.libraryScanNames.matches(page.pageId, id, it)) invalid() }
                }
                val occupied = existing.values.filterNotNull().mapTo(HashSet(), LibraryScanName::comparisonKey)
                val pages = rows.mapIndexed { ordinal, page ->
                    checkpoint()
                    val pageId = PageId(page.pageId)
                    if (page.ordinal != ordinal.takeIf { it < active.size } || page.documentId != id) invalid()
                    val source = sources.getOrPut(page.sourceId) {
                        SourceId(page.sourceId)
                        if (catalog.sourceReadIsBounded(page.sourceId) != true) invalid()
                        val row = catalog.source(page.sourceId) ?: invalid()
                        originals.getOrPut(row.sourceBlobId) {
                            val blob = catalog.sourceBlob(row.sourceBlobId) ?: invalid()
                            SourceBlobId(blob.sourceBlobId)
                            TransactionId(blob.ingestTransactionId)
                            requireSha256(blob.byteSha256)
                            captureDay(sql, blob)?.let(days::add)
                            blob
                        }
                        row
                    }
                    val blob = originals.getValue(source.sourceBlobId)
                    val inspection = SourceInspection(blob.mime, PixelSize(blob.encodedWidth, blob.encodedHeight), PixelSize(source.width, source.height),
                        source.exifOrientation, source.profileSha256, source.conversionFingerprint, source.pixelSha256)
                    val logicalName = existing[page] ?: LibraryScanName.forIngest(null, page.pageId, blob.mime, occupied::contains).also {
                        occupied += LibraryScanName.comparisonKey(it)
                    }
                    val binding = SourceBinding(inspection.sourcePixelHash, inspection.normalizedSize, inspection.conversionFingerprint)
                    val revisions = recipeRevisions(sql, page, binding, ::charge, checkpoint)
                    val saved = ocr.captureForLibraryInTransaction(documentId, pageId, page.currentRecipeRevision)
                    if (saved != null) charge(4L * saved.text.value.length + saved.intent.encode().size + 256)
                    charge(256 + 4L * logicalName.length)
                    Page(page, logicalName, revisions, saved)
                }
                charge(192 + 4L * album.title.length)
                Album(album, pages)
            }
            checkpoint()
            Snapshot(albums, sources.values.toList(), originals.values.toList(), days)
        }
    }

    suspend fun writeFresh(snapshot: Snapshot) = withContext(Dispatchers.IO) {
        val checkpoint = { coroutineContext.ensureActive() }
        metadata.transaction {
            val sql = database.openHelper.writableDatabase
            if (hasRow(sql, NONEMPTY_STAGE)) {
                throw RepositoryFailure(OperationError(OperationErrorCode.REVISION_CONFLICT, false, "library_stage_not_empty", null))
            }
            val ingest = database.ingestCompletion()
            val sourceCounts = snapshot.sources.groupingBy { it.sourceBlobId }.eachCount()
            for (original in snapshot.originals) {
                checkpoint()
                ingest.insertSourceBlob(original.copy(sourceCount = sourceCounts.getValue(original.sourceBlobId).toLong()))
            }
            snapshot.days.forEach { ingest.insertCaptureDay(it) }
            val refCounts = HashMap<String, Long>()
            for (album in snapshot.albums) for (page in album.pages) {
                refCounts.merge(page.row.sourceId, 1L + page.revisionCount, Long::plus)
            }
            for (source in snapshot.sources) {
                checkpoint()
                ingest.insertSource(source.copy(refCount = refCounts.getValue(source.sourceId)))
            }
            val savedOcr = RoomLibrarySavedOcr(database)
            for (album in snapshot.albums) {
                checkpoint()
                catalog.insertDocument(album.row)
                for (page in album.pages) {
                    checkpoint()
                    val row = page.row
                    catalog.insertPage(row)
                    names.insert(LibraryScanNameRow(row.pageId, row.documentId, page.filename, LibraryScanName.comparisonKey(page.filename)))
                    if (catalog.insertReference(SourceReferenceRow("PAGE", row.pageId, row.sourceId, row.createdAt)) <= 0) invalid()
                    for (revision in page.revisions) {
                        checkpoint()
                        catalog.insertRecipe(revision)
                        if (catalog.insertReference(SourceReferenceRow("RECIPE_HISTORY", "recipe-v1/${row.pageId}/${revision.recipeRevision}", row.sourceId, revision.createdAt)) <= 0) invalid()
                    }
                    page.savedOcr?.let(savedOcr::restoreInTransaction)
                }
            }
            for ((source, count) in refCounts) if (catalog.countSourceReferences(source) != count) invalid()
            sql.query("PRAGMA foreign_key_check").use { if (it.moveToFirst()) invalid() }
            checkpoint()
        }
    }

    private fun removedPages(sql: SupportSQLiteDatabase, document: String, remaining: Int): List<PageRow> {
        if (remaining < 0) limit()
        val ids = ArrayList<String>()
        sql.query("""SELECT CASE WHEN typeof(pageId)='text' AND length(CAST(pageId AS BLOB))=36 THEN pageId ELSE '' END
            FROM Page WHERE documentId=? AND state IS NOT 'ACTIVE' ORDER BY pageId LIMIT ${remaining + 1}""",
            arrayOf(document)).use { while (it.moveToNext()) ids += it.getString(0) }
        if (ids.size > remaining) limit()
        return ids.map { id ->
            PageId(id)
            if (catalog.pageReadIsBounded(id) != true) invalid()
            catalog.page(id)?.takeIf { it.state == "REMOVED" && it.ordinal == null && it.documentId == document } ?: invalid()
        }
    }

    private fun recipeRevisions(
        sql: SupportSQLiteDatabase, page: PageRow, binding: SourceBinding, charge: (Long) -> Unit, checkpoint: () -> Unit,
    ): List<RecipeRevisionRow> {
        val revisions = ArrayList<Long>()
        sql.query("""SELECT CASE WHEN typeof(recipeRevision)='integer' AND recipeRevision>0 THEN recipeRevision ELSE 0 END
            FROM RecipeRevision WHERE pageId=? ORDER BY recipeRevision LIMIT ${MAX_REVISIONS + 1}""", arrayOf(page.pageId)).use {
            while (it.moveToNext()) revisions += it.getLong(0)
        }
        if (revisions.size > MAX_REVISIONS) limit()
        if (revisions.isEmpty() || page.currentRecipeRevision !in revisions) invalid()
        val earlier = HashSet<Long>()
        return revisions.map { id ->
            checkpoint()
            if (catalog.recipeReadIsBounded(page.pageId, id) != true) invalid()
            val size = catalog.recipeBlobByteCount(page.pageId, id) ?: invalid()
            charge(size + 128)
            if (catalog.recipeHasMasks(page.pageId, id)) unavailable("library_masks_not_supported")
            val row = catalog.recipe(page.pageId, id)?.takeIf { it.sourceId == page.sourceId } ?: invalid()
            if (row.recipeSchemaVersion != 1) unavailable("library_recipe_not_supported")
            val recipe = try { RecipeCodec.decode(row.canonicalRecipe) }
            catch (_: IllegalArgumentException) { unavailable("library_recipe_not_supported") }
            if (recipe.geometry.source != binding || !RecipeCodec.encode(recipe).contentEquals(row.canonicalRecipe) ||
                RecipeCodec.hash(recipe) != row.recipeSha256 || (row.parentRevision != null && row.parentRevision !in earlier)
            ) invalid()
            earlier += id
            row
        }
    }

    private fun captureDay(sql: SupportSQLiteDatabase, blob: SourceBlobRow): SourceCaptureDayRow? =
        sql.query("""SELECT CASE WHEN typeof(byteSha256)='text' AND byteSha256=? AND (epochDay IS NULL OR
            (typeof(epochDay)='integer' AND epochDay BETWEEN -719162 AND 2932896)) THEN 1 ELSE 0 END,
            CASE WHEN typeof(epochDay)='integer' THEN epochDay END FROM SourceCaptureDay WHERE sourceBlobId=?""",
            arrayOf(blob.byteSha256, blob.sourceBlobId)).use {
            if (!it.moveToFirst()) null else {
                if (it.getInt(0) != 1) invalid()
                SourceCaptureDayRow(blob.sourceBlobId, blob.byteSha256, if (it.isNull(1)) null else it.getLong(1))
            }
        }

    private fun hasRow(sql: SupportSQLiteDatabase, query: String, vararg arguments: String): Boolean =
        sql.query(query, arguments).use { it.moveToFirst() }

    private fun invalid(): Nothing = fail(OperationErrorCode.INTEGRITY_FAILED, "library_saved_catalog_invalid")
    private fun unavailable(key: String): Nothing = fail(OperationErrorCode.UNSUPPORTED_VERSION, key)
    private fun limit(): Nothing = fail(OperationErrorCode.RESOURCE_LIMIT, "library_saved_catalog_limit")
    private fun fail(code: OperationErrorCode, key: String): Nothing = throw RepositoryFailure(OperationError(code, false, key, null))

    private companion object {
        // Include independent transaction/pin/index tables: neither foreign keys nor a zero album count establishes a clean stage.
        const val NONEMPTY_STAGE = """SELECT 1 FROM Document UNION ALL SELECT 1 FROM Page
            UNION ALL SELECT 1 FROM Source UNION ALL SELECT 1 FROM SourceBlob UNION ALL SELECT 1 FROM LibraryScanName
            UNION ALL SELECT 1 FROM RecipeRevision UNION ALL SELECT 1 FROM RecipeMask UNION ALL SELECT 1 FROM Mask
            UNION ALL SELECT 1 FROM ImportSession UNION ALL SELECT 1 FROM UndoEntry UNION ALL SELECT 1 FROM Confirmation
            UNION ALL SELECT 1 FROM SourceReference UNION ALL SELECT 1 FROM MaskReference UNION ALL SELECT 1 FROM RenderReference
            UNION ALL SELECT 1 FROM AssetPin UNION ALL SELECT 1 FROM Job UNION ALL SELECT 1 FROM OcrPageWork
            UNION ALL SELECT 1 FROM OcrResult UNION ALL SELECT 1 FROM OcrTextPublication UNION ALL SELECT 1 FROM SourceCaptureDay
            UNION ALL SELECT 1 FROM AtelierDraft UNION ALL SELECT 1 FROM FileTransaction UNION ALL SELECT 1 FROM Publication
            UNION ALL SELECT 1 FROM RenderedPage UNION ALL SELECT 1 FROM ExportSnapshot UNION ALL SELECT 1 FROM ExportPage
            UNION ALL SELECT 1 FROM ExportArtifact UNION ALL SELECT 1 FROM ShareLease
            UNION ALL SELECT 1 FROM OcrResultFts_docsize UNION ALL SELECT 1 FROM OcrResultFts_segdir
            UNION ALL SELECT 1 FROM OcrResultFts_segments UNION ALL SELECT 1 FROM OcrResultFts_stat
            UNION ALL SELECT 1 FROM sqlite_sequence LIMIT 1"""
        const val MAX_ALBUMS = 2_000
        const val MAX_PAGES = 4_096
        const val MAX_REVISIONS = 1_024
        fun <T> owned(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
    }
}
