package app.pristinepage.ocr

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.view.inspector.WindowInspectorCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.pristinepage.MainActivity
import app.pristinepage.PristinePageApplication
import app.pristinepage.R
import app.pristinepage.core.ocr.OcrLayout
import app.pristinepage.core.recipe.AppearanceRecipe
import app.pristinepage.core.recipe.BinaryRecipe
import app.pristinepage.data.model.IngestResult
import app.pristinepage.data.repository.SavedOcrWork
import app.pristinepage.editor.EditorViewModel
import app.pristinepage.export.AlbumExportViewModel
import app.pristinepage.library.ApplicationLibraryActions
import app.pristinepage.library.ImportLibraryViewModel
import app.pristinepage.library.LibraryEvent
import app.pristinepage.library.LibraryPhotoResult
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private fun View.ocrTextViews(): Sequence<ReadOnlyOcrTextView> = sequence {
    if (this@ocrTextViews is ReadOnlyOcrTextView) yield(this@ocrTextViews)
    if (this@ocrTextViews is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).ocrTextViews())
}

/** The debug provider supplies only synthetic images; import acknowledgement, WorkManager, recognition, Room and Home are the running app's own. This does not exercise a system picker. */
@RunWith(AndroidJUnit4::class)
class LocalOcrImportJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun importedScansAreRecognizedAutomaticallyAndHomeOpensTheSecondPage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as PristinePageApplication
        val graph = app.services.imports()
        val nonce = UUID.randomUUID().toString()
        val title = "Synthetic OCR import $nonce"
        val document = graph.store.documents.createDocument(title)
        val dir = File(context.cacheDir, "editor-journey-fixtures")
        check(dir.isDirectory || dir.mkdirs())
        val words = listOf("BLUEBELL", "ALBATROSS")
        val reference = Random.nextInt(100_000, 1_000_000).toString()
        val refs = words.mapIndexed { index, word ->
            val id = UUID.randomUUID().toString()
            makePage(File(dir, "$id.png"), word, reference.takeIf { index == 1 })
            "content://${context.packageName}.editor-fixtures/$id"
        }
        val actions = ApplicationLibraryActions(context, app.savedOcrSignals::onPagesSaved, app.services::imports)
        val query = "${words[1]} $reference"
        val label = context.getString(R.string.home_matching_page, title, 2)
        val library = compose.runOnUiThread { ViewModelProvider(compose.activity)[ImportLibraryViewModel::class.java].session }
        compose.onNodeWithTag("home-album-search").performTextInput(query)
        compose.waitUntil(15_000) {
            library.state.value.let { it.albumSearch == query && !it.loading }
        }
        assertFalse(library.state.value.pageMatches.any { it.document == document })
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
        compose.waitUntil(15_000) {
            library.state.value.let { it.albumSearch == query && it.pageMatches.any { h -> h.document == document && h.page == saved[1].pageId } }
        }
        compose.onNodeWithTag("atelier-album-list").performScrollToNode(hasText(label))
        assertEquals(1, compose.onAllNodesWithText(label).fetchSemanticsNodes().size)
        compose.onNodeWithText(label).performClick()
        val editor = compose.runOnUiThread { ViewModelProvider(compose.activity)[EditorViewModel::class.java] }
        compose.waitUntil(15_000) { editor.session.value?.state?.value?.page != null }
        val original = checkNotNull(editor.session.value?.state?.value?.page)
        assertEquals(saved[1].pageId, original.pageId)
        val originalHash = graph.store.committedSources.load(original.sourceId).sourceByteHash
        val precedingWakes = wakes().map { it.id }.toSet()
        val staleHit = hits.single()
        assertTrue(graph.store.savedOcr.canOpen(staleHit))

        // Change a real accepted recipe from the saved editor, without requesting recognition from the test.
        compose.onNodeWithTag("editor-tool-looks").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("editor-look-gray").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("editor-apply").assertIsEnabled().performClick()
        compose.waitUntil(15_000) { editor.session.value?.state?.value?.page?.recipeRevision == original.recipeRevision + 1 }
        val edited = graph.store.editablePages.load(document, original.pageId)
        assertEquals(original.recipeRevision + 1, edited.recipeRevision)
        assertEquals(original.sourceId, edited.sourceId)
        assertEquals(originalHash, graph.store.committedSources.load(edited.sourceId).sourceByteHash)
        assertEquals(BinaryRecipe.GrayscaleV1, edited.recipe.binary)
        assertFalse(graph.store.savedOcr.canOpen(staleHit))
        assertNull(graph.store.savedOcr.readText(document, edited.pageId, original.recipeRevision))
        val refreshed = withTimeout(120_000) {
            waitFor { graph.store.savedOcr.readStatus(document, edited.pageId, edited.recipeRevision)
                ?.takeIf { it.phase == SavedOcrWork.PagePhase.READY } }
        }
        assertEquals(1, refreshed.attemptsStarted)
        assertNull(refreshed.lastError)
        assertEquals("tesseract-android", refreshed.configuration.engineId)
        withTimeout(15_000) {
            waitFor { wakes().filterNot { it.id in precedingWakes }.singleOrNull()?.takeIf { it.state == WorkInfo.State.SUCCEEDED } }
        }
        val updated = graph.store.savedOcr.find(query, null, 20).hits.filter { it.document == document }
        assertEquals(listOf(edited.pageId), updated.map { it.page })
        assertTrue(graph.store.savedOcr.canOpen(updated.single()))

        compose.onNodeWithTag("editor-back").performClick()
        compose.onNodeWithTag("atelier-home-back").assertIsEnabled().performClick()
        compose.waitUntil(15_000) {
            library.state.value.let { !it.loading && it.selectedId == null && it.albumSearch == query &&
                it.pageMatches.any { hit -> hit.document == document && hit.page == edited.pageId } }
        }
        compose.onNodeWithTag("atelier-album-list").performScrollToNode(hasText(label))
        compose.onNodeWithText(label).performClick()
        compose.waitUntil(15_000) {
            editor.session.value?.state?.value?.page?.let { it.pageId == edited.pageId && it.recipeRevision == edited.recipeRevision } == true
        }
    }

    @Test
    fun coldHomeSearchReopensTheSavedFilesPageAndCreatesJpeg(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as PristinePageApplication
        val title = InstrumentationRegistry.getArguments().getString("filesSavedAlbum")
        assumeTrue("Requires the exact album from the separate real Files picker and correction run", title != null)
        val name = checkNotNull(title)
        val graph = app.services.imports()
        val document = graph.store.catalog.list(null, 50).single { it.title == name }
        val page = graph.store.catalog.load(document.documentId).pages.single()
        val saved = graph.store.editablePages.load(document.documentId, page.pageId)
        val inspectPicker = InstrumentationRegistry.getArguments().getString("filesInspectPicker") == "1"
        val textChooserMode = InstrumentationRegistry.getArguments().getString("filesInspectTextChooser")
        require(textChooserMode == null || textChooserMode in setOf("1", "3", "4"))
        val inspectTextChooser = textChooserMode != null
        val inspectDetails = InstrumentationRegistry.getArguments().getString("filesInspectOcrDetails") == "1"
        require(!inspectPicker || !inspectTextChooser)
        if (inspectPicker || inspectTextChooser || inspectDetails) {
            val original = graph.store.committedSources.load(saved.sourceId)
            assertEquals("Save as is permitted only for the exact synthetic Files input",
                "f4585967198db8a51a7d8afae50e449bdead2ad3b4122d1478afffc5fbd5608f", original.sourceByteHash)
        }
        assertTrue("The wizard's Natural lightness correction was saved", saved.recipe.appearance is AppearanceRecipe.NaturalLV1)
        val text = withTimeout(120_000) {
            waitFor { graph.store.savedOcr.readText(document.documentId, page.pageId, saved.recipeRevision) }
        }
        assertFalse(text.previousGeneration)
        assertTrue("Expected fixture words in the saved OCR result", text.text.value.contains("customer paid", ignoreCase = true))
        if (inspectDetails) {
            // The prior controlled interruption deliberately left a durable notice. Do not let it overlay this panel.
            val owner = compose.runOnUiThread { ViewModelProvider(compose.activity)[OcrTextSaveViewModel::class.java] }
            if (owner.state.value.pastWriteNeedsVerification) {
                closeOcrDialog(context, R.string.ocr_save_text_interrupted)
                assertFalse(owner.state.value.pastWriteNeedsVerification)
            }
        }
        val hit = context.getString(R.string.home_matching_page, name, 1)
        compose.onNodeWithTag("home-album-search").performTextInput("customer")
        val library = compose.runOnUiThread { ViewModelProvider(compose.activity)[ImportLibraryViewModel::class.java].session }
        compose.waitUntil(15_000) {
            library.state.value.pageMatches.any { it.document == document.documentId && it.page == page.pageId }
        }
        compose.onNodeWithTag("atelier-album-list").performScrollToNode(hasText(hit))
        assertEquals(1, compose.onAllNodesWithText(hit).fetchSemanticsNodes().size)
        compose.onNodeWithText(hit).performClick()
        val editor = compose.runOnUiThread { ViewModelProvider(compose.activity)[EditorViewModel::class.java] }
        compose.waitUntil(15_000) { editor.session.value?.state?.value?.page != null }
        assertEquals(page.pageId, editor.session.value?.state?.value?.page?.pageId)
        if (inspectDetails) {
            require(!inspectTextChooser && !inspectPicker)
            verifyOptInProcessingDetails(app, context, library, editor, document.documentId, page.pageId, saved.recipeRevision, hit, text.text.value)
            instrumentation.addResults(Bundle().apply { putString("filesWitness", "OCR_DETAILS_OPT_IN_SAME_PRODUCER") })
            return@runBlocking
        }
        if (InstrumentationRegistry.getArguments().getString("filesSearchOnly") == "1") {
            require(!inspectPicker)
            instrumentation.addResults(Bundle().apply { putString("filesWitness", "EXACT_PAGE_SEARCH") })
            return@runBlocking
        }
        if (!inspectPicker) {
            val ocrTool = compose.onNodeWithTag("editor-tool-ocr-text").performScrollTo().assertIsEnabled()
            // Invoke the real accessible action on the horizontal ribbon: a touch can be clipped or obscured independently of the dialog.
            ocrTool.performSemanticsAction(SemanticsActions.OnClick) { action -> check(action()) }
            fun observed(label: Int) = compose.onAllNodesWithText(context.getString(label)).fetchSemanticsNodes().size
            try {
                compose.waitUntil(15_000) {
                    compose.runOnUiThread {
                        WindowInspectorCompat.getGlobalWindowViews().flatMap { it.ocrTextViews().toList() }
                            .count { it.isShown && it.text.toString() == text.text.value } == 1
                    }
                }
            } catch (miss: androidx.compose.ui.test.ComposeTimeoutException) {
                throw AssertionError("Saved OCR panel: close=${observed(R.string.atelier_close)}, loading=${observed(R.string.ocr_details_loading)}, unavailable=${observed(R.string.ocr_details_unavailable)}, noText=${observed(R.string.ocr_text_not_available)}", miss)
            }
            if (inspectTextChooser) {
                val owner = compose.runOnUiThread { ViewModelProvider(compose.activity)[OcrTextSaveViewModel::class.java] }
                compose.onNodeWithTag("ocr-text-save-as").performScrollTo().assertIsEnabled().performClick()
                compose.waitUntil(15_000) { owner.state.value.let { it.consent != null && !it.launchRequested || !it.busy } }
                assertTrue(owner.state.value.let { it.busy && it.consent != null && !it.launchRequested })
                if (textChooserMode == "4") {
                    // The separately scoped host verifies the actual picker and destination. Do not attach a second
                    // UiAutomation client while the host is observing the system window; only test the app result here.
                    val expected = text.text.value.encodeToByteArray()
                    require(expected.isNotEmpty())
                    awaitHostVerifiedTextSave(context, owner, expected)
                    return@runBlocking
                }
                val automation = instrumentation.uiAutomation
                withTimeout(15_000) { waitFor { automation.rootInActiveWindow?.packageName?.toString()?.takeIf { it in FILES_PACKAGES } } }
                if (textChooserMode == "1") {
                    // No destination is selected. Back cancels the authenticated foreground system window.
                    check(automation.rootInActiveWindow?.packageName?.toString() in FILES_PACKAGES)
                    ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input keyevent KEYCODE_BACK")).use { it.readBytes() }
                    compose.waitUntil(15_000) { owner.state.value.let { !it.busy && it.consent == null } }
                    assertNull(owner.state.value.notice)
                    instrumentation.addResults(Bundle().apply { putString("filesWitness", "LOCAL_TEXT_CHOOSER_CANCELED") })
                } else if (textChooserMode == "3") {
                    // Stop this app process after durable consent and before giving the system chooser any input.
                    val consent = checkNotNull(owner.state.value.consent)
                    val row = readTextConsent(context, consent)
                    assertEquals("WAITING_DESTINATION" to true, row)
                    check(automation.rootInActiveWindow?.packageName?.toString() in FILES_PACKAGES)
                    instrumentation.sendStatus(67, Bundle().apply {
                        putString("ocrTextDeathConsent", consent)
                        putInt("ocrTextDeathPid", Process.myPid())
                    })
                    Process.killProcess(Process.myPid())
                    error("The exact app process did not exit")
                }
                return@runBlocking
            }
            closeOcrDialog(context, R.string.ocr_text)
        }
        compose.onNodeWithTag("editor-back").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("album-export-all").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("album-export-all").performClick()
        compose.onNodeWithText("${name}_1.jpg").assertExists()
        compose.onNodeWithTag("album-export-generate").assertIsEnabled().performClick()
        compose.waitUntil(45_000) { compose.onAllNodesWithTag("album-export-save-as").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("album-export-save-as").assertIsEnabled()
        if (!inspectPicker) return@runBlocking
        val export = compose.runOnUiThread { ViewModelProvider(compose.activity)[AlbumExportViewModel::class.java] }
        var stage = "before tap"
        val automation = instrumentation.uiAutomation
        fun resumed() = runCatching {
            val handle = automation.executeShellCommand("dumpsys activity activities")
            ParcelFileDescriptor.AutoCloseInputStream(handle).bufferedReader().useLines { lines ->
                // Retain only resumed component names, not task intents, extras, titles or URIs.
                lines.mapNotNull { RESUMED_COMPONENT.find(it)?.groupValues?.get(1) }.distinct().take(4).toList()
            }
        }.getOrNull()
        fun boundaryFailure(reason: String): Nothing {
            val state = export.state.value
            throw AssertionError(
                "Save as $reason at $stage: busy=${state.busy}; prepared=${state.publicationId != null}; waiting=${state.waitingForTarget}; " +
                    "launchPending=${state.launch != null}; error=${state.error}; notice=${state.notice}; resumed=${resumed() ?: "unavailable"}",
            )
        }
        try {
            val idle = export.state.value
            check(!idle.busy && !idle.waitingForTarget && idle.publicationId == null && idle.launch == null)
            check(resumed()?.singleOrNull() == "${context.packageName}/${MainActivity::class.java.name}")
            stage = "launch"
            compose.onNodeWithTag("album-export-save-as").performClick()
            // The preparation returns asynchronously. Keep advancing Compose until its ActivityResult launch effect has run before switching to system-window snapshots.
            compose.waitUntil(15_000) {
                export.state.value.let {
                    it.error != null || it.notice != null || it.waitingForTarget && it.publicationId != null && it.launch == null
                }
            }
            val launch = export.state.value
            check(launch.waitingForTarget && launch.publicationId != null && launch.launch == null && launch.error == null && launch.notice == null)
            stage = "foreground chooser"
            val picker = withTimeout(15_000) {
                waitFor { automation.rootInActiveWindow?.packageName?.toString()?.takeIf { it in FILES_PACKAGES } }
            }
            val activity = resumed()?.singleOrNull()
            check(activity == "$picker/.picker.PickActivity" || activity == "$picker/$picker.picker.PickActivity")
            val pending = export.state.value
            check(pending.publicationId == launch.publicationId && pending.waitingForTarget && pending.launch == null &&
                pending.error == null && pending.notice == null)
        } catch (_: TimeoutCancellationException) {
            boundaryFailure("timed out")
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (_: Exception) {
            boundaryFailure("refused")
        } catch (_: AssertionError) {
            boundaryFailure("assertion failed")
        }
        // Prove only that the chooser actually opened. No input or destination selection is allowed inside it.
        instrumentation.addResults(Bundle().apply { putString("filesWitness", "PICKER_OPENED_WITHOUT_CONFIRMATION") })
    }

    private fun closeOcrDialog(context: android.content.Context, title: Int) {
        val scope = isDialog() and hasAnyDescendant(hasText(context.getString(title)))
        compose.onNode(hasText(context.getString(R.string.atelier_close)) and hasAnyAncestor(scope)).performClick()
    }

    @Test
    fun recognizedBlankPageCanSaveAnEmptyTextDocument(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as PristinePageApplication
        assumeTrue("The blank save requires a guarded host-side DocumentsUI driver",
            InstrumentationRegistry.getArguments().getString("ocrBlankTextSave") == "1")
        val graph = app.services.imports()
        val document = graph.store.documents.createDocument("Blank OCR text control ${UUID.randomUUID()}")
        val dir = File(context.cacheDir, "editor-journey-fixtures")
        check(dir.isDirectory || dir.mkdirs())
        val id = UUID.randomUUID().toString()
        val file = File(dir, "$id.png")
        check(file.createNewFile())
        val blank = Bitmap.createBitmap(400, 440, Bitmap.Config.ARGB_8888)
        try {
            blank.eraseColor(Color.WHITE)
            file.outputStream().use { check(blank.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { blank.recycle() }
        val actions = ApplicationLibraryActions(context, app.savedOcrSignals::onPagesSaved, app.services::imports)
        val request = actions.begin(graph.store.catalog.load(document).document)
        val page = (actions.deliver(request, LibraryPhotoResult.Selected(
            listOf("content://${context.packageName}.editor-fixtures/$id"), 0,
        )).single() as IngestResult.Committed).pageId
        val saved = graph.store.editablePages.load(document, page)
        val work = graph.store.savedOcr
        val text = withTimeout(70_000) { waitFor { work.readText(document, page, saved.recipeRevision) } }
        assertEquals("", text.text.value)
        assertFalse(text.previousGeneration)
        val status = checkNotNull(work.readStatus(document, page, saved.recipeRevision))
        assertEquals(SavedOcrWork.PagePhase.READY, status.phase)
        assertEquals(1, status.attemptsStarted)
        val library = compose.runOnUiThread { ViewModelProvider(compose.activity)[ImportLibraryViewModel::class.java].session }
        compose.waitUntil(10_000) { !library.state.value.loading }
        compose.runOnUiThread { library.accept(LibraryEvent.Open(document)) }
        compose.waitUntil(15_000) { library.state.value.document?.pages?.singleOrNull()?.pageId == page }
        compose.onNodeWithTag("original-page-${page.value}").performScrollTo().assertIsEnabled().performClick()
        val editor = compose.runOnUiThread { ViewModelProvider(compose.activity)[EditorViewModel::class.java] }
        compose.waitUntil(15_000) { editor.session.value?.state?.value?.page?.let { it.pageId == page && it.recipeRevision == saved.recipeRevision } == true }
        compose.onNodeWithTag("editor-tool-ocr-text").performScrollTo().assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { check(it()) }
        compose.waitUntil(15_000) { compose.onAllNodesWithText(context.getString(R.string.ocr_text_blank)).fetchSemanticsNodes().size == 1 }
        val owner = compose.runOnUiThread { ViewModelProvider(compose.activity)[OcrTextSaveViewModel::class.java] }
        compose.onNodeWithTag("ocr-text-save-as").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(15_000) { owner.state.value.let { it.consent != null && !it.launchRequested || !it.busy } }
        assertTrue(owner.state.value.let { it.busy && it.consent != null && !it.launchRequested })
        awaitHostVerifiedTextSave(context, owner, byteArrayOf())
    }

    /** The host checks the real provider and sends the sole confirmation, then separately hashes the closed file. */
    private suspend fun awaitHostVerifiedTextSave(context: android.content.Context, owner: OcrTextSaveViewModel, expected: ByteArray) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(expected).joinToString("") { "%02x".format(it) }
        instrumentation.sendStatus(68, Bundle().apply {
            putString("ocrTextExpectedSha", digest)
            putInt("ocrTextExpectedBytes", expected.size)
        })
        withTimeout(165_000) { waitFor { owner.state.value.takeIf { !it.busy && it.consent == null } } }
        assertEquals(R.string.ocr_save_text_verified, owner.state.value.notice)
        compose.onNodeWithText(context.getString(R.string.ocr_save_text_verified)).assertExists()
        instrumentation.addResults(Bundle().apply { putString("filesWitness", "LOCAL_TEXT_APP_VERIFIED") })
    }

    private suspend fun verifyOptInProcessingDetails(
        app: PristinePageApplication,
        context: android.content.Context,
        library: app.pristinepage.library.LibrarySession,
        editor: EditorViewModel,
        document: app.pristinepage.data.model.DocumentId,
        page: app.pristinepage.data.model.PageId,
        revision: Long,
        hitLabel: String,
        recognizedText: String,
    ) {
        val preferences = app.atelierPreferences
        assertFalse(preferences.settings.value.ocrDiagnostics)
        val work = app.services.imports().store.savedOcr
        val hit = work.find("customer", null, 50).hits.single { it.document == document && it.page == page }
        val original = checkNotNull(work.readStatus(document, page, revision))
        val input = context.getString(R.string.ocr_details_planned_input, 1500, 232)
        val appearance = context.getString(R.string.ocr_details_planned_appearance, context.getString(R.string.editor_look_natural))
        fun planned(enabled: Boolean) {
            compose.onNodeWithTag("editor-tool-processing-details").performScrollTo().assertIsEnabled()
                .performSemanticsAction(SemanticsActions.OnClick) { check(it()) }
            val noErrors = context.getString(R.string.ocr_details_no_errors)
            compose.waitUntil(15_000) { compose.onAllNodesWithText(noErrors).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText(context.getString(R.string.ocr_details_ready)).assertExists()
            compose.onNodeWithText(context.getString(R.string.ocr_details_attempts, original.attemptsStarted)).assertExists()
            assertTrue("Processing details must not expose the saved OCR result",
                compose.onAllNodesWithText(recognizedText).fetchSemanticsNodes().isEmpty())
            if (enabled) {
                compose.onNodeWithText(input).assertExists()
                compose.onNodeWithText(appearance).assertExists()
            } else {
                compose.onNodeWithText(input).assertDoesNotExist()
                compose.onNodeWithText(appearance).assertDoesNotExist()
            }
            closeOcrDialog(context, R.string.ocr_processing_details)
        }
        planned(false)
        try {
            compose.onNodeWithTag("editor-back").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("atelier-home-back").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("atelier-home-back").performClick()
            compose.onNodeWithTag("atelier-settings").performClick()
            compose.onNodeWithTag("settings-open-ocr").performClick()
            compose.onNodeWithTag("settings-ocr-diagnostics").performScrollTo().performClick()
            assertTrue(preferences.settings.value.ocrDiagnostics)
            repeat(2) { compose.onNodeWithTag("settings-back").performClick() }
            compose.onNodeWithTag("home-album-search").performTextReplacement("customer")
            compose.waitUntil(15_000) { library.state.value.pageMatches.any { it.document == document && it.page == page } }
            compose.onNodeWithTag("atelier-album-list").performScrollToNode(hasText(hitLabel))
            compose.onNodeWithText(hitLabel).performClick()
            compose.waitUntil(15_000) { editor.session.value?.state?.value?.page?.let { it.pageId == page && it.recipeRevision == revision } == true }
            planned(true)
            assertEquals(original.attemptsStarted, work.readStatus(document, page, revision)?.attemptsStarted)
            assertTrue(work.canOpen(hit))
        } finally {
            compose.runOnUiThread { preferences.setOcrDiagnostics(false) }
            assertFalse(preferences.settings.value.ocrDiagnostics)
        }
    }

    private companion object {
        val FILES_PACKAGES = setOf("com.android.documentsui", "com.google.android.documentsui")
        val RESUMED_COMPONENT = Regex("""(?:mResumedActivity:|topResumedActivity=)\s+ActivityRecord\{[^}\r\n]*?\bu\d+\s+([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)\s""")
    }

}

/** Uses a real cold start after the owning application/test process ended with the system chooser open. */
@RunWith(AndroidJUnit4::class)
class LocalOcrTextDeathTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun endProcessAfterClosingTheControlledTextFileBeforeAcknowledgment(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as PristinePageApplication
        val token = InstrumentationRegistry.getArguments().getString("ocrTextOwnedFile")
        val album = InstrumentationRegistry.getArguments().getString("filesSavedAlbum")
        assumeTrue("Requires an explicit synthetic readback interruption", token != null && album != null)
        val key = checkNotNull(token)
        val file = controlledTextFile(context, key)
        val graph = app.services.imports()
        val document = graph.store.catalog.list(null, 50).single { it.title == album }.documentId
        val page = graph.store.catalog.load(document).pages.single()
        val saved = graph.store.editablePages.load(document, page.pageId)
        val source = graph.store.committedSources.load(saved.sourceId)
        assertEquals("f4585967198db8a51a7d8afae50e449bdead2ad3b4122d1478afffc5fbd5608f", source.sourceByteHash)
        val work = graph.store.savedOcr
        val text = checkNotNull(work.readText(document, page.pageId, saved.recipeRevision))
        val frozen = checkNotNull(work.prepareTextExport(text.exportObservation))
        assertTrue(frozen.byteCount > 0)
        assertFalse(file.exists())
        val locator = "content://app.pristinepage.debug.test.synthetic/document/$key"
        var consent: String? = null
        work.publishPreparedText(frozen, choose = { consent = it; locator }) { destination, bytes ->
            assertEquals(locator, destination)
            // Exercise the actual nontruncating descriptor writer. The callback is a controlled private file, not a SAF provider.
            writeOcrTextDocument(bytes, frozen.byteSha256, open = {
                check(file.createNewFile())
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
            }, reopen = {
                val durable = checkNotNull(consent)
                assertEquals("WRITING" to true, readTextConsent(context, durable, locator))
                java.io.RandomAccessFile(file, "rw").use { it.fd.sync() }
                assertArrayEquals(bytes, file.readBytes())
                instrumentation.sendStatus(69, Bundle().apply {
                    putString("ocrTextDeathConsent", durable)
                    putInt("ocrTextDeathPid", Process.myPid())
                    putString("ocrTextClosedSha", frozen.byteSha256)
                    putLong("ocrTextClosedBytes", frozen.byteCount)
                })
                Process.killProcess(Process.myPid())
                error("The exact app process did not exit during transfer readback")
            })
        }
        error("The interrupted transfer must not finish")
    }

    @Test fun coldLaunchKeepsControlledTextFileWithoutAcknowledgingOrReplayingIt(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as PristinePageApplication
        val args = InstrumentationRegistry.getArguments()
        val token = args.getString("ocrTextOwnedFile")
        val id = args.getString("ocrTextDeathConsent")
        assumeTrue("Requires the exact previously interrupted controlled text transfer", token != null && id != null)
        val exactConsent = checkNotNull(id)
        require(UUID.fromString(exactConsent).toString() == exactConsent)
        val file = controlledTextFile(context, checkNotNull(token))
        val extent = checkNotNull(args.getString("ocrTextClosedBytes")).toLong()
        val digest = checkNotNull(args.getString("ocrTextClosedSha"))
        require(extent in 1..393_216 && digest.matches(Regex("[a-f0-9]{64}")))
        assertEquals(extent, file.length())
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(digest, hash)
        val owner = compose.runOnUiThread { ViewModelProvider(compose.activity)[OcrTextSaveViewModel::class.java] }
        compose.waitUntil(20_000) { owner.state.value.pastWriteNeedsVerification }
        val locator = "content://app.pristinepage.debug.test.synthetic/document/$token"
        assertEquals("NEEDS_VERIFICATION" to true, readTextConsent(context, exactConsent, locator))
        assertNull(owner.state.value.consent)
        assertFalse(owner.state.value.launchRequested)
        assertTrue(owner.state.value.mayRetry)
        compose.onNodeWithText(context.getString(R.string.ocr_save_text_interrupted)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.atelier_close)).performClick()
        compose.onNodeWithTag("home-album-search").assertIsDisplayed()
        assertEquals(extent, file.length())
        assertEquals(hash, java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) })
        instrumentation.addResults(Bundle().apply {
            putString("ocrTextRecoveryWitness", "CLOSED_CONTROLLED_FILE_UNCHANGED")
            putInt("ocrTextRecoveryPid", Process.myPid())
        })
    }

    private fun controlledTextFile(context: android.content.Context, token: String): File {
        require(token.matches(Regex("[a-f0-9]{12}")))
        return File(context.filesDir, "ocr-closed-control-$token.txt")
    }

    @Test fun coldLaunchReportsInterruptedChooserWithoutReopeningIt(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as PristinePageApplication
        val consent = InstrumentationRegistry.getArguments().getString("ocrTextDeathConsent")
        assumeTrue("Only run after the named synthetic OCR chooser interruption", consent != null)
        val exactConsent = checkNotNull(consent)
        require(UUID.fromString(exactConsent).toString() == exactConsent)
        val owner = compose.runOnUiThread { ViewModelProvider(compose.activity)[OcrTextSaveViewModel::class.java] }
        compose.waitUntil(20_000) { owner.state.value.pastWriteNeedsVerification }
        val recovered = owner.state.value
        assertNull(recovered.consent)
        assertNull(recovered.destination)
        assertFalse(recovered.busy)
        assertFalse(recovered.launchRequested)
        assertTrue(recovered.mayRetry)
        assertEquals("NEEDS_VERIFICATION" to true, readTextConsent(context, exactConsent))
        compose.onNodeWithText(context.getString(R.string.ocr_save_text_interrupted)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.ocr_save_text_retry)).assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.atelier_close)).performClick()
        compose.onNodeWithTag("home-album-search").assertIsDisplayed()
        // Closing the Compose recovery dialog changes window focus asynchronously. Check the actual app Activity,
        // not a single potentially stale or absent accessibility root during the window handoff.
        compose.waitUntil(10_000) {
            var focused = false
            instrumentation.runOnMainSync { focused = compose.activity.hasWindowFocus() }
            focused
        }
        assertNull(owner.state.value.consent)
        assertFalse(owner.state.value.launchRequested)
        instrumentation.addResults(Bundle().apply {
            putString("ocrTextRecoveryWitness", "ENDED_CHOOSER_NO_AUTOMATIC_DESTINATION")
            putInt("ocrTextRecoveryPid", Process.myPid())
        })
    }
}

