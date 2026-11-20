package app.pristinepage

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.core.geometry.ExportSizing
import app.pristinepage.data.model.*
import app.pristinepage.image.export.AlbumArtifactEncoder
import app.pristinepage.library.ImportLibraryViewModel
import app.pristinepage.library.LibraryEvent
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic originals only. Product path reaches verified private output; never clicks a real Save as or Share target. */
@RunWith(AndroidJUnit4::class)
class AlbumExportJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun longPressExportsOnlyOneOfThreePagesAndOffersTheTwoSinglePageFormats() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val title = "Invoices ${UUID.randomUUID()}"
        val graph = (context.applicationContext as PristinePageApplication).services.imports()
        val root = File(context.cacheDir, "editor-journey-fixtures")
        check(root.isDirectory || root.mkdirs())
        val ids = List(3) { UUID.randomUUID().toString() }
        val files = ids.map { File(root, "$it.png") }
        try {
            for ((index, file) in files.withIndex()) {
                val bitmap = Bitmap.createBitmap(90, 60 + index * 10, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(listOf(Color.RED, Color.BLUE, Color.GREEN)[index])
                    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { bitmap.recycle() }
            }
            val document = graph.store.documents.createDocument(title)
            val batch = graph.store.imports.beginImport(document, 1, null)
            val input = graph.photoImports.importProviderReferences(
                ids.map { "content://${context.packageName}.editor-fixtures/$it" }, batch.parentId,
            ).map { it as IngestResult.Committed }
            val overview = graph.store.catalog.load(document)
            val library = compose.runOnUiThread { ViewModelProvider(compose.activity)[ImportLibraryViewModel::class.java].session }
            compose.waitUntil(10_000) { !library.state.value.loading }
            compose.runOnUiThread { library.accept(LibraryEvent.Open(document)) }
            compose.waitUntil(10_000) { library.state.value.document?.pages?.size == 3 && !library.state.value.loading }
            compose.onNodeWithTag("original-page-${input[1].pageId.value}").performTouchInput { longClick() }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("album-export-selected").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("album-export-selected").assertIsEnabled().performClick()
            compose.onNodeWithText("${title}_2.jpg").assertExists()
            compose.onNodeWithTag("album-export-format-jpeg").assertExists()
            compose.onNodeWithTag("album-export-format-pdf").assertExists()
            compose.onNodeWithTag("album-export-format-image_zip").assertDoesNotExist()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("album-export-generate").filter(isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("album-export-generate").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("album-export-share").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("album-export-save-as").assertIsEnabled()
            compose.onNodeWithText(context.getString(R.string.atelier_close)).performClick()

            val selected = listOf(overview.pages[0], overview.pages[2])
            val outputs = selected.map {
                val accepted = graph.store.editablePages.load(document, it.pageId)
                PageOutputSettings(it.pageId, ExportSizing.normal(accepted.recipe.geometry))
            }
            val settings = ExportSettings(ExportFormat.IMAGE_ZIP, outputs, null, AlbumArtifactEncoder.JPEG, emptyList(),
                AlbumExportScope(title, listOf(1, 3)))
            val store = graph.store.albumExports
            val result = store.export(document, overview.document.documentRevision, settings, AlbumArtifactEncoder.PROFILE,
                AlbumArtifactEncoder(File(context.cacheDir.canonicalFile, "album-export-test"), graph.store.committedSources, graph.renderer, graph.budget))
            assertEquals("$title.zip", result.filename)
            assertEquals("application/zip", result.mime)
            store.copy(result) { verified, _ ->
                val zip = ZipInputStream(verified)
                for (position in listOf(1, 3)) {
                    assertEquals("${title}_$position.jpg", zip.nextEntry.name)
                    assertTrue(zip.readBytes().size > 50)
                    zip.closeEntry()
                }
                assertNull(zip.nextEntry)
            }
            assertEquals(overview.pages, graph.store.catalog.load(document).pages)
        } finally { files.forEach { it.delete() } }
    }
}
