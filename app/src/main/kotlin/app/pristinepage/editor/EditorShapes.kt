package app.pristinepage.editor

import app.pristinepage.core.pipeline.PointPx
import app.pristinepage.core.pipeline.QuadPx
import app.pristinepage.core.recipe.GeometryRecipe

internal enum class ShapePoint {
    TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT, TOP_MIDPOINT, BOTTOM_MIDPOINT;

    fun replaceIn(quad: QuadPx, point: PointPx): QuadPx = when (this) {
        TOP_LEFT -> quad.copy(tl = point)
        TOP_RIGHT -> quad.copy(tr = point)
        BOTTOM_RIGHT -> quad.copy(br = point)
        BOTTOM_LEFT -> quad.copy(bl = point)
        TOP_MIDPOINT, BOTTOM_MIDPOINT -> quad
    }
}

/** The editor changes source-space controls only. Sizing, numerical admission, and raster output remain the renderer's responsibility. */
internal object EditorShapes {
    private val wholeImage = QuadPx(PointPx(0.0, 0.0), PointPx(1.0, 0.0), PointPx(1.0, 1.0), PointPx(0.0, 1.0))

    fun reset(geometry: GeometryRecipe) = GeometryRecipe.None(geometry.source, outputQuarterTurns = geometry.outputQuarterTurns)

    fun canAdjust(geometry: GeometryRecipe) = geometry !is GeometryRecipe.RoughCropDewarp &&
        geometry.source.sourceSize.width >= 2 && geometry.source.sourceSize.height >= 2

    fun perspective(geometry: GeometryRecipe): GeometryRecipe = when (geometry) {
        is GeometryRecipe.None -> if (canAdjust(geometry)) GeometryRecipe.Perspective(
            geometry.source, wholeImage, outputQuarterTurns = geometry.outputQuarterTurns,
        ) else geometry
        is GeometryRecipe.ManualCurve -> geometry.cornersOnly()
        else -> geometry
    }

    fun manualCurve(geometry: GeometryRecipe): GeometryRecipe =
        (if (geometry is GeometryRecipe.None) perspective(geometry) else geometry).let {
            if (it is GeometryRecipe.Perspective) straightCurve(it) else it
        }

    private fun GeometryRecipe.ManualCurve.cornersOnly() =
        GeometryRecipe.Perspective(source, quad, targetRule, outputQuarterTurns = outputQuarterTurns)

    private fun straightCurve(geometry: GeometryRecipe.Perspective) = GeometryRecipe.ManualCurve(
        geometry.source, geometry.quad, targetRule = geometry.targetRule, outputQuarterTurns = geometry.outputQuarterTurns,
    )

    private fun QuadPx.labeledCorners() = mapOf(
        ShapePoint.TOP_LEFT to tl, ShapePoint.TOP_RIGHT to tr, ShapePoint.BOTTOM_RIGHT to br, ShapePoint.BOTTOM_LEFT to bl,
    )

    fun points(geometry: GeometryRecipe): Map<ShapePoint, PointPx> = when (geometry) {
        is GeometryRecipe.Perspective -> geometry.quad.labeledCorners()
        is GeometryRecipe.ManualCurve -> geometry.quad.labeledCorners() + mapOf(
            ShapePoint.TOP_MIDPOINT to geometry.topMidpoint, ShapePoint.BOTTOM_MIDPOINT to geometry.bottomMidpoint,
        )
        else -> emptyMap()
    }

    fun setPoint(geometry: GeometryRecipe, label: ShapePoint, point: PointPx): GeometryRecipe = when (geometry) {
        is GeometryRecipe.Perspective -> geometry.copy(quad = label.replaceIn(geometry.quad, point))
        is GeometryRecipe.ManualCurve -> when (label) {
            ShapePoint.TOP_MIDPOINT -> geometry.copy(topMidpoint = point)
            ShapePoint.BOTTOM_MIDPOINT -> geometry.copy(bottomMidpoint = point)
            else -> {
                val corners = geometry.cornersOnly()
                val straight = straightCurve(corners)
                val moved = straightCurve(corners.copy(quad = label.replaceIn(corners.quad, point)))
                // Leave explicit adjustments in source space; only an exactly straight midpoint follows its corner.
                moved.copy(
                    topMidpoint = if (geometry.topMidpoint == straight.topMidpoint) moved.topMidpoint else geometry.topMidpoint,
                    bottomMidpoint = if (geometry.bottomMidpoint == straight.bottomMidpoint) moved.bottomMidpoint else geometry.bottomMidpoint,
                    depth = geometry.depth,
                )
            }
        }
        else -> geometry
    }
}
