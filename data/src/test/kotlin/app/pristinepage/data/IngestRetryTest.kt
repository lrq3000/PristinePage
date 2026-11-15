package app.pristinepage.data

import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Real SourceStore streams/private partial files/journal/owners and retry consumer. FatalCopyInterruption unwinds a local scope, not an actual process-death claim; Android row storage/provider/inspection remain substituted. */
class IngestRetryTest {
    private class FatalCopyInterruption : Error("synthetic copy interruption")
    private suspend fun interrupt(f: SourceStoreTest.Fixture): IngestJournalIntent {
        f.input = {
            object : InputStream() {
                private var emitted = false
                override fun read(): Int { if (emitted) throw FatalCopyInterruption(); emitted = true; return 1 }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int { bytes[offset] = read().toByte(); return 1 }
                override fun close() { f.closed++ }
            }
        }
        try { f.store.ingestBoundProvider(f.db.uri, f.db.child); fail("Synthetic fatal interruption became success") }
        catch (_: FatalCopyInterruption) { }
        return RoomIngestLookup(f.db.metadata, f.db, f.db, f.db).find(f.db.child)!!
    }

    @Test fun endedPartialCopyIsRetainedWhileTheFreshAttemptCommitsExactlyOnePage() = runTest {
        val f = SourceStoreTest.Fixture(enableRetry = true); val normal = f.input; val old = interrupt(f)
        val part = File(f.root, "staging/${old.transactionId.value}/original.part"); assertArrayEquals(byteArrayOf(1), part.readBytes())
        f.input = normal
        val result = f.store.ingestBoundProvider(f.db.uri, f.db.child) as IngestResult.Committed
        assertEquals(old.pageId, result.pageId); assertArrayEquals(byteArrayOf(1), part.readBytes())
        assertEquals(2, f.opened); assertEquals(2, f.closed); assertEquals(1, f.inspected)
        assertEquals(2, f.db.files.size); assertEquals("QUARANTINED", f.db.files[old.transactionId.value]!!.state)
        assertEquals("INTERRUPTED", f.db.jobs[old.jobId.value]!!.state)
        val active = RoomIngestLookup(f.db.metadata, f.db, f.db, f.db).find(f.db.child)!!
        assertEquals(2L, active.attempt); assertNotEquals(old.sourceBlobId, active.sourceBlobId); assertArrayEquals(f.bytes, f.files.finalFile(active.sourceBlobId.value).readBytes())
        assertEquals(1, f.db.catalog.pages.size); assertEquals(2, f.db.catalog.references.size); assertEquals(2L, f.db.catalog.documents[f.db.document.value]!!.documentRevision)
        assertEquals(result, f.store.ingestBoundProvider(f.db.uri, f.db.child)); assertEquals(2, f.opened)
    }

