package app.pristinepage.core.image

import kotlin.math.abs
import kotlin.math.floor

object Numeric {
    @JvmStatic fun roundByte(value: Double): Int {
        require(value.isFinite()) { "Nonfinite byte candidate" }
        return floor(value.coerceIn(0.0, 255.0) + 0.5).toInt()
    }

    @JvmStatic fun roundSigned(value: Double): Int {
        require(value.isFinite() && abs(value) < Int.MAX_VALUE.toDouble())
        val magnitude = floor(abs(value) + 0.5).toInt()
        return if (value < 0) -magnitude else magnitude
    }

    @JvmStatic fun luma(red: Int, green: Int, blue: Int): Int {
        require(red in 0..255 && green in 0..255 && blue in 0..255)
        return (77 * red + 150 * green + 29 * blue) / 256
    }

    @JvmStatic fun reflect101(index: Int, size: Int): Int {
        require(size > 0)
        if (size == 1) return 0
        val period = 2L * (size - 1)
        val folded = ((index.toLong() % period) + period) % period
        return (if (folded < size) folded else period - folded).toInt()
    }

    /** The input candidate is already clipped/quantized by its own stage. Separate Double operations deliberately forbid a Float scratch or fused multiply-add. */
    @JvmStatic fun compose(input: Int, candidate: Double, strength: Double, protection: Float): Int {
        require(input in 0..255 && candidate.isFinite() && candidate in 0.0..255.0)
        require(strength.isFinite() && strength in 0.0..1.0 && protection.isFinite() && protection in 0f..1f)
        if (strength == 0.0 || protection == 1f) return input
        val p = 1.0 - protection.toDouble()
        val a = strength * p
        val b = 1.0 - a
        val left = b * input.toDouble()
        val right = a * candidate
        val mixed = left + right
        return roundByte(mixed)
    }
}
