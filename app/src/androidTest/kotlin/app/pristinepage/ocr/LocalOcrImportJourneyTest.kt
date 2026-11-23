package app.pristinepage.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.pristinepage.MainActivity
import app.pristinepage.PristinePageApplication
import app.pristinepage.R
import app.pristinepage.core.recipe.AppearanceRecipe
import app.pristinepage.data.model.IngestResult
import app.pristinepage.data.repository.SavedOcrWork
import app.pristinepage.editor.EditorViewModel
import app.pristinepage.library.ApplicationLibraryActions
import app.pristinepage.library.ImportLibraryViewModel
import app.pristinepage.library.LibraryPhotoResult
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals(saved[1].pageId, editor.session.value?.state?.value?.page?.pageId)
    }

    @Test
    fun coldHomeSearchReopensTheSavedFilesPageAndCreatesJpeg(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as PristinePageApplication
        val title = InstrumentationRegistry.getArguments().getString("filesSavedAlbum")
        assumeTrue("Requires the exact album from the separate real Files picker and correction run", app.hasBundledOcr && title != null)
        val name = checkNotNull(title)
        val graph = app.services.imports()
        val document = graph.store.catalog.list(null, 50).single { it.title == name }
        val page = graph.store.catalog.load(document.documentId).pages.single()
        val saved = graph.store.editablePages.load(document.documentId, page.pageId)
        val folder = InstrumentationRegistry.getArguments().getString("filesSaveAsFolder")
        if (folder != null) {
            require(folder.matches(Regex("PristinePage-Export-[a-f0-9]{12}")))
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
        if (InstrumentationRegistry.getArguments().getString("filesSearchOnly") == "1") {
            require(folder == null)
            instrumentation.addResults(Bundle().apply { putString("filesWitness", "EXACT_PAGE_SEARCH") })
            return@runBlocking
        }
        if (folder == null) {
            val ocrTool = compose.onNodeWithTag("editor-tool-ocr-text").performScrollTo().assertIsEnabled()
            // Invoke the real accessible action on the horizontal ribbon: a touch can be clipped or obscured independently of the dialog.
            ocrTool.performSemanticsAction(SemanticsActions.OnClick) { action -> check(action()) }
            fun observed(label: Int) = compose.onAllNodesWithText(context.getString(label)).fetchSemanticsNodes().size
            try {
                compose.waitUntil(15_000) { compose.onAllNodesWithText(text.text.value).fetchSemanticsNodes().size == 1 }
            } catch (miss: androidx.compose.ui.test.ComposeTimeoutException) {
                throw AssertionError("Saved OCR panel: close=${observed(R.string.atelier_close)}, loading=${observed(R.string.ocr_details_loading)}, unavailable=${observed(R.string.ocr_details_unavailable)}, noText=${observed(R.string.ocr_text_not_available)}", miss)
            }
            compose.onNodeWithText(context.getString(R.string.atelier_close)).performClick()
        }
        compose.onNodeWithTag("editor-back").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("album-export-all").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("album-export-all").performClick()
        compose.onNodeWithText("${name}_1.jpg").assertExists()
        compose.onNodeWithTag("album-export-generate").assertIsEnabled().performClick()
        compose.waitUntil(45_000) { compose.onAllNodesWithTag("album-export-save-as").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("album-export-save-as").assertIsEnabled()
        if (folder == null) return@runBlocking
        check(InstrumentationRegistry.getArguments().getString("filesInspectPicker") == "1")
        compose.onNodeWithTag("album-export-save-as").performClick()
        inspectLocalFiles(folder, "${name}_1.jpg")
        instrumentation.addResults(Bundle().apply { putString("filesWitness", "PICKER_INSPECTED_WITHOUT_CONFIRMATION") })
    }

    private suspend fun inspectLocalFiles(folder: String, filename: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val files = setOf("com.android.documentsui", "com.google.android.documentsui")
        fun descendants(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                check(depth <= 30 && size < 1_500) { "Unexpected synthetic Files hierarchy extent" }
                add(node)
                for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it, depth + 1) }
            }
            visit(root, 0)
        }
        suspend fun locate(step: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
            var latestPackage = "none"
            var resourceIds = emptyList<String>()
            try {
                return withTimeout(15_000) {
                    waitFor {
                        val root = automation.rootInActiveWindow ?: return@waitFor null
                        val owner = root.packageName?.toString()
                        latestPackage = owner?.takeIf { it in files } ?: "other"
                        if (owner !in files) return@waitFor null
                        val nodes = descendants(root).filter { it.isVisibleToUser && it.packageName?.toString() in files }
                        resourceIds = nodes.mapNotNull { it.viewIdResourceName?.substringAfterLast('/') }.distinct().sorted().take(40)
                        nodes.filter(predicate).also { check(it.size <= 1) { "Ambiguous system Files control for $step" } }.singleOrNull()
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                throw AssertionError("System Files never showed $step (owner=$latestPackage; controls=$resourceIds)", timeout)
            }
        }
        fun AccessibilityNodeInfo.labelIn(vararg labels: String) =
            text?.toString() in labels || contentDescription?.toString() in labels
        fun AccessibilityNodeInfo.inRow(listId: String): Boolean {
            var node = parent
            while (node != null && node.packageName?.toString() in files) {
                if (node.viewIdResourceName?.substringAfterLast('/') == listId) return true
                node = node.parent
            }
            return false
        }
        suspend fun tap(step: String, predicate: (AccessibilityNodeInfo) -> Boolean) {
            var node = locate(step, predicate)
            while (!node.isClickable && node.parent?.packageName?.toString() in files) node = checkNotNull(node.parent)
            check(node.isClickable && node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                "The exact system Files control did not accept the action"
            }
        }
        tap("the root drawer") { it.labelIn("Show roots", "Afficher les répertoires racines") }
        tap("the local Downloads root") { it.labelIn("Downloads", "Téléchargements") && it.inRow("roots_list") }
        val title = locate("the new synthetic directory") {
            it.text?.toString() == folder && it.viewIdResourceName == "android:id/title" && it.inRow("dir_list")
        }
        var row = checkNotNull(title.parent)
        while (row.parent?.viewIdResourceName?.substringAfterLast('/') != "dir_list") {
            row = checkNotNull(row.parent) { "The destination is not an actual local Files directory row" }
            check(row.packageName?.toString() in files)
        }
        check(row.isEnabled && row.isFocusable)
        val bounds = Rect().also { row.getBoundsInScreen(it) }
        check(!bounds.isEmpty)
        // API 28 DocumentsUI delegates this row to its parent and can omit ACTION_CLICK.
        val timestamp = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(timestamp, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                check(automation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        // The folder row must be behind us, and the folder must be selected as the current breadcrumb before any write.
        locate("the selected directory breadcrumb") { it.text?.toString() == folder && it.inRow("dropdown_breadcrumb") }
        locate("the exact export name field") { it.isEditable && it.text?.toString() == filename }
        locate("the Save confirmation") {
            it.labelIn("Save", "Enregistrer") && it.viewIdResourceName?.substringAfterLast('/') == "button_save"
        }
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
        assumeTrue("Only run with the private OCR-enabled application candidate", app.hasBundledOcr)
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
