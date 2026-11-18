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
import app.pristinepage.data.repository.RoomFrozenRenderReads
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
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
) {
    fun bytes(): ByteArray = envelope.copyOf()
    fun sameExecution(other: OrdinaryRenderExecution): Boolean = envelope.contentEquals(other.envelope)

    fun matches(proof: RoomFrozenRenderReads.Verified, raster: String): Boolean =
        sourceId == proof.page.sourceId && blobId == proof.page.sourceBlobId && sourceByteSha256 == proof.page.byteHash &&
            preExecutionKey == proof.preExecutionKey && rasterSha256 == raster

    companion object {
        private const val TOKEN_LIMIT = 128
        private const val LIST_LIMIT = 16
        private const val JOB_KEY_TAG = "page-render-v1:"
        private val token = Regex("[A-Za-z0-9_.:-]+")
        private val successfulOutcomes = setOf("OK", "EXACT_IDENTITY", "EXACT_PERMUTATION")
        private val rasterDomain = "pristinepage-ordinary-render-rgb8-v1\u0000".encodeToByteArray()

        /** Borrow only. Run outside Room; never retain the raster, rederive stage results from a recipe, or close the result owner here. */
        fun capture(proof: RoomFrozenRenderReads.Verified, result: RenderResult, checkpoint: () -> Unit): OrdinaryRenderExecution {
            val page = proof.page
            val preExecutionKey = proof.preExecutionKey
            require(preExecutionKey.startsWith(JOB_KEY_TAG) && preExecutionKey.length == JOB_KEY_TAG.length + 64)
            requireSha256(preExecutionKey.substring(JOB_KEY_TAG.length))
            val expected = RenderRequest(page.sourceId.value, page.pixelHash, page.pageId.value, page.recipeRevision,
                RecipeCodec.decode(page.canonicalRecipe), page.output, 0, null, FallbackPolicy.FAIL)
            require(page.output == page.requestedOutput && page.output.purpose == RenderPurpose.EXPORT && page.fallbackPolicy == FallbackPolicy.FAIL)
            require(result.request == expected && result.status == RenderStatus.OK)
            val image = requireNotNull(result.image)
            val size = PixelSize(image.width, image.height)
            require(size == expected.output.size)
            val stages = result.stages
            require(stages.size in 3..LIST_LIMIT && stages.first().outputSize == page.sourceSize && stages.last().outputSize == size)
            val bytes = ByteArrayOutputStream()
            val raster: String
            DataOutputStream(bytes).use { out ->
                fun text(value: String) {
                    require(value.length in 1..TOKEN_LIMIT && token.matches(value))
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

                out.write("pristinepage-ordinary-render-execution-v1\u0000".encodeToByteArray())
                text(preExecutionKey)
                text(expected.pageId); text(expected.sourceId); text(page.sourceBlobId.value); text(page.byteHash); text(expected.sourcePixelHash)
                out.writeLong(expected.recipeRevision); text(page.recipeHash)
                dimensions(expected.output.size); text(expected.output.purpose.name); text(expected.fallback.name)
                text(result.status.name); dimensions(size)
                out.writeInt(stages.size)
                var previous: PixelSize? = null
                for (stage in stages) {
                    // This bounded ordinary envelope has no provider assets and cannot record a failed/omitted stage as a successful result.
                    require(stage.version > 0 && stage.provider == null && stage.outcome in successfulOutcomes)
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
            val key = "render-cache-v1:" + sha256(("pristinepage-ordinary-render-cache-v1\u0000" + preExecutionKey + hash).encodeToByteArray())
            return OrdinaryRenderExecution(page.sourceId, page.sourceBlobId, page.byteHash, preExecutionKey, envelope, hash, key, raster, size)
        }

        /** The future closed-file verifier hashes its decoded RGB in this same domain. No stride padding or second full raster enters the identity. */
        fun rasterSha256(image: RgbImage, checkpoint: () -> Unit): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(rasterDomain)
            digest.update(ByteBuffer.allocate(8).putInt(image.width).putInt(image.height).array())
            val chunk = ByteArray(3 * minOf(image.width, 4096))
            for (y in 0 until image.height) {
                var x = 0
                while (x < image.width) {
                    checkpoint()
                    val count = minOf(image.width - x, chunk.size / 3)
                    for (dx in 0 until count) for (c in 0..2) chunk[dx * 3 + c] = image.getUnsigned(x + dx, y, c).toByte()
                    digest.update(chunk, 0, count * 3)
                    x += count
                }
            }
            checkpoint()
            return hex(digest.digest())
        }

        private fun sha256(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
