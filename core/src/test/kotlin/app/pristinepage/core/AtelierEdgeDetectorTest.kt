package app.pristinepage.core

import app.pristinepage.core.geometry.AtelierEdgeDetector
import app.pristinepage.core.geometry.AtelierRegionOutline
import app.pristinepage.core.pipeline.QuadPx
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class AtelierEdgeDetectorTest {
    @Test fun locatesSkewedPaperRatherThanReturningFullFrame() {
        val result = AtelierEdgeDetector.detect(120, 100) { x, y ->
            val paper = x >= 16 + y / 16 && x <= 104 - y / 14 && y in 14..85
            if (paper && x in 40..74 && y in listOf(37, 52, 66)) 32 else if (paper) 229 else 48
        } ?: error("No supported paper contour")
        assertTrue(abs(result.tl.x - 17.0 / 119) < .025)
        assertTrue(abs(result.tr.x - 103.0 / 119) < .025)
        assertTrue(abs(result.bl.x - 21.0 / 119) < .025)
        assertTrue(abs(result.tl.y - 14.0 / 99) < .025)
        assertTrue(abs(result.br.y - 85.0 / 99) < .025)
    }

    @Test fun flatFrameAndASingleDividerAreExplicitNoProposals() {
        assertNull(AtelierEdgeDetector.detect(120, 100) { _, _ -> 180 })
        assertNull(AtelierEdgeDetector.detect(120, 100) { x, _ -> if (x < 50) 40 else 210 })
    }

    @Test fun diffuseLowContrastPaperHasAUsableQuadrilateral() {
        verifyLowContrast(inverted = false)
        verifyLowContrast(inverted = true)
    }

    @Test fun straightBackgroundRulesDoNotReplaceThePaperOutline() {
        val result = AtelierEdgeDetector.detect(160, 120) { x, y ->
            when {
                x in 29..129 && y in 21..98 -> 162
                x in 7..9 || x in 149..151 || y in 6..8 || y in 109..111 -> 40
                else -> 141
            }
        } ?: error("Expected the coherent paper outline, not the background rules")
        assertEquals(28.5 / 159, result.tl.x, .02)
        assertEquals(20.5 / 119, result.tl.y, .02)
        assertEquals(129.5 / 159, result.br.x, .02)
        assertEquals(98.5 / 119, result.br.y, .02)
    }

    @Test fun diffuseShadingAndClippedSheetDoNotInventMissingEdges() {
        assertNull(AtelierEdgeDetector.detect(160, 120) { x, y -> 90 + x / 2 + y / 3 })
        assertNull(AtelierEdgeDetector.detect(160, 120) { x, y ->
            if (x <= 130 && y in 21..98) 158 else 136
        })
    }

    @Test fun pairedInteriorRulesDoNotBecomeTheMissingEdgeOfAClippedSheet() {
        for ((ruleEnd, secondRule) in listOf(21 to 24..25, 22 to 24..25, 23 to 25..26)) {
            assertNull("Paper beyond the rules is not outside the sheet", AtelierEdgeDetector.detect(160, 120) { x, y ->
                if (x <= 130 && y in 21..98) {
                    if (x in 20..ruleEnd || x in secondRule) 24 else 162
                } else 141
            })
        }
    }

    @Test fun fullyVisibleHighContrastPageCanBeCloseToTheFrame() {
        val q = checkNotNull(AtelierEdgeDetector.detect(160, 120) { x, y ->
            if (x in 4..155 && y in 21..98) 220 else 40
        })
        assertEquals(3.5 / 159, q.tl.x, .01)
        assertEquals(155.5 / 159, q.br.x, .01)
    }

    @Test fun isolatedStraightRuleCannotSuppressAWeakerPageEdge() {
        for (ruleEnd in 16..17) {
            val q = checkNotNull(AtelierEdgeDetector.detect(160, 120) { x, y ->
                if (x in 29..129 && y in 36..84) 162 else if (x in 14..ruleEnd) 40 else 141
            })
            assertEquals(28.5 / 159, q.tl.x, .02)
            assertEquals(35.5 / 119, q.tl.y, .02)
        }
    }

    @Test fun detectionCanBeCancelledAfterItSamplesThePreview() {
        val cancellation = java.util.concurrent.CancellationException()
        var checks = 0
        val thrown = assertThrows(java.util.concurrent.CancellationException::class.java) {
            AtelierEdgeDetector.detect(160, 120, { if (++checks == 121) throw cancellation }) { _, _ -> 120 }
        }
        assertSame(cancellation, thrown)
    }

    @Test fun findsBothEndsOfFullyVisibleNarrowReceiptsEvenOffCenterOrTurned() {
        for ((left, right) in listOf(58 to 99, 22 to 46)) for (turn in listOf(false, true)) {
            val w = if (turn) 120 else 160
            val h = if (turn) 160 else 120
            val result = AtelierEdgeDetector.detect(w, h) { x, y ->
                val across = if (turn) y else x
                val along = if (turn) x else y
                when {
                    across in left + 4..right - 4 && along in listOf(22, 41, 59, 77, 95) -> 33
                    across in left..right && along in 9..110 -> 200
                    else -> 60
                }
            } ?: throw AssertionError("Missing a fully visible receipt: left=$left, right=$right, turned=$turn")
            val expected = if (turn) listOf(8.5 to left - .5, 110.5 to left - .5, 110.5 to right + .5, 8.5 to right + .5)
            else listOf(left - .5 to 8.5, right + .5 to 8.5, right + .5 to 110.5, left - .5 to 110.5)
            for ((point, xy) in result.points().zip(expected)) {
                assertEquals(xy.first, point.x * (w - 1), 1.0)
                assertEquals(xy.second, point.y * (h - 1), 1.0)
            }
        }
    }

    @Test fun doesNotTreatAPrintedLineAsTheMissingEndOfANarrowReceipt() {
        assertNull(AtelierEdgeDetector.detect(160, 120) { x, y ->
            when {
                x !in 22..46 || y > 110 -> 60
                y in 18..19 || y in 22..24 -> 30
                else -> 200
            }
        })
    }

    @Test fun doesNotBridgeTwoSeparateNarrowObjectsIntoOneReceipt() {
        assertNull(AtelierEdgeDetector.detect(160, 120) { x, y ->
            if ((x in 22..29 || x in 39..46) && y in 9..110) 200 else 60
        })
    }

    @Test fun aShadowCannotSupplyTheMissingEndWhenBothVisibleSidesContinue() {
        for (turn in listOf(false, true)) {
            assertNull(AtelierEdgeDetector.detect(if (turn) 120 else 160, if (turn) 160 else 120) { x, y ->
                val across = if (turn) y else x
                val along = if (turn) x else y
                when {
                    across !in 22..46 || along > 110 -> 60
                    across in 26..41 && along in 8..10 -> 70
                    along <= 24 -> 92
                    else -> 200
                }
            })
        }
    }

    @Test fun aSustainedThreePixelBackgroundGapCannotBecomeOneReceipt() {
        assertNull(AtelierEdgeDetector.detect(160, 120) { x, y ->
            if ((x in 22..32 || x in 36..46) && y in 9..110) 200 else 60
        })
    }

    @Test fun findsAShortPageEvenWhenItDoesNotCrossEitherImageMidline() {
        val corners = listOf(18 to 19, 78 to 23, 75 to 68, 19 to 64)
        val q = AtelierEdgeDetector.detect(200, 160) { x, y ->
            if (!inside(corners, x, y)) 72 else if (x in 30..58 && y % 9 == 0) 32 else 195
        }
        assertCorners(corners, q)
    }

    @Test fun findsAnObliquePageWithoutMakingTheScanAxesThePageAxes() {
        val corners = listOf(87 to 9, 168 to 82, 110 to 148, 29 to 75)
        val q = AtelierEdgeDetector.detect(200, 160) { x, y -> if (inside(corners, x, y)) 195 else 72 }
        assertCorners(corners, q)
    }

    @Test fun twoSeparatePlausiblePagesDoNotBecomeAnArbitrarySuggestion() {
        val q = AtelierEdgeDetector.detect(200, 160) { x, y ->
            if ((x in 16..74 || x in 128..181) && y in 18..70) 195 else 72
        }
        assertNull(q)
    }

    @Test fun aShortOffCenterPageCutOffByTheSourceIsNotGuessed() {
        assertNull(AtelierEdgeDetector.detect(200, 160) { x, y -> if (x in 0..75 && y in 19..66) 195 else 72 })
    }

    @Test fun aRoundObjectIsNotApproximatedAsFourPageSides() {
        assertNull(AtelierEdgeDetector.detect(200, 160) { x, y ->
            if ((x - 52) * (x - 52) + (y - 55) * (y - 55) <= 34 * 34) 195 else 72
        })
    }

    @Test fun colorBoundaryFindsAPageInvisibleToTheLuminanceDetector() {
        val corners = listOf(52 to 24, 143 to 27, 140 to 100, 56 to 97)
        // Independent rounded luma of both RGB triplets is 124 with the established 77/150/29 weights.
        fun color(x: Int, y: Int) = if (inside(corners, x, y)) 0xc26731 else 0x657eac
        assertNull(AtelierEdgeDetector.detect(200, 160) { _, _ -> 124 })
        assertCorners(corners, AtelierEdgeDetector.detectRgb(200, 160) { x, y -> color(x, y) })
    }

    @Test fun aColoredSingleDividerDoesNotSupplyFourBorders() {
        assertNull(AtelierEdgeDetector.detectRgb(200, 160) { x, _ -> if (x < 87) 0xc26731 else 0x657eac })
    }

    @Test fun unrelatedStrongOpponentColorOutlinesAreAmbiguous() {
        // Each rectangle contrasts against the same backdrop in one opponent channel. All three have luma 124.
        assertNull(AtelierEdgeDetector.detectRgb(200, 160) { x, y ->
            when {
                x in 15..79 && y in 20..75 -> 0xaf627c
                x in 120..181 && y in 82..137 -> 0x7c8b2e
                else -> 0x7c7c7c
            }
        })
    }

    @Test fun aShadowAcrossAClippedPageCannotSupplyItsMissingEdgeEvenIfThePolarityReverses() {
        for (transpose in listOf(false, true)) {
            assertNull(AtelierEdgeDetector.detect(if (transpose) 160 else 200, if (transpose) 200 else 160) { x, y ->
                val (across, along) = if (transpose) y to x else x to y
                when {
                    across !in 20..76 || along > 72 -> 100
                    along < 25 -> 60
                    else -> 210
                }
            })
        }
    }

    @Test fun aNewLuminanceRegionDoesNotHideASecondColorOnlyPage() {
        fun scene(gray: Boolean, color: Boolean) = AtelierEdgeDetector.detectRgb(200, 160) { x, y ->
            when {
                gray && x in 16..74 && y in 18..70 -> 0xc3c3c3
                color && x in 128..181 && y in 18..70 -> 0xc26731
                else -> 0x7c7c7c
            }
        }
        assertCorners(listOf(16 to 18, 74 to 18, 74 to 70, 16 to 70), scene(gray = true, color = false))
        assertCorners(listOf(128 to 18, 181 to 18, 181 to 70, 128 to 70), scene(gray = false, color = true))
        assertNull(scene(gray = true, color = true))
    }

    @Test fun aShadowCannotCutOffAClippedPageOnATwoToneBackground() {
        for (transposed in listOf(false, true)) {
            assertNull(AtelierEdgeDetector.detect(if (transposed) 160 else 200, if (transposed) 200 else 160) { x, y ->
                val across = if (transposed) y else x
                val along = if (transposed) x else y
                when {
                    across in 20..76 && along < 25 -> 60
                    across in 20..76 && along <= 72 -> 210
                    across < 79 -> 100
                    else -> 40
                }
            })
        }
    }

    @Test fun cancellationInterruptsABoundedRegionTraversalBeforePublishingAnOutline() {
        val w = 160
        val h = 120
        val pixels = IntArray(w * h) { index ->
            if (index % w in 8..107 && index / w in 4..103) 207 else 52
        }
        val cancellation = java.util.concurrent.CancellationException()
        var checks = 0
        // This budget covers the histogram's 75 checks and the first four scan rows, then stops inside the filled component.
        val thrown = assertThrows(java.util.concurrent.CancellationException::class.java) {
            AtelierRegionOutline.Workspace(w, h).proposals(pixels, {
                if (++checks == 90) throw cancellation
            }) { _, _ -> throw AssertionError("Cancelled work cannot reach a valid proposal") }
        }
        assertSame(cancellation, thrown)
    }

    private fun inside(corners: List<Pair<Int, Int>>, x: Int, y: Int): Boolean = corners.indices.all { i ->
        val (ax, ay) = corners[i]
        val (bx, by) = corners[(i + 1) % corners.size]
        (bx - ax) * (y - ay) >= (by - ay) * (x - ax)
    }

    private fun assertCorners(corners: List<Pair<Int, Int>>, q: QuadPx?) {
        assertNotNull("No crop suggested for a fully visible page", q)
        for ((point, expected) in checkNotNull(q).points().zip(corners)) {
            assertTrue("Expected labeled corner $expected, received ${point.x * 199} × ${point.y * 159}",
                hypot(point.x * 199 - expected.first, point.y * 159 - expected.second) <= 3.0)
        }
    }

    private fun verifyLowContrast(inverted: Boolean) {
        val result = AtelierEdgeDetector.detect(160, 120) { x, y ->
            val left = 20.0 + y / 10.0
            val right = 144.0 - y / 10.0
            val distance = minOf(x - left, right - x, y - 21.0, 97.0 - y)
            val contrast = ((distance + 2) / 4).coerceIn(0.0, 1.0)
            val value = 122 + x / 15 + (24 * contrast).toInt()
            if (inverted) 255 - value else value
        } ?: error("Expected a coherent low-contrast sheet, inverted=$inverted")
        assertEquals(22.1 / 159, result.tl.x, .02)
        assertEquals(141.9 / 159, result.tr.x, .02)
        assertEquals(29.7 / 159, result.bl.x, .02)
        assertEquals(21.0 / 119, result.tl.y, .02)
        assertEquals(97.0 / 119, result.br.y, .02)
    }
}
