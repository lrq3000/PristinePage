package app.pristinepage.ocr

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.MainActivity
import app.pristinepage.PristinePageApplication
import app.pristinepage.R
import app.pristinepage.core.ocr.OcrLayout
import app.pristinepage.data.model.IngestResult
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.PageId
import app.pristinepage.data.repository.SavedOcrWork
import app.pristinepage.design.OcrLanguage
import app.pristinepage.editor.EditorViewModel
import app.pristinepage.library.ApplicationLibraryActions
import app.pristinepage.library.ImportLibraryViewModel
import app.pristinepage.library.LibraryPhotoResult
import java.io.File
import java.io.DataInputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic controls supplied out of band; Settings, save enrollment, renderer, Tesseract, Room and Home are the actual app paths. */
@RunWith(AndroidJUnit4::class)
class LocalOcrLanguageJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsSelectedScriptsReachTheExactHomePage(): Unit = runBlocking {
        recognize(listOf(
            listOf("eng", "fra") to listOf(ENGLISH, FRENCH, BLANK),
            listOf("ara", "eng") to listOf(MIXED),
            listOf("ara") to listOf(ARABIC),
            listOf("chi_sim") to listOf(CHINESE),
        ), OcrLayout.AUTOMATIC)
    }

    @Test
    fun japaneseSparseLayoutRemainsBoundToTheExactSearchHit(): Unit = runBlocking {
        recognize(listOf(listOf("jpn") to listOf(JAPANESE)), OcrLayout.SPARSE_TEXT)
    }

    @Test
    fun remainingLatinLanguagesReachTheExactHomePage(): Unit = runBlocking {
        recognize(PinnedOcrScriptControls.latin.map { listOf(it.id) to listOf(it) }, OcrLayout.SINGLE_BLOCK)
    }

    @Test
    fun remainingNonLatinLanguagesReachTheExactHomePage(): Unit = runBlocking {
        recognize(PinnedOcrScriptControls.mixedScripts.map { listOf(it.id) to listOf(it) }, OcrLayout.SINGLE_BLOCK)
    }

    private suspend fun recognize(groups: List<Pair<List<String>, List<OcrScriptControl>>>, layout: OcrLayout) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as PristinePageApplication
        val fixture = InstrumentationRegistry.getArguments().getString("ocrFixture")
        assumeTrue("Requires an exact synthetic fixture directory", fixture != null)
        val prefix = checkNotNull(fixture)
        check(prefix.matches(Regex("[a-f0-9]{12}")))
        val graph = app.services.imports()
        val library = compose.runOnUiThread { ViewModelProvider(compose.activity)[ImportLibraryViewModel::class.java].session }
        val actions = ApplicationLibraryActions(context, app.savedOcrSignals::onPagesSaved, app.services::imports)
        val folder = File(context.cacheDir, "editor-journey-fixtures")
        check(folder.isDirectory || folder.mkdirs())
        val initial = app.atelierPreferences.settings.value.ocrLanguages.map { it.packCode }
        assertEquals(listOf("eng", "fra"), initial)
        val initialLayout = app.atelierPreferences.settings.value.ocrLayout
        assertEquals(OcrLayout.AUTOMATIC, initialLayout)
        val completed = mutableListOf<Triple<DocumentId, PageId, List<String>>>()
        var sparseProducer: SavedOcrWork.Hit? = null
        try {
            if (layout != initialLayout) selectLayout(app, layout)
            for ((order, controls) in groups) {
                applyOrder(app, order)
                for (control in controls) {
                    val descriptor = instrumentation.uiAutomation.executeShellCommand("cat /sdcard/Download/pristinepage-ocr-$prefix/${control.id}.png")
                    val image = ByteArray(control.bytes)
                    DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(descriptor)).use {
                        it.readFully(image)
                        assertEquals(-1, it.read())
                    }
                    assertEquals(control.sha, MessageDigest.getInstance("SHA-256").digest(image).joinToString("") { "%02x".format(it) })
                    val id = UUID.randomUUID().toString()
                    File(folder, "$id.png").apply { check(createNewFile()); writeBytes(image) }
                    val title = "Language control ${control.id} $prefix"
                    val document = graph.store.documents.createDocument(title)
                    val request = actions.begin(graph.store.catalog.load(document).document)
                    val page = (actions.deliver(request, LibraryPhotoResult.Selected(
                        listOf("content://${context.packageName}.editor-fixtures/$id"), 0,
                    )).single() as IngestResult.Committed).pageId
                    val result = withTimeout(115_000) {
                        var text = graph.store.savedOcr.readText(document, page)
                        while (text == null) {
                            delay(100)
                            text = graph.store.savedOcr.readText(document, page)
                        }
                        text
                    }
                    assertFalse(result.previousGeneration)
                    assertEquals(order, result.configuration.languagePacks?.ordered?.map { it.code })
                    assertEquals(layout, result.configuration.layout)
                    assertEquals(1, graph.store.savedOcr.readStatus(document, page)?.attemptsStarted)
                    if (control.queries.isEmpty()) assertTrue("Blank control returned visible text", result.text.value.isEmpty())
                    else for (query in control.queries) {
                        val hits = graph.store.savedOcr.find(query, null, 20).hits.filter { it.document == document }
                        assertEquals("Stored query failed for ${control.id}", listOf(page), hits.map { it.page })
                        if (layout == OcrLayout.SPARSE_TEXT) sparseProducer = hits.single()
                        val hit = context.getString(R.string.home_matching_page, title, 1)
                        val search = compose.onNodeWithTag("home-album-search")
                        // Exercise a newly entered query, not an already displayed result set from another control.
                        search.performTextReplacement("")
                        search.performTextReplacement(query)
                        try {
                            compose.waitUntil(15_000) { library.state.value.pageMatches.any { it.document == document && it.page == page } }
                        } catch (missing: androidx.compose.ui.test.ComposeTimeoutException) {
                            throw AssertionError("No exact Home hit for the ${control.id} control", missing)
                        }
                        // Retained synthetic albums can place the exact result outside the initially composed LazyColumn items.
                        compose.onNodeWithTag("atelier-album-list").performScrollToNode(hasText(hit))
                        assertEquals(1, compose.onAllNodesWithText(hit).fetchSemanticsNodes().size)
                        compose.onNodeWithText(hit).performClick()
                        val editor = compose.runOnUiThread { ViewModelProvider(compose.activity)[EditorViewModel::class.java] }
                        compose.waitUntil(15_000) { editor.session.value?.state?.value?.page?.pageId == page }
                        compose.onNodeWithTag("editor-back").performClick()
                        compose.waitUntil(15_000) { compose.onAllNodesWithTag("atelier-home-back").fetchSemanticsNodes().size == 1 }
                        compose.onNodeWithTag("atelier-home-back").performClick()
                    }
                    completed += Triple(document, page, order)
                    instrumentation.sendStatus(65, android.os.Bundle().apply { putString("ocrLanguageControl", control.id) })
                }
            }
            if (layout == OcrLayout.SPARSE_TEXT) {
                selectLayout(app, initialLayout)
                // The admitted hit retains the original producer, so equality of the displayed layout alone cannot hide replacement.
                assertTrue(graph.store.savedOcr.canOpen(checkNotNull(sparseProducer)))
            }
            for ((document, page, order) in completed) {
                val status = checkNotNull(graph.store.savedOcr.readStatus(document, page))
                val text = checkNotNull(graph.store.savedOcr.readText(document, page))
                assertEquals(1, status.attemptsStarted)
                assertEquals(order, text.configuration.languagePacks?.ordered?.map { it.code })
                assertEquals(layout, status.configuration.layout)
                assertEquals(layout, text.configuration.layout)
            }
        } finally {
            // Restore only this test's preference even when a failed assertion leaves a non-Home screen open.
            val preference = app.atelierPreferences
            val english = OcrLanguage.entries.single { it.packCode == initial[0] }
            val french = OcrLanguage.entries.single { it.packCode == initial[1] }
            compose.runOnUiThread {
                preference.setOcrLanguage(english, true)
                preference.settings.value.ocrLanguages.filter { it != english }.forEach { preference.setOcrLanguage(it, false) }
                preference.setOcrLanguage(french, true)
                preference.setOcrLayout(initialLayout)
            }
        }
    }

    private fun selectLayout(app: PristinePageApplication, expected: OcrLayout) {
        compose.onNodeWithTag("atelier-settings").performClick()
        compose.onNodeWithTag("settings-open-ocr").performClick()
        compose.onNodeWithTag("settings-ocr-layout-${expected.name.lowercase(java.util.Locale.ROOT)}").performScrollTo().performClick()
        assertEquals(expected, app.atelierPreferences.settings.value.ocrLayout)
        repeat(2) { compose.onNodeWithTag("settings-back").performClick() }
    }

    private fun applyOrder(app: PristinePageApplication, order: List<String>) {
        val current = { app.atelierPreferences.settings.value.ocrLanguages.map { it.packCode } }
        if (current() == order) return
        compose.onNodeWithTag("atelier-settings").performClick()
        compose.onNodeWithTag("settings-open-ocr").performClick()
        compose.onNodeWithTag("settings-open-ocr_languages").performClick()
        fun toggle(code: String) = compose.onNodeWithTag("settings-ocr-$code").performScrollTo().performClick()
        // Keep an anchor language while replacing the ordering. The UI deliberately forbids an empty selection.
        val anchor = order.first()
        if (anchor !in current()) toggle(anchor)
        current().filterNot { it == anchor }.forEach { toggle(it) }
        order.drop(1).forEach { toggle(it) }
        assertEquals(order, current())
        repeat(3) { compose.onNodeWithTag("settings-back").performClick() }
    }

    companion object {
        private val ENGLISH = OcrScriptControl("eng", 7242, "f4585967198db8a51a7d8afae50e449bdead2ad3b4122d1478afffc5fbd5608f", listOf("customer"))
        private val FRENCH = OcrScriptControl("fra", 6329, "eaff0fce642ed42cd90a79508fe42e8a7ab44680bc7ed8a0cc9b2a771fda5884", listOf("montant"))
        private val BLANK = OcrScriptControl("blank", 556, "ff448c425e99fc723185ad8de20a82d9d125b9831c375ff8501baf95487a3ac4", emptyList())
        private val ARABIC = OcrScriptControl("ara", 6525, "d05adf97c5b5e716546e001b36632aac0b81c548ad83410f6f01f4f5052f0dc0", listOf("مكتوب"))
        private val CHINESE = OcrScriptControl("chi_sim", 7653, "2007a8158c43d0a9235a4897aa94532cf069439769bc63c8c2f5b3408eff3304", listOf("付款总额"))
        private val MIXED = OcrScriptControl("ara-eng", 13032, "3b0deec873bac14e914bc45fd66c41486269c85cda20b3a9ae8d2bb7911910cb", listOf("customer", "مكتوب"))
        private val JAPANESE = OcrScriptControl("jpn", 14002, "26ee4767e3823ac846ffe0f9fe1e48d0bcc4a0d646487c53cb28fd13f100b8df", listOf("領収書"))
    }
}
