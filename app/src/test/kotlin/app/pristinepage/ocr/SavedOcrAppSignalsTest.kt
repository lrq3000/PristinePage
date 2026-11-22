package app.pristinepage.ocr

import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.core.ocr.OcrLayout
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.PageId
import app.pristinepage.data.repository.SavedOcrWork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SavedOcrAppSignalsTest {
    private val document = DocumentId("aaaaaaaa-aaaa-4aaa-8aaa-000000000011")
    private val first = PageId("aaaaaaaa-aaaa-4aaa-8aaa-000000000012")
    private val next = PageId("aaaaaaaa-aaaa-4aaa-8aaa-000000000013")
    private val job = JobId("aaaaaaaa-aaaa-4aaa-8aaa-000000000014")

    @Test fun anActiveOrSuccessfulStartupIsNotStartedAgainWithinTheProcess() = runTest {
        val release = CompletableDeferred<Unit>()
        var scans = 0
        val work = session(discover = { scans++; release.await() })
        val signals = SavedOcrAppSignals(backgroundScope, SavedOcrScheduling({ work }) { fail("No target") })
        signals.onStartup()
        signals.onStartup()
        runCurrent()
        assertEquals(1, scans)
        release.complete(Unit)
        runCurrent()
        signals.onStartup()
        runCurrent()
        assertEquals(1, scans)
    }

    @Test fun aLaterLaunchCanRecoverAfterAnEnrollmentFailure() = runTest {
        var starts = 0
        val target = SavedOcrWork.Target(first, job)
        val work = session(discover = {}, pending = target)
        val signals = SavedOcrAppSignals(backgroundScope, SavedOcrScheduling({ work }) {
            assertEquals(target, it)
            if (++starts == 1) throw IllegalStateException("Synthetic launch failure")
        })
        signals.onStartup()
        runCurrent()
        signals.onStartup()
        runCurrent()
        assertEquals(2, starts)
    }

    @Test fun savesDetachExactPageIdsAndOneFailedWakeDoesNotBlockTheNext() = runTest {
        val reconciled = mutableListOf<Pair<DocumentId, PageId>>()
        val delivered = mutableListOf<PageId>()
        val work = session(saved = { album, page ->
            reconciled += album to page
            SavedOcrWork.Target(page, job)
        })
        val signals = SavedOcrAppSignals(backgroundScope, SavedOcrScheduling({ work }) {
            if (it.page == first) throw IllegalStateException("Synthetic enrollment failure")
            delivered += it.page
        })
        val input = mutableListOf(first, next)
        signals.onPagesSaved(document, input)
        assertTrue(reconciled.isEmpty())
        input.clear()
        runCurrent()
        assertEquals(listOf(document to first, document to next), reconciled)
        assertEquals(listOf(next), delivered)
    }

    @Test fun cancellationOfOneEnqueueNeverRequestsAnotherPageInThatSignal() = runTest {
        val seen = mutableListOf<PageId>()
        val work = session(saved = { _, page -> seen += page; SavedOcrWork.Target(page, job) })
        val signals = SavedOcrAppSignals(backgroundScope, SavedOcrScheduling({ work }) {
            throw CancellationException("Synthetic canceled enqueue")
        })
        signals.onPagesSaved(document, listOf(first, next))
        runCurrent()
        assertEquals(listOf(first), seen)
    }

    @Test fun synchronousNotificationFailureCannotEscapeTheCommittedSaveBoundary() {
        val seen = mutableListOf<Pair<DocumentId, List<PageId>>>()
        sendCommittedOcrHint(document, listOf(first, next)) { album, pages ->
            seen += album to pages
            throw IllegalStateException("Synthetic notification failure after commit")
        }
        assertEquals(listOf(document to listOf(first, next)), seen)
    }

    @Test fun committedSaveNotificationPreservesCancellation() {
        val cancellation = CancellationException("Synthetic canceled notification")
        val thrown = runCatching { sendCommittedOcrHint(document, listOf(first)) { _, _ -> throw cancellation } }.exceptionOrNull()
        assertSame(cancellation, thrown)
    }

    @Test fun closingTheCallerDoesNotCancelTheAlreadyCommittedRerunWake() = runTest {
        val target = SavedOcrWork.Target(first, job)
        val configuration = OcrConfiguration("fixture", 1, OcrLayout.SPARSE_TEXT)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val delivered = mutableListOf<SavedOcrWork.Target>()
        val signals = SavedOcrAppSignals(backgroundScope, SavedOcrScheduling({ error("No reconciliation expected") }) {
            entered.complete(Unit)
            release.await()
            delivered += it
        })
        var returnedToCaller = false
        val panel = launch {
            signals.onRerun({ configuration }) { target }
            returnedToCaller = true
        }
        runCurrent()
        assertTrue(entered.isCompleted)
        panel.cancel()
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(target), delivered)
        assertFalse(returnedToCaller)
    }

    @Test fun reopeningThePageCannotCommitAnotherRerunWhileTheFirstWakeIsPending() = runTest {
        val target = SavedOcrWork.Target(first, job)
        val configuration = OcrConfiguration("fixture", 1, OcrLayout.SPARSE_TEXT)
        val release = CompletableDeferred<Unit>()
        var commits = 0
        val signals = SavedOcrAppSignals(backgroundScope, SavedOcrScheduling({ error("No reconciliation expected") }) {
            release.await()
        })
        val firstPanel = launch { signals.onRerun({ configuration }) { commits++; target } }
        runCurrent()
        assertEquals(1, commits)
        assertTrue(signals.rerunInFlight.value)
        firstPanel.cancel()
        var reopenedOutcome: SavedOcrScheduling.RerunOutcome? = null
        val reopened = launch { reopenedOutcome = signals.onRerun({ configuration }) { commits++; target } }
        runCurrent()
        try {
            assertEquals(1, commits)
            assertEquals(SavedOcrScheduling.RerunOutcome.BUSY, reopenedOutcome)
            assertTrue(signals.rerunInFlight.value)
        } finally {
            release.complete(Unit)
            reopened.join()
        }
        runCurrent()
        assertFalse(signals.rerunInFlight.value)
    }

    private fun session(
        discover: suspend () -> Unit = { fail("Expected a save hint") },
        pending: SavedOcrWork.Target? = null,
        saved: suspend (DocumentId, PageId) -> SavedOcrWork.Target? = { _, _ -> error("Expected startup discovery") },
    ): SavedOcrScheduling.QualifiedSession = object : SavedOcrScheduling.QualifiedSession {
        override fun pendingPages(): Flow<SavedOcrWork.Target> = flow {
            discover()
            if (pending != null) emit(pending)
        }
        override suspend fun reconcilePage(document: DocumentId, page: PageId): SavedOcrWork.Target? = saved(document, page)
    }
}
