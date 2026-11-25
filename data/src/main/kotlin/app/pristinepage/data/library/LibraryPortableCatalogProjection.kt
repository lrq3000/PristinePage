package app.pristinepage.data.library

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import app.pristinepage.core.library.LibraryScanName
import app.pristinepage.core.ocr.OcrText
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.data.db.DocumentRow
import app.pristinepage.data.db.OcrResultRow
import app.pristinepage.data.db.PageRow
import app.pristinepage.data.db.RecipeRevisionRow
import app.pristinepage.data.db.SourceBlobRow
import app.pristinepage.data.db.SourceCaptureDayRow
import app.pristinepage.data.db.SourceRow
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.SourceInspection
import app.pristinepage.data.model.requireOpaqueId
import app.pristinepage.data.model.requireStoredAlbumTitle
import app.pristinepage.data.repository.OcrJobInput
import app.pristinepage.data.repository.RoomOcrResults
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Projects a closed and independently schema-checked catalog; its raw rows never become writable Room pages. */
internal class LibraryPortableCatalogProjection(
    private val sql: SQLiteDatabase, private val verifyDerivedOcr: Boolean = false, private val checkpoint: () -> Unit,
) {
    private var retainedBytes = 0L
    private data class NamedPage(val row: PageRow, val filename: String)

    fun snapshot(): RoomLibrarySavedCatalog.Snapshot = try {
        LibraryPortableCatalogShape.verify(sql)
        val originals = LibraryPortableOriginals.read(sql, checkpoint)
        val blobs = rows(
            "SourceBlob", """sourceBlobId,mime,encodedWidth,encodedHeight,ingestTransactionId,createdAt""",
            "${text("ingestTransactionId", 36)} AND typeof(createdAt)='integer'", MAX_PAGES,
        ) {
            val key = id(0)
            val bytes = originals.getValue(key)
            SourceBlobRow(key, bytes.sha256, bytes.byteCount, getString(1), getInt(2), getInt(3),
                "sources/$key/original.bin", "READY", 0, id(4), getLong(5))
        }.associateBy(SourceBlobRow::sourceBlobId)
        val bindings = HashMap<String, SourceBinding>()
        val sources = rows(
            "Source", """sourceId,sourceBlobId,pixelSha256,width,height,exifOrientation,CAST(conversionFingerprint AS BLOB),profileSha256,createdAt""",
            "${text("sourceId", 36)} AND ${text("sourceBlobId", 36)} AND ${text("pixelSha256", 64)} AND " +
                "${integer("width", 1, 12000)} AND ${integer("height", 1, 12000)} AND ${integer("exifOrientation", 1, 8)} AND " +
                "${text("conversionFingerprint", 512)} AND (profileSha256 IS NULL OR ${text("profileSha256", 64)}) AND " +
                "state IS 'READY' AND typeof(createdAt)='integer'", MAX_PAGES,
        ) {
            val source = SourceRow(id(0), id(1), getString(2), getInt(3), getInt(4), getInt(5), utf8(6),
                if (isNull(7)) null else getString(7), 0, "READY", getLong(8))
            val blob = blobs.getValue(source.sourceBlobId)
            val inspection = SourceInspection(blob.mime, PixelSize(blob.encodedWidth, blob.encodedHeight), PixelSize(source.width, source.height),
                source.exifOrientation, source.profileSha256, source.conversionFingerprint, source.pixelSha256)
            bindings[source.sourceId] = SourceBinding(inspection.sourcePixelHash, inspection.normalizedSize, inspection.conversionFingerprint)
            source
        }
        val days = rows(
            "SourceCaptureDay", "sourceBlobId,byteSha256,epochDay",
            "${text("sourceBlobId", 36)} AND ${text("byteSha256", 64)} AND (epochDay IS NULL OR ${integer("epochDay", -719162, 2932896)})", MAX_PAGES,
        ) {
            val key = id(0)
            val hash = getString(1)
            if (hash != blobs.getValue(key).byteSha256) invalid()
            SourceCaptureDayRow(key, hash, nullableLong(2))
        }
        val albums = rows(
            "Document", "documentId,CAST(title AS BLOB),documentRevision,createdAt,updatedAt",
            "${text("documentId", 36)} AND ${text("title", 640)} AND ${positive("documentRevision")} AND " +
                "typeof(createdAt)='integer' AND typeof(updatedAt)='integer'", 2_000, "ORDER BY documentId",
        ) {
            val title = utf8(1)
            requireStoredAlbumTitle(title)
            DocumentRow(id(0), title, getLong(2), "ACTIVE", getLong(3), getLong(4), null)
        }
        val pages = rows(
            "Page p JOIN LibraryScanName n ON n.pageId=p.pageId AND n.documentId=p.documentId",
            "p.pageId,p.documentId,p.sourceId,p.currentRecipeRevision,p.state,p.ordinal,p.removedAt,p.createdAt," +
                "CAST(n.filename AS BLOB),CAST(n.comparisonKey AS BLOB)",
            "${text("p.pageId", 36)} AND ${text("p.documentId", 36)} AND ${text("p.sourceId", 36)} AND ${positive("p.currentRecipeRevision")} AND " +
                "((p.state IS 'ACTIVE' AND ${integer("p.ordinal", 0, 99)} AND p.removedAt IS NULL) OR " +
                "(p.state IS 'REMOVED' AND p.ordinal IS NULL AND typeof(p.removedAt)='integer')) AND " +
                "typeof(p.createdAt)='integer' AND ${text("n.filename", 1024)} AND ${text("n.comparisonKey", 1024)}",
            MAX_PAGES, "ORDER BY p.documentId,p.ordinal IS NULL,p.ordinal,p.pageId",
        ) {
            val name = utf8(8)
            if (LibraryScanName.comparisonKey(name) != utf8(9)) invalid()
            NamedPage(PageRow(id(0), id(1), id(2), getLong(3), getString(4), nullableLong(5)?.toInt(), nullableLong(6), getLong(7)), name)
        }.groupBy { it.row.documentId }
        val revisions = revisions(bindings)
        val ocr = savedOcr()
        val projected = albums.map { album ->
            RoomLibrarySavedCatalog.Album(album, pages[album.documentId].orEmpty().map { page ->
                val history = revisions.getValue(page.row.pageId)
                val saved = ocr[page.row.pageId]
                val current = history.find { it.recipeRevision == page.row.currentRecipeRevision } ?: invalid()
                if (saved != null && (saved.intent.documentId.value != album.documentId || saved.intent.sourceId.value != page.row.sourceId ||
                    saved.intent.input.recipeRevision != current.recipeRevision || saved.intent.recipeSha256 != current.recipeSha256 ||
                    saved.intent.input.sourcePixelSha256 != bindings.getValue(page.row.sourceId).sourcePixelHash)
                ) invalid()
                RoomLibrarySavedCatalog.Page(page.row, page.filename, history, saved)
            })
        }
        val byDocument = projected.associateBy { it.row.documentId }
        val undo = HashMap<String, MutableList<PortableUndoEntry>>()
        var lastOwner: String? = null
        var owners: PortableUndoEntry.Owners? = null
        LibraryUndoRows.read(sql::rawQuery, null, ::charge, checkpoint, ::invalid, ::invalid) { row ->
            if (row.documentId != lastOwner) {
                owners = PortableUndoEntry.Owners(byDocument.getValue(row.documentId))
                lastOwner = row.documentId
            }
            val entry = checkNotNull(owners).admit(row)
            charge(128L * entry.pinnedSources.size)
            verifyUndoPins(entry)
            undo.getOrPut(row.documentId) { ArrayList() }.add(entry)
        }
        RoomLibrarySavedCatalog.Snapshot(projected.map { album ->
            RoomLibrarySavedCatalog.Album(album.row, album.pages, undo[album.row.documentId].orEmpty())
        }, sources, blobs.values.toList(), days)
    } catch (_: IllegalArgumentException) {
        invalid()
    } catch (_: NoSuchElementException) {
        invalid()
    }

    private fun verifyUndoPins(entry: PortableUndoEntry) {
        sql.rawQuery("""SELECT CASE WHEN typeof(sourceId)='text' AND length(CAST(sourceId AS BLOB))=36 THEN sourceId ELSE '' END
            FROM SourceReference WHERE ownerType='UNDO' AND ownerId=? LIMIT 101""", arrayOf(entry.id)).use { pins ->
            val remaining = entry.pinnedSources.toMutableSet()
            while (pins.moveToNext()) {
                checkpoint()
                if (!remaining.remove(pins.getString(0))) invalid()
            }
            if (remaining.isNotEmpty()) invalid()
        }
    }

    private fun revisions(bindings: Map<String, SourceBinding>): Map<String, List<RecipeRevisionRow>> {
        val earlier = HashMap<String, MutableSet<Long>>()
        return rows(
            "RecipeRevision", "pageId,recipeRevision,sourceId,canonicalRecipe,recipeSha256,parentRevision,createdAt",
            "${text("pageId", 36)} AND ${positive("recipeRevision")} AND ${text("sourceId", 36)} AND " +
                "recipeSchemaVersion IS 1 AND typeof(canonicalRecipe)='blob' AND length(canonicalRecipe) BETWEEN 1 AND 524288 AND " +
                "${text("recipeSha256", 64)} AND (parentRevision IS NULL OR ${positive("parentRevision")}) AND typeof(createdAt)='integer'",
            MAX_PAYLOAD_ROWS, "ORDER BY pageId,recipeRevision",
        ) {
            val owner = id(0)
            val revision = getLong(1)
            val source = id(2)
            val bytes = getBlob(3)
            val hash = getString(4)
            val parent = nullableLong(5)
            charge(bytes.size + 128L)
            val seen = earlier.getOrPut(owner) { HashSet() }
            val recipe = RecipeCodec.decode(bytes)
            if (seen.size >= 1_024 || (parent != null && parent !in seen) || !seen.add(revision) || recipe.geometry.source != bindings.getValue(source) ||
                !RecipeCodec.encode(recipe).contentEquals(bytes) || RecipeCodec.hash(recipe) != hash
            ) invalid()
            RecipeRevisionRow(owner, revision, source, 1, bytes, hash, parent, getLong(6))
        }.groupBy(RecipeRevisionRow::pageId)
    }

    private fun savedOcr(): Map<String, LibrarySavedOcr> = rows(
        "OcrPageWork w JOIN Job j ON j.jobId=w.jobId JOIN OcrResult r ON r.pageId=w.pageId",
        """w.pageId,w.generation,w.inputSha256,j.jobId,j.idempotenceKey,j.canonicalInputSha256,j.input,
            j.attempt,j.createdAt,j.updatedAt,r.recipeRevision,r.sourcePixelSha256,r.engineId,r.engineVersion,r.layout,
             CAST(coalesce(r.verbatimText,r.indexedText) AS BLOB),
             ${if (verifyDerivedOcr) "CAST(r.indexedText AS BLOB),r.verbatimText IS NOT NULL" else "NULL,NULL"}""",
        "${text("w.pageId", 36)} AND ${positive("w.generation")} AND ${text("w.inputSha256", 64)} AND " +
            "w.projectionVersion IS ${RoomOcrResults.PROJECTION_VERSION} AND ${text("j.jobId", 36)} AND ${text("j.idempotenceKey", 128)} AND " +
            "${text("j.canonicalInputSha256", 64)} AND typeof(j.input)='blob' AND length(j.input) BETWEEN 1 AND 3532 AND " +
            "w.inputBytes IS length(j.input) AND j.state IS 'SUCCEEDED' AND ${integer("j.attempt", 1, 3)} AND j.leaseGeneration IS j.attempt AND " +
            "j.snapshotId IS NULL AND j.pageOrdinal IS NULL AND j.leaseOwner IS NULL AND j.leaseUntil IS NULL AND j.errorCode IS NULL AND " +
            "j.checkpoint IS NULL AND typeof(j.createdAt)='integer' AND typeof(j.updatedAt)='integer' AND " +
            "${positive("r.recipeRevision")} AND ${text("r.sourcePixelSha256", 64)} AND ${text("r.engineId", 128)} AND " +
            "${integer("r.engineVersion", 1, Int.MAX_VALUE)} AND ${text("r.layout", 32)} AND " +
            "typeof(r.indexedText)='text' AND length(CAST(r.indexedText AS BLOB))<=786432 AND " +
            "(r.verbatimText IS NULL OR typeof(r.verbatimText)='text') AND " +
            "length(CAST(coalesce(r.verbatimText,r.indexedText) AS BLOB))<=393216",
        MAX_PAGES,
    ) {
        val page = id(0)
        val input = getBlob(6)
        val intent = OcrJobInput.decodeV2(input)
        val exact = OcrText(utf8(15))
        charge(4L * exact.value.length + input.size + 256)
        if (verifyDerivedOcr) {
            val canonical = OcrResultRow.fromRecognition(intent.input, intent.configuration, exact)
            if (canonical.indexedText != utf8(16) || (canonical.verbatimText != null) != (getInt(17) == 1)) invalid()
        }
        if (intent.input.pageId != page || intent.generation != getLong(1) || intent.digest != getString(2) ||
            intent.key != getString(4) || intent.digest != getString(5) || intent.input.recipeRevision != getLong(10) ||
            intent.input.sourcePixelSha256 != getString(11) || intent.configuration.engineId != getString(12) ||
            intent.configuration.engineVersion != getInt(13) || intent.configuration.layout.name != getString(14)
        ) invalid()
        page to LibrarySavedOcr(JobId(id(3)), intent, exact, getLong(7), getLong(8), getLong(9))
    }.toMap()

    private fun <T> rows(from: String, select: String, valid: String, maximum: Int, order: String = "", read: Cursor.() -> T): List<T> {
        checkpoint()
        // Check scalar storage before any row can cross the CursorWindow boundary; the exact closed copy remains read-only throughout.
        sql.rawQuery("SELECT 1 FROM $from WHERE NOT coalesce(($valid),0) LIMIT 1", null).use { if (it.moveToFirst()) invalid() }
        return sql.rawQuery("SELECT $select FROM $from $order LIMIT ${maximum + 1}", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    checkpoint()
                    if (size == maximum) invalid()
                    add(cursor.read())
                }
            }
        }
    }

    private fun Cursor.id(index: Int) = getString(index).also(::requireOpaqueId)
    private fun Cursor.nullableLong(index: Int) = if (isNull(index)) null else getLong(index)
    private fun Cursor.utf8(index: Int): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(getBlob(index))).toString()
    } catch (_: CharacterCodingException) { invalid() }

    private fun charge(bytes: Long) {
        retainedBytes += bytes
        if (retainedBytes > 16L * 1024 * 1024) invalid()
    }

    private companion object {
        const val MAX_PAGES = 4_096
        const val MAX_PAYLOAD_ROWS = 16 * 1024 * 1024 / 128
        fun text(field: String, bytes: Int) = "typeof($field)='text' AND length(CAST($field AS BLOB)) BETWEEN 1 AND $bytes"
        fun positive(field: String) = "typeof($field)='integer' AND $field>0"
        fun integer(field: String, first: Int, last: Int) = "typeof($field)='integer' AND $field BETWEEN $first AND $last"
        fun invalid(): Nothing = throw IOException("Library catalog rows do not match the portable format")
    }
}
