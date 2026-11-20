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
import java.io.OutputStream
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
        val hold = budget.reserve(charge(sizes))
        val bitmaps = ArrayList<Bitmap>(sizes.size)
        var opened: PdfDocument? = null
        try {
            val points = sizes.map(::points)
            val doc = PdfDocument().also { opened = it }
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            for (index in sizes.indices) {
                checkpoint()
                val raster = bitmap(index).also(bitmaps::add)
                require(raster.width == sizes[index].width && raster.height == sizes[index].height)
                val size = points[index]
                val page = doc.startPage(PdfDocument.PageInfo.Builder(size.width, size.height, index + 1).create())
                try {
                    page.canvas.drawColor(Color.WHITE)
                    page.canvas.drawBitmap(raster, null, placement(size, raster), paint)
                } finally { doc.finishPage(page) }
            }
            return object : NamedAlbumExports.PreparedArtifact {
                override fun write(sink: OutputStream, checkpoint: () -> Unit) {
                    checkpoint()
                    doc.writeTo(sink)
                    checkpoint()
                }
                override fun verify(closed: File, checkpoint: () -> Unit) =
                    verifyPdf(closed, points, bitmaps, checkpoint)

                override fun close() {
                    try { doc.close() } finally { try { bitmaps.forEach(Bitmap::recycle) } finally { hold.close() } }
                }
            }
        } catch (failure: Throwable) {
            try { opened?.close() } finally { try { bitmaps.forEach(Bitmap::recycle) } finally { hold.close() } }
            throw failure
        }
    }

    private fun verifyPdf(file: File, points: List<PixelSize>, originals: List<Bitmap>, checkpoint: () -> Unit) {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val reader = try { PdfRenderer(descriptor) } catch (failure: Throwable) { descriptor.close(); throw failure }
        reader.use {
            require(it.pageCount == points.size && file.length() > 0)
            for ((index, size) in points.withIndex()) {
                checkpoint()
                it.openPage(index).use { page ->
                    require(page.width == size.width && page.height == size.height)
                    val sampleSize = sampledSize(size)
                    val w = sampleSize.width
                    val h = sampleSize.height
                    val transform = Matrix().apply { setScale(w.toFloat() / size.width, h.toFloat() / size.height) }
                    val sample = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val actual = IntArray(w * h)
                    val expected = IntArray(w * h)
                    try {
                        page.render(sample, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        sample.getPixels(actual, 0, w, 0, 0, w, h)
                        checkpoint()
                        // Render the matching retained input independently of the PDF container and decoder, with identical physical placement.
                        sample.eraseColor(Color.WHITE)
                        val canvas = Canvas(sample)
                        canvas.concat(transform)
                        val original = originals[index]
                        canvas.drawBitmap(original, null, placement(size, original), Paint(Paint.FILTER_BITMAP_FLAG))
                        sample.getPixels(expected, 0, w, 0, 0, w, h)
                        if (!PdfPageEvidence.matches(expected, actual)) refuse(OperationErrorCode.INTEGRITY_FAILED, "export_pdf_page_pixels")
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

        internal fun charge(sizes: List<PixelSize>): Long {
            if (sizes.size !in 1..12 || sizes.any { maxOf(it.width, it.height) > 1440 }) {
                refuse(OperationErrorCode.RESOURCE_LIMIT, "export_pdf_envelope")
            }
            val pixels = sizes.sumOf { it.width.toLong() * it.height }
            if (pixels > 2_097_152L) refuse(OperationErrorCode.RESOURCE_LIMIT, "export_pdf_envelope")
            return 16L * pixels + 4L * 1024 * 1024
        }

        private fun points(size: PixelSize) = PixelSize(floor(size.width / 2.0 + .5).toInt(), floor(size.height / 2.0 + .5).toInt())
    }
}
