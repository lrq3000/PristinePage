package app.pristinepage.core

import org.junit.Assert.*
import org.junit.Test

class CoordinatesTest {
    private fun coordinate(name: String, vararg args: Any) = CoreApi.function("geometry.Coordinates", name, *args)
    private fun numeric(name: String, vararg args: Any) = CoreApi.function("image.Numeric", name, *args)

    @Test fun cornerAlignedGridEndpointsAreNotHalfPixelResize() {
        assertEquals(0.0, coordinate("gridToSource", -1.0, 4000))
        assertEquals(3999.0, coordinate("gridToSource", 1.0, 4000))
        assertEquals(1999.5, coordinate("gridToSource", 0.0, 4000))
        assertEquals(0.5, coordinate("halfPixelSource", 0.0, 4, 2))
        assertEquals(2.5, coordinate("halfPixelSource", 1.0, 4, 2))
        assertThrows(IllegalArgumentException::class.java) { coordinate("gridToSource", 0.0, 1) }
        assertThrows(IllegalArgumentException::class.java) { coordinate("gridToSource", Double.NaN, 4) }
    }

    @Test fun eachQuarterTurnMapsFinalNormalizedCoordinatesToBase() {
        val expected = listOf(0.25 to 0.75, 0.75 to 0.75, 0.75 to 0.25, 0.25 to 0.25)
        for (q in 0..3) {
            val point = coordinate("finalToBase", 0.25, 0.75, q)!!
            assertEquals(expected[q].first, CoreApi.call(point, "getX"))
            assertEquals(expected[q].second, CoreApi.call(point, "getY"))
        }
        assertThrows(IllegalArgumentException::class.java) { coordinate("finalToBase", 0.0, 0.0, 4) }
    }

    @Test fun byteRoundingSignedRoundingAndLumaUseDeclaredContracts() {
        for ((input, output) in listOf(-5.0 to 0, 3.5 to 4, 227.5 to 228, 300.0 to 255)) assertEquals(output, numeric("roundByte", input))
        assertEquals(-1, numeric("roundSigned", -0.5))
        assertEquals(1, numeric("roundSigned", 0.5))
        assertEquals(76, numeric("luma", 255, 0, 0))
        assertEquals(149, numeric("luma", 0, 255, 0))
        assertEquals(28, numeric("luma", 0, 0, 255))
        assertThrows(IllegalArgumentException::class.java) { numeric("roundByte", Double.NaN) }
    }

    @Test fun composerClipsCandidateFirstAndUsesOrderedBinary64() {
        assertEquals(228, numeric("compose", 200, 255.0, 0.5, 0f))
        assertEquals(4, numeric("compose", 3, 8.0, 0.1, 0f))
        assertEquals(200, numeric("compose", 200, 0.0, 1.0, 1f))
        assertThrows(IllegalArgumentException::class.java) { numeric("compose", 20, 300.0, 1.0, 0f) }
    }

    @Test fun reflect101UsesWholePeriodsAndHandlesSingletonWithoutOverflow() {
        assertArrayEquals(intArrayOf(2, 1, 0, 1, 2, 1), (-2..3).map { numeric("reflect101", it, 3) as Int }.toIntArray())
        assertEquals(0, numeric("reflect101", Int.MIN_VALUE, 1))
        assertEquals(0, numeric("reflect101", Int.MIN_VALUE, 3))
    }

    @Test fun oddTurnSwapsAspectBeforePurposeSizingAndTinyGeometryRejects() {
        val size = CoreApi.create("pipeline.PixelSize", 401, 301)
        val rotated = coordinate("rotatedSize", size, 1)!!
        assertEquals(301, CoreApi.call(rotated, "getWidth"))
        assertEquals(401, CoreApi.call(rotated, "getHeight"))
        val limited = coordinate("limitLongEdge", rotated, 100, true)!!
        assertEquals(75, CoreApi.call(limited, "getWidth"))
        assertEquals(100, CoreApi.call(limited, "getHeight"))
        val narrow = CoreApi.create("pipeline.PixelSize", 12000, 2)
        assertThrows(IllegalArgumentException::class.java) { coordinate("limitLongEdge", narrow, 4000, true) }
    }
}
