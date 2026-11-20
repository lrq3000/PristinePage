package app.pristinepage.image.export

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.image.decode.ImageAllocationBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AlbumPdfAdmissionTest {
    @Test fun aSmallMixedAspectRequestStillNeedsAMeasuredWriterEnvelope() {
        val ledger = ImageAllocationBudget(64L * 1024 * 1024)
        val writer = AlbumPdf(ledger)
        val failure = assertThrows(RepositoryFailure::class.java) {
            writer.check(listOf(PixelSize(120, 80), PixelSize(60, 100)), AlbumPdf.DEFAULT)
        }
        assertEquals(OperationErrorCode.RESOURCE_LIMIT, failure.error.code)
        assertEquals("export_pdf_profile_unavailable", failure.error.safeMessageKey)
        assertEquals(0L, ledger.usedBytes)
    }
}
