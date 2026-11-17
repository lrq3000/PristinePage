package app.pristinepage.core.image

/** cie_lab_float_v1 from PHOTOMETRIC_ALGORITHMS §4.1: fixed Double matrices/transcendentals with Float chromatic axes and L255 storage, not Lab8 or encoded-byte luma. */
internal object ColorLab {
    private const val D = 6.0 / 29.0

    fun forward(red: Int, green: Int, blue: Int, output: FloatArray) {
        val r = decode(red); val g = decode(green); val b = decode(blue)
        val x = .4124564 * r + .3575761 * g + .1804375 * b
        val y = .2126729 * r + .7151522 * g + .0721750 * b
        val z = .0193339 * r + .1191920 * g + .9503041 * b
        val fx = curve(x / .95047); val fy = curve(y); val fz = curve(z / 1.08883)
        output[0] = (2.55 * (116 * fy - 16)).coerceIn(0.0, 255.0).toFloat()
        output[1] = (500 * (fx - fy)).toFloat()
        output[2] = (200 * (fy - fz)).toFloat()
    }

    /** Packs the already clipped/rounded byte candidate so the caller can blend its three channels without a per-pixel allocation. */
    fun inverse(lightness: Float, aStar: Float, bStar: Float): Int {
        require(lightness.isFinite() && lightness in 0f..255f && aStar.isFinite() && bStar.isFinite())
        val fy = (lightness.toDouble() / 2.55 + 16) / 116
        val fx = fy + aStar.toDouble() / 500
        val fz = fy - bStar.toDouble() / 200
        val x = .95047 * inverseCurve(fx); val y = inverseCurve(fy); val z = 1.08883 * inverseCurve(fz)
        val red = encode(3.2404542 * x - 1.5371385 * y - .4985314 * z)
        val green = encode(-.9692660 * x + 1.8760108 * y + .0415560 * z)
        val blue = encode(.0556434 * x - .2040259 * y + 1.0572252 * z)
        return (red shl 16) or (green shl 8) or blue
    }

    private fun decode(sample: Int): Double {
        val q = sample.toDouble() / 255
        return if (q <= .04045) q / 12.92 else StrictMath.pow((q + .055) / 1.055, 2.4)
    }

    private fun curve(t: Double): Double = if (t > D * D * D) StrictMath.cbrt(t) else t / (3 * D * D) + 4.0 / 29
    private fun inverseCurve(t: Double): Double = if (t > D) t * t * t else 3 * D * D * (t - 4.0 / 29)

    private fun encode(linear: Double): Int {
        val q = linear.coerceIn(0.0, 1.0)
        val encoded = if (q <= .0031308) 12.92 * q else 1.055 * StrictMath.pow(q, 1.0 / 2.4) - .055
        return Numeric.roundByte(255 * encoded)
    }
}
