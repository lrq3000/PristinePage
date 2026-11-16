package app.pristinepage

import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.image.decode.ImageAllocationBudget
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test

/** Exercises actual tracked admission, not allocations proportional to synthetic memory-class values. Android ActivityManager selection and application singleton wiring remain native gates. */
class ApplicationImageBudgetTest {
    private fun budget(memoryClassMiB: Int): ImageAllocationBudget {
        val type = try { Class.forName("app.pristinepage.ApplicationImageBudget") }
        catch (_: ClassNotFoundException) { throw AssertionError("Application-scoped image admission policy is absent") }
        return try { type.getMethod("create", Int::class.javaPrimitiveType).invoke(type.getField("INSTANCE").get(null), memoryClassMiB) as ImageAllocationBudget }
        catch (failure: InvocationTargetException) { throw failure.targetException }
    }

    @Test fun lowMemoryClassCannotReduceTrackedCapacityBelowTheDocumentedFloor() {
        for (memoryClass in listOf(1, 16, 64)) {
            val b = budget(memoryClass); assertEquals(33_554_432L, b.capacityBytes)
            val reservation = b.reserve(33_554_432L)
            assertEquals(OperationErrorCode.RESOURCE_LIMIT, assertThrows(RepositoryFailure::class.java) { b.reserve(1) }.error.code)
            reservation.close(); assertEquals(0L, b.usedBytes)
        }
    }

    @Test fun oddMemoryClassRetainsItsHalfMiBInsteadOfIntegerTruncation() {
        val b = budget(65); assertEquals(34_078_720L, b.capacityBytes)
        b.reserve(34_078_720L).use { assertEquals(34_078_720L, b.usedBytes) }
        assertEquals(0L, b.usedBytes)
    }

    @Test fun hugeMemoryClassCannotOverflowOrRemoveThe192MiBCeiling() {
        for (memoryClass in listOf(384, 1024, Int.MAX_VALUE)) {
            val b = budget(memoryClass); assertEquals(201_326_592L, b.capacityBytes)
            assertEquals(OperationErrorCode.RESOURCE_LIMIT, assertThrows(RepositoryFailure::class.java) { b.reserve(201_326_593L) }.error.code)
            assertEquals(0L, b.usedBytes)
        }
    }

    @Test fun unavailableMemoryClassIsRejectedRatherThanAssumedToBeALargeDevice() {
        for (invalid in listOf(0, -1, Int.MIN_VALUE)) assertThrows(IllegalArgumentException::class.java) { budget(invalid) }
    }
}
