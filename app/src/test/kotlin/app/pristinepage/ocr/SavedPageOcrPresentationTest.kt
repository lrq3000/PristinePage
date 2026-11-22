package app.pristinepage.ocr

import app.pristinepage.R
import app.pristinepage.core.ocr.OcrError
import app.pristinepage.core.ocr.OcrText
import app.pristinepage.data.repository.SavedOcrWork.PagePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SavedPageOcrPresentationTest {
    @Test fun readOnlyOcrPanelDoesNotInvokeComposeSelectionOrItsSmartSelectionPath() {
        // Compose selection can hand the displayed string to a platform text classifier before an explicit copy.
        val compiledPanel = checkNotNull(javaClass.getResourceAsStream("SavedPageOcrDetailsKt.class"))
            .use { it.readBytes().toString(Charsets.ISO_8859_1) }
        assertFalse("Read-only OCR must not call the selectable-text container",
            "androidx/compose/foundation/text/selection/SelectionContainerKt" in compiledPanel)
    }

    @Test fun absentAndSuccessfulBlankHaveDifferentMessagesAndNoInventedText() {
        val absent = presentOcrText(null, previousGeneration = false)
        assertEquals(R.string.ocr_text_not_available, absent.message)
        assertNull(absent.verbatim)
        val blank = presentOcrText(OcrText(""), previousGeneration = false)
        assertEquals(R.string.ocr_text_blank, blank.message)
        assertNull(blank.verbatim)
    }

    @Test fun previousTextPreservesRtlCjkAndWhitespaceAndIsExplicitlyLabeled() {
        val original = "  الإيصال　領収書\n"
        val shown = presentOcrText(OcrText(original), previousGeneration = true)
        assertEquals(R.string.ocr_text_previous, shown.message)
        assertEquals(original, shown.verbatim)
        assertEquals(R.string.ocr_text_previous_blank, presentOcrText(OcrText(""), true).message)
    }

    @Test fun phaseAndLastErrorAreIndependentAndNeverUseRawExceptionCopy() {
        assertEquals(R.string.ocr_details_queued, ocrPhaseLabel(PagePhase.QUEUED))
        assertEquals(R.string.ocr_details_ready, ocrPhaseLabel(PagePhase.READY))
        assertEquals(R.string.ocr_details_failed, ocrPhaseLabel(PagePhase.FAILED))
        assertEquals(R.string.ocr_details_preparation_blocked, ocrPhaseLabel(PagePhase.FAILED, OcrError.PREPARATION_RESOURCE_LIMIT))
        assertEquals(R.string.ocr_details_input_too_large, ocrErrorLabel(OcrError.PREPARATION_RESOURCE_LIMIT))
        assertEquals(R.string.ocr_details_no_errors, ocrErrorLabel(null))
        assertEquals(R.string.ocr_details_interrupted, ocrErrorLabel(OcrError.INTERRUPTED))
    }

    @Test fun rerunPresentationSeparatesUnavailableFromCommittedButUnscheduled() {
        assertEquals(R.string.ocr_rerun_unavailable, ocrRerunLabel(SavedOcrScheduling.RerunOutcome.UNAVAILABLE))
        assertEquals(R.string.ocr_rerun_enrolled, ocrRerunLabel(SavedOcrScheduling.RerunOutcome.ENROLLED))
        assertEquals(R.string.ocr_rerun_deferred, ocrRerunLabel(SavedOcrScheduling.RerunOutcome.WAKE_DEFERRED))
    }
}
