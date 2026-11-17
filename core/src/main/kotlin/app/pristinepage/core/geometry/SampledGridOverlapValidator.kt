package app.pristinepage.core.geometry

import app.pristinepage.core.pipeline.PointPx
import java.math.BigDecimal
import java.util.Arrays
import kotlin.math.abs

/** Regression diagnostic on a fixed sampled grid, NOT a continuous-map injectivity proof. Only the composite validator may combine this with the local and true-boundary certificates. */
class SampledGridOverlapValidation private constructor(private val candidate: ManualCurve, val candidatePairs: Int, val scheduledBoxes: Int, val processedBoxes: Int) {
    val columns: Int get() = 33
    val rows: Int get() = 33
    val cells: Int get() = 1024
    val sampledGridOverlapChecked: Boolean get() = true
    val injectivityProven: Boolean get() = false
    fun requireCandidate(actual: ManualCurve) { require(actual === candidate) { "Sampled diagnostic belongs to a different candidate" } }
    companion object {
        internal fun analyze(candidate: ManualCurve, budget: GeometryProofBudget, maximumPairs: Int, checkpoint: () -> Unit): SampledGridOverlapValidation {
            checkpoint()
            require(candidate.dispatch == ManualCurve.Dispatch.DIRECT_RULED_CANDIDATE)
            require(maximumPairs in 1..1_000_000)
            // Charge all fixed cell/index construction before sampling/allocating nodes. Query-node visits are additionally scheduled before each enqueue; all stages share the same cumulative allowance.
            budget.schedule(3071) // 1024 cells +2047 balanced binary index nodes.
            val points = Array(33 * 33) { PointPx(0.0, 0.0) }
            for (y in 0..32) {
                checkpoint()
                for (x in 0..32) points[y * 33 + x] = candidate.mapBaseUnit(x / 32.0, y / 32.0)
            }
            val pairs = checkGrid(points, 33, 33, budget, maximumPairs, checkpoint)
            checkpoint()
            val counts = budget.snapshot()
            return SampledGridOverlapValidation(candidate, pairs, counts.scheduledBoxes, counts.processedBoxes)
        }
    }
}

object SampledGridOverlapValidator {
    /** Constant fixed-grid/index/exact-predicate live scratch, not source/output storage, cumulative allocation churn or measured heap/PSS. */
    @JvmStatic fun workspaceBytes(): Long = 4L * 1024 * 1024
    @JvmStatic fun analyze(candidate: ManualCurve, budget: GeometryProofBudget, maximumPairs: Int, checkpoint: () -> Unit): SampledGridOverlapValidation = SampledGridOverlapValidation.analyze(candidate, budget, maximumPairs, checkpoint)
}

/** Private deterministic-grid port also used by primitive negative fixtures. No arbitrary raw grid can construct a public manual certificate. */
private fun analyzeGrid(points: Array<PointPx>, width: Int, height: Int, budget: GeometryProofBudget, maximumPairs: Int, checkpoint: () -> Unit): Int {
    require(width in 2..33 && height in 2..33 && points.size == width * height && maximumPairs in 1..1_000_000)
    val cells = (width - 1) * (height - 1)
    budget.schedule(3 * cells - 1)
    return checkGrid(points, width, height, budget, maximumPairs, checkpoint)
}

private fun checkGrid(points: Array<PointPx>, width: Int, height: Int, budget: GeometryProofBudget, maximumPairs: Int, checkpoint: () -> Unit): Int {
    val exact = Array(points.size) { GridPoint(points[it]) }
    val columns = width - 1
    val cells = Array(columns * (height - 1)) { id ->
        checkpoint(); budget.processed()
        val x = id % columns; val y = id / columns; val top = y * width + x
        GridCell(id, x, y, arrayOf(exact[top], exact[top + 1], exact[top + width + 1], exact[top + width]))
    }
    val tree = GridNode.build(cells.copyOf(), 0, cells.size, budget, checkpoint)
    val stack = arrayOfNulls<GridNode>(12)
    var pairs = 0
    for (cell in cells) {
        checkpoint()
        budget.schedule(1)
        stack[0] = tree; var pending = 1
        while (pending > 0) {
            checkpoint()
            val node = stack[--pending]!!; stack[pending] = null; budget.processed()
            if (node.maximumId <= cell.id || !node.overlaps(cell)) continue
            val other = node.cell
            if (other != null) {
                // Each pair reaches exactly one leaf with increasing IDs. Count before exact-predicate work, including prescribed neighbours, and never skip a pair when the cap is exhausted.
                if (pairs == maximumPairs) throw GeometryWorkLimitException("Sampled grid candidate-pair allowance exhausted")
                pairs++
                if (!cell.allowedContact(other)) throw InvalidInverseMapException("Sampled grid cells overlap or touch outside prescribed adjacency")
            } else {
                budget.schedule(2)
                stack[pending++] = node.right!!; stack[pending++] = node.left!!
            }
        }
    }
    checkpoint()
    return pairs
}

