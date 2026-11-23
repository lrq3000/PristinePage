package app.pristinepage.core.geometry

import app.pristinepage.core.pipeline.OutputSpec
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.PointPx
import app.pristinepage.core.pipeline.QuadPx
import app.pristinepage.core.pipeline.RenderPurpose
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.TargetRule
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max

/** Resolves only deterministic raster dimensions. Source-identity matching, provider execution and resource admission remain the consuming renderer's responsibilities. */
object GeometrySizing {
    // OCR has its own qualification target. Do not reduce the selected preview/detail/export targets.
    const val OCR_LONG_EDGE = 2400

    @JvmStatic fun baseTarget(recipe: GeometryRecipe): PixelSize = when (recipe) {
        is GeometryRecipe.None -> recipe.source.sourceSize
        is GeometryRecipe.Perspective -> fit(naturalTarget(recipe.quad, recipe.source.sourceSize), recipe.targetRule)
        is GeometryRecipe.ManualCurve -> fit(naturalTarget(recipe.quad, recipe.source.sourceSize), recipe.targetRule)
        is GeometryRecipe.ManualCurveFourSides -> fit(naturalTarget(recipe.quad, recipe.source.sourceSize), recipe.targetRule)
        is GeometryRecipe.Bezier -> fit(naturalTarget(recipe.contour.quad, recipe.source.sourceSize), recipe.targetRule)
        is GeometryRecipe.RoughCropDewarp -> fit(PixelSize(recipe.crop.width, recipe.crop.height), recipe.targetRule)
    }

    @JvmStatic fun target(recipe: GeometryRecipe): PixelSize = Coordinates.rotatedSize(baseTarget(recipe), recipe.outputQuarterTurns)

    /** One exact common scale; a floating-point product just below an integer can produce a pair the renderer rightly rejects. */
    @JvmStatic fun ocr(recipe: GeometryRecipe): OutputSpec {
        val size = target(recipe)
        val edge = maxOf(size.width, size.height)
        val limit = minOf(edge, OCR_LONG_EDGE).toLong()
        fun axis(value: Int) = (limit * value / edge).coerceAtLeast(1L).toInt()
        val output = PixelSize(axis(size.width), axis(size.height))
        require(recipe is GeometryRecipe.None || (output.width >= 2 && output.height >= 2)) { "Geometric OCR input has a singleton axis" }
        return OutputSpec(output, RenderPurpose.OCR)
    }

    /** The caller supplies a purpose-specific L explicitly, including EXPORT; no preview/export default or emergency shrink is inferred here. */
    @JvmStatic fun output(recipe: GeometryRecipe, purpose: RenderPurpose, longEdge: Int): OutputSpec {
        require(longEdge > 0) { "Purpose long edge must be positive" }
        return OutputSpec(Coordinates.limitLongEdge(target(recipe), longEdge, recipe !is GeometryRecipe.None), purpose)
    }

    private fun naturalTarget(normalized: QuadPx, source: PixelSize): PixelSize {
        // Controls are source-center fractions, not edge fractions. Denormalize once before validation/measurement; curve handles and arc lengths never alter chord-based target sizing.
        val quad = QuadPx(denormalize(normalized.tl, source), denormalize(normalized.tr, source), denormalize(normalized.br, source), denormalize(normalized.bl, source))
        QuadValidation.validate(quad, source)
        val width = max(distance(quad.tl, quad.tr), distance(quad.bl, quad.br))
        val height = max(distance(quad.tl, quad.bl), distance(quad.tr, quad.br))
        // A center-to-center extent of 400 spans 401 centers, not 400. Check in Double before narrowing so a diagonal cannot silently saturate an Int.
        return PixelSize(dimension(ceil(width) + 1.0), dimension(ceil(height) + 1.0))
    }

    private fun denormalize(point: PointPx, source: PixelSize): PointPx = PointPx(point.x * (source.width - 1).toDouble(), point.y * (source.height - 1).toDouble())
    private fun distance(a: PointPx, b: PointPx): Double = hypot(b.x - a.x, b.y - a.y)

    private fun fit(base: PixelSize, rule: TargetRule): PixelSize {
        require(base.width >= 2 && base.height >= 2) { "Geometric target axes must be at least two" }
        if (rule !is TargetRule.FixedRatio) return base
        val ratio = rule.widthOverHeight
        val size = if (base.width.toDouble() / base.height >= ratio) {
            PixelSize(dimension(floor(ratio * base.height)), base.height)
        } else {
            PixelSize(base.width, dimension(floor(base.width / ratio)))
        }
        require(size.width <= base.width && size.height <= base.height) { "A fixed ratio must fit without upscaling" }
        return size
    }

    private fun dimension(value: Double): Int {
        require(value.isFinite() && value >= 2.0 && value <= Int.MAX_VALUE.toDouble()) { "Geometric target dimension is unsupported" }
        return value.toInt()
    }
}
