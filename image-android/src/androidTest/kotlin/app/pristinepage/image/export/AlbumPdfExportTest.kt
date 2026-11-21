package app.pristinepage.image.export

import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.image.decode.ImageAllocationBudget
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlbumPdfExportTest {
    @Test fun recordMemoryThroughRepeatedMixedAspectCandidateWritesAndReadback() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File.createTempFile("album-profile-", ".pdf", instrumentation.targetContext.cacheDir)
        val budget = ImageAllocationBudget(64L * 1024 * 1024)
        // Nearly the provisional aggregate ceiling; mixed aspect and distinct, incompressible page samples defeat trivial deduplication.
        val sizes = listOf(PixelSize(1024, 1024), PixelSize(768, 1360))
        val notes = StringBuilder("sdk=${Build.VERSION.SDK_INT} abi=${Build.SUPPORTED_ABIS.first()} pages=$sizes pixels=2093056\n")
        val started = SystemClock.elapsedRealtime()
        fun sample(phase: String) {
            val (peak, current) = File("/proc/self/status").useLines { lines ->
                val rss = lines.filter { it.startsWith("VmHWM:") || it.startsWith("VmRSS:") }.associateBy { it.substringBefore(':') }
                rss.getValue("VmHWM").substringAfter(':').trim() to rss.getValue("VmRSS").substringAfter(':').trim()
            }
            notes.append("$phase t=${SystemClock.elapsedRealtime() - started}ms peakRSS=$peak RSS=$current PSS=${Debug.getPss()}kB nativeHeap=${Debug.getNativeHeapAllocatedSize()}B tracked=${budget.usedBytes}B file=${file.length()}B\n")
        }
        try {
            sample("baseline")
            repeat(3) { cycle ->
                AlbumPdf(budget).buildCandidate(sizes, {}) { index ->
                    val size = sizes[index]
                    Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888, false).apply {
                        try {
                            val random = java.util.Random(211L + index + cycle * 17L)
                            val row = IntArray(size.width)
                            for (y in 0 until size.height) {
                                for (x in row.indices) row[x] = random.nextInt(0x1000000) or 0xff000000.toInt()
                                setPixels(row, 0, row.size, 0, y, row.size, 1)
                            }
                        } catch (failure: Throwable) { recycle(); throw failure }
                    }
                }.use { prepared ->
                    sample("$cycle prepared")
                    val beforeWrite = budget.usedBytes
                    file.outputStream().use { prepared.write(it) {} }
                    assertTrue(file.length() in 1..(32L * 1024 * 1024))
                    assertTrue("Only readback evidence remains reserved after the native writer closes", budget.usedBytes in 1 until beforeWrite)
                    sample("$cycle written")
                    prepared.verify(file) {}
                    sample("$cycle verified")
                }
                assertEquals(0L, budget.usedBytes)
                sample("$cycle closed")
            }
        } finally {
            // Keep telemetry out of JUnit's per-test completion status stream.
            try {
                instrumentation.addResults(Bundle().apply { putString("pristinepage_pdf_profile", notes.toString()) })
            } finally { file.delete() }
        }
    }

    @Test fun independentlyReadableSameSizePagesAreRejectedWhenReordered() = runBlocking {
        val scratch = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val budget = ImageAllocationBudget(64L * 1024 * 1024)
        val size = PixelSize(120, 80)
        val writer = AlbumPdf(budget)
        val file = File.createTempFile("album-swapped-", ".pdf", scratch)
        try {
            suspend fun book(colors: List<Int>) = writer.buildCandidate(listOf(size, size), {}) { i ->
                Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888).apply { eraseColor(colors[i]) }
            }
            book(listOf(Color.BLUE, Color.RED)).use { changed ->
                file.outputStream().use { changed.write(it) {} }
                changed.verify(file) {}
            }
            book(listOf(Color.RED, Color.BLUE)).use { expected ->
                val control = File.createTempFile("album-original-order-", ".pdf", scratch)
                try {
                    control.outputStream().use { expected.write(it) {} }
                    expected.verify(control) {}
                } finally { control.delete() }
                // With a valid write and closed writer already proven, only the supplied file differs.
                val failure = assertThrows(RepositoryFailure::class.java) { expected.verify(file) {} }
                assertEquals(OperationErrorCode.INTEGRITY_FAILED, failure.error.code)
                assertEquals("export_pdf_page_pixels", failure.error.safeMessageKey)
            }
            assertEquals(0L, budget.usedBytes)
        } finally { file.delete() }
    }

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
