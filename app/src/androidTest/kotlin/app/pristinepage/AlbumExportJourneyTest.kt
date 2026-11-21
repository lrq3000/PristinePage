package app.pristinepage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.NamedAlbumExports
import app.pristinepage.export.ExportPagePlan
import app.pristinepage.export.ExportSize
import app.pristinepage.export.exportOutputs
import app.pristinepage.image.export.AlbumArtifactEncoder
import app.pristinepage.image.export.AlbumJpegQuality
import app.pristinepage.library.ImportLibraryViewModel
import app.pristinepage.library.LibraryEvent
import java.io.File
import java.io.FileNotFoundException
import java.io.ByteArrayInputStream
import java.io.DataInputStream
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
        val dimensions = listOf(1280 to 800, 90 to 70, 720 to 1280)
        try {
            for ((index, file) in files.withIndex()) {
                val (width, height) = dimensions[index]
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(listOf(Color.RED, Color.BLUE, Color.GREEN)[index])
                    // Off-center complementary marks distinguish orientation, wrong pages and invalid uniform readback.
                    val canvas = android.graphics.Canvas(bitmap)
                    val paint = android.graphics.Paint().apply { color = Color.WHITE }
                    canvas.drawRect(width * .1f, height * .1f, width * .25f, height * .24f, paint)
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
            compose.onNodeWithTag("album-export-quality-balanced").assertIsSelected()
            compose.onNodeWithTag("album-export-quality-maximum").performClick().assertIsSelected()
            compose.onNodeWithTag("album-export-format-pdf").performClick()
            compose.onNodeWithTag("album-export-quality-maximum").assertDoesNotExist()
            compose.onNodeWithTag("album-export-format-jpeg").performClick()
            compose.onNodeWithTag("album-export-quality-maximum").assertIsSelected()
            compose.onNodeWithTag("album-export-generate").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("album-export-share").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("album-export-save-as").assertIsEnabled()
            compose.onNodeWithText(context.getString(R.string.atelier_close)).performClick()

            compose.onNodeWithText(context.getString(R.string.atelier_batch_cancel)).performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("album-export-all").filter(isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("album-export-all").assertIsEnabled().performClick()
            compose.onNodeWithTag("album-export-format-image_zip").assertIsSelected()
            compose.onNodeWithTag("album-export-quality-smaller_file").performClick().assertIsSelected()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("album-export-generate").filter(isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("album-export-generate").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("album-export-save-as").filter(isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            val fromUi = uiAlbumZipQuantizers(context, document, title)
            compose.onNodeWithText(context.getString(R.string.atelier_close)).performClick()

            val selected = listOf(overview.pages[0], overview.pages[2])
            val plans = selected.map { ExportPagePlan(graph.store.editablePages.load(document, it.pageId), it.ordinal + 1) }
            val store = graph.store.albumExports
            val encoder = AlbumArtifactEncoder(File(context.cacheDir.canonicalFile, "album-export-test"), graph.store.committedSources, graph.renderer, graph.budget)
            val zipDimensions = listOf(listOf(1280 to 800, 720 to 1280), listOf(1024 to 640, 576 to 1024), listOf(640 to 400, 360 to 640))
            suspend fun exportZip(size: ExportSize, quality: AlbumJpegQuality): Pair<NamedAlbumExports.Ready, List<Int>> {
                val settings = ExportSettings(ExportFormat.IMAGE_ZIP, exportOutputs(plans, size), null,
                    quality.settings, emptyList(), AlbumExportScope(title, listOf(1, 3)))
                val artifact = store.export(document, overview.document.documentRevision, settings, AlbumArtifactEncoder.PROFILE, encoder)
                assertEquals("$title.zip", artifact.filename)
                assertEquals("application/zip", artifact.mime)
                val quantizers = mutableListOf<Int>()
                store.copy(artifact) { verified, _ ->
                    val zip = ZipInputStream(verified)
                    for ((member, position) in listOf(1, 3).withIndex()) {
                        assertEquals("${title}_$position.jpg", zip.nextEntry.name)
                        val bytes = zip.readBytes()
                        quantizers += jpegQuantizerSum(bytes)
                        val decoded = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                        try {
                            assertEquals(zipDimensions[size.ordinal][member], decoded.width to decoded.height)
                            val pixel = decoded.getPixel(decoded.width / 2, decoded.height / 2)
                            assertTrue(if (position == 1) Color.red(pixel) > 240 && Color.green(pixel) < 15
                                else Color.green(pixel) > 240 && Color.red(pixel) < 15)
                            val mark = decoded.getPixel(decoded.width / 6, decoded.height / 6)
                            assertTrue(listOf(Color.red(mark), Color.green(mark), Color.blue(mark)).all { it > 235 })
                        } finally { decoded.recycle() }
                        zip.closeEntry()
                    }
                    assertNull(zip.nextEntry)
                }
                return artifact to quantizers
            }
            val balanced = ExportSize.entries.map { exportZip(it, AlbumJpegQuality.BALANCED).second }
            assertTrue("Changing pixel dimensions must not change the requested quantization", balanced.all { it == balanced.first() })
            val (result, smaller) = exportZip(ExportSize.COMPACT, AlbumJpegQuality.SMALLER_FILE)
            val (_, maximum) = exportZip(ExportSize.COMPACT, AlbumJpegQuality.MAXIMUM)
            assertEquals(smaller, listOf(fromUi.first(), fromUi.last()))
            for (member in smaller.indices) {
                assertTrue(maximum[member] < balanced[ExportSize.COMPACT.ordinal][member])
                assertTrue(balanced[ExportSize.COMPACT.ordinal][member] < smaller[member])
            }
            assertEquals(overview.pages, graph.store.catalog.load(document).pages)

            // Exercise the real provider against the same verified sparse archive without dispatching an external app.
            val shareId = UUID.randomUUID().toString()
            val folder = File(context.filesDir, "shared-exports/$shareId")
            check(folder.mkdirs())
            val share = File(folder, result.filename)
            try {
                check(share.createNewFile())
                store.copy(result) { source, _ -> share.outputStream().use { source.copyTo(it) } }
                val authority = "${context.packageName}.finished-exports"
                val uri = FileProvider.getUriForFile(context, authority, share)
                assertThrows(FileNotFoundException::class.java) { context.contentResolver.openFileDescriptor(uri, "r")?.close() }
                store.recordShare(result, shareId, result.filename)
                context.contentResolver.openInputStream(uri)!!.use { inputStream ->
                    ZipInputStream(inputStream).use { zip ->
                        assertEquals("${title}_1.jpg", zip.nextEntry.name)
                        zip.closeEntry()
                        assertEquals("${title}_3.jpg", zip.nextEntry.name)
                        zip.closeEntry()
                        assertNull(zip.nextEntry)
                    }
                }
                assertThrows(FileNotFoundException::class.java) { context.contentResolver.openFileDescriptor(uri, "rw")?.close() }
                val foreign = Uri.withAppendedPath(checkNotNull(uri.buildUpon().path("/finished_exports/$shareId").build()), "Unrelated.zip")
                assertThrows(FileNotFoundException::class.java) { context.contentResolver.openFileDescriptor(foreign, "r")?.close() }
                store.revokeShare(result, shareId)
                assertTrue(share.exists()) // Metadata revocation blocks new opens independently of unlink.
                assertThrows(FileNotFoundException::class.java) { context.contentResolver.openFileDescriptor(uri, "r")?.close() }
            } finally {
                share.delete()
                folder.delete()
            }
        } finally { files.forEach { it.delete() } }
    }

    /** Read only the one just-created private UI artifact of this synthetic album; no provider or share target is involved. */
    private fun uiAlbumZipQuantizers(context: Context, document: DocumentId, title: String): List<Int> {
        val (snapshotId, artifactId) = SQLiteDatabase.openDatabase(
            context.getDatabasePath("pristinepage.db").path, null, SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            db.rawQuery(
                """SELECT s.snapshotId, a.artifactId, s.settings FROM ExportSnapshot s JOIN ExportArtifact a ON a.snapshotId = s.snapshotId
                    WHERE s.documentId = ? AND s.state = 'PRIVATE_READY' AND a.state = 'READY' AND a.mime = 'application/zip' LIMIT 2""",
                arrayOf(document.value),
            ).use { rows ->
                assertTrue(rows.moveToFirst())
                val ids = rows.getString(0) to rows.getString(1)
                val frozen = ExportSettingsCodec.decode(rows.getBlob(2))
                assertEquals(80, frozen.jpeg?.quality)
                assertEquals(listOf(1, 2, 3), frozen.albumScope?.positions)
                assertFalse(rows.moveToNext())
                ids
            }
        }
        check(UUID.fromString(snapshotId).toString() == snapshotId && UUID.fromString(artifactId).toString() == artifactId)
        val file = File(context.filesDir, "store/exports/$snapshotId/$artifactId.bin")
        check(file.length() in 1..1_048_576)
        return ZipInputStream(file.inputStream().buffered()).use { zip ->
            (1..3).map { position ->
                assertEquals("${title}_$position.jpg", zip.nextEntry.name)
                jpegQuantizerSum(zip.readBytes()).also { zip.closeEntry() }
            }.also { assertNull(zip.nextEntry) }
        }
    }

    /** Inspect actual 8-bit baseline DQT payload bytes independently of the production decoder and settings model. */
    private fun jpegQuantizerSum(bytes: ByteArray): Int = DataInputStream(ByteArrayInputStream(bytes)).use { jpeg ->
        assertEquals(0xffd8, jpeg.readUnsignedShort())
        var total = 0
        var tables = 0
        while (true) {
            assertEquals(0xff, jpeg.readUnsignedByte())
            var marker = jpeg.readUnsignedByte()
            while (marker == 0xff) marker = jpeg.readUnsignedByte()
            if (marker == 0xda) break
            val count = jpeg.readUnsignedShort() - 2
            check(count >= 0)
            val payload = ByteArray(count).also(jpeg::readFully)
            if (marker != 0xdb) continue
            check(count > 0 && count % 65 == 0)
            for (offset in payload.indices step 65) {
                check(payload[offset].toInt() in 0..3)
                for (i in offset + 1..offset + 64) total += payload[i].toInt() and 255
                tables++
            }
        }
        assertEquals(2, tables)
        total
    }
}
