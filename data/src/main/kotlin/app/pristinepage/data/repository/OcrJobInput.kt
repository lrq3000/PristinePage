package app.pristinepage.data.repository

import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.core.ocr.OcrInput
import app.pristinepage.core.ocr.OcrLanguagePack
import app.pristinepage.core.ocr.OcrLanguagePacks
import app.pristinepage.core.ocr.OcrLayout
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

    private val version: Int get() = if (configuration.languagePacks == null) 1 else 2

    // Every component is bounded canonical ASCII with no NUL. V1 remains byte-identical; v2 appends ordered pack identities.
    private val canonical by lazy {
        buildList {
            addAll(listOf(
                "pristinepage-ocr-page-v$version", documentId.value, input.pageId, sourceId.value,
                input.recipeRevision.toString(), recipeSha256, input.sourcePixelSha256,
                generation.toString(), configuration.engineId, configuration.engineVersion.toString(), configuration.layout.name,
            ))
            configuration.languagePacks?.ordered?.let { packs ->
                add(packs.size.toString())
                for (pack in packs) {
                    add(pack.code)
                    add(pack.sha256)
                }
            }
        }.joinToString("\u0000").encodeToByteArray()
    }
    val key: String get() = "ocr-page-v$version/${input.pageId}/$generation"
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
        private const val V2_TAG = "pristinepage-ocr-page-v2"
        private const val V2_HEADER_FIELDS = 12
        private const val MAX_V2_BYTES = 3_532

        /** Decode an already byte-admitted record; syntax does not establish current ownership, installed pack bytes, or a lease. */
        fun decodeV2(bytes: ByteArray): OcrJobInput {
            require(bytes.size in 1..MAX_V2_BYTES)
            val snapshot = bytes.copyOf()
            require(snapshot.all { it >= 0 })
            val fields = snapshot.decodeToString().split('\u0000', limit = V2_HEADER_FIELDS + 2 * 32 + 1)
            require(fields.size > V2_HEADER_FIELDS && fields[0] == V2_TAG)

            val count = fields[11].toIntOrNull()
            require(count != null && count in 1..32 && fields.size == V2_HEADER_FIELDS + 2 * count)
            val packs = OcrLanguagePacks(List(count) { index ->
                val offset = V2_HEADER_FIELDS + 2 * index
                OcrLanguagePack(fields[offset], fields[offset + 1])
            })
            val intent = OcrJobInput(
                DocumentId(fields[1]),
                OcrInput(fields[2], needLong(fields[4]), fields[6]),
                SourceId(fields[3]),
                fields[5],
                needLong(fields[7]),
                OcrConfiguration(fields[8], requireNotNull(fields[9].toIntOrNull()), OcrLayout.valueOf(fields[10]), packs),
            )
            // Reuse the canonical writer to refuse alternate numeric spelling and any other storage alias.
            require(intent.canonical.contentEquals(snapshot))
            return intent
        }

        private fun needLong(field: String): Long = requireNotNull(field.toLongOrNull())
    }
}
