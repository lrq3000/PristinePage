package app.pristinepage.core.geometry

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.PointPx
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

object Coordinates {
    @JvmStatic fun gridToSource(value: Double, sourceSize: Int): Double {
        require(value.isFinite() && sourceSize >= 2)
        return (value + 1.0) * (sourceSize - 1) / 2.0
    }

    @JvmStatic fun halfPixelSource(destinationCenter: Double, sourceSize: Int, destinationSize: Int): Double {
        require(destinationCenter.isFinite() && sourceSize > 0 && destinationSize > 0)
        return (destinationCenter + 0.5) * sourceSize / destinationSize - 0.5
    }

    @JvmStatic fun finalToBase(u: Double, v: Double, quarterTurns: Int): PointPx {
        require(u.isFinite() && v.isFinite() && u in 0.0..1.0 && v in 0.0..1.0 && quarterTurns in 0..3)
        return when (quarterTurns) {
            0 -> PointPx(u, v)
            1 -> PointPx(v, 1.0 - u)
            2 -> PointPx(1.0 - u, 1.0 - v)
            else -> PointPx(1.0 - v, u)
        }
    }

    fun rotatedSize(base: PixelSize, quarterTurns: Int): PixelSize {
        require(quarterTurns in 0..3)
        return if (quarterTurns % 2 == 1) PixelSize(base.height, base.width) else base
    }

    fun limitLongEdge(target: PixelSize, limit: Int, geometric: Boolean): PixelSize {
        require(limit > 0)
        val scale = min(1.0, limit.toDouble() / max(target.width, target.height))
        val w = max(1, floor(target.width * scale).toInt())
        val h = max(1, floor(target.height * scale).toInt())
        require(!geometric || (w >= 2 && h >= 2)) { "Geometric output has a singleton axis" }
        return PixelSize(w, h)
    }
}
