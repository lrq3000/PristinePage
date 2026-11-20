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
        try {
            RgbImage(80, 64, 240, ByteArray(80 * 64 * 3) { if (it / 3 % 80 < 37) 45 else (-56).toByte() }).use { rendered ->
                file.outputStream().use { encoder.write(rendered, it, AlbumJpeg.DEFAULT) {} }
                encoder.verify(file, rendered, AlbumJpeg.DEFAULT) {}
                RgbImage(80, 64, 240, ByteArray(80 * 64 * 3) { 0 }).use { wrong ->
                    assertThrows(RepositoryFailure::class.java) { encoder.verify(file, wrong, AlbumJpeg.DEFAULT) {} }
                }
            }
            assertEquals(0L, budget.usedBytes)
        } finally { file.delete() }
    }
}
