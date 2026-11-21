package app.pristinepage.image.export

import app.pristinepage.data.model.OperationErrorCode
import java.io.OutputStream

/**
 * Strip only Skia's optional single ICC marker from a fresh encoder-owned sRGB raster. Do not use for imported JPEGs. Copy all coding tables and compressed bytes unchanged; the existing strict verifier checks the complete closed output independently.
 */
internal class PlatformJpegStream(private val output: OutputStream) : OutputStream() {
    private val header = ByteArray(4)
    private val single = ByteArray(1)
    private var length = 0
    private var started = false
    private var sawProfile = false
    private var icc: ByteArray? = null
    private var iccSize = 0
    private var body = 0
    private var dropping = false
    private var scanHeader = false
    private var scan = false

    override fun write(b: Int) {
        single[0] = b.toByte()
        write(single, 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        require(off >= 0 && len >= 0 && off <= b.size - len)
        var cursor = off
        val end = off + len
        while (cursor < end) {
            if (scan) {
                output.write(b, cursor, end - cursor)
                return
            }
            if (body > 0) {
                val take = minOf(body, end - cursor)
                if (dropping) {
                    val profile = checkNotNull(icc)
                    b.copyInto(profile, iccSize, cursor, cursor + take)
                    iccSize += take
                    if (take == body) {
                        if (!ICC_PREFIX.indices.all { profile[it] == ICC_PREFIX[it] } ||
                            !PlatformSrgbProfile.matches(profile.copyOfRange(ICC_PREFIX.size, profile.size))) invalid()
                        icc = null
                    }
                } else output.write(b, cursor, take)
                cursor += take
                body -= take
                if (body == 0 && scanHeader) scan = true
                continue
            }
            // JPEG permits extra 0xff bytes in a marker prefix. Preserve them without buffering an arbitrary run.
            if (started && length == 1 && unsigned(0) == 0xff && b[cursor] == 0xff.toByte()) {
                output.write(0xff)
                cursor++
                continue
            }
            header[length++] = b[cursor++]
            if (!started) {
                if (length == 2) {
                    if (unsigned(0) != 0xff || unsigned(1) != 0xd8) invalid()
                    output.write(header, 0, 2)
                    started = true
                    length = 0
                }
            } else if (length == 4) {
                val marker = unsigned(1)
                if (unsigned(0) != 0xff || marker !in 0xc0..0xfe || marker in 0xd0..0xd9) invalid()
                body = (unsigned(2) * 256 + unsigned(3)) - 2
                if (body < 0) invalid()
                dropping = marker == 0xe2
                scanHeader = marker == 0xda
                if (dropping) {
                    if (sawProfile || body !in (ICC_PREFIX.size + 132)..(ICC_PREFIX.size + PlatformSrgbProfile.MAX_BYTES)) invalid()
                    sawProfile = true
                    icc = ByteArray(body)
                    iccSize = 0
                } else output.write(header)
                if (scanHeader && body == 0) invalid()
                length = 0
            }
        }
    }

    fun finish() {
        if (!scan) invalid()
    }

    private fun unsigned(i: Int): Int = header[i].toInt() and 255
    private fun invalid(): Nothing = refuse(OperationErrorCode.INTEGRITY_FAILED, "export_jpeg_encoder_output")

    private companion object {
        val ICC_PREFIX = "ICC_PROFILE\u0000".encodeToByteArray() + byteArrayOf(1, 1)
    }
}
