package app.pristinepage.core

import org.junit.Assert.*
import org.junit.Test

class ContractTest {
    private fun image(w: Int, h: Int, stride: Int, bytes: ByteArray) = CoreApi.create("image.RgbImage", w, h, stride, bytes)

    @Test fun rejectsInvalidDimensionsStrideAndShortStorageWithoutAllocation() {
        for ((w, h, stride, bytes) in listOf(
            Parts(0, 1, 3, ByteArray(3)), Parts(-1, 1, 3, ByteArray(3)), Parts(1, 0, 3, ByteArray(3)),
            Parts(Int.MAX_VALUE, 2, Int.MAX_VALUE, ByteArray(0)), Parts(2, 2, 5, ByteArray(12)),
            Parts(2, 2, 6, ByteArray(11)), Parts(1, Int.MAX_VALUE, Int.MAX_VALUE, ByteArray(0)),
        )) assertThrows(IllegalArgumentException::class.java) { image(w, h, stride, bytes) }
    }

    @Test fun rgbSamplesAreUnsignedAndPaddingDoesNotParticipateInEqualityOrHash() {
        val a = image(1, 2, 5, byteArrayOf(-1, 0, -128, 7, 8, 1, 2, 3))
        val b = image(1, 2, 3, byteArrayOf(-1, 0, -128, 1, 2, 3))
        assertEquals(255, CoreApi.call(a, "getUnsigned", 0, 0, 0))
        assertEquals(128, CoreApi.call(a, "getUnsigned", 0, 0, 2))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertArrayEquals(byteArrayOf(-1, 0, -128, 1, 2, 3), CoreApi.call(a, "toPackedByteArray") as ByteArray)
    }

    @Test fun sourceArrayAndReturnedPixelsCannotMutateOwnedImage() {
        val source = byteArrayOf(10, 20, 30)
        val image = image(1, 1, 3, source)
        source[0] = 99
        val exported = CoreApi.call(image, "getPixels") as ByteArray
        exported[1] = 99
        assertArrayEquals(byteArrayOf(10, 20, 30), CoreApi.call(image, "toPackedByteArray") as ByteArray)
    }

    @Test fun retainedLeaseSurvivesOwnerCloseAndCloseIsIdempotent() {
        val owner = image(1, 1, 3, byteArrayOf(1, 2, 3))
        val retained = CoreApi.call(owner, "retain")!!
        CoreApi.call(owner, "close")
        CoreApi.call(owner, "close")
        assertThrows(IllegalStateException::class.java) { CoreApi.call(owner, "toPackedByteArray") }
        assertArrayEquals(byteArrayOf(1, 2, 3), CoreApi.call(retained, "toPackedByteArray") as ByteArray)
        CoreApi.call(retained, "close")
        assertThrows(IllegalStateException::class.java) { CoreApi.call(retained, "retain") }
    }

    @Test fun outOfBoundsNeverReadsPaddingOrWrapsChannel() {
        val image = image(1, 1, 4, byteArrayOf(1, 2, 3, 99))
        for ((x, y, c) in listOf(Triple(1, 0, 0), Triple(0, -1, 0), Triple(0, 0, 3))) {
            assertThrows(IndexOutOfBoundsException::class.java) { CoreApi.call(image, "getUnsigned", x, y, c) }
        }
    }

    @Test fun protectionRejectsNonfiniteAndOutOfRangeSamplesAndOwnsStorage() {
        for (value in floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, -0.01f, 1.01f)) {
            assertThrows(IllegalArgumentException::class.java) { CoreApi.create("image.ProtectionMask", 1, 1, floatArrayOf(value)) }
        }
        val source = floatArrayOf(0.525f)
        val mask = CoreApi.create("image.ProtectionMask", 1, 1, source)
        source[0] = 1f
        assertEquals(0.525f, CoreApi.call(mask, "sample", 0, 0))
        assertThrows(IllegalArgumentException::class.java) { CoreApi.create("image.ProtectionMask", 2, 2, floatArrayOf(0f)) }
    }

    private data class Parts(val w: Int, val h: Int, val stride: Int, val bytes: ByteArray)
}
