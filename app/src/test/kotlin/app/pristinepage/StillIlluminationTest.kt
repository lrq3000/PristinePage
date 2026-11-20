package app.pristinepage

import app.pristinepage.capture.StillIllumination
import org.junit.Assert.*
import org.junit.Test

class StillIlluminationTest {
    @Test fun lateFileCannotSucceedOnceTheLightHasBeenCutOff() {
        val shot = StillIllumination(flash = true)
        assertFalse(shot.acceptDelivery)
        assertTrue(shot.acknowledge())
        assertTrue(shot.acceptDelivery)
        shot.expire()
        assertFalse(shot.acceptDelivery)
    }

    @Test fun lateAcknowledgementCannotAdmitExposureOnceTheFlashWindowExpires() {
        val shot = StillIllumination(flash = true)
        shot.expire()
        assertFalse(shot.acknowledge())
        assertFalse(shot.acceptDelivery)
        assertTrue(StillIllumination(flash = false).acceptDelivery)
    }
}
