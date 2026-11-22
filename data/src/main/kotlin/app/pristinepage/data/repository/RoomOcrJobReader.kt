package app.pristinepage.data.repository

import app.pristinepage.data.db.JobRow
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.requireOpaqueId

/** The store must reconstruct the expected v2 binding from trusted metadata in its existing Room transaction. A successful read proves stored identity, not current page ownership, a source pin, or a lease. */
internal class RoomOcrJobReader(private val database: PristineDatabase) {
    fun readInTransaction(id: JobId, expected: OcrJobInput): JobRow? {
        check(database.inTransaction()) { "OCR job reads require the owning Room transaction" }
        if (expected.configuration.languagePacks == null) invalid(id)

        val jobs = database.jobLeases()
        when (jobs.ocrJobReadIsBounded(id.value, expected.key, expected.digest, expected.encode().size)) {
            null -> return null
            false -> invalid(id)
            true -> Unit
        }
        val row = jobs.job(id.value) ?: invalid(id)
        expected.verify(id, row)
        try { row.leaseOwner?.let(::requireOpaqueId) }
        catch (_: IllegalArgumentException) { invalid(id) }
        return row
    }

    private fun invalid(id: JobId): Nothing = throw RepositoryFailure(
        OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "ocr_job_metadata_invalid", id.value),
    )
}
