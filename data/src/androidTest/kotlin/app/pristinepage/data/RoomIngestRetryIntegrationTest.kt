package app.pristinepage.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pristinepage.data.model.*
import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.repository.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native Room/private files and exact retry transactions. Provider/normalized inspection remain synthetic; reopen is not process death, no original/fixture DB/file is deleted, and fault triggers belong only to each unique test database. */
@RunWith(AndroidJUnit4::class)
class RoomIngestRetryIntegrationTest {
    @Test fun ownerFreeStorageRetryAfterReopenPreservesOldBytesAndCommitsOnlyOneReservedPage() = runBlocking {
        RoomSourceIngestIntegrationTest.Fixture(enableRetry = true).use { f ->
            val child = f.reserve().single()
            f.inspectionFailure = RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "synthetic_retry_storage", child.value))
            assertTrue(f.store.ingest(f.reference, child) is IngestResult.Pending)
            val old = RoomIngestLookup(f.database).find(child)!!
            val row = f.database.ingestPreparation().fileTransaction(old.transactionId.value)!!
            assertEquals("RETRYABLE", row.state)
            val stage = File(f.root, row.stagingPath!!); assertArrayEquals(f.bytes, stage.readBytes())
            f.inspectionFailure = null; f.reopen()
            val result = f.store.ingest(f.reference, child) as IngestResult.Committed
            val next = RoomIngestLookup(f.database).find(child)!!
            assertEquals(2L, next.attempt); assertEquals(old.pageId, result.pageId); assertNotEquals(old.transactionId, next.transactionId)
            assertArrayEquals(f.bytes, stage.readBytes()); assertArrayEquals(f.bytes, f.files.finalFile(next.sourceBlobId.value).readBytes())
            assertEquals("QUARANTINED", f.database.ingestPreparation().fileTransaction(old.transactionId.value)!!.state)
            assertEquals("INTERRUPTED", f.database.jobLeases().job(old.jobId.value)!!.state)
            assertEquals(1L, f.count("Page")); assertEquals(2L, f.count("SourceReference")); assertEquals(2, f.opened); assertEquals(2, f.closed)
            assertEquals(result, f.store.ingest(f.reference, child)); assertEquals(2, f.opened); f.assertForeignKeys()
        }
    }

    @Test fun nativeInsertFailureRollsBackRetirementJobAndSessionPointerBeforeLaterRetry() = runBlocking {
        RoomSourceIngestIntegrationTest.Fixture(enableRetry = true).use { f ->
            val child = f.reserve().single()
            f.inspectionFailure = RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "synthetic_retry_storage", child.value))
            assertTrue(f.store.ingest(f.reference, child) is IngestResult.Pending)
            val old = RoomIngestLookup(f.database).find(child)!!; val prior = f.database.ingestPreparation().fileTransaction(old.transactionId.value)!!
            val stage = File(f.root, prior.stagingPath!!); val bytes = stage.readBytes()
            f.inspectionFailure = null
            f.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER retry_reject_successor BEFORE INSERT ON FileTransaction WHEN NEW.attempt=2 BEGIN SELECT RAISE(ABORT,'synthetic retry insert fault'); END")
            val rejected = f.store.ingest(f.reference, child) as IngestResult.Rejected
            assertEquals(OperationErrorCode.STORAGE_FAILED, rejected.error.code)
            assertEquals(old, RoomIngestLookup(f.database).find(child)); assertEquals("RETRYABLE", f.database.ingestPreparation().fileTransaction(old.transactionId.value)!!.state)
            assertEquals("RETRYABLE", f.database.jobLeases().job(old.jobId.value)!!.state)
            assertNull(f.database.ingestPreparation().ingestJobByKey("ingest-v1:${child.value}:2"))
            assertArrayEquals(bytes, stage.readBytes()); assertEquals(0L, f.count("Page")); assertEquals(1, f.opened)
            f.database.openHelper.writableDatabase.execSQL("DROP TRIGGER retry_reject_successor") // Exact unique test-owned injector only.
            f.reopen(); assertTrue(f.store.ingest(f.reference, child) is IngestResult.Committed)
            assertEquals(2L, RoomIngestLookup(f.database).find(child)!!.attempt); assertArrayEquals(bytes, stage.readBytes())
            assertEquals(1L, f.count("Page")); assertEquals(2, f.opened); f.assertForeignKeys()
        }
    }

    @Test fun nativeSealAheadOfJournalStaysForRecoveryRatherThanBeingDiscardedForRecopy() = runBlocking {
        RoomSourceIngestIntegrationTest.Fixture(enableRetry = true).use { f ->
            val child = f.reserve().single()
            f.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER retry_ignore_seal BEFORE UPDATE ON FileTransaction WHEN NEW.state='SEALED' BEGIN SELECT RAISE(IGNORE); END")
            assertTrue(f.store.ingest(f.reference, child) is IngestResult.Pending)
            val old = RoomIngestLookup(f.database).find(child)!!
            assertNotNull(f.files.recover(old.transactionId.value, null))
            assertTrue(f.store.ingest(f.reference, child) is IngestResult.Pending)
            assertEquals(old, RoomIngestLookup(f.database).find(child)); assertEquals(1, f.opened)
            assertNull(f.database.ingestPreparation().ingestJobByKey("ingest-v1:${child.value}:2"))
            f.database.openHelper.writableDatabase.execSQL("DROP TRIGGER retry_ignore_seal")
            f.reopen(); val recovery = RoomRecoveryCoordinator(f.database, f.files, f.owners)
            assertEquals(1L, recovery.reconcile().completed); assertEquals(0L, recovery.reconcile().completed)
            assertEquals(1L, f.count("Page")); assertEquals(1, f.opened); f.assertForeignKeys()
        }
    }

    @Test fun actualPostSealSyncFailureAndReadmissionAbortRetainTheAttemptUntilSameBytesCommit() = runBlocking {
        var fired = false
        val sync = DirectorySync { directory ->
            if (!fired && File(directory, "seal.json").isFile) { fired = true; throw IOException("synthetic one-shot seal directory fault") }
            true
        }
        RoomSourceIngestIntegrationTest.Fixture(enableRetry = true, directorySync = sync).use { f ->
            val child = f.reserve().single()
            assertTrue(f.store.ingest(f.reference, child) is IngestResult.Pending); assertTrue(fired)
            val intent = RoomIngestLookup(f.database).find(child)!!
            assertNotNull(f.files.recover(intent.transactionId.value, null)); assertEquals("RETRYABLE", f.database.ingestPreparation().fileTransaction(intent.transactionId.value)!!.state)
            f.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER retry_reject_readmission BEFORE UPDATE ON Job WHEN OLD.state='RETRYABLE' AND NEW.state='ADMITTED' BEGIN SELECT RAISE(ABORT,'synthetic sealed readmission fault'); END")
            val recovery = RoomRecoveryCoordinator(f.database, f.files, f.owners)
            val failed = recovery.reconcile(); assertEquals(1L, failed.blocked); assertEquals(0L, failed.resumed)
            assertEquals("RETRYABLE", f.database.ingestPreparation().fileTransaction(intent.transactionId.value)!!.state); assertEquals("RETRYABLE", f.database.jobLeases().job(intent.jobId.value)!!.state)
            assertEquals(0L, f.count("Page")); assertEquals(1, f.opened)
            f.database.openHelper.writableDatabase.execSQL("DROP TRIGGER retry_reject_readmission")
            f.reopen(); val resumed = RoomRecoveryCoordinator(f.database, f.files, f.owners)
            assertEquals(1L, resumed.reconcile().completed); assertEquals(0L, resumed.reconcile().completed)
            assertEquals(intent, RoomIngestLookup(f.database).find(child)); assertArrayEquals(f.bytes, f.files.finalFile(intent.sourceBlobId.value).readBytes())
            assertEquals(1L, f.count("Page")); assertEquals(2L, f.count("SourceReference")); assertEquals(1, f.opened); f.assertForeignKeys()
        }
    }
}
