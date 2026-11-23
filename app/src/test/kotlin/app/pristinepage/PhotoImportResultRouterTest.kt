package app.pristinepage

import app.pristinepage.capture.PhotoImportController
import app.pristinepage.capture.PhotoImportKind
import app.pristinepage.capture.PhotoImportRequest
import app.pristinepage.capture.PhotoImportResultRouter
import app.pristinepage.capture.PhotoSelection
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.BoundSourceIngest
import app.pristinepage.data.repository.ImportSelections
import app.pristinepage.data.repository.ImportSessionLifecycle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Pins our dispatch choice when new-selection eligibility is lost. The consuming native regression separately expires a real Room BATCH and proves the next same-document import is unblocked. */
class PhotoImportResultRouterTest {
    @Test fun pickerCancellationUsesAbandonmentWithoutRequiringFreshSelectionEligibility() = runBlocking {
        val id = ImportSessionId("11111111-1111-4111-8111-111111111111")
        var selectionCalls = 0
        var abandonmentCalls = 0
        val imports = PhotoImportController(ImportSelections { _, _ ->
            selectionCalls++
            throw RepositoryFailure(OperationError(OperationErrorCode.SESSION_EXPIRED, false, "import_session_unavailable", id.value))
        }, object : BoundSourceIngest {
            override suspend fun ingestBoundProvider(boundReference: String, session: ImportSessionId): IngestResult = throw AssertionError("Cancellation opened a source")
        })
        val router = PhotoImportResultRouter(imports, ImportSessionLifecycle { session ->
            assertEquals(id, session); abandonmentCalls++
            ImportBatch(session, emptyList())
        })
        val outcome = runCatching { router.deliver(PhotoImportRequest(id.value), PhotoSelection.Canceled) }
        assertTrue("Picker cancellation must use abandonment, not fresh selection eligibility", outcome.isSuccess)
        val rejected = outcome.getOrThrow().single() as IngestResult.Rejected
        assertEquals(id, rejected.sessionId); assertEquals(OperationErrorCode.CANCELED, rejected.error.code)
        assertEquals(id.value, rejected.error.affectedId); assertEquals(0, selectionCalls); assertEquals(1, abandonmentCalls)
    }

    @Test fun aStagingRefusalClosesOnlyTheBoundBatchAndPreservesTheReason() = runBlocking {
        checkStagingRefusal(PhotoImportKind.FILES, selectionWon = false)
    }

    @Test fun aStagingRefusalDoesNotCancelASelectionThatBoundMeanwhile() = runBlocking {
        checkStagingRefusal(PhotoImportKind.GALLERY, selectionWon = true)
    }

    @Test fun lateCameraCapacityRefusalLeavesTheHandoffBatchPending() = runBlocking {
        checkStagingRefusal(PhotoImportKind.CAMERA, selectionWon = false)
    }

    private suspend fun checkStagingRefusal(kind: PhotoImportKind, selectionWon: Boolean) {
        val id = ImportSessionId("11111111-1111-4111-8111-111111111111")
        val refusal = OperationError(OperationErrorCode.RESOURCE_LIMIT, false, "draft_staging_full", id.value)
        val imports = PhotoImportController(ImportSelections { batch, _ ->
            assertEquals(id, batch)
            throw RepositoryFailure(refusal)
        }, object : BoundSourceIngest {
            override suspend fun ingestBoundProvider(boundReference: String, session: ImportSessionId): IngestResult =
                throw AssertionError("A refused selection opened a source")
        })
        val router = PhotoImportResultRouter(imports, object : ImportSessionLifecycle {
            override suspend fun cancelBatch(sessionId: ImportSessionId): ImportBatch =
                throw AssertionError("Capacity cleanup must not abandon a potentially bound batch")
            override suspend fun cancelUnboundBatch(sessionId: ImportSessionId): Boolean {
                assertEquals(id, sessionId)
                if (kind == PhotoImportKind.CAMERA) throw AssertionError("The refused camera handoff must remain pending")
                return !selectionWon
            }
        })
        // An empty list sidesteps the host Android Uri stub. The reservation adapter models the refused bind, not the platform picker.
        val attempt = runCatching { router.deliver(PhotoImportRequest(id.value, kind), PhotoSelection.Selected(emptyList(), 0)).single() }
        if (kind == PhotoImportKind.CAMERA) {
            assertEquals(refusal, (attempt.exceptionOrNull() as? RepositoryFailure)?.error)
        } else {
            assertEquals(IngestResult.Rejected(id, refusal), attempt.getOrThrow())
        }
    }
}
