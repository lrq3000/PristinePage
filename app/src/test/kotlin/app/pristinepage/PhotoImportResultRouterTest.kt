package app.pristinepage

import app.pristinepage.capture.PhotoImportController
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
}
