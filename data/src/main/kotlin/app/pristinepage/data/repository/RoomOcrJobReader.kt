package app.pristinepage.data.repository

import app.pristinepage.data.db.JobRow
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.PageId
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.requireOpaqueId

/** The store must reconstruct the expected v2 binding from trusted metadata in its existing Room transaction. A successful read proves stored identity, not current page ownership, a source pin, or a lease. */
internal class RoomOcrJobReader(private val database: PristineDatabase) {
    fun readInTransaction(id: JobId, expected: OcrJobInput): JobRow? {
        check(database.inTransaction()) { "OCR job reads require the owning Room transaction" }
        if (expected.configuration.languagePacks == null) invalid(id)
        val row = readBounded(id, expected.key, expected.digest, expected.encode().size) ?: return null
        expected.verify(id, row)
        return row
    }

    /** A bounded current pointer supplies the stored identity; its page and source authority are verified separately in the same transaction. */
    fun recoverInTransaction(id: JobId, page: PageId, generation: Long, digest: String, inputBytes: Int): Pair<OcrJobInput, JobRow> {
        check(database.inTransaction()) { "OCR job reads require the owning Room transaction" }
        val row = readBounded(id, "ocr-page-v2/${page.value}/$generation", digest, inputBytes) ?: invalid(id)
        val recovered = try { OcrJobInput.decodeV2(row.input) }
        catch (_: IllegalArgumentException) { invalid(id) }
        recovered.verify(id, row)
        if (recovered.input.pageId != page.value || recovered.generation != generation) invalid(id)
        return recovered to row
    }

    /** While a new generation runs, the producer has no current-pointer extent. Admit a scalar locator first, never the old input BLOB. */
    fun readProducerInTransaction(id: JobId, page: PageId): Pair<OcrJobInput, JobRow>? {
        check(database.inTransaction()) { "OCR producing-job reads require the owning Room transaction" }
        val locator = database.jobLeases().ocrProducerLocator(id.value) ?: return null
        val generation = locator.idempotenceKey.substringAfterLast('/').toLongOrNull() ?: return null
        if (generation < 1 || locator.idempotenceKey != "ocr-page-v2/${page.value}/$generation") return null
        return recoverInTransaction(id, page, generation, locator.digest, locator.extent)
    }

    private fun readBounded(id: JobId, key: String, digest: String, inputBytes: Int): JobRow? {
        val jobs = database.jobLeases()
        when (jobs.ocrJobReadIsBounded(id.value, key, digest, inputBytes)) {
            null -> return null
            false -> invalid(id)
            true -> Unit
        }
        val row = jobs.job(id.value) ?: invalid(id)
        try { row.leaseOwner?.let(::requireOpaqueId) }
        catch (_: IllegalArgumentException) { invalid(id) }
        return row
    }

    private fun invalid(id: JobId): Nothing = throw RepositoryFailure(
        OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "ocr_job_metadata_invalid", id.value),
    )
}
