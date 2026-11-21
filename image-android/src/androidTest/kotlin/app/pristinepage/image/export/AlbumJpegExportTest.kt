package app.pristinepage.image.export

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.core.image.RgbImage
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.image.decode.ImageAllocationBudget
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlbumJpegExportTest {
    @Test fun platformWriterProducesAReadableMetadataFreeJpegAndWrongPixelsRefuse() {
        val budget = ImageAllocationBudget(32L * 1024 * 1024)
        val encoder = AlbumJpeg(budget)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("rendered-", ".jpg", ctx.cacheDir)
        val settings = AlbumJpegQuality.BALANCED.settings
        try {
            RgbImage(80, 64, 240, ByteArray(80 * 64 * 3) { if (it / 3 % 80 < 37) 45 else (-56).toByte() }).use { rendered ->
                file.outputStream().use { encoder.write(rendered, it, settings) {} }
                encoder.verify(file, rendered, settings) {}
                RgbImage(80, 64, 240, ByteArray(80 * 64 * 3) { 0 }).use { wrong ->
                    assertThrows(RepositoryFailure::class.java) { encoder.verify(file, wrong, settings) {} }
                }
            }
            assertEquals(0L, budget.usedBytes)
        } finally { file.delete() }
    }

    @Test fun allThreePresetsWriteReadableJpegsOfTheSameDimensionsAndChangeCompression() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val encoder = AlbumJpeg(ImageAllocationBudget(32L * 1024 * 1024))
        val file = File.createTempFile("jpeg-presets-", ".jpg", context.cacheDir)
        try {
            val random = java.util.Random(25094)
            val luminance = ByteArray(160 * 128) { (48 + random.nextInt(160)).toByte() }
            RgbImage(160, 128, 480, ByteArray(160 * 128 * 3) { luminance[it / 3] }).use { rendered ->
                val sizes = AlbumJpegQuality.entries.map { preset ->
                    file.outputStream().use { encoder.write(rendered, it, preset.settings) {} }
                    encoder.verify(file, rendered, preset.settings) {}
                    file.length()
                }
                assertTrue("Quantization must affect actual output on the same raster", sizes.zipWithNext().all { (a, b) -> a > b })
            }
        } finally { file.delete() }
    }
}
