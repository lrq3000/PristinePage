package app.pristinepage.capture

import app.pristinepage.data.model.IngestResult
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.PageId
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.repository.ImportSessionLifecycle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Consumes one request-bound callback in the caller's structured scope. The controller/storage retain ordered receipt, capacity, replay and cancellation authority; no grant acquisition, detached work or current-document lookup is hidden here. */
class PhotoImportResultRouter(private val imports: PhotoImportController, private val lifecycle: ImportSessionLifecycle,
    private val onPagesSaved: ((DocumentId, List<PageId>) -> Unit)? = null) {
    suspend fun deliver(request: PhotoImportRequest, selection: PhotoSelection): List<IngestResult> {
        currentCoroutineContext().ensureActive()
        return when (selection) {
            is PhotoSelection.Selected -> try {
                imports.import(selection.uris, request.session, onPagesSaved)
            } catch (failure: RepositoryFailure) {
                val error = failure.error
                if (request.kind == PhotoImportKind.CAMERA || error.code != OperationErrorCode.RESOURCE_LIMIT ||
                    error.affectedId != request.batchId) throw failure
                // Another selection may have bound after the refusal. Only close the still-empty reservation.
                lifecycle.cancelUnboundBatch(request.session)
                listOf(IngestResult.Rejected(request.session, error))
            }
            PhotoSelection.Canceled -> abandon(request, OperationError(OperationErrorCode.CANCELED, false, "photo_selection_canceled", request.batchId))
            is PhotoSelection.Rejected -> abandon(request, selection.error)
        }
    }

    private suspend fun abandon(request: PhotoImportRequest, error: OperationError): List<IngestResult> {
        // Abandonment must not require new-selection eligibility: even an expired picker must close its exact reservation and permit the next import. This preserves terminal children/bytes and never imports a valid prefix or redirects to another batch.
        lifecycle.cancelBatch(request.session)
        return listOf(IngestResult.Rejected(request.session, error.copy(affectedId = request.batchId)))
    }
}
