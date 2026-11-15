package app.pristinepage.core

import org.junit.Assert.*
import org.junit.Test

class RecipeCodecTest {
    private val source = """{"sourcePixelHash":"${"a".repeat(64)}","sourceSize":{"width":3,"height":2},"orientationColorConversionVersion":"srgb-v1"}"""
    private val minimal get() = """{"geometry":{"kind":"none_v1","source":$source}}"""
    private fun decode(json: String) = CoreApi.function("recipe.RecipeCodec", "decode", json.toByteArray())!!
    private fun encode(recipe: Any) = CoreApi.function("recipe.RecipeCodec", "encode", recipe) as ByteArray
    private fun hash(recipe: Any) = CoreApi.function("recipe.RecipeCodec", "hash", recipe) as String

    @Test fun equivalentExplicitAndOmittedDefaultsHaveIdenticalCanonicalBytesAndHash() {
        val explicit = """{"binary":null,"detail":null,"denoise":null,"appearance":{"kind":"none_v1"},"geometry":{"kind":"none_v1","source":$source,"outputQuarterTurns":0,"schemaVersion":1},"schemaVersion":1}"""
        val a = decode(minimal)
        val b = decode(explicit)
        assertArrayEquals(encode(a), encode(b))
        assertEquals(hash(a), hash(b))
        assertTrue(hash(a).matches(Regex("[0-9a-f]{64}")))
        assertArrayEquals(encode(a), encode(decode(String(encode(a)))))
        assertTrue(String(encode(a)).contains("\"outputQuarterTurns\":0"))
        assertTrue(String(encode(a)).contains("\"denoise\":null"))
    }

    @Test fun unknownSchemasAlgorithmsFieldsAndNonfiniteValuesRejectBeforeRendering() {
        val invalid = listOf(
            minimal.dropLast(1) + ",\"schemaVersion\":2}",
            minimal.replace("none_v1", "future_v9"),
            minimal.dropLast(1) + ",\"unexpected\":true}",
            minimal.replace("\"source\":", "\"unexpected\":1,\"source\":"),
            minimal.replace("\"source\":", "\"outputQuarterTurns\":4,\"source\":"),
            minimal.replace("\"source\":", "\"outputQuarterTurns\":-1,\"source\":"),
            minimal.dropLast(1) + ",\"appearance\":{\"kind\":\"paper_gain_v1\",\"strength\":NaN}}",
        )
        for (json in invalid) assertThrows(IllegalArgumentException::class.java) { decode(json) }
    }

    @Test fun rotatedRecipeChangesHashWithoutChangingSourceBinding() {
        val rotated = minimal.replace("\"source\":", "\"outputQuarterTurns\":1,\"source\":")
        assertNotEquals(hash(decode(minimal)), hash(decode(rotated)))
        val json = String(encode(decode(rotated)))
        assertTrue(json.contains("\"sourcePixelHash\":\"${"a".repeat(64)}\""))
        assertTrue(json.contains("\"width\":3"))
    }

    @Test fun methodParameterRangesAndIncompatibleFinalStagesAreNotSilentlyRepaired() {
        for (appearance in listOf("""{"kind":"paper_gain_v1","target":239}""", """{"kind":"natural_l_v1","lightnessBlend":0.61}""", """{"kind":"local_max_gain_v1","radiusPx":0}""")) {
            assertThrows(IllegalArgumentException::class.java) { decode(minimal.dropLast(1) + ",\"appearance\":$appearance}") }
        }
        val incompatible = minimal.dropLast(1) + ",\"detail\":{\"kind\":\"luma_detail7_v1\"},\"binary\":{\"kind\":\"sauvola_v1\"}}"
        assertThrows(IllegalArgumentException::class.java) { decode(incompatible) }
    }

    @Test fun duplicateKeysMalformedUtf8AndExcessiveNestingCannotGainAmbiguousMeaning() {
        assertThrows(IllegalArgumentException::class.java) { decode(minimal.dropLast(1) + ",\"schemaVersion\":1,\"schemaVersion\":1}") }
        assertThrows(IllegalArgumentException::class.java) { CoreApi.function("recipe.RecipeCodec", "decode", byteArrayOf(0xc3.toByte(), 0x28)) }
        assertThrows(IllegalArgumentException::class.java) { decode("[".repeat(65) + "0" + "]".repeat(65)) }
    }

