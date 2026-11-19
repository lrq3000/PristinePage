package app.pristinepage.data.model

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.requireSha256
import java.nio.ByteBuffer

/** Fixed v1 metadata for one planned byte-preserving copy of a terminal ordinary PNG. It is not a lease, file receipt, or artifact. */
internal class PngExportItemIntent(private val assembly: Assembly, private val item: Item) {
    data class Assembly(
        val snapshotId: ExportSnapshotId,
        val snapshotSha256: String,
        val jobId: JobId,
        val canonicalInputSha256: String,
        val leaseOwner: String,
        val generation: Long,
        val attempt: Long,
        val pageCount: Int,
    ) {
        fun validate() {
            require(pageCount in 1..MAX_ITEMS && generation > 0 && attempt > 0)
            requireOpaqueId(leaseOwner)
            requireSha256(snapshotSha256)
            requireSha256(canonicalInputSha256)
        }
    }

    data class Item(
        val ordinal: Int,
        val size: PixelSize,
        val renderJobId: JobId,
        val renderId: RenderId,
        val renderTransactionId: TransactionId,
        val renderSealSha256: String,
        val byteSha256: String,
        val byteCount: Long,
        val rasterSha256: String,
        val artifactId: ArtifactId,
        val exportTransactionId: TransactionId,
    )

    init {
        assembly.validate()
        require(item.ordinal in 0 until assembly.pageCount && item.byteCount > 0)
        require(item.renderTransactionId != item.exportTransactionId)
        requireSha256(item.renderSealSha256)
        requireSha256(item.byteSha256)
        requireSha256(item.rasterSha256)
    }

    // Identity strings only: this type does not resolve paths or open either file.
    val stagingPath: String get() = "staging/${item.exportTransactionId.value}/derived.part"
    val finalPath: String get() = "exports/${assembly.snapshotId.value}/${item.artifactId.value}.bin"

    // Canonical ASCII UUIDs and hashes have fixed widths. All integers are big-endian. The assembly input digest binds ordered job IDs and page count; neither the cap nor the later EXPORT seal is part of the intent.
    fun journalPayload(): ByteArray = ByteBuffer.allocate(payloadBytes)
        .put(tag)
        .text(assembly.snapshotId.value).text(assembly.snapshotSha256)
        .text(assembly.jobId.value).text(assembly.canonicalInputSha256).text(assembly.leaseOwner)
        .putLong(assembly.generation).putLong(assembly.attempt)
        .putInt(item.ordinal).putInt(item.size.width).putInt(item.size.height)
        .text(item.renderJobId.value).text(item.renderId.value).text(item.renderTransactionId.value)
        .text(item.renderSealSha256).text(item.byteSha256).putLong(item.byteCount).text(item.rasterSha256)
        .text(item.artifactId.value).text(item.exportTransactionId.value)
        .array()

    companion object {
        const val MAX_ITEMS = 100
        private val tag = "pristinepage-ordinary-png-export-item-v1\u0000".encodeToByteArray()
        val payloadBytes: Int get() = tag.size + 8 * 36 + 5 * 64 + 3 * Long.SIZE_BYTES + 3 * Int.SIZE_BYTES

        fun ownerKey(jobId: JobId, ordinal: Int): String {
            require(ordinal in 0 until MAX_ITEMS)
            return "export-item-v1:${jobId.value}:$ordinal"
        }

        private fun ByteBuffer.text(value: String): ByteBuffer = put(value.encodeToByteArray())
    }
}
