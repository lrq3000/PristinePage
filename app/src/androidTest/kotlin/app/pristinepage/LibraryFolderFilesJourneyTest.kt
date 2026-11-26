package app.pristinepage

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Instrumentation
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.core.pipeline.OutputSpec
import app.pristinepage.core.pipeline.RenderPurpose
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.data.library.AndroidLibraryTreeRead
import app.pristinepage.data.library.LibraryDescriptorAncestryWitness
import app.pristinepage.data.library.AndroidLibraryStartup
import app.pristinepage.data.library.AndroidLibraryWritableFolder
import app.pristinepage.data.library.LibraryActivation
import app.pristinepage.data.library.LibraryBackupCapacityWitness
import app.pristinepage.data.library.LibraryFolderExportState
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.IngestResult
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.SaveRecipeResult
import app.pristinepage.data.model.SourceBlobId
import app.pristinepage.data.repository.ScopedCommittedOriginals
import app.pristinepage.data.repository.SavedOcrWork
import app.pristinepage.editor.EditorIdentity
import app.pristinepage.editor.EditorViewModel
import app.pristinepage.editor.EditorView
import app.pristinepage.editor.PageEditorScreen
import app.pristinepage.library.LibraryStorageViewModel
import app.pristinepage.design.AtelierPreferences
import app.pristinepage.image.decode.AndroidSourceInspector
import app.pristinepage.image.decode.SourceDecoder
import app.pristinepage.library.LibraryBackupConfirmation
import app.pristinepage.library.LibraryBackupViewModel
import app.pristinepage.library.LibraryFolderGrantHandoff
import app.pristinepage.library.LibraryFolderPickerContract
import app.pristinepage.library.ApplicationLibraryActions
import app.pristinepage.library.AtelierLibraryScreen
import app.pristinepage.library.ImportLibrarySession
import app.pristinepage.library.LibraryIdentity
import app.pristinepage.testing.LibraryInterruptionPickerActivity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** System Files, the exact Android grant and an isolated synthetic library. The opt-in write probe touches only its newly created child. */
@RunWith(AndroidJUnit4::class)
class LibraryFolderFilesJourneyTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val interruptionCompose = createAndroidComposeRule<LibraryInterruptionPickerActivity>()
    private val noColdRecoveryPicker = TestRule { next, description ->
        if (description.methodName != "coldSettingsRecoversTheExactCompletedBackupWithoutWriting") next else
            object : Statement() {
                override fun evaluate() {
                    val instrumentation = InstrumentationRegistry.getInstrumentation()
                    // Block even a transient launch from MainActivity creation through its teardown.
                    val monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_OPEN_DOCUMENT_TREE),
                        Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
                    try {
                        next.evaluate()
                        assertEquals("Cold backup recovery tried to open a folder chooser", 0, monitor.hits)
                    } finally {
                        instrumentation.removeMonitor(monitor)
                    }
                }
            }
    }
    private val chooseHost = TestRule { next, description ->
        val rule = if (description.methodName in setOf("endsProcessAfterPayloadBeforeTheCompletionMarker", "persistsIndependentFilesRootsAndAnchor",
                 "separatesIndependentlySelectedRootsWithoutAParentGrant", "selectedFilesMergeAppendsOnlyTheMissingOriginalAndKeepsItsPredecessor",
                 "selectedFilesMergeCanAppendTwiceWithoutReusingAnOlderStage", "endsProcessAfterSelectedAppendCompletionBeforeDelivery",
                 "oneFilesSelectionPublishesOnlyAClosedEditedCatalog", "selectedRecipeReceiptBindsTheExactIssuingActivation",
                 "selectedRecipeAdvancesOnlyTheVerifiedDirectSuccessor", "opensSelectedAlbumGridAndVerifiedOriginalWhileBrowsing",
                 "startEmptyRefusesANestedStageOfTheCurrentFilesLibrary", "unconfirmedDefaultGraphCloseFencesBothSuccessorAndReturn",
                 "selectedEditorDisplaysActualOriginalAndFrameWithoutWrite"))
            interruptionCompose else compose
        rule.apply(next, description)
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(noColdRecoveryPicker).around(chooseHost)

    @Test fun actualFilesGrantReopensAfterActivityRecreationAndRevocationFailsClosed() = verifyJourney()

    @Test fun coldSettingsRecoversTheExactCompletedBackupWithoutWriting() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as PristinePageApplication
        val arguments = InstrumentationRegistry.getArguments()
        val folder = arguments.getString("settingsCompletedFolder")
            ?.takeIf { it.matches(Regex("PristinePage-backup-[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")) }
            ?: error("The existing completed backup was not admitted")
        val parent = arguments.getString("settingsCompletedParent")
            ?.takeIf { it.matches(Regex("PristinePage-tree-[a-f0-9]{12}")) } ?: error("No exact synthetic backup parent")
        val operation = arguments.getString("settingsCompletedOperation")
            ?.takeIf { it.matches(Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")) } ?: error("No prelaunch backup operation")
        val backup = compose.runOnUiThread { ViewModelProvider(compose.activity)[LibraryBackupViewModel::class.java].session }
        val witness = File(app.filesDir.canonicalFile, "library-settings-native-witness")
        check(witness.length() in 1..1024)
        val published = witness.readLines().also { check(it.size == 15) }
        fun assertNoBackupGrant() {
            assertTrue(app.contentResolver.persistedUriPermissions.isEmpty())
            // Test both admitted Android spellings of this physical Documents child.
            for (id in listOf("primary:Documents/$parent", "home:$parent")) {
                val tree = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", id)
                assertEquals(PackageManager.PERMISSION_DENIED,
                    app.checkUriPermission(tree, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            }
        }
        assertNoBackupGrant()
        val activation = checkNotNull(runBlocking { app.services.recordedDefaultActivation() })

        compose.onNodeWithTag("atelier-settings").performClick()
        compose.onNodeWithTag("settings-open-library_storage").performClick()
        compose.onNodeWithTag("settings-screen-library_storage").assertIsDisplayed()
        compose.waitUntil(12_000) { backup.state.value.let { it.canReview && it.history?.receipts?.size == 1 } }
        val state = backup.state.value
        val history = checkNotNull(state.history)
        assertEquals(0, history.unresolvedCount)
        val previous = (history.receipts.single() as LibraryFolderExportState.Completed).receipt
        assertEquals(folder, previous.folderName)
        assertEquals(operation, previous.operationId)
        assertEquals(listOf(previous.libraryId, previous.generationId, folder), published.subList(6, 9))
        assertEquals(activation, state.issuedAt)
        assertEquals(previous, (runBlocking {
            app.services.inspectConfirmedDefaultBackup(LibraryBackupConfirmation.Ticket(previous.operationId, activation))
        } as LibraryFolderExportState.Completed).receipt)
        assertEquals(null, state.completed)
        assertEquals(null, state.picker)
        assertTrue(state.canReview)
        compose.onNodeWithText(app.getString(R.string.library_backup_record, folder)).performScrollTo().assertIsDisplayed()
        assertEquals(app.packageName, instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
        assertNoBackupGrant()
    }

    @Test fun settingsOpensAndCancelsFilesWithoutStartingABackup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = settingsFixtureFolder()
        val app = instrumentation.targetContext.applicationContext as PristinePageApplication
        val graph = runBlocking { app.services.imports() }
        val album = runBlocking { graph.store.catalog.list(null, 2) }.single()
        assertEquals("Synthetic Settings backup", album.title)
        val page = runBlocking { graph.store.catalog.load(album.documentId).pages.single() }
        compose.waitUntil(20_000) {
            runBlocking { graph.store.savedOcr.readStatus(album.documentId, page.pageId, 2)?.phase } == SavedOcrWork.PagePhase.READY
        }
        val backup = compose.runOnUiThread { ViewModelProvider(compose.activity)[LibraryBackupViewModel::class.java].session }
        val grants = app.contentResolver.persistedUriPermissions.map { Triple(it.uri, it.isReadPermission, it.isWritePermission) }.toSet()
        val pointer = File(app.filesDir.canonicalFile, "library-settings/active-library")
        val originalPointer = pointer.readBytes()
        fun attempts() = checkNotNull(app.filesDir.canonicalFile.list()).filter { it.startsWith("library-out-") }.toSet()
        val before = attempts()
        assertTrue("The retained synthetic guest must have no earlier backup attempt", before.isEmpty())
        val ocrBefore = settingsFixtureOcr(app, page.pageId.value)
        compose.onNodeWithTag("atelier-settings").performClick()
        compose.onNodeWithTag("settings-open-library_storage").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("library-backup-export") and isEnabled()).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("library-backup-export").performClick()
        val picker = checkNotNull(LibraryFolderPickerContract(writable = true).createIntent(app, Unit).component).packageName
        compose.onNodeWithTag("library-backup-choose-folder").performClick()
        val automation = instrumentation.uiAutomation
        fun foreground() = automation.rootInActiveWindow?.packageName?.toString()
        try {
            compose.waitUntil(12_000) { foreground() == picker || backup.state.value.notice != null }
            check(foreground() == picker)
        } catch (failure: Exception) {
            val state = backup.state.value
            val request = state.picker?.let { if (it.authorization.awaitsLaunch(it.ticket)) "awaiting-launch" else "claimed" }
            val sameActivation = runBlocking { app.services.recordedDefaultActivation() } == graph.activation
            throw IllegalStateException("Settings picker not foreground; foreground=${foreground()}; " +
                "reviewing=${state.reviewing}; request=$request; notice=${state.notice}; sameActivation=$sameActivation", failure)
        }
        assertEquals(picker, foreground())
        selectSettingsDestination(instrumentation, picker, folder, selectFolder = false)
        cancelExactFiles(instrumentation, picker, app.packageName)
        compose.waitUntil(10_000) {
            foreground() == app.packageName && backup.state.value.let { it.picker == null && it.canReview }
        }
        assertEquals(before, attempts())
        assertEquals(grants, app.contentResolver.persistedUriPermissions.map { Triple(it.uri, it.isReadPermission, it.isWritePermission) }.toSet())
        assertArrayEquals(originalPointer, pointer.readBytes())
        assertEquals(ocrBefore, settingsFixtureOcr(app, page.pageId.value))
    }

    @Test fun mainSettingsOpensAnExistingLibraryAndReturnsToTheUnchangedDefault() = mainSettingsJourney(applyEdit = false)

    @Test fun mainSettingsAppliesOneSelectedEditAndReopensTheSamePage() = mainSettingsJourney(applyEdit = true)

    private fun mainSettingsJourney(applyEdit: Boolean) {
        check(InstrumentationRegistry.getArguments().getString("librarySettingsOpenFixture") == if (applyEdit) "apply" else "fresh") {
            "This journey requires an independently admitted synthetic app graph"
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as PristinePageApplication
        val folder = settingsFixtureFolder()
        val graph = runBlocking { app.services.imports() }
        val initial = graph.activation
        assertEquals(if (applyEdit) 3L else 1L, initial.number)
        assertTrue(runBlocking { graph.store.catalog.list(null, 2) }.isEmpty())
        val witness = LibraryIndependentRootGrants
        val before = witness.inventory(app)
        if (applyEdit) {
            val priorFolder = InstrumentationRegistry.getArguments().getString("librarySettingsPriorFolder")
                ?.takeIf { it != folder && it.matches(Regex("PristinePage-tree-[0-9a-f]{12}")) }
                ?: error("No distinct retained Files grant baseline")
            val previous = Uri.parse(before.keys.single())
            assertTrue(previous in witness.spellings(priorFolder))
            assertEquals(true to true, before.getValue(previous.toString()))
        } else assertTrue(before.isEmpty())
        val album = DocumentId("aaaaaaaa-aaaa-4aaa-8aaa-000000000001")
        val editor = compose.runOnUiThread { ViewModelProvider(compose.activity)[EditorViewModel::class.java] }
        val picker = checkNotNull(LibraryFolderPickerContract(writable = true).createIntent(app, Unit).component).packageName
        val folderButton = "library-storage-folder"
        fun openStorage() {
            compose.onNodeWithTag("atelier-settings").performClick()
            compose.onNodeWithTag("settings-open-library_storage").performClick()
        }
        fun enabled(tag: String) = compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().size == 1
        openStorage()
        compose.waitUntil(10_000) { enabled(folderButton) }
        compose.onNodeWithTag(folderButton).performScrollTo().performClick()
        compose.waitUntil(12_000) { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == picker }
        val restoreHidden = InstrumentationRegistry.getArguments().getString("librarySettingsRestoreHidden") == "true"
        check(!restoreHidden || applyEdit)
        selectSettingsDestination(instrumentation, picker, folder, touchNavigation = true, restorePreviouslyHidden = restoreHidden)
        val storage = compose.runOnUiThread { ViewModelProvider(compose.activity)[LibraryStorageViewModel::class.java] }
        compose.waitUntil(20_000) { storage.state.value.review != null }
        val review = checkNotNull(storage.state.value.review)
        check(review.picked == folder || review.picked == "Documents/$folder")
        assertEquals(initial, runBlocking { app.services.storageLocation().activation })
        compose.onNodeWithText(app.getString(R.string.library_open_folder_confirm,
            app.getString(R.string.library_default_location), review.picked)).assertIsDisplayed()
        compose.onNodeWithTag("library-storage-open").performClick()
        compose.waitUntil(20_000) {
            val location = storage.state.value.location
            location?.activation?.libraryId == "9e940d7b-76c7-45ea-b5e4-0ad86f26ea13" &&
                location.activation.number > initial.number && !storage.state.value.busy
        }
        var selected = checkNotNull(storage.state.value.location).activation
        assertEquals(initial.number + 1, selected.number)
        val retained = witness.inventory(app)
        val exact = Uri.parse((retained.keys - before.keys).single())
        assertTrue(exact in witness.spellings(folder))
        assertEquals(before + (exact.toString() to (true to true)), retained)
        compose.onNodeWithTag("settings-back").performClick()
        compose.onNodeWithTag("settings-back").performClick()
        val albumTag = "atelier-album-${album.value}"
        compose.waitUntil(20_000) { enabled(albumTag) }
        compose.onNodeWithTag(albumTag).performClick()
        val page = runBlocking { app.services.loadLibraryAlbum(selected, album).pages.single() }
        val pageTag = "original-page-${page.pageId.value}"
        compose.waitUntil(20_000) { enabled(pageTag) }
        fun openPage() {
            compose.onNodeWithTag(pageTag).performClick()
            compose.waitUntil(20_000) { editor.session.value?.preview?.value?.display != null }
            assertEquals(EditorIdentity(album, page.pageId, selected), editor.state.value.identity)
        }
        fun checkOriginal() {
            compose.onNodeWithTag("editor-tool-original").performClick()
            compose.waitUntil(20_000) {
                editor.session.value?.preview?.value?.let { it.view == EditorView.ORIGINAL && !it.previous && it.display != null } == true
            }
            compose.runOnUiThread {
                val original = checkNotNull(editor.session.value?.preview?.value?.display).bitmap
                assertEquals(0xff637b89.toInt(), original.getPixel(2, 1))
            }
        }
        openPage()
        checkOriginal()
        if (applyEdit) {
            compose.onNodeWithTag("editor-tool-shape").performClick()
            compose.onNodeWithTag("editor-shape-perspective").performScrollTo().performClick()
            val frame = hasAnyAncestor(hasTestTag("atelier-frame-canvas")) and
                hasContentDescription(app.getString(R.string.editor_original_description))
            compose.waitUntil(20_000) { compose.onAllNodes(frame, useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
            compose.onNode(frame, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("editor-back").performClick()
            compose.onNodeWithTag("editor-discard-exit").performClick()
            compose.waitUntil(15_000) { editor.session.value == null && enabled(pageTag) }
            openPage()
            val live = checkNotNull(editor.session.value)
            assertEquals(2L, live.state.value.page?.recipeRevision)
            compose.onNodeWithTag("editor-tool-rotate").performClick()
            compose.onNodeWithTag("editor-rotate").performScrollTo().performClick()
            compose.onNodeWithTag("editor-apply").assertIsNotEnabled()
            compose.runOnUiThread { compose.activity.qualifySelectedApplyForTest(EditorIdentity(album, page.pageId, selected)) }
            compose.onNodeWithTag("editor-apply").assertIsEnabled().performClick() // One save; uncertain delivery does not authorize a second tap.
            compose.waitUntil(45_000) { !live.state.value.saving }
            val accepted = live.state.value
            assertEquals(null, accepted.error)
            assertFalse(accepted.dirty)
            assertEquals(3L, accepted.page?.recipeRevision)
            assertEquals(2, accepted.page?.recipe?.geometry?.outputQuarterTurns)
            selected = selected.copy(number = selected.number + 1)
            assertEquals(EditorIdentity(album, page.pageId, selected), editor.state.value.identity)
            assertEquals(selected, runBlocking { app.services.storageLocation().activation })
            val pointer = File(app.filesDir.canonicalFile, "library-settings/active-library")
            check(pointer.length() in 1..320)
            val record = File(app.filesDir.canonicalFile, "library-settings-apply-pointer-$folder")
            Files.write(record.toPath(), pointer.readBytes(), CREATE_NEW)
            compose.onNodeWithTag("editor-back").performClick()
            compose.waitUntil(15_000) { editor.session.value == null && enabled(pageTag) }
            openPage()
            val reopened = checkNotNull(editor.session.value?.state?.value?.page)
            assertEquals(accepted.page, reopened)
            val result = checkNotNull(editor.session.value?.preview?.value)
            assertEquals(EditorView.RESULT, result.view)
            assertFalse(result.previous)
            val rendered = checkNotNull(result.display)
            assertEquals(3L, rendered.request.recipeRevision)
            assertEquals(reopened.recipe, rendered.request.recipe)
            compose.runOnUiThread { assertEquals(0xff637b89.toInt(), rendered.bitmap.getPixel(0, 0)) }
            checkOriginal()
        }
        compose.onNodeWithTag("editor-back").performClick()
        compose.waitUntil(15_000) { editor.state.value.identity == null && enabled(pageTag) }
        assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        compose.waitUntil(15_000) { enabled("atelier-settings") }
        openStorage()
        compose.waitUntil(15_000) { enabled("library-storage-use-default") }
        compose.onNodeWithTag("library-storage-use-default").performScrollTo().performClick()
        compose.onNodeWithText(app.getString(R.string.library_open_folder_confirm, review.picked,
            app.getString(R.string.library_default_location))).assertIsDisplayed()
        compose.onNode(hasTestTag("library-storage-use-default").not() and
            hasText(app.getString(R.string.library_use_default)) and hasClickAction()).performClick()
        compose.waitUntil(20_000) {
            val location = storage.state.value.location
            location?.folderLabel == null && location?.activation?.libraryId == initial.libraryId &&
                location.activation.number == selected.number + 1 && !storage.state.value.busy
        }
        val returned = checkNotNull(storage.state.value.location).activation
        val backup = compose.runOnUiThread { ViewModelProvider(compose.activity)[LibraryBackupViewModel::class.java].session }
        compose.waitUntil(20_000) {
            backup.state.value.issuedAt == returned && enabled("library-backup-export")
        }
        val reopened = runBlocking { app.services.imports() }
        assertTrue(runBlocking { reopened.store.catalog.list(null, 2) }.isEmpty())
        assertEquals(retained, witness.inventory(app))
        app.contentResolver.releasePersistableUriPermission(exact,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(before, witness.inventory(app))
    }

    private fun cancelExactFiles(instrumentation: Instrumentation, picker: String, appPackage: String,
        wait: (Long, () -> Boolean) -> Unit = { timeout, condition -> compose.waitUntil(timeout, condition) },
    ) {
        val automation = instrumentation.uiAutomation
        fun foreground() = automation.rootInActiveWindow?.packageName?.toString()
        var backSteps = 0
        while (foreground() == picker) {
            check(++backSteps <= 5) { "The Files cancellation did not leave its bounded directory route" }
            val root = checkNotNull(automation.rootInActiveWindow)
            check(root.packageName?.toString() == picker)
            val labels = buildList { root.collectLabels(this) }
            assertTrue("The exact Files window did not accept Back", automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            wait(3_000) {
                val active = automation.rootInActiveWindow
                active != null && (active.packageName?.toString() == appPackage ||
                    (active.packageName?.toString() == picker && buildList { active.collectLabels(this) } != labels))
            }
        }
        assertEquals(appPackage, foreground())
    }

    private fun settingsFixtureFolder(): String =
        InstrumentationRegistry.getArguments().getString("libraryFixtureFolder")
            ?.takeIf { it.matches(Regex("PristinePage-tree-[0-9a-f]{12}")) }
            ?: error("No exact synthetic folder was staged")

    @Test fun settingsPublishesOneBackupOfTheSyntheticDefaultLibrary() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val fixture = args.getString("librarySettingsFixture")
        check(fixture == "fresh" || fixture == "retained") { "The witness requires an admitted synthetic guest" }
        val folder = settingsFixtureFolder()
        val app = instrumentation.targetContext.applicationContext as PristinePageApplication
        fun grants() = app.contentResolver.persistedUriPermissions.associate { it.uri.toString() to (it.isReadPermission to it.isWritePermission) }
        val before = grants()
        assertTrue("The synthetic app may not hold any persisted picker grant", before.isEmpty())
        val graph = runBlocking { app.services.imports() }
        assertEquals(1L, graph.activation.number)
        val privateRoot = app.filesDir.canonicalFile
        val evidence = File(privateRoot, "library-settings-native-witness")
        val spent = File(privateRoot, "library-settings-witness-spent-v2")
        val provedNavigationOnly = args.getString("librarySettingsPriorNavigationOnly") == "1"
        val privateLeaves = checkNotNull(privateRoot.list()).toSet()
        check(evidence.name !in privateLeaves && spent.name !in privateLeaves && "library-backup-acknowledged" !in privateLeaves &&
            ("library-settings-witness-spent" in privateLeaves) == provedNavigationOnly && (!provedNavigationOnly || fixture == "retained") &&
            privateLeaves.none { it.startsWith("library-out-") }) { "Refusing to repeat a possible prior backup" }
        val historyBefore = runBlocking { app.services.discoverDefaultBackups(graph.activation) }
        check(historyBefore.receipts.isEmpty() && historyBefore.unresolvedCount == 0)
        val pointer = File(app.filesDir.canonicalFile, "library-settings/active-library")
        val pointerBefore = pointer.readBytes()
        val png = LibraryDefaultFolderExportProbe.originalPng()
        val seededRecipes = if (fixture == "fresh") {
            assertTrue("Refuse an existing default album before any synthetic ingest", runBlocking { graph.store.catalog.list(null, 1).isEmpty() })
            val file = File(app.cacheDir, "editor-journey-fixtures/${UUID.randomUUID()}.png")
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            check(file.createNewFile())
            file.writeBytes(png)
            val authority = "${app.packageName}.editor-fixtures"
            runBlocking {
                val album = graph.store.documents.createDocument("Synthetic Settings backup")
                val batch = graph.store.imports.beginImport(album, 1, null)
                val committed = graph.photoImports.importProviderReferences(listOf("content://$authority/${file.nameWithoutExtension}"), batch.parentId)
                    .single() as IngestResult.Committed
                val edit = graph.store.editablePages.load(album, committed.pageId)
                val changed = edit.recipe.copy(geometry = (edit.recipe.geometry as GeometryRecipe.None).copy(outputQuarterTurns = 1))
                val saved = graph.store.documents.saveRecipe(committed.pageId, 1, changed) as SaveRecipeResult.Saved
                assertEquals(2L, saved.recipeRevision)
                assertEquals(3L, saved.documentRevision)
                listOf(sha256(RecipeCodec.encode(edit.recipe)), sha256(RecipeCodec.encode(changed)))
            }
        } else null
        val (page, source) = runBlocking {
            val album = graph.store.catalog.list(null, 2).single()
            check(album.title == "Synthetic Settings backup" && album.documentRevision == 3L && !album.hasPendingImport)
            val item = graph.store.catalog.load(album.documentId).pages.single()
            check(item.ordinal == 0 && item.recipeRevision == 2L)
            val saved = graph.store.editablePages.load(album.documentId, item.pageId)
            saved to graph.store.committedSources.load(saved.sourceId)
        }
        assertEquals(sha256(png), source.sourceByteHash)
        assertArrayEquals(png, source.file.readBytes())
        val geometry = page.recipe.geometry as GeometryRecipe.None
        assertEquals(1, geometry.outputQuarterTurns)
        compose.waitUntil(20_000) {
            runBlocking { graph.store.savedOcr.readStatus(page.documentId, page.pageId, 2)?.phase } == SavedOcrWork.PagePhase.READY
        }
        val ocr = settingsFixtureOcr(app, page.pageId.value)
        assertEquals(source.inspection.sourcePixelHash, ocr.pixelHash)
        val revisions = seededRecipes ?: listOf(
            sha256(RecipeCodec.encode(page.recipe.copy(geometry = geometry.copy(outputQuarterTurns = 0)))),
            sha256(RecipeCodec.encode(page.recipe)),
        )
        val stateBefore = settingsFixtureInventory(app, page.documentId.value, page.pageId.value, page.sourceId.value, revisions)
        compose.onNodeWithTag("atelier-settings").performClick()
        compose.onNodeWithTag("settings-open-library_storage").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("library-backup-export") and isEnabled()).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("library-backup-export").performClick()
        val picker = checkNotNull(LibraryFolderPickerContract(writable = true).createIntent(app, Unit).component).packageName
        check(spent.createNewFile()) { "The synthetic witness already spent its one confirmation" }
        compose.onNodeWithTag("library-backup-choose-folder").performClick()
        // Let Compose schedule the chooser effect before switching to standalone system-window navigation.
        compose.waitUntil(12_000) { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == picker }
        selectSettingsDestination(instrumentation, picker, folder)
        compose.waitUntil(40_000) {
            compose.onAllNodes(hasTestTag("library-backup-completed")).fetchSemanticsNodes().size == 1
        }
        val history = runBlocking { app.services.discoverDefaultBackups(graph.activation) }
        assertEquals(0, history.unresolvedCount)
        val receipt = (history.receipts.single() as LibraryFolderExportState.Completed).receipt
        assertEquals(before, grants())
        assertArrayEquals(pointerBefore, pointer.readBytes())
        assertArrayEquals(png, runBlocking { graph.store.committedSources.load(page.sourceId).file.readBytes() })
        assertEquals(stateBefore, settingsFixtureInventory(app, page.documentId.value, page.pageId.value, page.sourceId.value, revisions))
        assertEquals(ocr, settingsFixtureOcr(app, page.pageId.value))
        check(evidence.createNewFile())
        evidence.writeText(listOf(page.documentId.value, page.pageId.value, page.sourceId.value, source.inspection.sourcePixelHash,
            revisions[0], revisions[1], receipt.libraryId, receipt.generationId, receipt.folderName, ocr.engine, ocr.version,
            ocr.layout, ocr.producer, ocr.intentHash, ocr.textHash).joinToString("\n", postfix = "\n"))
    }

    /** Never report the recognized text, even for a synthetic scan. The closed backup must match its exact accepted producer and bytes. */
    private data class SettingsFixtureOcr(val pixelHash: String, val engine: String, val version: Int, val layout: String,
        val producer: String, val intentHash: String, val textHash: String)

    private fun settingsFixtureOcr(context: Context, page: String): SettingsFixtureOcr =
        SQLiteDatabase.openDatabase(context.getDatabasePath("pristinepage.db").path, null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
            db.rawQuery("""
                SELECT r.pageId,r.recipeRevision,r.sourcePixelSha256,r.engineId,r.engineVersion,r.layout,
                       w.resultJobId,j.canonicalInputSha256,w.projectionVersion,j.state,
                       CASE WHEN length(CAST(coalesce(r.verbatimText,r.indexedText) AS BLOB))<=4096
                            THEN CAST(coalesce(r.verbatimText,r.indexedText) AS BLOB) END
                  FROM OcrResult r LEFT JOIN OcrPageWork w ON w.pageId=r.pageId
                  LEFT JOIN Job j ON j.jobId=w.resultJobId LIMIT 2
            """.trimIndent(), null).use { c ->
                check(c.moveToFirst() && c.getString(0) == page && c.getLong(1) == 2L && c.getLong(8) == 1L &&
                    c.getString(9) == "SUCCEEDED" && !c.isNull(10)) { "The sole fixture OCR result must belong to the accepted revision" }
                SettingsFixtureOcr(c.getString(2), c.getString(3), c.getInt(4), c.getString(5),
                    c.getString(6), c.getString(7), sha256(c.getBlob(10))).also { check(!c.moveToNext()) }
            }
        }

    /** The visible catalog omits removed pages. Before granting a test write, independently inspect the whole synthetic saved inventory. */
    private fun settingsFixtureInventory(context: Context, document: String, page: String, source: String, revisions: List<String>): List<String?> =
        SQLiteDatabase.openDatabase(context.getDatabasePath("pristinepage.db").path, null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
            val expectedCounts = mapOf("Document" to 1, "Page" to 1, "Source" to 1, "SourceBlob" to 1, "LibraryScanName" to 1,
                "RecipeRevision" to 2, "UndoEntry" to 1, "Mask" to 0, "RecipeMask" to 0, "AtelierDraft" to 0)
            fun row(sql: String, args: Array<String>): List<String?> = db.rawQuery(sql, args).use { c ->
                check(c.moveToFirst())
                List(c.columnCount) { c.getString(it) }.also { check(!c.moveToNext()) }
            }
            for ((table, count) in expectedCounts) {
                assertEquals("Unexpected retained $table inventory", listOf(count.toString()),
                    row("SELECT COUNT(*) FROM (SELECT 1 FROM `$table` LIMIT ${count + 1})", emptyArray()))
            }
            val membership = row("""
                SELECT d.title,d.documentRevision,d.lifecycle,p.state,p.ordinal,p.currentRecipeRevision,n.filename,b.byteSha256,
                       b.byteCount,s.pixelSha256
                  FROM Document d JOIN Page p ON p.documentId=d.documentId JOIN Source s ON s.sourceId=p.sourceId
                  JOIN SourceBlob b ON b.sourceBlobId=s.sourceBlobId JOIN LibraryScanName n ON n.pageId=p.pageId AND n.documentId=d.documentId
                 WHERE d.documentId=? AND p.pageId=? AND s.sourceId=? LIMIT 2
            """.trimIndent(), arrayOf(document, page, source))
            assertEquals(listOf("Synthetic Settings backup", "3", "ACTIVE", "ACTIVE", "0", "2", "Scan $page.png",
                sha256(LibraryDefaultFolderExportProbe.originalPng()), "81"), membership.take(9))
            val history = db.rawQuery("""
                SELECT recipeRevision,parentRevision,CASE WHEN length(canonicalRecipe)<=524288 THEN canonicalRecipe END
                  FROM RecipeRevision WHERE pageId=? AND sourceId=? ORDER BY recipeRevision LIMIT 3
            """.trimIndent(), arrayOf(page, source)).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        check(!c.isNull(2))
                        add(listOf(c.getString(0), c.getString(1), sha256(c.getBlob(2))))
                    }
                }
            }
            assertEquals(listOf(listOf("1", null, revisions[0]), listOf("2", "1", revisions[1])), history)
            val undo = row("""
                SELECT action,appliedDocumentRevision,state,expiresAt,
                       CASE WHEN length(beforeState)<=524288 THEN hex(beforeState) END,
                       CASE WHEN length(afterState)<=524288 THEN hex(afterState) END
                  FROM UndoEntry WHERE documentId=? LIMIT 2
            """.trimIndent(), arrayOf(document))
            assertEquals(listOf("RECIPE_EDIT", "3", "AVAILABLE"), undo.take(3))
            check(undo.drop(3).none { it.isNullOrEmpty() })
            membership + history.flatten() + undo
        }

    private fun selectSettingsDestination(instrumentation: Instrumentation, picker: String, folder: String,
        selectFolder: Boolean = true, navigateFromRoot: Boolean = true,
        expectedChildren: Set<String> = setOf("catalogs", "generations", "objects"), allowExactWindowTitle: Boolean = false,
        touchNavigation: Boolean = false, restorePreviouslyHidden: Boolean = false,
    ) {
        val affirmative = setOf("SELECT", "Select", "SÉLECTIONNER", "Sélectionner", "Use this folder", "USE THIS FOLDER", "Choose this folder")
        fun containsUnder(root: AccessibilityNodeInfo, text: String, within: String) =
            root.findByTextInFixture(text).any { node ->
                node.isVisibleToUser && node.packageName?.toString() == picker &&
                    generateSequence(node) { it.parent }.any { it.viewIdResourceName?.substringAfterLast('/') == within }
            }
        fun atTarget(root: AccessibilityNodeInfo): Boolean {
            // The direct API 28 route can expose only the current folder in its header. Allow that form only for a nonce-bound test name, not a matching row in the directory listing.
            val syntheticTitle = allowExactWindowTitle && root.findByTextInFixture(folder).any { node ->
                node.isVisibleToUser && node.packageName?.toString() == picker && node.viewIdResourceName == "android:id/title" &&
                    generateSequence(node) { it.parent }.none { it.viewIdResourceName?.substringAfterLast('/') == "dir_list" }
            }
            return (containsUnder(root, folder, "dropdown_breadcrumb") || syntheticTitle) &&
                expectedChildren.all { containsUnder(root, it, "dir_list") }
        }
        fun unique(root: AccessibilityNodeInfo, labels: Set<String>, parentId: String? = null): AccessibilityNodeInfo? {
            val matches = linkedSetOf<AccessibilityNodeInfo>()
            val pending = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            var visits = 0
            while (pending.isNotEmpty()) {
                check(++visits <= 1_000)
                val label = pending.removeFirst()
                repeat(label.childCount) { index -> label.getChild(index)?.let(pending::addLast) }
                if (!label.isEnabled || !label.isVisibleToUser || label.packageName?.toString() != picker ||
                    (label.text?.toString() !in labels && label.contentDescription?.toString() !in labels)) continue
                if (parentId != null && generateSequence(label) { it.parent }
                        .none { it.viewIdResourceName?.substringAfterLast('/') == parentId }) continue
                val action = if (parentId == "dir_list") {
                    if (label.viewIdResourceName != "android:id/title") continue
                    generateSequence(label) { it.parent }.firstOrNull {
                        it.isFocusable && it.parent?.viewIdResourceName?.substringAfterLast('/') == parentId
                    }
                } else generateSequence(label) { it.parent }.firstOrNull { it.isClickable }
                action?.takeIf { it.isEnabled && it.isVisibleToUser && it.packageName?.toString() == picker }?.let(matches::add)
            }
            check(matches.size <= 1) { "The synthetic Settings destination did not expose a unique Files control" }
            return matches.singleOrNull()
        }
        fun select(step: String, labels: Set<String>, parent: String? = null, guard: (AccessibilityNodeInfo) -> Boolean = { true }) {
            var confirmed: AccessibilityNodeInfo? = null
            await(instrumentation, picker, step) { root ->
                if (!guard(root)) return@await false
                val first = unique(root, labels, parent) ?: return@await false
                val current = instrumentation.uiAutomation.rootInActiveWindow ?: return@await false
                if (current.packageName?.toString() != picker || current.windowId != root.windowId || !guard(current)) return@await false
                val control = unique(current, labels, parent) ?: return@await false
                val expectedBounds = Rect().also(first::getBoundsInScreen)
                if (expectedBounds.isEmpty || !control.refresh() || !control.isEnabled || !control.isVisibleToUser) return@await false
                if (Rect().also(control::getBoundsInScreen) != expectedBounds || unique(current, labels, parent) != control) return@await false
                confirmed = control
                true
            }
            val control = checkNotNull(confirmed)
            // A semantic acknowledgment did not establish navigation on this picker. Exercise real user touches while keeping the same live identity and geometry checks.
            if (touchNavigation) tap(instrumentation, control)
            else if (control.isClickable) check(control.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Files did not acknowledge $step" }
            else {
                check(parent == "dir_list")
                tap(instrumentation, control)
            }
        }
        fun finishExactRoute() {
            if (!selectFolder) {
                await(instrumentation, picker, "exact destination before cancellation", ::atTarget)
                // Back from nested directories can pop one level; callers verify cancellation of the entire chooser.
                return
            }
            select("exact synthetic destination", affirmative, guard = ::atTarget)
            // API 28 direct return was qualified separately. Do not authorize a second, unverified prompt.
            val appPackage = instrumentation.targetContext.packageName
            val deadline = SystemClock.uptimeMillis() + 12_000
            while (SystemClock.uptimeMillis() < deadline) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                if (root?.packageName?.toString() == appPackage) return
                if (root?.packageName?.toString() == picker &&
                    unique(root, setOf("Allow", "ALLOW", "Autoriser", "AUTORISER")) != null) error("Unqualified additional Files confirmation")
                SystemClock.sleep(125)
            }
            error("Files did not return after the one exact selection")
        }
        if (!navigateFromRoot) return finishExactRoute()
        val drawer = setOf("Show roots", "Afficher les répertoires racines")
        val options = setOf("More options", "Plus d'options", "Plus d’options")
        val showStorage = setOf("Show internal storage", "Aff. mémoire stockage interne")
        val hideStorage = setOf("Hide internal storage", "Masquer mém. stock. int.")
        val automation = instrumentation.uiAutomation
        fun hasVisibilityChoice(root: AccessibilityNodeInfo) =
            (unique(root, showStorage) != null) xor (unique(root, hideStorage) != null)
        fun preferenceMenu(step: String): AccessibilityNodeInfo {
            val open = automation.rootInActiveWindow
            if (open?.packageName?.toString() == picker && hasVisibilityChoice(open)) return open
            select("Files options for $step", options)
            return await(instrumentation, picker, step, ::hasVisibilityChoice)
        }
        fun isFilesToolbar(root: AccessibilityNodeInfo) =
            !hasVisibilityChoice(root) && unique(root, options) != null && unique(root, drawer) != null
        fun dismissPreferenceMenu() {
            if (touchNavigation) {
                val live = await(instrumentation, picker, "current Files overflow or toolbar") {
                    hasVisibilityChoice(it) || isFilesToolbar(it)
                }
                if (isFilesToolbar(live)) return
                val down = SystemClock.uptimeMillis()
                for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                    val key = KeyEvent(down, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_BACK, 0)
                    key.source = InputDevice.SOURCE_KEYBOARD
                    check(automation.injectInputEvent(key, true))
                }
            } else check(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            if (touchNavigation) await(instrumentation, picker, "Files toolbar after menu dismissal", ::isFilesToolbar)
        }
        fun reconcilePreferenceToggle(shown: Set<String>) {
            if (!touchNavigation) return
            val settled = await(instrumentation, picker, "Files toolbar or updated overflow menu") {
                isFilesToolbar(it) || unique(it, shown) != null
            }
            // A changed menu item proves the toggle took effect. Close that positively observed popup, never issue a second toggle.
            if (!isFilesToolbar(settled)) dismissPreferenceMenu()
        }
        // Inspect the real platform menu once; the primary-storage root is hidden by default on the fresh API 28 guest.
        val menu = preferenceMenu("initial internal storage preference")
        val wasHidden = unique(menu, showStorage) != null
        var changed = false
        var navigationFailure: Throwable? = null
        try {
            if (wasHidden) {
                changed = true // Reconcile even if the touch is accepted but its effect is not delivered cleanly.
                select("show internal storage", showStorage)
                reconcilePreferenceToggle(hideStorage)
            } else dismissPreferenceMenu()
            select("root drawer", drawer)
            select("primary storage", setOf(Build.MODEL, "Internal storage", "Stockage interne", "Stockage interne partagé"), "roots_list")
            select("Documents", setOf("Documents"), "dir_list")
            if (touchNavigation) await(instrumentation, picker, "Documents breadcrumb") {
                containsUnder(it, "Documents", "dropdown_breadcrumb")
            }
            select("synthetic folder", setOf(folder), "dir_list") { containsUnder(it, "Documents", "dropdown_breadcrumb") }
            await(instrumentation, picker, "exact destination before restoring Files preference", ::atTarget)
        } catch (failure: Throwable) {
            navigationFailure = failure
            throw failure
        } finally {
            if (changed || restorePreviouslyHidden) {
                try {
                    val visible = preferenceMenu("restore internal storage preference")
                    if (unique(visible, hideStorage) != null) {
                        select("restore hidden internal storage", hideStorage)
                        reconcilePreferenceToggle(showStorage)
                    }
                    await(instrumentation, picker, "settled Files restoration menu or toolbar") {
                        unique(it, showStorage) != null || (!hasVisibilityChoice(it) && unique(it, options) != null)
                    }
                    val restored = preferenceMenu("verify restored internal storage preference")
                    check(unique(restored, showStorage) != null) { "Files did not restore the hidden internal storage preference" }
                    dismissPreferenceMenu()
                } catch (restorationFailure: Throwable) {
                    val original = navigationFailure
                    if (original == null) throw restorationFailure
                    original.addSuppressed(restorationFailure)
                }
            }
        }
        // Android 9 can move back to Documents when its hidden-root preference changes. This is a new
        // navigation action on the positively observed parent, never another folder confirmation.
        val settled = await(instrumentation, picker, "settled exact fixture or its Documents parent") {
            atTarget(it) || (containsUnder(it, "Documents", "dropdown_breadcrumb") && unique(it, setOf(folder), "dir_list") != null)
        }
        if (!atTarget(settled)) {
            select("synthetic folder after Files preference restoration", setOf(folder), "dir_list") {
                containsUnder(it, "Documents", "dropdown_breadcrumb")
            }
        }
        finishExactRoute()
    }

    private fun AccessibilityNodeInfo.findByTextInFixture(text: String): List<AccessibilityNodeInfo> =
        findAccessibilityNodeInfosByText(text).filter { it.text?.toString() == text || it.contentDescription?.toString() == text }

    @Test fun defaultAppGraphUsesItsDurableActivationBeforeAndAfterActivityRecreation() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PristinePageApplication
        val initial = runBlocking { app.services.imports().activation }
        assertEquals(1L, initial.number)
        assertEquals(initial, runBlocking { AndroidLibraryStartup(app).requireDefaultActivation() })
        compose.activityRule.scenario.recreate()
        val reopened = runBlocking { app.services.imports().activation }
        assertEquals(initial, reopened)
        assertEquals(initial, runBlocking { AndroidLibraryStartup(app).requireDefaultActivation() })
    }

    @Test fun exactFilesTreeExposesSyncableDirectoryDescriptors() = verifyJourney { resolver, tree, _, _, _ ->
        LibraryProviderDirectoryProbe.verify(resolver, tree)
    }

    @Test fun persistsExactFilesReadForColdAncestry() = verifyJourney(holdReadForColdAncestry = true)

    @Test fun persistsIndependentFilesRootsAndAnchor() {
        withIndependentFilesSelections(chooseAnchor = true) { app, folder, selected, before ->
            LibraryIndependentRootGrants.save(app, folder, selected, before)
        }
    }

    @Test fun separatesIndependentlySelectedRootsWithoutAParentGrant() {
        withIndependentFilesSelections(chooseAnchor = false) { app, folder, selected, before ->
            val witness = LibraryIndependentRootGrants
            fun requireNoParentAccess() {
                check(witness.inventory(app) == before + selected.associate { it.toString() to (true to false) }) {
                    "The persisted grant inventory changed unexpectedly"
                }
                witness.refuseParentAccess(app, folder)
            }
            requireNoParentAccess()
            val scratch = File(app.filesDir.canonicalFile, "tree-read-${UUID.randomUUID()}").apply { check(mkdir()) }
            val catalogs = runBlocking {
                LibraryDescriptorAncestryWitness.verifyPairWithSharedOwner(app, scratch, selected[0], selected[1], ::requireNoParentAccess)
            }
            assertEquals(selected, catalogs.map { it.tree })
            for (catalog in catalogs) {
                assertEquals("9e940d7b-76c7-45ea-b5e4-0ad86f26ea13", catalog.libraryId)
                assertEquals("25c49d9b-02d8-418f-b982-154980d89cfb", catalog.generationId)
                assertEquals(1, catalog.albums)
                assertEquals(1, catalog.scans)
            }
            val preview = runBlocking {
                LibraryDescriptorAncestryWitness.estimateMerge(app, scratch, catalogs[0], catalogs[1], ::requireNoParentAccess)
            }
            assertEquals(1, preview.albums)
            assertEquals(listOf("Synthetic.png"), preview.names)
            assertEquals(1, preview.matchedOriginals)
            assertEquals(0L, preview.missingBytes)
            assertTrue(preview.closedCatalogBytes > 0)
            assertTrue(preview.requiredDestinationBytes >= preview.closedCatalogBytes)
            assertEquals(4, preview.retainedRevisions)
            assertEquals(emptyList<String>(), scratch.list()?.toList())

            val canceled = assertThrows(CancellationException::class.java) {
                runBlocking {
                    val caller = checkNotNull(coroutineContext[Job])
                    LibraryDescriptorAncestryWitness.estimateMerge(app, scratch, catalogs[0], catalogs[1]) {
                        requireNoParentAccess()
                        val stageClosed = scratch.listFiles().orEmpty().any { File(it, "candidate.sqlite").isFile }
                        if (stageClosed) caller.cancel(CancellationException("Cancel the closed synthetic candidate"))
                    }
                }
            }
            assertEquals("Cancel the closed synthetic candidate", canceled.message)
            assertEquals(emptyList<String>(), scratch.list()?.toList())
            requireNoParentAccess()
        }
    }

    @Test fun selectedFilesMergeAppendsOnlyTheMissingOriginalAndKeepsItsPredecessor() {
        val args = InstrumentationRegistry.getArguments()
        val witness = LibraryDescriptorAncestryWitness
        val operation = UUID.fromString(requireNotNull(args.getString("librarySelectedAppendOperation"))).toString()
        val expectedMissing = requireNotNull(args.getString("librarySelectedAppendBytes")).toLong()
        check(expectedMissing in 70_000..71_000)
        withIndependentFilesSelections(chooseAnchor = false, writableDestination = true) { app, folder, selected, before ->
            fun requireAuthority() {
                val expected = before + mapOf(selected[0].toString() to (true to true), selected[1].toString() to (true to false))
                assertEquals(expected, LibraryIndependentRootGrants.inventory(app))
                LibraryIndependentRootGrants.refuseParentAccess(app, folder)
            }
            val scratch = File(app.filesDir.canonicalFile, "tree-read-${UUID.randomUUID()}").apply { check(mkdir()) }
            runBlocking {
                assertEquals(null, witness.completedMerge(app, operation))
                val inputs = witness.verifyPairWithSharedOwner(app, scratch, selected[0], selected[1], ::requireAuthority)
                assertEquals(selected, inputs.map { it.tree })
                assertTrue(inputs.all { it.albums == 1 && it.scans == 1 })
                val plan = witness.estimateMerge(app, scratch, inputs[0], inputs[1], ::requireAuthority)
                assertEquals(listOf("Synthetic.png", "Additional synthetic.png"), plan.names)
                assertEquals(0, plan.matchedOriginals)
                assertEquals(expectedMissing, plan.missingBytes)
                assertEquals(operation, witness.publishMerge(app, scratch, operation, inputs[0], inputs[1], ::requireAuthority))
                val reopened = witness.verifyPairWithSharedOwner(app, scratch, selected[0], selected[1], ::requireAuthority)
                assertEquals(operation, reopened[0].generationId)
                assertEquals(1, reopened[0].albums)
                assertEquals(2, reopened[0].scans)
                assertEquals(inputs[1].generationId, reopened[1].generationId)
                assertEquals(1, reopened[1].scans)
            }
            requireAuthority()
        }
        // Construct a new private reader only after the Files fixture grants are restored.
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        runBlocking { assertEquals(operation, witness.completedMerge(app, operation)) }
    }

    @Test fun selectedFilesMergeCanAppendTwiceWithoutReusingAnOlderStage() {
        val args = InstrumentationRegistry.getArguments()
        val first = UUID.fromString(requireNotNull(args.getString("librarySelectedAppendOperation"))).toString()
        val second = UUID.fromString(requireNotNull(args.getString("librarySelectedRepeatOperation"))).toString()
        val expectedMissing = requireNotNull(args.getString("librarySelectedAppendBytes")).toLong()
        check(first != second && expectedMissing in 70_000..71_000)
        val witness = LibraryDescriptorAncestryWitness
        lateinit var isolated: Context
        withIndependentFilesSelections(chooseAnchor = false, writableDestination = true) { app, folder, selected, before ->
            fun requireAuthority() {
                val expected = before + mapOf(selected[0].toString() to (true to true), selected[1].toString() to (true to false))
                assertEquals(expected, LibraryIndependentRootGrants.inventory(app))
                LibraryIndependentRootGrants.refuseParentAccess(app, folder)
            }
            // The earlier one-shot witness remains in the ordinary app-files root. Only this exact synthetic batch can use its fresh private scope.
            val files = File(app.filesDir.canonicalFile, "library-selected-repeat-$first").apply { check(mkdir()) }
            isolated = object : ContextWrapper(app) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = files
            }
            val scratch = File(files, "read-scratch").apply { check(mkdir()) }
            val destination = selected[0]
            val source = selected[1]
            fun openFirstStage(): ParcelFileDescriptor {
                val root = DocumentsContract.getTreeDocumentId(destination)
                val id = DocumentsContract.buildDocumentUriUsingTree(destination, "$root/merge-staging-$first")
                return checkNotNull(app.contentResolver.openFileDescriptor(id, "r"))
            }
            runBlocking {
                val initial = witness.verifyPairWithSharedOwner(isolated, scratch, destination, source, ::requireAuthority)
                assertEquals(1, initial[0].scans)
                assertEquals(expectedMissing, witness.estimateMerge(isolated, scratch, initial[0], initial[1], ::requireAuthority).missingBytes)
                assertEquals(first, witness.publishMerge(isolated, scratch, first, initial[0], initial[1], ::requireAuthority))
                val once = witness.verifyPairWithSharedOwner(isolated, scratch, destination, source, ::requireAuthority)
                assertEquals(first, once[0].generationId)
                assertEquals(2, once[0].scans)
                assertEquals(initial[1].generationId, once[1].generationId)
                openFirstStage().use { priorStage ->
                    val dryRun = witness.estimateMerge(isolated, scratch, once[0], once[1], ::requireAuthority)
                    assertEquals(0L, dryRun.missingBytes)
                    assertEquals(1, dryRun.matchedOriginals)
                    assertEquals(second, witness.publishMerge(isolated, scratch, second, once[0], once[1], ::requireAuthority))
                    openFirstStage().use { witness.requireSameIdentity(priorStage, it) }
                }
                val twice = witness.verifyPairWithSharedOwner(isolated, scratch, destination, source, ::requireAuthority)
                assertEquals(second, twice[0].generationId)
                assertEquals(1, twice[0].albums)
                assertEquals(2, twice[0].scans)
                assertEquals(initial[1].generationId, twice[1].generationId)
            }
            requireAuthority()
        }
        // A fresh reader of the exact private receipts does not require either of the fixture grants.
        runBlocking {
            assertEquals(first, witness.completedMerge(isolated, first))
            assertEquals(second, witness.completedMerge(isolated, second))
        }
    }

    @Test fun endsProcessAfterSelectedAppendCompletionBeforeDelivery() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val operation = UUID.fromString(requireNotNull(InstrumentationRegistry.getArguments()
            .getString("librarySelectedAppendOperation"))).toString()
        val witness = LibraryDescriptorAncestryWitness
        withIndependentFilesSelections(chooseAnchor = false, writableDestination = true) { app, folder, selected, before ->
            val grants = LibraryIndependentRootGrants
            fun requireAuthority() {
                assertEquals(before + mapOf(selected[0].toString() to (true to true), selected[1].toString() to (true to false)),
                    grants.inventory(app))
                grants.refuseParentAccess(app, folder)
            }
            val files = File(app.filesDir.canonicalFile, "library-selected-death-$operation").apply { check(mkdir()) }
            val scope = object : ContextWrapper(app) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = files
            }
            val scratch = File(files, "read-scratch").apply { check(mkdir()) }
            runBlocking {
                val inputs = witness.verifyPairWithSharedOwner(scope, scratch, selected[0], selected[1], ::requireAuthority)
                witness.publishMergeBeforeDelivery(scope, scratch, operation, inputs[0], inputs[1], ::requireAuthority) { generation ->
                    assertEquals(operation, generation)
                    requireAuthority()
                    // These two modes were issued only to this synthetic case. Restore them before deliberately ending the app process.
                    app.contentResolver.releasePersistableUriPermission(selected[0], Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    grants.releaseReadDelta(app, selected) { assertEquals(before, grants.inventory(app)) }
                    suspendCancellableCoroutine<Nothing> {
                        instrumentation.sendStatus(72, Bundle().apply {
                            putString("selectedAppendDeathOperation", operation)
                            putInt("selectedAppendDeathPid", Process.myPid())
                            putString("selectedAppendDeathGrantInventory", LibraryAncestryGrantWitness.fingerprint(before))
                        })
                        Process.killProcess(Process.myPid())
                    }
                }
            }
            error("The selected append must lose its process before delivering success")
        }
    }

    @Test fun oneFilesSelectionPublishesOnlyAClosedEditedCatalog() {
        val operation = requireNotNull(InstrumentationRegistry.getArguments().getString("librarySelectedAppendOperation"))
        require(UUID.fromString(operation).toString() == operation)
        withIndependentFilesSelections(chooseAnchor = false, writableDestination = true, singleDestination = true) { app, folder, selections, before ->
            val tree = selections.single()
            val grants = LibraryIndependentRootGrants
            fun requireAuthority() {
                assertEquals(before + (tree.toString() to (true to true)), grants.inventory(app))
                grants.refuseParentAccess(app, folder)
            }
            val files = File(app.filesDir.canonicalFile, "library-selected-editor-$operation").apply { check(mkdir()) }
            val isolated = object : ContextWrapper(app) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = files
            }
            val scratch = File(files, "scratch").apply { check(mkdir()) }
            val reader = AndroidLibraryTreeRead(isolated, scratch)
            val album = DocumentId("aaaaaaaa-aaaa-4aaa-8aaa-000000000001")
            val witness = LibraryDescriptorAncestryWitness
            runBlocking {
                val unbound = reader.verify(tree)
                val baseline = witness.verifyRecipeEditTarget(reader, tree)
                val page = reader.openWorkingCatalog(baseline) { it.load(album).pages.single() }
                assertEquals(2L, page.recipeRevision)
                val unboundError = runCatching {
                    witness.appendSelectedRecipeEdit(isolated, scratch, operation, unbound, album, page.pageId,
                        page.recipeRevision, 2) { error("An unbound verification attempted edit admission") }
                }.exceptionOrNull()
                assertTrue(unboundError is IOException)
                assertEquals("The selected catalog edit requires the confirmed physical root", unboundError?.message)
                val edited = witness.appendSelectedRecipeEdit(isolated, scratch, operation, baseline, album, page.pageId,
                    page.recipeRevision, 2, ::requireAuthority)
                assertEquals(operation, edited)
                val reopened = reader.verify(tree)
                assertEquals(operation, reopened.generationId)
                assertEquals(baseline.libraryId, reopened.libraryId)
                val current = reader.openWorkingCatalog(reopened) { it.load(album).pages.single() }
                assertEquals(page.pageId, current.pageId)
                assertEquals(page.sourceId, current.sourceId)
                assertEquals(3L, current.recipeRevision)
                val forbidProvider = object : ContextWrapper(isolated) {
                    override fun getApplicationContext(): Context = this
                    override fun getContentResolver(): ContentResolver = error("Spent edit attempted provider access")
                }
                val refusal = runCatching {
                    witness.appendSelectedRecipeEdit(forbidProvider, scratch, operation, baseline, album, page.pageId,
                        page.recipeRevision, 2) {}
                }.exceptionOrNull()
                assertTrue(refusal is IOException)
                assertEquals("The catalog revision attempt is already spent", refusal?.message)
                assertEquals(operation, reader.verify(tree).generationId)
                requireAuthority()
                // Remove only this test's write mode. The remaining read grant may inspect the successor, not nominate a new edit.
                app.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                assertEquals(before + (tree.toString() to (true to false)), grants.inventory(app))
                val readOnly = runCatching { witness.verifyRecipeEditTarget(reader, tree) }.exceptionOrNull()
                assertTrue(readOnly is IOException)
                assertEquals("The exact library folder is not writable", readOnly?.message)
            }
            assertFalse(File(files, "library-settings").exists())
            assertFalse(files.walkTopDown().any { it.name == "original.bin" })
        }
    }

    @Test fun selectedRecipeReceiptBindsTheExactIssuingActivation() {
        val operation = requireNotNull(InstrumentationRegistry.getArguments().getString("librarySelectedAppendOperation"))
        require(UUID.fromString(operation).toString() == operation)
        withIndependentFilesSelections(chooseAnchor = false, writableDestination = true, singleDestination = true) { app, folder, selections, before ->
            val tree = selections.single()
            val files = File(app.filesDir.canonicalFile, "library-selected-editor-$operation").apply { check(mkdir()) }
            val isolated = object : ContextWrapper(app) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = files
            }
            val scratch = File(files, "scratch").apply { check(mkdir()) }
            val witness = LibraryDescriptorAncestryWitness
            val album = DocumentId("aaaaaaaa-aaaa-4aaa-8aaa-000000000001")
            runBlocking {
                val activation = witness.seedRecipeEditBinding(isolated, scratch, tree)
                val startup = AndroidLibraryStartup(isolated)
                val page = startup.useSelectedCatalog(scratch) { _, catalog -> catalog.load(album).pages.single() }
                val activePointer = File(files, "library-settings/active-library")
                val expectedPointer = activePointer.readBytes().toList()
                val stale = activation.copy(number = activation.number - 1)
                val unused = File(files, "never-open-this-scratch")
                val forbidProvider = object : ContextWrapper(isolated) {
                    override fun getApplicationContext(): Context = this
                    override fun getContentResolver(): ContentResolver = error("Refused edit or private inspection attempted provider access")
                }
                assertEquals("library_activation_changed", (runCatching {
                    witness.publishBoundRecipeEdit(forbidProvider, unused, stale, operation, album, page.pageId, 2, 2)
                }.exceptionOrNull() as RepositoryFailure).error.safeMessageKey)
                assertFalse(unused.exists())
                assertFalse(File(files, "catalog-recipe-attempts-v1").exists())
                assertEquals(null, witness.observeInterruptedRecipeSuccessor(isolated, scratch, activation))
                val child = witness.publishBoundRecipeEdit(isolated, scratch, activation, operation, album, page.pageId, 2, 2)
                assertEquals(operation, child.generationId)
                assertEquals(activation, child.issuedAt)
                assertEquals(page.pageId, child.page)
                assertEquals(3L, child.revision)
                assertTrue(child.reopenWithoutProvider(forbidProvider))
                assertEquals(operation, witness.observeInterruptedRecipeSuccessor(isolated, scratch, activation))
                val staged = File(files, "catalog-recipe-attempts-v1/$operation")
                // This operation is created by this test, not retained predecessor evidence. Temporarily withhold one leaf without modifying its bytes.
                for (leaf in listOf(File(staged, "recipe-edit.complete"), File(files, "catalog-revision-spends/$operation.spent"))) {
                    val parked = File(files, "withheld-${leaf.name}-$operation").toPath()
                    val path = leaf.toPath()
                    val exactBytes = leaf.readBytes()
                    check(Files.notExists(parked) && Files.isRegularFile(path))
                    Files.move(path, parked, ATOMIC_MOVE)
                    try {
                        val failure = runCatching { witness.observeInterruptedRecipeSuccessor(isolated, scratch, activation) }.exceptionOrNull()
                        assertTrue(failure is IOException)
                        assertFalse(leaf.exists())
                        assertEquals(expectedPointer, activePointer.readBytes().toList())
                        assertEquals(before + (tree.toString() to (true to true)), LibraryIndependentRootGrants.inventory(app))
                    } finally {
                        check(Files.notExists(path))
                        Files.move(parked, path, ATOMIC_MOVE)
                    }
                    assertArrayEquals(exactBytes, leaf.readBytes())
                }
                val spent = runCatching {
                    witness.publishBoundRecipeEdit(forbidProvider, unused, activation, operation, album, page.pageId, 2, 2)
                }.exceptionOrNull()
                assertTrue(spent is IOException)
                assertEquals("The catalog revision attempt is already spent", spent?.message)
                assertEquals(expectedPointer, activePointer.readBytes().toList())
                assertEquals(operation, AndroidLibraryTreeRead(isolated, scratch).verify(tree).generationId)
                assertTrue(File(staged, "recipe-edit.intent").isFile)
                assertTrue(File(staged, "recipe-edit.complete").isFile)
                assertEquals(before + (tree.toString() to (true to true)), LibraryIndependentRootGrants.inventory(app))
                LibraryIndependentRootGrants.refuseParentAccess(app, folder)
                // The retained bound-only case deliberately leaves the predecessor active for its existing host oracle.
                if (InstrumentationRegistry.getArguments().getString("libraryRecoverSelectedRecipePointer") == "true") {
                    val advanced = witness.recoverInterruptedRecipeSuccessor(isolated, scratch, activation)
                    assertEquals(activation.copy(number = activation.number + 1), advanced)
                    val current = checkNotNull(advanced)
                    val reopened = AndroidLibraryStartup(isolated).loadSelectedEditablePage(scratch, current, album, page.pageId)
                    assertEquals(child.revision, reopened.recipeRevision)
                    assertEquals(2, reopened.recipe.geometry.outputQuarterTurns)
                    assertEquals(null, witness.recoverInterruptedRecipeSuccessor(isolated, scratch, current))
                    assertEquals(before + (tree.toString() to (true to true)), LibraryIndependentRootGrants.inventory(app))
                }
            }
        }
    }

    @Test fun selectedRecipeAdvancesOnlyTheVerifiedDirectSuccessor() {
        val operation = requireNotNull(InstrumentationRegistry.getArguments().getString("librarySelectedAppendOperation"))
        require(UUID.fromString(operation).toString() == operation)
        withIndependentFilesSelections(chooseAnchor = false, writableDestination = true, singleDestination = true) { app, folder, selections, before ->
            val tree = selections.single()
            val files = File(app.filesDir.canonicalFile, "library-selected-editor-$operation").apply { check(mkdir()) }
            val isolated = object : ContextWrapper(app) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = files
            }
            val scratch = File(files, "scratch").apply { check(mkdir()) }
            val witness = LibraryDescriptorAncestryWitness
            val album = DocumentId("aaaaaaaa-aaaa-4aaa-8aaa-000000000001")
            runBlocking {
                val old = witness.seedRecipeEditBinding(isolated, scratch, tree)
                val startup = AndroidLibraryStartup(isolated)
                val page = startup.useSelectedCatalog(scratch) { _, catalog -> catalog.load(album).pages.single() }
                val (advanced, saved) = witness.saveAndAdvanceSelectedRecipe(isolated, scratch, old, operation, album, page.pageId, 2, 2)
                assertEquals(old.copy(number = old.number + 1), advanced)
                assertEquals(page.pageId, saved.pageId)
                assertEquals(3L, saved.recipeRevision)
                val reopened = AndroidLibraryStartup(isolated)
                assertEquals(advanced, reopened.useSelectedCatalog(scratch) { activation, _ -> activation })
                val exact = reopened.loadSelectedEditablePage(scratch, advanced, album, page.pageId)
                assertEquals(saved.recipeRevision, exact.recipeRevision)
                assertEquals(saved.recipeHash, exact.recipeHash)
                assertEquals(2, exact.recipe.geometry.outputQuarterTurns)
                val obsolete = runCatching { reopened.loadSelectedEditablePage(scratch, old, album, page.pageId) }.exceptionOrNull()
                assertEquals("library_activation_changed", (obsolete as RepositoryFailure).error.safeMessageKey)
                assertEquals(before + (tree.toString() to (true to true)), LibraryIndependentRootGrants.inventory(app))
                LibraryIndependentRootGrants.refuseParentAccess(app, folder)
            }
        }
    }

    @Test fun opensSelectedAlbumGridAndVerifiedOriginalWhileBrowsing() {
        withIndependentFilesSelections(chooseAnchor = false, writableDestination = true, singleDestination = true) { app, _, selections, _ ->
            val tree = selections.single()
            val files = File(app.filesDir.canonicalFile, "library-open-fixture-${UUID.randomUUID()}").apply { check(mkdir()) }
            val isolated = object : ContextWrapper(app) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = files
            }
            val scratch = File(files, "scratch").apply { check(mkdir()) }
            val start = AndroidLibraryStartup(isolated)
            runBlocking {
                val previous = start.requireDefaultActivation()
                val preview = start.prepareOpenExistingFolder(scratch, previous, tree)
                assertEquals(previous, start.recordedDefaultActivation())
                assertEquals(1, preview.albums)
                val selected = start.openVerifiedExistingFolder(scratch, preview)
                assertEquals(previous.number + 1, selected.number)
                assertEquals(null, start.recordedDefaultActivation())
                val catalog = AndroidLibraryStartup(isolated).useSelectedCatalog(scratch) { active, current ->
                    assertEquals(selected, active)
                    current.list(null, 10)
                }
                assertEquals(1, catalog.single().pageCount)
                val services = ApplicationServices(isolated)
                val document = catalog.single().documentId
                val page = services.loadLibraryAlbum(selected, document).pages.single()
                val editor = EditorIdentity(document, page.pageId, selected)
                coroutineScope {
                    val originals = async { services.originalLibraryEditor(editor, page.sourceId.value).use { it.bitmap.getPixel(0, 0) } }
                    val metadata = async { services.loadLibraryEditorPage(editor) }
                    assertEquals(page.pageId, metadata.await().pageId)
                    assertEquals(0xff132b47.toInt(), originals.await())
                }
                val owner = SupervisorJob()
                val clicked = AtomicReference<EditorIdentity?>()
                try {
                    interruptionCompose.runOnUiThread {
                        val session = ImportLibrarySession(ApplicationLibraryActions(isolated, null, services), LibraryIdentity(),
                            CoroutineScope(owner + Dispatchers.Main.immediate))
                        val preferences = AtelierPreferences(isolated)
                        interruptionCompose.activity.setContent {
                            val state by session.state.collectAsStateWithLifecycle()
                            MaterialTheme {
                                AtelierLibraryScreen(session, state, preferences,
                                    { services.loadLibraryAlbum(selected, it) },
                                    { album, tile -> services.renderLibraryThumbnail(selected, album, tile) },
                                    onSettings = {},
                                    onEdit = { documentId, pageId -> clicked.set(EditorIdentity(documentId, pageId, selected)) },
                                )
                            }
                        }
                    }
                    fun awaitTag(tag: String) = interruptionCompose.waitUntil(25_000) {
                        interruptionCompose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
                    }
                    val albumTag = "atelier-album-${document.value}"
                    awaitTag(albumTag)
                    interruptionCompose.onNodeWithTag(albumTag).performClick()
                    val pageTag = "original-page-${page.pageId.value}"
                    interruptionCompose.waitUntil(25_000) {
                        interruptionCompose.onAllNodes(hasTestTag(pageTag)).fetchSemanticsNodes().isNotEmpty()
                    }
                    interruptionCompose.onNodeWithTag("atelier-add-pages").assertIsNotEnabled()
                    interruptionCompose.onNodeWithTag(pageTag).assertIsEnabled().performClick()
                    assertEquals(editor, clicked.get())
                } finally {
                    // As in EditorHostTest, stop the actual task rather than depend on ActivityScenario's API 28 helper Activity.
                    val host = interruptionCompose.activity
                    assertTrue(interruptionCompose.runOnUiThread { host.moveTaskToBack(true) })
                    owner.cancelAndJoin()
                    withTimeout(15_000) {
                        while (withContext(Dispatchers.Main) { host.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) } ||
                            services.budget.usedBytes != 0L) delay(20)
                    }
                    assertEquals(Lifecycle.State.CREATED, withContext(Dispatchers.Main) { host.lifecycle.currentState })
                }
                val accepted = services.loadLibraryEditorPage(editor)
                services.originalLibraryEditor(editor, page.sourceId.value).use { original ->
                    assertEquals(page.sourceId.value, original.sourceId)
                    assertEquals(accepted.recipe.geometry.source.sourceSize, original.inspection.normalizedSize)
                    assertEquals(0xff132b47.toInt(), original.bitmap.getPixel(0, 0))
                    assertEquals(0xff637b89.toInt(), original.bitmap.getPixel(2, 1))
                    assertTrue(services.budget.usedBytes > 0)
                }
                assertEquals(0L, services.budget.usedBytes)
                val foreign = runCatching { services.originalLibraryEditor(editor, UUID.randomUUID().toString()).close() }.exceptionOrNull()
                assertEquals("editor_page_unavailable", (foreign as RepositoryFailure).error.safeMessageKey)
                val repeated = runCatching { start.openVerifiedExistingFolder(scratch, preview) }.exceptionOrNull() as RepositoryFailure
                assertEquals("library_activation_changed", repeated.error.safeMessageKey)
                app.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                val writeRevoked = runCatching { services.originalLibraryEditor(editor, page.sourceId.value).close() }.exceptionOrNull()
                assertEquals("library_reconnect_required", (writeRevoked as RepositoryFailure).error.safeMessageKey)
                assertTrue(start.location().needsReconnect)
                app.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val unavailable = runCatching {
                    AndroidLibraryStartup(isolated).useSelectedCatalog(scratch) { _, _ -> error("A removed grant cannot open the catalog") }
                }.exceptionOrNull() as RepositoryFailure
                assertEquals("library_reconnect_required", unavailable.error.safeMessageKey)
                val deniedOriginal = runCatching { services.originalLibraryEditor(editor, page.sourceId.value).close() }.exceptionOrNull()
                assertEquals("library_reconnect_required", (deniedOriginal as RepositoryFailure).error.safeMessageKey)
                assertEquals(0L, services.budget.usedBytes)
                assertEquals(null, start.recordedDefaultActivation())
            }
        }
    }

    @Test fun startEmptyRefusesANestedStageOfTheCurrentFilesLibrary() {
        val leaf = requireNotNull(InstrumentationRegistry.getArguments().getString("libraryNestedEmptyStage"))
        require(leaf.startsWith("merge-staging-") && UUID.fromString(leaf.removePrefix("merge-staging-")).toString() ==
            leaf.removePrefix("merge-staging-"))
        withIndependentFilesSelections(chooseAnchor = false, writableDestination = true, nestedEmptyLeaf = leaf) { app, _, selections, _ ->
            val (current, nested) = selections
            val files = File(app.filesDir.canonicalFile, "library-nested-start-${UUID.randomUUID()}").apply { check(mkdir()) }
            val isolated = object : ContextWrapper(app) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = files
            }
            val scratch = File(files, "scratch").apply { check(mkdir()) }
            val startup = AndroidLibraryStartup(isolated)
            fun contents(): Int {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(nested, DocumentsContract.getTreeDocumentId(nested))
                return checkNotNull(app.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null))
                    .use { it.count }
            }
            runBlocking {
                val before = LibraryDescriptorAncestryWitness.seedRecipeEditBinding(isolated, scratch, current)
                val pointer = File(files, "library-settings/active-library")
                val retained = pointer.readBytes().toList()
                assertEquals(0, contents())
                val preview = startup.prepareFolder(scratch, before, nested) as AndroidLibraryStartup.FolderPreview.Empty
                val refusal = runCatching { startup.startVerifiedEmptyFolder(scratch, preview) }.exceptionOrNull()
                assertTrue(refusal is IOException)
                assertEquals("The selected library folders have no usable common-ancestry hint", refusal?.message)
                assertEquals(retained, pointer.readBytes().toList())
                assertEquals(0, contents())
                assertEquals(before, startup.useSelectedCatalog(scratch) { activation, _ -> activation })
            }
        }
    }

    @Test fun unconfirmedDefaultGraphCloseFencesBothSuccessorAndReturn() {
        withIndependentFilesSelections(chooseAnchor = false, writableDestination = true, singleDestination = true) { app, _, selections, _ ->
            val tree = selections.single()
            val isolated = File(app.filesDir.canonicalFile, "library-cutover-observer-${UUID.randomUUID()}").apply { check(mkdir()) }
            val files = File(isolated, "files").apply { check(mkdir()) }
            val databases = File(isolated, "databases").apply { check(mkdir()) }
            val context = object : ContextWrapper(app) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = files
                override fun getDatabasePath(name: String): File = File(databases, name)
            }
            runBlocking {
                val services = ApplicationServices(context)
                val graph = services.imports()
                val issuing = graph.activation
                graph.store.documents.createDocument("Retained Default graph witness")
                val preview = services.inspectLibraryFolder(issuing, tree)
                // Hold the real store through its Room invalidation collector, without starting or exposing any OCR job.
                val ready = CompletableDeferred<Unit>()
                val observer = launch {
                    graph.store.savedOcr.observeSearchChanges().collect {
                        ready.complete(Unit)
                        awaitCancellation()
                    }
                }
                try {
                    withTimeout(15_000) { ready.await() }
                    val failedClose = runCatching { services.selectLibraryFolder(issuing, preview) {} }.exceptionOrNull() as RepositoryFailure
                    assertEquals("ordinary_export_store_in_use", failedClose.error.safeMessageKey)
                    val selected = services.storageLocation()
                    assertEquals(issuing.number + 1, selected.activation.number)
                    assertNotNull(selected.folderLabel)
                    val startup = runCatching { services.openLibraryPresentation() }.exceptionOrNull() as RepositoryFailure
                    assertEquals("library_graph_close_unconfirmed", startup.error.safeMessageKey)
                    val returnToDefault = runCatching { services.returnToDefaultLibrary(selected.activation) }.exceptionOrNull() as RepositoryFailure
                    assertEquals("library_graph_close_unconfirmed", returnToDefault.error.safeMessageKey)
                    assertEquals(selected, services.storageLocation())
                } finally {
                    observer.cancelAndJoin()
                    graph.close()
                }
            }
        }
    }

    @Test fun selectedEditorDisplaysActualOriginalAndFrameWithoutWrite() {
        withIndependentFilesSelections(chooseAnchor = false, writableDestination = true, singleDestination = true) { app, _, selections, _ ->
            val tree = selections.single()
            val files = File(app.filesDir.canonicalFile, "library-editor-controls-${UUID.randomUUID()}").apply { check(mkdir()) }
            val isolated = object : ContextWrapper(app) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = files
            }
            val services = ApplicationServices(isolated)
            runBlocking {
                val startup = AndroidLibraryStartup(isolated)
                val previous = startup.requireDefaultActivation()
                val scratch = File(files, "scratch").apply { check(mkdir()) }
                val preview = startup.prepareOpenExistingFolder(scratch, previous, tree)
                val selected = startup.openVerifiedExistingFolder(scratch, preview)
                val album = startup.useSelectedCatalog(scratch) { _, catalog -> catalog.list(null, 10).single() }
                val page = services.loadLibraryAlbum(selected, album.documentId).pages.single()
                val identity = EditorIdentity(album.documentId, page.pageId, selected)
                val framePixel = AtomicReference<Int?>(null)
                val exited = java.util.concurrent.atomic.AtomicBoolean()
                val model = interruptionCompose.runOnUiThread {
                    val factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                            EditorViewModel(services) { _, _, _ -> error("A preview-only route cannot publish a saved edit") } as T
                    }
                    val editor = ViewModelProvider(interruptionCompose.activity, factory)[EditorViewModel::class.java]
                    editor.open(identity)
                    interruptionCompose.activity.setContent {
                        val session by editor.session.collectAsStateWithLifecycle()
                        MaterialTheme {
                            session?.let {
                                PageEditorScreen(allowSave = false, loadOriginal = { source ->
                                    services.originalLibraryEditor(identity, source).also { preview ->
                                        framePixel.set(preview.bitmap.getPixel(0, 0))
                                    }
                                }).Content(it) { exited.set(true) }
                            }
                        }
                    }
                    editor
                }
                try {
                    interruptionCompose.waitUntil(25_000) { model.session.value?.preview?.value?.display != null }
                    assertEquals(identity, model.state.value.identity)
                    interruptionCompose.onNodeWithTag("editor-tool-original").performClick()
                    interruptionCompose.waitUntil(25_000) {
                        model.session.value?.preview?.value?.let { it.view == EditorView.ORIGINAL && !it.previous && it.display != null } == true
                    }
                    interruptionCompose.runOnUiThread {
                        val bitmap = checkNotNull(model.session.value?.preview?.value?.display).bitmap
                        assertEquals(3, bitmap.width)
                        assertEquals(2, bitmap.height)
                        assertEquals(0xff637b89.toInt(), bitmap.getPixel(2, 1))
                    }
                    interruptionCompose.onNodeWithTag("editor-tool-shape").performClick()
                    interruptionCompose.onNodeWithTag("editor-shape-perspective").performScrollTo().performClick()
                    interruptionCompose.waitUntil(25_000) { framePixel.get() == 0xff132b47.toInt() }
                    interruptionCompose.onNodeWithTag("atelier-frame-canvas").assertExists()
                    interruptionCompose.onNodeWithTag("editor-apply").assertIsNotEnabled()
                    interruptionCompose.onNodeWithTag("editor-back").performClick()
                    interruptionCompose.onNodeWithTag("editor-apply-exit").assertDoesNotExist()
                    interruptionCompose.onNodeWithTag("editor-discard-exit").performClick()
                    interruptionCompose.waitUntil(15_000) { exited.get() }
                    assertEquals(2L, services.loadLibraryEditorPage(identity).recipeRevision)
                    assertEquals(selected, services.storageLocation().activation)
                } finally {
                    interruptionCompose.runOnUiThread { model.closeEditor() }
                    interruptionCompose.waitUntil(15_000) { services.budget.usedBytes == 0L }
                }
            }
        }
    }

    private fun withIndependentFilesSelections(chooseAnchor: Boolean, writableDestination: Boolean = false, singleDestination: Boolean = false,
        nestedEmptyLeaf: String? = null,
        use: (Context, String, List<Uri>, Map<String, Pair<Boolean, Boolean>>) -> Unit,
    ) {
        check(!writableDestination || !chooseAnchor)
        check(!singleDestination || writableDestination)
        check(nestedEmptyLeaf == null || (!chooseAnchor && writableDestination && !singleDestination))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext
        val folder = requireNotNull(InstrumentationRegistry.getArguments().getString("libraryFixtureFolder"))
        val witness = LibraryIndependentRootGrants
        val paths = witness.paths(folder)
        val nested = nestedEmptyLeaf?.let { "${paths.first()}/$it" }
        val all = (paths + listOfNotNull(nested)).flatMap(witness::spellings)
        val before = witness.inventory(app)
        if (!chooseAnchor) {
            // This witness is pinned to the API 28 guest. Admit only a sparse starting inventory; never ask Android to prune someone else's grant to make room.
            check(Build.VERSION.SDK_INT == 28 && before.size <= 8) { "Synthetic selections need a sparse API 28 grant inventory" }
            witness.refuseParentAccess(app, folder)
        }
        check(all.none { it.toString() in before })
        InstrumentationRegistry.getArguments().getString("libraryPriorIndependentFolder")?.let { old ->
            witness.paths(old) // Reuse the same exact fixture-folder syntax guard.
            val oldPaths = listOf(old, "$old/Library-Left", "$old/Library-Right")
            val oldTrees = oldPaths.flatMap(witness::spellings)
            check(old != folder && oldTrees.none { it.toString() in before }) { "An earlier synthetic picker grant needs reconciliation" }
            witness.refuseEffectiveWrite(app, oldTrees)
        }
        witness.requireNewRecord(app, folder)
        witness.refuseEffectiveWrite(app, all)
        val selected = ArrayList<Uri>(paths.size)
        fun isWritable(index: Int) = writableDestination && (index == 0 || nested != null)
        fun expectedModes() = selected.mapIndexed { index, uri -> uri.toString() to (true to isWritable(index)) }.toMap()
        var handedOff = false
        try {
            val targets = if (nested != null) listOf(paths.first(), nested) else if (chooseAnchor) paths else paths.take(if (singleDestination) 1 else 2)
            for ((index, path) in targets.withIndex()) {
                val result = AtomicReference<ActivityResult>()
                val platform = LibraryFolderPickerContract(writable = isWritable(index))
                val (picker, launcher) = interruptionCompose.runOnUiThread {
                    val host = interruptionCompose.activity
                    val intent = platform.createIntent(host, Unit).putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                        DocumentsContract.buildDocumentUri(witness.AUTHORITY, "primary:Documents/$path"))
                    val launch = host.activityResultRegistry.register("library-independent-root-$index",
                        ActivityResultContracts.StartActivityForResult()) { result.set(it) }
                    launch.launch(intent)
                    requireNotNull(intent.component).packageName to launch
                }
                try {
                    val anchor = path == folder
                    if (anchor) {
                        selectSettingsDestination(instrumentation, picker, folder, navigateFromRoot = false,
                            expectedChildren = paths.take(2).map { it.substringAfterLast('/') }.toSet(), allowExactWindowTitle = true)
                    } else {
                         selectSettingsDestination(instrumentation, picker, path.substringAfterLast('/'),
                             navigateFromRoot = false, expectedChildren = if (path == nested) emptySet() else setOf("catalogs", "generations", "objects"),
                             allowExactWindowTitle = true)
                    }
                    interruptionCompose.waitUntil(10_000) { result.get() != null }
                    val raw = requireNotNull(result.get())
                    val picked = platform.parseResult(raw.resultCode, raw.data) as? LibraryFolderPickerContract.Picked
                        ?: error("Files did not return the expected synthetic tree")
                    check(picked.uri in witness.spellings(path))
                    check(witness.inventory(app) == before + expectedModes())
                    // Remember the exact previously absent URI before the platform persistence call; reconcile on failure.
                    selected += picked.uri
                    val handoff = LibraryFolderGrantHandoff(app)
                    val exact = if (isWritable(index)) handoff.persistWrite(picked) else handoff.persistRead(picked)
                    assertEquals(picked.uri, exact)
                    check(witness.inventory(app) == before + expectedModes())
                } catch (failure: Throwable) {
                    if (result.get() == null && instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == picker) {
                        try {
                            cancelExactFiles(instrumentation, picker, app.packageName) { timeout, condition ->
                                interruptionCompose.waitUntil(timeout, condition)
                            }
                            interruptionCompose.waitUntil(5_000) { result.get() != null }
                            assertEquals(Activity.RESULT_CANCELED, result.get().resultCode)
                        } catch (cancelFailure: Throwable) {
                            failure.addSuppressed(cancelFailure)
                        }
                    }
                    throw failure
                } finally {
                    interruptionCompose.runOnUiThread { launcher.unregister() }
                }
            }
            use(app, folder, selected, before)
            handedOff = chooseAnchor
        } finally {
            if (!handedOff) {
                try {
                    for ((index, destination) in selected.withIndex()) {
                        if (isWritable(index) && witness.inventory(app)[destination.toString()]?.second == true) {
                            // This URI was absent from the entire prior inventory and only this exact synthetic picker asked for its write mode.
                            app.contentResolver.releasePersistableUriPermission(destination, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        }
                    }
                } finally {
                    witness.releaseReadDelta(app, selected) { assertEquals(before, witness.inventory(app)) }
                }
            }
        }
    }

    @Test fun actualFilesCapacityRefusesBeforePublication() = verifyJourney(probeCapacityOnly = true)

    @Test fun grantedTreeCanStageAndPublishOneSyntheticFileWithoutOverwriting() = verifyJourney(probeWrite = true)

    @Test fun defaultLibraryExportsItsOriginalAndAcceptedEditIntoANewFilesBackup() = verifyJourney(exportBackup = true)

    @Test fun endsProcessAfterBackupCommitBeforeDelivery() = verifyJourney(backupDeath = backupDeathOperation())

    @Test fun endsProcessAfterPayloadBeforeTheCompletionMarker() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext
        val folder = requireNotNull(InstrumentationRegistry.getArguments().getString("libraryFixtureFolder"))
        check(folder.matches(Regex("PristinePage-tree-[0-9a-f]{12}")))
        val operation = backupDeathOperation()
        val authority = "com.android.externalstorage.documents"
        val expectedId = "primary:Documents/$folder"
        val exactTrees = listOf(expectedId, "home:$folder").map { DocumentsContract.buildTreeDocumentUri(authority, it) }
        fun grants() = app.contentResolver.persistedUriPermissions.associate { it.uri.toString() to (it.isReadPermission to it.isWritePermission) }
        val before = grants()
        check(exactTrees.none { it.toString() in before })
        for (tree in exactTrees) {
            assertEquals(PackageManager.PERMISSION_DENIED,
                app.checkUriPermission(tree, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        }
        val result = AtomicReference<ActivityResult>()
        val platform = LibraryFolderPickerContract(writable = true)
        val picker = interruptionCompose.runOnUiThread {
            val host = interruptionCompose.activity
            val intent = platform.createIntent(host, Unit).putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri(authority, expectedId))
            host.activityResultRegistry.register("library-pre-marker-witness", ActivityResultContracts.StartActivityForResult()) {
                result.set(it)
            }.launch(intent)
            requireNotNull(intent.component).packageName
        }
        interruptionCompose.waitUntil(12_000) { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == picker }
        selectSettingsDestination(instrumentation, picker, folder, navigateFromRoot = false)
        interruptionCompose.waitUntil(10_000) { result.get() != null }
        val raw = requireNotNull(result.get())
        val picked = platform.parseResult(raw.resultCode, raw.data) as? LibraryFolderPickerContract.Picked
            ?: error("Files did not return a writable synthetic folder")
        assertTrue(picked.uri in exactTrees && picked.grant.returnedWrite)
        assertEquals(before, grants())
        runBlocking {
            LibraryDefaultFolderExportProbe.verify(app, picked, operation = operation, beforeMarker = { library, generation, bindings ->
                assertEquals(before, grants())
                instrumentation.sendStatus(72, Bundle().apply {
                    putString("backupDeathOperation", operation)
                    putInt("backupDeathPid", Process.myPid())
                    putString("backupDeathReceipt", "$library:$generation")
                    putString("backupDeathBindings", bindings.joinToString(":"))
                    putString("backupDeathGrantInventory", LibraryAncestryGrantWitness.fingerprint(before))
                })
                Process.killProcess(Process.myPid())
                // Never return from this boundary and create a marker if termination is unexpectedly delayed.
                CountDownLatch(1).await(10, TimeUnit.SECONDS)
                error("The process did not terminate at the pre-marker checkpoint")
            })
        }
        error("The publication did not stop before creating its marker")
    }

    @Test fun checksTheBackupDeathPickerRouteWithoutSelection() = verifyJourney(backupDeathPickerOnly = true)

    private fun backupDeathOperation() =
        InstrumentationRegistry.getArguments().getString("libraryBackupDeathOperation")?.let {
            it.also { value -> require(UUID.fromString(value).toString() == value) }
        } ?: error("No exclusive synthetic backup death operation")

    @Test fun durableFolderSelectionReopensExactCatalogAndRefusesRevocation() {
        lateinit var context: Context
        lateinit var spool: File
        lateinit var pointer: File
        verifyJourney(revokeAfterProbe = { tree ->
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            val retained = pointer.readBytes()
            val late = assertThrows(RepositoryFailure::class.java) {
                runBlocking {
                    AndroidLibraryStartup(context).useSelectedCatalog(spool) { _, catalog ->
                        assertEquals(1, catalog.list(null, 2).size)
                        app.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        "Unpublishable after grant loss"
                    }
                }
            }
            assertEquals("library_reconnect_required", late.error.safeMessageKey)
            assertTrue(retained.contentEquals(pointer.readBytes()))
            assertTrue(spool.list().orEmpty().isEmpty())
        }) { resolver, tree, _, selected, scratch ->
            val files = File(scratch, "selection-fixture").apply { check(mkdir()) }
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            context = object : ContextWrapper(app) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = files
            }
            spool = File(files, "verify").apply { check(mkdir()) }
            val notSelected = assertThrows(RepositoryFailure::class.java) {
                runBlocking { AndroidLibraryStartup(context).useSelectedCatalog(spool) { _, _ -> error("Unexpected selection") } }
            }
            assertEquals("library_folder_not_selected", notSelected.error.safeMessageKey)
            assertEquals(listOf("verify"), files.list().orEmpty().toList())
            val settings = File(files, "library-settings").apply { check(mkdir()) }
            val markerDir = File(settings, "folder-verifications").apply { check(mkdir()) }
            val grantDir = File(settings, "folder-grants").apply { check(mkdir()) }
            val grantId = "aaaaaaaa-aaaa-4aaa-8aaa-000000000011"
            val generation = selected.generationId
            val grant = checksummed("pristinepage-library-grant-v1\n$grantId\n1\n$tree\n")
            File(grantDir, grantId).writeBytes(grant)
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val markerUri = DocumentsContract.buildDocumentUriUsingTree(tree, "$rootId/generations/$generation.complete")
            val marker = checkNotNull(resolver.openInputStream(markerUri)).use { input ->
                val result = ByteArrayOutputStream()
                repeat(256) {
                    val next = input.read()
                    if (next == -1) return@use result.toByteArray()
                    result.write(next)
                }
                error("Expected a bounded generation marker")
            }
            pointer = File(settings, "active-library")
            val support = File(markerDir, "$grantId-$generation")
            fun selectWith(markerBytes: ByteArray) {
                support.writeBytes(markerBytes)
                pointer.writeBytes(checksummed("pristinepage-active-library-v2\n${selected.libraryId}\n2\n$grantId\n$generation\n${sha256(grant + markerBytes)}\n"))
            }
            selectWith(marker)
            runBlocking {
                val album = DocumentId("aaaaaaaa-aaaa-4aaa-8aaa-000000000001")
                val startup = AndroidLibraryStartup(context)
                val expected = LibraryActivation(selected.libraryId, 2)
                val listed = startup.useSelectedCatalog(spool) { _, catalog -> catalog.load(album).pages.single() }
                val reopened = startup.loadSelectedEditablePage(spool, expected, album, listed.pageId)
                assertEquals(album, reopened.documentId)
                assertEquals(listed.pageId, reopened.pageId)
                assertEquals(listed.sourceId, reopened.sourceId)
                assertEquals(2L, reopened.recipeRevision)
                // Revision 2's literal digest from the independent host fixture, not a hash recomputed with the app's codec.
                assertEquals("0d0d5f0f7fe4177c302382deebd3ac931e9102137593dabeb50b27977b53222d", reopened.recipeHash)
                assertTrue(spool.list().orEmpty().isEmpty())
                val unavailableScratch = File(files, "no-stale-editor-scratch")
                val obsoleteEditor = runCatching {
                    startup.loadSelectedEditablePage(unavailableScratch, expected.copy(number = 1), album, listed.pageId)
                }.exceptionOrNull() as RepositoryFailure
                assertEquals("library_activation_changed", obsoleteEditor.error.safeMessageKey)
                assertFalse(unavailableScratch.exists())
                assertTrue(spool.list().orEmpty().isEmpty())
                repeat(2) {
                    AndroidLibraryStartup(context).useSelectedCatalog(spool) { activation, catalog ->
                        assertEquals(LibraryActivation(selected.libraryId, 2), activation)
                        assertEquals(2L, catalog.load(album).pages.single().recipeRevision)
                        assertFalse(files.walkTopDown().any { it.name == "original.bin" })
                    }
                }
                val stale = runCatching {
                    AndroidLibraryStartup(context).useSelectedCatalog(spool) { _, catalog ->
                        pointer.writeBytes(checksummed("pristinepage-active-library-v1\n${selected.libraryId}\n3\n-\n-\n"))
                        try {
                            val obsolete = runCatching { catalog.load(album) }.exceptionOrNull() as RepositoryFailure
                            assertEquals("library_reconnect_required", obsolete.error.safeMessageKey)
                        } finally { selectWith(marker) }
                    }
                }.exceptionOrNull() as RepositoryFailure
                assertEquals("library_reconnect_required", stale.error.safeMessageKey)
                val wrong = marker.copyOf()
                wrong[wrong.lastIndex - 1] = if (wrong[wrong.lastIndex - 1] == '0'.code.toByte()) '1'.code.toByte() else '0'.code.toByte()
                selectWith(wrong)
                val refused = runCatching {
                    AndroidLibraryStartup(context).useSelectedCatalog(spool) { _, _ -> error("Changed recorded catalog crossed admission") }
                }.exceptionOrNull() as RepositoryFailure
                assertEquals("library_reconnect_required", refused.error.safeMessageKey)
                selectWith(marker)
            }
        }
        val retained = pointer.readBytes()
        val lost = assertThrows(RepositoryFailure::class.java) {
            runBlocking { AndroidLibraryStartup(context).useSelectedCatalog(spool) { _, _ -> error("Revoked folder returned a catalog") } }
        }
        assertEquals("library_reconnect_required", lost.error.safeMessageKey)
        assertTrue(retained.contentEquals(pointer.readBytes()))
    }

    private fun checksummed(body: String): ByteArray {
        val bytes = body.toByteArray(Charsets.US_ASCII)
        return bytes + "${sha256(bytes)}\n".toByteArray(Charsets.US_ASCII)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun populatingAWorkingRoomCatalogDoesNotMaterializeTheOriginal() = verifyJourney { _, _, reader, selected, scratch ->
        runBlocking {
            reader.openWorkingCatalog(selected) { catalog ->
                val sourceAlbum = DocumentId("aaaaaaaa-aaaa-4aaa-8aaa-000000000001")
                val album = catalog.load(sourceAlbum)
                assertEquals("Synthetic folder test", album.document.title)
                assertEquals(2L, album.document.documentRevision)
                assertEquals(1, album.pages.size)
                assertEquals(2L, album.pages.single().recipeRevision)
                assertNotNull(scratch.walkTopDown().firstOrNull { it.name == "working.sqlite" })
                assertFalse(scratch.walkTopDown().any { it.name == "original.bin" || it.name == "objects" })
            }
            repeat(4) {
                val canceled = runCatching {
                    reader.openWorkingCatalog(selected) { throw CancellationException("Canceled after the catalog opened") }
                }
                assertTrue(canceled.exceptionOrNull() is CancellationException)
            }
            reader.openWorkingCatalog(selected) { catalog -> assertEquals(1, catalog.list(null, 2).size) }
        }
        assertTrue("Only this attempt's own working catalog is retired", scratch.list().orEmpty().isEmpty())
    }

    private fun verifyJourney(
        probeWrite: Boolean = false,
        exportBackup: Boolean = false,
        backupDeath: String? = null,
        backupDeathPickerOnly: Boolean = false,
        holdReadForColdAncestry: Boolean = false,
        probeCapacityOnly: Boolean = false,
        revokeAfterProbe: ((Uri) -> Unit)? = null,
        probe: ((ContentResolver, Uri, AndroidLibraryTreeRead, AndroidLibraryTreeRead.Verified, File) -> Unit)? = null,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext
        val folder = InstrumentationRegistry.getArguments().getString("libraryFixtureFolder")
            ?.takeIf { it.matches(Regex("PristinePage-tree-[0-9a-f]{12}")) } ?: error("No exact synthetic folder was staged")
        val arguments = InstrumentationRegistry.getArguments()
        val originalDigest = arguments.getString("libraryFixtureOriginal")?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            ?: error("No populated synthetic folder was staged")
        val expected = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Documents/$folder")
        val home = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "home:$folder")
        val exactFixtureTrees = setOf(expected.toString(), home.toString())
        val existing = { app.contentResolver.persistedUriPermissions.associate { it.uri.toString() to (it.isReadPermission to it.isWritePermission) } }
        val before = existing()
        assertFalse(before.keys.any { it in exactFixtureTrees })
        if (probeCapacityOnly) {
            for (fixtureTree in listOf(expected, home)) {
                assertEquals("The synthetic fixture already has effective write access", PackageManager.PERMISSION_DENIED,
                    app.checkUriPermission(fixtureTree, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            }
        }
        val isolatedWritableRoute = backupDeath != null || backupDeathPickerOnly || probeCapacityOnly
        val platform = LibraryFolderPickerContract(writable = isolatedWritableRoute)
        val result = AtomicReference<ActivityResult>()
        val receipt = CountDownLatch(1)
        val registration = compose.runOnUiThread {
            val activity = compose.activity
            val intent = platform.createIntent(activity, Unit).putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri(requireNotNull(expected.authority), DocumentsContract.getTreeDocumentId(expected)))
            val launcher = activity.activityResultRegistry.register("library-files-native-witness", ActivityResultContracts.StartActivityForResult()) {
                result.set(it)
                receipt.countDown()
            }
            launcher.launch(intent)
            (requireNotNull(intent.component).packageName) to launcher
        }
        var readHandedOff = false
        try {
            val picker = registration.first
            if (isolatedWritableRoute) {
                compose.waitUntil(12_000) { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == picker }
                selectSettingsDestination(instrumentation, picker, folder, selectFolder = !backupDeathPickerOnly, navigateFromRoot = false)
                if (backupDeathPickerOnly) {
                    cancelExactFiles(instrumentation, picker, app.packageName)
                    compose.waitUntil(10_000) { receipt.count == 0L }
                    val canceled = requireNotNull(result.get())
                    assertEquals(LibraryFolderPickerContract.Canceled, platform.parseResult(canceled.resultCode, canceled.data))
                    assertEquals(before, existing())
                    return
                }
            } else {
                // Files may briefly show navigation controls before resolving the initial URI. Do not open the drawer during that transition.
                val labels = setOf("SELECT", "Select", "SÉLECTIONNER", "Sélectionner", "Use this folder", "USE THIS FOLDER", "Choose this folder")
                val choice = await(instrumentation, picker, "exact fixture and affirmative action") {
                    it.hasText(folder) && it.hasText("catalogs") && it.hasText("generations") && labels.any { name -> it.find(name) != null }
                }
                val button = labels.firstNotNullOfOrNull { choice.find(it) } ?: error("Files did not expose a folder confirmation")
                tap(instrumentation, button)
                if (!receipt.await(4, TimeUnit.SECONDS)) {
                    val confirm = await(instrumentation, picker, "optional Files confirmation") { it.allowButton() != null }
                    tap(instrumentation, confirm.allowButton() ?: error("No Files confirmation"))
                }
            }
            assertTrue("Files did not return a selection", receipt.await(10, TimeUnit.SECONDS))
            val raw = requireNotNull(result.get())
            val selection = platform.parseResult(raw.resultCode, raw.data)
            val picked = selection as? LibraryFolderPickerContract.Picked ?: error(
                "Files returned $selection; result=${raw.resultCode}; flags=${raw.data?.flags}; " +
                    "exactTree=${raw.data?.data?.toString() == expected.toString()}; clipItems=${raw.data?.clipData?.itemCount}; " +
                    "clipMatches=${raw.data?.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri?.toString() == expected.toString()}; " +
                    resultShape(raw.data?.data, expected, folder),
            )
            assertTrue("Files returned a tree other than the exact synthetic directory", picked.uri.toString() in exactFixtureTrees)
            if (arguments.getString("libraryExpectHome") == "1") {
                assertTrue("Files did not return the exact Documents namespace", picked.uri.toString() == home.toString())
            }
            if (probeCapacityOnly) {
                assertTrue("Files did not return write permission for the synthetic capacity probe", picked.grant.returnedWrite)
                assertEquals(before, existing())
                runBlocking { LibraryBackupCapacityWitness.verifyRealProviderRefusal(app, picked.uri) }
                assertEquals(before, existing())
                return
            }
            if (backupDeath != null) {
                assertTrue(before.isEmpty())
                assertEquals(before, existing())
                runBlocking {
                    LibraryDefaultFolderExportProbe.verify(app, picked, operation = backupDeath, beforeDelivery = { completed, bindings ->
                        // No receiver is resumed or fallback exception thrown after arming death.
                        suspendCancellableCoroutine<Nothing> {
                            instrumentation.sendStatus(71, Bundle().apply {
                                putString("backupDeathOperation", completed.operationId)
                                putInt("backupDeathPid", Process.myPid())
                                putString("backupDeathReceipt", "${completed.libraryId}:${completed.generationId}")
                                putString("backupDeathBindings", bindings.joinToString(":"))
                            })
                            Process.killProcess(Process.myPid())
                        }
                    })
                }
                error("The publication must lose its process before delivering success")
            }
            if (exportBackup) {
                val temporary = AndroidLibraryWritableFolder(app)
                val other = DocumentsContract.buildTreeDocumentUri(requireNotNull(picked.uri.authority), "home:${folder}-other")
                assertTrue(runBlocking { temporary.availableFromFilesResult(picked.uri) } > 0)
                val sibling = assertThrows(IOException::class.java) { runBlocking { temporary.availableFromFilesResult(other) } }
                assertEquals("The exact library folder is not writable", sibling.message)
                var checks = 0
                val drift = object : ContextWrapper(app) {
                    override fun getApplicationContext(): Context = this
                    override fun checkUriPermission(uri: Uri, pid: Int, uid: Int, modeFlags: Int): Int {
                        assertEquals(picked.uri, uri)
                        // Checks 1–2 guard directory query; 3 guards open, and 4 must guard delivery of that descriptor.
                        return if (++checks >= 4) PackageManager.PERMISSION_DENIED else super.checkUriPermission(uri, pid, uid, modeFlags)
                    }
                }
                val revoked = assertThrows(IOException::class.java) {
                    runBlocking { AndroidLibraryWritableFolder(drift).availableFromFilesResult(picked.uri) }
                }
                assertEquals("The exact library folder is not writable", revoked.message)
                assertEquals(4, checks)
                assertEquals("Checking temporary write access must not persist any permission", before, existing())
            }
            val handoff = LibraryFolderGrantHandoff(app)
            val uri = handoff.persistRead(picked)
            assertEquals(before + (uri.toString() to (true to false)), existing())
            if (holdReadForColdAncestry) {
                compose.runOnUiThread { registration.second.unregister() }
                LibraryAncestryGrantWitness.save(app, folder, uri, before)
                readHandedOff = true
                return
            }
            // API 28 may expose filesDir through /data/user/0 rather than the canonical app-owned storage path.
            val scratch = File(app.filesDir.canonicalFile, "tree-read-${UUID.randomUUID()}").apply { check(mkdir()) }
            val first = runBlocking { AndroidLibraryTreeRead(app, scratch).verify(uri) }
            assertEquals("9e940d7b-76c7-45ea-b5e4-0ad86f26ea13", first.libraryId)
            assertEquals("25c49d9b-02d8-418f-b982-154980d89cfb", first.generationId)
            assertEquals(1, first.albums)
            assertEquals(1, first.scans)
            // Promote only this exact fixture grant while the original picker recipient still owns its transient write permission.
            val writeObservation = if (probeWrite) runCatching {
                val destination = AndroidLibraryWritableFolder(app)
                assertThrows(IOException::class.java) { runBlocking { destination.availableBytes(uri) } }
                LibraryProviderWriteProbe.verify(app.contentResolver, uri) {
                    handoff.persistWrite(picked).also {
                        assertTrue(runBlocking { destination.availableBytes(it) } > 0)
                        val alias = if (it == home) expected else home
                        assertThrows(IOException::class.java) { runBlocking { destination.availableBytes(alias) } }
                        assertTrue(runBlocking { destination.availableBytes(it) } > 0)
                    }
                }
                assertThrows(IOException::class.java) { runBlocking { destination.availableBytes(uri) } }
            } else if (exportBackup) runCatching {
                val flag = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                val backup = try { runBlocking { LibraryDefaultFolderExportProbe.verify(app, picked) } } finally {
                    if (existing()[uri.toString()]?.second == true) app.contentResolver.releasePersistableUriPermission(uri, flag)
                }
                try { reopenExportedChild(app, instrumentation, uri, backup, scratch) }
                finally { backup.assertSourceUnchanged() }
            } else null
            val different = DocumentsContract.buildTreeDocumentUri(requireNotNull(uri.authority), "primary:Documents/${folder}-different")
            val wrongRoot = assertThrows(IOException::class.java) {
                runBlocking { AndroidLibraryTreeRead(app, scratch).verify(different, first) }
             }
             assertEquals("The verified library belongs to a different selected folder", wrongRoot.message)
             compose.runOnUiThread { registration.second.unregister() }
            compose.activityRule.scenario.recreate()
            val again = runBlocking { AndroidLibraryTreeRead(app, scratch).verify(Uri.parse(uri.toString()), first) }
            assertEquals(first.generationId, again.generationId)
            val fixtureBlob = SourceBlobId("aaaaaaaa-aaaa-4aaa-8aaa-000000000003")
            val bytes = ByteArrayOutputStream()
            runBlocking { AndroidLibraryTreeRead(app, scratch).copyOriginal(again, fixtureBlob, bytes) }
            assertTrue(bytes.size() > 65_536)
            assertEquals(originalDigest, MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) })
            val unrelated = ByteArrayOutputStream()
            assertThrows(IOException::class.java) {
                runBlocking { AndroidLibraryTreeRead(app, scratch).copyOriginal(again, SourceBlobId(UUID.randomUUID().toString()), unrelated) }
            }
            assertEquals(0, unrelated.size())

            var canceledWrites = 0
            assertThrows(CancellationException::class.java) {
                runBlocking {
                    val owner = requireNotNull(currentCoroutineContext()[Job])
                    val canceledSink = onWrite {
                        if (++canceledWrites == 1) owner.cancel()
                    }
                    AndroidLibraryTreeRead(app, scratch).copyOriginal(again, fixtureBlob, canceledSink)
                }
            }
            assertEquals("Cancellation must prevent the next chunk from reaching the sink", 1, canceledWrites)
            val observation = probe?.let { runCatching { it(app.contentResolver, uri, AndroidLibraryTreeRead(app, scratch), again, scratch) } }
            if (revokeAfterProbe != null) {
                observation?.getOrThrow()
                revokeAfterProbe(uri)
            } else {
                var writes = 0
                val disposable = onWrite { length ->
                    if (++writes == 1) {
                        assertTrue("Revocation must occur before the entire original is copied", length in 1 until bytes.size())
                        app.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                assertThrows(IOException::class.java) {
                    runBlocking { AndroidLibraryTreeRead(app, scratch).copyOriginal(again, fixtureBlob, disposable) }
                }
                assertTrue("The probe must revoke from an in-progress stream, not at admission", writes > 0)
            }
            assertEquals(before, existing())
            assertThrows(IOException::class.java) { runBlocking { AndroidLibraryTreeRead(app, scratch).verify(uri, first) } }
            val afterRevocation = ByteArrayOutputStream()
            assertThrows(IOException::class.java) {
                runBlocking { AndroidLibraryTreeRead(app, scratch).copyOriginal(again, fixtureBlob, afterRevocation) }
            }
            assertEquals(0, afterRevocation.size())
            writeObservation?.getOrThrow()
            observation?.getOrThrow()
        } finally {
            for (fixtureTree in exactFixtureTrees.takeUnless { readHandedOff }.orEmpty()) {
                existing()[fixtureTree]?.let { (read, write) ->
                    val modes = (if (read) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                        (if ((probeWrite || exportBackup) && write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                    if (modes != 0) app.contentResolver.releasePersistableUriPermission(Uri.parse(fixtureTree), modes)
                }
            }
            val activePackage = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
            if (activePackage == registration.first) instrumentalBack(instrumentation)
        }
    }

    private fun reopenExportedChild(app: Context, instrumentation: Instrumentation, parent: Uri, backup: LibraryDefaultFolderExportProbe.Backup, scratch: File) {
        val authority = requireNotNull(parent.authority)
        val id = "${DocumentsContract.getTreeDocumentId(parent)}/${backup.receipt.folderName}"
        val expected = DocumentsContract.buildTreeDocumentUri(authority, id)
        val resolver = app.contentResolver
        val before = resolver.persistedUriPermissions.associate { it.uri.toString() to (it.isReadPermission to it.isWritePermission) }
        check(expected.toString() !in before)
        val platform = LibraryFolderPickerContract()
        val result = AtomicReference<ActivityResult>()
        val received = CountDownLatch(1)
        val registration = compose.runOnUiThread {
            val activity = compose.activity
            val intent = platform.createIntent(activity, Unit)
                .putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(authority, id))
            val launcher = activity.activityResultRegistry.register("library-backup-child-witness", ActivityResultContracts.StartActivityForResult()) {
                result.set(it)
                received.countDown()
            }
            launcher.launch(intent)
            requireNotNull(intent.component).packageName to launcher
        }
        try {
            val labels = setOf("SELECT", "Select", "SÉLECTIONNER", "Sélectionner", "Use this folder", "USE THIS FOLDER", "Choose this folder")
            val choice = await(instrumentation, registration.first, "completed backup child") { root ->
                root.hasText(backup.receipt.folderName) && root.hasText("catalogs") && root.hasText("generations") &&
                    labels.any { root.find(it) != null }
            }
            tap(instrumentation, checkNotNull(labels.firstNotNullOfOrNull { choice.find(it) }))
            if (!received.await(4, TimeUnit.SECONDS)) {
                val confirmed = await(instrumentation, registration.first, "optional backup folder confirmation") { it.allowButton() != null }
                tap(instrumentation, checkNotNull(confirmed.allowButton()))
            }
            assertTrue(received.await(10, TimeUnit.SECONDS))
            val raw = checkNotNull(result.get())
            val picked = platform.parseResult(raw.resultCode, raw.data) as LibraryFolderPickerContract.Picked
            assertEquals(expected.toString(), picked.uri.toString())
            assertEquals(expected, LibraryFolderGrantHandoff(app).persistRead(picked))
            assertEquals(before + (expected.toString() to (true to false)),
                resolver.persistedUriPermissions.associate { it.uri.toString() to (it.isReadPermission to it.isWritePermission) })
            val reader = AndroidLibraryTreeRead(app, scratch)
            val verified = runBlocking { reader.verify(expected) }
            assertEquals(backup.receipt.libraryId, verified.libraryId)
            assertEquals(backup.receipt.generationId, verified.generationId)
            var file: File? = null
            val budget = ApplicationImageBudget.create(64)
            val decoder = AndroidSourceInspector(budget)
            val output = OutputSpec(backup.inspection.normalizedSize, RenderPurpose.PREVIEW)
            lateinit var escaped: ScopedCommittedOriginals
            val files = File(app.filesDir.canonicalFile, "backup-activation-witness-${UUID.randomUUID()}").apply { check(mkdir()) }
            val startup = AndroidLibraryStartup(object : ContextWrapper(app) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = files
            })
            val loanDirectory = File(files, "verify").apply { check(mkdir()) }
            val settings = File(files, "library-settings").apply { check(mkdir()) }
            val grantDir = File(settings, "folder-grants").apply { check(mkdir()) }
            val markers = File(settings, "folder-verifications").apply { check(mkdir()) }
            val grantId = UUID.randomUUID().toString()
            val grantBytes = checksummed("pristinepage-library-grant-v1\n$grantId\n1\n$expected\n")
            val markerUri = DocumentsContract.buildDocumentUriUsingTree(expected, "$id/generations/${verified.generationId}.complete")
            val generationBytes = checkNotNull(resolver.openInputStream(markerUri)).use { input ->
                val out = ByteArrayOutputStream()
                repeat(257) {
                    val byte = input.read()
                    if (byte == -1) return@use out.toByteArray()
                    out.write(byte)
                }
                error("The marker exceeded its maximum extent")
            }
            File(grantDir, grantId).writeBytes(grantBytes)
            File(markers, "$grantId-${verified.generationId}").writeBytes(generationBytes)
            val pointer = File(settings, "active-library")
            val recorded = checksummed("pristinepage-active-library-v2\n${verified.libraryId}\n2\n$grantId\n${verified.generationId}\n${sha256(grantBytes + generationBytes)}\n")
            pointer.writeBytes(recorded)
            runBlocking {
                startup.useSelectedLibrary(loanDirectory) { activation, catalog, sources ->
                    assertEquals(LibraryActivation(verified.libraryId, 2), activation)
                    escaped = sources
                    val page = catalog.load(backup.album).pages.single()
                    assertEquals(backup.page, page.pageId)
                    assertEquals(backup.revision, page.recipeRevision)
                    assertEquals(backup.source, page.sourceId)
                    val producer = backup.producer
                    val wrong = producer.copy(source = producer.source.copy(createdAt = producer.source.createdAt + 1))
                    val refused = runCatching {
                        sources.withOriginal(page.sourceId, wrong) { _, _ -> error("The row-only mismatch reached the original") }
                    }.exceptionOrNull() as RepositoryFailure
                    assertEquals(OperationErrorCode.INTEGRITY_FAILED, refused.error.code)
                    val bound = ScopedCommittedOriginals { source, _, consume -> sources.withOriginal(source, producer, consume) }
                    SourceDecoder(bound, decoder).decode(page.sourceId.value, output).use {
                        assertEquals(backup.inspection, it.inspection)
                        assertTrue(budget.usedBytes > 0)
                        assertFalse(files.walkTopDown().any { original -> original.name == "original.bin" })
                    }
                }
            }
            assertEquals(0L, budget.usedBytes)
            assertTrue(checkNotNull(loanDirectory.list()).isEmpty())
            assertThrows(IOException::class.java) {
                runBlocking { escaped.withOriginal(backup.source, null) { _, _ -> error("Retired catalog offered a file") } }
            }
            val successor = checksummed("pristinepage-active-library-v1\n${verified.libraryId}\n3\n-\n-\n")
            var nativeBeforeSwitch = false
            val stale = assertThrows(RepositoryFailure::class.java) {
                runBlocking {
                    startup.useSelectedLibrary(loanDirectory) { _, catalog, sources ->
                        val page = catalog.load(backup.album).pages.single()
                        val switching = SourceDecoder(sources) { original, digest ->
                            val decoded = decoder.decodeFile(original, digest)
                            try {
                                pointer.writeBytes(successor)
                                nativeBeforeSwitch = true
                                decoded
                            } catch (failure: Throwable) {
                                decoded.close()
                                throw failure
                            }
                        }
                        switching.decode(page.sourceId.value, output).use { error("Stale activation delivered native pixels") }
                    }
                }
            }
            assertTrue(nativeBeforeSwitch)
            assertEquals("library_reconnect_required", stale.error.safeMessageKey)
            assertArrayEquals(successor, pointer.readBytes())
            assertEquals(0L, budget.usedBytes)
            assertTrue(checkNotNull(loanDirectory.list()).isEmpty())
            runBlocking {
                reader.withVerifiedOriginal(verified, backup.blob) {
                    file = it
                    assertArrayEquals(backup.original, it.readBytes())
                    assertEquals(backup.inspection, decoder.inspect(it, backup.digest))
                }
            }
            assertFalse(checkNotNull(file).exists())
            runBlocking {
                val entered = CompletableDeferred<File>()
                val loan = launch {
                    reader.withVerifiedOriginal(verified, backup.blob) {
                        entered.complete(it)
                        awaitCancellation()
                    }
                }
                file = entered.await()
                loan.cancelAndJoin()
                assertTrue(loan.isCancelled)
            }
            assertFalse(checkNotNull(file).exists())
            file = null
            var revokedInsideCodec = false
            assertThrows(IOException::class.java) {
                runBlocking {
                    reader.openWorkingOriginals(verified) { sources ->
                        val revoking = SourceDecoder(sources) { original, digest ->
                            file = original
                            val decoded = decoder.decodeFile(original, digest)
                            try {
                                assertEquals(backup.inspection, decoded.inspection)
                                resolver.releasePersistableUriPermission(expected, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                revokedInsideCodec = true
                                decoded
                            } catch (failure: Throwable) {
                                decoded.close()
                                throw failure
                            }
                        }
                        revoking.decode(backup.source.value, output).use { error("Revoked check delivered native pixels") }
                    }
                }
            }
            assertTrue(revokedInsideCodec)
            assertFalse(checkNotNull(file).exists())
            assertEquals(0L, budget.usedBytes)
            assertThrows(IOException::class.java) {
                runBlocking { reader.withVerifiedOriginal(verified, backup.blob) { error("Revoked backup lent an original") } }
            }
            assertTrue(checkNotNull(scratch.list()).isEmpty())
        } finally {
            compose.runOnUiThread { registration.second.unregister() }
            if (resolver.persistedUriPermissions.any { it.uri.toString() == expected.toString() && it.isReadPermission }) {
                resolver.releasePersistableUriPermission(expected, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    private fun onWrite(receiveCount: (Int) -> Unit): OutputStream = object : OutputStream() {
        override fun write(value: Int) = error("Expected a bounded bulk write")
        override fun write(bytes: ByteArray, offset: Int, length: Int) = receiveCount(length)
    }

    /** Report only structural information about a returned URI; no raw path or non-fixture title. */
    private fun resultShape(actual: Uri?, expected: Uri, folder: String): String {
        if (actual == null) return "No data URI"
        val parts = actual.pathSegments
        val id = if (DocumentsContract.isTreeUri(actual)) DocumentsContract.getTreeDocumentId(actual) else null
        val expectedId = DocumentsContract.getTreeDocumentId(expected)
        val relative = id?.substringAfter(':', missingDelimiterValue = "")
        val exactAuthority = actual.encodedAuthority == expected.encodedAuthority
        val canonical = id != null && exactAuthority &&
            DocumentsContract.buildTreeDocumentUri(requireNotNull(expected.authority), id).toString() == actual.toString()
        return "content=${actual.scheme == "content"}; authorityMatches=$exactAuthority; tree=${id != null}; " +
            "segments=${parts.size}; documentSegment=${parts.getOrNull(2) == "document"}; extras=${actual.encodedQuery != null || actual.encodedFragment != null}; " +
            "canonical=$canonical; primary=${id?.startsWith("primary:")}; exactId=${id == expectedId}; " +
            "exactRelative=${relative == "Documents/$folder"}; fixtureLeaf=${relative?.trimEnd('/')?.endsWith("/$folder")}; " +
            "trailingSlash=${id?.endsWith('/')}; slashAfterVolume=${relative?.startsWith('/')}; relativeSegments=${relative?.split('/')?.size}"
    }

    private fun await(instrumentation: Instrumentation, picker: String, stage: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 12_000
        var last: AccessibilityNodeInfo? = null
        while (SystemClock.uptimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root != null) last = root
            if (root?.packageName?.toString() == picker && predicate(root)) return root
            SystemClock.sleep(125)
        }
        val foreground = last?.packageName?.toString()
        val observed = if (foreground == picker) buildList { last.collectLabels(this) } else emptyList()
        error("Files did not reach $stage; foreground=$foreground; visible=$observed")
    }

    private fun AccessibilityNodeInfo.collectLabels(output: MutableList<String>) {
        if (output.size >= 32) return
        if (isVisibleToUser && (text != null || contentDescription != null)) {
            output += listOf(text, contentDescription, viewIdResourceName).filterNotNull()
                .joinToString(" / ") { value -> value.take(100).toString().filterNot(Char::isISOControl) }
        }
        for (i in 0 until childCount) getChild(i)?.collectLabels(output)
    }
    private fun AccessibilityNodeInfo.hasText(value: String) = find(value) != null
    private fun AccessibilityNodeInfo.allowButton() = find("Allow") ?: find("ALLOW") ?: find("Autoriser") ?: find("AUTORISER")

    private fun AccessibilityNodeInfo.find(value: String): AccessibilityNodeInfo? {
        if (isVisibleToUser && (text?.toString() == value || contentDescription?.toString() == value)) return this
        for (i in 0 until childCount) getChild(i)?.find(value)?.let { return it }
        return null
    }

    private fun tap(instrumentation: Instrumentation, node: AccessibilityNodeInfo) {
        val bounds = Rect().also(node::getBoundsInScreen)
        check(node.isEnabled && node.isVisibleToUser && !bounds.isEmpty)
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                check(instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally { event.recycle() }
        }
    }

    private fun instrumentalBack(instrumentation: Instrumentation) {
        instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }
}
