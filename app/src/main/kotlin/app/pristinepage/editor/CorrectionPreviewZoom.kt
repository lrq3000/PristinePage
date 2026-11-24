package app.pristinepage.editor

/** Display-only transform. Pan is expressed in screen pixels relative to the center of the fitted raster. */
internal data class CorrectionPreviewZoom(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
    fun transform(factor: Float, dx: Float, dy: Float, width: Int, height: Int, imageWidth: Int, imageHeight: Int): CorrectionPreviewZoom {
        if (!factor.isFinite() || factor <= 0f || !dx.isFinite() || !dy.isFinite() ||
            width <= 0 || height <= 0 || imageWidth <= 0 || imageHeight <= 0) return this
        val next = (scale * factor).coerceIn(1f, MAX_SCALE)
        if (next == 1f) return CorrectionPreviewZoom()
        val fit = minOf(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val maxX = ((imageWidth * fit * next - width) / 2).coerceAtLeast(0f)
        val maxY = ((imageHeight * fit * next - height) / 2).coerceAtLeast(0f)
        return CorrectionPreviewZoom(next, (x + dx).coerceIn(-maxX, maxX), (y + dy).coerceIn(-maxY, maxY))
    }

    companion object {
        const val MAX_SCALE = 5f
    }
}
