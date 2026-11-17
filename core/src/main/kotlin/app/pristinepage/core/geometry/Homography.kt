package app.pristinepage.core.geometry

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.PointPx
import app.pristinepage.core.pipeline.QuadPx
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt
import org.ejml.data.DMatrixRMaj
import org.ejml.dense.row.CommonOps_DDRM
import org.ejml.dense.row.factory.DecompositionFactory_DDRM

/** Numerical solution rejection is distinct from malformed serialized controls. The renderer maps this subtype to INVALID_MAP, never an affine/other-method substitute. */
class InvalidHomographyException(message: String) : IllegalArgumentException(message)

/** A pole-free source-center inverse map. This solver does not itself establish the separate stretch, visibility, resource or image/mask acceptance gates. */
class Homography private constructor(private val backward: DoubleArray, val sourceSize: PixelSize, val outputSize: PixelSize) {
    fun map(x: Double, y: Double): PointPx {
        require(x.isFinite() && y.isFinite() && x in 0.0..(outputSize.width - 1).toDouble() && y in 0.0..(outputSize.height - 1).toDouble()) { "Homography input must be a destination center" }
        return evaluate(backward, x, y)
    }

    companion object {
        @JvmStatic fun solve(quad: QuadPx, source: PixelSize, output: PixelSize): Homography {
            QuadValidation.validate(quad, source)
            require(output.width >= 2 && output.height >= 2) { "Homography output requires two-dimensional centers" }
            val destination = arrayOf(PointPx(0.0, 0.0), PointPx((output.width - 1).toDouble(), 0.0), PointPx((output.width - 1).toDouble(), (output.height - 1).toDouble()), PointPx(0.0, (output.height - 1).toDouble()))
            if (output == source && quad.points().toTypedArray().contentEquals(destination)) {
                return Homography(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), source, output)
            }
            val sourcePoints = quad.points().toTypedArray()
            val from = Normalization(sourcePoints)
            val to = Normalization(destination)
            val dlt = DMatrixRMaj(8, 9)
            for (i in 0..3) {
                val p = from.points[i]; val q = to.points[i]
                val first = doubleArrayOf(-p.x, -p.y, -1.0, 0.0, 0.0, 0.0, q.x * p.x, q.x * p.y, q.x)
                val second = doubleArrayOf(0.0, 0.0, 0.0, -p.x, -p.y, -1.0, q.y * p.x, q.y * p.y, q.y)
                for (j in 0..8) { dlt.set(2 * i, j, first[j]); dlt.set(2 * i + 1, j, second[j]) }
            }
            // Compact V would omit the ninth right-null vector for this wide8x9 system. Never replace this with normal equations or fixing a small H22 to one.
            val decomposition = DecompositionFactory_DDRM.svd(8, 9, false, true, false)
            if (!decomposition.decompose(dlt)) invalid("DLT SVD did not converge")
            condition(decomposition.singularValues, decomposition.numberOfSingularValues(), 8)
            val vectors = decomposition.getV(null, false)
            if (vectors.numRows != 9 || vectors.numCols != 9) invalid("DLT SVD omitted its full right basis")
            val hn = DMatrixRMaj(3, 3)
            for (i in 0..8) hn.data[i] = vectors.get(i, 8)
            if (hn.data.any { !it.isFinite() }) invalid("Nonfinite normalized homography")
            val normalizedSvd = DecompositionFactory_DDRM.svd(3, 3, false, false, true)
            if (!normalizedSvd.decompose(hn.copy())) invalid("Normalized homography SVD did not converge")
            condition(normalizedSvd.singularValues, normalizedSvd.numberOfSingularValues(), 3)
            val hnInverse = DMatrixRMaj(3, 3)
            if (!CommonOps_DDRM.invert(hn, hnInverse)) invalid("Normalized homography is singular")
            val forward = multiply(to.inverse, multiply(hn, from.transform)).data.copyOf()
            // Algebraically H^-1 = Tsource^-1 * Hn^-1 * Tdestination. Inverting in normalized units avoids treating harmless pixel translations as poor conditioning.
            val backward = multiply(from.inverse, multiply(hnInverse, to.transform)).data.copyOf()
            val magnitude = backward.maxOf { abs(it) }
            if (!magnitude.isFinite() || magnitude == 0.0) invalid("Invalid inverse scale")
            for (i in backward.indices) backward[i] /= magnitude
            if (forward.any { !it.isFinite() } || backward.any { !it.isFinite() }) invalid("Nonfinite denormalized map")
            val sourceTolerance = max(1e-6, 1e-9 * hypot((source.width - 1).toDouble(), (source.height - 1).toDouble()))
            val destinationTolerance = max(1e-6, 1e-9 * hypot((output.width - 1).toDouble(), (output.height - 1).toDouble()))
            for (i in 0..3) {
                val p = evaluate(forward, sourcePoints[i].x, sourcePoints[i].y)
                val q = evaluate(backward, destination[i].x, destination[i].y)
                if (hypot(p.x - destination[i].x, p.y - destination[i].y) > destinationTolerance || hypot(q.x - sourcePoints[i].x, q.y - sourcePoints[i].y) > sourceTolerance) invalid("Corner reprojection exceeded tolerance")
            }
            val z = destination.map { p -> denominator(backward, p.x, p.y) }
            val largest = z.maxOf { abs(it) }
            if (!largest.isFinite() || largest == 0.0 || !(z.all { it > 1e-10 * largest } || z.all { it < -1e-10 * largest })) invalid("Inverse denominator touches or crosses a pole")
            val determinant = CommonOps_DDRM.det(matrix(backward))
            if (!determinant.isFinite() || z.any { val jacobian = determinant / (it * it * it); !jacobian.isFinite() || jacobian <= 0.0 }) invalid("Inverse map does not preserve orientation")
            return Homography(backward, source, output)
        }

