package app.pristinepage.core.image

import app.pristinepage.core.recipe.AppearanceRecipe
import kotlin.math.floor

/** Independent log-lightness reference from PHOTOMETRIC_ALGORITHMS §4; no source-code port, cubic overshoot, percentile restretch or automatic contrast policy. */
object NaturalLightness {
    @JvmStatic fun workspaceBytes(width: Int, height: Int): Long {
        val n = AppearanceBuffers.pixels(width, height)
        val working = half(width).toLong() * half(height)
        // One L plane, four working planes, working+defensive-copy RGB output, one periodic Double prefix and conservative fixed/header scratch.
        return Math.addExact(Math.addExact(10L * n, 16L * working), Math.addExact(16L * maxOf(width, height), 8192L))
    }

    @JvmStatic fun apply(input: RgbImage, recipe: AppearanceRecipe.NaturalLV1, protection: ProtectionMask? = null, checkpoint: () -> Unit = {}): RgbImage {
        AppearanceBuffers.preflight(input, protection)
        workspaceBytes(input.width, input.height)
        checkpoint()
        if (recipe.strength == 0.0 || recipe.lightnessBlend == 0.0) return AppearanceBuffers.retain(input, checkpoint)

        val width = input.width; val height = input.height
        val lightness = FloatArray((width.toLong() * height).toInt())
        val lab = FloatArray(3)
        var sumL = 0.0; var minimum = Double.POSITIVE_INFINITY; var maximum = Double.NEGATIVE_INFINITY
        for (y in 0 until height) {
            checkpoint()
            for (x in 0 until width) {
                ColorLab.forward(input.getUnsigned(x, y, 0), input.getUnsigned(x, y, 1), input.getUnsigned(x, y, 2), lab)
                val l = lab[0]
                lightness[y * width + x] = l
                sumL += l.toDouble(); minimum = minOf(minimum, l.toDouble()); maximum = maxOf(maximum, l.toDouble())
            }
        }
        if (maximum - minimum <= 1e-6) return AppearanceBuffers.retain(input, checkpoint)
        val meanL = sumL / lightness.size
        val ws = half(width); val hs = half(height)
        val count = ws * hs
        val z = FloatArray(count)
        areaLog(lightness, width, height, z, checkpoint)
        val residual = FloatArray(count)
        val horizontal = FloatArray(count)
        val blurred = FloatArray(count)
        // One complete reflect101 period plus its leading zero. No window-sized buffers or initialization scans are needed.
        val prefix = DoubleArray(2 * maxOf(ws, hs) + 1)
        // Preserve each Float box result, not a prematurely rounded partial reduction. The last vertical pass may overwrite its horizontal scratch: the entire current column is already in the Double prefix before any output in that column is written. Other columns remain untouched. This schedule retains four working Float planes rather than adding a Double residual plane.
        val destinations = arrayOf(residual, blurred, horizontal)
        for ((scale, q) in intArrayOf(80, 10, 2).withIndex()) {
            val nominal = maxOf(3, maxOf(ws, hs) / q)
            val kernel = if (nominal % 2 == 0) nominal + 1 else nominal
            box(z, ws, hs, kernel, horizontal, destinations[scale], prefix, checkpoint)
        }
        minimum = Double.POSITIVE_INFINITY; maximum = Double.NEGATIVE_INFINITY
        for (y in 0 until hs) {
            checkpoint()
            for (x in 0 until ws) {
                val index = y * ws + x
                val original = z[index].toDouble()
                val first = original - residual[index].toDouble()
                val second = original - blurred[index].toDouble()
                val third = original - horizontal[index].toDouble()
                residual[index] = (((first + second) + third) / 3.0).toFloat()
                minimum = minOf(minimum, residual[index].toDouble()); maximum = maxOf(maximum, residual[index].toDouble())
            }
        }
        if (maximum - minimum <= 1e-6) return AppearanceBuffers.retain(input, checkpoint)
        for (y in 0 until hs) {
            checkpoint()
            for (x in 0 until ws) {
                val index = y * ws + x
                residual[index] = ((residual[index].toDouble() - minimum) / (maximum - minimum)).coerceIn(0.0, 1.0).toFloat()
            }
        }

        val output = ByteArray((3L * width * height).toInt())
        for (y in 0 until height) {
            checkpoint()
            for (x in 0 until width) {
                val red = input.getUnsigned(x, y, 0); val green = input.getUnsigned(x, y, 1); val blue = input.getUnsigned(x, y, 2)
                val p = protection?.sample(x, y) ?: 0f
                val index = y * width + x
                if (p == 1f) {
                    output[index * 3] = red.toByte(); output[index * 3 + 1] = green.toByte(); output[index * 3 + 2] = blue.toByte()
                    continue
                }
                val u = upsample(residual, ws, hs, width, height, x, y)
                val f = mixLightness(lightness[index], meanL, u, recipe.amplitudeL255, recipe.lightnessBlend)
                // Original chroma is recomputed from source, not from a modified row and not retained as two more image-sized planes.
                ColorLab.forward(red, green, blue, lab)
                val candidate = ColorLab.inverse(f, lab[1], lab[2])
                output[index * 3] = StageBlend.channel(red, ((candidate ushr 16) and 255).toDouble(), recipe.strength, p).toByte()
                output[index * 3 + 1] = StageBlend.channel(green, ((candidate ushr 8) and 255).toDouble(), recipe.strength, p).toByte()
                output[index * 3 + 2] = StageBlend.channel(blue, (candidate and 255).toDouble(), recipe.strength, p).toByte()
            }
        }
        checkpoint()
        return AppearanceBuffers.output(input, output, checkpoint)
    }