    @Test fun everyPhotometricFamilyRoundTripsWithEffectiveDefaults() {
        val additions = listOf(
            "\"appearance\":{\"kind\":\"paper_gain_v1\"}",
            "\"appearance\":{\"kind\":\"natural_l_v1\"}",
            "\"appearance\":{\"kind\":\"local_max_gain_v1\"}",
            "\"denoise\":{\"kind\":\"bilateral_luma_v1\"}",
            "\"detail\":{\"kind\":\"luma_detail7_v1\"}",
            "\"binary\":{\"kind\":\"grayscale_v1\"}",
            "\"binary\":{\"kind\":\"sauvola_v1\",\"window\":{\"kind\":\"explicit_odd_v1\",\"sidePx\":15}}",
        )
        for (addition in additions) {
            val bytes = encode(decode(minimal.dropLast(1) + ",$addition}"))
            assertArrayEquals(bytes, encode(decode(String(bytes))))
        }
    }

    @Test fun malformedProgrammaticUnicodeCannotCollapseIntoQuestionMarkIdentity() {
        for (bad in listOf("\uD800", "\uDC00")) {
            assertThrows(IllegalArgumentException::class.java) {
                val binding = app.pristinepage.core.recipe.SourceBinding("a".repeat(64), app.pristinepage.core.pipeline.PixelSize(3, 2), bad)
                val recipe = app.pristinepage.core.pipeline.Recipe(geometry = app.pristinepage.core.recipe.GeometryRecipe.None(binding))
                app.pristinepage.core.recipe.RecipeCodec.encode(recipe)
            }
        }
    }

    @Test fun escapedUnpairedSurrogatesRejectBeforeReturningDecodedRecipe() {
        for (escaped in listOf("\\uD800", "\\uDC00")) {
            assertThrows(IllegalArgumentException::class.java) { decode(minimal.replace("srgb-v1", escaped)) }
        }
    }

    @Test fun validSupplementaryUnicodeSurvivesExactRoundTrip() {
        val version = "srgb-\uD83D\uDCC4-v1"
        val recipe = decode(minimal.replace("srgb-v1", version))
        val bytes = encode(recipe)
        val decoded = app.pristinepage.core.recipe.RecipeCodec.decode(bytes)
        assertEquals(version, decoded.geometry.source.orientationColorConversionVersion)
        assertNotEquals(hash(recipe), hash(decode(minimal.replace("srgb-v1", "srgb-?-v1"))))
    }

    @Test fun canonicalBytesAndHashMatchIndependentVersionOneGolden() {
        val expected = """{"appearance":{"kind":"none_v1"},"binary":null,"denoise":null,"detail":null,"geometry":{"kind":"none_v1","outputQuarterTurns":0,"schemaVersion":1,"source":{"orientationColorConversionVersion":"srgb-v1","sourcePixelHash":"${"a".repeat(64)}","sourceSize":{"height":2,"width":3}}},"schemaVersion":1}"""
        val recipe = decode(minimal)
        assertArrayEquals(expected.toByteArray(Charsets.UTF_8), encode(recipe))
        assertEquals("e9c6c21880a3c8802e7f59bdeb80305956150c380b76f4254dcfae3458928ce0", hash(recipe))
    }

    @Test fun fourTurnsRestoreCanonicalRecipeWithoutRebindingControlsOrDuplicate() {
        val binding = app.pristinepage.core.recipe.SourceBinding("a".repeat(64), app.pristinepage.core.pipeline.PixelSize(401, 301), "srgb-v1")
        val controls = app.pristinepage.core.pipeline.QuadPx(
            app.pristinepage.core.pipeline.PointPx(0.1, 0.1), app.pristinepage.core.pipeline.PointPx(0.9, 0.0),
            app.pristinepage.core.pipeline.PointPx(1.0, 1.0), app.pristinepage.core.pipeline.PointPx(0.0, 0.9),
        )
        val original = app.pristinepage.core.pipeline.Recipe(geometry = app.pristinepage.core.recipe.GeometryRecipe.Perspective(binding, controls))
        val duplicatedPageRecipe = original.copy()
        var draft = original
        for (turn in 1..4) {
            draft = draft.copy(geometry = draft.geometry.withOutputQuarterTurns((draft.geometry.outputQuarterTurns + 1) % 4))
            assertEquals(turn % 4, draft.geometry.outputQuarterTurns)
            assertEquals(binding, draft.geometry.source)
            assertEquals(controls, (draft.geometry as app.pristinepage.core.recipe.GeometryRecipe.Perspective).quad)
            assertEquals(0, duplicatedPageRecipe.geometry.outputQuarterTurns)
        }
        assertArrayEquals(encode(original), encode(draft))
        assertEquals(hash(original), hash(draft))
    }
}
