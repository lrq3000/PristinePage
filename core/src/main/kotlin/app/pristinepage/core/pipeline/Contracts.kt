package app.pristinepage.core.pipeline

import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.recipe.AppearanceRecipe
import app.pristinepage.core.recipe.BinaryRecipe
import app.pristinepage.core.recipe.DenoiseRecipe
import app.pristinepage.core.recipe.DetailRecipe
import app.pristinepage.core.recipe.GeometryRecipe
import kotlinx.serialization.Serializable

@Serializable data class PixelSize(val width: Int, val height: Int) {
    init { require(width > 0 && height > 0) { "Pixel dimensions must be positive" } }
    val pixelCount: Long get() = width.toLong() * height
}
@Serializable data class PointPx(val x: Double, val y: Double) { init { require(x.isFinite() && y.isFinite()) } }
@Serializable data class QuadPx(val tl: PointPx, val tr: PointPx, val br: PointPx, val bl: PointPx) {
    fun points(): List<PointPx> = listOf(tl, tr, br, bl)
}
@Serializable data class RectPx(val x: Int, val y: Int, val width: Int, val height: Int) {
    init { require(x >= 0 && y >= 0 && width > 0 && height > 0 && x.toLong() + width <= Int.MAX_VALUE && y.toLong() + height <= Int.MAX_VALUE) }
}
@Serializable enum class GeometryMode { NONE, PERSPECTIVE, MANUAL_CURVE, ROUGH_CROP_DEWARP }
@Serializable enum class RenderPurpose { CARD, PREVIEW, DETAIL_CROP, EXPORT }
@Serializable enum class FallbackPolicy { FAIL, KEEP_STAGE_INPUT }
@Serializable data class OutputSpec(val size: PixelSize, val purpose: RenderPurpose)
@Serializable data class ProviderIdentity(val id: String, val contractVersion: Int, val assetSha256: String?, val runtime: String, val precision: String) {
    init { require(id.isNotBlank() && contractVersion > 0 && runtime.isNotBlank() && precision.isNotBlank()); listOf(id, runtime, precision).forEach(::requireWellFormedUnicode); assetSha256?.let(::requireSha256) }
}

@Serializable data class Recipe(
    val schemaVersion: Int = 1,
    val geometry: GeometryRecipe,
    val appearance: AppearanceRecipe = AppearanceRecipe.NoneV1,
    val denoise: DenoiseRecipe? = null,
    val detail: DetailRecipe? = null,
    val binary: BinaryRecipe? = null,
) {
    init { require(schemaVersion == 1); require(!(detail != null && binary is BinaryRecipe.SauvolaV1)) { "Detail and Sauvola cannot be combined" } }
}

data class RenderRequest(val sourceId: String, val sourcePixelHash: String, val pageId: String, val recipeRevision: Long, val recipe: Recipe, val output: OutputSpec, val generation: Long, val protectionMaskId: String?, val fallback: FallbackPolicy)
enum class RenderStatus { OK, DEGRADED, INVALID_INPUT, UNAVAILABLE, INVALID_MAP, PROCESSING_FAILED, CANCELED, RESOURCE_LIMIT, STORAGE_FAILED }
data class StageRecord(val stageId: String, val version: Int, val provider: ProviderIdentity?, val outcome: String, val inputSize: PixelSize, val outputSize: PixelSize, val warningCodes: List<String>)
data class RenderResult(val request: RenderRequest, val status: RenderStatus, val image: RgbImage?, val stages: List<StageRecord>, val warnings: List<String>)
interface RenderCoordinator { suspend fun render(request: RenderRequest): RenderResult }
data class DetectionProposal(val quad: QuadPx?, val score: Double?, val detected: Boolean, val reason: String?)

fun requireSha256(hash: String) { require(hash.matches(Regex("[0-9a-f]{64}"))) { "Expected lowercase SHA-256" } }

/** UTF-8 identity must never replace an unpaired UTF-16 surrogate with a question mark. Valid supplementary code points remain supported. */
fun requireWellFormedUnicode(value: String) {
    var i = 0
    while (i < value.length) {
        val c = value[i]
        when {
            Character.isHighSurrogate(c) -> { require(i + 1 < value.length && Character.isLowSurrogate(value[i + 1])) { "Unpaired Unicode surrogate" }; i += 2 }
            Character.isLowSurrogate(c) -> throw IllegalArgumentException("Unpaired Unicode surrogate")
            else -> i++
        }
    }
}
