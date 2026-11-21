package app.pristinepage.core.image

import app.pristinepage.core.recipe.AppearanceRecipe
import kotlin.math.abs

/** Versioned local paper-neutralization candidate. Chroma/luma similarity protects foreground instead of stretching each RGB channel indiscriminately. */
object DocumentColor {
    @JvmStatic fun workspaceBytes(width: Int, height: Int): Long = PaperGain.workspaceBytes(width, height)

    @JvmStatic fun apply(input: RgbImage, recipe: AppearanceRecipe.DocumentColorV1, protection: ProtectionMask? = null, checkpoint: () -> Unit = {}): RgbImage {
        AppearanceBuffers.preflight(input, protection)
        val n = AppearanceBuffers.pixels(input.width, input.height)
        workspaceBytes(input.width, input.height)
        checkpoint()
        if (recipe.strength == 0.0) return AppearanceBuffers.retain(input, checkpoint)
        val field = PaperGain.estimatePaper(input, checkpoint)
        val pageConfidence = ramp(field.typical.toDouble(), 104.0, 160.0)
        if (pageConfidence == 0.0) return AppearanceBuffers.retain(input, checkpoint)

        val output = ByteArray((3L * n).toInt())
        for (y in 0 until input.height) {
            checkpoint()
            for (x in 0 until input.width) {
                val r = input.getUnsigned(x, y, 0)
                val g = input.getUnsigned(x, y, 1)
                val b = input.getUnsigned(x, y, 2)
                val p = protection?.sample(x, y) ?: 0f
                val offset = 3 * (y * input.width + x)
                val br = field.sample(x, y, 0).toDouble()
                val bg = field.sample(x, y, 1).toDouble()
                val bb = field.sample(x, y, 2).toDouble()
                val paperY = (77 * br + 150 * bg + 29 * bb) / 256
                val localConfidence = ramp(paperY, 80.0, 152.0)
                val affinity = if (p == 1f || localConfidence == 0.0) 0.0 else {
                    val distance = maxOf(abs((r - g) - (br - bg)), abs((b - g) - (bb - bg)), abs((r - b) - (br - bb)))
                    val relativeLuma = Numeric.luma(r, g, b) / paperY
                    // Preserve strokes on the darker side of the local paper estimate and independently protect colored marks.
                    pageConfidence * localConfidence * ramp(relativeLuma, .58, .98) * (1 - ramp(distance, 10.0, 45.0))
                }
                if (affinity == 0.0) {
                    output[offset] = r.toByte()
                    output[offset + 1] = g.toByte()
                    output[offset + 2] = b.toByte()
                    continue
                }
                val brightness = (238 - paperY).coerceIn(0.0, 80.0)
                val dr = brightness + (paperY - br).coerceIn(-36.0, 36.0)
                val dg = brightness + (paperY - bg).coerceIn(-36.0, 36.0)
                val db = brightness + (paperY - bb).coerceIn(-36.0, 36.0)
                // Fade to no correction at either gamut boundary, so a directional cap cannot switch abruptly when a saturated channel's shift changes sign.
                // Include the estimated paper so nearby faint marks do not get extra correction just because their pixel has more room than the paper.
                val lowest = minOf(minOf(r, g, b).toDouble(), br, bg, bb)
                val highest = maxOf(maxOf(r, g, b).toDouble(), br, bg, bb)
                val endpointWeight = ramp(minOf(lowest, 255.0 - highest), 0.0, 16.0)
                val scale = minOf(affinity * endpointWeight, headroom(r, br, dr), headroom(g, bg, dg), headroom(b, bb, db))
                output[offset] = blended(r, scale * dr, recipe.strength, p)
                output[offset + 1] = blended(g, scale * dg, recipe.strength, p)
                output[offset + 2] = blended(b, scale * db, recipe.strength, p)
            }
        }
        checkpoint()
        return AppearanceBuffers.output(input, output, checkpoint)
    }

    private fun ramp(value: Double, low: Double, high: Double): Double {
        val t = ((value - low) / (high - low)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    private fun headroom(value: Int, paper: Double, delta: Double): Double = when {
        delta > 0.0 -> ((255.0 - maxOf(value.toDouble(), paper)) / delta).coerceAtLeast(0.0)
        delta < 0.0 -> (minOf(value.toDouble(), paper) / -delta).coerceAtLeast(0.0)
        else -> 1.0
    }

    private fun blended(value: Int, delta: Double, strength: Double, protection: Float): Byte {
        val candidate = Numeric.roundByte(value + delta)
        return StageBlend.channel(value, candidate.toDouble(), strength, protection).toByte()
    }
}
