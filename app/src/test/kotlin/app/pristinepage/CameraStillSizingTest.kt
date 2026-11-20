package app.pristinepage

import app.pristinepage.capture.CameraStillSizing
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraStillSizingTest {
    @Test fun aSingleFullCameraSensorFrameIsNotSelectedWhenItCannotFit() {
        assertFalse(CameraStillSizing.fits(4000, 3000, 64L * 1024 * 1024))
        assertTrue(CameraStillSizing.fits(1920, 1440, 64L * 1024 * 1024))
    }

    @Test fun lowMemoryKeepsHeadroomForOriginalAndFirstCropAndAcceptsPortrait() {
        assertFalse(CameraStillSizing.fits(1920, 1440, 32L * 1024 * 1024))
        assertTrue(CameraStillSizing.fits(960, 1280, 32L * 1024 * 1024))
        assertFalse(CameraStillSizing.fits(0, 960, 32L * 1024 * 1024))
    }
}
