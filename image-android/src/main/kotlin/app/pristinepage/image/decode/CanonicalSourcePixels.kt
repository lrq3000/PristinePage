package app.pristinepage.image.decode

import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.requireWellFormedUnicode
import java.nio.ByteBuffer
import java.security.MessageDigest

/** The codec supplies raw straight-alpha encoded-sRGB rows with NO automatic orientation. Integer EXIF permutations do not resample, and white composition is performed once before assigning normalized RGB pixels. */
object CanonicalSourcePixels {
    fun normalize(width: Int, height: Int, orientation: Int, readRow: (Int, IntArray) -> Unit, checkpoint: () -> Unit): RgbImage {
        require(width in 1..12000 && height in 1..12000 && width.toLong() * height <= 48_000_000 && orientation in 1..8)
        checkpoint()
        val outWidth = if (orientation in 5..8) height else width
        val outHeight = if (orientation in 5..8) width else height
        val output = ByteArray(outWidth * outHeight * 3)
        val row = IntArray(width)
        for (y in 0 until height) {
            readRow(y, row)
            for (x in 0 until width) {
                val dx: Int
                val dy: Int
                when (orientation) {
                    1 -> { dx = x; dy = y }
                    2 -> { dx = width - 1 - x; dy = y }
                    3 -> { dx = width - 1 - x; dy = height - 1 - y }
                    4 -> { dx = x; dy = height - 1 - y }
                    5 -> { dx = y; dy = x }
                    6 -> { dx = height - 1 - y; dy = x }
                    7 -> { dx = height - 1 - y; dy = width - 1 - x }
                    else -> { dx = y; dy = width - 1 - x }
                }
                val argb = row[x]
                val alpha = argb ushr 24
                val index = (dy * outWidth + dx) * 3
                output[index] = compose((argb ushr 16) and 255, alpha)
                output[index + 1] = compose((argb ushr 8) and 255, alpha)
                output[index + 2] = compose(argb and 255, alpha)
            }
            checkpoint()
        }
        // RgbImage owns its defensive copy. That simultaneous second RGB buffer is part of DecodeAdmission, not an untracked "free" handoff.
        return RgbImage(outWidth, outHeight, outWidth * 3, output)
    }

    /** Exact roundByte(a*c+(1-a)*255) for 8-bit straight alpha. Denominator255 is odd, so integer+127 nearest rounding has no ambiguous half tie. Hidden RGB at alpha0 becomes white without division. */
    private fun compose(color: Int, alpha: Int): Byte = ((alpha * color + (255 - alpha) * 255 + 127) / 255).toByte()

    fun hash(image: RgbImage, conversionFingerprint: String, checkpoint: () -> Unit): String {
        require(conversionFingerprint.isNotBlank() && conversionFingerprint.length <= 128)
        requireWellFormedUnicode(conversionFingerprint)
        checkpoint()
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("pristinepage-source-pixels-v1\u0000".toByteArray(Charsets.UTF_8))
        val fingerprint = conversionFingerprint.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(fingerprint.size).array()); digest.update(fingerprint)
        digest.update(ByteBuffer.allocate(16).putInt(image.width).putInt(image.height).putLong(image.width.toLong() * image.height * 3).array())
        val row = ByteArray(image.width * 3)
        for (y in 0 until image.height) {
            checkpoint()
            for (x in 0 until image.width) for (c in 0..2) row[x * 3 + c] = image.getUnsigned(x, y, c).toByte()
            digest.update(row)
        }
        checkpoint()
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
