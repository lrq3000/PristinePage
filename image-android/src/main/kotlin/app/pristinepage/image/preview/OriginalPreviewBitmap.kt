package app.pristinepage.image.preview

import android.graphics.Bitmap
import app.pristinepage.core.pipeline.OutputSpec
import app.pristinepage.data.model.SourceInspection

/** A display-owner lease, not saved state or durable output. The bitmap is borrowed while open. Close releases application ownership/accounting; it must never recycle a published bitmap still reachable from a Compose/draw reference. Graphics/PSS and GC latency remain separately measured device gates. */
interface OriginalPreviewBitmap : AutoCloseable {
    val sourceId: String
    val sourceByteHash: String
    val inspection: SourceInspection
    val output: OutputSpec
    val bitmap: Bitmap
}
