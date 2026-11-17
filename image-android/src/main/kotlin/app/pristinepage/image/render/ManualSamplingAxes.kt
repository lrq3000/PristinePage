package app.pristinepage.image.render

import app.pristinepage.core.geometry.ManualCurve
import app.pristinepage.core.geometry.ManualMapValidation
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.PointPx

/** O(Wg log128+Hg) setup and O(1) source-coordinate lookup per pixel. This is NOT a map fit: the four arrays evaluate the certified candidate's exact stored LUTs, and the final blend retains its operation order. It is not used by the renderer until its complete proof gate succeeds. */
internal class ManualSamplingAxes(candidate: ManualCurve, validation: ManualMapValidation, checkpoint: () -> Unit) {
    private val topX: DoubleArray
    private val topY: DoubleArray
    private val bottomX: DoubleArray
    private val bottomY: DoubleArray
    private val weights: DoubleArray
    init {
        checkpoint()
        validation.requireCandidate(candidate)
        val size = candidate.baseOutputSize
        topX = DoubleArray(size.width); topY = DoubleArray(size.width)
        bottomX = DoubleArray(size.width); bottomY = DoubleArray(size.width)
        weights = DoubleArray(size.height)
        val top = candidate.topBoundary!!; val bottom = candidate.bottomBoundary!!
        for (x in 0 until size.width) {
            checkpoint()
            val u = x.toDouble() / (size.width - 1)
            val a = top.pointAtArc(u); val b = bottom.pointAtArc(u)
            topX[x] = a.x; topY[x] = a.y; bottomX[x] = b.x; bottomY[x] = b.y
        }
        for (y in 0 until size.height) {
            checkpoint()
            val v = y.toDouble() / (size.height - 1)
            weights[y] = v + candidate.depth * v * (1.0 - v)
        }
        checkpoint()
    }

    /** Integer UNROTATED base indices; the caller composes its final quarter-turn exactly before lookup, including varying weights across a final row for odd turns. */
    fun map(x: Int, y: Int): PointPx {
        val w = weights[y]
        return PointPx((1.0 - w) * topX[x] + w * bottomX[x], (1.0 - w) * topY[x] + w * bottomY[x])
    }

    companion object {
        fun workspaceBytes(base: PixelSize): Long = Math.addExact(32L * base.width, 8L * base.height)
    }
}
