package app.pristinepage.image.decode

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure

/** Full-source identity never downscales to make admission pass. Keep the permit until every estimated bitmap/workspace/output lease has closed or ownership has transferred explicitly. */
class DecodePermit internal constructor(val size: PixelSize, val reservedBytes: Long, private val reservation: AutoCloseable, private val ledger: ImageAllocationBudget) : AutoCloseable {
    internal fun chargedOn(budget: ImageAllocationBudget): Long = if (ledger === budget) reservedBytes else 0L
    override fun close() = reservation.close()
}

class DecodeAdmission(private val budget: ImageAllocationBudget) {
    fun admit(encodedBytes: Long, width: Long, height: Long, orientation: Int): DecodePermit {
        val estimate = estimate(encodedBytes, width, height, orientation)
        return DecodePermit(estimate.size, estimate.bytes, budget.reserve(estimate.bytes), budget)
    }

    internal class Estimate(val size: PixelSize, val bytes: Long)

    internal companion object {
        fun estimate(encodedBytes: Long, width: Long, height: Long, orientation: Int): Estimate {
            if (encodedBytes <= 0 || width <= 0 || height <= 0 || orientation !in 1..8) refuse(OperationErrorCode.INVALID_INPUT, "invalid_decode_metadata")
            if (encodedBytes > 104857600 || width > 12000 || height > 12000) refuse(OperationErrorCode.RESOURCE_LIMIT, "source_metadata_limit")
            // These bounds precede multiplication and narrowing; all charge terms together remain below 1 GiB.
            val pixels = width * height
            if (pixels > 48_000_000) refuse(OperationErrorCode.RESOURCE_LIMIT, "source_metadata_limit")
            // RGBA bitmap4N + provisional native workspace4N + packed RGB3N + owned immutable RGB copy3N, one ARGB row, fixed parser/hash workspace, and encoded-byte exposure. Not a measured native upper bound.
            val estimate = encodedBytes + 14 * pixels + 4 * width + 1048576
            val size = if (orientation in 5..8) PixelSize(height.toInt(), width.toInt()) else PixelSize(width.toInt(), height.toInt())
            return Estimate(size, estimate)
        }

        private fun refuse(code: OperationErrorCode, key: String): Nothing = throw RepositoryFailure(OperationError(code, false, key, null))
    }
}
