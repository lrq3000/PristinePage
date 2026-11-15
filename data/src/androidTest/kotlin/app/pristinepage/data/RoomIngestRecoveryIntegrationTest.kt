package app.pristinepage.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native Room/SQLite/private-filesystem consuming recovery, with real same-process structured ENDED evidence. Provider/normalized inspection remain synthetic, close/reopen is not process death, and every unique fixture DB/file is retained. */
@RunWith(AndroidJUnit4::class)
class RoomIngestRecoveryIntegrationTest {
    @Test fun validSealAheadOfJournalReadmitsFreshOwnerAndCommitsOnceAfterReopen() = runBlocking {
        RoomSourceIngestIntegrationTest.Fixture().use { f ->
            val child = f.reserve().single()
            f.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER recovery_ignore_seal BEFORE UPDATE ON FileTransaction WHEN NEW.state='SEALED' BEGIN SELECT RAISE(IGNORE); END")
            assertTrue(f.store.ingest(f.reference, child) is IngestResult.Pending)
            val intent = RoomIngestLookup(f.database).find(child)!!
            val old = f.database.jobLeases().job(intent.jobId.value)!!
            assertEquals(LeaseOwnerState.ENDED, f.owners.state(old.leaseOwner!!))
            assertEquals("COPYING", f.database.ingestPreparation().fileTransaction(intent.transactionId.value)!!.state)
            f.database.openHelper.writableDatabase.execSQL("DROP TRIGGER recovery_ignore_seal") // This unique test-owned fault injector only; no document/file cleanup.
            f.reopen()
            val recovery = RoomRecoveryCoordinator(f.database, f.files, f.owners)
            val first = recovery.reconcile()
            assertEquals(1L, first.inspected); assertEquals(1L, first.resumed); assertEquals(1L, first.completed); assertEquals(0L, first.blocked)
            val done = f.database.jobLeases().job(intent.jobId.value)!!
            assertEquals(old.leaseGeneration + 1, done.leaseGeneration); assertEquals("SUCCEEDED", done.state); assertNull(done.leaseOwner)
            assertEquals(0L, recovery.reconcile().completed)
            assertEquals(1L, f.count("Page")); assertEquals(2L, f.count("SourceReference"))
            assertEquals(2L, f.database.catalog().document(f.document.value)!!.documentRevision)
            assertArrayEquals(f.bytes, f.files.finalFile(intent.sourceBlobId.value).readBytes())
            assertEquals(1, f.opened); assertEquals(1, f.closed); f.assertForeignKeys()
        }
    }

    @Test fun finalAheadOfMovedJournalReusesVerifiedOriginalWithoutProviderReread() = runBlocking {
        RoomSourceIngestIntegrationTest.Fixture().use { f ->
            val child = f.reserve().single()
            f.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER recovery_ignore_move BEFORE UPDATE ON FileTransaction WHEN NEW.state='MOVED' BEGIN SELECT RAISE(IGNORE); END")
            assertTrue(f.store.ingest(f.reference, child) is IngestResult.Pending)
            val intent = RoomIngestLookup(f.database).find(child)!!
            val row = f.database.ingestPreparation().fileTransaction(intent.transactionId.value)!!
            assertEquals("SEALED", row.state); assertFalse(File(f.root, row.stagingPath!!).exists())
            val target = f.files.finalFile(intent.sourceBlobId.value); assertArrayEquals(f.bytes, target.readBytes())
            f.database.openHelper.writableDatabase.execSQL("DROP TRIGGER recovery_ignore_move")
            f.reopen()
            val recovery = RoomRecoveryCoordinator(f.database, f.files, f.owners)
            assertEquals(1L, recovery.reconcile().completed); assertEquals(0L, recovery.reconcile().completed)
            assertArrayEquals(f.bytes, target.readBytes()); assertEquals(1L, f.count("Page")); assertEquals(1, f.opened)
            f.assertForeignKeys()
        }
    }

