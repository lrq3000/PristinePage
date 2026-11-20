package app.pristinepage.core

import app.pristinepage.core.geometry.AtelierEdgeDetector
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

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

}
