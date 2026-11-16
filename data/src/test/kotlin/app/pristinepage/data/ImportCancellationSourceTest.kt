package app.pristinepage.data

import app.pristinepage.data.model.*
import app.pristinepage.data.repository.RoomImportSessions
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Real SourceStore/file/journal/lease/owner and cancellation command composition; only provider, inspector and unavailable Room substituted. Native Room/codec completion fences remain separate evidence. Unique synthetic files are retained. */
class ImportCancellationSourceTest {
    private fun sessions(f: SourceStoreTest.Fixture) = RoomImportSessions(f.db.metadata, f.db, { f.db.now }, Dispatchers.Unconfined)

    @Test fun abandonmentAfterInspectionFencesLateSealPublicationWithoutDeletingStaging() = runTest {
        val f = SourceStoreTest.Fixture()
        f.duringInspection = { runBlocking { sessions(f).cancelBatch(f.db.parent) } }
        val result = f.store.ingestBoundProvider(f.db.uri, f.db.child) as IngestResult.Rejected
        assertEquals(OperationErrorCode.CANCELED, result.error.code)
        assertEquals("CANCELED", f.db.sessions[f.db.parent.value]!!.state); assertEquals("CANCELED", f.db.sessions[f.db.child.value]!!.state)
        assertTrue(f.db.catalog.pages.isEmpty()); assertTrue(f.db.catalog.sources.isEmpty()); assertTrue(f.db.blobs.isEmpty()); assertTrue(f.db.catalog.references.isEmpty())
        val row = f.db.files.values.single(); assertArrayEquals(f.bytes, File(f.root, row.stagingPath!!).readBytes()); assertFalse(File(f.root, row.finalPath!!).exists())
        val before = listOf(f.db.files.toMap(), f.db.jobs.toMap(), f.db.sessions.toMap())
        assertEquals(result, f.store.ingestBoundProvider(f.db.uri, f.db.child)); assertEquals(before, listOf(f.db.files.toMap(), f.db.jobs.toMap(), f.db.sessions.toMap()))
        assertEquals(1, f.opened); assertEquals(1, f.closed); assertEquals(1, f.inspected)
        // The file/journal/job are deliberately retained, not retroactively declared finalized or physically reclaimed by session cancellation.
        assertEquals("COPYING", row.state)
    }

    @Test fun cancellationAfterCommitCannotRewriteAReceiptSourceOrItsOwnership() = runTest {
        val f = SourceStoreTest.Fixture(); val first = f.store.ingestBoundProvider(f.db.uri, f.db.child) as IngestResult.Committed
        val before = listOf(f.db.catalog.pages.toMap(), f.db.catalog.sources.toMap(), f.db.blobs.toMap(), f.db.catalog.references.toMap(), f.db.files.toMap(), f.db.jobs.toMap(), f.db.sessions.toMap())
        sessions(f).cancelBatch(f.db.parent)
        assertEquals(before, listOf(f.db.catalog.pages.toMap(), f.db.catalog.sources.toMap(), f.db.blobs.toMap(), f.db.catalog.references.toMap(), f.db.files.toMap(), f.db.jobs.toMap(), f.db.sessions.toMap()))
        assertEquals(first, f.store.ingestBoundProvider(f.db.uri, f.db.child)); assertEquals(1, f.opened)
        val row = f.db.files.values.single(); assertArrayEquals(f.bytes, File(f.root, row.finalPath!!).readBytes())
    }

    @Test fun cancellationBeforeAnyAttemptPreventsProviderIoAndJournalCreation() = runTest {
        val f = SourceStoreTest.Fixture(); sessions(f).cancelBatch(f.db.parent)
        val result = f.store.ingestBoundProvider(f.db.uri, f.db.child) as IngestResult.Rejected
        assertEquals(OperationErrorCode.CANCELED, result.error.code)
        assertEquals(0, f.opened); assertEquals(0, f.inspected); assertTrue(f.db.files.isEmpty()); assertTrue(f.db.jobs.isEmpty()); assertTrue(f.db.catalog.pages.isEmpty())
    }
}
