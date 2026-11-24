package app.pristinepage.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class CorrectionPreviewZoomTest {
    @Test fun fittedPreviewCannotPanAndPinchingBackToFitResetsOffset() {
        val fit = CorrectionPreviewZoom()
        assertEquals(fit, fit.transform(1f, 100f, -30f, 200, 200, 200, 100))
        val zoomed = fit.transform(2f, 50f, 0f, 200, 200, 200, 100)
        assertEquals(2f, zoomed.scale)
        assertEquals(50f, zoomed.x)
        assertEquals(fit, zoomed.transform(.25f, 100f, 100f, 200, 200, 200, 100))
    }

    @Test fun panIsBoundedByTheFittedRasterRatherThanTheLetterbox() {
        val zoomed = CorrectionPreviewZoom().transform(2f, -500f, 500f, 200, 200, 200, 100)
        assertEquals(CorrectionPreviewZoom(2f, -100f, 0f), zoomed)
        val closer = zoomed.transform(50f, -500f, 500f, 200, 200, 200, 100)
        assertEquals(CorrectionPreviewZoom(5f, -400f, 150f), closer)
    }

    @Test fun malformedGestureOrUnmeasuredPreviewDoesNotMutateTheViewport() {
        val zoom = CorrectionPreviewZoom(2f, 10f, 5f)
        assertEquals(zoom, zoom.transform(Float.NaN, 0f, 0f, 200, 200, 200, 100))
        assertEquals(zoom, zoom.transform(2f, 0f, 0f, 0, 200, 200, 100))
    }
}
