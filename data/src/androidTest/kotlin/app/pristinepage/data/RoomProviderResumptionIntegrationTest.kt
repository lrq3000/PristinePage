package app.pristinepage.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Room/private-file resumption with synthetic provider/normalized inspection. Reopen is not process death; original bytes, old attempts and unique test databases are retained, not deleted. Host REDs precede the production route; these cases add native integration evidence only when actually run. */
@RunWith(AndroidJUnit4::class)
class RoomProviderResumptionIntegrationTest {
    @Test fun reopenedPendingBatchResumesOnlySixteenSessionsAndFinishesTheRemainingOneNextPass() = runBlocking {
        RoomSourceIngestIntegrationTest.Fixture().use { f ->
            val children = f.reserve(17)
            for (child in children) RoomIngestPreparation(f.database).prepare(child, f.reference.toString())
            val plannedPages = children.map { f.database.importSessions().session(it.value)!!.plannedPageId }
            f.reopen()
            val recovery = RoomRecoveryCoordinator(f.database, f.files, f.owners, f.store as BoundSourceIngest)
            val first = recovery.reconcile()
            assertEquals(17L, first.inspected); assertEquals(16L, first.completed); assertEquals(16L, first.resumed); assertEquals(1L, first.blocked)
            assertEquals(16, f.opened); assertEquals(16, f.closed); assertEquals(16L, f.count("Page"))
            val second = recovery.reconcile()
            assertEquals(1L, second.completed); assertEquals(0L, second.blocked)
            assertEquals(17, f.opened); assertEquals(17, f.closed); assertEquals(17L, f.count("Page")); assertEquals(34L, f.count("SourceReference"))
            val pages = f.database.catalog().activePages(f.document.value)
            assertEquals(plannedPages, pages.map { it.pageId }); assertEquals((0..16).toList(), pages.map { it.ordinal })
            val parent = f.database.importSessions().session(children[0].value)!!.parentSessionId!!
            assertEquals("COMMITTED", f.database.importSessions().session(parent)!!.state)
            assertEquals(0L, recovery.reconcile().completed); assertEquals(18L, f.database.catalog().document(f.document.value)!!.documentRevision)
            assertEquals(17, f.opened); f.assertForeignKeys()
        }
    }

