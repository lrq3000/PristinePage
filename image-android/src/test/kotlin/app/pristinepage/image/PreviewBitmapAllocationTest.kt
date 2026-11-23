package app.pristinepage.image

import app.pristinepage.core.image.RgbImage
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.image.decode.ImageAllocationBudget
import app.pristinepage.image.preview.SrgbPreviewBitmap
import org.junit.Assert.*
import org.junit.Test

/** The real shared converter, with a synthetic OOM at its existing checkpoint immediately before native allocation. No native Bitmap stub, giant allocation or actual memory exhaustion is used. */
class PreviewBitmapAllocationTest {
    @Test fun allocationOomAfterReservationIsAResourceRefusalAndReleasesOnlyItsOwnPermit() {
        val budget = ImageAllocationBudget(128)
        budget.reserve(24).use {
            RgbImage(2,2,6,ByteArray(12) { 7 }).use { image ->
                var checkpoints = 0
                val failure = assertThrows(RepositoryFailure::class.java) {
                    SrgbPreviewBitmap.convert(image,budget) {
                        checkpoints++
                        if (checkpoints == 2) { assertEquals(64L,budget.usedBytes); throw OutOfMemoryError("synthetic allocation boundary") }
                    }
                }
                assertEquals(OperationErrorCode.RESOURCE_LIMIT,failure.error.code)
                assertTrue(failure.error.retryable)
                assertEquals(24L,budget.usedBytes); assertEquals(7,image.getUnsigned(0,0,0))
                assertFalse(failure.error.toString().contains("synthetic"))
            }
        }
        assertEquals(0L,budget.usedBytes)
    }

    @Test fun recognitionBitmapOomIsNotEvidenceOfTemporaryLedgerContention() {
        val budget = ImageAllocationBudget(128)
        budget.reserve(24).use {
            RgbImage(2, 2, 6, ByteArray(12) { 7 }).use { image ->
                var checkpoints = 0
                val failure = assertThrows(RepositoryFailure::class.java) {
                    SrgbPreviewBitmap.convertForOcr(image, budget) {
                        if (++checkpoints == 2) {
                            assertEquals(64L, budget.usedBytes)
                            throw OutOfMemoryError("synthetic OCR bitmap allocation boundary")
                        }
                    }
                }
                assertEquals(OperationErrorCode.RESOURCE_LIMIT, failure.error.code)
                assertFalse(failure.error.retryable)
                assertFalse(failure.error.toString().contains("synthetic"))
                assertEquals(24L, budget.usedBytes)
                assertEquals(7, image.getUnsigned(0, 0, 0))
            }
        }
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun allocationOomBeforeReservationDoesNotReleaseACompetingOwner() {
        val budget = ImageAllocationBudget(128)
        budget.reserve(24).use {
            RgbImage(2,2,6,ByteArray(12)).use { image ->
                val failure = assertThrows(RepositoryFailure::class.java) { SrgbPreviewBitmap.convert(image,budget) { throw OutOfMemoryError("synthetic preallocation boundary") } }
                assertEquals(OperationErrorCode.RESOURCE_LIMIT,failure.error.code); assertEquals(24L,budget.usedBytes)
            }
        }
        assertEquals(0L,budget.usedBytes)
    }
}
