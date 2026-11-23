package app.pristinepage.ocr

import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.MainActivity
import app.pristinepage.PristinePageApplication
import app.pristinepage.R
import app.pristinepage.capture.AtelierCaptureViewModel
import app.pristinepage.core.recipe.AppearanceRecipe
import app.pristinepage.data.model.EditablePage
import app.pristinepage.data.repository.SavedOcrWork
import app.pristinepage.editor.EditorViewModel
import app.pristinepage.library.ImportLibrarySession
import app.pristinepage.library.ImportLibraryViewModel
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.WRITE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run from the guest shell after positive radio isolation. No host-side UI calls or injected picker results. */
@RunWith(AndroidJUnit4::class)
class LocalOcrAirplaneFilesTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    private fun fixtureToken(): String {
        val token = InstrumentationRegistry.getArguments().getString("offlineOcrFixture")
        assumeTrue("Requires a staged fixture and reversible guest isolation", token != null)
        return checkNotNull(token).also { require(it.matches(Regex("[a-f0-9]{12}"))) }
    }

    @Test fun coldFilesImportCorrectsRecognizesAndReopensTheExactPageInAirplaneMode(): Unit = runBlocking {
        val fixture = fixtureToken()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val library = scenario.library()
            val scan = importScan(scenario, library, fixture)
            awaitAutomaticOcrAndOpen(scenario, library, scan)
        }
        report("EXACT_COLD_FILES_PAGE_IN_AIRPLANE_MODE")
    }

    @Test fun filesStillSavesAndThenAutomaticallyRecoversAfterPackRefusal(): Unit = runBlocking {
        val fixture = fixtureToken()
        val app = instrumentation.targetContext.applicationContext as PristinePageApplication
        // Claim only the existing catalog's ordinary coordination lock. Never remove, rewrite or replace a pack or its owner.
        assertFalse(app.ocrRuntimeReady.value)
        val parent = app.filesDir.resolve("ocr-verified-packs-v1")
        val guard = checkNotNull(parent.listFiles()).single {
            it.isFile && it.name.matches(Regex("\\.ocr-packs-[a-f0-9]{64}\\.lock"))
        }
        FileChannel.open(guard.toPath(), WRITE).use { channel ->
            val claim = checkNotNull(channel.tryLock())
            claim.use {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    val library = scenario.library()
                    val scan = importScan(scenario, library, fixture)
                    val store = app.services.imports().store
                    // A real availability check must refuse while the lock is held, without reopening or changing the accepted page.
                    assertNull(app.savedOcr.reconciliation())
                    assertFalse(app.ocrRuntimeReady.value)
                    assertNull(store.savedOcr.readStatus(scan.page.documentId, scan.page.pageId, scan.page.recipeRevision))
                    tap("atelier-home-back")
                    compose.onNodeWithTag("home-album-search").performTextInput(scan.title)
                    compose.waitUntil(15_000) { library.state.value.let { state ->
                        !state.loading && state.documents.any { it.documentId == scan.page.documentId }
                    } }
                    tap("atelier-album-${scan.page.documentId.value}")
                    awaitTag("document-overview")
                    tap("original-page-${scan.page.pageId.value}")
                    awaitExactEditor(scenario, scan)
                    verifyIsolation("after reopening while OCR is unavailable")
                    assertNull(store.savedOcr.readStatus(scan.page.documentId, scan.page.pageId, scan.page.recipeRevision))

                    scenario.moveToState(Lifecycle.State.CREATED)
                    awaitPreReleaseWakes(app.savedOcrSignals)
                    assertNull(store.savedOcr.readStatus(scan.page.documentId, scan.page.pageId, scan.page.recipeRevision))
                    claim.release()
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    tap("editor-back")
                    awaitAutomaticOcrAndOpen(scenario, library, scan)
                    assertEquals(scan.page, store.editablePages.load(scan.page.documentId, scan.page.pageId))
                }
            }
        }
        report("EXACT_FILES_SAVE_AND_AUTOMATIC_OCR_RECOVERY")
    }

    private suspend fun awaitPreReleaseWakes(signals: SavedOcrAppSignals) {
        // Inspect the real debug process scope without adding a test bypass to the app. The Activity is stopped and cannot issue a further foreground signal yet.
        val scope = SavedOcrAppSignals::class.java.getDeclaredField("processScope").let {
            it.isAccessible = true
            it.get(signals) as CoroutineScope
        }
        val owner = checkNotNull(scope.coroutineContext[Job])
        withTimeout(10_000) {
            do {
                val remaining = owner.children.toList()
                remaining.joinAll()
            } while (remaining.isNotEmpty())
        }
    }

    private data class SavedScan(val title: String, val page: EditablePage)

    private fun ActivityScenario<MainActivity>.library(): ImportLibrarySession {
        lateinit var session: ImportLibrarySession
        onActivity { session = ViewModelProvider(it)[ImportLibraryViewModel::class.java].session }
        return session
    }

    private suspend fun importScan(
        scenario: ActivityScenario<MainActivity>,
        library: ImportLibrarySession,
        fixture: String,
    ): SavedScan {
        val app = instrumentation.targetContext.applicationContext as PristinePageApplication
        verifyIsolation("cold activity")
        lateinit var acquisition: AtelierCaptureViewModel
        scenario.onActivity { acquisition = ViewModelProvider(it)[AtelierCaptureViewModel::class.java] }
        assertNull(library.state.value.selectedId)
        compose.waitUntil(15_000) { !library.state.value.loading }
        tap("atelier-new")
        compose.waitUntil(15_000) { acquisition.state.value.let { it.draft != null && !it.busy } }
        check(!acquisition.state.value.resumeChoice) { "Preserve the existing draft; its ownership has not been verified for this test" }
        awaitTag("atelier-camera-import")
        tap("atelier-camera-import")
        tap("atelier-import-files")
        awaitFiles()
        selectLocalFixture(fixture)
        compose.waitUntil(25_000) { acquisition.state.value.draft?.pages?.size == 1 && !acquisition.state.value.busy }
        verifyIsolation("after system Files")
        val staged = checkNotNull(acquisition.state.value.draft).pages.single()
        val graph = app.services.imports()
        assertEquals(ENGLISH_CONTROL, graph.store.committedSources.load(staged.sourceId).sourceByteHash)
        tap("atelier-review-import")
        tap("atelier-finish-import")
        compose.waitUntil(15_000) { acquisition.state.value.editor?.state?.value?.loading == false }
        tap("atelier-wizard-skip-frame")
        tap("atelier-wizard-confirm-skip")
        awaitWizard(acquisition, 1)
        tap("atelier-wizard-next")
        awaitWizard(acquisition, 2)
        compose.onNodeWithTag("editor-look-natural").performScrollTo().assertIsEnabled().performClick()
        tap("atelier-wizard-next")
        compose.waitUntil(30_000) { !acquisition.state.value.open && !library.state.value.loading &&
            library.state.value.document?.pages?.singleOrNull()?.pageId == staged.pageId }
        verifyIsolation("after save")
        val album = checkNotNull(library.state.value.document).document
        val page = graph.store.editablePages.load(album.documentId, staged.pageId)
        assertTrue(page.recipe.appearance is AppearanceRecipe.NaturalLV1)
        assertEquals(staged.sourceId, page.sourceId)
        return SavedScan(album.title, page)
    }

    private suspend fun awaitAutomaticOcrAndOpen(
        scenario: ActivityScenario<MainActivity>,
        library: ImportLibrarySession,
        scan: SavedScan,
    ) {
        val context = instrumentation.targetContext
        val app = context.applicationContext as PristinePageApplication
        val work = app.services.imports().store.savedOcr
        val page = scan.page
        val ready = withTimeout(90_000) {
            eventually { work.readStatus(page.documentId, page.pageId, page.recipeRevision)
                ?.takeIf { it.phase == SavedOcrWork.PagePhase.READY } }
        }
        assertEquals(1, ready.attemptsStarted)
        assertNull(ready.lastError)
        assertEquals("tesseract-android", ready.configuration.engineId)
        val text = checkNotNull(work.readText(page.documentId, page.pageId, page.recipeRevision))
        assertFalse(text.previousGeneration)
        assertTrue(text.text.value.contains("customer paid", ignoreCase = true))
        verifyIsolation("after recognition")
        tap("atelier-home-back")
        awaitTag("home-album-search")
        val label = context.getString(R.string.home_matching_page, scan.title, 1)
        compose.onNodeWithTag("home-album-search").performTextReplacement("customer")
        compose.waitUntil(15_000) { library.state.value.let { state -> !state.loading &&
            state.pageMatches.any { it.document == page.documentId && it.page == page.pageId } } }
        compose.onNodeWithTag("atelier-album-list").performScrollToNode(hasText(label))
        compose.onNodeWithText(label).assertIsEnabled().performClick()
        awaitExactEditor(scenario, scan)
        verifyIsolation("after exact reopen")
    }

    private fun awaitExactEditor(scenario: ActivityScenario<MainActivity>, scan: SavedScan) {
        lateinit var editor: EditorViewModel
        scenario.onActivity { editor = ViewModelProvider(it)[EditorViewModel::class.java] }
        compose.waitUntil(15_000) { editor.session.value?.state?.value?.page?.let {
            it.documentId == scan.page.documentId && it.pageId == scan.page.pageId && it.recipeRevision == scan.page.recipeRevision
        } == true }
    }

    private fun report(marker: String) = instrumentation.addResults(Bundle().apply { putString("offlineOcrWitness", marker) })

    private fun tap(tag: String) = compose.onNodeWithTag(tag).assertIsEnabled().performClick()
    private fun awaitTag(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
    private fun awaitWizard(acquisition: AtelierCaptureViewModel, step: Int) {
        compose.waitUntil(20_000) { acquisition.state.value.let { it.draft?.step == step && it.editor?.state?.value?.loading == false } }
    }

    private fun verifyIsolation(stage: String) {
        val read = listOf("airplane_mode_on", "wifi_on", "mobile_data").map { shell("settings get global $it").trim() }
        val wifi = shell("dumpsys wifi").lineSequence().map(String::trim).filter { it.startsWith("Wi-Fi is ") }.toList()
        val groups = shell("dumpsys connectivity").lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val noNetwork = groups.filter { it.startsWith("Active default network:") } == listOf("Active default network: none") &&
            groups.count { it == "Current Networks:" } == 1 && groups.count { it == "Network Requests:" } == 1 &&
            groups.indexOf("Network Requests:") == groups.indexOf("Current Networks:") + 1
        assertTrue("Android radio isolation was not proven at $stage", read == listOf("1", "0", "0") &&
            wifi == listOf("Wi-Fi is disabled") && noNetwork)
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }

    private fun awaitFiles() = compose.waitUntil(15_000) { automation.rootInActiveWindow?.packageName?.toString() in FILES }

    private suspend fun selectLocalFixture(fixture: String) {
        val filename = "pristinepage-offline-$fixture.png"
        val folder = "PristinePage-Offline-$fixture"
        val drawer = setOf("Show roots", "Afficher les répertoires racines")
        // Never use Recents across providers. Traverse only the fresh, randomly named staged directory.
        select(PickerAction("root drawer", drawer, null))
        select(PickerAction("Downloads root", setOf("Downloads", "Téléchargements"), "roots_list"))
        select(PickerAction("synthetic folder", setOf(folder), "dir_list"))
        awaitFilesRow(PickerAction("synthetic breadcrumb", setOf(folder), "dropdown_breadcrumb"))
        select(PickerAction("synthetic image", setOf(filename), "dir_list"))
    }

    private data class PickerAction(val step: String, val labels: Set<String>, val within: String?)

    private suspend fun awaitFilesRow(action: PickerAction, actionable: Boolean = false): AccessibilityNodeInfo = try {
        withTimeout(15_000) {
            eventually {
                val first = findFilesRow(action.labels, action.within) ?: return@eventually null
                if (!actionable) return@eventually first
                // The provider can replace a row while its list renders. Reobserve before dispatch; never retry the click itself.
                val current = findFilesRow(action.labels, action.within) ?: return@eventually null
                current.takeIf {
                    it == first && automation.rootInActiveWindow?.windowId == it.windowId &&
                        it.refresh() && it.isEnabled && it.isVisibleToUser
                }
            }
        }
    } catch (timeout: TimeoutCancellationException) {
        throw AssertionError("Android Files did not expose the expected ${action.step} control", timeout)
    }

    private suspend fun select(action: PickerAction) {
        click(awaitFilesRow(action, actionable = true), action.within == "dir_list")
    }

    private fun findFilesRow(labels: Set<String>, within: String?): AccessibilityNodeInfo? {
        val root = automation.rootInActiveWindow ?: return null
        val owner = root.packageName?.toString()
        check(owner in FILES) { "Unexpected active Android window during Files selection" }
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        val matches = linkedSetOf<AccessibilityNodeInfo>()
        var visited = 0
        while (queue.isNotEmpty()) {
            check(++visited <= 1_000)
            val node = queue.removeFirst()
            repeat(node.childCount) { node.getChild(it)?.let(queue::addLast) }
            if (!node.isVisibleToUser || !node.isEnabled || node.packageName?.toString() != owner ||
                (node.text?.toString() !in labels && node.contentDescription?.toString() !in labels)) continue
            var candidate = node
            if (within == "dir_list") {
                if (node.viewIdResourceName != "android:id/title") continue
                while (candidate.parent?.viewIdResourceName?.substringAfterLast('/') != within) {
                    candidate = candidate.parent ?: break
                    if (candidate.packageName?.toString() != owner) break
                }
                if (candidate.parent?.viewIdResourceName?.substringAfterLast('/') != within || !candidate.isFocusable) continue
            } else {
                if (within != null && generateSequence(node) { it.parent }.none { it.viewIdResourceName?.substringAfterLast('/') == within }) continue
                while (!candidate.isClickable && candidate.parent?.packageName?.toString() == owner) candidate = checkNotNull(candidate.parent)
                if (within != "dropdown_breadcrumb" && !candidate.isClickable) continue
            }
            if (candidate.isEnabled && candidate.isVisibleToUser) matches += candidate
        }
        check(matches.size <= 1) { "Ambiguous Files control" }
        return matches.singleOrNull()
    }

    private fun click(node: AccessibilityNodeInfo, document: Boolean) {
        if (node.isClickable) {
            check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            return
        }
        check(document && node.isFocusable && node.parent?.viewIdResourceName?.substringAfterLast('/') == "dir_list")
        // API 28 delegates these document rows to the grid rather than exposing ACTION_CLICK.
        val bounds = Rect().also(node::getBoundsInScreen)
        check(!bounds.isEmpty)
        val time = SystemClock.uptimeMillis()
        for (eventType in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), eventType, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                check(automation.injectInputEvent(event, true))
            } finally { event.recycle() }
        }
    }

    private suspend fun <T : Any> eventually(block: suspend () -> T?): T {
        while (true) {
            block()?.let { return it }
            delay(200)
        }
    }

    private companion object {
        val FILES = setOf("com.android.documentsui", "com.google.android.documentsui")
        const val ENGLISH_CONTROL = "f4585967198db8a51a7d8afae50e449bdead2ad3b4122d1478afffc5fbd5608f"
    }
}
