package app.pristinepage.data

import app.pristinepage.data.db.JobRow
import app.pristinepage.data.journal.IngestJournalCodec
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Production attempt-retirement metadata, real canonical intents and structured owners; only unavailable Android row storage/transaction is substituted. No file/provider/new-copy/resource admission or whole recovery acceptance is inferred. */
class IngestAttemptTest {
    private class Fixture {
        val db = IngestIntentTest.Fixture()
        val owners = LocalOperationOwners()
        var intent = db.commands.prepare(db.child, db.uri)
        lateinit var oldOwner: String
        suspend fun ended() {
            owners.run { owner ->
                oldOwner = owner
                db.files[intent.transactionId.value] = db.files[intent.transactionId.value]!!.copy(state = "COPYING")
                db.jobs[intent.jobId.value] = db.jobs[intent.jobId.value]!!.copy(state = "RUNNING", attempt = 1, leaseOwner = owner, leaseUntil = 99999, leaseGeneration = 1)
            }
        }
        fun restart(owner: String? = oldOwner, generation: Long = 1, liveness: LeaseOwnerLiveness = owners, jobs: JobLeaseQueries = db): IngestJournalIntent? {
            val type = try { Class.forName("app.pristinepage.data.repository.RoomIngestAttempts") }
            catch (failure: ClassNotFoundException) { throw AssertionError("Preserved fresh ingest attempt allocation is absent", failure) }
            return try {
                val commands = type.constructors.single { it.parameterCount == 7 }.newInstance(db.metadata, db, db, jobs, db, liveness, { db.now })
                type.getMethod("restartUnsealed", IngestJournalIntent::class.java, java.lang.Long.TYPE, String::class.java).invoke(commands, intent, generation, owner) as IngestJournalIntent?
            } catch (failure: InvocationTargetException) { throw failure.targetException }
        }
    }

    @Test fun endedAttemptGetsFreshPathsAndJobButKeepsTheReservedSessionPageAndReference() = runTest {
        val f = Fixture(); f.ended(); val old = f.intent
        val prior = f.db.files[old.transactionId.value]!!; val originalInput = f.db.jobs[old.jobId.value]!!.input.copyOf()
        val next = checkNotNull(f.restart())
        assertEquals(2L, next.attempt); assertEquals(old.sessionId, next.sessionId); assertEquals(old.pageId, next.pageId); assertEquals(old.documentId, next.documentId)
        assertNotEquals(old.transactionId, next.transactionId); assertNotEquals(old.sourceBlobId, next.sourceBlobId); assertNotEquals(old.sourceId, next.sourceId); assertNotEquals(old.jobId, next.jobId)
        val retained = f.db.files[old.transactionId.value]!!
        assertEquals("QUARANTINED", retained.state); assertArrayEquals(prior.payload, retained.payload); assertEquals(prior.stagingPath, retained.stagingPath); assertEquals(prior.finalPath, retained.finalPath)
        val interrupted = f.db.jobs[old.jobId.value]!!; assertEquals("INTERRUPTED", interrupted.state); assertNull(interrupted.leaseOwner); assertNull(interrupted.leaseUntil); assertEquals(1L, interrupted.leaseGeneration); assertArrayEquals(originalInput, interrupted.input)
        val pending = f.db.jobs[next.jobId.value]!!; assertEquals("PENDING", pending.state); assertNull(pending.leaseOwner); assertEquals(0L, pending.leaseGeneration); assertEquals("ingest-v1:${old.sessionId.value}:2", pending.idempotenceKey)
        assertEquals(next, IngestJournalCodec.decodeIntent(pending.input)); assertEquals("INTENT", f.db.files[next.transactionId.value]!!.state)
        val child = f.db.sessions[old.sessionId.value]!!; assertEquals("INGESTING", child.state); assertEquals(f.db.uri, child.uriText); assertEquals(next.transactionId.value, child.transactionId)
        assertEquals(1L, f.db.catalog.documents[f.db.document.value]!!.documentRevision); assertTrue(f.db.catalog.pages.isEmpty()); assertTrue(f.db.catalog.references.isEmpty())
        assertEquals(next, f.db.commands.prepare(f.db.child, f.db.uri))
        assertEquals(next, f.restart()); assertEquals(2, f.db.files.size); assertEquals(2, f.db.jobs.size)
    }

    @Test fun ownerFreeRetryUsesItsExactGenerationWithoutInventingAnEndedOwner() = runTest {
        val f = Fixture(); f.ended()
        val old = f.db.jobs[f.intent.jobId.value]!!
        f.db.jobs[old.jobId] = old.copy(state = "RETRYABLE", leaseOwner = null, leaseUntil = null)
        f.db.files[f.intent.transactionId.value] = f.db.files[f.intent.transactionId.value]!!.copy(state = "RETRYABLE", errorCode = "STORAGE_FAILED")
        assertNull(f.restart(owner = null, generation = 0, liveness = LeaseOwnerLiveness { error("Owner-free route consulted nonexistent owner") }))
        assertEquals(2L, checkNotNull(f.restart(owner = null, liveness = LeaseOwnerLiveness { error("Owner-free route consulted nonexistent owner") })).attempt)
        assertEquals("INGESTING", f.db.sessions[f.db.child.value]!!.state)
    }