/** Exact predicates on stored sampled Double nodes. A rounded orientation close to zero is never guessed positive; exact zero is a collapsed/touching condition, not an epsilon-expanded pass. */
private class GridPoint(point: PointPx) {
    val x = BigDecimal(point.x); val y = BigDecimal(point.y)
    val rawX = point.x; val rawY = point.y
    fun projection(dx: BigDecimal, dy: BigDecimal): BigDecimal = x.multiply(dx).add(y.multiply(dy))
    companion object {
        fun cross(a: GridPoint, b: GridPoint, c: GridPoint): BigDecimal = b.x.subtract(a.x).multiply(c.y.subtract(a.y)).subtract(b.y.subtract(a.y).multiply(c.x.subtract(a.x)))
    }
}

private class GridCell(val id: Int, val x: Int, val y: Int, val p: Array<GridPoint>) {
    val minX = p.minOf { it.rawX }; val maxX = p.maxOf { it.rawX }
    val minY = p.minOf { it.rawY }; val maxY = p.maxOf { it.rawY }
    init {
        // These four turns are the four bilinear corner Jacobian signs in cyclic p00,p10,p11,p01 order, not the centre or only two triangle areas.
        for (i in 0..3) if (GridPoint.cross(p[i], p[(i + 1) % 4], p[(i + 2) % 4]).signum() <= 0) throw InvalidInverseMapException("Nonpositive sampled cell corner Jacobian")
    }
    fun allowedContact(other: GridCell): Boolean {
        val neighbours = abs(x - other.x) <= 1 && abs(y - other.y) <= 1
        val common = if (neighbours) p.filter { a -> other.p.any { it === a } } else emptyList()
        for (polygon in arrayOf(p, other.p)) for (i in 0..3) {
            val a = polygon[i]; val b = polygon[(i + 1) % 4]
            val dx = b.y.subtract(a.y).negate(); val dy = b.x.subtract(a.x)
            val pa = p.map { it.projection(dx, dy) }; val pb = other.p.map { it.projection(dx, dy) }
            val aMin = pa.min(); val aMax = pa.max(); val bMin = pb.min(); val bMax = pb.max()
            if (aMax < bMin || bMax < aMin) return true
            val level = if (aMax.compareTo(bMin) == 0) aMax else if (bMax.compareTo(aMin) == 0) bMax else null
            if (level != null) {
                // Both convex polygons meet, if at all, on this separating line. Intersect their one-dimensional contact faces: diagonal neighbours can meet only at an endpoint even though NEITHER whole face is that endpoint. Membership in the permitted face comes from shared grid-node identity, never coordinate coincidence alone.
                val tx = dy.negate(); val ty = dx
                fun face(points: Array<GridPoint>, values: List<BigDecimal>): List<BigDecimal> = points.indices.filter { values[it].compareTo(level) == 0 }.map { points[it].projection(tx, ty) }
                val fa = face(p, pa); val fb = face(other.p, pb)
                val lo = maxOf(fa.min(), fb.min()); val hi = minOf(fa.max(), fb.max())
                if (lo > hi) return true
                if (common.isNotEmpty()) {
                    val allowed = common.map { it.projection(tx, ty) }
                    if (lo >= allowed.min() && hi <= allowed.max()) return true
                }
            }
        }
        return false
    }
}

/** Balanced static spatial index; median splits use the widest box axis. O(K log²K) bounded construction, O(K) live nodes, output-sensitive traversal on ordinary grids; pathological query work is capped by the shared budget before expansion. */
private class GridNode(val minX: Double, val maxX: Double, val minY: Double, val maxY: Double, val maximumId: Int, val cell: GridCell?, val left: GridNode?, val right: GridNode?) {
    fun overlaps(c: GridCell): Boolean = maxX >= c.minX && c.maxX >= minX && maxY >= c.minY && c.maxY >= minY
    companion object {
        fun build(cells: Array<GridCell>, from: Int, until: Int, budget: GeometryProofBudget, checkpoint: () -> Unit): GridNode {
            checkpoint(); budget.processed()
            if (until - from == 1) { val c = cells[from]; return GridNode(c.minX, c.maxX, c.minY, c.maxY, c.id, c, null, null) }
            var minX = Double.POSITIVE_INFINITY; var maxX = Double.NEGATIVE_INFINITY; var minY = minX; var maxY = maxX
            for (i in from until until) { val c = cells[i]; minX = minOf(minX, c.minX); maxX = maxOf(maxX, c.maxX); minY = minOf(minY, c.minY); maxY = maxOf(maxY, c.maxY) }
            val useX = maxX - minX >= maxY - minY
            Arrays.sort(cells, from, until, compareBy<GridCell> { if (useX) it.minX + it.maxX else it.minY + it.maxY }.thenBy { it.id })
            val middle = (from + until) ushr 1
            val left = build(cells, from, middle, budget, checkpoint); val right = build(cells, middle, until, budget, checkpoint)
            return GridNode(minX, maxX, minY, maxY, maxOf(left.maximumId, right.maximumId), null, left, right)
        }
    }
}
