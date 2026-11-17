package app.pristinepage.core.image

import app.pristinepage.core.recipe.DenoiseRecipe

/** Square bilateral_luma_v1 on immutable integer Y8. Every reflected offset retains its own spatial weight, including duplicate folded samples on singleton axes. */
object LumaDenoise {
    @JvmStatic fun workspaceBytes(width: Int, height: Int): Long = Math.addExact(7L * AppearanceBuffers.pixels(width, height), 8192L)

    @JvmStatic fun apply(input: RgbImage, recipe: DenoiseRecipe.BilateralLumaV1, protection: ProtectionMask? = null, checkpoint: () -> Unit = {}): RgbImage {
        AppearanceBuffers.preflight(input, protection)
        workspaceBytes(input.width, input.height)
        checkpoint()
        if (recipe.strength == 0.0) return AppearanceBuffers.retain(input, checkpoint)
        val width = input.width; val height = input.height
        val luma = FinishingBuffers.luma(input, checkpoint)
        val output = ByteArray((3L * width * height).toInt())
        val spatialDenominator = 2.0 * recipe.sigmaSpatialPx * recipe.sigmaSpatialPx
        val rangeDenominator = 2.0 * recipe.sigmaRange * recipe.sigmaRange
        for (y in 0 until height) {
            checkpoint()
            for (x in 0 until width) {
                val index = y * width + x
                val center = luma[index].toInt() and 255
                val red = input.getUnsigned(x, y, 0); val green = input.getUnsigned(x, y, 1); val blue = input.getUnsigned(x, y, 2)
                val protectionValue = protection?.sample(x, y) ?: 0f
                var sumWeight = 0.0; var sumWeightedY = 0.0
                for (dy in -recipe.radiusPx..recipe.radiusPx) for (dx in -recipe.radiusPx..recipe.radiusPx) {
                    val sx = Numeric.reflect101(x + dx, width); val sy = Numeric.reflect101(y + dy, height)
                    val neighbor = luma[sy * width + sx].toInt() and 255
                    val difference = (neighbor - center).toDouble()
                    val weight = StrictMath.exp(-(dx * dx + dy * dy).toDouble() / spatialDenominator - difference * difference / rangeDenominator)
                    sumWeight += weight
                    sumWeightedY += weight * neighbor
                }
                val filtered = (sumWeightedY / sumWeight).toFloat()
                val change = FinishingBuffers.headroom(red, green, blue, filtered.toDouble() - center)
                for (channel in 0..2) {
                    val original = input.getUnsigned(x, y, channel)
                    val candidate = Numeric.roundByte(original + change)
                    output[3 * index + channel] = StageBlend.channel(original, candidate.toDouble(), recipe.strength, protectionValue).toByte()
                }
            }
        }
        return AppearanceBuffers.output(input, output, checkpoint)
    }
}

/** Shared byte-plane preparation is source-only; no finishing stage reads its newly emitted RGB back into statistics. Admission remains the caller's responsibility, including both RGB constructor copies. */
internal object FinishingBuffers {
    fun luma(input: RgbImage, checkpoint: () -> Unit): ByteArray {
        val result = ByteArray(AppearanceBuffers.pixels(input.width, input.height).toInt())
        for (y in 0 until input.height) {
            checkpoint()
            for (x in 0 until input.width) result[y * input.width + x] = Numeric.luma(input.getUnsigned(x, y, 0), input.getUnsigned(x, y, 1), input.getUnsigned(x, y, 2)).toByte()
        }
        return result
    }

    fun headroom(red: Int, green: Int, blue: Int, change: Double): Double = change.coerceIn(-minOf(red, green, blue).toDouble(), (255 - maxOf(red, green, blue)).toDouble())
}
