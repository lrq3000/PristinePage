package app.pristinepage.data

import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Reuses the actual file/metadata command fixture. Lookup itself reads only recorded metadata and replay uses the existing final-file verifier; neither may mint a lease or a second operation. */
class IngestLookupTest {
    private fun lookup(f: IngestCompletionTest.Fixture): IngestOperationLookup {
        val type = try { Class.forName("app.pristinepage.data.repository.RoomIngestLookup") }
        catch (error: ClassNotFoundException) { throw AssertionError("Read-only recorded ingest intent lookup is absent", error) }
        return try { type.constructors.single { it.parameterCount == 4 }.newInstance(f.db.metadata, f.db, f.db, f.db) as IngestOperationLookup }
        catch (error: InvocationTargetException) { throw error.targetException }
    }
    private fun replay(f: IngestCompletionTest.Fixture): IngestReplay = f.commands as? IngestReplay ?: throw AssertionError("Lease-free committed ingest replay is absent")

    @Test fun boundChildWithoutAnIntentHasNoRecordedOperationAndNoSideEffects() {
        val f = IngestCompletionTest.Fixture()
        assertNull(lookup(f).find(f.db.child))
        assertTrue(f.db.jobs.isEmpty()); assertTrue(f.db.files.isEmpty()); assertTrue(f.db.catalog.pages.isEmpty())
        assertEquals("BOUND", f.db.sessions[f.db.child.value]!!.state)
    }

    @Test fun activeOperationLookupReturnsItsOriginalIntentWithoutRepreparing() {
        val f = IngestCompletionTest.Fixture(); val (intent, _) = f.ready()
        val row = f.db.files[intent.transactionId.value]!!; val job = f.db.jobs[intent.jobId.value]!!
        repeat(2) { assertEquals(intent, lookup(f).find(f.db.child)) }
        assertSame(row, f.db.files[intent.transactionId.value]); assertSame(job, f.db.jobs[intent.jobId.value])
        assertEquals(1, f.db.jobs.size); assertEquals(1, f.db.files.size); assertTrue(f.db.catalog.pages.isEmpty())
    }

    @Test fun committedReceiptIsResolvedThroughTheImmutableOriginalJobIntent() = runTest {
        val f = IngestCompletionTest.Fixture(); val (intent, lease) = f.ready()
        assertTrue(f.commands.commit(intent, lease) is IngestResult.Committed)
        assertThrows(RepositoryFailure::class.java) { f.db.commands.prepare(f.db.child, f.db.uri) }
        assertEquals(intent, lookup(f).find(f.db.child))
        assertEquals(1, f.db.jobs.size); assertEquals(1, f.db.catalog.pages.size)
    }

    @Test fun reboundOrMissingRecordedJobCannotSupplyAnIntent() {
        for (mutation in listOf("missing", "hash", "child")) {
            val f = IngestCompletionTest.Fixture(); val (intent, _) = f.ready()
            when (mutation) {
                "missing" -> f.db.jobs.clear()
                "hash" -> f.db.jobs[intent.jobId.value] = f.db.jobs[intent.jobId.value]!!.copy(canonicalInputSha256 = "b".repeat(64))
                else -> f.db.sessions[f.db.child.value] = f.db.sessions[f.db.child.value]!!.copy(transactionId = UUID.randomUUID().toString())
            }
            val failure = assertThrows(RepositoryFailure::class.java) { lookup(f).find(f.db.child) }
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, failure.error.code)
            assertEquals(1, f.db.files.size); assertTrue(f.db.catalog.pages.isEmpty())
        }
    }

    @Test fun committedResultReplaysWithoutARunnerOrLeaseEvenAfterLaterEdits() = runTest {
        val f = IngestCompletionTest.Fixture(); val (intent, lease) = f.ready()
        val first = f.commands.commit(intent, lease)
        f.db.catalog.documents[f.db.document.value] = f.db.catalog.documents[f.db.document.value]!!.copy(documentRevision = 9)
        assertEquals(first, replay(f).replay(intent))
        assertEquals(9L, f.db.catalog.documents[f.db.document.value]!!.documentRevision)
        assertEquals(1, f.db.catalog.pages.size); assertEquals(2, f.db.catalog.references.size)
    }

    @Test fun leaseFreeReplayCannotCommitAnUnfinishedMovedOriginal() = runTest {
        val f = IngestCompletionTest.Fixture(); val (intent, _) = f.ready()
        assertTrue(replay(f).replay(intent) is IngestResult.Pending)
        assertTrue(f.db.catalog.pages.isEmpty()); assertTrue(f.db.catalog.sources.isEmpty())
        assertEquals("MOVED", f.db.files[intent.transactionId.value]!!.state); assertEquals("RUNNING", f.db.jobs[intent.jobId.value]!!.state)
    }
}
