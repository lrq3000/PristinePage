package app.pristinepage.data.model

import app.pristinepage.core.pipeline.OutputSpec
import java.nio.ByteBuffer

/** Identifies one unsealed ordinary render attempt. These are planned targets, not files or evidence of admitted/verified pixels. */
internal data class RenderFileIntent(
    val transactionId: TransactionId,
    val renderId: RenderId,
    val jobId: JobId,
    val leaseOwner: String,
    val generation: Long,
    val attempt: Long,
    val canonicalInputSha256: String,
    val pageId: PageId,
    val sourceId: SourceId,
    val sourceBlobId: SourceBlobId,
    val jobPinSourceId: SourceId,
    val sourceByteSha256: String,
    val sourcePixelSha256: String,
    val recipeRevision: Long,
    val recipeSha256: String,
    val output: OutputSpec,
) {
    val stagingPath: String get() = "staging/${transactionId.value}/derived.part"
    val finalPath: String get() = "renders/${renderId.value}/page.bin"

    // All strings are verified canonical ASCII UUIDs/hashes. The domain fixes ordinary FAIL/EXPORT with equal requested and actual dimensions; the unchanged job digest binds the rest of the v1 request.
    internal fun journalPayload(): ByteArray = ByteBuffer.allocate(payloadBytes)
        .put(payloadTag)
        .put(transactionId.value.encodeToByteArray()).put(renderId.value.encodeToByteArray())
        .put(jobId.value.encodeToByteArray()).put(leaseOwner.encodeToByteArray())
        .putLong(generation).putLong(attempt).put(canonicalInputSha256.encodeToByteArray())
        .put(pageId.value.encodeToByteArray()).put(sourceId.value.encodeToByteArray()).put(sourceBlobId.value.encodeToByteArray()).put(jobPinSourceId.value.encodeToByteArray())
        .put(sourceByteSha256.encodeToByteArray()).put(sourcePixelSha256.encodeToByteArray())
        .putLong(recipeRevision).put(recipeSha256.encodeToByteArray())
        .putInt(output.size.width).putInt(output.size.height)
        .array()

    companion object {
        private val payloadTag = "pristinepage-ordinary-render-intent-v1\u0000".encodeToByteArray()
        val payloadBytes: Int get() = payloadTag.size + 8 * 36 + 4 * 64 + 3 * Long.SIZE_BYTES + 2 * Int.SIZE_BYTES
    }
}
