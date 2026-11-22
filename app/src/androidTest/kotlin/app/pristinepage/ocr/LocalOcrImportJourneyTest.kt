package app.pristinepage.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.pristinepage.MainActivity
import app.pristinepage.PristinePageApplication
import app.pristinepage.R
import app.pristinepage.data.model.IngestResult
import app.pristinepage.data.repository.SavedOcrWork
import app.pristinepage.editor.EditorViewModel
import app.pristinepage.library.ApplicationLibraryActions
import app.pristinepage.library.LibraryPhotoResult
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The debug provider supplies only synthetic images; import acknowledgement, WorkManager, recognition, Room and Home are the running app's own. This does not exercise a system picker. */
@RunWith(AndroidJUnit4::class)
class LocalOcrImportJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun importedScansAreRecognizedAutomaticallyAndHomeOpensTheSecondPage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as PristinePageApplication
        assumeTrue("Only run with the private OCR-enabled application candidate", app.hasBundledOcr)
        val graph = app.services.imports()
        val nonce = UUID.randomUUID().toString()
        val title = "Synthetic OCR import $nonce"
        val document = graph.store.documents.createDocument(title)
        val dir = File(context.cacheDir, "editor-journey-fixtures")
        check(dir.isDirectory || dir.mkdirs())
        val words = listOf("BLUEBELL", "ALBATROSS")
        val refs = words.map { word ->
            val id = UUID.randomUUID().toString()
            makePage(File(dir, "$id.png"), word)
            "content://${context.packageName}.editor-fixtures/$id"
        }
        val actions = ApplicationLibraryActions(context, app.savedOcrSignals::onPagesSaved, app.services::imports)
        val query = words[1]
        assertFalse(graph.store.savedOcr.find(query, null, 20).hits.any { it.document == document })
        val manager = WorkManager.getInstance(context)
        suspend fun wakes() = withContext(Dispatchers.IO) {
            manager.getWorkInfosByTag(SavedOcrPageWorker::class.java.name).get(5, TimeUnit.SECONDS)
        }
        val existingWakes = wakes().map { it.id }.toSet()
        val request = actions.begin(graph.store.catalog.load(document).document)
        val saved = actions.deliver(request, LibraryPhotoResult.Selected(refs, 0)).map { it as IngestResult.Committed }
        assertEquals(2, saved.size)
        // No manual wake, injected recognizer, or direct worker trigger. The genuine post-commit callback is the only stimulus.
        withTimeout(120_000) {
            for (page in saved) {
                val status = waitFor { graph.store.savedOcr.readStatus(document, page.pageId)?.takeIf { it.phase == SavedOcrWork.PagePhase.READY } }
                assertEquals(1, status.attemptsStarted)
                assertNull(status.lastError)
                assertEquals("tesseract-android", status.configuration.engineId)
            }
        }
        val hits = graph.store.savedOcr.find(query, null, 20).hits.filter { it.document == document }
        assertEquals(listOf(saved[1].pageId), hits.map { it.page })
        withTimeout(15_000) {
            waitFor {
                // Run on a dedicated guest with no competing import. Prior successful wakes must not satisfy this witness.
                // A delivery can defer behind the per-store OCR lane. Only the durable recognition count above is required to be one.
                wakes().filterNot { it.id in existingWakes }.takeIf { it.size == 2 && it.all { wake ->
                    wake.state == WorkInfo.State.SUCCEEDED
                } }
            }
        }
        val label = context.getString(R.string.home_matching_page, title, 2)
        compose.onNodeWithTag("home-album-search").performTextInput(query)
        compose.waitUntil(15_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText(label).performClick()
        val editor = compose.runOnUiThread { ViewModelProvider(compose.activity)[EditorViewModel::class.java] }
        compose.waitUntil(15_000) { editor.session.value?.state?.value?.page != null }
        assertEquals(saved[1].pageId, editor.session.value?.state?.value?.page?.pageId)
    }

    private suspend fun <T : Any> waitFor(read: suspend () -> T?): T {
        while (true) {
            read()?.let { return it }
            delay(100)
        }
    }

    private fun makePage(file: File, word: String) {
        check(file.createNewFile())
        val bitmap = Bitmap.createBitmap(1024, 480, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 76f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            Canvas(bitmap).drawText("Receipt $word", 50f, 220f, paint)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }
}
