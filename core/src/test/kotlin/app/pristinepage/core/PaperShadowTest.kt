package app.pristinepage.core

import app.pristinepage.core.image.PaperShadow
import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.recipe.AppearanceRecipe
import org.junit.Assert.*
import org.junit.Test

class PaperShadowTest {
    @Test fun correctsAHardTintedShadowWithoutChangingTheLitSideOrClosingAFaintStroke() {
        for (turned in listOf(false, true)) for (darkFirst in listOf(false, true)) {
            image(if (turned) 112 else 192, if (turned) 192 else 112) { x, y ->
                val p = if (turned) y else x
                val q = if (turned) x else y
                val dark = (p < 81) == darkFirst
                val page = if (dark) intArrayOf(152, 142, 133) else intArrayOf(224, 218, 210)
                when {
                    p in 60..99 && q == 56 -> page.map { it - 11 }.toIntArray()
                    p in (if (darkFirst) 22..30 else 128..136) && q in 43..47 -> intArrayOf(165, 50, 64)
                    else -> page
                }
            }.use { input ->
                PaperShadow.apply(input, AppearanceRecipe.PaperShadowV1()).use { result ->
                    fun pixel(p: Int, q: Int) = if (turned) rgb(result, q, p) else rgb(result, p, q)
                    val lit = intArrayOf(224, 218, 210)
                    for (p in 74..88) assertArrayEquals("paper boundary at $p", lit, pixel(p, 36))
                    for (p in listOf(65, 95)) assertArrayEquals(intArrayOf(213, 207, 199), pixel(p, 56))
                    assertArrayEquals(intArrayOf(165, 50, 64), pixel(if (darkFirst) 26 else 132, 45))
                }
            }
        }
    }

    @Test fun decliningEvidenceLeavesUniformAndGradualPaperByteIdentical() {
        for (gradient in listOf(false, true)) image(192, 112) { x, _ ->
            val v = if (gradient) 122 + x / 2 else 212
            intArrayOf(v, v - 4, v - 8)
        }.use { source ->
            PaperShadow.apply(source, AppearanceRecipe.PaperShadowV1()).use {
                assertArrayEquals(source.toPackedByteArray(), it.toPackedByteArray())
            }
        }
    }

    @Test fun correctionCannotEraseOrInvertAFaintNeutralHighlight() {
        for (mark in listOf(15, 19)) image(192, 112) { x, y ->
            val v = (if (x < 81) 140 else 220) + if (x in 60..99 && y == 56) mark else 0
            intArrayOf(v, v, v)
        }.use { source ->
            PaperShadow.apply(source, AppearanceRecipe.PaperShadowV1()).use { result ->
                assertEquals(220, result.getUnsigned(74, 55, 0))
                assertTrue("The brighter mark must remain brighter on the corrected side", result.getUnsigned(74, 56, 0) >= 224)
                assertEquals(220 + mark, result.getUnsigned(95, 56, 0))
            }
        }
    }

    @Test fun aStepInTheShadowBoundaryCannotLeaveABlockArtifact() {
        for (shiftedLevel in listOf(140, 153)) {
            image(192, 112) { x, y ->
                val edge = if (y < 90) 81 else 112
                val dark = if (y < 90) 140 else shiftedLevel
                val v = if (x < edge) dark else 220
                intArrayOf(v, v, v)
            }.use { source ->
                PaperShadow.apply(source, AppearanceRecipe.PaperShadowV1()).use { result ->
                    assertArrayEquals(source.toPackedByteArray(), result.toPackedByteArray())
                }
            }
        }
    }

    @Test fun tintAffinityDoesNotReversePaperLikeLightMarks() {
        image(192, 112) { x, y ->
            val paper = if (x < 81) 140 else 220
            when {
                y == 56 && x in 60..99 -> intArrayOf(paper + 12, paper + 4, paper)
                else -> intArrayOf(paper, paper, paper)
            }
        }.use { source ->
            PaperShadow.apply(source, AppearanceRecipe.PaperShadowV1()).use { result ->
                assertArrayEquals(intArrayOf(220, 220, 220), rgb(result, 74, 55))
                val (r, g, b) = rgb(result, 74, 56)
                assertTrue("A mark lighter than the shaded paper must still be lighter", (77 * r + 150 * g + 29 * b) / 256 >= 223)
            }
        }
    }

    @Test fun declinesIfAChromaticHighlightWouldBecomeDarkerThanThePaper() {
        image(192, 112) { x, y ->
            val paper = if (x < 81) 140 else 220
            if (x in 60..70 && y == 56) intArrayOf(222, 140, 140) else intArrayOf(paper, paper, paper)
        }.use { source ->
            PaperShadow.apply(source, AppearanceRecipe.PaperShadowV1()).use { result ->
                assertArrayEquals(source.toPackedByteArray(), result.toPackedByteArray())
            }
        }
    }

    private fun rgb(image: RgbImage, x: Int, y: Int) = IntArray(3) { image.getUnsigned(x, y, it) }

    private fun image(w: Int, h: Int, at: (Int, Int) -> IntArray): RgbImage {
        val bytes = ByteArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val rgb = at(x, y)
            for (c in 0..2) bytes[3 * (y * w + x) + c] = rgb[c].toByte()
        }
        return RgbImage(w, h, 3 * w, bytes)
    }
}
