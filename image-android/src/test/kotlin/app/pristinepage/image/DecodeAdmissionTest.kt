package app.pristinepage.image

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test

/** No Android codec or giant raster is substituted: these tests execute the actual pure preallocation and live-budget contract. Native allocation estimates need a separate measured gate. */
class DecodeAdmissionTest {
    private fun type(name: String): Class<*> = try { Class.forName("app.pristinepage.image.decode.$name") }
    catch (_: ClassNotFoundException) { throw AssertionError("Admitted source normalization is absent: $name") }
    private fun budget(bytes: Long): Any = type("ImageAllocationBudget").getConstructor(Long::class.javaPrimitiveType).newInstance(bytes)
    private fun call(target: Any, name: String, vararg args: Any): Any? = try {
        target.javaClass.methods.single { it.name == name && it.parameterCount == args.size }.invoke(target, *args)
    } catch (failure: InvocationTargetException) { throw failure.targetException }
    private fun admit(budget: Any, bytes: Long, width: Long, height: Long, orientation: Int = 1): Any = try {
        val gate = type("DecodeAdmission").getConstructor(type("ImageAllocationBudget")).newInstance(budget)
        call(gate, "admit", bytes, width, height, orientation)!!
    } catch (failure: InvocationTargetException) { throw failure.targetException }
    private fun reject(code: OperationErrorCode, action: () -> Unit) {
        val error = try { action(); throw AssertionError("Unsafe decode was admitted") } catch (failure: RepositoryFailure) { failure.error }
        assertEquals(code, error.code)
    }

    @Test fun invalidDimensionsAndOrientationRejectWithoutReservingAnyBuffer() {
        val b = budget(256L * 1024 * 1024)
        for ((w, h, o) in listOf(Triple(0L, 2L, 1), Triple(-1L, 2L, 1), Triple(2L, 0L, 1), Triple(2L, 2L, 0), Triple(2L, 2L, 9))) {
            reject(OperationErrorCode.INVALID_INPUT) { admit(b, 20, w, h, o) }
            assertEquals(0L, call(b, "getUsedBytes"))
        }
    }

    @Test fun encodedByteCeilingIsInclusiveAndNotAReportedProviderLength() {
        val b = budget(256L * 1024 * 1024)
        (admit(b, 104857600L, 1, 1) as AutoCloseable).close()
        reject(OperationErrorCode.RESOURCE_LIMIT) { admit(b, 104857601L, 1, 1) }
        reject(OperationErrorCode.INVALID_INPUT) { admit(b, 0, 1, 1) }
        assertEquals(0L, call(b, "getUsedBytes"))
    }

    @Test fun metadataCeilingsAndOverflowRefuseBeforeAllocation() {
        val b = budget(2_000_000_000L)
        (admit(b, 1, 12000, 4000) as AutoCloseable).close()
        for ((w, h) in listOf(12001L to 1L, 8000L to 6001L, Long.MAX_VALUE to Long.MAX_VALUE)) {
            reject(OperationErrorCode.RESOURCE_LIMIT) { admit(b, 1, w, h) }
        }
        assertEquals(0L, call(b, "getUsedBytes"))
    }

    @Test fun fullDecodeRefusesWhenMetadataPassesButTrackedBuffersDoNotFit() {
        val b = budget(32L * 1024 * 1024)
        reject(OperationErrorCode.RESOURCE_LIMIT) { admit(b, 1000, 4000, 3000) }
        assertEquals(0L, call(b, "getUsedBytes"))
        val p = admit(b, 1000, 3, 2, 6)
        assertEquals(PixelSize(2, 3), call(p, "getSize"))
        assertEquals(1049672L, call(p, "getReservedBytes")) // 1000 encoded +14*6 pixel bytes +4*3 row +1MiB workspace.
        (p as AutoCloseable).close(); assertEquals(0L, call(b, "getUsedBytes"))
    }

    @Test fun concurrentConsumersShareCapacityAndDoubleCloseCannotReleaseAnotherLease() {
        val b = budget(100)
        val a = call(b, "reserve", 60L) as AutoCloseable
        reject(OperationErrorCode.RESOURCE_LIMIT) { call(b, "reserve", 41L) }
        val c = call(b, "reserve", 40L) as AutoCloseable
        assertEquals(100L, call(b, "getUsedBytes")); a.close(); a.close()
        assertEquals(40L, call(b, "getUsedBytes")); c.close(); assertEquals(0L, call(b, "getUsedBytes"))
        val entire = call(b, "reserve", 100L) as AutoCloseable; entire.close()
    }

    @Test fun budgetAdditionCannotWrapAndAnInvalidReservationCannotChangeAccounting() {
        val b = budget(Long.MAX_VALUE); val a = call(b, "reserve", Long.MAX_VALUE - 5) as AutoCloseable
        reject(OperationErrorCode.RESOURCE_LIMIT) { call(b, "reserve", 10L) }
        reject(OperationErrorCode.INVALID_INPUT) { call(b, "reserve", 0L) }
        reject(OperationErrorCode.INVALID_INPUT) { call(b, "reserve", -1L) }
        assertEquals(Long.MAX_VALUE - 5, call(b, "getUsedBytes")); a.close(); assertEquals(0L, call(b, "getUsedBytes"))
    }

    @Test fun processingCapacityUsesTheSpecifiedMemoryClassRangeWithoutAnAppVersionLiteral() {
        val cls = type("ImageAllocationBudget")
        val factory = cls.methods.single { it.name == "forMemoryClass" && it.parameterCount == 1 }
        for ((memory, expectedMiB) in listOf(32 to 32, 128 to 64, 256 to 128, 1024 to 192)) {
            val b = checkNotNull(factory.invoke(null, memory)); assertEquals(expectedMiB.toLong() * 1024 * 1024, call(b, "getCapacityBytes"))
        }
    }
}
