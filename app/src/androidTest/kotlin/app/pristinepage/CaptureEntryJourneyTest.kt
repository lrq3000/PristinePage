package app.pristinepage

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.capture.AtelierCaptureViewModel
import app.pristinepage.capture.AtelierCaptureScreen
import app.pristinepage.capture.PhotoImportKind
import app.pristinepage.capture.PhotoPickerContract
import app.pristinepage.core.pipeline.OutputSpec
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.RenderPurpose
import app.pristinepage.library.ImportLibraryViewModel
import app.pristinepage.testing.SyntheticDocumentsProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run only on a dedicated synthetic-camera guest. These journeys never resume or discard an existing draft. */
@RunWith(AndroidJUnit4::class)
class CaptureEntryJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as PristinePageApplication

    @Test fun galleryAndFilesBothOpenFromAnEmptyScan() = runBlocking<Unit> {
        val acquisition = openNewScan()
        for ((kind, tag) in listOf(PhotoImportKind.GALLERY to "atelier-import-gallery", PhotoImportKind.FILES to "atelier-import-files")) {
            val intent = PhotoPickerContract(kind).createIntent(context, Unit)
            @Suppress("DEPRECATION")
            val expected = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            assertNotNull("No handler for $kind", expected)
            tap("atelier-camera-import")
            tap(tag)
            val automation = instrumentation.uiAutomation
            compose.waitUntil(15_000) { automation.rootInActiveWindow?.packageName?.toString() == expected }
            assertEquals(kind, acquisition.state.value.request?.kind)
            assertEquals(expected, automation.rootInActiveWindow?.packageName?.toString())
            assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            compose.waitUntil(15_000) { acquisition.state.value.let { !it.busy && it.request == null && it.draft?.pending == null } }
            assertEquals(0, acquisition.state.value.draft?.pages?.size)
            compose.onNodeWithTag("atelier-camera-import").assertIsEnabled()
        }
        compose.runOnUiThread { acquisition.leave(true) }
        compose.waitUntil(10_000) { !acquisition.state.value.open }
    }

    @Test fun cameraFreeGalleryAndFilesShowTheDisclosureAndReturnToTheOwnedDraft() = runBlocking<Unit> {
        withCameraFreeDraft(realProvider = false) { _, fixture, owner ->
            for ((kind, tag) in listOf(PhotoImportKind.GALLERY to "atelier-import-gallery", PhotoImportKind.FILES to "atelier-import-files")) {
                val picker = openPicker(kind, tag, owner)
                if (kind == PhotoImportKind.GALLERY) {
                    val navigation = setOf("Photos", "Albums", "Browse", "On this device", "Collections")
                    instrumentation.addResults(Bundle().apply {
                        putString("galleryHandler", picker)
                        putString("galleryNavigation", pickerNodes(picker).mapNotNull { node ->
                            node.text?.toString()?.takeIf { node.isVisibleToUser && it in navigation }
                        }.distinct().joinToString(","))
                    })
                }
                assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                compose.waitUntil(15_000) { owner.state.value.let { !it.busy && it.request == null && it.draft?.pending == null } }
                assertEquals(0, checkNotNull(owner.state.value.draft).pages.size)
                assertEquals(0, fixture.providerOpens)
            }
            compose.runOnUiThread { owner.leave(true) } // Only this invocation's isolated empty draft.
            compose.waitUntil(10_000) { !owner.state.value.open }
        }
    }

    @Test fun actualFilesSelectionCommitsAndReopensTheCompleteSyntheticHdrSource() = runBlocking<Unit> {
        withCameraFreeDraft(realProvider = true) { graph, _, owner ->
            val picker = openPicker(PhotoImportKind.FILES, "atelier-import-files", owner)
            val name = SyntheticDocumentsProvider.HDR_NAME
            if (findPickerControl(picker, setOf(name)) == null) {
                activatePickerControl(picker, setOf("Show roots", "Afficher les répertoires racines"))
                activatePickerControl(picker, setOf("PristinePage QA"))
            }
            val switchToList = pickerNodes(picker).singleOrNull {
                it.isEnabled && it.isVisibleToUser && it.isClickable && it.viewIdResourceName?.endsWith("/option_menu_list") == true
            }
            if (switchToList != null) check(switchToList.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            activatePickerControl(picker, setOf(name))
            val confirm = setOf("Open", "Select", "Ouvrir", "Sélectionner")
            try {
                compose.waitUntil(7_000) {
                    val active = instrumentation.uiAutomation.rootInActiveWindow
                    active?.packageName?.toString()?.let { it != picker || findPickerControl(picker, confirm) != null } == true
                }
            } catch (miss: ComposeTimeoutException) {
                val safeFixtureNames = setOf(name, "PristinePage QA", "Original A.png", "Original B.png", "Corrupt image.png")
                val active = pickerNodes(picker).filter { it.isVisibleToUser }.map { node ->
                    val id = node.viewIdResourceName?.substringAfterLast('/')
                    val label = node.text?.toString()?.takeIf { it in safeFixtureNames }
                    "$id:$label:${node.className}:${node.isSelected}:${node.isClickable}"
                }.distinct()
                throw AssertionError("Picker remained open after fixture tap: $active", miss)
            }
            if (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == picker) {
                val picked = findPickerControl(picker, setOf(name))
                check(picked != null) { "The synthetic row is no longer visible; do not confirm a selection" }
                val selected = generateSequence(picked) { it.parent }.any { it.isSelected || it.isChecked }
                    || pickerNodes(picker).any { it.text?.toString() in setOf("1 selected", "1 sélectionné") }
                check(selected) { "No evidence of the synthetic selection; do not confirm" }
                activatePickerControl(picker, confirm)
            }
            try {
                compose.waitUntil(15_000) {
                    owner.state.value.let { !it.busy && it.request == null &&
                        (!it.draft?.pages.isNullOrEmpty() || it.importIssues.isNotEmpty() || it.error != null) }
                }
            } catch (miss: ComposeTimeoutException) {
                val state = owner.state.value
                val active = instrumentation.uiAutomation.rootInActiveWindow
                throw AssertionError("No HDR result: pickerVisible=${active?.packageName == picker}, request=${state.request?.kind}, " +
                    "busy=${state.busy}, pending=${state.draft?.pending != null}, pages=${state.draft?.pages?.size}", miss)
            }
            assertTrue("HDR import issues: ${owner.state.value.importIssues.map { it.error?.safeMessageKey }}, " +
                "owner error: ${owner.state.value.error?.safeMessageKey}", owner.state.value.importIssues.isEmpty() && owner.state.value.error == null)
            val page = checkNotNull(owner.state.value.draft).pages.single()
            tap("atelier-review-import")
            tap("atelier-finish-import")
            awaitWizard(owner, 0)
            tap("atelier-wizard-skip-frame")
            tap("atelier-wizard-confirm-skip")
            awaitWizard(owner, 1)
            tap("atelier-wizard-next")
            awaitWizard(owner, 2)
            tap("atelier-wizard-next")
            compose.waitUntil(20_000) { owner.state.value.saved != null }
            val album = checkNotNull(owner.state.value.saved)
            assertEquals(page.sourceId, graph.store.editablePages.load(album, page.pageId).sourceId)
            val saved = graph.store.committedSources.load(page.sourceId)
            assertArrayEquals(SyntheticDocumentsProvider.generateHdrJpeg(), saved.file.readBytes())
            graph.decoder.decode(page.sourceId.value, OutputSpec(PixelSize(48, 32), RenderPurpose.PREVIEW)).use { decoded ->
                assertEquals(PixelSize(48, 32), decoded.inspection.normalizedSize)
                assertTrue(decoded.inspection.conversionFingerprint.endsWith("/jpeg-ultrahdr-sdr-v1"))
            }
        }
    }

    private suspend fun withCameraFreeDraft(realProvider: Boolean, checkDraft: suspend
        (ApplicationImports, DocumentCatalogIntegrationTest.Fixture, AtelierCaptureViewModel) -> Unit) {
        assertEquals("Do not activate an unverified camera on the retained guest", PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(Manifest.permission.CAMERA))
        DocumentCatalogIntegrationTest.Fixture(realProvider = realProvider).use { fixture ->
            val graph = fixture.open()
            val models = ViewModelStore()
            val owner = compose.runOnUiThread {
                val factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                         AtelierCaptureViewModel(context, { graph }) { _, _, _ -> check(realProvider) { "Cancel must not save pages" } } as T
                }
                ViewModelProvider(models, factory)[AtelierCaptureViewModel::class.java].also { acquisition ->
                    val activity = compose.activity
                    activity.setContent {
                        MaterialTheme {
                            val state by acquisition.state.collectAsState()
                            AtelierCaptureScreen(state, acquisition, activity.activityResultRegistry, false, activity.window,
                                { graph.originalPreview(it) }, { graph.originalPreview(it, 224) })
                        }
                    }
                    acquisition.open("Synthetic picker entry", null)
                }
            }
            try {
                compose.waitUntil(15_000) { owner.state.value.let { it.open && !it.busy && it.draft != null } }
                checkDraft(graph, fixture, owner)
            } finally {
                compose.runOnUiThread { compose.activity.setContent {}; models.clear() }
            }
        }
    }

    private fun openPicker(kind: PhotoImportKind, tag: String, owner: AtelierCaptureViewModel): String {
        val handler = checkNotNull(PhotoPickerContract(kind).createIntent(context, Unit).resolveActivity(context.packageManager)).packageName
        tap("atelier-camera-import")
        compose.onNodeWithText(context.getString(R.string.atelier_import_sdr_note)).assertIsDisplayed()
        tap(tag)
        compose.waitUntil(15_000) { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == handler }
        assertEquals(kind, owner.state.value.request?.kind)
        return handler
    }

    private fun pickerNodes(picker: String): List<AccessibilityNodeInfo> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        check(root.packageName?.toString() == picker) { "Unexpected picker window" }
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        return buildList {
            while (queue.isNotEmpty()) {
                check(size < 1_000)
                val node = queue.removeFirst()
                repeat(node.childCount) { node.getChild(it)?.let(queue::addLast) }
                add(node)
            }
        }
    }

    private fun findPickerControl(picker: String, labels: Set<String>): AccessibilityNodeInfo? {
        val matches = pickerNodes(picker).filter { node ->
            node.packageName?.toString() == picker && node.isVisibleToUser && node.isEnabled &&
                (node.text?.toString() in labels || node.contentDescription?.toString() in labels)
        }.distinct()
        check(matches.size <= 1) { "Ambiguous requested picker control" }
        return matches.singleOrNull()
    }

    private fun activatePickerControl(picker: String, labels: Set<String>) {
        try {
            compose.waitUntil(15_000) { findPickerControl(picker, labels) != null }
        } catch (miss: ComposeTimeoutException) {
            val safeLabels = labels + setOf("PristinePage QA", "Generated fixtures", "Original A.png", "Original B.png", "Corrupt image.png", "No items", "No files", "Open")
            val observed = pickerNodes(picker).mapNotNull { it.text?.toString()?.takeIf(safeLabels::contains) }.distinct()
            throw AssertionError("Missing fixture picker control $labels; matching visible fixture labels: $observed", miss)
        }
        val node = checkNotNull(findPickerControl(picker, labels))
        val document = SyntheticDocumentsProvider.HDR_NAME in labels
        if (!document) {
            val actionNode = generateSequence(node) { it.parent }.firstOrNull { it.isClickable && it.packageName?.toString() == picker }
            check(checkNotNull(actionNode).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            return
        }
        // Older DocumentsUI puts click handling on the list. The child's visible title proves which row to touch.
        val row = generateSequence(node) { it.parent }.firstOrNull { it.parent?.viewIdResourceName?.endsWith("/dir_list") == true } ?: node
        check(row.refresh() && row.isVisibleToUser && row.isEnabled)
        val bounds = Rect().also(row::getBoundsInScreen)
        check(!bounds.isEmpty)
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "input tap ${bounds.centerX()} ${bounds.centerY()}"
        )).use { check(it.readBytes().isEmpty()) }
    }

    @Test fun livePreviewCapturesAndSavesOnePage() = runBlocking<Unit> {
        assertEquals("Use only a guest with approved synthetic camera access", PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.CAMERA))
        val acquisition = openNewScan()
        compose.onNodeWithTag("camera-preview").assertExists()
        compose.waitUntil(20_000) {
            compose.onAllNodes(hasTestTag("camera-shutter") and hasClickAction() and isEnabled()).fetchSemanticsNodes().size == 1
        }
        tap("camera-shutter")
        compose.waitUntil(25_000) { acquisition.state.value.let { it.draft?.pages?.size == 1 && !it.busy } }
        val page = checkNotNull(acquisition.state.value.draft).pages.single()
        val graph = app.services.imports()
        val original = graph.store.committedSources.load(page.sourceId)
        assertTrue(original.byteCount > 0)

        tap("atelier-review-import")
        tap("atelier-finish-import")
        awaitWizard(acquisition, 0)
        tap("atelier-wizard-skip-frame")
        tap("atelier-wizard-confirm-skip")
        awaitWizard(acquisition, 1)
        tap("atelier-wizard-next")
        awaitWizard(acquisition, 2)
        tap("atelier-wizard-next")
        val library = compose.runOnUiThread { ViewModelProvider(compose.activity)[ImportLibraryViewModel::class.java].session }
        compose.waitUntil(25_000) { !acquisition.state.value.open && library.state.value.document?.pages?.singleOrNull()?.pageId == page.pageId }
        val album = checkNotNull(library.state.value.document).document.documentId
        assertEquals(page.sourceId, graph.store.editablePages.load(album, page.pageId).sourceId)
    }

    private suspend fun openNewScan(): AtelierCaptureViewModel {
        check(app.services.imports().store.atelier.pending() == null) { "Preserve the existing draft; it does not belong to this test" }
        val owner = compose.runOnUiThread { ViewModelProvider(compose.activity)[AtelierCaptureViewModel::class.java] }
        tap("atelier-new")
        compose.waitUntil(15_000) { owner.state.value.let { it.open && !it.busy } }
        val state = owner.state.value
        assertNull(state.error)
        assertFalse(state.resumeChoice)
        assertTrue(checkNotNull(state.draft).pages.isEmpty())
        return owner
    }

    private fun tap(tag: String) = compose.onNodeWithTag(tag).assertIsEnabled().performClick()

    private fun awaitWizard(owner: AtelierCaptureViewModel, step: Int) = compose.waitUntil(20_000) {
        owner.state.value.let { it.draft?.step == step && it.editor?.state?.value?.loading == false }
    }
}
