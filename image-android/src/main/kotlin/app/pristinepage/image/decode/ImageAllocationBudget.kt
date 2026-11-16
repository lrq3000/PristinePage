package app.pristinepage.image.decode

import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import java.util.concurrent.atomic.AtomicBoolean

/** Application-scoped accounting shared by decode, retained previews and later render work, not one independent allowance per operation. Estimates do not measure total PSS/native decoder peaks; callers must include all concurrently owned buffers. */
class ImageAllocationBudget(val capacityBytes: Long) {
    private var used = 0L
    init { require(capacityBytes > 0) }
    val usedBytes: Long @Synchronized get() = used

    @Synchronized fun reserve(bytes: Long): AutoCloseable {
        if (bytes <= 0) refuse(OperationErrorCode.INVALID_INPUT, "invalid_image_allocation")
        // Subtraction is safe because 0<=used<=capacity; addition-first could wrap even when both values are positive.
        if (bytes > capacityBytes - used) refuse(OperationErrorCode.RESOURCE_LIMIT, "image_allocation_limit")
        used += bytes
        return Reservation(this, bytes)
    }

    @Synchronized private fun release(bytes: Long) { check(bytes in 1..used); used -= bytes }
    private class Reservation(private val owner: ImageAllocationBudget, private val bytes: Long) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        override fun close() { if (closed.compareAndSet(false, true)) owner.release(bytes) }
    }

    companion object {
        @JvmStatic fun forMemoryClass(memoryClassMiB: Int): ImageAllocationBudget {
            require(memoryClassMiB > 0)
            return ImageAllocationBudget(minOf(192L * 1024 * 1024, maxOf(32L * 1024 * 1024, memoryClassMiB.toLong() * 1024 * 1024 / 2)))
        }
        private fun refuse(code: OperationErrorCode, key: String): Nothing = throw RepositoryFailure(OperationError(code, false, key, null))
    }
}
