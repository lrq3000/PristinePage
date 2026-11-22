package app.pristinepage.data

import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.core.ocr.OcrInput
import app.pristinepage.core.ocr.OcrLayout
import app.pristinepage.core.ocr.OcrText
import app.pristinepage.data.db.JobRow
import app.pristinepage.data.db.OcrResultRow
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.SourceId
import app.pristinepage.data.repository.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class OcrJobCompletionTest {
    @Test fun publishesOnlyUnderTheCurrentLiveLease() = runTest {
        val f = Fixture()
        f.complete()
        assertEquals("SUCCEEDED", f.job.state)
        assertNull(f.job.leaseOwner)
        assertEquals(f.binding.input.pageId, f.output?.pageId)
        assertEquals("test-only receipt", f.output?.recognizedText())
        assertEquals(1, f.writes)
        val repeated = runCatching { f.complete() }.exceptionOrNull() as RepositoryFailure
        assertEquals(OperationErrorCode.REVISION_CONFLICT, repeated.error.code)
        assertEquals(1, f.writes)
    }

    @Test fun staleGenerationAndUnknownOwnerCannotTouchTheExistingText() = runTest {
        for (change in listOf<(Fixture) -> Unit>(
            { it.current = it.binding.copy(generation = 3) },
            { it.live = LeaseOwnerState.UNKNOWN },
            { it.job = it.job.copy(leaseGeneration = 2) },
        )) {
            val f = Fixture()
            change(f)
            val failure = runCatching { f.complete() }.exceptionOrNull() as RepositoryFailure
            assertEquals(OperationErrorCode.REVISION_CONFLICT, failure.error.code)
            assertEquals(0, f.writes)
            assertNull(f.output)
            assertEquals("RUNNING", f.job.state)
        }
    }

    @Test fun ownershipLossAfterTextWriteRollsBackTextAndJob() = runTest {
        val f = Fixture()
        f.afterWrite = { f.live = LeaseOwnerState.ENDED }
        val failure = runCatching { f.complete() }.exceptionOrNull() as RepositoryFailure
        assertEquals(OperationErrorCode.REVISION_CONFLICT, failure.error.code)
        assertNull(f.output)
        assertEquals("RUNNING", f.job.state)
        assertEquals(1, f.writes)
    }

    @Test fun noOpResultWriteCannotSucceed() = runTest {
        val f = Fixture()
        f.skipWrite = true
        val failure = runCatching { f.complete() }.exceptionOrNull() as RepositoryFailure
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, failure.error.code)
        assertNull(f.output)
        assertEquals("RUNNING", f.job.state)
    }

    @Test fun cancellationAfterJobUpdateRestoresPriorTextAndJob() = runTest {
        val f = Fixture()
        val prior = OcrResultRow.fromRecognition(f.binding.input, f.binding.configuration, OcrText("earlier result"))
        f.output = prior
        val running = launch(start = CoroutineStart.LAZY) { f.complete() }
        f.afterJobUpdate = { running.cancel() }
        running.start()
        running.join()
        assertTrue(running.isCancelled)
        assertEquals(1, f.writes)
        assertEquals(1, f.jobUpdates)
        assertSame(prior, f.output)
        assertEquals("RUNNING", f.job.state)
    }

    private class Fixture {
        val binding = OcrJobInput(
            DocumentId(UUID(0, 1).toString()), OcrInput(UUID(0, 2).toString(), 1, "a".repeat(64)),
            SourceId(UUID(0, 3).toString()), "b".repeat(64), 2, OcrConfiguration("fixture", 1, OcrLayout.AUTOMATIC),
        )
        val lease = JobLease(JobId(UUID(0, 4).toString()), UUID(0, 5).toString(), 1)
        var job = JobRow(lease.jobId.value, OcrJobInput.KIND, binding.key, binding.digest, null, null, binding.encode(),
            "RUNNING", 1, lease.owner, 1000, lease.generation, null, null, 0, 0)
        var current: OcrJobInput? = binding
        var live = LeaseOwnerState.ALIVE
        var output: OcrResultRow? = null
        var writes = 0
        var jobUpdates = 0
        var skipWrite = false
        var afterWrite: () -> Unit = {}
        var afterJobUpdate: () -> Unit = {}
        private var locked = false
        private val metadata = object : MetadataAccess {
            override val queries: CatalogQueries = MemoryMetadata()
            override fun <T> transaction(action: () -> T): T {
                check(!locked)
                locked = true
                val previousJob = job
                val previousOutput = output
                try { return action() }
                catch (failure: Throwable) { job = previousJob; output = previousOutput; throw failure }
                finally { locked = false }
            }
        }
        private val jobs = object : JobLeaseQueries {
            override fun job(id: String): JobRow = error("Wide job read is forbidden")
            override fun updateLease(row: JobRow, expectedGeneration: Long): Int {
                check(locked)
                if (expectedGeneration != job.leaseGeneration) return 0
                job = row
                jobUpdates++
                afterJobUpdate()
                return 1
            }
        }

        suspend fun complete() = RoomOcrJobCompletion(
            JobLeaseTransactions(metadata, jobs, auditClock = { 77 }, leaseClock = { 100 }),
            ownerState = { live },
            currentBinding = { check(locked); current },
            boundedJob = { id, expectedSize -> check(locked && id == lease.jobId && job.input.size == expectedSize); job },
            persist = { row -> check(locked); writes++; if (!skipWrite) output = row; afterWrite() },
            storedResultMatches = { row -> check(locked); output === row },
            dispatcher = Dispatchers.Unconfined,
        ).complete(binding, lease, OcrText("test-only receipt"))
    }
}
