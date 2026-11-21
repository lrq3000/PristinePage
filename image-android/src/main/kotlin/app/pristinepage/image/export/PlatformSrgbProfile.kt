package app.pristinepage.image.export

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

/** Recognize the small matrix/shaper profiles produced by the platform sRGB encoder, not arbitrary ICC transforms. */
internal object PlatformSrgbProfile {
    const val MAX_BYTES = 4096
    private val required = setOf("rXYZ", "gXYZ", "bXYZ", "wtpt", "rTRC", "gTRC", "bTRC")
    private val allowed = required + setOf("desc", "cprt")
    private val white = doubleArrayOf(.9642, 1.0, .8249)
    private val primaries = mapOf(
        "rXYZ" to doubleArrayOf(.4360747, .2225045, .0139322),
        "gXYZ" to doubleArrayOf(.3850649, .7168786, .0971045),
        "bXYZ" to doubleArrayOf(.1430804, .0606169, .7141733),
        "wtpt" to white,
    )
    private val transfer = doubleArrayOf(2.4, 1 / 1.055, .055 / 1.055, 1 / 12.92, .04045, 0.0, 0.0)

    fun matches(bytes: ByteArray): Boolean {
        if (bytes.size !in 132..MAX_BYTES) return false
        val profile = ByteBuffer.wrap(bytes) // ICC integers are big endian; offsets and lengths below must fit this segment.
        fun signature(offset: Int) = String(bytes, offset, 4, Charsets.US_ASCII)
        fun matchesFixed(offset: Int, expected: DoubleArray, tolerance: Long) =
            expected.indices.all { i -> abs(profile.getInt(offset + 4 * i).toLong() - (expected[i] * 65536).roundToInt()) <= tolerance }
        if (profile.getInt(0) != bytes.size || (bytes[8].toInt() != 2 && bytes[8].toInt() != 4) ||
            signature(12) != "mntr" || signature(16) != "RGB " || signature(20) != "XYZ " || signature(36) != "acsp" ||
            profile.getInt(64) !in 0..3 || !matchesFixed(68, white, 1)) return false
        val count = profile.getInt(128)
        if (count !in required.size..allowed.size || count > (bytes.size - 132) / 12) return false
        val dataStart = 132 + 12 * count
        val seen = mutableSetOf<String>()
        val spans = mutableListOf<Tag>()
        for (i in 0 until count) {
            val row = 132 + 12 * i
            val id = signature(row)
            val start = profile.getInt(row + 4)
            val length = profile.getInt(row + 8)
            if (id !in allowed || !seen.add(id) || start !in dataStart..bytes.size || start % 4 != 0 ||
                length !in 8..(bytes.size - start) || profile.getInt(start + 4) != 0) return false
            val tag = Tag(id, start, start + length)
            if (spans.any { it.start < tag.end && tag.start < it.end && !it.sharesCurve(tag) }) return false
            spans += tag
            val type = signature(start)
            val xyz = primaries[id]
            when {
                // Tolerance covers the old floating D50 matrix and current Skia's fixed-point matrix (≤6 units).
                xyz != null -> if (length != 20 || type != "XYZ " || !matchesFixed(start + 8, xyz, 6)) return false
                id.endsWith("TRC") -> if (length != 40 || type != "para" || profile.getInt(start + 8) != 0x40000 ||
                    !matchesFixed(start + 12, transfer, 1)) return false
                type != "mluc" -> return false // Description and copyright are discarded, not interpreted.
            }
        }
        return seen.containsAll(required)
    }

    private data class Tag(val id: String, val start: Int, val end: Int) {
        fun sharesCurve(other: Tag) = id.endsWith("TRC") && other.id.endsWith("TRC") && start == other.start && end == other.end
    }
}
