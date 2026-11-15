package app.pristinepage.data

import android.database.Cursor
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Room/closed-file proof and transaction evidence for ordinary freezes only. Synthetic source inspection/admission are explicit; no pixel render, encoder, PDF profile, process-death or physical performance is established. Keep every unique DB/file. */
@RunWith(AndroidJUnit4::class)
class RoomExportSnapshotIntegrationTest {
    private suspend fun populate(f: RoomSourceIngestIntegrationTest.Fixture) {
        for (child in f.reserve(2)) assertTrue(f.store.ingest(f.reference, child) is IngestResult.Committed)
    }
    private fun settings(f: RoomSourceIngestIntegrationTest.Fixture) = ExportSettings(ExportFormat.PNG, f.database.catalog().activePages(f.document.value).map { PageOutputSettings(PageId(it.pageId), OutputSpec(PixelSize(3, 2), RenderPurpose.EXPORT)) }, null, null, emptyList())
    private fun repository(f: RoomSourceIngestIntegrationTest.Fixture, admit: (() -> OperationError?)? = null): DocumentRepository {
        val gate = ExportAdmission { snapshot ->
            assertFalse(f.database.inTransaction()); assertEquals(2, snapshot.pages.size); admit?.invoke()
        }
        val snapshots = RoomExportSnapshots(f.database, FileExportSourceProof(f.files), gate, "fixture-renderer-v1", "rgb8-v1", "fixture-reference", "binary64")
        return RoomDocumentRepository(RoomMetadataAccess(f.database), RecipeAdmission { _, _ -> null }, snapshots = snapshots)
    }
    private fun revision(f: RoomSourceIngestIntegrationTest.Fixture) = f.database.catalog().document(f.document.value)!!.documentRevision
    private fun count(f: RoomSourceIngestIntegrationTest.Fixture, table: String, kind: String? = null): Long {
        require(table in setOf("ExportSnapshot", "ExportPage", "Job", "SourceReference"))
        val sql = if (kind == null) "SELECT COUNT(*) FROM $table" else "SELECT COUNT(*) FROM Job WHERE kind=?"
        return f.database.openHelper.readableDatabase.query(sql, if (kind == null) emptyArray() else arrayOf(kind)).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getLong(0) }
    }
    private fun state(f: RoomSourceIngestIntegrationTest.Fixture): List<List<List<String>>> = listOf("Document", "SourceBlob", "Source", "Page", "RecipeRevision", "RecipeMask", "SourceReference", "UndoEntry", "Mask", "MaskReference", "ExportSnapshot", "ExportPage", "Job").map { table ->
        f.database.openHelper.readableDatabase.query("SELECT * FROM $table LIMIT 1001").use { cursor ->
            val rows = ArrayList<List<String>>()
            while (cursor.moveToNext()) rows += (0 until cursor.columnCount).map { index ->
                when (cursor.getType(index)) {
                    Cursor.FIELD_TYPE_NULL -> "NULL"
                    Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index).joinToString("") { "%02x".format(it.toInt() and 255) }
                    else -> cursor.getString(index)
                }
            }
            check(rows.size < 1001); rows.sortedBy { it.joinToString("\u0000") }
        }
    }

    @Test fun frozenRowsReopenWithReviewedOrderAndRecipesAfterLaterAlbumEdits() = runBlocking {
        RoomSourceIngestIntegrationTest.Fixture().use { f ->
            populate(f); val revision = revision(f); val settings = settings(f); val repo = repository(f)
            val frozen = repo.freezeExport(f.document, revision, settings); val ids = frozen.pages.map { it.pageId }
            assertEquals(2, frozen.pages.size); assertEquals(2L, count(f, "ExportPage")); assertEquals(2L, count(f, "Job", "PAGE_RENDER")); assertEquals(1L, count(f, "Job", "EXPORT_ASSEMBLY"))
            assertEquals(revision, revision(f)); assertTrue(repo.rotatePage(ids.first(), 1, 1) is DocumentMutationResult.Applied)
            assertTrue(repo.reorderPages(f.document, revision + 1, ids.reversed()) is DocumentMutationResult.Applied)
            f.reopen(); val rows = f.database.exportSnapshots().exportPages(frozen.snapshotId.value)
            assertEquals(ids.map { it.value }, rows.map { it.originalPageId }); assertTrue(rows.all { it.recipeRevision == 1L && RecipeCodec.decode(it.recipe).geometry.outputQuarterTurns == 0 })
            assertEquals(frozen.snapshotHash, f.database.exportSnapshots().snapshot(frozen.snapshotId.value)!!.snapshotSha256)
            assertEquals(ids.reversed().map { it.value }, f.database.catalog().activePages(f.document.value).map { it.pageId })
            for (page in frozen.pages) assertArrayEquals(f.bytes, f.files.finalFile(page.sourceBlobId.value).readBytes())
            assertEquals(2, f.opened); f.assertForeignKeys()
        }
    }

    @Test fun intentionalRepeatCreatesIndependentAssemblyButReusesExactUnleasedPageJobs() = runBlocking {
        RoomSourceIngestIntegrationTest.Fixture().use { f ->
            populate(f); val revision = revision(f); val settings = settings(f); val refs = count(f, "SourceReference"); val repo = repository(f)
            val first = repo.freezeExport(f.document, revision, settings); f.reopen()
            val second = repository(f).freezeExport(f.document, revision, settings)
            assertNotEquals(first.snapshotId, second.snapshotId); assertEquals(first.snapshotHash, second.snapshotHash)
            assertEquals(2L, count(f, "ExportSnapshot")); assertEquals(4L, count(f, "ExportPage")); assertEquals(2L, count(f, "Job", "PAGE_RENDER")); assertEquals(2L, count(f, "Job", "EXPORT_ASSEMBLY")); assertEquals(refs + 6, count(f, "SourceReference"))
            f.database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM Job WHERE kind='PAGE_RENDER' AND snapshotId IS NULL AND pageOrdinal IS NULL AND state='PENDING' AND leaseOwner IS NULL AND leaseGeneration=0").use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals(2L, cursor.getLong(0)) }
            for (page in second.pages) assertEquals(5L, f.database.catalog().source(page.sourceId.value)!!.refCount)
            f.assertForeignKeys()
        }
    }

    @Test fun lateRealSqlFailureRollsBackSnapshotPagesJobsPinsAndCounters() = runBlocking {
        RoomSourceIngestIntegrationTest.Fixture().use { f ->
            populate(f); val revision = revision(f); val settings = settings(f); val before = state(f)
            f.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER freeze_reject_second BEFORE INSERT ON ExportPage WHEN NEW.ordinal=1 BEGIN SELECT RAISE(ABORT,'synthetic late freeze fault'); END")
            val error = try { repository(f).freezeExport(f.document, revision, settings); throw AssertionError("Late write failure returned a snapshot") } catch (failure: RepositoryFailure) { failure.error }
            assertEquals(OperationErrorCode.STORAGE_FAILED, error.code); f.reopen(); assertEquals(before, state(f)); f.assertForeignKeys()
        }
    }

    @Test fun changedOriginalRefusesBeforeAdmissionWithoutChangingMetadata() = runBlocking {
        RoomSourceIngestIntegrationTest.Fixture().use { f ->
            populate(f); val revision = revision(f); val settings = settings(f); val before = state(f)
            val source = f.database.catalog().source(f.database.catalog().activePages(f.document.value).first().sourceId)!!
            val original = f.files.finalFile(source.sourceBlobId); original.writeBytes(byteArrayOf(9, 2, 3, 4))
            val error = try { repository(f) { throw AssertionError("Bad source reached admission") }.freezeExport(f.document, revision, settings); throw AssertionError("Changed original froze") } catch (failure: RepositoryFailure) { failure.error }
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, error.code); f.reopen(); assertEquals(before, state(f)); assertArrayEquals(byteArrayOf(9, 2, 3, 4), original.readBytes()); f.assertForeignKeys()
        }
    }

    @Test fun revisionChangeDuringOutsideRoomAdmissionCannotPublishStaleFreeze() = runBlocking {
        RoomSourceIngestIntegrationTest.Fixture().use { f ->
            populate(f); val revision = revision(f); val settings = settings(f); val refs = count(f, "SourceReference")
            val repo = repository(f) {
                f.database.runInTransaction { val row = f.database.catalog().document(f.document.value)!!; assertEquals(1, f.database.catalog().updateDocument(row.copy(documentRevision = row.documentRevision + 1))) }; null
            }
            val error = try { repo.freezeExport(f.document, revision, settings); throw AssertionError("Stale freeze committed") } catch (failure: RepositoryFailure) { failure.error }
            assertEquals(OperationErrorCode.REVISION_CONFLICT, error.code); f.reopen(); assertEquals(0L, count(f, "ExportSnapshot")); assertEquals(0L, count(f, "Job", "PAGE_RENDER")); assertEquals(refs, count(f, "SourceReference")); f.assertForeignKeys()
        }
    }

    @Test fun corruptCanonicalJobInputCannotBeReusedAfterReopen() = runBlocking {
        RoomSourceIngestIntegrationTest.Fixture().use { f ->
            populate(f); val revision = revision(f); val settings = settings(f)
            repository(f).freezeExport(f.document, revision, settings)
            f.database.openHelper.writableDatabase.execSQL("UPDATE Job SET input=x'00' WHERE kind='PAGE_RENDER'")
            f.reopen(); val before = state(f)
            val error = try { repository(f).freezeExport(f.document, revision, settings); throw AssertionError("Corrupt job gained consumers") } catch (failure: RepositoryFailure) { failure.error }
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, error.code); f.reopen(); assertEquals(before, state(f)); f.assertForeignKeys()
        }
    }
}
