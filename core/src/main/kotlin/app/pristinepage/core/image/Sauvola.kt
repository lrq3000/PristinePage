package app.pristinepage.core.image

import app.pristinepage.core.recipe.BinaryRecipe
import app.pristinepage.core.recipe.WindowPolicy
import kotlin.math.floor

/** Final grayscale is Y8 representation conversion only, not a strength-blended appearance preset. */
object Grayscale {
    @JvmStatic fun workspaceBytes(width: Int, height: Int): Long = Math.addExact(6L * AppearanceBuffers.pixels(width, height), 8192L)

    @JvmStatic fun apply(input: RgbImage, protection: ProtectionMask? = null, checkpoint: () -> Unit = {}): RgbImage {
        AppearanceBuffers.preflight(input, protection)
        require(protection == null) { "Grayscale does not support protection masks" }
        workspaceBytes(input.width, input.height)
        checkpoint()
        val output = ByteArray((3L * input.width * input.height).toInt())
        for (y in 0 until input.height) {
            checkpoint()
            for (x in 0 until input.width) {
                val value = Numeric.luma(input.getUnsigned(x, y, 0), input.getUnsigned(x, y, 1), input.getUnsigned(x, y, 2)).toByte()
                val index = 3 * (y * input.width + x)
                output[index] = value; output[index + 1] = value; output[index + 2] = value
            }
        }
        return AppearanceBuffers.output(input, output, checkpoint)
    }
}

/** Serial sauvola_v1: clipped squares, int64 column/prefix sums and unrounded population thresholds. No integral images, white shortcut, cleanup or preceding filter is hidden here. */
object Sauvola {
    @JvmStatic fun workspaceBytes(width: Int, height: Int): Long {
        val count = AppearanceBuffers.pixels(width, height)
        Math.multiplyExact(count, 65025L)
        return Math.addExact(7L * count, Math.addExact(32L * width + 16L, 8192L))
    }

    /** Resolve the persisted window policy for execution provenance; a side is not a radius and explicit odd values are not silently capped to the image. */
    @JvmStatic fun resolvedWindow(width: Int, height: Int, policy: WindowPolicy): Int {
        require(width > 0 && height > 0)
        return when (policy) {
            WindowPolicy.AutoShortSideV1 -> floor(minOf(width, height) / 40.0 + .5).toInt().coerceIn(15, 101) or 1
            is WindowPolicy.ExplicitOdd -> policy.sidePx
        }
    }

    @JvmStatic fun apply(input: RgbImage, recipe: BinaryRecipe.SauvolaV1, protection: ProtectionMask? = null, checkpoint: () -> Unit = {}): RgbImage {
        AppearanceBuffers.preflight(input, protection)
        require(protection == null) { "Sauvola does not support protection masks" }
        workspaceBytes(input.width, input.height)
        checkpoint()
        val width = input.width; val height = input.height
        val radius = resolvedWindow(width, height, recipe.window) / 2
        val luma = FinishingBuffers.luma(input, checkpoint)
        val columns = LongArray(width); val squares = LongArray(width)
        val prefix = LongArray(width + 1); val prefixSquares = LongArray(width + 1)
        val output = ByteArray((3L * width * height).toInt())
        var activeTop = 0; var activeBottom = 0
        for (y in 0 until height) {
            checkpoint()
            val top = maxOf(0, y - radius); val bottom = minOf(height, y + radius + 1)
            while (activeTop < top) { checkpoint(); updateColumns(luma, width, activeTop++, -1, columns, squares) }
            while (activeBottom < bottom) { checkpoint(); updateColumns(luma, width, activeBottom++, 1, columns, squares) }
            prefix[0] = 0L; prefixSquares[0] = 0L
            for (x in 0 until width) {
                prefix[x + 1] = prefix[x] + columns[x]
                prefixSquares[x + 1] = prefixSquares[x] + squares[x]
            }
            for (x in 0 until width) {
                val left = maxOf(0, x - radius); val right = minOf(width, x + radius + 1)
                val count = (right - left).toLong() * (bottom - top)
                val boundary = threshold(prefix[right] - prefix[left], prefixSquares[right] - prefixSquares[left], count, recipe.k)
                val value = if ((luma[y * width + x].toInt() and 255).toDouble() > boundary) 255.toByte() else 0.toByte()
                val index = 3 * (y * width + x)
                output[index] = value; output[index + 1] = value; output[index + 2] = value
            }
        }
        return AppearanceBuffers.output(input, output, checkpoint)
    }

    private fun updateColumns(luma: ByteArray, width: Int, row: Int, sign: Int, columns: LongArray, squares: LongArray) {
        for (x in 0 until width) {
            val sample = (luma[row * width + x].toInt() and 255).toLong()
            columns[x] += sign * sample
            squares[x] += sign * sample * sample
        }
    }

    private fun threshold(sum: Long, squaredSum: Long, count: Long, k: Double): Double {
        val mean = sum.toDouble() / count
        val variance = maxOf(0.0, squaredSum.toDouble() / count - mean * mean)
        return mean * (1.0 + k * (StrictMath.sqrt(variance) / 128.0 - 1.0))
    }
}
