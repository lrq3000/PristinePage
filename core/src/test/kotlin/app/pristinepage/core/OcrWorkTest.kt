package app.pristinepage.core

import app.pristinepage.core.ocr.*
import app.pristinepage.core.image.RgbImage
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OcrWorkTest {
    private val settings = OcrConfiguration("test-fixture", 1, OcrLayout.AUTOMATIC)
    private fun work() = OcrWork.queued(OcrInput("aaaaaaaa-aaaa-4aaa-8aaa-000000000001", 4, "a".repeat(64)), settings)
    private fun OcrWork.claim(id: Long) = start(UUID(0, id))

    @Test fun interruptionAndFailuresExhaustExactlyThreeStartsAcrossSnapshots() {
        val first = assertNotNull(work().claim(1))
        assertNull(first.claim(2))
        val recovered = assertNotNull(first.interrupted(first.token))
        assertEquals(OcrPhase.QUEUED, recovered.phase)
        val second = assertNotNull(recovered.claim(3))
        val third = assertNotNull(assertNotNull(second.failed(second.token, OcrError.ENGINE_FAILED)).claim(4))
        val terminal = assertNotNull(third.interrupted(third.token))
        assertEquals(OcrPhase.FAILED, terminal.phase)
        assertEquals(3, terminal.attemptsStarted)
        assertEquals(OcrError.INTERRUPTED, terminal.lastError)
        assertNull(terminal.claim(5))
        assertFalse(terminal.processing)
    }

    @Test fun rerunResetsAttemptsButCannotAcceptOldWorkerOrDiscardGoodText() {
        val first = assertNotNull(work().claim(1))
        val done = assertNotNull(first.succeeded(first.token, OcrText("lorem ipsum for this fixture only")))
        val running = assertNotNull(done.rerun(settings.copy(layout = OcrLayout.SINGLE_BLOCK)).claim(2))
        assertTrue(running.processing)
        assertEquals("lorem ipsum for this fixture only", running.result?.text?.value)
        assertEquals(1, running.attemptsStarted)
        assertNull(running.succeeded(first.token, OcrText("obsolete")))
        val empty = assertNotNull(running.succeeded(running.token, OcrText("")))
        assertEquals(OcrPhase.READY, empty.phase)
        assertEquals("", empty.result?.text?.value)
        assertEquals(OcrLayout.SINGLE_BLOCK, empty.result?.configuration?.layout)
    }

    @Test fun textRejectsMalformedOrOversizedResultsWithoutTruncation() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { OcrText("\uD800") }
        kotlin.test.assertFailsWith<IllegalArgumentException> { OcrText("x".repeat(131_073)) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { OcrText("one\u0000two") }
    }

    @Test fun syntheticEngineResultCanCompleteOnlyTheCurrentAttempt() = runBlocking {
        val fixture = OcrEngine { image, effective ->
            assertEquals(2, image.width)
            assertEquals(settings, effective)
            OcrText("lorem ipsum from a synthetic test image")
        }
        val first = assertNotNull(work().claim(1))
        val running = assertNotNull(assertNotNull(first.failed(first.token, OcrError.ENGINE_FAILED)).claim(2))
        RgbImage(2, 2, 6, ByteArray(12)).use { image ->
            val text = fixture.recognize(image, running.configuration)
            assertNull(running.succeeded(first.token, text))
            assertEquals(text.value, running.succeeded(running.token, text)?.result?.text?.value)
        }
    }

    @Test fun independentlyQueuedReplacementCannotAcceptOldAttempt() {
        val old = assertNotNull(work().claim(1))
        val replacement = assertNotNull(work().claim(2))
        assertNull(replacement.succeeded(old.token, OcrText("stale")))
        assertNull(replacement.failed(old.token, OcrError.ENGINE_FAILED))
        assertNotNull(replacement.succeeded(replacement.token, OcrText("current")))
    }
}
