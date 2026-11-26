package app.pristinepage.data.library

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.RoomDatabase
import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.PageId
import app.pristinepage.data.model.SaveRecipeResult
import app.pristinepage.data.repository.DocumentCatalog
import app.pristinepage.data.repository.EditablePages
import app.pristinepage.data.repository.RecipeAdmission
import app.pristinepage.data.repository.RoomDocumentCatalog
import app.pristinepage.data.repository.RoomDocumentRepository
import app.pristinepage.data.repository.RoomEditablePages
import app.pristinepage.data.repository.RoomMetadataAccess
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Fresh, bounded, closed catalog writer for an exclusively owned stage. Never opens the portable input with Room. */
internal class RoomLibraryCatalogStage(private val application: Context) {
    sealed interface RecipeEdit {
        data class Refused(val result: SaveRecipeResult) : RecipeEdit
        data class Ready(
            val saved: SaveRecipeResult.Saved, val schema: Int, val snapshot: RoomLibrarySavedCatalog.Snapshot,
            val catalog: LibraryObjectTransfer.ObjectId,
        ) : RecipeEdit
    }

    suspend fun write(snapshot: RoomLibrarySavedCatalog.Snapshot, file: File, acknowledgeClose: () -> Unit = {}): Int =
        withFreshStage(snapshot, file, acknowledgeClose) { _, schema -> schema }

    /** Apply the standard revision transaction only to a new app-owned catalog. The closed candidate conveys no publication authority. */
    suspend fun stageRecipeEdit(
        baseline: RoomLibrarySavedCatalog.Snapshot, file: File, document: DocumentId, page: PageId,
        expectedRevision: Long, recipe: Recipe, admission: RecipeAdmission, acknowledgeClose: () -> Unit = {},
    ): RecipeEdit = withContext(Dispatchers.IO) {
        val checkpoint = { coroutineContext.ensureActive() }
        checkpoint()
        // The edited page will have a new accepted revision. Do not seed its old result into the disposable candidate;
        // the predecessor still owns that exact OCR and no unrelated producer is removed or rebound.
        val working = RoomLibrarySavedCatalog.Snapshot(baseline.albums.map { album ->
            if (album.row.documentId != document.value) album else RoomLibrarySavedCatalog.Album(album.row, album.pages.map { entry ->
                if (entry.row.pageId != page.value || entry.savedOcr == null) entry
                else RoomLibrarySavedCatalog.Page(entry.row, entry.filename, entry.revisions, savedOcr = null)
            }, album.undo)
        }, baseline.sources, baseline.originals, baseline.days)
        val staged = withFreshStage(working, file, acknowledgeClose) { stage, schema ->
            val metadata = RoomMetadataAccess(stage)
            // Reject an ID from another album before the ordinary save transaction, which is keyed by page.
            RoomEditablePages(metadata, stage.catalog()).load(document, page)
            val result = RoomDocumentRepository(metadata, admission).saveRecipe(page, expectedRevision, recipe)
            Triple(result, schema, if (result is SaveRecipeResult.Saved) RoomLibrarySavedCatalog(stage).capture() else null)
        }
        checkpoint()
        val (saved, schema, snapshot) = staged
        if (saved !is SaveRecipeResult.Saved) return@withContext RecipeEdit.Refused(saved)
        val edited = checkNotNull(snapshot)
        val beforeRows = baseline.albums.associateBy { it.row.documentId }
        if (edited.albums.size != beforeRows.size || edited.albums.any { album ->
                val prior = beforeRows[album.row.documentId]?.row ?: return@any true
                val expected = if (prior.documentId != document.value) prior else
                    prior.copy(documentRevision = saved.documentRevision, updatedAt = album.row.updatedAt)
                album.row != expected
            }) throw IOException("The edited catalog changed unrelated album metadata")
        val proof = fingerprintClosedLibraryCatalog(file, checkpoint)
        RoomLibraryCatalogReader(application, RoomLibraryCatalogReader.MAX_PORTABLE_BYTES).verifyStagedProjection(file, edited, checkpoint)
        if (fingerprintClosedLibraryCatalog(file, checkpoint) != proof) throw IOException("The edited catalog changed after reopening")
        RecipeEdit.Ready(saved, schema, edited, proof)
    }

