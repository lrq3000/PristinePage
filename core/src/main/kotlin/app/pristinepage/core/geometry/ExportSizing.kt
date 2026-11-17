package app.pristinepage.core.geometry

import app.pristinepage.core.pipeline.OutputSpec
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.RenderPurpose
import app.pristinepage.core.recipe.GeometryRecipe
import kotlin.math.floor
import kotlin.math.sqrt

/** Deterministic dimensions shown before export review. This selects no codec, verifies no map/source and reserves no memory; the later snapshot admission must still admit these exact dimensions without secretly shrinking them. */
object ExportSizing {
    @JvmStatic fun normal(recipe: GeometryRecipe): OutputSpec {
        // GeometrySizing already resolves the page ratio and output turns. One scale preserves that aspect under both normal-export caps, rather than reusing a PREVIEW or long-edge-only size.
        val target = GeometrySizing.target(recipe)
        val scale = minOf(1.0,4000.0 / maxOf(target.width,target.height),sqrt(12_000_000.0 / target.pixelCount))
        val width = floor(target.width * scale).toInt()
        val height = floor(target.height * scale).toInt()
        val minimum = if (recipe is GeometryRecipe.None) 1 else 2
        require(width >= minimum && height >= minimum) { "Export aspect has no supported floored size" }
        return OutputSpec(PixelSize(width,height),RenderPurpose.EXPORT)
    }

    /** Separate explicit source-target choice, not a way around resource admission or permission to upscale a preview. */
    @JvmStatic fun sourceResolution(recipe: GeometryRecipe): OutputSpec = OutputSpec(GeometrySizing.target(recipe),RenderPurpose.EXPORT)
}
