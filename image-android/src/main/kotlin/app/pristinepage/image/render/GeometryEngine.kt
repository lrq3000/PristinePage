package app.pristinepage.image.render

import app.pristinepage.core.geometry.BilinearSampler
import app.pristinepage.core.geometry.Coordinates
import app.pristinepage.core.geometry.GeometrySizing
import app.pristinepage.core.image.CoverageMask
import app.pristinepage.core.image.ProtectionMask
import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.image.decode.ImageAllocationBudget
import java.util.concurrent.atomic.AtomicBoolean

/** Complete destination planes share one accounting lifetime. Inputs and their separately admitted source leases remain caller-owned. */
class GeometryRaster internal constructor(val image: RgbImage, val protection: ProtectionMask?, val coverage: CoverageMask?, val stage: StageRecord, private val reservation: AutoCloseable) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { image.close(); protection?.close(); coverage?.close() } finally { reservation.close() }
        }
    }
}

/** Synchronous off-UI-thread primitive. The initial admitted paths are NONE and exact full-frame permutations; unimplemented maps refuse explicitly rather than sampling a substitute. */
class GeometryEngine(private val budget: ImageAllocationBudget) {
    fun render(source: RgbImage, binding: SourceBinding, recipe: GeometryRecipe, output: OutputSpec, protection: ProtectionMask?, coverage: CoverageMask?, checkpoint: () -> Unit): GeometryRaster {
        checkpoint()
        val sourceSize = PixelSize(source.width, source.height)
        val target = try {
            require(binding == recipe.source && binding.sourceSize == sourceSize)
            require(protection == null || (protection.width == source.width && protection.height == source.height))
            require(coverage == null || (coverage.width == source.width && coverage.height == source.height))
            source.getUnsigned(0, 0, 0); protection?.sample(0, 0); coverage?.sample(0, 0)
            GeometrySizing.target(recipe)
        } catch (_: IllegalArgumentException) { refuse(OperationErrorCode.INVALID_INPUT, "invalid_geometry_binding") }
        catch (_: IllegalStateException) { refuse(OperationErrorCode.INVALID_INPUT, "closed_geometry_input") }
        val geometric = recipe !is GeometryRecipe.None
        if (!admissibleSize(target, output.size, geometric)) refuse(OperationErrorCode.INVALID_INPUT, "invalid_geometry_output")
        val q = recipe.outputQuarterTurns
        val base = Coordinates.rotatedSize(output.size, q)
        val identitySize = base == sourceSize
        if (recipe !is GeometryRecipe.None && !(recipe is GeometryRecipe.Perspective && fullFrame(recipe) && identitySize)) {
            refuse(OperationErrorCode.PROVIDER_UNAVAILABLE, "geometry_map_not_implemented")
        }
        val n = output.size.pixelCount
        if (n > Int.MAX_VALUE / 3L || output.size.width > Int.MAX_VALUE / 3) refuse(OperationErrorCode.RESOURCE_LIMIT, "geometry_output_extent")
        // Scratch plus immutable constructor copies: RGB6N, each float mask8N, and fixed metadata/object headroom. The caller continues charging the full source and source masks independently.
        val charge = 65536L + 6L * n + (if (protection != null) 8L * n else 0L) + (if (coverage != null) 8L * n else 0L)
        val reservation = budget.reserve(charge)
        var image: RgbImage? = null
        var transformedProtection: ProtectionMask? = null
        var transformedCoverage: CoverageMask? = null
        var transferred = false
        try {
            val rgb = ByteArray((3L * n).toInt())
            val mask = if (protection != null) FloatArray(n.toInt()) else null
            val page = if (coverage != null) FloatArray(n.toInt()) else null
            for (y in 0 until output.size.height) {
                checkpoint()
                for (x in 0 until output.size.width) {
                    val bx: Int
                    val by: Int
                    when (q) {
                        0 -> { bx = x; by = y }
                        1 -> { bx = y; by = base.height - 1 - x }
                        2 -> { bx = base.width - 1 - x; by = base.height - 1 - y }
                        else -> { bx = base.width - 1 - y; by = x }
                    }
                    val index = y * output.size.width + x
                    val sx = if (identitySize) bx.toDouble() else Coordinates.halfPixelSource(bx.toDouble(), source.width, base.width)
                    val sy = if (identitySize) by.toDouble() else Coordinates.halfPixelSource(by.toDouble(), source.height, base.height)
                    for (c in 0..2) rgb[3 * index + c] = (if (identitySize) source.getUnsigned(bx, by, c) else BilinearSampler.resizedRgb(source, sx, sy, c)).toByte()
                    if (mask != null) mask[index] = if (identitySize) protection!!.sample(bx, by) else BilinearSampler.protection(protection!!, sx, sy)
                    if (page != null) page[index] = if (identitySize) coverage!!.sample(bx, by) else BilinearSampler.coverage(coverage!!, sx, sy)
                }
            }
            image = RgbImage(output.size.width, output.size.height, output.size.width * 3, rgb)
            if (mask != null) transformedProtection = ProtectionMask(output.size.width, output.size.height, mask)
            if (page != null) transformedCoverage = CoverageMask(output.size.width, output.size.height, page)
            checkpoint()
            val stageId = if (!identitySize) "source_resize_half_pixel_v1" else if (recipe is GeometryRecipe.None) "geometry_none_v1" else "perspective_v1"
            val stage = StageRecord(stageId, 1, null, if (identitySize) "EXACT_PERMUTATION" else "OK", sourceSize, output.size, listOf("OUTPUT_QUARTER_TURNS_$q"))
            return GeometryRaster(image, transformedProtection, transformedCoverage, stage, reservation).also { transferred = true }
        } finally {
            if (!transferred) {
                try { image?.close(); transformedProtection?.close(); transformedCoverage?.close() } finally { reservation.close() }
            }
        }
    }

    private fun fullFrame(recipe: GeometryRecipe.Perspective): Boolean = recipe.quad == QuadPx(PointPx(0.0, 0.0), PointPx(1.0, 0.0), PointPx(1.0, 1.0), PointPx(0.0, 1.0))

    /** An explicit destination must arise from the common floored scale, including NONE's max(1,...) singleton rule; a near aspect ratio alone does not authorize stretching. */
    private fun admissibleSize(target: PixelSize, output: PixelSize, geometric: Boolean): Boolean {
        if (output.width > target.width || output.height > target.height || (geometric && (output.width < 2 || output.height < 2))) return false
        if (output == target) return true
        fun lower(value: Int, original: Int) = if (!geometric && value == 1) 0.0 else value.toDouble() / original
        val low = maxOf(lower(output.width, target.width), lower(output.height, target.height))
        val high = minOf(1.0, (output.width.toDouble() + 1.0) / target.width, (output.height.toDouble() + 1.0) / target.height)
        return low < high
    }

    private fun refuse(code: OperationErrorCode, key: String): Nothing = throw RepositoryFailure(OperationError(code, false, key, null))
}
