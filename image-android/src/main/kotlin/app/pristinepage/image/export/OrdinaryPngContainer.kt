package app.pristinepage.image.export

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.image.decode.ImageAllocationBudget
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32

/** Identity of compressed file bytes, never the canonical raster or a data-owned seal. */
internal data class EncodedPngIdentity(val byteLength: Long, val sha256: String)

/** Narrow output gate for closed, caller-owned PNGs. Does not inflate image data, infer color from a decoder, or accept arbitrary PNG metadata. */
internal class OrdinaryPngContainer(private val budget: ImageAllocationBudget) {
    fun read(input: InputStream, length: Long, maxBytes: Long, expected: PixelSize, checkpoint: () -> Unit): EncodedPngIdentity {
        checkpoint()
        if (maxBytes <= 0) pngRefuse(OperationErrorCode.INVALID_INPUT, "export_png_invalid_cap")
        if (length > maxBytes) pngRefuse(OperationErrorCode.RESOURCE_LIMIT, "export_png_readback_byte_limit")
        if (length < SIGNATURE.size) structure()
        // One reusable 64 KiB block plus 64 KiB provisional digest/framing/stream overhead; no allocation sized by encoded payload.
        try {
            return budget.reserve(scanBytes).use { scan(Cursor(input, length, checkpoint), length, expected) }
        } catch (_: IOException) {
            pngRefuse(OperationErrorCode.STORAGE_FAILED, "export_png_read_failed", retryable = true)
        } catch (_: OutOfMemoryError) {
            pngRefuse(OperationErrorCode.RESOURCE_LIMIT, "export_png_allocation_failed", retryable = true)
        }
    }

    private fun scan(cursor: Cursor, length: Long, expected: PixelSize): EncodedPngIdentity {
        val b = cursor.block
        cursor.read(SIGNATURE.size)
        if (SIGNATURE.indices.any { b[it] != SIGNATURE[it] }) pngRefuse(OperationErrorCode.UNSUPPORTED_FORMAT, "export_png_format")
        val crc = CRC32()
        val singletons = mutableSetOf<String>()
        var dataStarted = false
        var nonemptyData = false
        var chunks = 0
        while (true) {
            if (cursor.remaining < 12) structure()
            if (++chunks > 4096) pngRefuse(OperationErrorCode.RESOURCE_LIMIT, "export_png_chunk_limit")
            cursor.read(8)
            val count = b.u32(0)
            if (count > Int.MAX_VALUE || count > cursor.remaining - 4) structure()
            val kind = String(b, 4, 4, Charsets.US_ASCII)
            if ((chunks == 1) != (kind == "IHDR")) structure()
            crc.reset()
            crc.update(b, 4, 4)
            when (kind) {
                "IDAT" -> {
                    dataStarted = true
                    nonemptyData = nonemptyData || count > 0
                    var left = count
                    while (left > 0) {
                        val take = minOf(left, BLOCK.toLong()).toInt()
                        cursor.read(take)
                        crc.update(b, 0, take)
                        left -= take
                    }
                }
                "IEND" -> if (!nonemptyData || count != 0L || cursor.remaining != 4L) structure()
                "IHDR" -> {
                    if (count != 13L) structure()
                    cursor.read(13)
                    crc.update(b, 0, 13)
                    if (b.u32(0) != expected.width.toLong() || b.u32(4) != expected.height.toLong()) {
                        pngRefuse(OperationErrorCode.INTEGRITY_FAILED, "export_png_dimensions")
                    }
                    if (b[8] != 8.toByte() || (b[9] != 2.toByte() && b[9] != 6.toByte()) || (10..12).any { b[it] != 0.toByte() }) {
                        pngRefuse(OperationErrorCode.UNSUPPORTED_FORMAT, "export_png_methods")
                    }
                }
                else -> {
                    val legalExtent = when (kind) {
                        "sRGB" -> count == 1L
                        "gAMA" -> count == 4L
                        "cHRM" -> count == 32L
                        "pHYs" -> count == 9L
                        "PLTE" -> count in 3..768 && count % 3 == 0L
                        "eXIf" -> count == EXIF_LE.size.toLong()
                        else -> metadata()
                    }
                    if (dataStarted || !singletons.add(kind) || (kind in COLOR_FIELDS && "PLTE" in singletons)) structure()
                    if (!legalExtent) metadata()
                    val small = count.toInt()
                    cursor.read(small)
                    crc.update(b, 0, small)
                    when (kind) {
                        "sRGB" -> if (b.u8(0) > 3) metadata()
                        "gAMA" -> if (b.u32(0) != 45455L) metadata()
                        "cHRM" -> if (SRGB_CHROMA.indices.any { b.u32(4 * it) != SRGB_CHROMA[it] }) metadata()
                        "pHYs" -> if (b.u8(8) > 1) metadata()
                        "eXIf" -> if (!EXIF_LE.indices.all { b[it] == EXIF_LE[it] } &&
                            !EXIF_BE.indices.all { b[it] == EXIF_BE[it] }) metadata()
                    }
                }
            }
            cursor.read(4)
            if (crc.value != b.u32(0)) pngRefuse(OperationErrorCode.INTEGRITY_FAILED, "export_png_crc")
            if (kind == "IEND") return cursor.finish(length)
        }
    }

