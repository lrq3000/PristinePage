package app.pristinepage.data.repository

import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.core.ocr.OcrInput
import app.pristinepage.core.pipeline.requireSha256
import app.pristinepage.data.db.JobRow
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.SourceId

/** Durable intent for one accepted page and effective OCR configuration. It deliberately excludes the recognized text, query, attempts, and lease fields. */
internal data class OcrJobInput(
    val documentId: DocumentId,
    val input: OcrInput,
    val sourceId: SourceId,
    val recipeSha256: String,
    val generation: Long,
    val configuration: OcrConfiguration,
) {
    init {
        require(generation > 0)
        requireSha256(recipeSha256)
    }

    // Every component is bounded canonical ASCII with no NUL; explicit field order and domain distinguish this input from other Job kinds.
    private val canonical by lazy {
        listOf(
            "pristinepage-ocr-page-v1", documentId.value, input.pageId, sourceId.value,
            input.recipeRevision.toString(), recipeSha256, input.sourcePixelSha256,
            generation.toString(), configuration.engineId, configuration.engineVersion.toString(), configuration.layout.name,
        ).joinToString("\u0000").encodeToByteArray()
    }
    val key: String get() = "ocr-page-v1/${input.pageId}/$generation"
    val digest: String by lazy { ExportAssemblyInput.digest(canonical) }
    fun encode(): ByteArray = canonical.copyOf()

    /** The caller supplies the current expected binding after raw SQL admission. A matching row is not a lease or permission to read pixels. */
    fun verify(expectedJobId: JobId, row: JobRow) {
        if (row.jobId != expectedJobId.value || row.kind != KIND || row.snapshotId != null || row.pageOrdinal != null ||
            row.idempotenceKey != key || row.canonicalInputSha256 != digest || !row.input.contentEquals(canonical)
        ) throw RepositoryFailure(OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "ocr_job_input_invalid", expectedJobId.value))
    }

    companion object {
        const val KIND = "PAGE_OCR"
    }
}
