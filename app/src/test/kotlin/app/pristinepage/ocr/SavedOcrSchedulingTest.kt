package app.pristinepage.ocr

import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.PageId
import app.pristinepage.data.repository.SavedOcrWork
import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.core.ocr.OcrLayout
import app.pristinepage.data.library.LibraryActivation
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
    private val activeLibrary = LibraryActivation("aaaaaaaa-aaaa-4aaa-8aaa-000000000006", 2)

    @Test fun missingRuntimeLeavesBothSignalsDormant(): Unit = runBlocking {
        val schedule = SavedOcrScheduling(resolve = { null }) { throw AssertionError("No runtime, no WorkManager enrollment") }
        schedule.onPagesSaved(activeLibrary, document, listOf(page))
        assertFalse(schedule.onStartup(activeLibrary))
    }

    @Test fun saveReconcilesOnlyTheNamedPageAndSkipsAnAbsentTarget() = runBlocking {
        val targets = mutableListOf<SavedOcrWork.Target>()
        val seen = mutableListOf<Pair<DocumentId, PageId>>()
        val store = object : SavedOcrScheduling.QualifiedSession {
            override val activation = activeLibrary
            override val canCreateJobs = true
            override suspend fun reconcilePage(document: DocumentId, page: PageId): SavedOcrWork.Target? {
                seen += document to page
                return if (page == first.page) first else null
            }

            override fun pendingPages(): Flow<SavedOcrWork.Target> = throw AssertionError("Save must not scan the library")
        }
        val schedule = SavedOcrScheduling(resolve = { store }) { targets.add(it.target) }
        schedule.onPagesSaved(activeLibrary, document, listOf(page, second.page))
        assertEquals(listOf(document to page, document to second.page), seen)
        assertEquals(listOf(first), targets)
    }

    @Test fun startupWaitsForEnrollmentBeforeTakingTheNextTarget() = runBlocking {
        val events = mutableListOf<String>()
        val store = object : SavedOcrScheduling.QualifiedSession {
            override val activation = activeLibrary
            override val canCreateJobs = true
            override suspend fun reconcilePage(document: DocumentId, page: PageId): SavedOcrWork.Target =
                throw AssertionError("Startup must use the bounded discovery path")

            override fun pendingPages(): Flow<SavedOcrWork.Target> = flow {
                events += "first discovered"
                emit(first)
                events += "second discovered"
                emit(second)
            }
        }
        val schedule = SavedOcrScheduling(resolve = { issued ->
            assertEquals(activeLibrary, issued)
            events += "resolved"
            store
        }) { (activation, target) ->
            assertEquals(activeLibrary, activation)
            events += if (target == first) "first enrolled" else {
                assertEquals(second, target)
                "second enrolled"
            }
        }
        schedule.onStartup(activeLibrary)
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

    @Test fun unresolvedDefaultsStillDeliverExistingJobsButLeaveBackfillEligible(): Unit = runBlocking {
        val enrolled = mutableListOf<SavedOcrWork.Target>()
        val partial = object : SavedOcrScheduling.QualifiedSession by onlyFirst() {
            override val canCreateJobs = false
            override fun pendingPages(): Flow<SavedOcrWork.Target> = flow { emit(first) }
        }
        val schedule = SavedOcrScheduling({ partial }) { enrolled += it.target }
        assertFalse(schedule.onStartup(activeLibrary))
        assertEquals(listOf(first), enrolled)
    }

    @Test fun cancellationWhileWaitingForEnrollmentStopsDiscovery() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val schedule = SavedOcrScheduling(resolve = { onlyFirst() }) {
            entered.complete(Unit)
            awaitCancellation()
        }
        val startup = launch { schedule.onStartup(activeLibrary) }
        try { withTimeout(1_000) { entered.await() } }
        finally { startup.cancelAndJoin() }
        assertTrue(startup.isCancelled)
    }

    @Test fun failedEnrollmentDoesNotStrandLaterPagesButKeepsStartupRetryable() = runBlocking {
        val failure = IllegalStateException("Fixture scheduler failed")
        val enrolled = mutableListOf<SavedOcrWork.Target>()
        val store = object : SavedOcrScheduling.QualifiedSession {
            override val activation = activeLibrary
            override val canCreateJobs = true
            override suspend fun reconcilePage(document: DocumentId, page: PageId): SavedOcrWork.Target =
                throw AssertionError("Startup must use the bounded discovery path")

            override fun pendingPages(): Flow<SavedOcrWork.Target> = flow {
                emit(first)
                emit(second)
            }
        }
        val schedule = SavedOcrScheduling(resolve = { store }) { (_, target) ->
            if (target == first) throw failure
            enrolled += target
        }
        assertSame(failure, runCatching { schedule.onStartup(activeLibrary) }.exceptionOrNull())
        assertEquals(listOf(second), enrolled)
    }

    @Test fun unqualifiedRerunDoesNotCommitOrOfferAWake() = runBlocking {
        val schedule = SavedOcrScheduling(resolve = { error("Must not reconcile") }) { error("Must not wake") }
        assertEquals(SavedOcrScheduling.RerunOutcome.UNAVAILABLE,
            schedule.requestRerun({ null }, activeLibrary) { error("Must not commit") })
    }

    @Test fun committedRerunOffersOnlyItsExactTargetAndDistinguishesDeferredEnrollment() = runBlocking {
        val configuration = OcrConfiguration("fixture", 1, OcrLayout.SPARSE_TEXT)
        val events = mutableListOf<String>()
        var denyWake = false
        val schedule = SavedOcrScheduling(resolve = { error("Must not reconcile instead") }) { (activation, target) ->
            assertEquals(activeLibrary, activation)
            assertEquals(second, target)
            events += "wake"
            if (denyWake) throw IllegalStateException("Fixture enrollment failure")
        }
        suspend fun request() = schedule.requestRerun({ configuration }, activeLibrary) {
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
        assertSame(error, runCatching { schedule.requestRerun({ configuration }, activeLibrary) { throw error } }.exceptionOrNull())
    }

    private fun onlyFirst(): SavedOcrScheduling.QualifiedSession =
        object : SavedOcrScheduling.QualifiedSession {
            override val activation = activeLibrary
            override val canCreateJobs = true
            override suspend fun reconcilePage(document: DocumentId, page: PageId): SavedOcrWork.Target? =
                throw AssertionError("Startup must use the bounded discovery path")

            override fun pendingPages(): Flow<SavedOcrWork.Target> = flow {
                emit(first)
                throw AssertionError("A canceled enrollment continued discovery")
            }
        }
}
