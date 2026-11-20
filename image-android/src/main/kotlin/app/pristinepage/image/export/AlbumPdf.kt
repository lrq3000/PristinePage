package app.pristinepage.image.export

import android.graphics.Bitmap
import android.graphics.Color
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
                    val scale = minOf(size.width.toFloat() / raster.width, size.height.toFloat() / raster.height)
                    val width = scale * raster.width
                    val height = scale * raster.height
                    val x = (size.width - width) / 2
                    val y = (size.height - height) / 2
                    page.canvas.drawBitmap(raster, null, RectF(x, y, x + width, y + height), paint)
                } finally { doc.finishPage(page) }
            }
            return object : NamedAlbumExports.PreparedArtifact {
                override fun write(sink: OutputStream, checkpoint: () -> Unit) {
                    checkpoint()
                    doc.writeTo(sink)
                    checkpoint()
                }
                override fun verify(closed: File, checkpoint: () -> Unit) =
                    verifyPdf(closed, points, checkpoint)

                override fun close() {
                    try { doc.close() } finally { try { bitmaps.forEach(Bitmap::recycle) } finally { hold.close() } }
                }
            }
        } catch (failure: Throwable) {
            try { opened?.close() } finally { try { bitmaps.forEach(Bitmap::recycle) } finally { hold.close() } }
            throw failure
        }
    }

    private fun verifyPdf(file: File, points: List<PixelSize>, checkpoint: () -> Unit) {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val reader = try { PdfRenderer(descriptor) } catch (failure: Throwable) { descriptor.close(); throw failure }
        reader.use {
            require(it.pageCount == points.size && file.length() > 0)
            for ((index, size) in points.withIndex()) {
                checkpoint()
                it.openPage(index).use { page ->
                    require(page.width == size.width && page.height == size.height)
                    val sample = Bitmap.createBitmap(minOf(128, size.width), minOf(128, size.height), Bitmap.Config.ARGB_8888)
                    try { page.render(sample, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                    finally { sample.recycle() }
                }
            }
        }
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
