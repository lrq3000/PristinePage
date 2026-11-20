package app.pristinepage

import app.pristinepage.capture.PhotoImportRequest
import app.pristinepage.capture.PhotoImportKind
import app.pristinepage.data.model.*
import app.pristinepage.library.*
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** State/controller boundary only; the native consumer must keep Application/Room/controller/codec wiring real. The fixture mirrors complete metadata/results and substitutes the suspend IO calls, not the state transitions being asserted. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ImportLibrarySessionTest {
    @Test fun cameraHandoffAcknowledgesOnlyAnActiveBatchAndRejectsDuplicateStill() = runTest {
        val f = Fixture(); val session = session(f, LibraryIdentity(f.first), this); advanceUntilIdle()
        session.accept(LibraryEvent.BeginCapture); advanceUntilIdle()
        val request = session.state.value.request!!
        val handoff = LibraryEvent.PhotoResult(request, f.selected)
        val captureSession = session as ImportLibrarySession
        val wrongBatch = LibraryEvent.PhotoResult(PhotoImportRequest(f.otherBatch.value, PhotoImportKind.CAMERA), f.selected)
        assertFalse(captureSession.submitCapture(wrongBatch))
        assertTrue(captureSession.submitCapture(handoff))
        assertFalse(captureSession.submitCapture(handoff))
        advanceUntilIdle()
        assertEquals(1, f.deliveries)
        assertFalse(captureSession.submitCapture(handoff))
    }

    @Test fun filesStayAnExplicitChoiceBoundToTheOriginalAlbum() = runTest {
        val f = Fixture(); val session = session(f, LibraryIdentity(f.first), this); advanceUntilIdle()
        session.accept(LibraryEvent.BeginFiles)
        advanceUntilIdle()
        session.accept(LibraryEvent.Open(f.second)); advanceUntilIdle()
        assertEquals(f.first, f.begunDocument)
        assertEquals(PhotoImportKind.FILES, session.state.value.request!!.kind)
        assertTrue(session.claimPickerLaunch(f.batch))
        assertFalse(session.claimPickerLaunch(f.batch))
    }

    @Test fun cancelingCameraRefusesALateStillEvenAfterAnAlbumSwitch() = runTest {
        val f = Fixture(); val session = session(f, LibraryIdentity(f.first), this); advanceUntilIdle()
        session.accept(LibraryEvent.BeginCapture)
        advanceUntilIdle()
        val request = session.state.value.request!!
        assertEquals(PhotoImportKind.CAMERA, request.kind)
        assertFalse("Camera must not launch an external image picker", session.claimPickerLaunch(f.batch))
        session.accept(LibraryEvent.Open(f.second)); advanceUntilIdle()
        session.accept(LibraryEvent.CancelImport); advanceUntilIdle()
        session.accept(LibraryEvent.PhotoResult(request, f.selected)); advanceUntilIdle()
        assertEquals(f.first, f.begunDocument)
        assertEquals(listOf(f.batch), f.canceled)
        assertEquals(0, f.deliveries)
        assertNull(session.state.value.request)
    }

    @Test fun initializationLoadsRetainedMetadataAndRecoveryWithoutInventingAnImport() = runTest {
        val f = Fixture(); val session = session(f, LibraryIdentity(f.first), this); advanceUntilIdle()
        assertFalse(session.state.value.loading); assertEquals(listOf(f.first, f.second), session.state.value.documents.map { it.documentId })
        assertEquals(f.first, session.state.value.document!!.document.documentId); assertNotNull(session.state.value.recovery)
        assertNull(session.state.value.request); assertEquals(0, f.begins); assertEquals(0, f.deliveries)
    }

    @Test fun durablePreparationPrecedesOneShotLaunchAndOnlyOpaqueIdsAreRetained() = runTest {
        val f = Fixture(); val session = session(f, LibraryIdentity(f.first), this); advanceUntilIdle()
        session.accept(LibraryEvent.BeginImport)
        assertFalse(session.claimPickerLaunch(f.batch)); advanceUntilIdle()
        assertEquals(1, f.begins); assertEquals(LibraryIdentity(f.first, f.batch), session.identity())
        assertTrue(session.claimPickerLaunch(f.batch)); assertFalse(session.claimPickerLaunch(f.batch))
        assertEquals(ImportPhase.WAITING_PICKER, session.state.value.phase); assertEquals(0, f.deliveries)
    }

    @Test fun restoredRequestNeverRelaunchesButCanConsumeItsQueuedResultExactlyOnce() = runTest {
        val f = Fixture(); val session = session(f, LibraryIdentity(f.first, f.batch), this); advanceUntilIdle()
        assertEquals(ImportPhase.INTERRUPTED, session.state.value.phase); assertFalse(session.claimPickerLaunch(f.batch))
        assertEquals(0, f.begins)
        val request = PhotoImportRequest(f.batch.value)
        session.accept(LibraryEvent.PhotoResult(request, LibraryPhotoResult.Canceled)); advanceUntilIdle()
        assertEquals(1, f.deliveries); assertNull(session.identity().pending)
        assertEquals(OperationErrorCode.CANCELED, (session.state.value.results.single() as IngestResult.Rejected).error.code)
    }

    @Test fun orderedMixedOutcomesAreNotReassignedWhenNavigationChanges() = runTest {
        val f = Fixture(); val session = session(f, LibraryIdentity(f.first), this); advanceUntilIdle()
        session.accept(LibraryEvent.BeginImport); advanceUntilIdle(); val request = session.state.value.request!!
        f.block = CompletableDeferred(); f.outcomes = listOf(f.committed(), IngestResult.Rejected(f.child2, OperationError(OperationErrorCode.UNSUPPORTED_FORMAT, false, "unsupported_source", f.child2.value)))
        session.accept(LibraryEvent.PhotoResult(request, f.selected)); advanceUntilIdle()
        session.accept(LibraryEvent.Open(f.second)); advanceUntilIdle()
        f.block!!.complete(Unit); advanceUntilIdle()
        assertEquals(f.second, session.state.value.selectedId); assertEquals(f.second, session.state.value.document!!.document.documentId)
        assertEquals(f.outcomes, session.state.value.results); assertEquals(f.batch, session.state.value.resultBatch)
        assertEquals(f.first, (session.state.value.results[0] as IngestResult.Committed).documentId)
        assertEquals(1, f.deliveries)
    }

    @Test fun duplicateInFlightAndUnrelatedLateCallbacksCannotReplaceTheActiveRequest() = runTest {
        val f = Fixture(); val session = session(f, LibraryIdentity(f.first), this); advanceUntilIdle()
        session.accept(LibraryEvent.BeginImport); advanceUntilIdle(); val request = session.state.value.request!!
        session.accept(LibraryEvent.PhotoResult(PhotoImportRequest(f.otherBatch.value), f.selected)); advanceUntilIdle()
        assertEquals(0, f.deliveries); assertTrue(session.claimPickerLaunch(f.batch))
        f.block = CompletableDeferred()
        session.accept(LibraryEvent.PhotoResult(request, f.selected)); advanceUntilIdle()
        session.accept(LibraryEvent.PhotoResult(request, f.selected)); advanceUntilIdle()
        assertEquals(1, f.deliveries)
        f.block!!.complete(Unit); advanceUntilIdle(); assertNull(session.identity().pending)
    }

    @Test fun cancellationClosesTheExactBatchBeforeEndingItsWorkAndRetainsSavedPages() = runTest {
        val f = Fixture(); val session = session(f, LibraryIdentity(f.first), this); advanceUntilIdle()
        session.accept(LibraryEvent.BeginImport); advanceUntilIdle(); val request = session.state.value.request!!
        f.block = CompletableDeferred(); f.commitBeforeWait = true
        session.accept(LibraryEvent.PhotoResult(request, f.selected)); advanceUntilIdle()
        session.accept(LibraryEvent.CancelImport); advanceUntilIdle()
        assertEquals(listOf(f.batch), f.canceled); assertEquals(ImportPhase.CANCELED, session.state.value.phase)
        assertNull(session.identity().pending); assertEquals(1, session.state.value.document!!.pages.size)
        assertTrue(session.state.value.results.none { it is IngestResult.Committed })
    }

    @Test fun preparationFailureCannotProduceAPickerLaunchOrHideItsStructuredReason() = runTest {
        val f = Fixture(); val session = session(f, LibraryIdentity(f.first), this); advanceUntilIdle()
        f.failBegin = true; session.accept(LibraryEvent.BeginImport); advanceUntilIdle()
        assertNull(session.identity().pending); assertFalse(session.claimPickerLaunch(f.batch))
        assertEquals(OperationErrorCode.REVISION_CONFLICT, session.state.value.error!!.code); assertEquals(0, f.deliveries)
    }

    @Test fun refreshFailurePreservesAcceptedLibraryAndDoesNotReportAnEmptySuccessfulLibrary() = runTest {
        val f = Fixture(); val session = session(f, LibraryIdentity(f.first), this); advanceUntilIdle()
        val before = session.state.value.documents
        f.failList = true; session.accept(LibraryEvent.Refresh); advanceUntilIdle()
        assertEquals(before, session.state.value.documents); assertFalse(session.state.value.loading)
        assertEquals(OperationErrorCode.STORAGE_FAILED, session.state.value.error!!.code)
        assertFalse(session.state.value.error!!.safeMessageKey.contains("fixture-private"))
    }

    @Test fun failedAbandonmentDoesNotTrapCancelOrClaimItsStillRunningConsumerHasEnded() = runTest {
        val f = Fixture(); val session = session(f, LibraryIdentity(f.first), this); advanceUntilIdle()
        session.accept(LibraryEvent.BeginImport); advanceUntilIdle()
        f.block = CompletableDeferred(); f.failCancel = true
        session.accept(LibraryEvent.PhotoResult(session.state.value.request!!, f.selected)); advanceUntilIdle()
        session.accept(LibraryEvent.CancelImport); advanceUntilIdle()
        assertEquals("Failed abandonment must leave a retryable running copy, not a permanent Canceling trap", ImportPhase.COPYING, session.state.value.phase)
        assertEquals(f.batch, session.identity().pending); assertEquals(OperationErrorCode.STORAGE_FAILED, session.state.value.error!!.code)
        f.failCancel = false; session.accept(LibraryEvent.CancelImport); advanceUntilIdle()
        assertEquals(ImportPhase.CANCELED, session.state.value.phase); assertNull(session.identity().pending)
    }

    @Test fun pickerLaunchFailureAbandonsItsReservationAndKeepsTheSafeUnavailableReason() = runTest {
        val f = Fixture(); val session = session(f, LibraryIdentity(f.first), this); advanceUntilIdle()
        session.accept(LibraryEvent.BeginImport); advanceUntilIdle()
        session.accept(LibraryEvent.PickerFailed(session.state.value.request!!)); advanceUntilIdle()
        assertEquals(listOf(f.batch), f.canceled); assertNull(session.identity().pending)
        assertEquals(ImportPhase.CANCELED, session.state.value.phase); assertEquals(OperationErrorCode.PROVIDER_UNAVAILABLE, session.state.value.error!!.code)
    }

    @Test fun missingBundleStillFindsAndCanCancelTheDurablePendingInsertionWithoutRelaunch() = runTest {
        val f = Fixture(); f.markPending()
        val session = session(f, LibraryIdentity(f.first), this); advanceUntilIdle()
        assertEquals("Durable insertion must be discoverable without a saved BATCH", f.batch, session.identity().pending)
        assertEquals(ImportPhase.INTERRUPTED, session.state.value.phase); assertFalse(session.claimPickerLaunch(f.batch))
        assertEquals(0, f.begins); assertEquals(0, f.deliveries)
        session.accept(LibraryEvent.CancelImport); advanceUntilIdle()
        assertNull(session.identity().pending); assertFalse(session.state.value.document!!.document.hasPendingImport)
    }

    @Test fun queuedResultCanArriveBeforeAsynchronousStartupWithoutDependingOnARegistrationObject() = runTest {
        val f = Fixture(); f.startupBlock = CompletableDeferred()
        val session = session(f, LibraryIdentity(f.first, f.batch), this)
        session.accept(LibraryEvent.PhotoResult(PhotoImportRequest(f.batch.value), f.selected)); advanceUntilIdle()
        assertEquals(0, f.deliveries); assertFalse(session.claimPickerLaunch(f.batch))
        f.startupBlock!!.complete(Unit); advanceUntilIdle()
        assertEquals(1, f.deliveries); assertEquals(f.outcomes, session.state.value.results); assertEquals(0, f.begins)
    }

    @Test fun explicitCreationOpensAnEmptyDocumentWithoutClaimingAnySavedScan() = runTest {
        val f = Fixture(); val session = session(f, LibraryIdentity(), this); advanceUntilIdle()
        session.accept(LibraryEvent.NewDocument("New synthetic document")); advanceUntilIdle()
        val document = session.state.value.document!!
        assertEquals("New synthetic document", document.document.title); assertEquals(0, document.document.pageCount)
        assertTrue(document.pages.isEmpty()); assertNull(session.state.value.phase); assertNull(session.state.value.request)
        assertEquals(0, f.begins); assertEquals(0, f.deliveries)
    }

    @Test fun moreDocumentsAppendsTheNextBoundedPageWithoutDroppingEarlierMetadata() = runTest {
        val f = Fixture(); f.addDocuments(51)
        val session = session(f, LibraryIdentity(), this); advanceUntilIdle()
        assertEquals(50, session.state.value.documents.size); assertTrue(session.state.value.hasMore)
        val first = session.state.value.documents
        session.accept(LibraryEvent.More); advanceUntilIdle()
        assertEquals(53, session.state.value.documents.size); assertFalse(session.state.value.hasMore)
        assertEquals(first, session.state.value.documents.take(50))
        assertEquals(53, session.state.value.documents.map { it.documentId }.toSet().size)
    }

    @Test fun firstRunImportCreatesItsDocumentBeforePreparingTheOneShotPickerRequest() = runTest {
        val f = Fixture(); val session = session(f, LibraryIdentity(), this); advanceUntilIdle()
        session.accept(LibraryEvent.NewDocument("First import", importAfterCreate = true)); advanceUntilIdle()
        val document = session.state.value.document!!.document
        assertEquals("First import", document.title); assertEquals(0, document.pageCount)
        assertEquals("First-run import must persist its document and reservation before launch", f.batch, session.identity().pending)
        assertEquals(document.documentId, f.begunDocument); assertTrue(session.claimPickerLaunch(f.batch))
        assertEquals(0, f.deliveries)
    }

    @Test fun completedCopyRemainsRefreshingUntilItsSavedPageMetadataHasArrived() = runTest {
        val f = Fixture(); val session = session(f, LibraryIdentity(f.first), this); advanceUntilIdle()
        session.accept(LibraryEvent.BeginImport); advanceUntilIdle()
        f.publishCompletedPage = true; f.listBlock = CompletableDeferred()
        session.accept(LibraryEvent.PhotoResult(session.state.value.request!!, f.selected)); advanceUntilIdle()
        assertEquals(ImportPhase.COMPLETE, session.state.value.phase)
        assertTrue("Completed outcomes cannot advertise settled metadata while the overview still describes the pre-copy document", session.state.value.loading)
        assertTrue(session.state.value.document!!.pages.isEmpty())
        f.listBlock!!.complete(Unit); advanceUntilIdle()
        assertFalse(session.state.value.loading); assertEquals(1, session.state.value.document!!.pages.size)
        assertEquals(1, session.state.value.documents.single { it.documentId == f.first }.pageCount)
    }

    @Test fun navigationCannotAdmitASecondPreparationWhileTheFirstReservationIsSuspended() = runTest {
        val f = Fixture(); val gate = CompletableDeferred<Unit>(); val begun = mutableListOf<DocumentId>()
        val actions = object : LibraryActions by f {
            override suspend fun begin(document: DocumentSummary): PhotoImportRequest {
                begun += document.documentId
                if (document.documentId == f.first) gate.await()
                return PhotoImportRequest(if (document.documentId == f.first) f.batch.value else f.otherBatch.value)
            }
        }
        val session = session(actions, LibraryIdentity(f.first), this); advanceUntilIdle()
        try {
            session.accept(LibraryEvent.BeginImport); advanceUntilIdle()
            session.accept(LibraryEvent.Open(f.second)); advanceUntilIdle()
            session.accept(LibraryEvent.BeginImport); advanceUntilIdle()
            assertEquals("Navigation must not release import-preparation admission", listOf(f.first), begun)
        } finally { gate.complete(Unit); advanceUntilIdle() }
        assertEquals(f.second, session.state.value.selectedId); assertEquals(f.batch, session.identity().pending)
        assertTrue(session.claimPickerLaunch(f.batch)); assertFalse(session.claimPickerLaunch(f.batch))
    }

    @Test fun createThenImportRetainsTheCreatedDestinationThroughInterveningNavigation() = runTest {
        val f = Fixture(); val session = session(f, LibraryIdentity(), this); advanceUntilIdle()
        f.listBlock = CompletableDeferred()
        session.accept(LibraryEvent.NewDocument("Created import target", importAfterCreate = true)); advanceUntilIdle()
        val created = session.identity().selected!!
        session.accept(LibraryEvent.Open(f.second)); advanceUntilIdle()
        f.listBlock!!.complete(Unit); advanceUntilIdle()
        assertEquals("First-run reservation must use the created ID, not later navigation", created, f.begunDocument)
        assertEquals(f.second, session.identity().selected); assertEquals(f.batch, session.identity().pending)
    }

    @Test fun preCopyOverviewCannotReplaceANewerCompletedOverview() = runTest { metadataInterleaving(releaseOldFirst = false, failOld = false) }
    @Test fun preCopyReadCannotClearTheBusyStateOfANewerCompletionRefresh() = runTest { metadataInterleaving(releaseOldFirst = true, failOld = false) }
    @Test fun staleReadFailureCannotReplaceTheCurrentSuccessfulRefresh() = runTest { metadataInterleaving(releaseOldFirst = false, failOld = true) }

    @Test fun unrelatedMetadataFailureDoesNotRelabelARunningCopyAsInterrupted() = runTest {
        val f = Fixture(); val session = session(f, LibraryIdentity(f.first), this); advanceUntilIdle()
        session.accept(LibraryEvent.BeginImport); advanceUntilIdle(); f.block = CompletableDeferred()
        session.accept(LibraryEvent.PhotoResult(session.state.value.request!!, f.selected)); advanceUntilIdle()
        try {
            f.failList = true; session.accept(LibraryEvent.Refresh); advanceUntilIdle()
            assertEquals("Metadata read failure cannot change the lifetime of running copy IO", ImportPhase.COPYING, session.state.value.phase)
            assertEquals(OperationErrorCode.STORAGE_FAILED, session.state.value.error!!.code)
        } finally { f.failList = false; f.block!!.complete(Unit); advanceUntilIdle() }
    }

    @Test fun duplicateAfterReadFailureCannotReplaceTheConsumerWhichCancellationMustAwait() = runTest {
        val f = Fixture(); val release = CompletableDeferred<Unit>(); var deliveries = 0
        val actions = object : LibraryActions by f {
            override suspend fun deliver(request: PhotoImportRequest, selection: LibraryPhotoResult): List<IngestResult> {
                deliveries++
                if (deliveries == 1) withContext(NonCancellable) { release.await() }
                return listOf(IngestResult.Pending(f.batch, FileTransactionState.RETRYABLE))
            }
        }
        val session = session(actions, LibraryIdentity(f.first), this); advanceUntilIdle()
        session.accept(LibraryEvent.BeginImport); advanceUntilIdle(); val request = session.state.value.request!!
        session.accept(LibraryEvent.PhotoResult(request, f.selected)); advanceUntilIdle()
        try {
            f.failList = true; session.accept(LibraryEvent.Refresh); advanceUntilIdle()
            session.accept(LibraryEvent.PhotoResult(request, f.selected)); advanceUntilIdle()
            f.failList = false; session.accept(LibraryEvent.CancelImport); advanceUntilIdle()
            assertEquals("Cancellation must still await the original uninterruptible consumer", ImportPhase.CANCELING, session.state.value.phase)
            assertEquals(1, deliveries); assertEquals(f.batch, session.identity().pending)
        } finally { f.failList = false; release.complete(Unit); advanceUntilIdle() }
        assertEquals(ImportPhase.CANCELED, session.state.value.phase); assertNull(session.identity().pending)
    }

    @Test fun authoritativeMissingSavedRequestCanBeDismissedForExplicitReselection() = runTest {
        val f = Fixture()
        val actions = object : LibraryActions by f {
            override suspend fun cancel(request: PhotoImportRequest) {
                assertEquals(f.otherBatch.value, request.batchId)
                throw RepositoryFailure(OperationError(OperationErrorCode.SESSION_EXPIRED, false, "import_session_unavailable", request.batchId))
            }
        }
        val session = session(actions, LibraryIdentity(f.first, f.otherBatch), this); advanceUntilIdle()
        session.accept(LibraryEvent.CancelImport); advanceUntilIdle()
        assertNull("Authoritatively absent saved identity must not permanently disable import", session.identity().pending)
        assertEquals(OperationErrorCode.SESSION_EXPIRED, session.state.value.error!!.code)
        assertTrue(f.canceled.isEmpty()); assertEquals(0, f.deliveries)
        session.accept(LibraryEvent.BeginImport); advanceUntilIdle()
        assertEquals(f.batch, session.identity().pending); assertEquals(1, f.begins)
    }

    @Test fun wrongKindSavedIdentityDoesNotAbandonTheGenuinePendingBatch() = runTest {
        val f = Fixture(); f.markPending()
        val actions = object : LibraryActions by f {
            override suspend fun cancel(request: PhotoImportRequest) {
                if (request.batchId == f.child2.value) throw RepositoryFailure(OperationError(OperationErrorCode.SESSION_EXPIRED, false, "import_session_unavailable", request.batchId))
                f.cancel(request)
            }
        }
        val session = session(actions, LibraryIdentity(f.first, f.child2), this); advanceUntilIdle()
        session.accept(LibraryEvent.CancelImport); advanceUntilIdle()
        assertEquals("Recover the genuine parent without canceling or replacing it", f.batch, session.identity().pending)
        assertTrue(f.canceled.isEmpty()); assertEquals(0, f.deliveries); assertEquals(0, f.begins)
        session.accept(LibraryEvent.CancelImport); advanceUntilIdle()
        assertEquals(listOf(f.batch), f.canceled); assertNull(session.identity().pending)
    }

    @Test fun transientAbandonmentStorageFailureIsNotProofThatTheSavedRequestIsAbsent() = runTest {
        val f = Fixture(); f.failCancel = true
        val session = session(f, LibraryIdentity(f.first, f.batch), this); advanceUntilIdle()
        session.accept(LibraryEvent.CancelImport); advanceUntilIdle()
        assertEquals(f.batch, session.identity().pending); assertEquals(OperationErrorCode.STORAGE_FAILED, session.state.value.error!!.code)
        assertEquals(0, f.deliveries); assertEquals(0, f.begins)
    }

    private suspend fun TestScope.metadataInterleaving(releaseOldFirst: Boolean, failOld: Boolean) {
        val f = Fixture(); val oldRelease = CompletableDeferred<Unit>(); val captured = CompletableDeferred<Unit>(); var capture = false
        val actions = object : LibraryActions by f {
            override suspend fun load(id: DocumentId): DocumentOverview {
                val snapshot = f.load(id)
                if (capture) {
                    capture = false; captured.complete(Unit); oldRelease.await()
                    if (failOld) throw java.io.IOException("Synthetic delayed old read failure")
                }
                return snapshot
            }
        }
        val session = session(actions, LibraryIdentity(f.first), this); advanceUntilIdle()
        session.accept(LibraryEvent.BeginImport); advanceUntilIdle()
        f.block = CompletableDeferred()
        session.accept(LibraryEvent.PhotoResult(session.state.value.request!!, f.selected)); advanceUntilIdle()
        capture = true; session.accept(LibraryEvent.Refresh); advanceUntilIdle(); assertTrue(captured.isCompleted)
        try {
            f.publishCompletedPage = true
            if (releaseOldFirst) f.listBlock = CompletableDeferred()
            f.block!!.complete(Unit); advanceUntilIdle()
            oldRelease.complete(Unit); advanceUntilIdle()
            if (releaseOldFirst) {
                assertTrue("A stale read must not settle a newer completion refresh", session.state.value.loading)
                f.listBlock!!.complete(Unit); advanceUntilIdle()
            }
            assertNull("A stale read failure must not replace current successful metadata", session.state.value.error)
            assertEquals("Older snapshots must not overwrite committed page membership", listOf(f.committed().pageId), session.state.value.document!!.pages.map { it.pageId })
            assertEquals(1, session.state.value.document!!.document.pageCount); assertFalse(session.state.value.loading)
        } finally { oldRelease.complete(Unit); f.block!!.complete(Unit); f.listBlock?.complete(Unit); advanceUntilIdle() }
    }

    private class Fixture : LibraryActions {
        val first = DocumentId("11111111-1111-4111-8111-111111111111")
        val second = DocumentId("22222222-2222-4222-8222-222222222222")
        val batch = ImportSessionId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        val otherBatch = ImportSessionId("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
        private val child = ImportSessionId("cccccccc-cccc-4ccc-8ccc-cccccccccccc")
        val child2 = ImportSessionId("dddddddd-dddd-4ddd-8ddd-dddddddddddd")
        private val page = PageId("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee")
        private val source = SourceId("ffffffff-ffff-4fff-8fff-ffffffffffff")
        private val docs = linkedMapOf(first to DocumentOverview(DocumentSummary(first, "First", 1, 0, false), emptyList()), second to DocumentOverview(DocumentSummary(second, "Second", 1, 0, false), emptyList()))
        var begins = 0; var deliveries = 0; var failBegin = false; var failList = false; var failCancel = false
        var begunDocument: DocumentId? = null
        val selected = LibraryPhotoResult.Selected(listOf("content://fixture.documents/first", "content://fixture.documents/second"), 0)
        var block: CompletableDeferred<Unit>? = null; var commitBeforeWait = false; var startupBlock: CompletableDeferred<Unit>? = null
        var publishCompletedPage = false; var listBlock: CompletableDeferred<Unit>? = null
        val canceled = mutableListOf<ImportSessionId>()
        var outcomes: List<IngestResult> = listOf(committed(), IngestResult.Rejected(child2, OperationError(OperationErrorCode.UNSUPPORTED_FORMAT, false, "unsupported_source", child2.value)))
        override suspend fun startup(): RecoveryReport { startupBlock?.await(); return RecoveryReport(0, 0, 0, 0, 0, emptyList()) }
        override suspend fun list(after: DocumentId?, limit: Int): List<DocumentSummary> {
            listBlock?.await()
            if (failList) throw java.io.IOException("fixture-private title must not escape")
            return docs.values.map { it.document }.sortedBy { it.documentId.value }.filter { after == null || it.documentId.value > after.value }.take(limit)
        }
        override suspend fun load(id: DocumentId) = docs.getValue(id)
        override suspend fun activeImport(id: DocumentId): ImportSessionId? = if (docs.getValue(id).document.hasPendingImport) batch else null
        override suspend fun create(title: String): DocumentId {
            val id = DocumentId("33333333-3333-4333-8333-000000000001")
            docs[id] = DocumentOverview(DocumentSummary(id, title, 1, 0, false), emptyList())
            return id
        }
        override suspend fun begin(document: DocumentSummary): PhotoImportRequest {
            assertTrue(document.documentId in docs)
            if (failBegin) throw RepositoryFailure(OperationError(OperationErrorCode.REVISION_CONFLICT, false, "document_revision_changed", first.value))
            begins++; begunDocument = document.documentId; return PhotoImportRequest(batch.value)
        }
        override suspend fun deliver(request: PhotoImportRequest, selection: LibraryPhotoResult): List<IngestResult> {
            assertEquals(batch.value, request.batchId); deliveries++
            if (selection == LibraryPhotoResult.Canceled) return listOf(IngestResult.Rejected(batch, OperationError(OperationErrorCode.CANCELED, false, "photo_selection_canceled", batch.value)))
            assertEquals(selected, selection)
            if (commitBeforeWait) docs[first] = DocumentOverview(DocumentSummary(first, "First", 2, 1, true), listOf(DocumentPage(page, source, 1, 0)))
            block?.await()
            if (publishCompletedPage) docs[first] = DocumentOverview(DocumentSummary(first, "First", 2, 1, false), listOf(DocumentPage(page, source, 1, 0)))
            return outcomes
        }
        override suspend fun cancel(request: PhotoImportRequest) {
            if (failCancel) throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "synthetic_cancel_io", request.batchId))
            canceled += ImportSessionId(request.batchId)
            docs[first] = docs.getValue(first).let { it.copy(document = it.document.copy(hasPendingImport = false)) }
        }
        fun committed() = IngestResult.Committed(child, first, page, source, "a".repeat(64), "b".repeat(64), 2)
        fun markPending() { docs[first] = docs.getValue(first).let { it.copy(document = it.document.copy(hasPendingImport = true)) } }
        fun addDocuments(count: Int) {
            for (i in 0 until count) {
                val id = DocumentId("33333333-3333-4333-8333-" + "%012x".format(i + 1))
                docs[id] = DocumentOverview(DocumentSummary(id, "Additional", 1, 0, false), emptyList())
            }
        }
    }

    private companion object {
        fun session(actions: LibraryActions, identity: LibraryIdentity, scope: CoroutineScope): LibrarySession {
            val type = try { Class.forName("app.pristinepage.library.ImportLibrarySession") }
            catch (_: ClassNotFoundException) { throw AssertionError("Lifecycle-retained import/library session is absent") }
            return try { type.getConstructor(LibraryActions::class.java, LibraryIdentity::class.java, CoroutineScope::class.java).newInstance(actions, identity, scope) as LibrarySession }
            catch (failure: InvocationTargetException) { throw failure.targetException }
        }
    }
}
