package app.pristinepage.image.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.NamedAlbumExports
import app.pristinepage.image.decode.ImageAllocationBudget
import java.io.File
import kotlin.math.floor

/** PdfDocument retains completed page recordings. A configured scratch estimate is not a measured writer envelope. */
internal class AlbumPdf(private val budget: ImageAllocationBudget) {
    suspend fun prepare(sizes: List<PixelSize>, settings: PdfSettings, checkpoint: () -> Unit,
        bitmap: suspend (Int) -> Bitmap): NamedAlbumExports.PreparedArtifact {
        check(sizes, settings)
        return buildCandidate(sizes, checkpoint, bitmap)
    }

    fun check(sizes: List<PixelSize>, settings: PdfSettings) {
        if (settings != DEFAULT) refuse(OperationErrorCode.UNSUPPORTED_FORMAT, "export_pdf_profile_invalid")
        val needed = charge(sizes)
        if (needed > budget.capacityBytes - budget.usedBytes) refuse(OperationErrorCode.RESOURCE_LIMIT, "export_pdf_budget")
        // No measured OS/backend profile is registered. Refuse before even a synthetic probe can allocate.
        refuse(OperationErrorCode.RESOURCE_LIMIT, "export_pdf_profile_unavailable")
    }

    /** Internal candidate for collecting isolated codec/measurement evidence; it does not issue a frozen export or ready token. */
    internal suspend fun buildCandidate(sizes: List<PixelSize>, checkpoint: () -> Unit,
        bitmap: suspend (Int) -> Bitmap): NamedAlbumExports.PreparedArtifact {
        val cost = cost(sizes)
        val bitmaps = ArrayList<Bitmap>(sizes.size)
        val evidence = ArrayList<PageSample>(sizes.size)
        val evidenceHold = budget.reserve(cost.readback)
        try {
            val writerHold = budget.reserve(cost.writer)
            var opened: PdfDocument? = null
            fun releaseWriter() {
                try {
                    opened?.close()
                    // A failed close does not prove the native recordings released their references.
                    bitmaps.forEach(Bitmap::recycle)
                } finally {
                    opened = null
                    bitmaps.clear()
                    writerHold.close()
                }
            }
            try {
                val points = sizes.map(::points)
                val doc = PdfDocument().also { opened = it }
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                for (index in sizes.indices) {
                    checkpoint()
                    val raster = bitmap(index).also(bitmaps::add)
                    require(raster.width == sizes[index].width && raster.height == sizes[index].height)
                    val size = points[index]
                    val rect = placement(size, raster)
                    evidence += sampleSource(size, raster, rect, paint)
                    val page = doc.startPage(PdfDocument.PageInfo.Builder(size.width, size.height, index + 1).create())
                    try {
                        page.canvas.drawColor(Color.WHITE)
                        page.canvas.drawBitmap(raster, null, rect, paint)
                    } finally { doc.finishPage(page) }
                }
                return PlatformPdfArtifact(
                    writer = AutoCloseable(::releaseWriter), evidence = AutoCloseable { evidence.clear(); evidenceHold.close() },
                    write = { sink, _ -> doc.writeTo(sink) },
                    readback = { closed, active -> verifyPdf(closed, evidence, active) },
                )
            } catch (failure: Throwable) {
                AutoCloseable(::releaseWriter).use { throw failure }
            }
        } catch (failure: Throwable) {
            evidenceHold.close()
            throw failure
        }
    }

    private data class PageSample(val points: PixelSize, val size: PixelSize, val expected: IntArray) {
        fun transform() = Matrix().apply { setScale(size.width.toFloat() / points.width, size.height.toFloat() / points.height) }
    }

    private fun sampleSource(points: PixelSize, raster: Bitmap, rect: RectF, paint: Paint): PageSample {
        val size = sampledSize(points)
        val w = size.width
        val h = size.height
        val expected = IntArray(w * h)
        val sample = PageSample(points, size, expected)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            Canvas(bitmap).apply { concat(sample.transform()); drawBitmap(raster, null, rect, paint) }
            bitmap.getPixels(expected, 0, w, 0, 0, w, h)
        } finally { bitmap.recycle() }
        return sample
    }

    private fun verifyPdf(file: File, samples: List<PageSample>, checkpoint: () -> Unit) {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val reader = try { PdfRenderer(descriptor) } catch (failure: Throwable) { descriptor.close(); throw failure }
        reader.use {
            require(it.pageCount == samples.size && file.length() > 0)
            for ((index, evidence) in samples.withIndex()) {
                checkpoint()
                it.openPage(index).use { page ->
                    require(page.width == evidence.points.width && page.height == evidence.points.height)
                    val w = evidence.size.width
                    val h = evidence.size.height
                    val actual = IntArray(w * h)
                    val sample = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    try {
                        page.render(sample, null, evidence.transform(), PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        sample.getPixels(actual, 0, w, 0, 0, w, h)
                        checkpoint()
                        if (!PdfPageEvidence.matches(evidence.expected, actual)) refuse(OperationErrorCode.INTEGRITY_FAILED, "export_pdf_page_pixels")
                    } finally { sample.recycle() }
                }
            }
        }
    }

    private fun placement(points: PixelSize, raster: Bitmap): RectF {
        val scale = minOf(points.width.toFloat() / raster.width, points.height.toFloat() / raster.height)
        val w = raster.width * scale
        val h = raster.height * scale
        val x = (points.width - w) / 2
        val y = (points.height - h) / 2
        return RectF(x, y, x + w, y + h)
    }

    private fun sampledSize(points: PixelSize): PixelSize {
        val edge = maxOf(points.width, points.height)
        val cap = minOf(edge, 128)
        return PixelSize(maxOf(1, points.width * cap / edge), maxOf(1, points.height * cap / edge))
    }

    companion object {
        val DEFAULT = PdfSettings(PdfPageRule.IMAGE_ASPECT, 144.0, PdfMarginRule.NONE, 0.0, "android_pdfdocument", 1, 1)

        internal fun charge(sizes: List<PixelSize>): Long = cost(sizes).let { it.writer + it.readback }

        private data class Cost(val writer: Long, val readback: Long)

        private fun cost(sizes: List<PixelSize>): Cost {
            if (sizes.size !in 1..12 || sizes.any { maxOf(it.width, it.height) > 1440 }) {
                refuse(OperationErrorCode.RESOURCE_LIMIT, "export_pdf_envelope")
            }
            val pixels = sizes.sumOf { it.width.toLong() * it.height }
            if (pixels > 2_097_152L) refuse(OperationErrorCode.RESOURCE_LIMIT, "export_pdf_envelope")
            // Charge writer/recordings until native close, plus samples and a conservative current-page readback allowance. Neither coefficient is a measured device bound.
            return Cost(16L * pixels + 4L * 1024 * 1024, 8L * sizes.maxOf { it.pixelCount } + 4L * 1024 * 1024)
        }

        private fun points(size: PixelSize) = PixelSize(floor(size.width / 2.0 + .5).toInt(), floor(size.height / 2.0 + .5).toInt())
    }
}
