package app.pristinepage.core.geometry

import app.pristinepage.core.image.CoverageMask
import app.pristinepage.core.image.Numeric
import app.pristinepage.core.image.ProtectionMask
import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.PixelSize
import kotlin.math.floor

/** Source pixel-center sampling; RGB, photometric protection, page coverage and geometric support deliberately retain distinct outside semantics. No input buffer is copied or mutated. */
object BilinearSampler {
    @JvmStatic fun rgb(source: RgbImage, x: Double, y: Double, channel: Int): Int {
        require(channel in 0..2) { "RGB channel must be in0..2" }
        val value = constantBorder(source.width, source.height, x, y, 255.0) { ix, iy -> source.getUnsigned(ix, iy, channel).toDouble() }
        return Numeric.roundByte(value)
    }

    @JvmStatic fun protection(source: ProtectionMask, x: Double, y: Double): Float =
        constantBorder(source.width, source.height, x, y, 1.0) { ix, iy -> source.sample(ix, iy).toDouble() }.coerceIn(0.0, 1.0).toFloat()

    @JvmStatic fun coverage(source: CoverageMask, x: Double, y: Double): Float =
        constantBorder(source.width, source.height, x, y, 0.0) { ix, iy -> source.sample(ix, iy).toDouble() }.coerceIn(0.0, 1.0).toFloat()

    @JvmStatic fun support(size: PixelSize, x: Double, y: Double): Double =
        constantBorder(size.width, size.height, x, y, 0.0) { _, _ -> 1.0 }

    /** x/y are already half-pixel-resize source coordinates, not destination indices. Clamp each neighbor rather than importing geometry's white border. */
    @JvmStatic fun resizedRgb(source: RgbImage, x: Double, y: Double, channel: Int): Int {
        require(channel in 0..2) { "RGB channel must be in0..2" }
        requireFinite(x, y)
        // Beyond this interval both neighbors clamp to the same edge; bounding before floor also handles finite coordinates too large for an integer.
        val boundedX = x.coerceIn(-1.0, source.width.toDouble())
        val boundedY = y.coerceIn(-1.0, source.height.toDouble())
        val value = interpolate(boundedX, boundedY) { ix, iy ->
            source.getUnsigned(ix.coerceIn(0L, source.width.toLong() - 1).toInt(), iy.coerceIn(0L, source.height.toLong() - 1).toInt(), channel).toDouble()
        }
        return Numeric.roundByte(value)
    }

    private fun requireFinite(x: Double, y: Double) { require(x.isFinite() && y.isFinite()) { "Sampling coordinates must be finite" } }

    private inline fun constantBorder(width: Int, height: Int, x: Double, y: Double, outside: Double, read: (Int, Int) -> Double): Double {
        requireFinite(x, y)
        // The entire bilinear footprint is outside here. Do not narrow extreme finite coordinates before making this decision.
        if (x < -1.0 || x > width.toDouble() || y < -1.0 || y > height.toDouble()) return outside
        return interpolate(x, y) { ix, iy ->
            if (ix >= 0L && ix < width.toLong() && iy >= 0L && iy < height.toLong()) read(ix.toInt(), iy.toInt()) else outside
        }
    }

    private inline fun interpolate(x: Double, y: Double, read: (Long, Long) -> Double): Double {
        val floorX = floor(x)
        val floorY = floor(y)
        // Callers bound coordinates before reaching this helper. Long neighbors avoid i+1 overflow even for support-only PixelSize(Int.MAX_VALUE, ...).
        val i = floorX.toLong()
        val j = floorY.toLong()
        val a = x - floorX
        val b = y - floorY
        val w00 = (1.0 - a) * (1.0 - b)
        val w10 = a * (1.0 - b)
        val w01 = (1.0 - a) * b
        val w11 = a * b
        // Separate binary64 multiplies and left-associated sums fix the byte oracle: no FMA, intermediate quantization, or reassociation into two linear passes.
        val term00 = w00 * read(i, j)
        val term10 = w10 * read(i + 1, j)
        val term01 = w01 * read(i, j + 1)
        val term11 = w11 * read(i + 1, j + 1)
        val top = term00 + term10
        val through01 = top + term01
        return through01 + term11
    }
}