    private fun half(value: Int): Int = ((value.toLong() + 1) / 2).toInt()

    private fun areaLog(lightness: FloatArray, width: Int, height: Int, output: FloatArray, checkpoint: () -> Unit) {
        val ws = half(width); val hs = half(height)
        for (y in 0 until hs) {
            checkpoint()
            for (x in 0 until ws) {
                var sum = 0.0; var count = 0
                for (sy in y * 2 until minOf(y * 2 + 2, height)) for (sx in x * 2 until minOf(x * 2 + 2, width)) {
                    sum += lightness[sy * width + sx].toDouble() + 1.0; count++
                }
                val area = (sum / count).toFloat()
                output[y * ws + x] = StrictMath.log(area.toDouble()).toFloat()
            }
        }
    }

    /** Separable reflect101 box: Double prefix reductions, then Float storage after each axis. Rebuilding one complete period keeps even huge-window/singleton work O(N), not O(kN). */
    private fun box(input: FloatArray, width: Int, height: Int, kernel: Int, horizontal: FloatArray, output: FloatArray, prefix: DoubleArray, checkpoint: () -> Unit) {
        val radius = kernel.toLong() / 2
        for (y in 0 until height) {
            checkpoint()
            val period = buildPrefix(input, y * width, 1, width, prefix, null)
            for (x in 0 until width) horizontal[y * width + x] = ((prefixAt(x.toLong() + radius + 1, period, prefix) - prefixAt(x.toLong() - radius, period, prefix)) / kernel).toFloat()
        }
        // Vertical prefix formation itself crosses rows and must be interruptible, not only the subsequent output scan.
        for (x in 0 until width) {
            checkpoint()
            val period = buildPrefix(horizontal, x, width, height, prefix, checkpoint)
            for (y in 0 until height) {
                checkpoint()
                output[y * width + x] = ((prefixAt(y.toLong() + radius + 1, period, prefix) - prefixAt(y.toLong() - radius, period, prefix)) / kernel).toFloat()
            }
        }
    }

    private fun buildPrefix(input: FloatArray, start: Int, step: Int, size: Int, prefix: DoubleArray, rowCheckpoint: (() -> Unit)?): Int {
        val period = if (size == 1) 1 else 2 * (size - 1)
        prefix[0] = 0.0
        for (i in 0 until period) {
            rowCheckpoint?.invoke()
            val index = if (i < size) i else period - i
            prefix[i + 1] = prefix[i] + input[start + index * step].toDouble()
        }
        return period
    }

    private fun prefixAt(end: Long, period: Int, prefix: DoubleArray): Double {
        val cycles = Math.floorDiv(end, period.toLong())
        val remainder = Math.floorMod(end, period.toLong()).toInt()
        return cycles.toDouble() * prefix[period] + prefix[remainder]
    }

    private fun upsample(input: FloatArray, ws: Int, hs: Int, width: Int, height: Int, x: Int, y: Int): Float {
        val u = ((x + .5) * ws / width - .5).coerceIn(0.0, (ws - 1).toDouble())
        val v = ((y + .5) * hs / height - .5).coerceIn(0.0, (hs - 1).toDouble())
        val x0 = floor(u).toInt(); val x1 = minOf(x0 + 1, ws - 1)
        val y0 = floor(v).toInt(); val y1 = minOf(y0 + 1, hs - 1)
        val tx = u - x0; val ty = v - y0
        val top = input[y0 * ws + x0].toDouble() + (input[y0 * ws + x1].toDouble() - input[y0 * ws + x0].toDouble()) * tx
        val bottom = input[y1 * ws + x0].toDouble() + (input[y1 * ws + x1].toDouble() - input[y1 * ws + x0].toDouble()) * tx
        return (top + (bottom - top) * ty).coerceIn(0.0, 1.0).toFloat()
    }

    private fun mixLightness(lightness: Float, meanL: Double, u: Float, amplitude: Double, blend: Double): Float {
        val target = meanL + amplitude * (u.toDouble() - .5)
        return ((1.0 - blend) * lightness.toDouble() + blend * target).coerceIn(0.0, 255.0).toFloat()
    }
}
