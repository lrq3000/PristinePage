package app.pristinepage.data

import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.core.ocr.OcrInput
import app.pristinepage.core.ocr.OcrLayout
import app.pristinepage.data.db.JobRow
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.SourceId
import app.pristinepage.data.repository.OcrJobInput
import org.junit.Assert.*
import org.junit.Test

class OcrJobInputTest {
    private val page = "aaaaaaaa-aaaa-4aaa-8aaa-000000000004"
    private val input = OcrJobInput(
        DocumentId("aaaaaaaa-aaaa-4aaa-8aaa-000000000001"),
        OcrInput(page, 7, "b".repeat(64)),
        SourceId("aaaaaaaa-aaaa-4aaa-8aaa-000000000002"),
        "a".repeat(64),
        2,
        OcrConfiguration("fixture", 1, OcrLayout.AUTOMATIC),
    )
    private val job = JobId("aaaaaaaa-aaaa-4aaa-8aaa-000000000009")
    private val digest = "b28e2a90571b649df36a05e4f3a9848980ec65cf17a33e2a1c86a10b01607a4e"

    @Test fun versionOneIntentHoldsOnlyCanonicalBindings() {
        assertEquals("ocr-page-v1/$page/2", input.key)
        assertEquals(289, input.encode().size)
        assertEquals(digest, input.digest)
        input.encode().fill(0)
        assertEquals(digest, input.digest)
        input.verify(job, validRow())
        assertNotEquals(input.key, input.copy(generation = 3).key)
        assertNotEquals(input.digest, input.copy(configuration = input.configuration.copy(layout = OcrLayout.SPARSE_TEXT)).digest)
    }

    @Test fun staleWrongKindAndMisboundInputsRefuseWithoutAJobLease() {
        val row = validRow()
        for (candidate in listOf(
            row.copy(kind = "PAGE_RENDER"),
            row.copy(jobId = "aaaaaaaa-aaaa-4aaa-8aaa-00000000000a"),
            row.copy(snapshotId = "aaaaaaaa-aaaa-4aaa-8aaa-00000000000b"),
            row.copy(pageOrdinal = 0),
            row.copy(canonicalInputSha256 = "f".repeat(64)),
            row.copy(input = row.input + 0.toByte()),
            row.copy(input = row.input.copyOf().also { it[it.lastIndex] = 0 }),
            row.copy(input = input.copy(input = input.input.copy(recipeRevision = 8)).encode()),
        )) {
            val failure = assertThrows(RepositoryFailure::class.java) { input.verify(job, candidate) }
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, failure.error.code)
            assertEquals(job.value, failure.error.affectedId)
        }
        assertThrows(RepositoryFailure::class.java) { input.copy(generation = 3).verify(job, row) }
    }

    private fun validRow() = JobRow(job.value, "PAGE_OCR", "ocr-page-v1/$page/2", digest, null, null,
        input.encode(), "ADMITTED", 0, null, null, 0, null, null, 0, 0)
}
