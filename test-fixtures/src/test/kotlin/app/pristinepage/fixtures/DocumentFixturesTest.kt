package app.pristinepage.fixtures

import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test

class DocumentFixturesTest {
    private fun generate(id: String): Any {
        val type = try { Class.forName("app.pristinepage.fixtures.DocumentFixtures") } catch (missing: ClassNotFoundException) { throw AssertionError("Deterministic fixture generator is absent", missing) }
        return try { type.getMethod("generate", String::class.java).invoke(type.getField("INSTANCE").get(null), id) } catch (error: InvocationTargetException) { throw error.targetException }
    }
    private fun get(target: Any, name: String): Any = try { checkNotNull(target.javaClass.getMethod(name).invoke(target)) } catch (error: ReflectiveOperationException) { throw AssertionError("Required fixture dataset property is absent: $name", error) }
    private fun required(id: String): Any = try { generate(id) } catch (error: IllegalArgumentException) { throw AssertionError("Required fixture case is absent: $id", error) }
    private fun sample(image: Any, x: Int, y: Int, channel: Int): Int = image.javaClass.getMethod("getUnsigned", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(image, x, y, channel) as Int

    @Test fun whiteCanvasHasExactFrameFaintLevelsAndColorPatches() {
        val fixture = generate("F02-white")
        val clean = get(fixture, "getClean")
        assertEquals(255, sample(clean, 0, 0, 0))
        assertEquals(0, sample(clean, 16, 16, 0))
        assertEquals(240, sample(clean, 32, 352, 0))
        assertEquals(180, sample(clean, 288, 64, 0))
        assertEquals(40, sample(clean, 288, 64, 1))
        assertEquals(80, sample(clean, 32, 440, 0))
        assertEquals(255, sample(clean, 32, 452, 0))
    }

    @Test fun generatedNoiseIsRepeatableAndDoesNotMutateCleanCanvas() {
        val a = generate("F03-color-noise")
        val b = generate("F03-color-noise")
        assertEquals(get(a, "getCorrupted"), get(b, "getCorrupted"))
        assertNotEquals(get(a, "getClean"), get(a, "getCorrupted"))
        assertEquals(255, sample(get(a, "getClean"), 0, 0, 0))
        assertEquals("20261114", (get(a, "getParameters") as Map<*, *>)["seed"])
    }

    @Test fun creamTargetPreservesTintAndUnknownFixtureIsExplicitlyRejected() {
        val clean = get(generate("F02-cream"), "getClean")
        assertEquals(240, sample(clean, 0, 0, 0))
        assertEquals(225, sample(clean, 0, 0, 1))
        assertEquals(190, sample(clean, 0, 0, 2))
        assertThrows(IllegalArgumentException::class.java) { generate("not-a-fixture") }
    }

    @Test fun adversarialFixtureKeepsPaddingAndAlphaSeparateFromRgbMeaning() {
        val padding = get(generate("F01-padding"), "getClean")
        assertEquals(255, sample(padding, 0, 0, 0))
        assertEquals(128, sample(padding, 0, 0, 2))
        val alpha = generate("F01-alpha")
        assertArrayEquals(byteArrayOf(-6, 20, 100, 0, 0, 0, -1, -128), get(alpha, "getRawRgba") as ByteArray)
        assertEquals(255, sample(get(alpha, "getClean"), 0, 0, 0))
        assertEquals(127, sample(get(alpha, "getClean"), 1, 0, 0))
    }

    @Test fun geometryFixtureRetainsKnownCoordinatesAndSemanticMasks() {
        val curl = generate("F04-curl")
        val coords = get(curl, "getCoordinateSamples") as Map<*, *>
        val center = coords["center"] as List<*>
        assertEquals(255.5, center[0] as Double, 1e-9)
        assertEquals(383.5, center[1] as Double, 1e-9)
        val masks = get(generate("F02-white"), "getMasks") as Map<*, *>
        val frame = masks["frame"]!!
        assertEquals(true, frame.javaClass.getMethod("contains", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(frame, 16, 16))
        assertEquals(false, frame.javaClass.getMethod("contains", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(frame, 0, 0))
    }

    @Test fun primitiveRawPixelsHaveIndependentPinnedDigest() {
        val clean = get(generate("F05-primitives"), "getClean")
        val bytes = clean.javaClass.getMethod("toPackedByteArray").invoke(clean) as ByteArray
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals("7a096cc12702bcfa647ee070d4f3ba4c2d1d715b484b55b825d0edba6545803b", hash)
    }

    @Test fun geometryProvenanceDoesNotDescribeCurlAsAProjectiveMatrix() {
        val curl = get(generate("F04-curl"), "getParameters") as Map<*, *>
        assertFalse("A nonlinear curl must not carry a perspective inverse matrix", curl.containsKey("inverseMatrix"))
        val perspective = get(generate("F04-perspective"), "getParameters") as Map<*, *>
        assertNotNull("Fixture must preserve its forward transform as well as inverse", perspective["forwardMatrix"])
        val h = (perspective["forwardMatrix"] as String).split(',').map(String::toDouble)
        for ((x, y, u, v) in listOf(Correspondence(0.0, 0.0, 40.0, 40.0), Correspondence(511.0, 0.0, 480.0, 10.0), Correspondence(511.0, 767.0, 500.0, 730.0), Correspondence(0.0, 767.0, 10.0, 760.0))) {
            val z = h[6] * x + h[7] * y + h[8]
            assertEquals(u, (h[0] * x + h[1] * y + h[2]) / z, 1e-7)
            assertEquals(v, (h[3] * x + h[4] * y + h[5]) / z, 1e-7)
        }
    }

    @Test fun seedOrderAndChannelGainsMatchIndependentNoiseOracle() {
        val image = get(generate("F03-color-noise"), "getCorrupted")
        assertArrayEquals(intArrayOf(148, 136, 136, 144, 139, 139), (0..5).map { sample(image, it / 3, 0, it % 3) }.toIntArray())
    }

    private data class Correspondence(val x: Double, val y: Double, val u: Double, val v: Double)

    @Test fun constantCasesHaveTwoNonzeroPaddingEquivalents() {
        for (color in listOf("black", "gray80", "gray200", "white", "cream", "blue")) for (size in listOf("1x1", "1x17", "17x1", "2x2", "31x47", "512x768")) {
            val id = "F01-$color-$size"
            val packed = required(id) as AutoCloseable
            val a = required("$id-padded-a") as AutoCloseable
            val b = required("$id-padded-b") as AutoCloseable
            try {
                assertEquals(get(packed, "getClean"), get(a, "getClean"))
                assertEquals(get(packed, "getClean"), get(b, "getClean"))
                val imageA = get(a, "getClean"); val imageB = get(b, "getClean")
                assertFalse((get(imageA, "getPixels") as ByteArray).contentEquals(get(imageB, "getPixels") as ByteArray))
            } finally { packed.close(); a.close(); b.close() }
        }
    }

    @Test fun everyLightingVariantCanRetainACreamTarget() {
        for (variant in listOf("smooth", "color-noise", "abrupt", "glare")) {
            val fixture = required("F03-$variant-cream")
            try {
                val clean = get(fixture, "getClean")
                assertEquals(240, sample(clean, 0, 0, 0)); assertEquals(225, sample(clean, 0, 0, 1)); assertEquals(190, sample(clean, 0, 0, 2))
                assertNotEquals(clean, get(fixture, "getCorrupted"))
            } finally { (fixture as AutoCloseable).close() }
        }
    }

    @Test fun pageCoverageTracksTheProjectedPageRatherThanItsPrintedFrame() {
        val fixture = required("F04-perspective")
        try {
            val coverage = get(fixture, "getPageCoverage")
            fun mask(x: Int, y: Int) = coverage.javaClass.getMethod("sample", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(coverage, x, y) as Float
            assertEquals(0f, mask(0, 0)); assertEquals(1f, mask(40, 40)); assertEquals(1f, mask(100, 100))
            assertEquals("corrupted-destination", (get(fixture, "getParameters") as Map<*, *>)["pageCoverageSpace"])
        } finally { (fixture as AutoCloseable).close() }
    }

    @Test fun geometryDiagnosticDatasetsExposeIndependentFailureAndHandleOracles() {
        fun coordinates(id: String): Map<*, *> = get(required(id), "getCoordinateSamples") as Map<*, *>
        val invalid = coordinates("F04-invalid-cell")
        assertEquals(listOf(0.25, 1.25), invalid["p11"])
        val vectors = get(required("F04-invalid-cell"), "getVectors") as Map<*, *>
        assertEquals(listOf(1.5, -1.0), get(vectors["jacobian-center-and-min"]!!, "getExpected"))
        val overlap = coordinates("F04-overlap")
        assertEquals(listOf(0.0, 0.0), overlap["a00"])
        assertEquals(listOf(1.0, 1.0), overlap["b00"])
        assertEquals(listOf(3.0, 3.0), overlap["b11"])
        val manual = coordinates("F04-manual-edge")
        assertEquals(listOf(50.0, -20.0), manual["control"])
        val manualVectors = get(required("F04-manual-edge"), "getVectors") as Map<*, *>
        assertEquals(listOf(0.625), get(manualVectors["depth-midpoint"]!!, "getExpected"))
        val corners = get(required("F04-corners"), "getClean")
        val colors = listOf(0 to 0, 511 to 0, 511 to 767, 0 to 767).map { (x, y) -> (0..2).map { sample(corners, x, y, it) } }
        assertEquals(4, colors.toSet().size)
    }

    @Test fun primitiveDatasetsExposeAllSpecifiedVectorIdsWithoutExecutingStages() {
        val vectors = get(required("F05-primitives"), "getVectors") as Map<*, *>
        val expected = (1..12).map { "ALG-%02d".format(it) } + (1..7).map { "GEO-%02d".format(it) } + (1..3).map { "STATE-%02d".format(it) }
        assertTrue(vectors.keys.containsAll(expected))
        assertEquals(listOf(-7.2, -7.0), get(vectors["ALG-07"]!!, "getExpected"))
        assertEquals(listOf(255.0, 105.0, 25.0), get(vectors["ALG-08"]!!, "getExpected"))
        assertEquals(listOf(0.0, 0.0), get(vectors["ALG-11"]!!, "getExpected"))
        assertEquals(listOf(300.0, 500.0), get(vectors["GEO-04"]!!, "getExpected"))
    }

    @Test fun curlOracleIncludesAnOffCenterNonzeroDisplacement() {
        val samples = get(required("F04-curl"), "getCoordinateSamples") as Map<*, *>
        assertEquals(listOf(271.5, 191.75), samples["quarter"])
    }
}
