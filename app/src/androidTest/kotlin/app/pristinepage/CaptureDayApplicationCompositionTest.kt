package app.pristinepage

import android.content.Context
import android.content.ContextWrapper
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.data.model.AlbumOrder
import app.pristinepage.data.model.DocumentMutationResult
import app.pristinepage.data.model.IngestResult
import app.pristinepage.data.repository.AlbumSearchQuery
import app.pristinepage.data.repository.SourceInput
import app.pristinepage.library.ApplicationLibraryActions
import java.io.ByteArrayInputStream
import java.io.File
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real app ingress and Home actions on isolated synthetic bytes, without picker UI, OCR runtime or native navigation. */
@RunWith(AndroidJUnit4::class)
class CaptureDayApplicationCompositionTest {
    @Test fun freshDatedPhotoFindsOnlyItsExactSavedPageAcrossGraphReopen() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.filesDir, "capture-day-compose-${UUID.randomUUID()}").also { check(it.mkdir()) }
        val files = File(root, "files").also { check(it.mkdir()) }
        val database = File(root, "databases").also { check(it.mkdir()) }
        val isolated = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getDatabasePath(name: String): File = File(database, name)
        }
        val plain = "content://fixture.documents/undated"
        val dated = "content://fixture.documents/dated"
        val opened = mutableListOf<String>()
        var canReadProvider = true
        val provider = SourceInput { ref ->
            opened += ref
            check(canReadProvider)
            ByteArrayInputStream(Base64.decode(when (ref) {
                plain -> PLAIN
                dated -> DATED
                else -> error("Unexpected fixture reference")
            }, Base64.NO_WRAP))
        }
        suspend fun open() = ApplicationServices(isolated, ApplicationImageBudget.create(64), provider).imports()
        val initial = open()
        val document = initial.store.documents.createDocument("Synthetic capture-day test")
        val saved = try {
            val batch = initial.store.imports.beginImport(document, 1, null)
            initial.photoImports.importProviderReferences(listOf(plain, dated), batch.parentId).map { it as IngestResult.Committed }
        } finally { initial.store.close() }
        assertEquals(listOf(plain, dated), opened)
        assertEquals(saved[0].sourcePixelHash, saved[1].sourcePixelHash)
        assertNotEquals(saved[0].sourceByteHash, saved[1].sourceByteHash)
        canReadProvider = false
        val reopened = open()
        try {
            val library = ApplicationLibraryActions(isolated) { reopened }
            val hit = library.searchPages("captured:2024-02-29").hits.single()
            assertEquals(document, hit.document)
            assertEquals(saved[1].pageId, hit.page)
            assertEquals(2, hit.pageNumber)
            assertTrue(hit.stillMatches())
            assertEquals(listOf(document), library.searchAlbums(AlbumSearchQuery("synthetic capture-day", ZoneOffset.UTC),
                AlbumOrder.MODIFIED, Locale.ENGLISH, null, 20).albums.map { it.documentId })
            assertTrue(library.searchPages("captured:2024-03-01").hits.isEmpty())
            assertTrue(library.searchPages("2024-02-29").hits.isEmpty())
            assertTrue(reopened.store.documents.removePage(saved[1].pageId, saved[1].documentRevision) is DocumentMutationResult.Applied)
            assertFalse(hit.stillMatches())
            assertTrue(library.searchPages("captured:2024-02-29").hits.isEmpty())
            assertEquals(listOf(plain, dated), opened)
            assertEquals(0L, reopened.budget.usedBytes)
        } finally { reopened.store.close() }
    }

    private companion object {
        // Same 3×2 RGB raster and PNG chunks; the second original adds only a primary Exif IFD with DateTimeOriginal 2024:02:29 09:20:01.
        const val PLAIN = "iVBORw0KGgoAAAANSUhEUgAAAAMAAAACCAIAAAASFvFNAAAAGElEQVR4nGP4z8DAAME2NjYVFRX///8HAD+JCBdips5AAAAAAElFTkSuQmCC"
        const val DATED = "iVBORw0KGgoAAAANSUhEUgAAAAMAAAACCAIAAAASFvFNAAAAQGVYSWZJSSoACAAAAAEAaYcEAAEAAAAaAAAAAAAAAAEAA5ACABQAAAAsAAAAAAAAADIwMjQ6MDI6MjkgMDk6MjA6MDEA59MQtgAAABhJREFUeJxj+M/AwADBNjY2FRUV////BwA/iQgXYqbOQAAAAABJRU5ErkJggg=="
    }
}
