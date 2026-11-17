package app.pristinepage.core

import app.pristinepage.core.image.ProtectionMask
import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.recipe.AppearanceRecipe
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import org.junit.Assert.*
import org.junit.Test

class StageBlendTest {
    private fun blend(input: Int, candidate: Double, strength: Double, protection: Float = 0f) = CoreApi.function("image.StageBlend", "channel", input, candidate, strength, protection)

    @Test fun clippedCandidatesAndFractionalProtectionAreBlendedExactlyOnce() {
        assertEquals(228, blend(200, 255.0, .5))
        assertEquals(211, blend(200, 245.0, .5, .5f))
        assertEquals(228, blend(200, 255.0, 1.0, .5f))
        assertEquals(101, blend(0, 100.5, 1.0))
    }

    @Test fun binary64HalfTiesDoNotUseFloatScratchFmaOrReassociation() {
        assertEquals(4, blend(3, 8.0, .1))
        // Independent exact-rational IEEE-754 boundary calculations: FMA/reassociation would cross these half ties.
        assertEquals(2, blend(1, 3.0, 0.7499999999999999))
        assertEquals(3, blend(1, 5.0, 0.37499999999999994))
        assertEquals(4, blend(1, 6.0, .7))
        assertEquals(128, blend(0, 255.0, .5))
    }

    @Test fun exactOffAndFullyProtectedSamplesCopyUnsignedInput() {
        for (input in listOf(0, 3, 128, 255)) {
            assertEquals(input, blend(input, 17.25, 0.0, .25f))
            assertEquals(input, blend(input, 17.25, 1.0, 1f))
        }
    }

    @Test fun invalidFiniteRangesAndNonfiniteInputsRejectEvenOnBypass() {
        CoreApi.type("image.StageBlend")
        for (input in listOf(-1, 256)) assertThrows(IllegalArgumentException::class.java) { blend(input, 0.0, 0.0) }
        for (bad in listOf(-.01, 255.01, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { blend(200, bad, 0.0, 1f) }
        }
        for (bad in listOf(-.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY)) assertThrows(IllegalArgumentException::class.java) { blend(200, 245.0, bad, 1f) }
        for (bad in listOf(-.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY)) assertThrows(IllegalArgumentException::class.java) { blend(200, 245.0, 0.0, bad) }
    }
}

/** Test-only reflection preserves an assertion RED while production classes are absent; helper access does not add a production debug API. */
internal object AppearanceTestApi {
    fun apply(name: String, input: RgbImage, recipe: AppearanceRecipe, mask: ProtectionMask? = null, checkpoint: () -> Unit = {}): RgbImage = CoreApi.function("image.$name", "apply", input, recipe, mask, checkpoint) as RgbImage
    fun bytes(name: String, width: Int, height: Int): Long = CoreApi.function("image.$name", "workspaceBytes", width, height) as Long
    fun helper(type: String, name: String, vararg args: Any?): Any? {
        val owner = CoreApi.type("image.$type")
        val method = owner.declaredMethods.single { (it.name == name || it.name.startsWith(name + "$")) && it.parameterCount == args.size }
        method.isAccessible = true
        return try { method.invoke(if (Modifier.isStatic(method.modifiers)) null else owner.getField("INSTANCE").get(null), *args) } catch (error: InvocationTargetException) { throw error.targetException }
    }
    fun neutral(width: Int, height: Int, values: IntArray): RgbImage = RgbImage(width, height, width * 3, ByteArray(values.size * 3) { values[it / 3].toByte() })
    fun assertNeutral(expected: IntArray, image: RgbImage) { assertArrayEquals(ByteArray(expected.size * 3) { expected[it / 3].toByte() }, image.toPackedByteArray()) }
    fun liveLeases(image: RgbImage): Int {
        val storage = RgbImage::class.java.getDeclaredField("storage").also { it.isAccessible = true }.get(image)
        return (storage.javaClass.getDeclaredField("references").also { it.isAccessible = true }.get(storage) as java.util.concurrent.atomic.AtomicInteger).get()
    }
}
