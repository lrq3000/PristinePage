package app.pristinepage

import app.pristinepage.capture.AtelierCaptureState
import app.pristinepage.data.model.*
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AtelierImportFeedbackTest {
    @Test fun mixedSelectionPreservesPickerPositionsInsteadOfRenumberingUnimportedItems() {
        val batch = session(1)
        val failure = OperationError(OperationErrorCode.UNSUPPORTED_FORMAT, false, "unsupported_source_format", session(3).value)
        val revoked = OperationError(OperationErrorCode.PERMISSION_LOST, false, "import_permission_lost", session(5).value)
        val state = AtelierCaptureState().withImportResults(batch, listOf(
            IngestResult.Committed(session(2), DocumentId(id(6)), PageId(id(7)), SourceId(id(8)), "a".repeat(64), "b".repeat(64), 2L),
            IngestResult.Rejected(session(3), failure),
            IngestResult.Pending(session(4), FileTransactionState.COPYING),
            IngestResult.Rejected(session(5), revoked),
        ))

        assertEquals(listOf(2, 3, 4), state.importIssues.map { it.position })
        assertEquals(listOf(OperationErrorCode.UNSUPPORTED_FORMAT, null, OperationErrorCode.PERMISSION_LOST),
            state.importIssues.map { it.error?.code })
        assertEquals(listOf(null, null, null), state.importIssues.map { it.error?.affectedId })
        assertNull(state.draft)
    }

    @Test fun pickerFailureHasNoItemNumberAndNextCancellationClearsItQuietly() {
        val batch = session(1)
        val failed = AtelierCaptureState().withImportResults(batch, listOf(
            IngestResult.Rejected(batch, OperationError(OperationErrorCode.PROVIDER_UNAVAILABLE, false, "files_picker_unavailable", batch.value)),
        ))
        assertNull(failed.importIssues.single().position)
        assertEquals("files_picker_unavailable", failed.importIssues.single().error?.safeMessageKey)

        val next = session(2)
        assertEquals(emptyList<Any>(), failed.withImportResults(next, listOf(
            IngestResult.Rejected(next, OperationError(OperationErrorCode.CANCELED, false, "photo_selection_canceled", next.value)),
        )).importIssues)
    }

    private fun session(i: Long) = ImportSessionId(id(i))
    private fun id(i: Long) = UUID(0, i).toString()
}
