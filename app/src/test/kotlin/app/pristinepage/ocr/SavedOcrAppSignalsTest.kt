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

    @Test fun aLaterLaunchCanRecoverTheFailedWakeWithoutStrandingTheRestOfTheScan() = runTest {
        var scans = 0
        val delivered = mutableListOf<SavedOcrWork.Target>()
        val firstTarget = SavedOcrWork.Target(first, job)
        val nextTarget = SavedOcrWork.Target(next, JobId("aaaaaaaa-aaaa-4aaa-8aaa-000000000015"))
        val work = session(discover = { scans++ }, pending = { if (scans == 1) listOf(firstTarget, nextTarget) else listOf(firstTarget) })
        val signals = SavedOcrAppSignals(backgroundScope, SavedOcrScheduling({ work }) {
            if (scans == 1 && it == firstTarget) throw IllegalStateException("Synthetic launch failure")
            delivered += it
        })
        signals.onStartup()
        runCurrent()
        assertEquals(listOf(nextTarget), delivered)
        signals.onStartup()
        runCurrent()
        assertEquals(listOf(nextTarget, firstTarget), delivered)
        signals.onStartup()
        runCurrent()
        assertEquals(2, scans)
    }

    @Test fun nextForegroundRetriesBackfillWhenRuntimeWasUnavailableOnFirstStart() = runTest {
        var available = false
        var scans = 0
        val previouslySaved = SavedOcrWork.Target(first, job)
        val delivered = mutableListOf<SavedOcrWork.Target>()
        val work = session(discover = { scans++ }, pending = { listOf(previouslySaved) })
        val signals = SavedOcrAppSignals(backgroundScope, SavedOcrScheduling({ work.takeIf { available } }) {
            delivered += it
        })
        signals.onStartup()
        runCurrent()
        assertEquals(0, scans)
        assertTrue(delivered.isEmpty())

        available = true
        signals.onStartup()
        signals.onStartup()
        runCurrent()
        assertEquals(listOf(previouslySaved), delivered)
        signals.onStartup()
        runCurrent()
        assertEquals(1, scans)
    }

    @Test fun foregroundDuringAnInFlightRefusalIsNotLost() = runTest {
        val finishFirstProbe = CompletableDeferred<Unit>()
        var probes = 0
        val target = SavedOcrWork.Target(first, job)
        val delivered = mutableListOf<SavedOcrWork.Target>()
        val work = session(discover = {}, pending = { listOf(target) })
        val signals = SavedOcrAppSignals(backgroundScope, SavedOcrScheduling({
            if (++probes == 1) {
                finishFirstProbe.await()
                null
            } else work
        }) { delivered += it })
        signals.onStartup()
        runCurrent()
        assertEquals(1, probes)
        signals.onStartup()
        signals.onStartup()
        runCurrent()
        assertEquals(1, probes)
        finishFirstProbe.complete(Unit)
        runCurrent()
        assertEquals(listOf(target), delivered)
        assertEquals(2, probes)
    }

    @Test fun failedSaveWakeAfterCompletedStartupIsRecoveredOnForeground() = runTest {
        val target = SavedOcrWork.Target(first, job)
        var saved = false
        var ready = true
        var scans = 0
        val delivered = mutableListOf<SavedOcrWork.Target>()
        val work = session(discover = { scans++ }, pending = { if (saved) listOf(target) else emptyList() })
        val signals = SavedOcrAppSignals(backgroundScope, SavedOcrScheduling({ work.takeIf { ready } }) { delivered += it })
        signals.onStartup()
        runCurrent()
        assertEquals(1, scans)
        saved = true
        ready = false
        signals.onPagesSaved(document, listOf(first))
        runCurrent()
        ready = true
        assertTrue(delivered.isEmpty())
        signals.onStartup()
        runCurrent()
        assertEquals(listOf(target), delivered)
        assertEquals(2, scans)
    }

    @Test fun aPassThatHasAlreadyScannedPastAFailedSaveCannotMarkStartupComplete() = runTest {
        val finishedScanning = CompletableDeferred<Unit>()
        val target = SavedOcrWork.Target(first, job)
        var ready = true
        var scans = 0
        val delivered = mutableListOf<SavedOcrWork.Target>()
        val work = session(
            discover = { if (++scans == 1) finishedScanning.await() },
            pending = { if (scans > 1) listOf(target) else emptyList() },
        )
        val signals = SavedOcrAppSignals(backgroundScope, SavedOcrScheduling({ work.takeIf { ready } }) { delivered += it })
        signals.onStartup()
        runCurrent()
        ready = false
        signals.onPagesSaved(document, listOf(first))
        runCurrent()
        ready = true
        finishedScanning.complete(Unit)
        runCurrent()
        assertEquals(1, scans)
        assertTrue(delivered.isEmpty())
        signals.onStartup()
        runCurrent()
        assertEquals(listOf(target), delivered)
        assertEquals(2, scans)
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

    @Test fun oneSavedNotificationReusesOneQualificationButTheNextReadsFreshDefaults() = runTest {
        var resolutions = 0
        val observed = mutableListOf<Pair<PageId, Int>>()
        val signals = SavedOcrAppSignals(backgroundScope, SavedOcrScheduling({
            val snapshot = ++resolutions
            session(saved = { album, page ->
                assertEquals(document, album)
                observed += page to snapshot
                null
            })
        }) { fail("No eligible wake") })
        signals.onPagesSaved(document, emptyList())
        runCurrent()
        assertEquals(0, resolutions)
        signals.onPagesSaved(document, listOf(first, next))
        runCurrent()
        signals.onPagesSaved(document, listOf(next))
        runCurrent()
        assertEquals(listOf(first to 1, next to 1, next to 2), observed)
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
        pending: () -> List<SavedOcrWork.Target> = { emptyList() },
        saved: suspend (DocumentId, PageId) -> SavedOcrWork.Target? = { _, _ -> error("Expected startup discovery") },
    ): SavedOcrScheduling.QualifiedSession = object : SavedOcrScheduling.QualifiedSession {
        override val canCreateJobs = true
        override fun pendingPages(): Flow<SavedOcrWork.Target> = flow {
            discover()
            for (target in pending()) emit(target)
        }
        override suspend fun reconcilePage(document: DocumentId, page: PageId): SavedOcrWork.Target? = saved(document, page)
    }
}
