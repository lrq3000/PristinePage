package app.pristinepage

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
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
import app.pristinepage.library.ImportLibraryViewModel
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
        assertEquals("Do not activate an unverified camera on the retained guest", PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(Manifest.permission.CAMERA))
        DocumentCatalogIntegrationTest.Fixture().use { fixture ->
            val graph = fixture.open()
            val models = ViewModelStore()
            val owner = compose.runOnUiThread {
                val factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        AtelierCaptureViewModel(context, { graph }) { _, _ -> error("Cancel must not save pages") } as T
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
                for ((kind, tag) in listOf(PhotoImportKind.GALLERY to "atelier-import-gallery", PhotoImportKind.FILES to "atelier-import-files")) {
                    val intent = PhotoPickerContract(kind).createIntent(context, Unit)
                    val handler = intent.resolveActivity(context.packageManager)?.packageName
                    assertNotNull("No installed picker for $kind", handler)
                    tap("atelier-camera-import")
                    compose.onNodeWithText(context.getString(R.string.atelier_import_sdr_note)).assertIsDisplayed()
                    tap(tag)
                    val automation = instrumentation.uiAutomation
                    compose.waitUntil(15_000) { automation.rootInActiveWindow?.packageName?.toString() == handler }
                    assertEquals(kind, owner.state.value.request?.kind)
                    assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                    compose.waitUntil(15_000) { owner.state.value.let { !it.busy && it.request == null && it.draft?.pending == null } }
                    assertEquals(0, checkNotNull(owner.state.value.draft).pages.size)
                    assertEquals(0, fixture.providerOpens)
                }
                compose.runOnUiThread { owner.leave(true) } // Only this invocation's isolated empty draft.
                compose.waitUntil(10_000) { !owner.state.value.open }
            } finally {
                compose.runOnUiThread { compose.activity.setContent {}; models.clear() }
            }
        }
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
