package app.pristinepage.image.export

import app.pristinepage.data.model.JpegChromaPolicy
import app.pristinepage.data.model.JpegSampling
import app.pristinepage.data.model.JpegSettings

/** Named album export exposes compression, not arbitrary encoder parameters or a change of dimensions. */
enum class AlbumJpegQuality(quality: Int) {
    MAXIMUM(100), BALANCED(92), SMALLER_FILE(80);

    // Version 2 normalizes an optional encoder-generated ICC marker before verifying the staged JPEG.
    val settings = JpegSettings(quality, "android_bitmap", 2, JpegChromaPolicy.PLATFORM_VERIFIED, JpegSampling.YUV420)

    internal companion object {
        fun supports(settings: JpegSettings?) = entries.any { it.settings == settings }
    }
}
