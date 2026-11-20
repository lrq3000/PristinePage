package app.pristinepage.image.decode

import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure

/** Narrow, nonmaterializing EXIF/TIFF envelope reader. It validates every reachable declared value BEFORE any platform metadata/codec parsing, but only exposes primary orientation. No component-count-sized allocation or general maker-note interpreter exists here. */
object TiffOrientation {
    fun read(bytes: ByteArray, checkpoint: () -> Unit): Int = Reader(bytes, checkpoint).read()

    /** These narrow exceptions are only safe when the complete APP1 is replaced before native decode. */
    internal fun readCameraJpeg(bytes: ByteArray, checkpoint: () -> Unit): Pair<Int, Boolean> {
        val reader = Reader(bytes, checkpoint, cameraJpeg = true)
        return reader.read() to reader.needsProjection
    }

    private class Reader(private val bytes: ByteArray, private val checkpoint: () -> Unit, private val cameraJpeg: Boolean = false) {
        private val base = if (bytes.size >= 6 && bytes.copyOfRange(0, 6).contentEquals("Exif\u0000\u0000".toByteArray())) 6 else 0
        private val length = bytes.size - base
        private var little = false
        private val visited = HashSet<Long>()
        private var entries = 0
        private var charged = 0L
        private var orientation = 1
        var needsProjection = false
            private set

        fun read(): Int {
            checkpoint()
            if (length !in 8..1048576) invalid()
            little = when {
                b(0) == 73 && b(1) == 73 -> true
                b(0) == 77 && b(1) == 77 -> false
                else -> invalid()
            }
            if (u16(2) != 42) invalid()
            val first = u32(4)
            if (first < 8) invalid()
            directory(first, 1, true)
            return orientation
        }

        private fun directory(offset: Long, depth: Int, primary: Boolean) {
            checkpoint()
            if (depth > 8 || visited.size >= 64) refuse(OperationErrorCode.RESOURCE_LIMIT, "source_metadata_limit")
            if (!visited.add(offset)) invalid()
            range(offset, 2)
            val count = u16(offset.toInt())
            if (count > 4096 - entries) refuse(OperationErrorCode.RESOURCE_LIMIT, "source_metadata_limit")
            entries += count
            range(offset + 2, count.toLong() * 12 + 4)
            val tags = HashSet<Int>()
            var firstMakerNoteIsOpaque = false
            for (i in 0 until count) {
                checkpoint()
                val entry = (offset + 2 + i * 12L).toInt()
                val tag = u16(entry)
                val type = u16(entry + 2)
                val components = u32(entry + 4)
                val first = tags.add(tag)
                if (first && tag == 0x927c) firstMakerNoteIsOpaque = type == 7
                if (!first) {
                    if (!cameraJpeg || primary || tag != 0x927c || type != 7 || !firstMakerNoteIsOpaque) invalid()
                    needsProjection = true
                }
                if (type !in 1..13) invalid()
                if (components == 0L) {
                    if (!cameraJpeg || primary || tag != 0x010d || type != 7) invalid()
                    needsProjection = true
                    continue // An empty optional field has no payload or pointer.
                }
                val byteCount = components * TYPE_BYTES[type] // uint32 * at-most8 is safe in Long.
                val value = if (byteCount <= 4) (entry + 8).toLong() else u32(entry + 8)
                range(value, byteCount)
                if (byteCount > 1048576 - charged) refuse(OperationErrorCode.RESOURCE_LIMIT, "source_metadata_limit")
                charged += byteCount
                when (tag) {
                    0x112 -> {
                        if (type != 3 || components != 1L) invalid()
                        val candidate = u16(value.toInt())
                        if (candidate !in 1..8) {
                            if (!cameraJpeg || candidate != 0) invalid()
                            needsProjection = true // Undefined camera orientation means the sensor raster as stored.
                        } else if (primary) orientation = candidate
                    }
                    0xa001 -> {
                        if (type != 3 || components != 1L) invalid()
                        if (u16(value.toInt()) != 1) refuse(OperationErrorCode.UNSUPPORTED_FORMAT, "source_profile_unsupported")
                    }
                    0x8769, 0x8825, 0xa005, 0x14a -> {
                        if (type !in setOf(4, 13) || (tag != 0x14a && components != 1L)) invalid()
                        if (components > 64) refuse(OperationErrorCode.RESOURCE_LIMIT, "source_metadata_limit")
                        repeat(components.toInt()) { index ->
                            val nested = u32((value + index * 4L).toInt())
                            if (nested < 8) invalid()
                            directory(nested, depth + 1, false)
                        }
                    }
                }
            }
            val next = u32((offset + 2 + count * 12L).toInt())
            if (next != 0L) { if (next < 8) invalid(); directory(next, depth + 1, false) }
        }

        private fun range(offset: Long, count: Long) { if (offset < 0 || count < 0 || offset > length || count > length - offset) invalid() }
        private fun b(at: Int): Int { range(at.toLong(), 1); return bytes[base + at].toInt() and 255 }
        private fun u16(at: Int): Int = if (little) b(at) or (b(at + 1) shl 8) else (b(at) shl 8) or b(at + 1)
        private fun u32(at: Int): Long {
            range(at.toLong(), 4)
            var result = 0L
            for (i in 0..3) result = (result shl 8) or b(at + if (little) 3 - i else i).toLong()
            return result
        }
    }

    private val TYPE_BYTES = intArrayOf(0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8, 4)
    private fun invalid(): Nothing = refuse(OperationErrorCode.INVALID_INPUT, "invalid_source_exif")
    private fun refuse(code: OperationErrorCode, key: String): Nothing = throw RepositoryFailure(OperationError(code, false, key, null))
}
