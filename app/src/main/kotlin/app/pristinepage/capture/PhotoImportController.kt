package app.pristinepage.capture

import android.net.Uri
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.BoundSourceIngest
import app.pristinepage.data.repository.ImportSelections
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Routes one picker callback to its originally persisted BATCH, never the currently visible document. Selection/bindings commit atomically before serial source IO; only SourceStore decides byte acceptance and durable page visibility. No provider reopening, grant acquisition, coroutine launch or UI lifetime is hidden here. */
class PhotoImportController(private val selections: ImportSelections, private val sources: BoundSourceIngest) {
    suspend fun import(uris: List<Uri>, session: ImportSessionId): List<IngestResult> {
        checkCount(uris.size, session)
        return importProviderReferences(uris.map { it.toString() }, session)
    }

    /** Private process-local adapter boundary, also usable by an explicitly retained selection. References never enter saved-state bundles, scheduler input, logs or result/error messages. Android selection/grant validation is distinct from a successful metadata handoff. */
    suspend fun importProviderReferences(references: List<String>, session: ImportSessionId): List<IngestResult> {
        val context = currentCoroutineContext()
        context.ensureActive()
        checkCount(references.size, session)
        val selected = references.toList()
        val batch = selections.prepareSelection(session, selected)
        context.ensureActive()
        if (batch.parentId != session || batch.childIds.size != selected.size || batch.childIds.toSet().size != selected.size || session in batch.childIds) refuse(session, OperationErrorCode.INTEGRITY_FAILED, "invalid_import_reservation")
        if (selected.isEmpty()) return listOf(IngestResult.Rejected(session, OperationError(OperationErrorCode.CANCELED, false, "photo_selection_canceled", session.value)))
        val results = ArrayList<IngestResult>(selected.size)
        selected.forEachIndexed { ordinal, reference ->
            context.ensureActive()
            val child = batch.childIds[ordinal]
            val result = sources.ingestBoundProvider(reference, child)
            context.ensureActive()
            val actualSession = when (result) {
                is IngestResult.Committed -> result.sessionId
                is IngestResult.Rejected -> result.sessionId
                is IngestResult.Pending -> result.sessionId
            }
            if (actualSession != child) refuse(session, OperationErrorCode.INTEGRITY_FAILED, "import_result_binding_mismatch")
            results += result
        }
        return results
    }

    private fun checkCount(count: Int, session: ImportSessionId) { if (count > 100) refuse(session, OperationErrorCode.RESOURCE_LIMIT, "document_page_limit") }
    private fun refuse(session: ImportSessionId, code: OperationErrorCode, key: String): Nothing = throw RepositoryFailure(OperationError(code, false, key, session.value))
}
