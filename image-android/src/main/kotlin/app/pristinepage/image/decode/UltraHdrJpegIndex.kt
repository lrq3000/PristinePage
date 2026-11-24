package app.pristinepage.image.decode

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Only the two-image MPF index is read; subimage metadata and compressed pixels never go to the native codec. */
internal class UltraHdrJpegIndex private constructor(
    private val primaryBytes: Long,
    private val gainStart: Long,
    private val gainBytes: Long,
) {
    fun startsAtPrimaryEnd(primaryEnd: Long) = primaryEnd == primaryBytes && gainStart == primaryEnd && gainBytes >= 4

    fun endsAt(total: Long) = gainBytes == total - gainStart

    companion object {
        fun isVersionOnlyAttribute(segment: ByteArray): Boolean {
            if (segment.size != 30) return false
            val data = ByteBuffer.wrap(segment, 4, segment.size - 4).slice()
            if (!setByteOrder(data) || data.getInt(4) != 8 || data.getShort(8).toInt() != 1) return false
            return (data.getShort(10).toInt() and 0xffff) == 0xb000 && data.getShort(12).toInt() == 7 &&
                data.getInt(14) == 4 && hasVersionBytes(data, 18) && data.getInt(22) == 0
        }

        fun parse(segment: ByteArray, tiffStart: Long): UltraHdrJpegIndex? {
            if (segment.size !in 54..4096) return null
            val data = ByteBuffer.wrap(segment, 4, segment.size - 4).slice()
            if (!setByteOrder(data)) return null
            val ifd = data.getInt(4)
            if (ifd !in 8..data.limit() - 6) return null
            val count = data.getShort(ifd).toInt()
            if (count !in 3..16 || count * 12 + 6 > data.limit() - ifd) return null
            if (data.getInt(ifd + 2 + count * 12) != 0) return null
            var version = false
            var imageCount = false
            var entries = 0
            for (i in 0 until count) {
                val row = ifd + 2 + i * 12
                val tag = data.getShort(row).toInt() and 0xffff
                val type = data.getShort(row + 2).toInt()
                val elements = data.getInt(row + 4)
                val value = data.getInt(row + 8)
                when (tag) {
                    0xb000 -> {
                        if (version || type != 7 || elements != 4 || !hasVersionBytes(data, row + 8)) return null
                        version = true
                    }
                    0xb001 -> {
                        if (imageCount || type != 4 || elements != 1 || value != 2) return null
                        imageCount = true
                    }
                    0xb002 -> {
                        if (entries != 0 || type != 7 || elements != 32 ||
                            value !in (ifd + count * 12 + 6)..(data.limit() - 32)) return null
                        entries = value
                    }
                }
            }
            if (!version || !imageCount || entries == 0 || data.getInt(entries) != 0x030000 ||
                data.getInt(entries + 8) != 0 || data.getInt(entries + 12) != 0 ||
                data.getInt(entries + 16) != 0 || data.getInt(entries + 28) != 0) return null
            val size = data.getInt(entries + 4).toLong() and 0xffffffffL
            val gainSize = data.getInt(entries + 20).toLong() and 0xffffffffL
            val gainOffset = data.getInt(entries + 24).toLong() and 0xffffffffL
            return UltraHdrJpegIndex(size, tiffStart + gainOffset, gainSize)
        }

        private fun setByteOrder(data: ByteBuffer): Boolean {
            data.order(when (data.getShort(0).toInt()) {
                0x4d4d -> ByteOrder.BIG_ENDIAN
                0x4949 -> ByteOrder.LITTLE_ENDIAN
                else -> return false
            })
            return data.getShort(2).toInt() == 42
        }

        private fun hasVersionBytes(data: ByteBuffer, offset: Int): Boolean =
            (0..3).all { data[offset + it].toInt() == "0100"[it].code }
    }
}