    private suspend fun <T : Any> withFreshStage(
        snapshot: RoomLibrarySavedCatalog.Snapshot, file: File, acknowledgeClose: () -> Unit,
        block: suspend (PristineDatabase, Int) -> T,
    ): T {
        if (!Files.notExists(file.toPath(), NOFOLLOW_LINKS)) throw IOException("Library staging catalog already exists")
        val stage = database(file)
        var primary: Throwable? = null
        try {
            val sql = stage.openHelper.writableDatabase
            sql.query("PRAGMA journal_mode=DELETE").use {
                if (!it.moveToFirst() || it.getString(0) != "delete") throw IOException("Library stage cannot be closed without a journal")
            }
            if (sql.path != file.path) throw IOException("Library stage path mismatch")
            val pageSize = sql.pageSize
            if (pageSize !in 512..65_536 || java.lang.Long.bitCount(pageSize) != 1) throw IOException("Invalid SQLite page size")
            val pageCap = RoomLibraryCatalogReader.MAX_PORTABLE_BYTES / pageSize
            sql.query("PRAGMA max_page_count=$pageCap").use {
                if (!it.moveToFirst() || it.getLong(0) !in 1..pageCap) throw IOException("Library stage capacity could not be set")
            }
            RoomLibrarySavedCatalog(stage).writeFresh(snapshot)
            return block(stage, sql.version)
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally { close(stage, primary, acknowledgeClose) }
    }

    /** The caller verified this closed, app-built stage. Only structured metadata reads are exposed, not Room or local writes. */
    suspend fun <T : Any> useCatalog(
        file: File, access: LibraryWorkingCatalogAccess, acknowledgeClose: () -> Unit,
        block: suspend CoroutineScope.(DocumentCatalog, EditablePages) -> T,
    ): T {
        val stage = database(file)
        var primary: Throwable? = null
        try {
            if (stage.openHelper.readableDatabase.path != file.path) throw IOException("Working catalog path mismatch")
            val metadata = RoomMetadataAccess(stage)
            val catalog = RoomDocumentCatalog(metadata, stage.catalog())
            val editable = RoomEditablePages(metadata, stage.catalog())
            val scoped = object : DocumentCatalog, app.pristinepage.data.repository.SearchableAlbumCatalog {
                override suspend fun list(after: DocumentId?, limit: Int) =
                    access.read { catalog.list(after, limit) }
                override suspend fun load(documentId: DocumentId) =
                    access.read { catalog.load(documentId) }
                override suspend fun activeImport(documentId: DocumentId) =
                    access.read { catalog.activeImport(documentId) }
                override suspend fun listAlbums(order: app.pristinepage.data.model.AlbumOrder, locale: java.util.Locale,
                    after: app.pristinepage.data.model.AlbumPosition?, limit: Int) =
                    access.read { catalog.listAlbums(order, locale, after, limit) }
                override suspend fun searchAlbums(query: app.pristinepage.data.repository.AlbumSearchQuery, order: app.pristinepage.data.model.AlbumOrder,
                    locale: java.util.Locale, after: app.pristinepage.data.model.AlbumPosition?, limit: Int) =
                    access.read { catalog.searchAlbums(query, order, locale, after, limit) }
            }
            val scopedEditable = object : EditablePages {
                override suspend fun load(documentId: DocumentId, pageId: PageId) =
                    access.read { editable.load(documentId, pageId) }
            }
            return coroutineScope {
                access.requireCurrent()
                block(scoped, scopedEditable)
            }
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            access.retire { close(stage, primary, acknowledgeClose) }
        }
    }

    private fun close(stage: PristineDatabase, primary: Throwable?, acknowledgeClose: () -> Unit) {
        try { stage.close() }
        catch (failure: Exception) {
            if (primary == null) throw failure
            if (failure !== primary) primary.addSuppressed(failure)
            return
        }
        acknowledgeClose()
    }

    private fun database(file: File): PristineDatabase {
        val stagingContext = object : ContextWrapper(application) {
            override fun getDatabasePath(name: String): File {
                check(name == file.name)
                return file
            }
        }
        return Room.databaseBuilder(stagingContext, PristineDatabase::class.java, file.name)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
    }
}

/** Verify the freshly rebuilt rows, not merely whether SQLite can open the file. Derived counts and FTS are regenerated by the stage writer. */
internal fun RoomLibraryCatalogReader.verifyStagedProjection(
    file: File, expected: RoomLibrarySavedCatalog.Snapshot, checkpoint: () -> Unit,
) {
    read(file) { catalog ->
        val actual = LibraryPortableCatalogProjection(catalog, verifyDerivedOcr = true, checkpoint = checkpoint).snapshot()
        // The portable reader validates rebuilt counters against actual rows. The live snapshot may also pin jobs outside this projection.
        if (actual.originals.toSet() != expected.originals.map { it.copy(sourceCount = 0) }.toSet() ||
            actual.sources.toSet() != expected.sources.map { it.copy(refCount = 0) }.toSet() ||
            actual.days.toSet() != expected.days.toSet() || actual.albums.map { it.row } != expected.albums.map { it.row }
        ) throw IOException("Staged library metadata changed")
        for ((album, other) in actual.albums.zip(expected.albums)) {
            if (album.undo.size != other.undo.size || album.undo.zip(other.undo).any { (a, b) ->
                    checkpoint()
                    !a.sameRecord(b)
                }) throw IOException("Staged library Undo history changed")
            if (album.pages.size != other.pages.size) throw IOException("Staged library page count changed")
            for ((page, prior) in album.pages.zip(other.pages)) {
                checkpoint()
                if (page.row != prior.row || page.filename != prior.filename || page.revisionCount != prior.revisionCount ||
                    page.revisions.zip(prior.revisions).any { (a, b) ->
                        a.copy(canonicalRecipe = b.canonicalRecipe) != b || !a.canonicalRecipe.contentEquals(b.canonicalRecipe)
                    } || !sameOcr(page.savedOcr, prior.savedOcr)
                ) throw IOException("Staged library page history changed")
            }
        }
    }
}

private fun sameOcr(a: LibrarySavedOcr?, b: LibrarySavedOcr?): Boolean = if (a == null || b == null) a === b else
    a.producerId == b.producerId && a.intent == b.intent && a.text.value == b.text.value &&
        a.attempt == b.attempt && a.createdAt == b.createdAt && a.updatedAt == b.updatedAt

/** Extent and fingerprint of a closed regular catalog under the caller's stable local ownership. */
internal fun fingerprintClosedLibraryCatalog(file: File, checkpoint: () -> Unit): LibraryObjectTransfer.ObjectId {
    val path = file.toPath()
    if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) throw IOException("Library catalog is not a regular file")
    val length = Files.size(path)
    if (length !in 1..RoomLibraryCatalogReader.MAX_PORTABLE_BYTES) throw IOException("Library catalog exceeds the size limit")
    val hash = MessageDigest.getInstance("SHA-256")
    var remaining = length
    Files.newInputStream(path, READ, NOFOLLOW_LINKS).use { stream ->
        val buffer = ByteArray(65536)
        while (remaining > 0) {
            checkpoint()
            val count = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count <= 0) throw IOException("Library catalog extent changed")
            hash.update(buffer, 0, count)
            remaining -= count
        }
        checkpoint()
        if (stream.read() != -1) throw IOException("Library catalog extent changed")
    }
    checkpoint()
    return LibraryObjectTransfer.ObjectId(hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, length)
}
