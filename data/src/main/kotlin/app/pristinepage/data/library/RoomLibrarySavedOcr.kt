package app.pristinepage.data.library

import app.pristinepage.core.ocr.OcrText
import app.pristinepage.data.db.JobRow
import app.pristinepage.data.db.OcrPageWorkRow
import app.pristinepage.data.db.OcrResultRow
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.PageId
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.repository.OcrJobInput
import app.pristinepage.data.repository.RoomOcrResults
import app.pristinepage.data.repository.RoomOcrSearch

/** Only terminal producing provenance and exact text travel. This type intentionally has no generated text-bearing toString. */
internal class LibrarySavedOcr(
    val producerId: JobId, val intent: OcrJobInput, val text: OcrText,
    val attempt: Long, val createdAt: Long, val updatedAt: Long,
)

/** Rebuild one saved result in a fresh staged catalog. Page/revision/source and any destination remap must already be applied by the enclosing writer. */
internal class RoomLibrarySavedOcr(private val database: PristineDatabase) {
    fun restoreInTransaction(saved: LibrarySavedOcr) {
        check(database.inTransaction())
        val work = database.ocrPageWork()
        val results = database.ocrResults()
        val intent = saved.intent
        val id = PageId(intent.input.pageId)
        val search = RoomOcrSearch(database)
        if (saved.attempt !in 1L..3L || intent.configuration.languagePacks == null ||
            !search.libraryInputMatches(intent) ||
            work.pointerIsBounded(id.value) != null || results.libraryResultRevision(id.value) != null
        ) invalid(id)
        val input = intent.encode()
        val job = saved.producerId.value
        work.insertJob(JobRow(job, OcrJobInput.KIND, intent.key, intent.digest, null, null, input, "SUCCEEDED",
            saved.attempt, null, null, saved.attempt, null, null, saved.createdAt, saved.updatedAt))
        work.insertPointer(OcrPageWorkRow(id.value, job, intent.generation, intent.digest, input.size, job, RoomOcrResults.PROJECTION_VERSION))
        val result = OcrResultRow.fromRecognition(intent.input, intent.configuration, saved.text)
        if (!results.writeResult(result) || !results.matchesResult(result)) invalid(id)
        val reopened = search.captureForLibraryInTransaction(intent.documentId, id, intent.input.recipeRevision)
            ?: invalid(id)
        if (reopened.producerId != saved.producerId || reopened.intent != intent || reopened.text.value != saved.text.value ||
            reopened.attempt != saved.attempt || reopened.createdAt != saved.createdAt || reopened.updatedAt != saved.updatedAt
        ) invalid(id)
    }

    private fun invalid(page: PageId): Nothing = throw RepositoryFailure(
        OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "library_saved_ocr_invalid", page.value),
    )
}