        private fun condition(values: DoubleArray, count: Int, expected: Int) {
            if (count != expected) invalid("Unexpected singular-value count")
            val selected = values.take(count)
            if (selected.any { !it.isFinite() || it <= 0.0 }) invalid("Rank-deficient homography system")
            if (selected.max() / selected.min() > 1e10) invalid("Normalized homography condition exceeded limit")
        }

        private fun multiply(a: DMatrixRMaj, b: DMatrixRMaj): DMatrixRMaj = DMatrixRMaj(3, 3).also { CommonOps_DDRM.mult(a, b, it) }
        private fun matrix(values: DoubleArray): DMatrixRMaj = DMatrixRMaj(3, 3).also { values.copyInto(it.data) }
        private fun denominator(m: DoubleArray, x: Double, y: Double): Double = m[6] * x + m[7] * y + m[8]
        private fun evaluate(m: DoubleArray, x: Double, y: Double): PointPx {
            val z = denominator(m, x, y)
            if (!z.isFinite() || z == 0.0) invalid("Nonfinite or zero projective denominator")
            val a = (m[0] * x + m[1] * y + m[2]) / z
            val b = (m[3] * x + m[4] * y + m[5]) / z
            if (!a.isFinite() || !b.isFinite()) invalid("Nonfinite projective coordinate")
            return PointPx(a, b)
        }

        private fun invalid(message: String): Nothing = throw InvalidHomographyException(message)

        private class Normalization(original: Array<PointPx>) {
            private val cx = original.sumOf { it.x } / 4.0
            private val cy = original.sumOf { it.y } / 4.0
            private val distance = original.sumOf { hypot(it.x - cx, it.y - cy) } / 4.0
            private val scale = sqrt(2.0) / distance
            val points: Array<PointPx>
            val transform: DMatrixRMaj
            val inverse: DMatrixRMaj
            init {
                if (!scale.isFinite() || scale <= 0.0) invalid("Degenerate point normalization")
                points = Array(4) { i -> PointPx(scale * (original[i].x - cx), scale * (original[i].y - cy)) }
                transform = matrix(doubleArrayOf(scale, 0.0, -scale * cx, 0.0, scale, -scale * cy, 0.0, 0.0, 1.0))
                inverse = matrix(doubleArrayOf(1.0 / scale, 0.0, cx, 0.0, 1.0 / scale, cy, 0.0, 0.0, 1.0))
            }
        }
    }
}
