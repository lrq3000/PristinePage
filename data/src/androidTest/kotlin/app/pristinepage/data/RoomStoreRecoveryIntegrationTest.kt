package app.pristinepage.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pristinepage.data.db.DocumentRow
import app.pristinepage.data.db.UndoEntryRow
import app.pristinepage.data.files.SourceFiles
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real composed Room/ingest/audit/expiry/facade paths. All generated files/databases remain retained. Synthetic inspection, reopen and a clean partial-domain report do not prove decoder, process-death or whole-store integrity. */
@RunWith(AndroidJUnit4::class)
class RoomStoreRecoveryIntegrationTest {
    private fun repository(f: RoomRepositoryIntegrationTest.Fixture): DocumentRepository {
        val files = SourceFiles(File(f.context.filesDir, "t03-composed-${f.name}"))
        val ingest = RoomRecoveryCoordinator(f.database, files, LocalOperationOwners())
        return RoomDocumentRepository(RoomMetadataAccess(f.database), RecipeAdmission { _, _ -> null }, recovery = RoomStoreRecovery(f.database, ingest))
    }

    @Test fun canonicalEntryRecoversMovedOriginalOnceThenAuditsTheNewlyCommittedRows() = runBlocking {
        RoomSourceIngestIntegrationTest.Fixture().use { f ->
            val child = f.reserve().single()
            f.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER composed_reject_recipe BEFORE INSERT ON RecipeRevision BEGIN SELECT RAISE(ABORT,'synthetic composed recovery fault'); END")
            assertTrue(f.store.ingest(f.reference, child) is IngestResult.Pending)
            val intent = RoomIngestLookup(f.database).find(child)!!
            assertEquals("MOVED", f.database.ingestPreparation().fileTransaction(intent.transactionId.value)!!.state)
            val original = f.files.finalFile(intent.sourceBlobId.value); assertArrayEquals(f.bytes, original.readBytes())
            // Remove only this unique fixture's fault trigger so the retained attempt can complete. No file/reference/history cleanup.
            f.database.openHelper.writableDatabase.execSQL("DROP TRIGGER composed_reject_recipe")
            f.reopen()
            val recovery = RoomStoreRecovery(f.database, RoomRecoveryCoordinator(f.database, f.files, f.owners))
            val repository: DocumentRepository = RoomDocumentRepository(RoomMetadataAccess(f.database), RecipeAdmission { _, _ -> null }, recovery = recovery)
            assertEquals(RecoveryReport(6, 1, 1, 0, 0, emptyList()), repository.reconcile())
            assertEquals(RecoveryReport(5, 0, 0, 0, 0, emptyList()), repository.reconcile())
            assertEquals(1L, f.count("Page")); assertEquals(1L, f.count("RecipeRevision")); assertEquals(2L, f.count("SourceReference"))
            assertEquals(2L, f.database.catalog().document(f.document.value)!!.documentRevision)
            assertArrayEquals(f.bytes, original.readBytes()); assertEquals(1, f.opened); assertEquals(1, f.closed); f.assertForeignKeys()
        }
    }

    @Test fun canonicalExpiryPreservesAuditMismatchAndEveryNonStateByteAcrossReopen() = runBlocking {
        RoomRepositoryIntegrationTest.Fixture().use { f ->
            val edit = f.repository.rotatePage(f.pages[0], 1, 1) as DocumentMutationResult.Applied
            val undo = checkNotNull(edit.undoId).value
            f.database.runInTransaction {
                f.database.openHelper.writableDatabase.execSQL("UPDATE UndoEntry SET expiresAt=1 WHERE undoId=?", arrayOf<Any>(undo))
                assertEquals(1, f.database.catalog().setSourceRefCount(f.source, 0))
            }
            f.reopen(); val before = f.metadataState(ignoreUndoState = true)
            val first = repository(f).reconcile(); assertEquals(8L, first.inspected); assertEquals(1L, first.completed); assertEquals(1L, first.blocked)
            val issue = first.safeIssues.single(); assertEquals(f.source, issue.operationId); assertEquals("SOURCE_REFERENCE_COUNT", issue.state)
            assertEquals(RecoveryAction.REVIEW_INTEGRITY, issue.action)
            f.reopen(); assertEquals("EXPIRED", f.database.catalog().undo(undo)!!.state); assertEquals(0L, f.database.catalog().source(f.source)!!.refCount)
            assertEquals(before, f.metadataState(ignoreUndoState = true))
            val second = repository(f).reconcile(); assertEquals(first.copy(inspected = 7, completed = 0), second)
            assertEquals(before, f.metadataState(ignoreUndoState = true)); f.assertForeignKeys()
        }
    }

    @Test fun actualSqlPaginatesAllRetainedLifecyclesWithoutReleasingSyntheticHistory() = runBlocking {
        RoomRepositoryIntegrationTest.Fixture().use { f ->
            f.database.runInTransaction {
                val q = f.database.catalog()
                repeat(70) { index ->
                    val document = UUID.randomUUID().toString()
                    q.insertDocument(DocumentRow(document, "Synthetic expiry", 1, listOf("ACTIVE", "TOMBSTONED", "PURGING")[index % 3], 0, 0, null))
                    q.insertUndo(UndoEntryRow(UUID.randomUUID().toString(), document, byteArrayOf(1), byteArrayOf(2), "PAGE_REORDER", 1, 1, "AVAILABLE"))
                }
            }
            f.reopen(); val before = f.metadataState(ignoreUndoState = true)
            assertEquals(RecoveryReport(77, 70, 0, 0, 0, emptyList()), repository(f).reconcile())
            f.reopen(); assertEquals(before, f.metadataState(ignoreUndoState = true)); assertEquals(70L, f.count("UndoEntry"))
            f.database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM UndoEntry WHERE state='EXPIRED'").use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals(70L, cursor.getLong(0)) }
            assertEquals(RecoveryReport(7, 0, 0, 0, 0, emptyList()), repository(f).reconcile()); f.assertForeignKeys()
        }
    }

    @Test fun invalidEmptyDocumentIdIsNotSkippedOrAllowedToExpireTheRestOfItsPage() = runBlocking {
        RoomRepositoryIntegrationTest.Fixture().use { f ->
            val edit = f.repository.rotatePage(f.pages[0], 1, 1) as DocumentMutationResult.Applied
            f.database.runInTransaction {
                f.database.catalog().insertDocument(DocumentRow("", "Synthetic corrupt identity", 1, "ACTIVE", 0, 0, null))
                f.database.openHelper.writableDatabase.execSQL("UPDATE UndoEntry SET expiresAt=1 WHERE undoId=?", arrayOf<Any>(checkNotNull(edit.undoId).value))
            }
            f.reopen(); val before = f.metadataState()
            assertEquals("", f.database.catalog().retainedDocumentIdsAfter(null, 64).first())
            val failure = try { repository(f).reconcile(); throw AssertionError("Invalid initial document ID was omitted") }
            catch (failure: RepositoryFailure) { failure.error }
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, failure.code); assertEquals("invalid_store_recovery_listing", failure.safeMessageKey)
            f.reopen(); assertEquals(before, f.metadataState()); f.assertForeignKeys()
        }
    }
}
