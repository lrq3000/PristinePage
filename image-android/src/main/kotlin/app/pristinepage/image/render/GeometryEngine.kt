package app.pristinepage.image.render

import app.pristinepage.core.geometry.BilinearSampler
import app.pristinepage.core.geometry.Coordinates
import app.pristinepage.core.geometry.CubicSamplingAxes
import app.pristinepage.core.geometry.GeometrySizing
import app.pristinepage.core.geometry.GeometryWorkLimitException
import app.pristinepage.core.geometry.Homography
import app.pristinepage.core.geometry.InvalidHomographyException
import app.pristinepage.core.geometry.InvalidInverseMapException
import app.pristinepage.core.geometry.InverseMapValidator
import app.pristinepage.core.geometry.PerspectiveValidation
import app.pristinepage.core.geometry.ManualCurve
import app.pristinepage.core.geometry.ManualMapValidation
import app.pristinepage.core.geometry.ManualMapValidator
import app.pristinepage.core.geometry.GeometryProofBudget
import app.pristinepage.core.geometry.InvalidManualCurveException
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
import java.util.concurrent.CancellationException

/** Complete destination planes share one accounting lifetime. Inputs and their separately admitted source leases remain caller-owned. */
class GeometryRaster internal constructor(val image: RgbImage, val protection: ProtectionMask?, val coverage: CoverageMask?, val stage: StageRecord, private val reservation: AutoCloseable, val perspectiveValidation: PerspectiveValidation? = null, val manualValidation: ManualMapValidation? = null) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { image.close(); protection?.close(); coverage?.close() } finally { reservation.close() }
        }
    }
}

