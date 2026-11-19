package app.pristinepage.core.geometry

import app.pristinepage.core.pipeline.OutputSpec
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.RenderPurpose
import app.pristinepage.core.recipe.GeometryRecipe

/** Deterministic dimensions shown before export review. This selects no codec, verifies no map/source and reserves no memory; the later snapshot admission must still admit these exact dimensions without secretly shrinking them. */
object ExportSizing {
    @JvmStatic fun normal(recipe: GeometryRecipe): OutputSpec {
        val target = GeometrySizing.target(recipe)
        val longEdge = maxOf(target.width, target.height)
        val width = flooredAxis(target.width, target.height, longEdge)
        val height = flooredAxis(target.height, target.width, longEdge)
        val minimum = if (recipe is GeometryRecipe.None) 1 else 2
        require(width >= minimum && height >= minimum) { "Export aspect has no supported floored size" }
        return OutputSpec(PixelSize(width,height),RenderPurpose.EXPORT)
    }

    /** Separate explicit source-target choice, not a way around resource admission or permission to upscale a preview. */
    @JvmStatic fun sourceResolution(recipe: GeometryRecipe): OutputSpec = OutputSpec(GeometrySizing.target(recipe),RenderPurpose.EXPORT)

    private fun flooredAxis(axis: Int, otherAxis: Int, longEdge: Int): Int {
        // Flooring commutes with min, so first bound the axis by no-upscale and long-edge limits. Squaring the nonnegative area bound gives n² × otherAxis ≤ 12_000_000 × axis; n ≤ 4000 keeps all products in Long even for Int.MAX_VALUE targets.
        var low = 0L
        var high = minOf(axis.toLong(), 4000L * axis / longEdge)
        val areaBound = 12_000_000L * axis
        while (low < high) {
            val n = (low + high + 1) / 2
            if (n * n * otherAxis <= areaBound) low = n else high = n - 1
        }
        return low.toInt()
    }
}