    @Test fun liveUnknownMismatchedOrStaleOwnerNeverRetiresTheOriginalAttempt() = runTest {
        for (mode in listOf("live", "unknown", "owner", "generation", "missing")) {
            val f = Fixture(); f.ended(); val row = f.db.files[f.intent.transactionId.value]; val job = f.db.jobs[f.intent.jobId.value]
            val result = when (mode) {
                "live" -> f.restart(liveness = LeaseOwnerLiveness { LeaseOwnerState.ALIVE })
                "unknown" -> f.restart(liveness = LeaseOwnerLiveness { LeaseOwnerState.UNKNOWN })
                "owner" -> f.restart(owner = UUID.randomUUID().toString())
                "generation" -> f.restart(generation = 2)
                else -> f.restart(owner = null)
            }
            assertNull(mode, result); assertSame(row, f.db.files[f.intent.transactionId.value]); assertSame(job, f.db.jobs[f.intent.jobId.value]); assertEquals(1, f.db.files.size)
        }
    }

    @Test fun sealedTerminalOrNeverStartedAttemptsCannotBeDiscardedAsPartialRetries() = runTest {
        for (state in listOf("INTENT", "SEALED", "MOVED", "COMMITTED", "CANCELED", "FAILED", "QUARANTINED")) {
            val f = Fixture(); f.ended(); f.db.files[f.intent.transactionId.value] = f.db.files[f.intent.transactionId.value]!!.copy(state = state)
            assertNull(state, f.restart()); assertEquals(1, f.db.files.size); assertEquals(f.intent.transactionId.value, f.db.sessions[f.db.child.value]!!.transactionId)
        }
        val f = Fixture(); f.ended(); val row = f.db.files[f.intent.transactionId.value]!!
        f.db.files[row.transactionId] = row.copy(sealSha256 = "a".repeat(64))
        assertNull(f.restart()); assertEquals(1, f.db.jobs.size)
    }

    @Test fun canceledExpiredOrChangedMembershipCannotAllocateAReplacement() = runTest {
        for (mode in listOf("canceled", "expired", "parent", "destination", "membership", "uri")) {
            val f = Fixture(); f.ended(); val child = f.db.sessions[f.db.child.value]!!; val parent = f.db.sessions[f.db.parent.value]!!
            when (mode) {
                "canceled" -> f.db.sessions[child.sessionId] = child.copy(state = "CANCELED")
                "expired" -> f.db.now = child.expiresAt
                "parent" -> f.db.sessions[parent.sessionId] = parent.copy(state = "CANCELED")
                "destination" -> f.db.catalog.documents[f.db.document.value] = f.db.catalog.documents[f.db.document.value]!!.copy(lifecycle = "TOMBSTONED")
                "membership" -> f.db.sessions[parent.sessionId] = parent.copy(expectedMembershipToken = "0".repeat(64))
                "uri" -> f.db.sessions[child.sessionId] = child.copy(uriText = null)
            }
            assertNull(mode, f.restart()); assertEquals(1, f.db.files.size); assertEquals(1, f.db.jobs.size)
        }
    }

    @Test fun mismatchedCanonicalInputCannotBeRetiredAsTheRequestedOperation() = runTest {
        val f = Fixture(); f.ended(); val prior = f.db.jobs[f.intent.jobId.value]!!
        f.db.jobs[prior.jobId] = prior.copy(input = byteArrayOf(9))
        try { f.restart(); fail("Corrupt job input obtained a successor") }
        catch (failure: RepositoryFailure) { assertEquals(OperationErrorCode.INTEGRITY_FAILED, failure.error.code) }
        assertEquals(1, f.db.files.size); assertEquals(1, f.db.jobs.size)
    }

    @Test fun failedPhaseJobOrChildWriteRollsBackAllRetirementAndNewIntentChanges() = runTest {
        for (mode in listOf("phase", "job", "child")) {
            val f = Fixture(); f.ended(); val row = f.db.files[f.intent.transactionId.value]; val job = f.db.jobs[f.intent.jobId.value]; val child = f.db.sessions[f.db.child.value]
            if (mode == "phase") f.db.rejectPhaseState = "QUARANTINED"
            if (mode == "child") f.db.rejectChildUpdate = true
            val jobs = if (mode == "job") object : JobLeaseQueries by f.db { override fun updateLease(row: JobRow, expectedGeneration: Long) = 0 } else f.db
            try { f.restart(jobs = jobs); fail("A failed $mode write did not abort the metadata command") }
            catch (failure: RepositoryFailure) { assertTrue(failure.error.code in setOf(OperationErrorCode.STORAGE_FAILED, OperationErrorCode.REVISION_CONFLICT)) }
            assertSame(row, f.db.files[f.intent.transactionId.value]); assertSame(job, f.db.jobs[f.intent.jobId.value]); assertSame(child, f.db.sessions[f.db.child.value]); assertEquals(1, f.db.files.size); assertEquals(1, f.db.jobs.size)
        }
    }

    @Test fun attemptOverflowCannotReuseAnEarlierKeyOrModifyTheOldAttempt() = runTest {
        val f = Fixture(); f.ended(); f.intent = f.intent.copy(attempt = Long.MAX_VALUE)
        val bytes = IngestJournalCodec.encodeIntent(f.intent); val row = f.db.files[f.intent.transactionId.value]!!; val job = f.db.jobs[f.intent.jobId.value]!!
        f.db.files[row.transactionId] = row.copy(attempt = Long.MAX_VALUE, payload = bytes)
        f.db.jobs[job.jobId] = job.copy(idempotenceKey = "ingest-v1:${f.intent.sessionId.value}:${Long.MAX_VALUE}", input = bytes, canonicalInputSha256 = IngestJobIdentity.hash(bytes))
        try { f.restart(); fail("Overflow allocated a reused attempt key") }
        catch (failure: RepositoryFailure) { assertEquals(OperationErrorCode.RESOURCE_LIMIT, failure.error.code) }
        assertEquals(1, f.db.files.size); assertEquals(1, f.db.jobs.size); assertEquals("COPYING", f.db.files[row.transactionId]!!.state)
    }
}
