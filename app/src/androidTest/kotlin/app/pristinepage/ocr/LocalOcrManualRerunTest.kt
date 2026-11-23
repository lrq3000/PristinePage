package app.pristinepage.ocr

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.MainActivity
import app.pristinepage.PristinePageApplication
import app.pristinepage.R
import app.pristinepage.core.ocr.OcrError
import app.pristinepage.data.repository.SavedOcrWork
import app.pristinepage.design.OcrLanguage
import app.pristinepage.editor.EditorViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Start from the real terminal interruption fixture, then use only saved-page UI commands to recover the text. */
@RunWith(AndroidJUnit4::class)
class LocalOcrManualRerunTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun terminalPageCanRerunWithSavedSettingsAndThenDefaults(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as PristinePageApplication
        val argument = InstrumentationRegistry.getArguments().getString("recoveryAlbum")
        assumeTrue("Requires the exact failed synthetic recovery album", app.hasBundledOcr && argument != null)
        val title = checkNotNull(argument)
        check(title.matches(Regex("OCR recovery [a-f0-9]{12}")))
        val graph = app.services.imports()
        val document = graph.store.catalog.list(null, 50).single { it.title == title }.documentId
        val saved = graph.store.catalog.load(document).pages.single()
        val page = saved.pageId
        val store = graph.store.savedOcr
        suspend fun status() = checkNotNull(store.readStatus(document, page, saved.recipeRevision))
        val failed = status()
        assertEquals(SavedOcrWork.PagePhase.FAILED, failed.phase)
        assertEquals(3, failed.attemptsStarted)
        assertEquals(OcrError.INTERRUPTED, failed.lastError)
        val original = listOf("eng", "fra")
        val reversed = original.reversed()
        assertEquals(original, failed.configuration.languagePacks?.ordered?.map { it.code })
        val preferences = app.atelierPreferences
        assertEquals(original, preferences.settings.value.ocrLanguages.map { it.packCode })
        val english = OcrLanguage.entries.single { it.packCode == "eng" }
        val french = OcrLanguage.entries.single { it.packCode == "fra" }
        try {
            compose.onNodeWithTag("atelier-settings").performClick()
            compose.onNodeWithTag("settings-open-ocr").performClick()
            compose.onNodeWithTag("settings-open-ocr_languages").performClick()
            // Remove then re-add English so the caller's current defaults differ from the failed job's saved order.
            repeat(2) { compose.onNodeWithTag("settings-ocr-eng").performScrollTo().performClick() }
            assertEquals(reversed, preferences.settings.value.ocrLanguages.map { it.packCode })
            repeat(3) { compose.onNodeWithTag("settings-back").performClick() }
            // Changing defaults alone must leave this existing failed generation untouched.
            assertEquals(3, status().attemptsStarted)
            assertEquals(OcrError.INTERRUPTED, status().lastError)
            compose.onNodeWithTag("home-album-search").performTextInput(title)
            val album = "atelier-album-${document.value}"
            // The text field itself may already expose the exact title; wait for and tap the identified album card instead.
            compose.waitUntil(15_000) { compose.onAllNodesWithTag(album).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag(album).performClick()
            val tile = "original-page-${page.value}"
            compose.waitUntil(15_000) { compose.onAllNodesWithTag(tile).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag(tile).performClick()
            val editor = compose.runOnUiThread { ViewModelProvider(compose.activity)[EditorViewModel::class.java] }
            compose.waitUntil(15_000) { editor.session.value?.state?.value?.page?.pageId == page }
            val close = context.getString(R.string.atelier_close)
            suspend fun rerun(label: Int, order: List<String>) {
                compose.onNodeWithTag("editor-tool-processing-details").performScrollTo().performClick()
                compose.onNodeWithText(context.getString(label)).performScrollTo().performClick()
                val ready = withTimeout(120_000) {
                    var current = status()
                    while (current.phase != SavedOcrWork.PagePhase.READY || current.configuration.languagePacks?.ordered?.map { it.code } != order) {
                        delay(150)
                        current = status()
                    }
                    current
                }
                assertEquals(1, ready.attemptsStarted)
                assertNull(ready.lastError)
                val hit = store.find("MAGNOLIA", null, 20).hits.single { it.document == document }
                assertEquals(page, hit.page)
                assertTrue(store.canOpen(hit))
                compose.onNodeWithText(close).performClick()
            }
            rerun(R.string.ocr_rerun_saved, original)
            rerun(R.string.ocr_rerun_defaults, reversed)
        } finally {
            compose.runOnUiThread {
                preferences.setOcrLanguage(english, true)
                preferences.setOcrLanguage(french, false)
                preferences.setOcrLanguage(french, true)
            }
        }
    }
}