    @Test fun retryablePrivateStorageFailureCanStartFreshWithoutDeletingThePriorBytes() = runTest {
        val f = SourceStoreTest.Fixture(enableRetry = true)
        f.inspectionFailure = RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "synthetic_disk_failure", f.db.child.value))
        assertTrue(f.store.ingestBoundProvider(f.db.uri, f.db.child) is IngestResult.Pending)
        val old = f.db.files.values.single(); val part = File(f.root, old.stagingPath!!); val retained = part.readBytes()
        f.inspectionFailure = null
        assertTrue(f.store.ingestBoundProvider(f.db.uri, f.db.child) is IngestResult.Committed)
        assertArrayEquals(retained, part.readBytes()); assertEquals(2, f.opened); assertEquals(1, f.db.catalog.pages.size)
    }

    @Test fun validSealLeadingTheJournalIsNotAbandonedOrRereadByTheRetryRoute() = runTest {
        val f = SourceStoreTest.Fixture(enableRetry = true); f.db.rejectPhaseState = "SEALED"
        assertTrue(f.store.ingestBoundProvider(f.db.uri, f.db.child) is IngestResult.Pending)
        val prior = f.db.files.values.single(); val job = f.db.jobs.values.single()
        assertNull(prior.sealSha256); assertNotNull(f.files.recover(prior.transactionId, null))
        assertTrue(f.store.ingestBoundProvider(f.db.uri, f.db.child) is IngestResult.Pending)
        assertSame(prior, f.db.files.values.single()); assertSame(job, f.db.jobs.values.single()); assertEquals(1, f.opened); assertTrue(f.db.catalog.pages.isEmpty())
    }

    @Test fun liveOrUnknownOwnerCannotBeRetiredEvenWhenItsPartialFileExists() = runTest {
        for (live in listOf(false, true)) {
            val f = SourceStoreTest.Fixture(enableRetry = true); val normal = f.input; val intent = interrupt(f); f.input = normal
            val old = f.db.jobs[intent.jobId.value]!!
            suspend fun verify(owner: String) {
                f.db.jobs[old.jobId] = old.copy(leaseOwner = owner)
                val prior = f.db.files.values.single()
                assertTrue(f.store.ingestBoundProvider(f.db.uri, f.db.child) is IngestResult.Pending)
                assertSame(prior, f.db.files.values.single()); assertEquals(1, f.opened); assertTrue(f.db.catalog.pages.isEmpty())
            }
            if (live) f.owners.run { owner -> verify(owner) } else verify(UUID.randomUUID().toString())
        }
    }

    @Test fun unexplainedFinalOrChangedProviderCannotAuthorizeAReplacementAttempt() = runTest {
        for (mode in listOf("final", "reference")) {
            val f = SourceStoreTest.Fixture(enableRetry = true); val normal = f.input; val intent = interrupt(f); f.input = normal
            if (mode == "final") f.files.finalFile(intent.sourceBlobId.value).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(9)) }
            val reference = if (mode == "reference") "content://fixture.documents/different" else f.db.uri
            val rejected = f.store.ingestBoundProvider(reference, f.db.child) as IngestResult.Rejected
            assertEquals(if (mode == "final") OperationErrorCode.INTEGRITY_FAILED else OperationErrorCode.INVALID_INPUT, rejected.error.code)
            assertEquals(1, f.db.files.size); assertEquals(1, f.opened); assertTrue(f.db.catalog.pages.isEmpty())
            if (mode == "final") assertArrayEquals(byteArrayOf(9), f.files.finalFile(intent.sourceBlobId.value).readBytes())
        }
    }

    @Test fun twoCallbacksQueuedBeforeRetirementBothObserveTheSameCommittedSuccessor() = runTest {
        val f = SourceStoreTest.Fixture(enableRetry = true); val normal = f.input; interrupt(f); f.input = normal
        SourceCopyAdmission.mutex.lock(); var held = true
        try {
            // Both real callbacks complete preparation against the same old intent, then suspend on the shared copy permit. The second must re-read the session pointer after the first retires/commits it.
            val first = async(start = CoroutineStart.UNDISPATCHED) { f.store.ingestBoundProvider(f.db.uri, f.db.child) }
            val second = async(start = CoroutineStart.UNDISPATCHED) { f.store.ingestBoundProvider(f.db.uri, f.db.child) }
            assertEquals(1, f.db.files.size); assertEquals(1, f.opened)
            held = false; SourceCopyAdmission.mutex.unlock()
            val result = first.await(); assertTrue(result is IngestResult.Committed); assertEquals(result, second.await())
            assertEquals(2, f.db.files.size); assertEquals(2, f.opened); assertEquals(1, f.db.catalog.pages.size); assertEquals(2, f.db.catalog.references.size)
        } finally { if (held) SourceCopyAdmission.mutex.unlock() }
    }

    @Test fun callbackWhoseInitialReplayIsPreemptedByRetirementFollowsTheCommittedSuccessor() = runTest {
        val f = SourceStoreTest.Fixture(enableRetry = true); val normal = f.input; val old = interrupt(f); f.input = normal
        var winner: IngestResult? = null
        f.beforeReplay = { intent ->
            if (intent == old) {
                f.beforeReplay = {}
                // Deterministic lookup-to-replay preemption: another actual SourceStore callback retires/commits before this callback reads its previously resolved journal. The real replay is not substituted.
                winner = f.store.ingestBoundProvider(f.db.uri, f.db.child)
            }
        }
        val result = f.store.ingestBoundProvider(f.db.uri, f.db.child)
        assertTrue(result is IngestResult.Committed); assertEquals(winner, result)
        assertEquals(1, f.db.catalog.pages.size); assertEquals(2, f.opened); assertEquals(2, f.db.files.size)
    }

    @Test fun preparationPreemptedByAnotherCallbackCommitDoesNotRejectAnAlreadySavedImport() = runTest {
        val f = SourceStoreTest.Fixture(enableRetry = true); val normal = f.input; interrupt(f); f.input = normal
        var winner: IngestResult? = null
        f.beforePreparation = {
            f.beforePreparation = {}
            winner = runBlocking { f.store.ingestBoundProvider(f.db.uri, f.db.child) }
        }
        val result = f.store.ingestBoundProvider(f.db.uri, f.db.child)
        assertTrue(result is IngestResult.Committed); assertEquals(winner, result)
        assertEquals(1, f.db.catalog.pages.size); assertEquals(2, f.opened)
    }
}
