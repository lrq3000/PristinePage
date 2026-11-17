package app.pristinepage.image.preview

import android.graphics.Bitmap
import android.graphics.ColorSpace
import app.pristinepage.core.image.RgbImage
import app.pristinepage.image.decode.ImageAllocationBudget
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import java.util.concurrent.atomic.AtomicBoolean

/** Shared RGB-to-display packing and accounting for original and rendered previews. RGB is borrowed only during conversion; this owner retains no input image or input reservation. */
internal class SrgbPreviewBitmap private constructor(initial: Bitmap, private val permit: AutoCloseable) : AutoCloseable {
    @Volatile private var pixels: Bitmap? = initial
    private val closed = AtomicBoolean(false)
    val bitmap: Bitmap get() = checkNotNull(pixels) { "Preview display lease is closed" }

    override fun close() = release(recycle = false)

    /** Only a privately completed bitmap that has never reached its caller may be recycled explicitly. */
    fun discardUndelivered() = release(recycle = true)

    private fun release(recycle: Boolean) {
        if (closed.compareAndSet(false, true)) {
            val owned = pixels
            // An issued Compose/draw reference can survive disposal by a frame. Drop application ownership without invalidating delivered references; Android GC owns residual Java/graphics retention.
            pixels = null
            try { if (recycle) owned?.recycle() } finally { permit.close() }
        }
    }

    companion object {
        fun convert(image: RgbImage, budget: ImageAllocationBudget, checkpoint: () -> Unit): SrgbPreviewBitmap {
            try {
            checkpoint()
            // Bitmap4N + provisional draw/upload copy4N + one ARGB row4W, in addition to the caller's still-live RGB accounting. Checked Long arithmetic precedes every output allocation. This is not a measured bound on graphics/PSS or GC latency.
            val pixels = Math.multiplyExact(image.width.toLong(), image.height.toLong())
            val bytes = Math.addExact(Math.multiplyExact(8L, pixels), Math.multiplyExact(4L, image.width.toLong()))
            val permit = budget.reserve(bytes)
            var bitmap: Bitmap? = null
            var transferred = false
            try {
                checkpoint()
                bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888, false, ColorSpace.get(ColorSpace.Named.SRGB))
                bitmap.density = Bitmap.DENSITY_NONE
                val row = IntArray(image.width)
                for (y in 0 until image.height) {
                    checkpoint()
                    // Read active RGB samples, not stride padding, and widen signed storage bytes through getUnsigned before packing opaque ARGB.
                    for (x in row.indices) row[x] = (255 shl 24) or (image.getUnsigned(x, y, 0) shl 16) or (image.getUnsigned(x, y, 1) shl 8) or image.getUnsigned(x, y, 2)
                    bitmap.setPixels(row, 0, image.width, 0, y, image.width, 1)
                }
                checkpoint()
                return SrgbPreviewBitmap(bitmap, permit).also { transferred = true }
            } finally {
                if (!transferred) { try { bitmap?.recycle() } finally { permit.close() } }
            }
            } catch (_: OutOfMemoryError) {
                // Logical admission cannot guarantee Java/native/graphics availability. The inner finally relinquishes only this unpublished conversion; both original and rendered routes receive one controlled resource refusal. Other VM Errors are not swallowed.
                throw RepositoryFailure(OperationError(OperationErrorCode.RESOURCE_LIMIT, true, "preview_display_allocation_failed", null))
            }
        }
    }
}
