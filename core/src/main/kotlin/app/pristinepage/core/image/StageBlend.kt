package app.pristinepage.core.image

/** The candidate has already been clipped and quantized by its algorithm; the existing ordered binary64 composer owns the one and only strength/protection blend. */
object StageBlend {
    @JvmStatic fun channel(input: Int, candidate: Double, strength: Double, protection: Float): Int = Numeric.compose(input, candidate, strength, protection)
}

/** Incremental workspace excludes the caller's source/mask. Public RGB construction copies its working array, so estimates include both live RGB arrays, not just the final lease. */
internal object AppearanceBuffers {
    private const val MAX_ARRAY_LENGTH = Int.MAX_VALUE - 8

    fun pixels(width: Int, height: Int): Long {
        require(width > 0 && height > 0) { "Appearance dimensions must be positive" }
        val count = Math.multiplyExact(width.toLong(), height.toLong())
        require(count <= MAX_ARRAY_LENGTH && Math.multiplyExact(count, 3L) <= MAX_ARRAY_LENGTH) { "Appearance arrays exceed checked JVM extents" }
        return count
    }

    fun preflight(input: RgbImage, protection: ProtectionMask?) {
        pixels(input.width, input.height)
        require(protection == null || (protection.width == input.width && protection.height == input.height)) { "Protection must match the appearance raster" }
        // Check the caller-owned leases without cloning either input. This also validates a closed mask before an exact-off return.
        input.getUnsigned(0, 0, 0)
        protection?.sample(0, 0)
    }

    fun retain(input: RgbImage, checkpoint: () -> Unit): RgbImage = deliver(input.retain(), checkpoint)

    fun output(input: RgbImage, pixels: ByteArray, checkpoint: () -> Unit): RgbImage = deliver(RgbImage(input.width, input.height, input.width * 3, pixels), checkpoint)

    private fun deliver(output: RgbImage, checkpoint: () -> Unit): RgbImage {
        try {
            checkpoint()
            return output
        } catch (failure: Throwable) {
            output.close()
            throw failure
        }
    }
}