    @Test fun reopenedUnsealedRetryDelegatesToTheSameStoreAndPreservesThePriorAttemptBytes() = runBlocking {
        RoomSourceIngestIntegrationTest.Fixture(enableRetry = true).use { f ->
            val child = f.reserve().single()
            f.inspectionFailure = RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "synthetic_resumption_storage", child.value))
            assertTrue(f.store.ingest(f.reference, child) is IngestResult.Pending)
            val old = RoomIngestLookup(f.database).find(child)!!
            val stage = File(f.root, "staging/${old.transactionId.value}/original.part"); val retained = stage.readBytes()
            f.inspectionFailure = null; f.reopen()
            val recovery = RoomRecoveryCoordinator(f.database, f.files, f.owners, f.store as BoundSourceIngest)
            val report = recovery.reconcile(); assertEquals(1L, report.completed); assertEquals(1L, report.resumed)
            val next = RoomIngestLookup(f.database).find(child)!!
            assertEquals(2L, next.attempt); assertEquals(old.pageId, next.pageId); assertNotEquals(old.transactionId, next.transactionId)
            assertEquals("QUARANTINED", f.database.ingestPreparation().fileTransaction(old.transactionId.value)!!.state)
            assertEquals("INTERRUPTED", f.database.jobLeases().job(old.jobId.value)!!.state)
            assertArrayEquals(retained, stage.readBytes()); assertArrayEquals(f.bytes, f.files.finalFile(next.sourceBlobId.value).readBytes())
            assertEquals(2, f.opened); assertEquals(2, f.closed); assertEquals(1L, f.count("Page")); assertEquals(2L, f.count("SourceReference"))
            assertEquals(0L, recovery.reconcile().completed); assertEquals(2, f.opened); f.assertForeignKeys()
        }
    }

    @Test fun revokedSyntheticProviderProducesReselectionWithoutAnyVisibleSourceOrPage() = runBlocking {
        RoomSourceIngestIntegrationTest.Fixture().use { f ->
            val child = f.reserve().single(); RoomIngestPreparation(f.database).prepare(child, f.reference.toString())
            f.reopen(); f.providerFailure = SecurityException("synthetic resumption permission loss")
            val recovery = RoomRecoveryCoordinator(f.database, f.files, f.owners, f.store as BoundSourceIngest)
            val report = recovery.reconcile(); assertEquals(1L, report.blocked); assertEquals(0L, report.completed); assertEquals(0L, report.resumed)
            val issue = report.safeIssues.single(); assertEquals(OperationErrorCode.PERMISSION_LOST, issue.error!!.code); assertEquals(RecoveryAction.RESELECT, issue.action)
            assertEquals("FAILED", f.database.importSessions().session(child.value)!!.state); assertNull(f.database.importSessions().session(child.value)!!.uriText)
            for (table in listOf("Page", "Source", "SourceBlob", "RecipeRevision", "SourceReference")) assertEquals(0L, f.count(table))
            assertEquals(1, f.opened); assertEquals(0, f.closed); f.assertForeignKeys()
        }
    }

    @Test fun actualScopedLiveIntentStaysBlockedUntilItsOwnerEndsThenUsesAFreshLeaseGeneration() = runBlocking {
        RoomSourceIngestIntegrationTest.Fixture().use { f ->
            val child = f.reserve().single(); val intent = RoomIngestPreparation(f.database).prepare(child, f.reference.toString())
            assertTrue(RoomIngestPhases(f.database).admit(intent))
            val recovery = RoomRecoveryCoordinator(f.database, f.files, f.owners, f.store as BoundSourceIngest)
            f.owners.run { owner ->
                assertTrue(RoomJobLeases(f.database, f.owners).acquireAdmitted(intent.jobId, owner, 300_000L) is AcquireLeaseResult.Granted)
                val live = recovery.reconcile(); assertEquals(1L, live.blocked); assertEquals(0L, live.resumed); assertEquals(0, f.opened)
            }
            assertEquals(1L, recovery.reconcile().completed)
            assertEquals(2L, f.database.jobLeases().job(intent.jobId.value)!!.leaseGeneration)
            assertEquals(1, f.opened); assertEquals(1L, f.count("Page")); assertEquals(0L, recovery.reconcile().completed); f.assertForeignKeys()
        }
    }

    @Test fun unchangedNativePendingPrefixCannotConsumeEveryLaterPassBeforeAHealthyImport() = runBlocking {
        RoomSourceIngestIntegrationTest.Fixture().use { f ->
            val children = f.reserve(17)
            val intents = children.map { RoomIngestPreparation(f.database).prepare(it, f.reference.toString()) }.sortedBy { it.transactionId.value }
            val healthy = intents.last(); var calls = 0
            val consumer = object : BoundSourceIngest {
                override suspend fun ingestBoundProvider(boundReference: String, session: ImportSessionId): IngestResult {
                    calls++
                    return if (session == healthy.sessionId) (f.store as BoundSourceIngest).ingestBoundProvider(boundReference, session)
                    else IngestResult.Pending(session, FileTransactionState.INTENT)
                }
            }
            val recovery = RoomRecoveryCoordinator(f.database, f.files, f.owners, consumer)
            assertEquals(0L, recovery.reconcile().completed); assertEquals(16, calls); assertEquals(0, f.opened)
            calls = 0
            val next = recovery.reconcile(); assertEquals(1L, next.completed); assertEquals(17L, next.inspected); assertTrue(calls in 1..16)
            assertEquals(listOf(healthy.pageId.value), f.database.catalog().activePages(f.document.value).map { it.pageId })
            for (intent in intents.dropLast(1)) {
                assertEquals("INTENT", f.database.ingestPreparation().fileTransaction(intent.transactionId.value)!!.state)
                assertEquals("PENDING", f.database.jobLeases().job(intent.jobId.value)!!.state)
            }
            assertEquals(1, f.opened); assertEquals(1L, f.count("Page")); assertEquals(0L, recovery.reconcile().completed); f.assertForeignKeys()
        }
    }
}
