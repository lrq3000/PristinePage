package app.pristinepage.core

import app.pristinepage.core.geometry.AtelierEdgeDetector
import app.pristinepage.core.geometry.AtelierBoundaryVotes
import java.util.concurrent.CancellationException
import javax.imageio.ImageIO
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MagazineGeometryTest {
    @Test fun aSingleStrongContinuedSideCannotMakeAShadowIntoACap() {
        for (transpose in listOf(false, true)) {
            assertNull(fallback(if (transpose) 160 else 200, if (transpose) 200 else 160) { x, y ->
                val u = if (transpose) y else x
                val v = if (transpose) x else y
                if ((v < 42 && u < 32) || (v in 42..114 && u in 32..167)) 0x7c7c7c else 0x707070
            })
        }
    }

    @Test fun strongerInternalPanelCannotHideASupportedEnclosingPage() {
        assertNull(fallback(200, 160) { x, y ->
            when {
                x in 45..154 && y in 45..114 -> 0x707070
                x in 20..179 && y in 15..144 -> if (x < 100) 0x464646 else 0x8c8c8c
                x < 100 -> 0x3a3a3a
                else -> 0x989898
            }
        })
    }

    @Test fun aPrintedPanelEvenWithASharedTopEdgeCannotHideTheOtherThreePageEdges() {
        assertNull(fallback(200, 160) { x, y ->
            when {
                x in 45..154 && y in 15..114 -> 0x707070
                x in 20..179 && y in 15..144 -> if (x < 100) 0x464646 else 0x8c8c8c
                x < 100 -> 0x3a3a3a
                else -> 0x989898
            }
        })
    }

    @Test fun mixedContrastClippedPageMustNotUseAnInteriorShadowAsItsMissingSide() {
        assertNull(fallback(200, 160) { x, y ->
            when {
                x in 32..167 && y <= 114 -> if (y < 42) 0x747d81 else 0x92bbc8
                x < 100 -> 0xaaa6a1
                else -> 0x4c4a46
            }
        })
    }

    @Test fun crossingBandsAreNotEnoughToInventOneEnclosedPage() {
        assertNull(fallback(220, 170) { x, y ->
            val vertical = x in 44..173
            val horizontal = y in 31..136
            when {
                vertical && horizontal -> 0x6c9db8
                vertical -> 0xd9c6b8
                horizontal -> 0x4b423d
                else -> 0x818181
            }
        })
    }

    @Test fun independentlyColoredBackingIsNotARequirementOfTheMagazineFixture() {
        for (transpose in listOf(false, true)) {
            val width = if (transpose) 158 else 210
            val height = if (transpose) 210 else 158
            val corners = listOf(37 to 24, 176 to 22, 172 to 134, 36 to 137)
            val q = fallback(width, height) { x, y ->
                val u = if (transpose) y else x
                val v = if (transpose) x else y
                val onPage = corners.indices.all { i ->
                    val (a, b) = corners[i]
                    val (c, d) = corners[(i + 1) % 4]
                    (c - a) * (v - b) >= (d - b) * (u - a)
                }
                when {
                    onPage && u in 54..148 && v in 44..112 -> if ((u / 17 + v / 14) % 2 == 0) 0xe6dacf else 0x2b2024
                    onPage -> 0x678fab
                    v < 60 -> 0xdfdcd3
                    u < 136 -> 0x47494d
                    else -> 0x99938b
                }
            }
            val want = if (!transpose) corners else listOf(corners[0], corners[3], corners[2], corners[1]).map { (x, y) -> y to x }
            assertNotNull("Mixed-contrast closed boundary", q)
            for ((point, corner) in checkNotNull(q).points().zip(want)) {
                assertTrue("Expected $corner but received $point", hypot(point.x - corner.first, point.y - corner.second) <= 3.5)
            }
        }
    }

    @Test fun cancellationInterruptsEdgeVotingBeforeAnyQuadIsReturned() {
        val cancellation = CancellationException()
        var checks = 0
        val thrown = assertThrows(CancellationException::class.java) {
            AtelierBoundaryVotes.detect(200, 160, IntArray(200 * 160)) {
                // The first 81 checks fill the vertical scanlines. Interrupt the slope sweep itself.
                if (++checks == 87) throw cancellation
            }
        }
        assertSame(cancellation, thrown)
    }

    // Isolate the new last-resort route. The historical region path returns the center of these two adversarial controls.
    private fun fallback(w: Int, h: Int, pixel: (Int, Int) -> Int) =
        AtelierBoundaryVotes.detect(w, h, IntArray(w * h) { i -> pixel(i % w, i / w) }) {}

    @Test fun detectsTheApprovedFullBleedPageRatherThanTheFacingPageOrPrintedArt() {
        // Both test suites consume the same lossless asset. No copy can drift from the Android witness.
        val image = checkNotNull(javaClass.getResourceAsStream("/blue-hour-magazine.png")).use { ImageIO.read(it) }
        assertTrue(image.width == 2000 && image.height == 910)
        val previewWidth = 1024
        val previewHeight = 465
        val pixels = IntArray(previewWidth * previewHeight) { i ->
            val x = (i % previewWidth + .5) * image.width / previewWidth - .5
            val y = (i / previewWidth + .5) * image.height / previewHeight - .5
            val x0 = floor(x).toInt()
            val y0 = floor(y).toInt()
            val fx = x - x0
            val fy = y - y0
            val tl = image.getRGB(x0, y0)
            val tr = image.getRGB(x0 + 1, y0)
            val bl = image.getRGB(x0, y0 + 1)
            val br = image.getRGB(x0 + 1, y0 + 1)
            fun channel(shift: Int): Int {
                fun component(rgb: Int) = rgb ushr shift and 255
                val upper = (1 - fx) * component(tl) + fx * component(tr)
                val lower = (1 - fx) * component(bl) + fx * component(br)
                return ((1 - fy) * upper + fy * lower).roundToInt()
            }
            (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
        val dw = 384
        val dh = 174
        val quad = AtelierEdgeDetector.detectRgb(dw, dh) { x, y ->
            var red = 0
            var green = 0
            var blue = 0
            var count = 0
            for (row in y * previewHeight / dh until (y + 1) * previewHeight / dh) {
                for (column in x * previewWidth / dw until (x + 1) * previewWidth / dw) {
                    val rgb = pixels[row * previewWidth + column]
                    red += rgb ushr 16 and 255
                    green += rgb ushr 8 and 255
                    blue += rgb and 255
                    count++
                }
            }
            fun average(value: Int) = (value + count / 2) / count
            (average(red) shl 16) or (average(green) shl 8) or average(blue)
        }
        assertNotNull("The full-bleed page needs a supported automatic outline", quad)
        // Independently selected and visually approved corners, expressed in the unrotated source.
        val expected = listOf(400 to 80, 1478 to 60, 1500 to 853, 395 to 876)
        for ((index, pair) in checkNotNull(quad).points().zip(expected).withIndex()) {
            val (point, corner) = pair
            val x = point.x * (image.width - 1)
            val y = point.y * (image.height - 1)
            assertTrue("Corner $index: ($x,$y) vs $corner", hypot(x - corner.first, y - corner.second) <= 22)
        }
    }
}
