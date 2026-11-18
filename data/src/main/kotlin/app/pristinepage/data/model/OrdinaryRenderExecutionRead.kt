package app.pristinepage.data.model

import app.pristinepage.core.pipeline.FallbackPolicy
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.RenderPurpose
import app.pristinepage.core.pipeline.RenderStatus
import app.pristinepage.core.pipeline.StageRecord
import app.pristinepage.core.pipeline.StageRecords
import app.pristinepage.core.pipeline.requireSha256
import app.pristinepage.data.repository.RoomFrozenRenderReads
import java.nio.ByteBuffer
import java.util.Collections

/** Authenticated job/raster metadata and recorded history, not a terminal row, live owner, file capability or RenderResult. */
internal class OrdinaryRenderExecutionRead private constructor(
    val unprovenProducer: UnprovenProducer,
    val sourceRelation: SourceRelation,
    val sha256: String,
    val cacheKey: String,
    val rasterSha256: String,
    val size: PixelSize,
    val stages: List<StageRecord>,
    val warnings: List<String>,
) {
    data class UnprovenProducer(val sourceId: SourceId, val blobId: SourceBlobId, val byteSha256: String)
    enum class SourceRelation { EXACT_FROZEN_TUPLE, NORMALIZED_COMPATIBLE_REUSE }

    companion object {
        /**
         * Borrow one stable, already materialized BLOB outside Room. The caller must raw-admit its type/extent before projection; this check cannot undo Cursor allocation. Stored raster values are metadata, not file verification.
         */
        fun read(
            bytes: ByteArray,
            proof: RoomFrozenRenderReads.Verified,
            storedExecutionSha256: String,
            storedCacheKey: String,
            storedRasterSha256: String,
            storedRasterSize: PixelSize,
        ): OrdinaryRenderExecutionRead {
            try {
                require(bytes.size in 1..OrdinaryRenderExecution.maxEnvelopeBytes)
                val sha256 = OrdinaryRenderExecution.sha256(bytes)
                require(sha256 == storedExecutionSha256)
                val page = proof.page
                val input = Input(bytes)
                input.tag()
                val jobKey = input.text().also { OrdinaryRenderExecution.requirePreExecutionKey(it) }
                require(jobKey == proof.preExecutionKey)
                val cacheKey = OrdinaryRenderExecution.cacheKey(jobKey, sha256)
                require(cacheKey == storedCacheKey)
                require(PageId(input.text()) == page.pageId)
                val producer = UnprovenProducer(SourceId(input.text()), SourceBlobId(input.text()), input.hash())
                require(input.hash() == page.pixelHash)
                require(input.revision() == page.recipeRevision)
                require(input.hash() == page.recipeHash)
                require(input.dimensions() == page.requestedOutput.size)
                require(input.text() == RenderPurpose.EXPORT.name)
                require(input.text() == FallbackPolicy.FAIL.name)
                require(input.text() == RenderStatus.OK.name)
                val size = input.dimensions()
                require(size == page.output.size && size == storedRasterSize)
                val stages = input.stages()
                require(stages.first().outputSize == page.sourceSize && stages.last().outputSize == size)
                val warnings = input.warnings()
                val raster = input.hash()
                require(input.atEnd && raster == storedRasterSha256)

                // An immutable ID cannot describe two objects. Distinct originals may normalize to the same input; the JOB pin is not evidence of what produced this raster.
                val sameSource = producer.sourceId == page.sourceId
                val sameBlob = producer.blobId == page.sourceBlobId
                require(!sameSource || sameBlob)
                require(!sameBlob || producer.byteSha256 == page.byteHash)
                val relation = if (sameSource) SourceRelation.EXACT_FROZEN_TUPLE else SourceRelation.NORMALIZED_COMPATIBLE_REUSE
                return OrdinaryRenderExecutionRead(producer, relation, sha256, cacheKey, raster, size,
                    StageRecords.snapshot(stages), Collections.unmodifiableList(warnings))
            } catch (_: IllegalArgumentException) {
                throw RepositoryFailure(OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "ordinary_render_execution_invalid", proof.jobId.value))
            }
        }
    }

    private class Input(private val bytes: ByteArray) {
        private val buffer = ByteBuffer.wrap(bytes) // v1 uses big-endian 32-/64-bit values.
        val atEnd: Boolean get() = !buffer.hasRemaining()

        fun tag() {
            val tag = OrdinaryRenderExecution.ENVELOPE_TAG
            require(buffer.remaining() >= tag.length)
            require(tag.all { buffer.get().toInt() == it.code })
        }

        private fun int(): Int {
            require(buffer.remaining() >= Int.SIZE_BYTES)
            return buffer.int
        }

        fun revision(): Long {
            require(buffer.remaining() >= Long.SIZE_BYTES)
            return buffer.long.also { require(it > 0) }
        }

        fun text(): String {
            val length = int()
            require(length in 1..OrdinaryRenderExecution.TOKEN_LIMIT && length <= buffer.remaining())
            val start = buffer.position()
            val end = start + length
            require((start until end).all { OrdinaryRenderExecution.isTokenChar(bytes[it].toInt().toChar()) })
            buffer.position(end)
            return String(bytes, start, length, Charsets.US_ASCII)
        }

        fun hash(): String = text().also(::requireSha256)
        fun dimensions(): PixelSize = PixelSize(int(), int())

        private fun count(minimum: Int, minimumItemBytes: Int): Int = int().also {
            require(it in minimum..OrdinaryRenderExecution.LIST_LIMIT && it <= buffer.remaining() / minimumItemBytes)
        }

        fun warnings(): List<String> = List(count(0, Int.SIZE_BYTES + 1)) { text() }

        fun stages(): List<StageRecord> {
            // Two length-prefixed tokens (at least one byte each), a version, four dimensions and a warning count.
            val count = count(OrdinaryRenderExecution.MIN_STAGES, 8 * Int.SIZE_BYTES + 2)
            var previous: PixelSize? = null
            return List(count) {
                val stage = text()
                val version = int().also { require(it > 0) }
                val outcome = text().also { require(OrdinaryRenderExecution.isSuccessfulOutcome(it)) }
                val input = dimensions()
                val output = dimensions()
                require(previous == null || input == previous)
                previous = output
                StageRecord(stage, version, null, outcome, input, output, warnings())
            }
        }
    }
}
