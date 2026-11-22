package app.pristinepage.data

import app.pristinepage.data.db.JobRow
import app.pristinepage.data.repository.ocrRerunReadbackMatches
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrRerunReadbackTest {
    private val created = JobRow(
        "aaaaaaaa-aaaa-4aaa-8aaa-000000000001", "PAGE_OCR", "key", "0".repeat(64),
        null, null, byteArrayOf(1, 2), "PENDING", 0, null, null, 0, null, null, 10, 10,
    )

    @Test fun aDetachedEquivalentRowAndUnchangedPinCanCommit() {
        assertTrue(ocrRerunReadbackMatches(created, created.copy(input = byteArrayOf(1, 2)), true, true))
        assertTrue(ocrRerunReadbackMatches(created, created, false, false))
    }

    @Test fun lateStartAuditOrPayloadChangesCannotCommitAsTheFreshJob() {
        for (altered in listOf(created.copy(attempt = 1), created.copy(updatedAt = 11), created.copy(input = byteArrayOf(1, 3)))) {
            assertFalse(ocrRerunReadbackMatches(created, altered, true, true))
        }
    }

    @Test fun predecessorPinCannotDisappearOrAppearDuringTheExplicitRerun() {
        assertFalse(ocrRerunReadbackMatches(created, created, true, false))
        assertFalse(ocrRerunReadbackMatches(created, created, false, true))
    }
}
