package app.pristinepage.image

import app.pristinepage.core.image.RgbImage
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Literal permutations and a separately generated tagged hash vector prevent a self-oracle. Input rows are already straight encoded-sRGB; this is not ICC or Android codec evidence. */
class CanonicalSourcePixelsTest {
    private fun normalizer(): Any = try { Class.forName("app.pristinepage.image.decode.CanonicalSourcePixels").getField("INSTANCE").get(null) }
    catch (_: ClassNotFoundException) { throw AssertionError("Canonical source pixel normalization is absent") }
    private fun call(name: String, vararg args: Any): Any = try {
        val target = normalizer(); target.javaClass.methods.single { it.name == name && it.parameterCount == args.size }.invoke(target, *args)!!
    } catch (failure: InvocationTargetException) { throw failure.targetException }
    private fun normalize(w: Int, h: Int, orientation: Int, pixels: IntArray, checkpoint: () -> Unit = {}): RgbImage {
        val reader: (Int, IntArray) -> Unit = { y, row -> pixels.copyInto(row, 0, y * w, (y + 1) * w) }
        return call("normalize", w, h, orientation, reader, checkpoint) as RgbImage
    }
    private fun hash(image: RgbImage, fingerprint: String): String = call("hash", image, fingerprint, {}) as String

    @Test fun everyExifCaseUsesTheDeclaredAsymmetricPermutationExactlyOnce() {
        val input = IntArray(6) { 0xff000000.toInt() or ((it + 1) * 0x010101) }
        val permutations = listOf(intArrayOf(1, 2, 3, 4, 5, 6), intArrayOf(3, 2, 1, 6, 5, 4), intArrayOf(6, 5, 4, 3, 2, 1), intArrayOf(4, 5, 6, 1, 2, 3), intArrayOf(1, 4, 2, 5, 3, 6), intArrayOf(4, 1, 5, 2, 6, 3), intArrayOf(6, 3, 5, 2, 4, 1), intArrayOf(3, 6, 2, 5, 1, 4))
        for (orientation in 1..8) normalize(3, 2, orientation, input).use { image ->
            assertEquals(if (orientation >= 5) 2 else 3, image.width); assertEquals(if (orientation >= 5) 3 else 2, image.height)
            val actual = image.toPackedByteArray().map { it.toInt() and 255 }
            assertEquals(permutations[orientation - 1].flatMap { listOf(it, it, it) }, actual)
        }
        assertArrayEquals(IntArray(6) { 0xff000000.toInt() or ((it + 1) * 0x010101) }, input)
    }

    @Test fun straightAlphaComposesOnWhiteWithExactIntegerRoundingAndRgbOrder() {
        val pixels = intArrayOf(0x00ff0040, 0x80402000.toInt(), 0xffff0000.toInt(), 0x01ffffff)
        normalize(4, 1, 1, pixels).use { image ->
            assertEquals(listOf(255, 255, 255, 159, 143, 127, 255, 0, 0, 255, 255, 255), image.toPackedByteArray().map { it.toInt() and 255 })
        }
    }

    @Test fun hashMatchesIndependentTaggedFullSourceVectorAndExcludesPadding() {
        val bytes = byteArrayOf(-1, 0, 0, -1, -1, -1)
        RgbImage(2, 1, 6, bytes).use { image ->
            assertEquals("d8af9f120ffc67cfb27c795a2c4ec4369ecec30ed377465a91270b01af94c497", hash(image, "test-srgb-straight-v1"))
            RgbImage(2, 1, 10, bytes + byteArrayOf(1, 2, 3, 4)).use { padded -> assertEquals(hash(image, "test-srgb-straight-v1"), hash(padded, "test-srgb-straight-v1")) }
            assertNotEquals(hash(image, "test-srgb-straight-v1"), hash(image, "another-conversion"))
            RgbImage(1, 2, 3, bytes).use { otherShape -> assertNotEquals(hash(image, "test-srgb-straight-v1"), hash(otherShape, "test-srgb-straight-v1")) }
        }
    }

    @Test fun normalizationRejectsInvalidMetadataBeforeRequestingAnyRow() {
        val reader: (Int, IntArray) -> Unit = { _, _ -> throw AssertionError("Invalid metadata reached row allocation/read") }
        for ((w, h, o) in listOf(Triple(0, 2, 1), Triple(12001, 1, 1), Triple(Int.MAX_VALUE, Int.MAX_VALUE, 1), Triple(2, 2, 9))) {
            assertThrows(IllegalArgumentException::class.java) { call("normalize", w, h, o, reader, {}) }
        }
    }

    @Test fun midRowCancellationPropagatesWithoutReturningPartialImage() {
        var checks = 0
        assertThrows(CancellationException::class.java) { normalize(3, 2, 6, IntArray(6) { -1 }) { if (++checks == 2) throw CancellationException("synthetic") } }
        assertEquals(2, checks)
    }
}
