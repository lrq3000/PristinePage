package app.pristinepage

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import app.pristinepage.core.pipeline.PointPx
import app.pristinepage.core.pipeline.QuadPx
import app.pristinepage.core.recipe.BezierContour
import app.pristinepage.core.recipe.ContourSide
import app.pristinepage.editor.nearestInsertion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BezierInsertionHitTest {
    private val frame = Rect(0f, 0f, 300f, 200f)
    private val contour = BezierContour.straight(QuadPx(PointPx(0.0, 0.0), PointPx(1.0, 0.0), PointPx(1.0, 1.0), PointPx(0.0, 1.0)))

    @Test fun insertionDoesNotCreateADuplicateAtAnExistingCornerOrKnot() {
        assertNull(nearestInsertion(contour, Offset(2f, 2f), frame, 22f, 8f))
        val split = contour.insert(ContourSide.TOP, 0, .5)
        assertNull(nearestInsertion(split, Offset(150f, 2f), frame, 22f, 8f))
    }

    @Test fun aTangentHandleOverTheEdgeDoesNotBlockInsertion() {
        val hit = nearestInsertion(contour, Offset(100f, 2f), frame, 22f, 8f)
        assertNotNull(hit)
        assertEquals(ContourSide.TOP, hit!!.control.side)
        assertEquals(1.0 / 3, hit.t, .021)
    }
}
