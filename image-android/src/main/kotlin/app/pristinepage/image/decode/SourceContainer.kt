package app.pristinepage.image.decode

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.CRC32

data class EncodedSourceHeader(val mime: String, val size: PixelSize, val exifOrientation: Int = 1) {
    internal var jpegProjection: JpegDecodeView? = null
}

/** Bounded static-container preflight, NOT entropy/pixel decode validation. A strict codec must still decode the complete image and agree with this header. Unknown color transforms are refused rather than relabeled sRGB; later validated profile support may extend this unreleased policy explicitly. */
object SourceContainer {
    fun read(file: File, checkpoint: () -> Unit): EncodedSourceHeader {
        try {
            val length = file.length()
            if (length <= 0) invalid()
            if (length > 104857600) refuse(OperationErrorCode.RESOURCE_LIMIT, "encoded_source_limit")
            checkpoint()
            file.inputStream().buffered(65536).use { input ->
                val c = Cursor(input, length, checkpoint)
                val start = c.bytes(minOf(length, 8).toInt())
                return when {
                    start.contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) -> png(c)
                    start.size >= 2 && start[0] == (-1).toByte() && start[1] == (-40).toByte() -> {
                        // Reopen only these immutable app-owned bytes, never a provider stream. No original-byte rewrite is performed.
                        input.close()
                        file.inputStream().buffered(65536).use { jpeg(Cursor(it, length, checkpoint)) }
                    }
                    start.size == 8 && start.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" -> webp(c, uint(start, 4, true) + 8 == length)
                    else -> refuse(OperationErrorCode.UNSUPPORTED_FORMAT, "source_format_unsupported")
                }
            }
        } catch (failure: RepositoryFailure) { throw failure }
        catch (_: EOFException) { invalid() }
        catch (_: IOException) { refuse(OperationErrorCode.STORAGE_FAILED, "source_header_read_failed", true) }
    }

    private fun png(c: Cursor): EncodedSourceHeader {
        var size: PixelSize? = null
        var dataSeen = false
        var dataEnded = false
        var metadata = 0L
        var chunks = 0
        val exif = ExifState()
        while (c.remaining > 0) {
            c.checkpoint()
            if (++chunks > 4096) refuse(OperationErrorCode.RESOURCE_LIMIT, "source_metadata_limit")
            val n = c.u32(false)
            val kindBytes = c.bytes(4)
            val kind = kindBytes.toString(Charsets.US_ASCII)
            if (n > c.remaining - 4) invalid()
            if (size == null && kind != "IHDR") invalid()
            if (kind != "IDAT") {
                metadata += n + 12
                if (metadata > 1048576) refuse(OperationErrorCode.RESOURCE_LIMIT, "source_metadata_limit")
            }
            val crc = CRC32().apply { update(kindBytes) }
            val fields = when (kind) {
                "IHDR", "sRGB", "gAMA", "cHRM", "eXIf" -> c.bytes(n.toInt()).also(crc::update)
                else -> { c.drain(n, crc); null }
            }
            if (crc.value != c.u32(false)) invalid()
            if (kind != "IDAT" && dataSeen) dataEnded = true
            when (kind) {
                "IHDR" -> {
                    if (size != null || n != 13L) invalid()
                    val f = checkNotNull(fields)
                    size = dimensions(uint(f, 0), uint(f, 4))
                    val depth = f[8].toInt() and 255
                    val color = f[9].toInt() and 255
                    val legalDepth = if (color in setOf(0, 3)) depth in setOf(1, 2, 4, 8) else color in setOf(2, 4, 6) && depth == 8
                    if (!legalDepth || f[10].toInt() != 0 || f[11].toInt() != 0 || f[12].toInt() !in 0..1) unsupported()
                }
                "IDAT" -> { if (dataEnded) invalid(); dataSeen = true }
                "IEND" -> { if (!dataSeen || n != 0L || c.remaining != 0L) invalid(); return EncodedSourceHeader("image/png", checkNotNull(size), exif.orientation) }
                "eXIf" -> exif.accept(checkNotNull(fields), c.checkpoint)
                "acTL", "fcTL", "fdAT", "iCCP", "cICP", "mDCv", "cLLi" -> unsupported()
                "sRGB" -> if (n != 1L || checkNotNull(fields)[0].toInt() !in 0..3) invalid()
                "gAMA" -> if (n != 4L || uint(checkNotNull(fields), 0) != 45455L) unsupported()
                "cHRM" -> {
                    val expected = longArrayOf(31270, 32900, 64000, 33000, 30000, 60000, 15000, 6000)
                    if (n != 32L || expected.indices.any { uint(checkNotNull(fields), it * 4) != expected[it] }) unsupported()
                }
                "PLTE" -> if (dataSeen || n !in 3..768 || n % 3 != 0L) invalid()
                else -> if (kindBytes[0].toInt() and 32 == 0) unsupported() // Unknown critical chunks cannot be safely ignored.
            }
        }
        invalid()
    }

    private fun jpeg(c: Cursor): EncodedSourceHeader {
        if (c.byte() != 255 || c.byte() != 216) invalid()
        var size: PixelSize? = null
        var scans = 0
        var markers = 0
        val exif = ExifState()
        var exifSegment: LongRange? = null
        var exifCompatible = false
        var marker = nextMarker(c)
        while (true) {
            c.checkpoint()
            if (++markers > 4096) refuse(OperationErrorCode.RESOURCE_LIMIT, "source_metadata_limit")
            if (marker == 0xd9) {
                if (scans == 0) invalid()
                val primaryBytes = c.position
                if (c.remaining != 0L) {
                    if (!exifCompatible) invalid()
                    if (c.remaining > 65536L) refuse(OperationErrorCode.RESOURCE_LIMIT, "source_metadata_limit")
                    // The opaque vendor suffix is never parsed natively. Do not turn a visibly compound container into a still image.
                    val tail = c.bytes(c.remaining.toInt())
                    val raw = tail.toString(Charsets.ISO_8859_1)
                    if ("\u00ff\u00d8" in raw || "ftyp" in raw || "RIFF" in raw ||
                        raw.contains("MotionPhoto", ignoreCase = true) || raw.contains("GainMap", ignoreCase = true)
                    ) unsupported()
                }
                return EncodedSourceHeader("image/jpeg", size ?: invalid(), exif.orientation).also { header ->
                    if (exifCompatible) header.jpegProjection = JpegDecodeView(primaryBytes.toInt(), checkNotNull(exifSegment), exif.orientation)
                }
            }
            if (marker == 0xd8 || marker in 0xd0..0xd7 || marker == 0x01 || marker == 0x00) invalid()
            val segmentStart = c.position - 2 // Ends with final FF and marker; any preceding fill FF remains legal.
            val n = (c.byte() * 256 + c.byte()) - 2
            if (n < 0 || n.toLong() > c.remaining) invalid()
            when {
                marker in setOf(0xc0, 0xc2) -> {
                    if (size != null || n < 6) invalid()
                    val f = c.bytes(n)
                    val components = f[5].toInt() and 255
                    if (f[0].toInt() != 8 || components !in setOf(1, 3)) unsupported()
                    if (n != 6 + 3 * components) invalid()
                    size = dimensions(((f[3].toInt() and 255) * 256 + (f[4].toInt() and 255)).toLong(), ((f[1].toInt() and 255) * 256 + (f[2].toInt() and 255)).toLong())
                }
                marker in 0xc0..0xcf && marker !in setOf(0xc4, 0xc8, 0xcc) -> unsupported()
                marker == 0xda -> {
                    if (size == null || n < 6) invalid()
                    c.drain(n.toLong()); scans++
                    marker = entropyMarker(c)
                    continue
                }
                marker in 0xe0..0xef || marker == 0xfe -> {
                    c.metadata(n.toLong())
                    val f = c.bytes(n)
                    if (marker == 0xe1 && f.size >= 6 && f.copyOfRange(0, 6).contentEquals("Exif\u0000\u0000".toByteArray())) {
                        exifCompatible = exif.accept(f, c.checkpoint, cameraJpeg = true)
                        exifSegment = segmentStart until c.position
                    }
                    val text = f.toString(Charsets.ISO_8859_1)
                    if (marker == 0xe2 && (text.startsWith("ICC_PROFILE\u0000") || text.startsWith("MPF\u0000"))) unsupported()
                    if (marker == 0xeb || (marker == 0xe1 && (text.contains("hdrgm:", true) || text.contains("gainmap", true)))) unsupported()
                }
                else -> c.drain(n.toLong())
            }
            marker = nextMarker(c)
        }
    }

    private fun nextMarker(c: Cursor): Int {
        if (c.byte() != 255) invalid()
        var marker: Int
        do { marker = c.byte() } while (marker == 255)
        return marker
    }

    private fun entropyMarker(c: Cursor): Int {
        while (true) {
            if (c.byte() != 255) continue
            var marker: Int
            do { marker = c.byte() } while (marker == 255)
            if (marker != 0 && marker !in 0xd0..0xd7) return marker
        }
    }

    private fun webp(c: Cursor, validLength: Boolean): EncodedSourceHeader {
        if (!validLength || c.bytes(4).toString(Charsets.US_ASCII) != "WEBP") invalid()
        var extended: PixelSize? = null
        var image: PixelSize? = null
        var chunks = 0
        val exif = ExifState()
        while (c.remaining > 0) {
            c.checkpoint()
            if (++chunks > 4096) refuse(OperationErrorCode.RESOURCE_LIMIT, "source_metadata_limit")
            val kind = c.bytes(4).toString(Charsets.US_ASCII)
            val n = c.u32(true)
            if (n + n % 2 > c.remaining) invalid()
            when (kind) {
                "VP8X" -> {
                    if (chunks != 1 || n != 10L) invalid()
                    val f = c.bytes(10); val flags = f[0].toInt() and 255
                    if (flags and 0x22 != 0) unsupported()
                    if (flags and 0xc1 != 0 || f.sliceArray(1..3).any { it.toInt() != 0 }) invalid()
                    extended = dimensions(u24(f, 4) + 1, u24(f, 7) + 1)
                }
                "VP8 ", "VP8L" -> {
                    if (image != null) invalid()
                    val needed = if (kind == "VP8 ") 10 else 5
                    if (n < needed) invalid()
                    val f = c.bytes(needed)
                    image = if (kind == "VP8 ") {
                        if (f[0].toInt() and 1 != 0 || (f[3].toInt() and 255) != 0x9d || f[4] != 1.toByte() || f[5] != 0x2a.toByte()) invalid()
                        dimensions(((f[6].toInt() and 255) + ((f[7].toInt() and 63) shl 8)).toLong(), ((f[8].toInt() and 255) + ((f[9].toInt() and 63) shl 8)).toLong())
                    } else {
                        if (f[0] != 0x2f.toByte() || f[4].toInt() and 0xe0 != 0) invalid()
                        dimensions(1L + (f[1].toInt() and 255) + ((f[2].toInt() and 63) shl 8), 1L + ((f[2].toInt() and 255) ushr 6) + ((f[3].toInt() and 255) shl 2) + ((f[4].toInt() and 15) shl 10))
                    }
                    c.drain(n - needed)
                }
                "ANIM", "ANMF", "ICCP" -> unsupported()
                "EXIF" -> { c.metadata(n); exif.accept(c.bytes(n.toInt()), c.checkpoint) }
                else -> { c.metadata(n); c.drain(n) }
            }
            if (n % 2 == 1L && c.byte() != 0) invalid()
        }
        val size = image ?: invalid()
        if (extended != null && extended != size) invalid()
        return EncodedSourceHeader("image/webp", size, exif.orientation)
    }

    private class ExifState {
        var orientation: Int = 1
            private set
        private var seen = false
        fun accept(bytes: ByteArray, checkpoint: () -> Unit, cameraJpeg: Boolean = false): Boolean {
            if (seen) invalid()
            seen = true
            if (!cameraJpeg) {
                orientation = TiffOrientation.read(bytes, checkpoint)
                return false
            }
            val (value, compatibility) = TiffOrientation.readCameraJpeg(bytes, checkpoint)
            orientation = value
            return compatibility
        }
    }

    private class Cursor(private val input: InputStream, private val length: Long, val checkpoint: () -> Unit) {
        var remaining: Long = length
            private set
        val position: Long get() = length - remaining
        private var metadataBytes = 0L
        private var nextCheck = 65536
        private val scratch = ByteArray(65536)
        fun byte(): Int {
            if (remaining <= 0) throw EOFException()
            val b = input.read(); if (b < 0) throw EOFException()
            remaining--
            if (--nextCheck == 0) { checkpoint(); nextCheck = 65536 }
            return b
        }
        fun bytes(count: Int): ByteArray {
            if (count !in 0..1048576 || count.toLong() > remaining) invalid()
            return ByteArray(count).also { data ->
                var offset = 0
                while (offset < count) {
                    checkpoint()
                    val n = input.read(data, offset, count - offset)
                    if (n <= 0) throw EOFException()
                    remaining -= n; offset += n
                }
            }
        }
        fun drain(count: Long, crc: CRC32? = null) {
            if (count < 0 || count > remaining) invalid()
            var left = count
            while (left > 0) {
                checkpoint()
                val n = input.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
                if (n <= 0) throw EOFException()
                crc?.update(scratch, 0, n); remaining -= n; left -= n
            }
        }
        fun u32(little: Boolean): Long = uint(bytes(4), 0, little)
        fun metadata(count: Long) { metadataBytes += count; if (metadataBytes > 1048576) refuse(OperationErrorCode.RESOURCE_LIMIT, "source_metadata_limit") }
    }

    private fun uint(bytes: ByteArray, at: Int, little: Boolean = false): Long {
        if (at < 0 || at.toLong() + 4 > bytes.size) invalid()
        var result = 0L
        for (i in 0..3) result = (result shl 8) or (bytes[at + if (little) 3 - i else i].toLong() and 255)
        return result
    }
    private fun u24(bytes: ByteArray, at: Int): Long = (bytes[at].toLong() and 255) + ((bytes[at + 1].toLong() and 255) shl 8) + ((bytes[at + 2].toLong() and 255) shl 16)
    private fun dimensions(width: Long, height: Long): PixelSize {
        if (width <= 0 || height <= 0) invalid()
        if (width > 12000 || height > 12000 || width * height > 48_000_000) refuse(OperationErrorCode.RESOURCE_LIMIT, "source_metadata_limit")
        return PixelSize(width.toInt(), height.toInt())
    }
    private fun invalid(): Nothing = refuse(OperationErrorCode.INVALID_INPUT, "invalid_source_container")
    private fun unsupported(): Nothing = refuse(OperationErrorCode.UNSUPPORTED_FORMAT, "source_color_or_frames_unsupported")
    private fun refuse(code: OperationErrorCode, key: String, retryable: Boolean = false): Nothing = throw RepositoryFailure(OperationError(code, retryable, key, null))
}
