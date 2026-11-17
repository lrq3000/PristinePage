package app.pristinepage.image.preview

import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.OutputSpec
import app.pristinepage.data.model.SourceInspection
import kotlinx.coroutines.Dispatchers

/** Owned aspect-preserving display raster from the verified oriented original, never a recipe result, exported image or new source identity. The image is borrowed until this entire result closes; retaining pixels requires an explicit shared-budget ownership transfer. */
interface OriginalPreviewRaster : AutoCloseable {
    val sourceId: String
    val sourceByteHash: String
    val inspection: SourceInspection
    val output: OutputSpec
    val image: RgbImage
}

/** One bounded CPU dispatcher for raster and display conversion, not one pool per requested preview. Native decode keeps its existing IO ownership/admission. */
internal object PreviewProcessing { val dispatcher = Dispatchers.Default.limitedParallelism(2) }
