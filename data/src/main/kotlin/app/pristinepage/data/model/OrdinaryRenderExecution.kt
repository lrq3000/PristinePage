package app.pristinepage.data.model

import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.FallbackPolicy
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.RenderPurpose
import app.pristinepage.core.pipeline.RenderRequest
import app.pristinepage.core.pipeline.RenderResult
import app.pristinepage.core.pipeline.RenderStatus
import app.pristinepage.core.pipeline.requireSha256
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.data.repository.RenderAttemptAuthority
import app.pristinepage.data.repository.RoomFrozenRenderReads
import app.pristinepage.data.repository.RoomJobLeases.FrozenRenderAcquisition.Acquired
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** A detached v1 record of what the retained result actually did. The canonical pre-execution digest binds the recipe bytes, masks and runtime profile without duplicating them here. */
internal class OrdinaryRenderExecution private constructor(
    private val sourceId: SourceId,
    private val blobId: SourceBlobId,
    private val sourceByteSha256: String,
    private val preExecutionKey: String,
    private val envelope: ByteArray,
    val sha256: String,
    val cacheKey: String,
    val rasterSha256: String,
    val size: PixelSize,
    private val capturedWith: Acquired?,
) {
    fun bytes(): ByteArray = envelope.copyOf()
    fun sameExecution(other: OrdinaryRenderExecution): Boolean = envelope.contentEquals(other.envelope)

    /** In-memory capture authority, deliberately excluded from the v1 envelope. Equal serialized provenance cannot upgrade a legacy capture. */
    fun capturedFor(authority: RenderAttemptAuthority): Boolean = when (authority) {
        is Acquired -> capturedWith === authority
        is RenderAttemptAuthority.Legacy -> capturedWith == null
    }

    fun matches(proof: RoomFrozenRenderReads.Verified, intent: RenderFileIntent, raster: String): Boolean =
        sourceId == intent.sourceId && blobId == intent.sourceBlobId && sourceByteSha256 == intent.sourceByteSha256 &&
            preExecutionKey == proof.preExecutionKey && rasterSha256 == raster

    companion object {
        const val TOKEN_LIMIT = 128
        const val LIST_LIMIT = 16
        const val MIN_STAGES = 3
        const val ENVELOPE_TAG = "pristinepage-ordinary-render-execution-v1\u0000"
        private const val JOB_KEY_TAG = "page-render-v1:"
        private val successfulOutcomes = setOf("OK", "EXACT_IDENTITY", "EXACT_PERMUTATION")

        // All lengths are bytes (the writer accepts only ASCII). Include each length/count prefix and today's fixed-width v1 identities, not database or image allocation limits.
        private fun textExtent(length: Int) = Int.SIZE_BYTES + length
        private val warningsExtent = Int.SIZE_BYTES + LIST_LIMIT * textExtent(TOKEN_LIMIT)
        private val stageExtent = textExtent(TOKEN_LIMIT) + Int.SIZE_BYTES + textExtent(successfulOutcomes.maxOf { it.length }) +
            4 * Int.SIZE_BYTES + warningsExtent
        val maxEnvelopeBytes = ENVELOPE_TAG.length + textExtent(JOB_KEY_TAG.length + 64) + 3 * textExtent(36) +
            4 * textExtent(64) + Long.SIZE_BYTES + 4 * Int.SIZE_BYTES + textExtent(RenderPurpose.EXPORT.name.length) +
            textExtent(FallbackPolicy.FAIL.name.length) + textExtent(RenderStatus.OK.name.length) + Int.SIZE_BYTES +
            LIST_LIMIT * stageExtent + warningsExtent

        fun isTokenChar(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "_.:-"
        fun isSuccessfulOutcome(value: String): Boolean = value in successfulOutcomes

        fun requirePreExecutionKey(value: String) {
            require(value.startsWith(JOB_KEY_TAG) && value.length == JOB_KEY_TAG.length + 64)
            requireSha256(value.substring(JOB_KEY_TAG.length))
        }

        fun cacheKey(preExecutionKey: String, executionHash: String): String =
            "render-cache-v1:" + sha256(("pristinepage-ordinary-render-cache-v1\u0000" + preExecutionKey + executionHash).encodeToByteArray())

        /** The frozen page supplies recipe/output; the admitted execution path supplies the actual original's source ID. */
        fun requestFor(page: ExportPageSnapshot, sourceId: SourceId): RenderRequest {
            require(page.output == page.requestedOutput && page.output.purpose == RenderPurpose.EXPORT && page.fallbackPolicy == FallbackPolicy.FAIL)
            return RenderRequest(sourceId.value, page.pixelHash, page.pageId.value, page.recipeRevision,
                RecipeCodec.decode(page.canonicalRecipe), page.output, 0, null, FallbackPolicy.FAIL)
        }

        /** Borrow only. Run outside Room; never retain the raster, rederive stage results from a recipe, or close the result owner here. */
        fun capture(proof: RoomFrozenRenderReads.Verified, result: RenderResult, checkpoint: () -> Unit): OrdinaryRenderExecution =
            capture(proof, null, result, checkpoint)

        fun capture(authority: RenderAttemptAuthority, result: RenderResult, checkpoint: () -> Unit): OrdinaryRenderExecution =
            capture(authority.proof, authority as? Acquired, result, checkpoint)

        private fun capture(
            proof: RoomFrozenRenderReads.Verified, acquired: Acquired?, result: RenderResult, checkpoint: () -> Unit,
        ): OrdinaryRenderExecution {
            val producer = acquired?.proof?.pinnedBinding
            val page = proof.page
            val sourceId = producer?.source?.sourceId?.let(::SourceId) ?: page.sourceId
            val blobId = producer?.blob?.sourceBlobId?.let(::SourceBlobId) ?: page.sourceBlobId
            val sourceByteSha256 = producer?.blob?.byteSha256 ?: page.byteHash
            val preExecutionKey = proof.preExecutionKey
            requirePreExecutionKey(preExecutionKey)
            val expected = requestFor(page, sourceId)
            require(result.request == expected && result.status == RenderStatus.OK)
            val image = requireNotNull(result.image)
            val size = PixelSize(image.width, image.height)
            require(size == expected.output.size)
            val stages = result.stages
            require(stages.size in MIN_STAGES..LIST_LIMIT && stages.first().outputSize == page.sourceSize && stages.last().outputSize == size)
            if (producer != null) {
                val decode = stages.first()
                require(decode.stageId == "source_decode_v1" && decode.version == 1 && decode.outcome == "OK" &&
                    decode.inputSize == PixelSize(producer.blob.encodedWidth, producer.blob.encodedHeight))
            }
            val bytes = ByteArrayOutputStream()
            val raster: String
            DataOutputStream(bytes).use { out ->
                fun text(value: String) {
                    require(value.length in 1..TOKEN_LIMIT && value.all(::isTokenChar))
                    val field = value.encodeToByteArray()
                    out.writeInt(field.size)
                    out.write(field)
                }
                fun dimensions(value: PixelSize) { out.writeInt(value.width); out.writeInt(value.height) }
                fun warnings(codes: List<String>) {
                    require(codes.size <= LIST_LIMIT)
                    out.writeInt(codes.size)
                    codes.forEach(::text)
                }

                out.write(ENVELOPE_TAG.encodeToByteArray())
                text(preExecutionKey)
                text(expected.pageId); text(expected.sourceId); text(blobId.value); text(sourceByteSha256); text(expected.sourcePixelHash)
                out.writeLong(expected.recipeRevision); text(page.recipeHash)
                dimensions(expected.output.size); text(expected.output.purpose.name); text(expected.fallback.name)
                text(result.status.name); dimensions(size)
                out.writeInt(stages.size)
                var previous: PixelSize? = null
                for (stage in stages) {
                    // This bounded ordinary envelope has no provider assets and cannot record a failed/omitted stage as a successful result.
                    require(stage.version > 0 && stage.provider == null && isSuccessfulOutcome(stage.outcome))
                    require(previous == null || stage.inputSize == previous)
                    text(stage.stageId); out.writeInt(stage.version); text(stage.outcome)
                    dimensions(stage.inputSize); dimensions(stage.outputSize); warnings(stage.warningCodes)
                    previous = stage.outputSize
                }
                warnings(result.warnings)
                raster = rasterSha256(image, checkpoint)
                text(raster)
            }
            val envelope = bytes.toByteArray()
            val hash = sha256(envelope)
            val key = cacheKey(preExecutionKey, hash)
            return OrdinaryRenderExecution(sourceId, blobId, sourceByteSha256, preExecutionKey, envelope, hash, key, raster, size, acquired)
        }

        /** Hash only active samples of the retained raster, using the same row digest as closed-file verification. */
        fun rasterSha256(image: RgbImage, checkpoint: () -> Unit): String {
            val digest = OrdinaryRasterDigest(image.width, image.height)
            for (y in 0 until image.height) {
                digest.appendRow(image.width, checkpoint) { x ->
                    (255 shl 24) or (image.getUnsigned(x, y, 0) shl 16) or
                        (image.getUnsigned(x, y, 1) shl 8) or image.getUnsigned(x, y, 2)
                }
            }
            checkpoint()
            return digest.finish()
        }

        fun sha256(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
