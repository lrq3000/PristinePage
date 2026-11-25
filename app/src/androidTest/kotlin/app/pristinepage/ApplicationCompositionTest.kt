package app.pristinepage

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.capture.PhotoImportController
import app.pristinepage.core.pipeline.OutputSpec
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.RenderPurpose
import app.pristinepage.core.pipeline.RenderCoordinator
import app.pristinepage.core.pipeline.RenderRequest
import app.pristinepage.core.pipeline.RenderStatus
import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.core.pipeline.FallbackPolicy
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.data.files.SourceInspector
import app.pristinepage.data.library.LibraryActivation
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import app.pristinepage.image.decode.AndroidSourceInspector
import app.pristinepage.image.decode.ImageAllocationBudget
import app.pristinepage.image.decode.SourceDecoder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import java.util.UUID
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real production composition, Room/private files/owners/codec/controller. Only external provider delivery and an explicit storage-failure boundary are injected. Unique private roots and all originals/journals are retained; no grants, picker UI, OS death or physical-memory claim. */
@RunWith(AndroidJUnit4::class)
class ApplicationCompositionTest {
    private val output = OutputSpec(PixelSize(2, 2), RenderPurpose.PREVIEW)

    @Test fun anAtelierChooserBelongsToTheDraftAndItsIssuingActivation() = runBlocking {
        Fixture().use { f ->
            val services = ApplicationServices(f.context, f.budget, f.provider)
            val stale = LibraryActivation("aaaaaaaa-aaaa-4aaa-8aaa-000000000011", 1)
            val refused = runCatching { services.openDefaultAtelier(stale, "Cold draft", null) }.exceptionOrNull() as RepositoryFailure
            assertEquals("library_activation_changed", refused.error.safeMessageKey)
            assertFalse(File(f.context.filesDir, "library-settings").exists())
            assertFalse(f.context.getDatabasePath("pristinepage.db").exists())
            services.imports().useStore { graph ->
                val draft = services.openDefaultAtelier(graph.activation, "Exact draft", null).draft
                val request = services.reserveDefaultAtelier(graph.activation, draft, app.pristinepage.capture.PhotoImportKind.FILES)
                assertEquals(graph.activation, request.issuedAt)
                assertEquals(app.pristinepage.capture.PhotoImportKind.FILES, request.kind)
                assertEquals(request.batchId, graph.store.atelier.refresh(draft).pending?.value)
                val route = app.pristinepage.library.ApplicationLibraryActions(f.context, null, services)
                val selection = app.pristinepage.library.LibraryPhotoResult.Selected(listOf(f.good), 0)
                val staleCancel = runCatching { route.cancelAtelier(graph.activation.copy(number = 2), draft, request) }
                    .exceptionOrNull() as RepositoryFailure
                assertEquals("library_activation_changed", staleCancel.error.safeMessageKey)
                val wrongDraft = draft.copy(id = "aaaaaaaa-aaaa-4aaa-8aaa-000000000013")
                assertTrue(runCatching { route.deliverAtelier(graph.activation, wrongDraft, request, selection) }.isFailure)
                assertTrue(f.opened.isEmpty())
                assertEquals(request.batchId, graph.store.atelier.refresh(draft).pending?.value)
                val marker = File(f.context.filesDir, "library-settings/active-library")
                val baseline = marker.readBytes()
                val body = "pristinepage-active-library-v1\n${graph.activation.libraryId}\n${graph.activation.number + 1}\n-\n-\n"
                val digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.US_ASCII))
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                var checks = 0
                f.afterRoomOpen = { if (++checks == 2) marker.writeText("$body$digest\n", Charsets.US_ASCII) }
                try {
                    val late = runCatching { route.deliverAtelier(graph.activation, draft, request, selection) }.exceptionOrNull() as RepositoryFailure
                    assertEquals("library_activation_changed", late.error.safeMessageKey)
                } finally {
                    f.afterRoomOpen = {}
                    marker.writeBytes(baseline)
                }
                assertEquals(listOf(f.good), f.opened)
                val settled = route.cancelAtelier(graph.activation, draft, request)
                assertNull(settled.pending)
                assertEquals(1, settled.pages.size)
                assertEquals(listOf(f.good), f.opened)
            }
        }
    }

    @Test fun onlyTheIssuedCameraRequestCanDiscardItsHandoff() = runBlocking {
        Fixture().use { f ->
            val services = ApplicationServices(f.context, f.budget, f.provider)
            val route = app.pristinepage.library.ApplicationLibraryActions(f.context, null, services)
            services.imports().useStore { graph ->
                val activation = graph.activation
                val draft = services.openDefaultAtelier(activation, "Capture ownership", null).draft
                val files = services.reserveDefaultAtelier(activation, draft, app.pristinepage.capture.PhotoImportKind.FILES)
                val handoff = app.pristinepage.capture.CaptureStage(f.context, files.batchId).create()
                handoff.writeText("Independent bytes")
                val forged = files.routedAs(app.pristinepage.capture.PhotoImportKind.CAMERA)
                assertTrue(runCatching { route.cancelAtelier(activation, draft, forged) }.exceptionOrNull() is RepositoryFailure)
                assertEquals(files.session, graph.store.atelier.refresh(draft).pending)
                route.cancelAtelier(activation, draft, files)
                assertEquals("Independent bytes", handoff.readText())
                val camera = services.reserveDefaultAtelier(activation, draft, app.pristinepage.capture.PhotoImportKind.CAMERA)
                val owned = app.pristinepage.capture.CaptureStage(f.context, camera.batchId).create()
                route.cancelAtelier(activation, draft, camera)
                assertFalse(owned.exists())
                val rediscovered = services.reserveDefaultAtelier(activation, draft, app.pristinepage.capture.PhotoImportKind.CAMERA)
                val unknown = app.pristinepage.capture.CaptureStage(f.context, rediscovered.batchId).create()
                route.cancelAtelier(activation, draft, app.pristinepage.capture.PhotoImportRequest.abandonmentOnly(rediscovered.session, activation))
                assertTrue(unknown.isFile)
                assertNull(graph.store.atelier.refresh(draft).pending)
                assertTrue(f.opened.isEmpty())
            }
        }
    }

    @Test fun atelierSaveRefusesAReplacementRevisionAndCanReadBackOnlyTheExactReceipt() = runBlocking {
        Fixture().use { f ->
            val services = ApplicationServices(f.context, f.budget, f.provider)
            val route = app.pristinepage.library.ApplicationLibraryActions(f.context, null, services)
            services.imports().useStore { graph ->
                val activation = graph.activation
                val draft = services.openDefaultAtelier(activation, "Reviewed revision", null).draft
                val files = services.reserveDefaultAtelier(activation, draft, app.pristinepage.capture.PhotoImportKind.FILES)
                route.deliverAtelier(activation, draft, files, app.pristinepage.library.LibraryPhotoResult.Selected(listOf(f.good), 0))
                val editing = services.finishDefaultAtelier(activation, draft)
                val page = checkNotNull(editing.selected)
                val stepOne = services.navigateDefaultAtelier(activation, editing, page, 1)
                val reviewed = services.navigateDefaultAtelier(activation, stepOne, page, 2)
                val original = services.loadDefaultAtelierPage(activation, reviewed, page)
                val originalInput = services.freezeDefaultAtelierSave(activation, reviewed, original)
                val rotated = original.recipe.copy(geometry = original.recipe.geometry.withOutputQuarterTurns(1))
                assertTrue(services.checkpointDefaultAtelier(activation, reviewed, page, original.recipeRevision, rotated) is SaveRecipeResult.Saved)
                val conflict = runCatching { services.saveDefaultAtelier(originalInput) }.exceptionOrNull() as RepositoryFailure
                assertEquals(OperationErrorCode.REVISION_CONFLICT, conflict.error.code)
                assertNull(services.recoverDefaultAtelierSave(originalInput))
                val edited = services.loadDefaultAtelierPage(activation, reviewed, page)
                assertEquals(original.recipeRevision + 1, edited.recipeRevision)
                val exact = services.freezeDefaultAtelierSave(activation, reviewed, edited)
                val marker = File(f.context.filesDir, "library-settings/active-library")
                val baseline = marker.readBytes()
                val body = "pristinepage-active-library-v1\n${activation.libraryId}\n${activation.number + 1}\n-\n-\n"
                val digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.US_ASCII))
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                var checks = 0
                f.afterRoomOpen = { if (++checks == 2) marker.writeText("$body$digest\n", Charsets.US_ASCII) }
                try {
                    val failure = runCatching { services.saveDefaultAtelier(exact) }.exceptionOrNull() as RepositoryFailure
                    assertEquals("atelier_save_delivery_unconfirmed", failure.error.safeMessageKey)
                } finally {
                    f.afterRoomOpen = {}
                    marker.writeBytes(baseline)
                }
                val receipt = checkNotNull(services.recoverDefaultAtelierSave(exact))
                assertEquals(listOf(page), receipt.pageIds)
                assertEquals(edited.recipeRevision, graph.store.editablePages.load(receipt.document, page).recipeRevision)
                assertEquals(OperationErrorCode.REVISION_CONFLICT,
                    (runCatching { services.recoverDefaultAtelierSave(originalInput) }.exceptionOrNull() as RepositoryFailure).error.code)
                assertEquals(0L, f.budget.usedBytes)
            }
        }
    }

    @Test fun anEditorContinuationCannotReadSaveOrRenderUnderAnotherActivation() = runBlocking {
        Fixture().use { f ->
            val services = ApplicationServices(f.context, f.budget, f.provider)
            val cold = app.pristinepage.editor.EditorIdentity(DocumentId("aaaaaaaa-aaaa-4aaa-8aaa-000000000011"),
                PageId("aaaaaaaa-aaaa-4aaa-8aaa-000000000012"), LibraryActivation("aaaaaaaa-aaaa-4aaa-8aaa-000000000010", 1))
            suspend fun refused(key: String = "library_activation_changed", action: suspend () -> Any) {
                val error = runCatching { action() }.exceptionOrNull() as RepositoryFailure
                assertEquals(key, error.error.safeMessageKey)
            }
            refused { services.loadDefaultEditorPage(cold) }
            assertFalse(File(f.context.filesDir, "library-settings").exists())
            assertFalse(f.context.getDatabasePath("pristinepage.db").exists())
            services.imports().useStore { graph ->
                val album = graph.store.documents.createDocument("Current editor")
                val batch = graph.store.imports.beginImport(album, 1, null)
                val scan = graph.photoImports.importProviderReferences(listOf(f.good), batch.parentId).single() as IngestResult.Committed
                val exact = app.pristinepage.editor.EditorIdentity(album, scan.pageId, graph.activation)
                val stale = exact.copy(issuedAt = graph.activation.copy(number = graph.activation.number + 1))
                val original = services.loadDefaultEditorPage(exact)
                val rotated = original.recipe.copy(geometry = original.recipe.geometry.withOutputQuarterTurns(1))
                val request = RenderRequest(scan.sourceId.value, scan.sourcePixelHash, scan.pageId.value, original.recipeRevision,
                    original.recipe, output, 1, null, FallbackPolicy.FAIL)
                refused { services.loadDefaultEditorPage(stale) }
                refused { services.saveDefaultEditorPage(stale, original.recipeRevision, rotated) }
                refused { services.renderDefaultEditor(stale, request).also { it.close() } }
                assertEquals(0L, f.budget.usedBytes)
                assertEquals(original, services.loadDefaultEditorPage(exact))
                assertEquals(listOf(f.good), f.opened)
                services.renderDefaultEditor(exact, request).use { assertEquals(RenderStatus.OK, it.status) }
                val marker = File(f.context.filesDir, "library-settings/active-library")
                val baseline = marker.readBytes()
                val body = "pristinepage-active-library-v1\n${graph.activation.libraryId}\n${graph.activation.number + 1}\n-\n-\n"
                val digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.US_ASCII))
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                suspend fun drift(pixels: Boolean = false, key: String = "library_activation_changed", action: suspend () -> Any) {
                    var checks = 0
                    f.afterRoomOpen = {
                        if (++checks == 2) {
                            if (pixels) assertTrue("The complete pixel owner exists before the late refusal", f.budget.usedBytes > 0)
                            marker.writeText("$body$digest\n", Charsets.US_ASCII)
                        }
                    }
                    try {
                        refused(key, action)
                        assertTrue(checks >= 2)
                        assertEquals(0L, f.budget.usedBytes)
                    } finally {
                        f.afterRoomOpen = {}
                        marker.writeBytes(baseline)
                    }
                }
                drift { services.loadDefaultEditorPage(exact) }
                drift { services.readDefaultEditorOcrStatus(exact, original.recipeRevision); Unit }
                drift { services.readDefaultEditorOcrText(exact, original.recipeRevision); Unit }
                drift(pixels = true) { services.renderDefaultEditor(exact, request).also { it.close() } }
                drift(pixels = true) { services.originalDefaultEditor(exact, scan.sourceId.value).also { it.close() } }
                drift(key = "editor_save_delivery_unconfirmed") { services.saveDefaultEditorPage(exact, original.recipeRevision, rotated) }
                val committed = services.loadDefaultEditorPage(exact)
                assertEquals("Refusing a late receipt does not roll back the old store", original.recipeRevision + 1, committed.recipeRevision)
                assertEquals(1, committed.recipe.geometry.outputQuarterTurns)
            }
        }
    }

    @Test fun aStalePhotoResultCannotBootstrapOrAccessTheDefaultGraph() = runBlocking {
        Fixture().use { f ->
            val services = ApplicationServices(f.context, f.budget, f.provider)
            val actions = app.pristinepage.library.ApplicationLibraryActions(f.context, null, services)
            val stale = LibraryActivation("aaaaaaaa-aaaa-4aaa-8aaa-000000000010", 1)
            val cold = app.pristinepage.capture.PhotoImportRequest("aaaaaaaa-aaaa-4aaa-8aaa-000000000011",
                app.pristinepage.capture.PhotoImportKind.FILES, stale)
            val result = app.pristinepage.library.LibraryPhotoResult.Selected(listOf(f.good), 0)
            val refused = runCatching { actions.deliver(cold, result) }.exceptionOrNull() as RepositoryFailure
            assertEquals("library_activation_changed", refused.error.safeMessageKey)
            assertFalse(File(f.context.filesDir, "library-settings").exists())
            assertFalse(f.context.getDatabasePath("pristinepage.db").exists())
            services.imports().useStore { graph ->
                val id = graph.store.documents.createDocument("Current album")
                val request = actions.begin(graph.store.catalog.load(id).document)
                assertEquals(graph.activation, request.issuedAt)
                val rediscovered = checkNotNull(actions.activeImportRequest(id))
                val notIssued = runCatching { actions.deliver(rediscovered, result) }.exceptionOrNull() as RepositoryFailure
                assertEquals("photo_result_not_issued", notIssued.error.safeMessageKey)
                assertTrue("Room discovery must not open the provider", f.opened.isEmpty())
                val mismatched = app.pristinepage.capture.PhotoImportRequest(request.batchId, request.kind, graph.activation.copy(number = 2))
                val declined = runCatching { actions.cancel(mismatched) }.exceptionOrNull() as RepositoryFailure
                assertEquals("library_activation_changed", declined.error.safeMessageKey)
                assertNotNull(graph.store.catalog.activeImport(id))
                assertTrue(actions.deliver(request, result).single() is IngestResult.Committed)
            }
        }
    }

    @Test fun aColdWorkerCannotCreateOrBorrowADefaultLibraryUntilTheExactActivationExists() = runBlocking {
        Fixture().use { f ->
            val service = ApplicationServices(f.context, f.budget, f.provider)
            val stale = LibraryActivation("aaaaaaaa-aaaa-4aaa-8aaa-000000000010", 1)
            assertNull(service.reconcileDefaultOcrBatch(stale, null, null, 2))
            assertFalse(File(f.context.filesDir, "library-settings").exists())
            assertFalse(f.context.getDatabasePath("pristinepage.db").exists())
            val recorded = service.imports().useStore { it.activation }
            val reopened = ApplicationServices(f.context, f.budget, f.provider)
            try {
                assertNotNull(reopened.reconcileDefaultOcrBatch(recorded, null, null, 2))
                assertNull(reopened.reconcileDefaultOcrBatch(recorded.copy(number = 2), null, null, 2))
                val album = DocumentId("aaaaaaaa-aaaa-4aaa-8aaa-000000000011")
                val page = PageId("aaaaaaaa-aaaa-4aaa-8aaa-000000000012")
                assertNull(reopened.reconcileDefaultOcrPage(recorded, album, page, null))
                val skipped = runCatching {
                    reopened.reconcileDefaultOcrPage(recorded.copy(number = 2), album, page, null)
                }.exceptionOrNull() as RepositoryFailure
                assertEquals("library_activation_changed", skipped.error.safeMessageKey)
            } finally {
                reopened.imports().close()
            }
        }
    }

    @Test fun firstActivationPreservesAPopulatedStoreOpenedByThePreviousComposition() = runBlocking {
        Fixture().use { f ->
            val before = PrivateImportStore.open(f.context, f.provider, AndroidSourceInspector(f.budget)).use { legacy ->
                val album = legacy.documents.createDocument("Retained album")
                val batch = legacy.imports.beginImport(album, 1, null)
                val scan = PhotoImportController(legacy.selections, legacy.sources)
                    .importProviderReferences(listOf(f.good), batch.parentId).single() as IngestResult.Committed
                legacy.catalog.load(album) to scan
            }
            assertFalse(File(f.context.filesDir, "library-settings").exists())
            f.forbid = true
            val original = File(f.context.filesDir, "store/sources").walkTopDown().single { it.name == "original.bin" }
            assertArrayEquals(f.png, original.readBytes())
            val first = ApplicationServices(f.context, f.budget, f.provider).imports().useStore { graph ->
                assertEquals(before.first, graph.store.catalog.load(before.second.documentId))
                assertArrayEquals(f.png, graph.store.committedSources.load(before.second.sourceId).file.readBytes())
                graph.activation
            }
            ApplicationServices(f.context, f.budget, f.provider).imports().useStore { graph ->
                assertEquals(first, graph.activation)
                assertEquals(before.first, graph.store.catalog.load(before.second.documentId))
            }
            assertEquals(listOf(f.good), f.opened)
        }
    }

    @Test fun corruptSelectionRefusesBeforeCreatingTheDefaultStore() = runBlocking {
        Fixture().use { f ->
            val settings = File(f.context.filesDir, "library-settings").apply { check(mkdir()) }
            val marker = File(settings, "active-library").apply { writeText("damaged selection") }
            val failure = try {
                ApplicationServices(f.context, f.budget, f.provider).imports().also { it.store.close() }
                throw AssertionError("Corrupt selection unexpectedly opened the default graph")
            } catch (failure: RepositoryFailure) { failure }
            assertEquals("library_selection_unavailable", failure.error.safeMessageKey)
            assertEquals("damaged selection", marker.readText())
            assertFalse(f.context.getDatabasePath("pristinepage.db").exists())
            assertFalse(File(f.context.filesDir, "store").exists())
        }
    }

    @Test fun aFolderSelectionWithLostSupportNeverOpensTheDefaultStore() = runBlocking {
        Fixture().use { f ->
            val settings = File(f.context.filesDir, "library-settings").apply { check(mkdir()) }
            val body = "pristinepage-active-library-v2\n" +
                "aaaaaaaa-aaaa-4aaa-8aaa-000000000001\n2\n" +
                "aaaaaaaa-aaaa-4aaa-8aaa-000000000002\naaaaaaaa-aaaa-4aaa-8aaa-000000000003\n${"0".repeat(64)}\n"
            val checksum = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.US_ASCII))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val contents = "$body$checksum\n"
            val marker = File(settings, "active-library").apply { writeText(contents) }
            val failure = try {
                ApplicationServices(f.context, f.budget, f.provider).imports().also { it.store.close() }
                throw AssertionError("Unavailable selected folder unexpectedly opened the default graph")
            } catch (failure: RepositoryFailure) { failure }
            assertEquals(OperationErrorCode.PERMISSION_LOST, failure.error.code)
            assertEquals("library_reconnect_required", failure.error.safeMessageKey)
            assertEquals(contents, marker.readText())
            assertFalse(f.context.getDatabasePath("pristinepage.db").exists())
            assertFalse(File(f.context.filesDir, "store").exists())
        }
    }

    private inline fun <T> ApplicationImports.useStore(block: (ApplicationImports) -> T): T = store.use { block(this) }

    @Test fun orderedMixedResultsSurviveReopenWithoutReopeningAProvider() = runBlocking {
        Fixture().use { f ->
            f.open()
            val document = f.documents.createDocument("Composition mixed results")
            val batch = f.sessions.beginImport(document, 1, null)
            val refs = listOf(f.good, f.bad, f.good)
            val results = f.controller.importProviderReferences(refs, batch.parentId)
            val first = results[0] as IngestResult.Committed
            assertTrue(results[1] is IngestResult.Rejected); assertTrue(results[2] is IngestResult.Committed)
            assertNotEquals(first.pageId, (results[2] as IngestResult.Committed).pageId)
            assertEquals(listOf(f.good, f.bad, f.good), f.opened)
            val selected = f.selections.prepareSelection(batch.parentId, refs)
            assertEquals(selected.childIds, results.map { when (it) { is IngestResult.Committed -> it.sessionId; is IngestResult.Rejected -> it.sessionId; is IngestResult.Pending -> it.sessionId } })
            val original = f.committed.load(first.sourceId)
            assertArrayEquals(f.png, original.file.readBytes())
            val inventory = f.inventory()
            f.reopen(); f.forbid = true
            assertEquals(results, f.controller.importProviderReferences(refs, batch.parentId))
            f.decoder.decode(first.sourceId.value, output).use { assertArrayEquals(f.rgb, it.image.toPackedByteArray()) }
            assertEquals(inventory, f.inventory()); assertEquals(3, f.opened.size); assertEquals(0L, f.budget.usedBytes)
        }
    }

    @Test fun canceledRemainderKeepsCommittedReceiptThroughTheRealControllerAndReopen() = runBlocking {
        Fixture().use { f ->
            f.open(); val doc = f.documents.createDocument("Composition cancellation")
            val batch = f.sessions.beginImport(doc, 1, null)
            f.beforeOpen = { if (f.opened.size == 2) runBlocking { f.lifecycle.cancelBatch(batch.parentId) } }
            val refs = listOf(f.good, f.good, f.good)
            val results = f.controller.importProviderReferences(refs, batch.parentId)
            assertTrue(results[0] is IngestResult.Committed)
            for (result in results.drop(1)) assertEquals(OperationErrorCode.CANCELED, (result as IngestResult.Rejected).error.code)
            val first = results[0] as IngestResult.Committed
            val inventory = f.inventory(); f.reopen(); f.forbid = true
            assertEquals(results, f.controller.importProviderReferences(refs, batch.parentId))
            assertArrayEquals(f.png, f.committed.load(first.sourceId).file.readBytes())
            assertEquals(inventory, f.inventory()); assertEquals(2, f.opened.size)
        }
    }

    @Test fun retainedDecodedPixelsConsumeTheSameAllowanceAsSubsequentIngressAndDecode() = runBlocking {
        Fixture().use { f ->
            f.open(); val doc = f.documents.createDocument("Composition budget")
            val batch = f.sessions.beginImport(doc, 1, null)
            val first = f.controller.importProviderReferences(listOf(f.good), batch.parentId).single() as IngestResult.Committed
            f.decoder.decode(first.sourceId.value, output).use { retained ->
                assertTrue(f.budget.usedBytes > 0); val owned = f.budget.usedBytes
                f.budget.reserve(f.budget.capacityBytes - owned).use {
                    val second = f.sessions.beginImport(doc, first.documentRevision, null)
                    val rejected = f.controller.importProviderReferences(listOf(f.good), second.parentId).single() as IngestResult.Rejected
                    assertEquals(OperationErrorCode.RESOURCE_LIMIT, rejected.error.code)
                    try { f.decoder.decode(first.sourceId.value, output).close(); fail("Decode escaped shared admission") }
                    catch (failure: RepositoryFailure) { assertEquals(OperationErrorCode.RESOURCE_LIMIT, failure.error.code) }
                    assertEquals(f.budget.capacityBytes, f.budget.usedBytes); assertArrayEquals(f.rgb, retained.image.toPackedByteArray())
                }
                assertEquals(owned, f.budget.usedBytes)
            }
            assertEquals(0L, f.budget.usedBytes)
            f.decoder.decode(first.sourceId.value, output).use { assertArrayEquals(f.rgb, it.image.toPackedByteArray()) }
        }
    }

    @Test fun recoveryReportsUnsealedWorkWithoutCallingItsTemporaryProviderAgain() = runBlocking {
        Fixture().use { f ->
            val normalizer = AndroidSourceInspector(f.budget)
            var rejectInspection = true
            val inspector = SourceInspector { file, hash ->
                if (rejectInspection) throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "synthetic_inspection_io", null))
                normalizer.inspect(file, hash)
            }
            var store = privateStore(f.context, f.provider, inspector)
            try {
                val documents = property<DocumentRepository>(store, "Documents")
                val sessions = property<ImportSessionCommands>(store, "Imports")
                val selections = property<ImportSelections>(store, "Selections")
                val controller = PhotoImportController(selections, property(store, "Sources"))
                val doc = documents.createDocument("Composition unsealed recovery")
                val batch = sessions.beginImport(doc, 1, null)
                assertTrue(controller.importProviderReferences(listOf(f.good), batch.parentId).single() is IngestResult.Pending)
                (store as AutoCloseable).close(); f.forbid = true; rejectInspection = false
                store = privateStore(f.context, f.provider, inspector)
                val repository = property<DocumentRepository>(store, "Documents")
                val inventory = f.inventory()
                repeat(2) {
                    val report = repository.reconcile()
                    assertTrue(report.blocked >= 1); assertEquals(0L, report.completed); assertEquals(0L, report.resumed)
                    assertTrue(report.safeIssues.any { it.action == RecoveryAction.RESELECT })
                }
                assertEquals(1, f.opened.size); assertEquals(inventory, f.inventory())
            } finally { (store as AutoCloseable).close() }
        }
    }

    @Test fun concurrentConsumersReceiveOneInitializedStoreAndBudget() = runBlocking {
        Fixture().use { f ->
            val services = services(f.context, f.budget, f.provider)
            val runtimes = coroutineScope { List(12) { async { runtime(services) } }.map { it.await() } }
            try {
                assertTrue(runtimes.all { it === runtimes[0] })
                assertSame(f.budget, property<ImageAllocationBudget>(runtimes[0], "Budget"))
                val documents = property<DocumentRepository>(property<Any>(runtimes[0], "Store"), "Documents")
                documents.createDocument("Shared initialized store")
                assertEquals(0L, documents.reconcile().blocked)
            } finally { (property<Any>(runtimes[0], "Store") as AutoCloseable).close() }
        }
    }

    @Test fun installedApplicationOwnsTheServicesRatherThanEachActivity() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        assertEquals("Application-scoped composition is absent", "app.pristinepage.PristinePageApplication", app.javaClass.name)
        val method = app.javaClass.getMethod("getServices")
        assertSame(method.invoke(app), method.invoke(app))
    }

    @Test fun applicationRendererRetainsTheSharedAllowanceAndReadsOnlyCommittedSources() = runBlocking {
        Fixture().use { f ->
            f.open(); val doc = f.documents.createDocument("Application renderer source")
            val batch = f.sessions.beginImport(doc,1,null)
            val imported = f.controller.importProviderReferences(listOf(f.good),batch.parentId).single() as IngestResult.Committed
            val source = f.committed.load(imported.sourceId); val info = source.inspection
            val binding = SourceBinding(info.sourcePixelHash,info.normalizedSize,info.conversionFingerprint)
            val request = RenderRequest(imported.sourceId.value,binding.sourcePixelHash,imported.pageId.value,1,Recipe(geometry = GeometryRecipe.None(binding,outputQuarterTurns = 1)),output,1,null,FallbackPolicy.FAIL)
            val renderer = f.renderer; f.forbid = true
            assertSame(renderer,f.renderer)
            renderer.render(request).use {
                assertEquals(RenderStatus.OK,it.status); val image = checkNotNull(it.image)
                val rotated = byteArrayOf(0,0,-1,-1,0,0,-1,-1,-1,0,-1,0)
                assertArrayEquals(rotated,image.toPackedByteArray()); val owned = f.budget.usedBytes; assertTrue(owned > 0)
                f.budget.reserve(f.budget.capacityBytes-owned).use {
                    f.renderer.render(request.copy(generation = 2)).use { assertEquals(RenderStatus.RESOURCE_LIMIT,it.status); assertNull(it.image) }
                    assertArrayEquals(rotated,image.toPackedByteArray())
                }
                assertEquals(owned,f.budget.usedBytes)
            }
            assertEquals(0L,f.budget.usedBytes); assertArrayEquals(f.png,source.file.readBytes()); assertEquals(1,f.opened.size)
        }
    }

    @Test fun concurrentApplicationConsumersShareOneRendererNotIndependentProcessingQueues() = runBlocking {
        Fixture().use { f ->
            val services = services(f.context,f.budget,f.provider)
            val graphs = coroutineScope { List(12) { async { runtime(services) } }.map { it.await() } }
            try {
                val renderers = graphs.map { renderer(it) }
                assertTrue(renderers.all { it === renderers[0] }); assertTrue(f.opened.isEmpty()); assertEquals(0L,f.budget.usedBytes)
            } finally { (property<Any>(graphs[0],"Store") as AutoCloseable).close() }
        }
    }

    @Test fun failedRecoveryDoesNotCacheReadinessAndTheSameRootCanRetry() = runBlocking {
        Fixture().use { f ->
            val owner = services(f.context, f.budget, f.provider)
            f.afterRoomOpen = {
                // Factory has opened the actual schema, but no graph is published. Corrupt only this fixture's otherwise unreferenced row so canonical startup listing must refuse, not report a clean pass.
                f.afterRoomOpen = {}
                SQLiteDatabase.openDatabase(f.context.getDatabasePath("pristinepage.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                    db.execSQL("INSERT INTO Document(documentId,title,documentRevision,lifecycle,createdAt,updatedAt,deletedAt) VALUES ('','Synthetic recovery fault',1,'ACTIVE',1,1,NULL)")
                }
            }
            try { withContext(Dispatchers.Main) { runtime(owner) }; fail("Failed initialization was reported ready") }
            catch (failure: RepositoryFailure) { assertEquals(OperationErrorCode.INTEGRITY_FAILED, failure.error.code) }
            f.afterRoomOpen = {}
            SQLiteDatabase.openDatabase(f.context.getDatabasePath("pristinepage.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("UPDATE Document SET documentId=? WHERE documentId=''", arrayOf(UUID.randomUUID().toString()))
            }
            val graph = withContext(Dispatchers.Main) { runtime(owner) }
            try {
                val documents = property<DocumentRepository>(property<Any>(graph, "Store"), "Documents")
                documents.createDocument("Initialization retry")
                assertEquals(0L, documents.reconcile().blocked); assertTrue(f.opened.isEmpty())
            } finally { (property<Any>(graph, "Store") as AutoCloseable).close() }
        }
    }

    @Test fun cancellationDuringIoDeliveryLeavesNoCachedCanceledGraphAndAllowsRetry() = runBlocking {
        Fixture().use { f ->
            val owner = services(f.context, f.budget, f.provider)
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            f.afterRoomOpen = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            val request = launch(Dispatchers.Main) { runtime(owner) }
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                request.cancel()
            } finally { release.countDown() }
            request.join(); assertTrue(request.isCancelled)
            f.afterRoomOpen = {}
            val graph = withContext(Dispatchers.Main) { runtime(owner) }
            try {
                val documents = property<DocumentRepository>(property<Any>(graph, "Store"), "Documents")
                documents.createDocument("Canceled initialization retry")
                assertEquals(0L, documents.reconcile().blocked); assertTrue(f.opened.isEmpty())
            } finally { (property<Any>(graph, "Store") as AutoCloseable).close() }
        }
    }

    private class Fixture : AutoCloseable {
        private val base = InstrumentationRegistry.getInstrumentation().targetContext
        private val root = File(base.filesDir, "t04-composition-${UUID.randomUUID()}")
        private val files = File(root, "files").apply { check(mkdirs()) }
        private val cache = File(root, "cache").apply { check(mkdirs()) }
        var afterRoomOpen: () -> Unit = {}
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = cache
            override fun getFilesDir(): File {
                if (File(root, "databases/pristinepage.db").isFile) afterRoomOpen()
                return files
            }
            override fun getDatabasePath(name: String): File = File(root, "databases/$name").also { it.parentFile!!.mkdirs() }
        }
        val good = "content://fixture.documents/good"; val bad = "content://fixture.documents/corrupt"
        val rgb = byteArrayOf(-1, 0, 0, 0, -1, 0, 0, 0, -1, -1, -1, -1)
        val png = Bitmap.createBitmap(intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), 0xffffffff.toInt()), 2, 2, Bitmap.Config.ARGB_8888).let { bitmap ->
            try { ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray() } finally { bitmap.recycle() }
        }
        val budget = ApplicationImageBudget.create(64)
        val opened = mutableListOf<String>()
        var forbid = false; var beforeOpen: () -> Unit = {}
        val provider = SourceInput { reference ->
            check(!forbid) { "Replay reopened a temporary provider" }; check(reference == good || reference == bad)
            opened += reference; beforeOpen()
            ByteArrayInputStream(if (reference == good) png else byteArrayOf(1, 2, 3, 4))
        }
        private var current: Any? = null
        private val graph get() = checkNotNull(current)
        val documents get() = property<DocumentRepository>(property<Any>(graph, "Store"), "Documents")
        val sessions get() = property<ImportSessionCommands>(property<Any>(graph, "Store"), "Imports")
        val selections get() = property<ImportSelections>(property<Any>(graph, "Store"), "Selections")
        val lifecycle get() = property<ImportSessionLifecycle>(property<Any>(graph, "Store"), "Lifecycle")
        val committed get() = property<CommittedSources>(property<Any>(graph, "Store"), "CommittedSources")
        val controller get() = property<PhotoImportController>(graph, "PhotoImports")
        val decoder get() = property<SourceDecoder>(graph, "Decoder")
        val renderer get() = renderer(graph)
        suspend fun open() { current = runtime(services(context, budget, provider)) }
        suspend fun reopen() { close(); current = null; open() }
        fun inventory(): Map<String, String> = File(root, "files/store").walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { b -> "%02x".format(b.toInt() and 255) } }
        override fun close() { current?.let { (property<Any>(it, "Store") as AutoCloseable).close() } }
    }

    private companion object {
        fun renderer(graph: Any): RenderCoordinator = try { property(graph,"Renderer") } catch (_: NoSuchMethodException) { throw AssertionError("Application-scoped render coordinator is absent") }
        @Suppress("UNCHECKED_CAST") fun <T> property(receiver: Any, name: String): T = receiver.javaClass.getMethod("get$name").invoke(receiver) as T
        fun type(name: String): Class<*> = try { Class.forName(name) } catch (_: ClassNotFoundException) { throw AssertionError("Application-scoped composition is absent: $name") }
        fun services(context: Context, budget: ImageAllocationBudget, provider: SourceInput): Any = type("app.pristinepage.ApplicationServices").getConstructor(Context::class.java, ImageAllocationBudget::class.java, SourceInput::class.java).newInstance(context, budget, provider)
        suspend fun runtime(services: Any): Any = invoke(services.javaClass.getMethod("imports", kotlin.coroutines.Continuation::class.java), services)
        suspend fun privateStore(context: Context, provider: SourceInput, inspector: SourceInspector): Any {
            val method = type("app.pristinepage.data.repository.PrivateImportStore").getMethod("open", Context::class.java, SourceInput::class.java, SourceInspector::class.java, kotlin.coroutines.Continuation::class.java)
            return invoke(method, null, context, provider, inspector)
        }
        suspend fun invoke(method: java.lang.reflect.Method, receiver: Any?, vararg args: Any?): Any = suspendCoroutineUninterceptedOrReturn { continuation ->
            try { method.invoke(receiver, *args, continuation) } catch (failure: InvocationTargetException) { throw failure.targetException }
        }
    }
}