/** Bound a scalar test read to the consent observed in-process; do not open the provider or materialize a destination. */
private fun readTextConsent(context: android.content.Context, consent: String, target: String? = null): Pair<String, Boolean> =
    SQLiteDatabase.openDatabase(context.getDatabasePath("pristinepage.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        val targetMatches = if (target == null) "targetUri IS NULL" else "targetUri = ?"
        val args = if (target == null) arrayOf(consent) else arrayOf(target, consent)
        db.rawQuery("SELECT state, $targetMatches FROM OcrTextPublication WHERE consentId = ?", args).use { cursor ->
            check(cursor.moveToFirst())
            val result = cursor.getString(0) to (cursor.getInt(1) == 1)
            check(!cursor.moveToNext())
            result
        }
    }

/** Start from a real saved page with no OCR binding, not from an injected recognizer, wake, or result. */
@RunWith(AndroidJUnit4::class)
class LocalOcrStartupBackfillTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun firstActivityLaunchFindsAndIndexesAnExistingUnqueuedPage(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as PristinePageApplication
        val graph = app.services.imports()
        val title = "Startup backfill ${UUID.randomUUID()}"
        val document = graph.store.documents.createDocument(title)
        val dir = File(context.cacheDir, "editor-journey-fixtures")
        check(dir.isDirectory || dir.mkdirs())
        val id = UUID.randomUUID().toString()
        val keyword = "HONEYDEW"
        makePage(File(dir, "$id.png"), keyword)
        val actions = ApplicationLibraryActions(context, app.services::imports)
        val request = actions.begin(graph.store.catalog.load(document).document)
        val saved = actions.deliver(request, LibraryPhotoResult.Selected(
            listOf("content://${context.packageName}.editor-fixtures/$id"), 0,
        )).single() as IngestResult.Committed
        assertNull(graph.store.savedOcr.readStatus(document, saved.pageId))
        assertFalse(graph.store.savedOcr.find(keyword, null, 20).hits.any { it.document == document })
        // Seed without the post-save signal, then use MainActivity.onCreate as the only enrollment stimulus.
        ActivityScenario.launch(MainActivity::class.java).use {
            val ready = withTimeout(120_000) {
                waitFor { graph.store.savedOcr.readStatus(document, saved.pageId)?.takeIf { it.phase == SavedOcrWork.PagePhase.READY } }
            }
            assertEquals(1, ready.attemptsStarted)
            assertNull(ready.lastError)
            assertEquals("tesseract-android", ready.configuration.engineId)
            val hit = context.getString(R.string.home_matching_page, title, 1)
            compose.onNodeWithTag("home-album-search").performTextInput(keyword)
            compose.waitUntil(15_000) { compose.onAllNodesWithText(hit).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText(hit).performClick()
            lateinit var editor: EditorViewModel
            it.onActivity { activity -> editor = ViewModelProvider(activity)[EditorViewModel::class.java] }
            compose.waitUntil(15_000) { editor.state.value.identity?.pageId == saved.pageId }
        }
    }

    @Test
    fun largerSavedPageCompletesItsExactWakeAndOpensFromTheFooter(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as PristinePageApplication
        assumeTrue("Requires explicit larger-page qualification on a synthetic fixture guest",
            InstrumentationRegistry.getArguments().getString("ocrLargePage") == "1")
        val settings = app.atelierPreferences.settings.value
        assertEquals(listOf("eng", "fra"), settings.ocrLanguages.map { it.packCode })
        assertEquals(OcrLayout.AUTOMATIC, settings.ocrLayout)
        assertFalse(settings.ocrDiagnostics)
        val graph = app.services.imports()
        val work = graph.store.savedOcr
        val title = "Larger OCR control ${UUID.randomUUID()}"
        val document = graph.store.documents.createDocument(title)
        val dir = File(context.cacheDir, "editor-journey-fixtures")
        check(dir.isDirectory || dir.mkdirs())
        val id = UUID.randomUUID().toString()
        makeDensePage(File(dir, "$id.png"))
        val actions = ApplicationLibraryActions(context, app.services::imports)
        val request = actions.begin(graph.store.catalog.load(document).document)
        val pageId = (actions.deliver(request, LibraryPhotoResult.Selected(
            listOf("content://${context.packageName}.editor-fixtures/$id"), 0,
        )).single() as IngestResult.Committed).pageId
        val saved = graph.store.editablePages.load(document, pageId)
        val revision = saved.recipeRevision
        assertNull(work.readStatus(document, pageId, revision))
        assertFalse(work.find("ELDERFLOWER", null, 20).hits.any { it.document == document })

        coroutineScope {
            // Subscribe before the first ordinary Activity starts backfill; querying only after READY has lost the pending hint.
            val target = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(35_000) { work.observePendingTarget(document, pageId, revision).filterNotNull().first() }
            }
            val auditLog = InstrumentationRegistry.getArguments().getString("ocrLargePageLogcat") == "1"
            val logMark = "ocr-window-${UUID.randomUUID()}"
            if (auditLog) Log.i("PristineOcrAudit", "$logMark/start")
            val startedAt = SystemClock.elapsedRealtime()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val admitted = target.await()
                assertEquals(pageId, admitted.page)
                val manager = WorkManager.getInstance(context)
                val name = OcrPageDelivery.uniqueName(admitted)
                var lastSample: String? = null
                val ready = withTimeout(125_000) {
                    waitFor {
                        val wakes = withContext(Dispatchers.IO) {
                            manager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS)
                        }
                        val status = work.readStatus(document, pageId, revision)
                        val observed = wakes.map { "${it.state}:${it.runAttemptCount}" }.sorted().joinToString(",")
                        val sample = "phase=${status?.phase};starts=${status?.attemptsStarted};error=${status?.lastError};wakes=$observed"
                        if (sample != lastSample) {
                            instrumentation.sendStatus(66, Bundle().apply { putString("ocrLargePageSample", sample) })
                            lastSample = sample
                        }
                        assertTrue("The exact synthetic page stopped before READY: $sample",
                            status?.phase !in setOf(SavedOcrWork.PagePhase.FAILED, SavedOcrWork.PagePhase.CANCELED) &&
                                !(wakes.isNotEmpty() && wakes.all { it.state == WorkInfo.State.FAILED }))
                        status?.takeIf { it.phase == SavedOcrWork.PagePhase.READY &&
                            wakes.any { wake -> wake.state == WorkInfo.State.SUCCEEDED } }
                    }
                }
                assertEquals(1, ready.attemptsStarted)
                assertEquals("tesseract-android", ready.configuration.engineId)
                assertEquals(OcrLayout.AUTOMATIC, ready.configuration.layout)
                assertEquals(listOf("eng", "fra"), ready.configuration.languagePacks?.ordered?.map { it.code })
                assertNull(ready.lastError)
                val readyMs = SystemClock.elapsedRealtime() - startedAt
                val text = checkNotNull(work.readText(document, pageId, revision))
                assertFalse(text.previousGeneration)
                assertTrue("Header anchor was not read", text.text.value.contains("IRONWOOD", ignoreCase = true))
                assertTrue("Footer anchor was not read", text.text.value.contains("ELDERFLOWER", ignoreCase = true))
                val label = context.getString(R.string.home_matching_page, title, 1)
                lateinit var library: ImportLibraryViewModel
                scenario.onActivity { activity -> library = ViewModelProvider(activity)[ImportLibraryViewModel::class.java] }
                compose.onNodeWithTag("home-album-search").performTextInput("elderflower")
                compose.waitUntil(15_000) { library.session.state.value.pageMatches.any { it.document == document && it.page == pageId } }
                compose.onNodeWithTag("atelier-album-list").performScrollToNode(hasText(label))
                compose.onNodeWithText(label).performClick()
                lateinit var editor: EditorViewModel
                scenario.onActivity { activity -> editor = ViewModelProvider(activity)[EditorViewModel::class.java] }
                compose.waitUntil(15_000) { editor.session.value?.state?.value?.page?.let {
                    it.pageId == pageId && it.recipeRevision == revision
                } == true }
                instrumentation.addResults(Bundle().apply {
                    putString("ocrLargePageWitness", "EXACT_DENSE_PAGE_WAKE_AND_REOPEN")
                    putLong("ocrLargePageReadyMs", readyMs)
                    if (auditLog) {
                        Log.i("PristineOcrAudit", "$logMark/end")
                        putString("ocrLargePageLogcat", sampleOcrLogcat(logMark))
                    }
                })
            }
        }
    }

    /** Capture only this synthetic app process between positive log markers. Retain counts, never raw paths or OCR strings. */
    private fun sampleOcrLogcat(mark: String): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val descriptor = automation.executeShellCommand("logcat -b main -b system -b crash -d --pid=${Process.myPid()} -t 512 -v brief")
        var inWindow = false
        var starts = 0
        var ends = 0
        var lines = 0
        var nativeTagged = 0
        var contentSuspect = 0
        var packPathSuspect = 0
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().useLines { log ->
            for (line in log) {
                when {
                    line.startsWith("I/PristineOcrAudit") && line.endsWith("$mark/start") -> {
                        starts++
                        inWindow = true
                    }
                    line.startsWith("I/PristineOcrAudit") && line.endsWith("$mark/end") -> {
                        ends++
                        inWindow = false
                    }
                    inWindow -> {
                        lines++
                        if (line.contains("tesseract", ignoreCase = true) || line.contains("leptonica", ignoreCase = true)) nativeTagged++
                        if (line.contains("IRONWOOD", ignoreCase = true) || line.contains("ELDERFLOWER", ignoreCase = true)) contentSuspect++
                        if (line.contains("ocr-packs-", ignoreCase = true) || line.contains(".traineddata", ignoreCase = true)) packPathSuspect++
                    }
                }
            }
        }
        assertTrue("Scoped logcat capture did not retain both ordered control markers", starts == 1 && ends == 1 && !inWindow)
        return "lines=$lines;nativeMentions=$nativeTagged;controlMentions=$contentSuspect;packMentions=$packPathSuspect"
    }

    private fun makeDensePage(file: File) {
        check(file.createNewFile())
        val bitmap = Bitmap.createBitmap(1_600, 2_200, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 28f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            }
            canvas.drawText("IRONWOOD   Office supplies inventory", 42f, 60f, paint)
            for (line in 1..52) {
                canvas.drawText("Entry $line. Document supplies, archive shelves, and stock recorded for inspection.", 42f, 114f + 38f * line, paint)
            }
            canvas.drawText("ELDERFLOWER   End of inventory", 42f, 2_160f, paint)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }
}

private suspend fun <T : Any> waitFor(read: suspend () -> T?): T {
    while (true) {
        read()?.let { return it }
        delay(100)
    }
}

private fun makePage(file: File, word: String, reference: String? = null) {
    check(file.createNewFile())
    val bitmap = Bitmap.createBitmap(1024, 480, Bitmap.Config.ARGB_8888)
    try {
        bitmap.eraseColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 76f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val canvas = Canvas(bitmap)
        canvas.drawText("Receipt $word", 50f, 220f, paint)
        reference?.let { canvas.drawText("Reference $it", 50f, 336f, paint) }
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    } finally {
        bitmap.recycle()
    }
}
