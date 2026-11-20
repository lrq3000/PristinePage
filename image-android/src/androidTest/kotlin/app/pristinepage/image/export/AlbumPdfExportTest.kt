package app.pristinepage.image.export

import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.image.decode.ImageAllocationBudget
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlbumPdfExportTest {
    @Test fun candidatePlatformDocumentIsReadableInTheRequestedMixedAspectOrder() = runBlocking {
        val scratch = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val result = File.createTempFile("album-test-", ".pdf", scratch)
        val budget = ImageAllocationBudget(64L * 1024 * 1024)
        val pages = listOf(PixelSize(120, 80), PixelSize(60, 100))
        val writer = AlbumPdf(budget)
        try {
            writer.buildCandidate(pages, {}) { index ->
                val size = pages[index]
                Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(if (index == 0) Color.RED else Color.BLUE)
                }
            }.use { prepared ->
                result.outputStream().use { prepared.write(it) {} }
                prepared.verify(result) {}
            }
            PdfRenderer(ParcelFileDescriptor.open(result, ParcelFileDescriptor.MODE_READ_ONLY)).use { reader ->
                assertEquals(2, reader.pageCount)
                for (index in pages.indices) reader.openPage(index).use { page ->
                    assertEquals(if (index == 0) 60 else 30, page.width)
                    assertEquals(if (index == 0) 40 else 50, page.height)
                    val pixel = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                    try {
                        page.render(pixel, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val rgb = pixel.getPixel(4, 4)
                        assertTrue(if (index == 0) Color.red(rgb) > Color.blue(rgb) else Color.blue(rgb) > Color.red(rgb))
                    } finally { pixel.recycle() }
                }
            }
            assertEquals(0L, budget.usedBytes)
        } finally { result.delete() }
    }
}