    private class Cursor(private val input: InputStream, length: Long, private val checkpoint: () -> Unit) {
        val block = ByteArray(BLOCK)
        private val digest = MessageDigest.getInstance("SHA-256")
        var remaining = length
            private set

        fun read(count: Int) {
            if (count.toLong() > remaining) structure()
            var at = 0
            while (at < count) {
                checkpoint()
                val n = input.read(block, at, count - at)
                if (n < 0) structure()
                if (n == 0) throw IOException("Closed PNG stream did not advance")
                at += n
            }
            remaining -= count
            digest.update(block, 0, count)
        }

        fun finish(length: Long): EncodedPngIdentity {
            checkpoint()
            // A single EOF probe, not another admitted byte. Refuse growth even when the initial extent equaled the cap.
            if (remaining != 0L || input.read() != -1) structure()
            val receipt = EncodedPngIdentity(length, digest.digest().toHexString())
            checkpoint()
            return receipt
        }
    }

    internal companion object {
        private const val BLOCK = 65536
        const val scanBytes = 2L * BLOCK
        private val SIGNATURE = "89504e470d0a1a0a".hexToByteArray()
        private val COLOR_FIELDS = setOf("sRGB", "gAMA", "cHRM")
        private val SRGB_CHROMA = longArrayOf(31270, 32900, 64000, 33000, 30000, 60000, 15000, 6000)
        // Exact TIFF in either endian: one inline SHORT Orientation=1, zero padding/next IFD, no extra bytes. Do not invoke the general source TIFF reader for this privacy allowlist.
        private val EXIF_LE = "49492a0008000000010012010300010000000100000000000000".hexToByteArray()
        private val EXIF_BE = "4d4d002a00000008000101120003000000010001000000000000".hexToByteArray()

        private fun ByteArray.u8(at: Int) = this[at].toInt() and 255
        private fun ByteArray.u32(at: Int) =
            (u8(at).toLong() shl 24) or (u8(at + 1).toLong() shl 16) or (u8(at + 2).toLong() shl 8) or u8(at + 3).toLong()

        private fun structure(): Nothing = pngRefuse(OperationErrorCode.INTEGRITY_FAILED, "export_png_structure")
        private fun metadata(): Nothing = pngRefuse(OperationErrorCode.UNSUPPORTED_FORMAT, "export_png_metadata")
    }
}

internal fun pngRefuse(code: OperationErrorCode, key: String, retryable: Boolean = false): Nothing =
    throw RepositoryFailure(OperationError(code, retryable, key, null))
