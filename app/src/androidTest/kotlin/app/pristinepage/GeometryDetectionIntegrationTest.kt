package app.pristinepage

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pristinepage.editor.detectAtelierFrame
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Native bitmap input through the same adapter used by Perspective and Find edges; no camera or user file. */
@RunWith(AndroidJUnit4::class)
class GeometryDetectionIntegrationTest {
    @Test fun bitmapAdapterUsesOffCenterRegionsAndEqualBrightnessColor() = runBlocking<Unit> {
        val bitmap = Bitmap.createBitmap(200, 160, Bitmap.Config.ARGB_8888)
        try {
            for ((background, foreground) in listOf(0xff474747 to 0xffc3c3c3, 0xff657eac to 0xffc26731)) {
                bitmap.eraseColor(background.toInt())
                for (y in 18..68) for (x in 19..78) bitmap.setPixel(x, y, foreground.toInt())
                val frame = checkNotNull(detectAtelierFrame(bitmap))
                for ((point, expected) in frame.points().zip(listOf(19 to 18, 78 to 18, 78 to 68, 19 to 68))) {
                    assertEquals(expected.first.toDouble(), point.x * 199, 2.0)
                    assertEquals(expected.second.toDouble(), point.y * 159, 2.0)
                }
            }
            bitmap.eraseColor(0xff818181.toInt())
            assertNull(detectAtelierFrame(bitmap))
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun minificationPreservesLabeledCornersButRejectsAColorPatternWithNoRegionalDifference() = runBlocking<Unit> {
        val width = 767
        val height = 639
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val cool = 0xff657eac.toInt()
        val warm = 0xffc26731.toInt()
        try {
            bitmap.eraseColor(cool)
            for (y in 53..247) for (x in 81..331) bitmap.setPixel(x, y, warm)
            val frame = checkNotNull(detectAtelierFrame(bitmap))
            for ((point, expected) in frame.points().zip(listOf(81 to 53, 331 to 53, 331 to 247, 81 to 247))) {
                assertEquals(expected.first.toDouble(), point.x * (width - 1), 5.0)
                assertEquals(expected.second.toDouble(), point.y * (height - 1), 5.0)
            }
            val row = IntArray(width)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val inside = x in 101..400 && y in 81..390
                    row[x] = if ((x % 2 == 0) xor inside) cool else warm
                }
                bitmap.setPixels(row, 0, width, 0, y, width, 1)
            }
            assertNull(detectAtelierFrame(bitmap))
        } finally {
            bitmap.recycle()
        }
    }
}
