package app.pristinepage.image.export

import kotlin.math.abs

/** Compare independently rasterized, positioned opaque sRGB samples. Tolerances are candidates, not a physical-device fidelity claim. */
internal object PdfPageEvidence {
    fun matches(expected: IntArray, actual: IntArray): Boolean {
        if (expected.isEmpty() || actual.size != expected.size) return false
        var sum = 0L
        var severe = 0L
        for (i in expected.indices) {
            val a = expected[i]
            val b = actual[i]
            if (a ushr 24 != 255 || b ushr 24 != 255) return false
            val r = abs((a shr 16 and 255) - (b shr 16 and 255))
            val g = abs((a shr 8 and 255) - (b shr 8 and 255))
            val blue = abs((a and 255) - (b and 255))
            sum += r + g + blue
            if (maxOf(r, g, blue) > 64) severe++
        }
        return sum <= 8L * 3 * actual.size && severe * 100 <= 3L * actual.size
    }
}
