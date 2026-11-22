package app.pristinepage.data.repository

import app.pristinepage.data.db.JobRow
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.PageId
import app.pristinepage.data.model.RepositoryFailure

/** Resolves durable intent only. No lease or source-byte access is granted by this metadata read. */
internal class RoomOcrCurrentWork(private val database: PristineDatabase) {
    private val metadata = RoomMetadataAccess(database)
    private val jobs = RoomOcrJobReader(database)

    data class Current(
        val intent: OcrJobInput,
        val job: JobRow,
        val resultJobId: JobId?,
        val projectionVersion: Int?,
    )

    fun readInTransaction(page: PageId): Current? {
        val current = readStoredInTransaction(page) ?: return null
        if (!isSavedInputInTransaction(current.intent)) throw RepositoryFailure(
            OperationError(OperationErrorCode.REVISION_CONFLICT, false, "ocr_job_no_longer_current", page.value),
        )
        return current
    }

    /** Only generation replacement may use a previously valid pointer whose input is no longer current. */
    fun readStoredInTransaction(page: PageId): Current? {
        check(database.inTransaction()) { "OCR current work requires the owning Room transaction" }
        val work = database.ocrPageWork()
        when (work.pointerIsBounded(page.value)) {
            null -> return null
            false -> invalid(page)
            true -> Unit
        }
        val row = work.pointer(page.value) ?: invalid(page)
        val (jobId, resultJobId) = try { JobId(row.jobId) to row.resultJobId?.let(::JobId) }
        catch (_: IllegalArgumentException) { invalid(page) }
        val (intent, job) = jobs.recoverInTransaction(jobId, page, row.generation, row.inputSha256, row.inputBytes)
        return Current(intent, job, resultJobId, row.projectionVersion)
    }

    fun isSavedInputInTransaction(intent: OcrJobInput): Boolean {
        check(database.inTransaction()) { "OCR page verification requires the owning Room transaction" }
        val document = intent.documentId.value
        return database.ocrPageWork().liveInputMatches(
            intent.input.pageId, document, intent.sourceId.value, intent.input.recipeRevision, intent.recipeSha256,
            intent.input.sourcePixelSha256,
        ) && !metadata.isHiddenAtelierDraft(document)
    }

    private fun invalid(page: PageId): Nothing = throw RepositoryFailure(
        OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "ocr_work_pointer_invalid", page.value),
    )
}
