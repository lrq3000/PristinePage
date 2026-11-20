package app.pristinepage.image.decode

import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

/** The JPEG reader locates one exact APP1 segment and the primary EOI. No relaxed TIFF or opaque trailer reaches ImageDecoder; the unchanged original is still the byte identity. */
internal class JpegDecodeView(private val primaryBytes: Int, exif: LongRange, orientation: Int) {
    private val exifStart = exif.first.toInt()
    private val exifEnd = (exif.last + 1).toInt()

    // Fixed big-endian TIFF with just one typed orientation; Android applies it once, as for the strict path.
    private val replacement = byteArrayOf(
        -1, -31, 0, 34, 69, 120, 105, 102, 0, 0, 77, 77, 0, 42, 0, 0, 0, 8,
        0, 1, 1, 18, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0, 0, 0, 0, 0,
    )

    fun read(file: File, expectedByteSha256: String, checkpoint: () -> Unit): ByteArray {
        require(exifStart >= 2 && exifEnd in (exifStart + replacement.size)..primaryBytes && primaryBytes <= 104857600)
        val output = ByteArray(primaryBytes - (exifEnd - exifStart) + replacement.size)
        val digest = MessageDigest.getInstance("SHA-256")
        // Authenticate the very bytes used to construct the view, including skipped metadata and suffix. The decode permit already covers the single encoded array.
        file.inputStream().buffered(65536).use { raw ->
            val input = DigestInputStream(raw, digest)
            readInto(input, output, 0, exifStart, checkpoint)
            val scratch = ByteArray(65536)
            // APP1 includes two marker bytes outside its uint16 length; even the largest segment fits by chunking.
            var exifRemaining = exifEnd - exifStart
            while (exifRemaining > 0) {
                val chunk = minOf(exifRemaining, scratch.size)
                readInto(input, scratch, 0, chunk, checkpoint)
                exifRemaining -= chunk
            }
            replacement.copyInto(output, exifStart)
            readInto(input, output, exifStart + replacement.size, primaryBytes - exifEnd, checkpoint)
            var trailer = 0
            while (true) {
                checkpoint()
                val count = input.read(scratch)
                if (count == -1) break
                trailer += count
                if (count == 0 || trailer > 65536 || primaryBytes.toLong() + trailer > 104857600) mismatch()
            }
        }
        if (digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } != expectedByteSha256) mismatch()
        checkpoint()
        return output
    }

    private fun readInto(input: InputStream, target: ByteArray, start: Int, length: Int, checkpoint: () -> Unit) {
        var offset = start
        val end = start + length
        while (offset < end) {
            checkpoint()
            val count = input.read(target, offset, minOf(end - offset, 65536))
            if (count <= 0) mismatch()
            offset += count
        }
    }

    private fun mismatch(): Nothing = throw RepositoryFailure(OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "source_byte_identity_mismatch", null))
}
