package app.pristinepage.core

import app.pristinepage.core.image.DocumentColor
import app.pristinepage.core.image.ProtectionMask
import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.recipe.AppearanceRecipe
import org.junit.Assert.*
import org.junit.Test

class DocumentColorTest {
    @Test fun smoothShadowDoesNotAcquireAFalseEdge() {
        val row = ByteArray(256 * 3) { i -> (70 + (i / 3) / 2 + 5 * (1 - i % 3)).toByte() }
        RgbImage(256, 1, row.size, row).use { input ->
            DocumentColor.apply(input, AppearanceRecipe.DocumentColorV1()).use { result ->
                for (x in 1 until 256) for (c in 0..2) {
                    val left = result.getUnsigned(x - 1, 0, c)
                    val right = result.getUnsigned(x, 0, c)
                    assertTrue("Artificial edge at column $x, channel $c: $left to $right", kotlin.math.abs(right - left) <= 5)
                }
            }
        }
    }

    @Test fun saturatedChannelDoesNotCreateAnEdgeWhenTheDirectionOfItsCorrectionChanges() {
        val row = ByteArray(128 * 3) { i ->
            when (i % 3) {
                0 -> 255
                1 -> 170 + (i / 3) / 4
                else -> 159
            }.toByte()
        }
        RgbImage(128, 1, row.size, row).use { input ->
            DocumentColor.apply(input, AppearanceRecipe.DocumentColorV1()).use { result ->
                for (c in 0..2) assertEquals("Unchanged input at columns 48 and 49, channel $c",
                    result.getUnsigned(48, 0, c), result.getUnsigned(49, 0, c))
            }
        }
    }

    @Test fun nearIdenticalDarkPagesDoNotReceiveAbruptlyDifferentCorrection() {
        fun corrected(level: Int): Int =
            RgbImage(4, 1, 12, ByteArray(12) { level.toByte() }).use { input ->
                DocumentColor.apply(input, AppearanceRecipe.DocumentColorV1()).use { it.getUnsigned(0, 0, 0) }
            }
        val darker = corrected(127)
        val lighter = corrected(128)
        assertTrue("Abrupt exposure change: $darker to $lighter", lighter - darker in 0..5)
    }

    @Test fun narrowRedChannelHeadroomDoesNotEraseAFaintMark() {
        val bytes = byteArrayOf(-3, -92, -105, -3, -92, -105, -3, -92, -105, -13, -92, -105)
        RgbImage(4, 1, 12, bytes).use { input ->
            DocumentColor.apply(input, AppearanceRecipe.DocumentColorV1()).use { result ->
                val separation = result.getUnsigned(0, 0, 0) - result.getUnsigned(3, 0, 0)
                assertTrue("Retain the red-channel stroke, not a new hue", separation in 8..12)
                for (c in 1..2) assertEquals(result.getUnsigned(0, 0, c), result.getUnsigned(3, 0, c))
            }
        }
    }

    @Test fun locallyNormalizesTintedPaperWithoutWashingOutInk() {
        val faintInk = intArrayOf(132, 122, 107)
        val colorInk = intArrayOf(173, 62, 42)
        val paper = intArrayOf(188, 173, 151)
        val source = ByteArray(96 * 64 * 3) { i ->
            val x = i / 3 % 96
            val y = i / 3 / 96
            when {
                x in 19..34 && y in 27..32 -> (faintInk[i % 3] + x / 2).toByte()
                x in 48..57 && y in 32..38 -> colorInk[i % 3].toByte()
                else -> (paper[i % 3] + x / 2).toByte()
            }
        }
        RgbImage(96, 64, 288, source).use { input ->
            DocumentColor.apply(input, AppearanceRecipe.DocumentColorV1()).use { result ->
                fun rgb(x: Int, y: Int) = IntArray(3) { result.getUnsigned(x, y, it) }
                val shaded = rgb(5, 15)
                val lit = rgb(87, 15)
                assertTrue("paper remains bright on both sides", (shaded + lit).all { it in 218..248 })
                assertTrue("cast reduced in the shadow", shaded.max() - shaded.min() <= 11)
                assertTrue("page illumination is more uniform", kotlin.math.abs(lit.average() - shaded.average()) < 15)
                assertArrayEquals(colorInk, rgb(52, 35))
                assertTrue("faint stroke remains distinct from nearby paper", rgb(25, 15).average() - rgb(25, 29).average() >= 37)
            }
        }
    }

    @Test fun disabledProtectedAndDarkFlatInputsRetainExactBytes() {
        val bytes = ByteArray(4 * 4 * 3) { intArrayOf(46, 60, 52)[it % 3].toByte() }
        RgbImage(4, 4, 12, bytes).use { input ->
            DocumentColor.apply(input, AppearanceRecipe.DocumentColorV1()).use { result ->
                assertArrayEquals(bytes, result.toPackedByteArray())
            }
        }
        val light = ByteArray(bytes.size) { (150 + 18 * (it % 3)).toByte() }
        RgbImage(4, 4, 12, light).use { input ->
            DocumentColor.apply(input, AppearanceRecipe.DocumentColorV1(strength = 0.0)).use { result ->
                assertArrayEquals(light, result.toPackedByteArray())
            }
            ProtectionMask(4, 4, FloatArray(16) { 1f }).use { mask ->
                DocumentColor.apply(input, AppearanceRecipe.DocumentColorV1(), mask).use { result ->
                    assertArrayEquals(light, result.toPackedByteArray())
                }
            }
        }
    }
}
