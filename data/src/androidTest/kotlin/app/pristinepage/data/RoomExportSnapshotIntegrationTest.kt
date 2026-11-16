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
                    Cursor.FIELD_TYPE_BLOB -> {
                        // Fixture inventory fingerprints avoid millions of formatter allocations in the oversized-metadata refusal case; no production reader uses this diagnostic path.
                        val bytes = cursor.getBlob(index)
                        "blob/${bytes.size}/" + java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                    }
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

    private fun loader(f: RoomSourceIngestIntegrationTest.Fixture) = RoomExportSnapshots(f.database, ExportSourceProof { throw AssertionError("Metadata load cannot invoke file proof") }, ExportAdmission { throw AssertionError("Metadata load cannot invoke admission") }, "new-renderer-v2", "different-encoding", "different-backend", "different-precision")

    @Test fun committedLookupAfterReopenIgnoresRemovedLivePagesAndCurrentRendererConfig() = runBlocking {
        RoomSourceIngestIntegrationTest.Fixture().use { f ->
            populate(f); val repo = repository(f); val frozen = repo.freezeExport(f.document, revision(f), settings(f))
            for (page in frozen.pages) assertTrue(repo.removePage(page.pageId, revision(f)) is DocumentMutationResult.Applied)
            f.reopen(); assertTrue(f.database.catalog().activePages(f.document.value).isEmpty()); val before = state(f)
            val loaded = loader(f).load(frozen.snapshotId)
            assertEquals(frozen.snapshotHash, loaded.snapshotHash); assertEquals(frozen.documentRevision, loaded.documentRevision)
            assertEquals(frozen.pages.map { it.pageId }, loaded.pages.map { it.pageId }); assertTrue(loaded.pages.all { it.rendererVersion == "fixture-renderer-v1" })
            for ((old, restored) in frozen.pages.zip(loaded.pages)) assertArrayEquals(old.canonicalRecipe, restored.canonicalRecipe)
            f.reopen(); assertEquals(before, state(f)); f.assertForeignKeys()
        }
    }

    @Test fun alteredHashAndFutureLifecycleRefuseWithoutRewritingRetainedSnapshot() = runBlocking {
        RoomSourceIngestIntegrationTest.Fixture().use { f ->
            populate(f); val frozen = repository(f).freezeExport(f.document, revision(f), settings(f))
            for (future in listOf(false, true)) {
                if (future) f.database.openHelper.writableDatabase.execSQL("UPDATE ExportSnapshot SET state='FUTURE_STATE' WHERE snapshotId=?", arrayOf(frozen.snapshotId.value))
                else f.database.openHelper.writableDatabase.execSQL("UPDATE ExportSnapshot SET snapshotSha256=? WHERE snapshotId=?", arrayOf("f".repeat(64), frozen.snapshotId.value))
                f.reopen(); val before = state(f)
                val error = try { loader(f).load(frozen.snapshotId); throw AssertionError("Changed/future snapshot accepted") } catch (failure: RepositoryFailure) { failure.error }
                assertEquals(if (future) OperationErrorCode.UNSUPPORTED_VERSION else OperationErrorCode.INTEGRITY_FAILED, error.code)
                f.reopen(); assertEquals(before, state(f)); f.assertForeignKeys()
            }
        }
    }

    @Test fun rawSqliteIntegerAliasesAndFutureRecipeSchemasStayDistinctAndPreserved() = runBlocking {
        RoomSourceIngestIntegrationTest.Fixture().use { f ->
            populate(f); val frozen = repository(f).freezeExport(f.document, revision(f), settings(f)); val page = frozen.pages.first()
            val fields = listOf("ordinal" to 0, "width" to 3, "height" to 2, "recipeSchemaVersion" to 1, "requestedOutputWidth" to 3, "requestedOutputHeight" to 2, "outputWidth" to 3, "outputHeight" to 2)
            for ((column, original) in fields) {
                val aliased = original.toLong() + 4294967296L
                f.database.openHelper.writableDatabase.execSQL("UPDATE ExportPage SET $column=? WHERE snapshotId=? AND originalPageId=?", arrayOf<Any>(aliased, frozen.snapshotId.value, page.pageId.value))
                f.reopen()
                f.database.openHelper.readableDatabase.query("SELECT $column FROM ExportPage WHERE snapshotId=? AND originalPageId=?", arrayOf(frozen.snapshotId.value, page.pageId.value)).use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals(aliased, cursor.getLong(0)) }
                val before = state(f); assertFalse(f.database.exportSnapshots().exportPagesAreBounded(frozen.snapshotId.value))
                val error = try { loader(f).load(frozen.snapshotId); throw AssertionError("Raw INTEGER wrapped into an accepted value") } catch (failure: RepositoryFailure) { failure.error }
                assertEquals(OperationErrorCode.INTEGRITY_FAILED, error.code); assertEquals(before, state(f))
                f.database.openHelper.writableDatabase.execSQL("UPDATE ExportPage SET $column=? WHERE snapshotId=? AND originalPageId=?", arrayOf<Any>(original, frozen.snapshotId.value, page.pageId.value))
            }
            f.database.openHelper.writableDatabase.execSQL("UPDATE ExportPage SET recipeSchemaVersion=2 WHERE snapshotId=? AND originalPageId=?", arrayOf(frozen.snapshotId.value, page.pageId.value))
            f.reopen(); val before = state(f); assertTrue(f.database.exportSnapshots().exportPagesAreBounded(frozen.snapshotId.value))
            val error = try { loader(f).load(frozen.snapshotId); throw AssertionError("Future recipe schema accepted") } catch (failure: RepositoryFailure) { failure.error }
            assertEquals(OperationErrorCode.UNSUPPORTED_VERSION, error.code); f.reopen(); assertEquals(before, state(f)); f.assertForeignKeys()
        }
    }

    @Test fun rawBlobBoundsAreCheckedBeforeSettingsOrPageMaterialization() = runBlocking {
        RoomSourceIngestIntegrationTest.Fixture().use { f ->
            populate(f); val frozen = repository(f).freezeExport(f.document, revision(f), settings(f))
            val record = f.database.exportSnapshots().snapshot(frozen.snapshotId.value)!!
            f.database.openHelper.writableDatabase.execSQL("UPDATE ExportSnapshot SET settings=zeroblob(524289) WHERE snapshotId=?", arrayOf(frozen.snapshotId.value))
            f.reopen(); assertEquals(false, f.database.exportSnapshots().snapshotMetadataIsBounded(frozen.snapshotId.value)); val beforeHeader = state(f)
            val headerError = try { loader(f).load(frozen.snapshotId); throw AssertionError("Oversized settings loaded") } catch (failure: RepositoryFailure) { failure.error }
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, headerError.code); assertEquals(beforeHeader, state(f))
            f.database.openHelper.writableDatabase.execSQL("UPDATE ExportSnapshot SET settings=? WHERE snapshotId=?", arrayOf(record.settings, frozen.snapshotId.value))
            f.database.openHelper.writableDatabase.execSQL("UPDATE ExportPage SET providers=zeroblob(524289) WHERE snapshotId=?", arrayOf(frozen.snapshotId.value))
            f.reopen(); assertFalse(f.database.exportSnapshots().exportPagesAreBounded(frozen.snapshotId.value)); val beforePages = state(f)
            val pageError = try { loader(f).load(frozen.snapshotId); throw AssertionError("Oversized page BLOB loaded") } catch (failure: RepositoryFailure) { failure.error }
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, pageError.code); f.reopen(); assertEquals(beforePages, state(f)); f.assertForeignKeys()
        }
    }
}
