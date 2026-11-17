package app.pristinepage.core.geometry

import app.pristinepage.core.pipeline.PointPx
import java.math.BigDecimal

/** A boundary-simplicity certificate tied to the SAME prepared candidate, not a local-stretch, sampled-overlap or final image-admission certificate. */
class ManualBoundaryValidation private constructor(private val candidate: ManualCurve, val boundaryPairs: Int, val boxes: Int, val scheduledBoxes: Int, val processedBoxes: Int, val maximumDepth: Int) {
    val globalBoundarySimplicityProven: Boolean get() = true
    val localStretchProven: Boolean get() = false
    val injectivityProven: Boolean get() = false
    val sampledGridOverlapChecked: Boolean get() = false
    fun requireCandidate(actual: ManualCurve) { require(actual === candidate) { "Boundary certificate belongs to a different prepared candidate" } }

    companion object {
        internal fun analyze(candidate: ManualCurve, budget: GeometryProofBudget, checkpoint: () -> Unit): ManualBoundaryValidation {
            checkpoint()
            require(candidate.dispatch == ManualCurve.Dispatch.DIRECT_RULED_CANDIDATE) { "Straight manual candidate uses the existing perspective proof" }
            val before = budget.snapshot()
            // Four self checks and six pair roots are scheduled BEFORE creating their exact-control geometry. Child schedules use this same operation budget, including any previous local proof.
            budget.schedule(10)
            val top = candidate.topBoundary!!; val bottom = candidate.bottomBoundary!!
            val tl = ExactPoint.of(top.start); val tr = ExactPoint.of(top.end)
            val bl = ExactPoint.of(bottom.start); val br = ExactPoint.of(bottom.end)
            val edges = arrayOf(ExactCurve.quadratic(tl, ExactPoint.of(top.control), tr), ExactCurve.line(tr, br), ExactCurve.quadratic(br, ExactPoint.of(bottom.control), bl), ExactCurve.line(bl, tl))
            for (edge in edges) {
                checkpoint(); budget.processed()
                edge.requireSimple()
            }
            val xScale = BigDecimal(candidate.source.sourceSize.width - 1)
            val yScale = BigDecimal(candidate.source.sourceSize.height - 1)
            val minimumBox = BigDecimal("0.000000001").multiply(xScale).multiply(yScale)
            var deepest = 0
            // Splitting ONE curve per node bounds a pair's depth by32+32 and its DFS frontier by65, not by a materialized Cartesian tree.
            val stack = arrayOfNulls<BoundaryPairBox>(65)
            var pairs = 0
            for (i in 0..3) for (j in i + 1..3) {
                pairs++
                val common = if (j == i + 1) edges[i].end else if (i == 0 && j == 3) edges[0].start else null
                var pending = 1
                stack[0] = BoundaryPairBox(edges[i], edges[j], common)
                while (pending > 0) {
                    checkpoint()
                    val box = stack[--pending]!!; stack[pending] = null
                    budget.processed()
                    deepest = maxOf(deepest, box.a.depth, box.b.depth)
                    if (box.certifiablySeparated()) continue
                    val sizeA = box.a.normalizedSizeNumerator(xScale, yScale)
                    val sizeB = box.b.normalizedSizeNumerator(xScale, yScale)
                    if (maxOf(sizeA, sizeB).compareTo(minimumBox) <= 0) invalidBoundary("Manual boundary intersection unresolved at normalized width1e-9")
                    val splitA = sizeA >= sizeB
                    val chosen = if (splitA) box.a else box.b
                    if (chosen.depth == 32) invalidBoundary("Manual boundary intersection unresolved at depth32")
                    budget.schedule(2)
                    val children = chosen.split()
                    if (splitA) {
                        stack[pending++] = BoundaryPairBox(children.second, box.b, common)
                        stack[pending++] = BoundaryPairBox(children.first, box.b, common)
                    } else {
                        stack[pending++] = BoundaryPairBox(box.a, children.second, common)
                        stack[pending++] = BoundaryPairBox(box.a, children.first, common)
                    }
                }
            }
            checkpoint()
            val after = budget.snapshot()
            return ManualBoundaryValidation(candidate, pairs, after.processedBoxes - before.processedBoxes, after.scheduledBoxes, after.processedBoxes, deepest)
        }
    }
}

/** The stored finite binary64 controls are exact dyadic rationals. BigDecimal(Double), unlimited-precision add/subtract/multiply and exact division by2 preserve those values and every de Casteljau child; valueOf(Double) or a MathContext would instead round the proof inputs. All decisions below are exact predicates on their convex hulls, not rounded scalar sample positions. */
object ManualBoundaryValidator {
    /** Conservative live numerical allowance for exact controls/products,65-entry DFS and temporary hull projections. Source/curve/local-proof/raster owners remain separate. Depth32 limits coordinate scale growth; this is not measured JVM/PSS or an allocation-churn bound. */
    @JvmStatic fun workspaceBytes(): Long = 8L * 1024 * 1024
    @JvmStatic fun analyze(candidate: ManualCurve, budget: GeometryProofBudget, checkpoint: () -> Unit): ManualBoundaryValidation = ManualBoundaryValidation.analyze(candidate, budget, checkpoint)
}

private fun invalidBoundary(message: String): Nothing = throw InvalidInverseMapException(message)