    @Test fun movedOriginalSurvivesNativeCommitFailureThenRecoveryCommitsExactlyOnce() = runBlocking {
        RoomSourceIngestIntegrationTest.Fixture().use { f ->
            val child = f.reserve().single()
            f.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER recovery_reject_recipe BEFORE INSERT ON RecipeRevision BEGIN SELECT RAISE(ABORT,'synthetic recovery fault'); END")
            assertTrue(f.store.ingest(f.reference, child) is IngestResult.Pending)
            val intent = RoomIngestLookup(f.database).find(child)!!
            assertEquals("MOVED", f.database.ingestPreparation().fileTransaction(intent.transactionId.value)!!.state)
            assertEquals(0L, f.count("Page")); assertEquals(0L, f.count("SourceReference"))
            assertArrayEquals(f.bytes, f.files.finalFile(intent.sourceBlobId.value).readBytes())
            f.database.openHelper.writableDatabase.execSQL("DROP TRIGGER recovery_reject_recipe")
            f.reopen()
            val recovery = RoomRecoveryCoordinator(f.database, f.files, f.owners)
            assertEquals(1L, recovery.reconcile().completed); assertEquals(0L, recovery.reconcile().completed)
            assertEquals(1L, f.count("SourceBlob")); assertEquals(1L, f.count("Source")); assertEquals(1L, f.count("RecipeRevision")); assertEquals(2L, f.count("SourceReference"))
            assertEquals(1, f.opened); f.assertForeignKeys()
        }
    }

    @Test fun absentOwnerRegistryCannotPublishSealedBytesEvenAfterDatabaseReopen() = runBlocking {
        RoomSourceIngestIntegrationTest.Fixture().use { f ->
            val child = f.reserve().single()
            f.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER recovery_unknown_ignore BEFORE UPDATE ON FileTransaction WHEN NEW.state='SEALED' BEGIN SELECT RAISE(IGNORE); END")
            assertTrue(f.store.ingest(f.reference, child) is IngestResult.Pending)
            val intent = RoomIngestLookup(f.database).find(child)!!
            val old = f.database.jobLeases().job(intent.jobId.value)!!
            f.database.openHelper.writableDatabase.execSQL("DROP TRIGGER recovery_unknown_ignore")
            f.reopen()
            val report = RoomRecoveryCoordinator(f.database, f.files, LocalOperationOwners()).reconcile()
            assertEquals(1L, report.blocked); assertEquals(0L, report.resumed); assertEquals(0L, report.completed)
            val unchanged = f.database.jobLeases().job(intent.jobId.value)!!
            assertEquals(old.leaseOwner, unchanged.leaseOwner); assertEquals(old.leaseGeneration, unchanged.leaseGeneration)
            assertEquals(0L, f.count("Page")); assertEquals("COPYING", f.database.ingestPreparation().fileTransaction(intent.transactionId.value)!!.state)
            assertEquals(1, f.opened); f.assertForeignKeys()
        }
    }

    @Test fun nativeListingTraversesMoreThanOnePageWithoutTurningPendingIntentsIntoSuccesses() = runBlocking {
        RoomSourceIngestIntegrationTest.Fixture().use { f ->
            val documents = RoomDocumentRepository(RoomMetadataAccess(f.database), RecipeAdmission { _, _ -> null })
            repeat(17) {
                val document = documents.createDocument("Synthetic pending recovery")
                val sessions = RoomImportSessions(f.database)
                val parent = sessions.beginImport(document, 1, null).parentId
                val child = sessions.reserveImportItems(parent, 1).childIds.single()
                sessions.bindImportItem(parent, 0, f.reference)
                RoomIngestPreparation(f.database).prepare(child, f.reference.toString())
            }
            f.reopen()
            val recovery = RoomRecoveryCoordinator(f.database, f.files, f.owners)
            repeat(2) {
                val report = recovery.reconcile()
                assertEquals(17L, report.inspected); assertEquals(17L, report.blocked); assertEquals(0L, report.completed); assertEquals(0L, report.resumed)
                assertEquals(17, report.safeIssues.map { it.operationId }.toSet().size)
            }
            assertEquals(0, f.opened); assertEquals(0L, f.count("Page")); f.assertForeignKeys()
        }
    }
}
