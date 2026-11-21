package app.pristinepage

import app.pristinepage.capture.CameraStillSizing
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.image.decode.DecodeAdmission
import app.pristinepage.image.decode.ImageAllocationBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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

    @Test fun oneNominallyEligibleStillCanHitDecodeAdmissionWhileOtherImageOwnersAreRetained() {
        val budget = ImageAllocationBudget(64L * 1024 * 1024)
        val decode = DecodeAdmission(budget)
        assertTrue(CameraStillSizing.fits(1920, 1440, budget.capacityBytes))

        // Budget tokens model competing owners without allocating 32 MiB or pretending these values came from a physical device.
        budget.reserve(32L * 1024 * 1024).use {
            val refused = assertThrows(RepositoryFailure::class.java) {
                decode.admit(3L * 1024 * 1024, 1920, 1440, 1).close()
            }
            assertEquals(OperationErrorCode.RESOURCE_LIMIT, refused.error.code)
            assertEquals("image_allocation_limit", refused.error.safeMessageKey)
            decode.admit(3L * 1024 * 1024, 1280, 960, 1).close()
        }

        decode.admit(3L * 1024 * 1024, 1920, 1440, 1).close()
        assertEquals(0L, budget.usedBytes)
    }
}
