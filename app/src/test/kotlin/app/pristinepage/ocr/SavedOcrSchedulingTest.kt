package app.pristinepage.ocr

import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.PageId
import app.pristinepage.data.repository.SavedOcrWork
import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.core.ocr.OcrLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class SavedOcrSchedulingTest {
    private val document = DocumentId("aaaaaaaa-aaaa-4aaa-8aaa-000000000001")
    private val page = PageId("aaaaaaaa-aaaa-4aaa-8aaa-000000000002")
    private val first = SavedOcrWork.Target(page, JobId("aaaaaaaa-aaaa-4aaa-8aaa-000000000003"))
    private val second = SavedOcrWork.Target(PageId("aaaaaaaa-aaaa-4aaa-8aaa-000000000004"), JobId("aaaaaaaa-aaaa-4aaa-8aaa-000000000005"))

    @Test fun missingRuntimeLeavesBothSignalsDormant() = runBlocking {
        val schedule = SavedOcrScheduling(resolve = { null }) { throw AssertionError("No runtime, no WorkManager enrollment") }
        schedule.onPagesSaved(document, listOf(page))
        schedule.onStartup()
    }

    @Test fun saveReconcilesOnlyTheNamedPageAndSkipsAnAbsentTarget() = runBlocking {
        val targets = mutableListOf<SavedOcrWork.Target>()
        val seen = mutableListOf<Pair<DocumentId, PageId>>()
        val store = object : SavedOcrScheduling.QualifiedSession {
            override suspend fun reconcilePage(document: DocumentId, page: PageId): SavedOcrWork.Target? {
                seen += document to page
                return if (page == first.page) first else null
            }

            override fun pendingPages(): Flow<SavedOcrWork.Target> = throw AssertionError("Save must not scan the library")
        }
        val schedule = SavedOcrScheduling(resolve = { store }) { targets.add(it) }
        schedule.onPagesSaved(document, listOf(page, second.page))
        assertEquals(listOf(document to page, document to second.page), seen)
        assertEquals(listOf(first), targets)
    }

    @Test fun startupWaitsForEnrollmentBeforeTakingTheNextTarget() = runBlocking {
        val events = mutableListOf<String>()
        val store = object : SavedOcrScheduling.QualifiedSession {
            override suspend fun reconcilePage(document: DocumentId, page: PageId): SavedOcrWork.Target =
                throw AssertionError("Startup must use the bounded discovery path")

            override fun pendingPages(): Flow<SavedOcrWork.Target> = flow {
                events += "first discovered"
                emit(first)
                events += "second discovered"
                emit(second)
            }
        }
        val schedule = SavedOcrScheduling(resolve = { events += "resolved"; store }) { target ->
            events += if (target == first) "first enrolled" else {
                assertEquals(second, target)
                "second enrolled"
            }
        }
        schedule.onStartup()
        assertEquals(listOf("resolved", "first discovered", "first enrolled", "second discovered", "second enrolled"), events)
    }

    @Test fun emptyBatchStillContinuesWithTheOriginalUnresolvedDefaults() = runBlocking {
        val cursor = Any()
        var batches = 0
        val targets = SavedOcrScheduling.discoverTargets<Any>(null) { configuration, after, limit ->
            assertNull(configuration)
            assertEquals(20, limit)
            when (batches++) {
                0 -> {
                    assertNull(after)
                    emptyList<SavedOcrWork.Target>() to cursor
                }
                1 -> {
                    assertSame(cursor, after)
                    listOf(second) to null
                }
                else -> throw AssertionError("Terminal batch restarted discovery")
            }
        }.toList()
        assertEquals(2, batches)
        assertEquals(listOf(second), targets)
    }

    @Test fun cancellationWhileWaitingForEnrollmentStopsDiscovery() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val schedule = SavedOcrScheduling(resolve = { onlyFirst() }) {
            entered.complete(Unit)
            awaitCancellation()
        }
        val startup = launch { schedule.onStartup() }
        try { withTimeout(1_000) { entered.await() } }
        finally { startup.cancelAndJoin() }
        assertTrue(startup.isCancelled)
    }

    @Test fun failedEnrollmentIsVisibleAndStopsThatScan() = runBlocking {
        val failure = IllegalStateException("Fixture scheduler failed")
        val schedule = SavedOcrScheduling(resolve = { onlyFirst() }) { throw failure }
        assertSame(failure, runCatching { schedule.onStartup() }.exceptionOrNull())
    }

    @Test fun unqualifiedRerunDoesNotCommitOrOfferAWake() = runBlocking {
        val schedule = SavedOcrScheduling(resolve = { error("Must not reconcile") }) { error("Must not wake") }
        assertEquals(SavedOcrScheduling.RerunOutcome.UNAVAILABLE,
            schedule.requestRerun({ null }) { error("Must not commit") })
    }

    @Test fun committedRerunOffersOnlyItsExactTargetAndDistinguishesDeferredEnrollment() = runBlocking {
        val configuration = OcrConfiguration("fixture", 1, OcrLayout.SPARSE_TEXT)
        val events = mutableListOf<String>()
        var denyWake = false
        val schedule = SavedOcrScheduling(resolve = { error("Must not reconcile instead") }) { target ->
            assertEquals(second, target)
            events += "wake"
            if (denyWake) throw IllegalStateException("Fixture enrollment failure")
        }
        suspend fun request() = schedule.requestRerun({ configuration }) {
            assertSame(configuration, it)
            events += "commit"
            second
        }
        assertEquals(SavedOcrScheduling.RerunOutcome.ENROLLED, request())
        denyWake = true
        assertEquals(SavedOcrScheduling.RerunOutcome.WAKE_DEFERRED, request())
        assertEquals(listOf("commit", "wake", "commit", "wake"), events)
    }

    @Test fun failedRerunCommitCannotOfferAWakeOrLookQueued() = runBlocking {
        val error = IllegalStateException("Fixture commit refused")
        val configuration = OcrConfiguration("fixture", 1, OcrLayout.SPARSE_TEXT)
        val schedule = SavedOcrScheduling(resolve = { error("Must not reconcile") }) { throw AssertionError("Must not wake") }
        assertSame(error, runCatching { schedule.requestRerun({ configuration }) { throw error } }.exceptionOrNull())
    }

    private fun onlyFirst(): SavedOcrScheduling.QualifiedSession =
        object : SavedOcrScheduling.QualifiedSession {
            override suspend fun reconcilePage(document: DocumentId, page: PageId): SavedOcrWork.Target? =
                throw AssertionError("Startup must use the bounded discovery path")

            override fun pendingPages(): Flow<SavedOcrWork.Target> = flow {
                emit(first)
                throw AssertionError("A failed or canceled enrollment continued discovery")
            }
        }
}
