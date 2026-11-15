package app.pristinepage.data

import app.pristinepage.core.pipeline.FallbackPolicy
import app.pristinepage.core.pipeline.OutputSpec
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.core.pipeline.RenderPurpose
import app.pristinepage.core.pipeline.RenderRequest
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.SourceBinding
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test

/** STATE-03: a fallback policy is a pixel/execution decision, not transient scheduler state. The real key builder must segregate its jobs before execution, when no execution hash exists yet. */
class RenderJobIdentityTest {
    private val sourceHash = "a".repeat(64)
    private val source = SourceBinding(sourceHash, PixelSize(3, 2), "srgb-v1")
    private val recipe = Recipe(geometry = GeometryRecipe.None(source))
    private val request = RenderRequest("180aab39-4036-4979-922e-e91a1ded30c4", sourceHash, "9b58d249-0641-4cb7-bbe7-ff805098b21b", 1, recipe, OutputSpec(PixelSize(3, 2), RenderPurpose.EXPORT), 8, null, FallbackPolicy.FAIL)

    private fun identity(request: RenderRequest = this.request, masks: Map<String, String> = emptyMap(), assets: String = "b".repeat(64), conversion: String = "srgb-v1", renderer: String = "renderer-v1", encoding: String = "rgb8-v1", backend: String = "reference", precision: String = "binary64"): Any {
        val type = try { Class.forName("app.pristinepage.data.model.RenderJobIdentity") } catch (error: ClassNotFoundException) { throw AssertionError("Canonical persistent render-job identity is absent", error) }
        return try { type.methods.single { it.name == "create" }.invoke(null, request, masks, assets, conversion, renderer, encoding, backend, precision)!! }
        catch (error: InvocationTargetException) { throw error.targetException }
    }
    private fun get(target: Any, name: String): Any = target.javaClass.getMethod(name).invoke(target)!!
    private fun key(identity: Any): String = get(identity, "getIdempotenceKey") as String
    private fun bytes(identity: Any): ByteArray = get(identity, "getCanonicalInput") as ByteArray

    @Test fun fallbackPoliciesHaveDistinctJobsAndPolicyScopedRetries() {
        val fail = identity(); val keep = identity(request.copy(fallback = FallbackPolicy.KEEP_STAGE_INPUT))
        assertNotEquals(key(fail), key(keep))
        assertFalse(bytes(fail).contentEquals(bytes(keep)))
        assertEquals(key(fail), key(identity()))
        assertEquals(key(keep), key(identity(request.copy(fallback = FallbackPolicy.KEEP_STAGE_INPUT))))
        assertTrue(key(fail).matches(Regex("page-render-v1:[0-9a-f]{64}")))
    }

    @Test fun transientGenerationAndEquivalentSourceOwnersCannotCreateNewRenderJobs() {
        val late = request.copy(generation = 9, sourceId = "39e7bf93-1337-4a3a-8dd0-1b7c3a7b695b")
        assertArrayEquals(bytes(identity()), bytes(identity(late)))
        assertEquals(key(identity()), key(identity(late)))
    }

    @Test fun everyPixelAffectingInputSeparatesJobs() {
        val keys = listOf(
            identity(),
            identity(request.copy(recipeRevision = 2)),
            identity(request.copy(pageId = "39e7bf93-1337-4a3a-8dd0-1b7c3a7b695b")),
            identity(request.copy(recipe = recipe.copy(geometry = recipe.geometry.withOutputQuarterTurns(2)))),
            identity(request.copy(output = OutputSpec(PixelSize(2, 2), RenderPurpose.EXPORT))),
            identity(masks = mapOf("protection" to "c".repeat(64)), request = request.copy(protectionMaskId = "39e7bf93-1337-4a3a-8dd0-1b7c3a7b695b")),
            identity(assets = "c".repeat(64)),
            identity(renderer = "renderer-v2"), identity(encoding = "rgb8-v2"),
            identity(backend = "platform"), identity(precision = "float32"),
        ).map(::key)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun canonicalInputIsOwnedAndMapInsertionOrderDoesNotChangeIdentity() {
        val marked = request.copy(protectionMaskId = "39e7bf93-1337-4a3a-8dd0-1b7c3a7b695b")
        val a = identity(marked, linkedMapOf("protection" to "c".repeat(64), "coverage" to "d".repeat(64)))
        val b = identity(marked, linkedMapOf("coverage" to "d".repeat(64), "protection" to "c".repeat(64)))
        val before = bytes(a); val exported = bytes(a); exported.fill(0)
        assertArrayEquals(before, bytes(a))
        assertArrayEquals(before, bytes(b)); assertEquals(key(a), key(b))
        assertEquals(key(a).removePrefix("page-render-v1:"), get(a, "getCanonicalInputSha256"))
    }

    @Test fun inconsistentBindingAndUnversionedOrMalformedInputsRejectBeforeJobReuse() {
        for (bad in listOf(request.copy(recipeRevision = 0), request.copy(sourcePixelHash = "c".repeat(64)), request.copy(pageId = "../other"))) {
            assertThrows(IllegalArgumentException::class.java) { identity(bad) }
        }
        assertThrows(IllegalArgumentException::class.java) { identity(assets = "invalid") }
        assertThrows(IllegalArgumentException::class.java) { identity(renderer = "") }
        assertThrows(IllegalArgumentException::class.java) { identity(conversion = "another-conversion") }
        assertThrows(IllegalArgumentException::class.java) { identity(masks = mapOf("protection" to "c".repeat(64))) }
        assertThrows(IllegalArgumentException::class.java) { identity(request.copy(protectionMaskId = "39e7bf93-1337-4a3a-8dd0-1b7c3a7b695b")) }
    }
}