/** Synchronous off-UI-thread primitive. NONE and exact full-frame permutations bypass lossy solves. Other perspective and direct manual maps require their actual-map admission proofs before RGB/mask output allocation; unavailable learned maps still refuse explicitly. */
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
        validateOutput(target, output.size, recipe !is GeometryRecipe.None)
        val q = recipe.outputQuarterTurns
        val (base, identitySize, manualStraight, manualDirect, controls, exactPermutation, needsPerspectiveProof, charge, cubicDirect) =
            plan(recipe, output.size, protection != null, coverage != null)
        val n = output.size.pixelCount
        val reservation = budget.reserve(charge)
        var image: RgbImage? = null
        var transformedProtection: ProtectionMask? = null
        var transformedCoverage: CoverageMask? = null
        var transferred = false
        try {
            var validation: PerspectiveValidation? = null
            var manualValidation: ManualMapValidation? = null
            val manualAxes = if (manualDirect) {
                try {
                    val candidate = ManualCurve.prepare(recipe as GeometryRecipe.ManualCurve, output.size, checkpoint)
                    candidate.requireSource(binding)
                    val admitted = ManualMapValidator.analyze(candidate, GeometryProofBudget(), checkpoint)
                    admitted.requireCandidate(candidate)
                    manualValidation = admitted
                    ManualSamplingAxes(candidate, admitted, checkpoint)
                } catch (_: InvalidManualCurveException) { refuse(OperationErrorCode.INVALID_MAP, "invalid_manual_preparation") }
                catch (_: InvalidInverseMapException) { refuse(OperationErrorCode.INVALID_MAP, "invalid_manual_map") }
                catch (_: GeometryWorkLimitException) { refuse(OperationErrorCode.RESOURCE_LIMIT, "manual_validation_work_limit") }
                catch (failure: CancellationException) { throw failure }
            } else null
            val cubicAxes = if (cubicDirect) try {
                when (recipe) {
                    is GeometryRecipe.Bezier -> CubicSamplingAxes.prepare(recipe, base, checkpoint)
                    is GeometryRecipe.ManualCurveFourSides -> CubicSamplingAxes.prepare(recipe, base, checkpoint)
                    else -> error("Invalid cubic dispatch")
                }
            } catch (_: IllegalArgumentException) { refuse(OperationErrorCode.INVALID_MAP, "invalid_cubic_map") }
            else null
            val map = if (needsPerspectiveProof) {
                try {
                    val controls = checkNotNull(controls)
                    fun point(p: PointPx) = PointPx(p.x * (source.width - 1), p.y * (source.height - 1))
                    val quad = QuadPx(point(controls.tl), point(controls.tr), point(controls.br), point(controls.bl))
                    Homography.solve(quad, sourceSize, base).also { validation = InverseMapValidator.perspective(it, checkpoint) }
                } catch (_: InvalidHomographyException) { refuse(OperationErrorCode.INVALID_MAP, "invalid_perspective_solution") }
                catch (_: InvalidInverseMapException) { refuse(OperationErrorCode.INVALID_MAP, "invalid_perspective_map") }
                catch (_: GeometryWorkLimitException) { refuse(OperationErrorCode.RESOURCE_LIMIT, "perspective_validation_work_limit") }
                catch (failure: CancellationException) { throw failure }
            } else null
            checkpoint()
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
                    val mapped = try {
                        cubicAxes?.map(bx, by) ?: manualAxes?.map(bx, by) ?: map?.map(bx.toDouble(), by.toDouble())
                    } catch (_: InvalidInverseMapException) { refuse(OperationErrorCode.INVALID_MAP, "invalid_cubic_sample") }
                    val sx = mapped?.x ?: if (identitySize) bx.toDouble() else Coordinates.halfPixelSource(bx.toDouble(), source.width, base.width)
                    val sy = mapped?.y ?: if (identitySize) by.toDouble() else Coordinates.halfPixelSource(by.toDouble(), source.height, base.height)
                    for (c in 0..2) rgb[3 * index + c] = when {
                        exactPermutation -> source.getUnsigned(bx, by, c)
                        mapped != null -> BilinearSampler.rgb(source, sx, sy, c)
                        else -> BilinearSampler.resizedRgb(source, sx, sy, c)
                    }.toByte()
                    if (mask != null) mask[index] = if (exactPermutation) protection!!.sample(bx, by) else BilinearSampler.protection(protection!!, sx, sy)
                    if (page != null) page[index] = if (exactPermutation) coverage!!.sample(bx, by) else BilinearSampler.coverage(coverage!!, sx, sy)
                }
            }
            image = RgbImage(output.size.width, output.size.height, output.size.width * 3, rgb)
            if (mask != null) transformedProtection = ProtectionMask(output.size.width, output.size.height, mask)
            if (page != null) transformedCoverage = CoverageMask(output.size.width, output.size.height, page)
            checkpoint()
            val stageId = when (recipe) {
                is GeometryRecipe.Bezier -> "atelier_bezier_v1"
                is GeometryRecipe.ManualCurve -> "manual_ruled_v1"
                is GeometryRecipe.ManualCurveFourSides -> "manual_four_sides_v1"
                is GeometryRecipe.Perspective -> "perspective_v1"
                else -> if (!identitySize) "source_resize_half_pixel_v1" else "geometry_none_v1"
            }
            val diagnostics = listOf("OUTPUT_QUARTER_TURNS_$q") + (if (validation != null || manualValidation != null || cubicAxes != null) listOf("GEOMETRY_VISIBILITY_V1") else emptyList()) + (if (manualStraight) listOf("MANUAL_STRAIGHT_PERSPECTIVE") else emptyList()) + (if (manualValidation != null) listOf("MANUAL_COMPOSITE_V1") else emptyList())
            val stage = StageRecord(stageId, 1, null, if (exactPermutation) "EXACT_PERMUTATION" else "OK", sourceSize, output.size, diagnostics)
            return GeometryRaster(image, transformedProtection, transformedCoverage, stage, reservation, validation, manualValidation).also { transferred = true }
        } finally {
            if (!transferred) {
                try { image?.close(); transformedProtection?.close(); transformedCoverage?.close() } finally { reservation.close() }
            }
        }
    }

    private data class Plan(
        val base: PixelSize, val identitySize: Boolean, val manualStraight: Boolean, val manualDirect: Boolean,
        val controls: QuadPx?, val exactPermutation: Boolean, val needsPerspectiveProof: Boolean, val charge: Long, val cubicDirect: Boolean,
    )

    companion object {
        /** The ordinary coordinator supplies neither protection nor coverage. The shared plan chooses the execution branch but does not prepare, solve or validate a map. */
        internal fun ordinaryCharge(recipe: GeometryRecipe, size: PixelSize): Long = plan(recipe, size, false, false).charge

        private fun plan(recipe: GeometryRecipe, size: PixelSize, protection: Boolean, coverage: Boolean): Plan {
            val base = Coordinates.rotatedSize(size, recipe.outputQuarterTurns)
            val identitySize = base == recipe.source.sourceSize
            if (recipe is GeometryRecipe.RoughCropDewarp) {
                refuse(OperationErrorCode.PROVIDER_UNAVAILABLE, "geometry_map_not_implemented")
            }
            // Compare SERIALIZED normalized chord midpoints exactly, as preparation does. A near-straight manual curve must not disappear under a source-resolution-dependent tolerance.
            val legacyManualStraight = recipe is GeometryRecipe.ManualCurve && recipe.depth == 0.0 && recipe.topMidpoint == midpoint(recipe.quad.tl, recipe.quad.tr) && recipe.bottomMidpoint == midpoint(recipe.quad.bl, recipe.quad.br)
            val manualStraight = legacyManualStraight || (recipe is GeometryRecipe.ManualCurveFourSides && recipe.straight)
            val manualDirect = recipe is GeometryRecipe.ManualCurve && !legacyManualStraight
            val cubicDirect = (recipe is GeometryRecipe.Bezier && !recipe.contour.straight) ||
                (recipe is GeometryRecipe.ManualCurveFourSides && !recipe.straight)
            val controls = when (recipe) {
                is GeometryRecipe.Perspective -> recipe.quad
                is GeometryRecipe.ManualCurve -> recipe.quad
                is GeometryRecipe.ManualCurveFourSides -> recipe.quad
                is GeometryRecipe.Bezier -> recipe.contour.quad
                else -> null
            }
            val projective = recipe is GeometryRecipe.Perspective || manualStraight || (recipe is GeometryRecipe.Bezier && !cubicDirect)
            val exactPermutation = identitySize && (recipe is GeometryRecipe.None || (projective && fullFrame(checkNotNull(controls))))
            val needsPerspectiveProof = projective && !exactPermutation
            val n = size.pixelCount
            if (n > Int.MAX_VALUE / 3L || size.width > Int.MAX_VALUE / 3) refuse(OperationErrorCode.RESOURCE_LIMIT, "geometry_output_extent")
            // Scratch plus immutable constructor copies: RGB6N, each float mask8N, and fixed metadata/object headroom. The caller continues charging the full source and source masks independently.
            val proofScratch = if (cubicDirect) CubicSamplingAxes.workspaceBytes(base) else if (manualDirect) ManualCurve.workspaceBytes() + ManualMapValidator.workspaceBytes() + ManualSamplingAxes.workspaceBytes(base) else if (needsPerspectiveProof) InverseMapValidator.perspectiveWorkspaceBytes() else 0L
            val charge = 65536L + 6L * n + (if (protection) 8L * n else 0L) + (if (coverage) 8L * n else 0L) + proofScratch
            return Plan(base, identitySize, manualStraight, manualDirect, controls, exactPermutation, needsPerspectiveProof, charge, cubicDirect)
        }

        private fun fullFrame(quad: QuadPx): Boolean = quad == QuadPx(PointPx(0.0, 0.0), PointPx(1.0, 0.0), PointPx(1.0, 1.0), PointPx(0.0, 1.0))
        private fun midpoint(a: PointPx, b: PointPx) = PointPx((a.x + b.x) / 2.0, (a.y + b.y) / 2.0)

        /** Shared before coordinator decode and again at primitive entry: one predicate avoids disagreement about common-floor aspect/no-upscale/singleton policy. It does not admit source pixels or bypass map proof. */
        internal fun validateOutput(target: PixelSize, output: PixelSize, geometric: Boolean) {
            if (!admissibleSize(target, output, geometric)) refuse(OperationErrorCode.INVALID_INPUT, "invalid_geometry_output")
        }

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
}
