package app.pristinepage.image

import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

/** Tiny malicious TIFF envelopes are passed ONLY to the bounded pure reader, never the unbounded EXIF library/native codec. Component-count tests must not attempt the claimed allocation. */
class TiffOrientationTest {
    private fun read(bytes: ByteArray): Int {
        val cls = try { Class.forName("app.pristinepage.image.decode.TiffOrientation") }
        catch (_: ClassNotFoundException) { throw AssertionError("Bounded TIFF orientation validation is absent") }
        return try { cls.methods.single { it.name == "read" }.invoke(cls.getField("INSTANCE").get(null), bytes, {}) as Int }
        catch (failure: InvocationTargetException) { throw failure.targetException }
    }
    private fun tiff(order: ByteOrder = ByteOrder.LITTLE_ENDIAN, entries: List<IntArray> = listOf(intArrayOf(0x112, 3, 1, 6)), next: Int = 0): ByteArray {
        val b = ByteBuffer.allocate(14 + 12 * entries.size).order(order)
        b.put(if (order == ByteOrder.LITTLE_ENDIAN) 'I'.code.toByte() else 'M'.code.toByte()); b.put(b[0]); b.putShort(42); b.putInt(8); b.putShort(entries.size.toShort())
        for (e in entries) { b.putShort(e[0].toShort()); b.putShort(e[1].toShort()); b.putInt(e[2]); if (e[1] == 3 && e[2] == 1) { b.putShort(e[3].toShort()); b.putShort(0) } else b.putInt(e[3]) }
        return b.putInt(next).array()
    }
    private fun reject(code: OperationErrorCode, bytes: ByteArray) {
        val error = try { read(bytes); throw AssertionError("Malformed TIFF was treated as absence") } catch (failure: RepositoryFailure) { failure.error }
        assertEquals(code, error.code)
    }

    @Test fun validatedMissingTagDefaultsToOneButBothByteOrdersRetainAllEightValues() {
        assertEquals(1, read(tiff(entries = emptyList())))
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) for (orientation in 1..8) assertEquals(orientation, read(tiff(order, listOf(intArrayOf(0x112, 3, 1, orientation)))))
    }

    @Test fun billionComponentCountRejectsInsideTinyEnvelopeWithoutAllocatingItsClaim() {
        reject(OperationErrorCode.INVALID_INPUT, tiff(entries = listOf(intArrayOf(0x112, 3, 1_000_000_000, 8))))
        reject(OperationErrorCode.INVALID_INPUT, tiff(entries = listOf(intArrayOf(0x100, 12, Int.MAX_VALUE, 8))))
    }

    @Test fun presentInvalidTypeCountAndValueCannotBecomeDefaultOrientation() {
        for (e in listOf(intArrayOf(0x112, 0, 1, 6), intArrayOf(0x112, 3, 0, 0), intArrayOf(0x112, 3, 2, 6), intArrayOf(0x112, 3, 1, 0), intArrayOf(0x112, 3, 1, 9))) reject(OperationErrorCode.INVALID_INPUT, tiff(entries = listOf(e)))
    }

    @Test fun duplicateOrientationAndCyclicOrOutOfRangeIfdsRejectRatherThanGuess() {
        reject(OperationErrorCode.INVALID_INPUT, tiff(entries = listOf(intArrayOf(0x112, 3, 1, 1), intArrayOf(0x112, 3, 1, 6))))
        reject(OperationErrorCode.INVALID_INPUT, tiff(next = 8))
        reject(OperationErrorCode.INVALID_INPUT, tiff(next = Int.MAX_VALUE))
    }

    @Test fun malformedHeadersAndComponentOffsetsRefuseWithoutSubordinateParsing() {
        reject(OperationErrorCode.INVALID_INPUT, byteArrayOf())
        reject(OperationErrorCode.INVALID_INPUT, tiff().copyOf(12))
        reject(OperationErrorCode.INVALID_INPUT, tiff(entries = listOf(intArrayOf(0x100, 4, 2, 24))))
        reject(OperationErrorCode.INVALID_INPUT, tiff(entries = listOf(intArrayOf(0x8769, 4, 1, -1))))
    }

    @Test fun excessiveNestedIfdsHaveABoundedWorkFailureNotUnboundedRecursion() {
        val b = ByteBuffer.allocate(8 + 18 * 10).order(ByteOrder.LITTLE_ENDIAN).put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
        repeat(10) { i -> b.putShort(1).putShort(0x8769.toShort()).putShort(4).putInt(1).putInt(if (i == 9) 8 else 8 + 18 * (i + 1)).putInt(0) }
        reject(OperationErrorCode.RESOURCE_LIMIT, b.array())
    }

    @Test fun declaredNonSrgbExifColorDoesNotBecomeUnprofiledSrgb() {
        assertEquals(1, read(tiff(entries = listOf(intArrayOf(0xa001, 3, 1, 1)))))
        reject(OperationErrorCode.UNSUPPORTED_FORMAT, tiff(entries = listOf(intArrayOf(0xa001, 3, 1, 2))))
    }
}