private class ExactPoint(val x: BigDecimal, val y: BigDecimal) {
    operator fun plus(p: ExactPoint) = ExactPoint(x.add(p.x), y.add(p.y))
    operator fun minus(p: ExactPoint) = ExactPoint(x.subtract(p.x), y.subtract(p.y))
    fun half() = ExactPoint(x.divide(TWO), y.divide(TWO))
    fun same(p: ExactPoint): Boolean = x.compareTo(p.x) == 0 && y.compareTo(p.y) == 0
    fun dot(p: ExactPoint): BigDecimal = x.multiply(p.x).add(y.multiply(p.y))
    fun cross(p: ExactPoint): BigDecimal = x.multiply(p.y).subtract(y.multiply(p.x))
    fun normal() = ExactPoint(y.negate(), x)
    fun zero(): Boolean = x.signum() == 0 && y.signum() == 0
    companion object {
        private val TWO = BigDecimal(2)
        fun of(p: PointPx) = ExactPoint(BigDecimal(p.x), BigDecimal(p.y))
    }
}

private class ExactCurve private constructor(val points: Array<ExactPoint>, val depth: Int, private val isLine: Boolean) {
    val start: ExactPoint get() = points.first()
    val end: ExactPoint get() = points.last()
    val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
    val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
    fun hasEndpoint(p: ExactPoint): Boolean = start.same(p) || end.same(p)

    fun requireSimple() {
        if (start.same(end)) invalidBoundary("Manual boundary edge collapses")
        if (isLine) return
        val first = points[1] - start
        val last = end - points[1]
        // Q(t1)=Q(t2),t1!=t2 requires the quadratic and linear power-basis vectors to be parallel. Thus a noncollinear quadratic is injective. A collinear quadratic is simple exactly when its derivative never reverses along its endpoint chord; zero derivative at a sole endpoint is allowed here and remains subject to local-J admission.
        if (first.cross(last).signum() != 0) return
        val chord = end - start
        if (first.dot(chord).signum() < 0 || last.dot(chord).signum() < 0) invalidBoundary("Manual collinear quadratic retraces its boundary")
    }

    fun normalizedSizeNumerator(xScale: BigDecimal, yScale: BigDecimal): BigDecimal = maxOf(maxX.subtract(minX).multiply(yScale), maxY.subtract(minY).multiply(xScale))

    fun split(): Pair<ExactCurve, ExactCurve> {
        if (isLine) {
            val mid = (start + end).half()
            return ExactCurve(arrayOf(start, mid), depth + 1, true) to ExactCurve(arrayOf(mid, end), depth + 1, true)
        }
        val a = (start + points[1]).half(); val b = (points[1] + end).half(); val mid = (a + b).half()
        return ExactCurve(arrayOf(start, a, mid), depth + 1, false) to ExactCurve(arrayOf(mid, b, end), depth + 1, false)
    }

    companion object {
        fun line(a: ExactPoint, b: ExactPoint) = ExactCurve(arrayOf(a, b), 0, true)
        fun quadratic(a: ExactPoint, b: ExactPoint, c: ExactPoint) = ExactCurve(arrayOf(a, b, c), 0, false)
    }
}

private class Projection(val minimum: BigDecimal, val maximum: BigDecimal, val values: Array<BigDecimal>)

private class BoundaryPairBox(val a: ExactCurve, val b: ExactCurve, private val common: ExactPoint?) {
    fun certifiablySeparated(): Boolean {
        if (a.maxX < b.minX || b.maxX < a.minX || a.maxY < b.minY || b.maxY < a.minY) return true
        val allowed = common?.takeIf { a.hasEndpoint(it) && b.hasEndpoint(it) }
        for (hull in arrayOf(a.points, b.points)) for (i in hull.indices) {
            val direction = hull[(i + 1) % hull.size] - hull[i]
            if (direction.zero()) continue
            // Hull-edge normals give SAT for triangles/segments; their directions also handle collinear segments and endpoint-only contact. Extra valid separating axes cannot create a false intersection-free claim.
            for (axis in arrayOf(direction.normal(), direction)) {
                val pa = project(a.points, axis); val pb = project(b.points, axis)
                if (pa.maximum < pb.minimum || pb.maximum < pa.minimum) return true
                if (allowed != null) {
                    if (pa.maximum.compareTo(pb.minimum) == 0 && onlyAllowedContact(pa.maximum, pa, pb, allowed, axis)) return true
                    if (pb.maximum.compareTo(pa.minimum) == 0 && onlyAllowedContact(pb.maximum, pa, pb, allowed, axis)) return true
                }
            }
        }
        return false
    }

    private fun project(points: Array<ExactPoint>, axis: ExactPoint): Projection {
        val values = Array(points.size) { points[it].dot(axis) }
        return Projection(values.min(), values.max(), values)
    }

    /** With convex hulls in opposite closed half-planes, their intersection lies on the separating line. If at least one hull's entire contact face is the known shared endpoint, that point is the ONLY possible contact. A control point on the face is not silently treated as an endpoint. */
    private fun onlyAllowedContact(level: BigDecimal, pa: Projection, pb: Projection, endpoint: ExactPoint, axis: ExactPoint): Boolean {
        if (endpoint.dot(axis).compareTo(level) != 0) return false
        fun isolated(points: Array<ExactPoint>, projection: Projection): Boolean = points.indices.all { projection.values[it].compareTo(level) != 0 || points[it].same(endpoint) }
        return isolated(a.points, pa) || isolated(b.points, pb)
    }
}
