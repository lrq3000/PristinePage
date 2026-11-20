package app.pristinepage.core

import app.pristinepage.core.pipeline.PointPx
import app.pristinepage.core.geometry.AtelierCubic
import org.junit.Assert.*
import org.junit.Test

class AtelierCubicTest {
    @Test fun splittingACubicPreservesItsContourAndProducesTwoRealHandles() {
        val curve = AtelierCubic(PointPx(0.0, 0.0), PointPx(12.0, 45.0), PointPx(39.0, -24.0), PointPx(64.0, 7.0))
        val (first, second) = curve.split(.37)
        for (i in 0..50) {
            val t = i / 50.0
            val actual = if (t <= .37) first.pointAt(t / .37) else second.pointAt((t - .37) / .63)
            val wanted = curve.pointAt(t)
            assertEquals(wanted.x, actual.x, 1e-11)
            assertEquals(wanted.y, actual.y, 1e-11)
        }
        assertEquals(first.pointAt(1.0), second.pointAt(0.0))
        assertEquals(27.125, curve.pointAt(.5).x, 1e-12)
        assertEquals(8.75, curve.pointAt(.5).y, 1e-12)
    }

}
