package app.pristinepage.core

import app.pristinepage.core.geometry.*
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.PointPx
import app.pristinepage.core.pipeline.QuadPx
import app.pristinepage.core.recipe.ContourSide
import org.junit.Assert.*
import org.junit.Test

class AtelierProjectiveContourTest {
    private val source = PixelSize(101, 91)
    private val output = PixelSize(81, 67)
    private val quad = QuadPx(p(12, 12), p(85, 15), p(74, 72), p(18, 70))

    @Test fun straightContourAndInsertedStraightKnotMatchTheSkewHomography() {
        val contour = AtelierContour.straight(quad)
        val inserted = contour.insert(ContourSide.TOP, 0, .39)
        val baseline = Homography.solve(quad, source, output)
        for (curve in listOf(contour, inserted)) {
            val map = AtelierProjectiveContour.prepare(curve, source, output)
            for (j in 0..10) for (i in 0..10) {
                val u = i / 10.0; val v = j / 10.0
                assertNear(baseline.map(u * 80, v * 66), map.map(u, v), 1e-10)
            }
        }
    }

    @Test fun cubicHandleDisplacementMovesTheActualEdgeAndItsInteriorWithoutChangingCorners() {
        val straight = AtelierContour.straight(quad)
        val initial = straight.top.segments.single().cubic.outgoing
        val curved = straight.moveHandle(ContourSide.TOP, 0, true, initial.copy(y = initial.y + 2.0))
        val map = AtelierProjectiveContour.prepare(curved, source, output)
        assertNear(quad.tl, map.map(0.0, 0.0), 1e-9)
        assertNear(quad.tr, map.map(1.0, 0.0), 1e-9)
        val base = Homography.solve(quad, source, output)
        val raw = base.map(40.0, 0.0)
        val t = (raw.x - quad.tl.x) / (quad.tr.x - quad.tl.x)
        assertEquals(6.0 * t * (1.0 - t) * (1.0 - t), map.map(.5, 0.0).y - raw.y, 1e-10)
        assertTrue(map.map(.5, .5).y > base.map(40.0, 33.0).y)
        val split = AtelierProjectiveContour.prepare(curved.insert(ContourSide.TOP, 0, .43), source, output)
        for (u in listOf(.1, .3, .5, .8)) assertNear(map.map(u, .4), split.map(u, .4), 1e-9)
    }

    @Test fun strongUnprovedBowIsRejectedRatherThanClippedOrApproximated() {
        val contour = AtelierContour.straight(quad).moveHandle(ContourSide.TOP, 0, true, p(35, 82))
        assertThrows(IllegalArgumentException::class.java) { AtelierProjectiveContour.prepare(contour, source, output) }
    }

    @Test fun fullFrameInwardBowDoesNotRequireMarginsOnUnchangedEdges() {
        val size = PixelSize(121, 91)
        val frame = QuadPx(p(0, 0), p(120, 0), p(120, 90), p(0, 90))
        val curved = AtelierContour.straight(frame).moveHandle(ContourSide.TOP, 0, true, p(40, 9))
        val map = AtelierProjectiveContour.prepare(curved, size, size)
        assertNear(PointPx(60.0, 3.375), map.map(.5, 0.0), 1e-10)
        assertNear(PointPx(60.0, 46.6875), map.map(.5, .5), 1e-10)
        assertNear(PointPx(60.0, 90.0), map.map(.5, 1.0), 1e-10)
        val preview = AtelierProjectiveContour.prepare(curved, size, PixelSize(61, 46))
        assertNear(map.map(.5, 0.0), preview.map(.5, 0.0), 1e-9)
        assertNear(map.map(.5, .5), preview.map(.5, .5), 1e-9)
    }

    @Test fun fullFrameOutwardBowStillRefusesInsteadOfClampingTheMap() {
        val size = PixelSize(121, 91)
        val frame = QuadPx(p(0, 0), p(120, 0), p(120, 90), p(0, 90))
        val curved = AtelierContour.straight(frame).moveHandle(ContourSide.TOP, 0, true, p(40, -9))
        assertThrows(IllegalArgumentException::class.java) { AtelierProjectiveContour.prepare(curved, size, size) }
    }

    @Test fun legalShortStraightSegmentDoesNotChangeDispatch() {
        val size = PixelSize(8, 8)
        val frame = QuadPx(p(0, 0), p(7, 0), p(7, 7), p(0, 7))
        val inserted = AtelierContour.straight(frame).insert(ContourSide.TOP, 0, .99998)
        val map = AtelierProjectiveContour.prepare(inserted, size, size)
        for (u in listOf(.125, .5, .99999)) assertEquals(PointPx(u * 7, 0.0), map.map(u, 0.0))
    }

    @Test fun aTinyRealHandleEditIsNotSilentlyDispatchedAsStraight() {
        val size = PixelSize(121, 91)
        val frame = QuadPx(p(0, 0), p(120, 0), p(120, 90), p(0, 90))
        val edited = AtelierContour.straight(frame).moveHandle(ContourSide.TOP, 0, true, PointPx(40.0, 1e-12))
        val map = AtelierProjectiveContour.prepare(edited, size, size)
        assertEquals(3.75e-13, map.map(.5, 0.0).y, 1e-24)
    }

    @Test fun injectiveButExtremelyThinCurvedCropFailsTheNormalizedStretchGate() {
        val size = PixelSize(4001, 4001)
        val frame = QuadPx(p(500, 1500), p(3500, 1500), p(3500, 1501), p(500, 1501))
        val curve = AtelierContour.straight(frame).moveHandle(ContourSide.TOP, 0, true, PointPx(1500.0, 1500.00001))
        assertThrows(InvalidInverseMapException::class.java) {
            AtelierProjectiveContour.prepare(curve, size, PixelSize(3001, 2))
        }
    }

    private fun p(x: Int, y: Int) = PointPx(x.toDouble(), y.toDouble())
    private fun assertNear(expected: PointPx, actual: PointPx, tolerance: Double) {
        assertEquals(expected.x, actual.x, tolerance)
        assertEquals(expected.y, actual.y, tolerance)
    }
}
