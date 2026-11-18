package app.pristinepage.data.model

import java.nio.ByteBuffer
import java.security.MessageDigest

/** Single-use accumulator for the ordinary execution-v1 raster identity. Supply complete opaque sRGB ARGB rows in top-to-bottom order; only active RGB bytes enter the digest. Calls are synchronous and non-reentrant. */
class OrdinaryRasterDigest(private val width: Int, private val height: Int) {
    init { require(width > 0 && height > 0) }

    private val digest = MessageDigest.getInstance("SHA-256").apply {
        update(domain)
        update(ByteBuffer.allocate(8).putInt(width).putInt(height).array())
    }
    private var scratch: ByteArray? = ByteArray(3 * minOf(width, 4096))
    private var rows = 0

    /** The reader is borrowed for this call, with one primitive ARGB sample requested per column. An invalid, interrupted or incomplete row cannot produce a partial digest. */
    fun appendRow(sampleCount: Int, checkpoint: () -> Unit, samples: OpaqueArgbRow) {
        val row = rows
        rows = INVALID
        val chunk = checkNotNull(scratch) { "Raster is complete or no longer usable" }
        scratch = null
        require(sampleCount == width) { "Wrong active row width" }
        rows = APPENDING
        try {
            var x = 0
            while (x < width) {
                checkpoint()
                requireActiveRow()
                val count = minOf(width - x, chunk.size / 3)
                for (dx in 0 until count) {
                    val argb = samples.at(x + dx)
                    requireActiveRow()
                    require(argb ushr 24 == 255) { "Raster sample must be opaque" }
                    val offset = dx * 3
                    chunk[offset] = ((argb ushr 16) and 255).toByte()
                    chunk[offset + 1] = ((argb ushr 8) and 255).toByte()
                    chunk[offset + 2] = (argb and 255).toByte()
                }
                digest.update(chunk, 0, count * 3)
                x += count
            }
            rows = row + 1
            // Drop the block after the last row so the caller can release its native phase before the encoded-file rescan.
            if (rows < height) scratch = chunk
        } finally {
            if (rows == APPENDING) rows = INVALID
        }
    }

    private fun requireActiveRow() {
        // Nested append or finish poisons the outer operation even when its callback catches the rejection.
        check(rows == APPENDING) { "Raster row was invalidated" }
    }

    /** No hash can be obtained before exactly the declared number of complete rows have been consumed. */
    fun finish(): String {
        val complete = rows == height
        rows = INVALID
        scratch = null
        check(complete) { "Raster is incomplete or no longer usable" }
        return digest.digest().toHexString()
    }

    fun interface OpaqueArgbRow {
        fun at(x: Int): Int
    }

    private companion object {
        const val INVALID = -1
        const val APPENDING = -2
        val domain = "pristinepage-ordinary-render-rgb8-v1\u0000".encodeToByteArray()
    }
}
