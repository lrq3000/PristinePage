package app.pristinepage.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.data.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Current repository commands on actual Room/SQLite. Reuse retained synthetic metadata fixtures; reopen is not process death and no source/fixture database is deleted. */
@RunWith(AndroidJUnit4::class)
class RoomDocumentCommandsIntegrationTest {
    @Test fun orderRemovalAndRecipeUndosSurviveReopenWithoutChangingSourceIdentity() = runBlocking {
        RoomRepositoryIntegrationTest.Fixture().use { f ->
            val source = f.database.catalog().source(f.source)!!
            val original = f.database.catalog().recipe(f.pages[0].value, 1)!!.canonicalRecipe.copyOf()
            val reorder = f.repository.reorderPages(f.document, 1, f.pages.reversed()) as DocumentMutationResult.Applied
            val rotation = f.repository.rotatePage(f.pages[0], 1, 1) as DocumentMutationResult.Applied
            assertTrue(f.repository.renameDocument(f.document, 3, " Renamed / synthetic ") is DocumentMutationResult.Applied)
            val removal = f.repository.removePage(f.pages[1], 4) as DocumentMutationResult.Applied
            f.reopen()
            assertEquals("REMOVED", f.database.catalog().page(f.pages[1].value)!!.state)
            assertEquals(listOf(f.pages[0].value), f.database.catalog().activePages(f.document.value).map { it.pageId })
            assertTrue(f.repository.undoDocumentEdit(removal.undoId!!, 5) is DocumentMutationResult.Applied)
            assertTrue(f.repository.undoDocumentEdit(reorder.undoId!!, 6) is DocumentMutationResult.Applied)
            assertEquals(1, RecipeCodec.decode(f.database.catalog().recipe(f.pages[0].value, 2)!!.canonicalRecipe).geometry.outputQuarterTurns)
            assertTrue(f.repository.undoDocumentEdit(rotation.undoId!!, 7) is DocumentMutationResult.Applied)
            f.reopen()
            val q = f.database.catalog()
            assertEquals(f.pages.map { it.value }, q.activePages(f.document.value).map { it.pageId }); assertEquals(listOf(0, 1), q.activePages(f.document.value).map { it.ordinal })
            assertEquals("Renamed / synthetic", q.document(f.document.value)!!.title); assertEquals(8L, q.document(f.document.value)!!.documentRevision)
            assertEquals(3L, q.page(f.pages[0].value)!!.currentRecipeRevision)
            assertEquals(0, RecipeCodec.decode(q.recipe(f.pages[0].value, 3)!!.canonicalRecipe).geometry.outputQuarterTurns)
            assertArrayEquals(original, q.recipe(f.pages[0].value, 1)!!.canonicalRecipe)
            assertEquals(9L, q.source(f.source)!!.refCount); assertEquals(9L, f.count("SourceReference")); assertEquals(source.copy(refCount = 9L), q.source(f.source))
            f.assertForeignKeys()
        }
    }

    @Test fun sqliteOrdinalFailureRollsBackBothReorderAndRemoval() = runBlocking {
        for (remove in listOf(false, true)) RoomRepositoryIntegrationTest.Fixture().use { f ->
            // This UUID belongs only to the generated synthetic database. Force the second temporary ordinal UPDATE to abort after the first one executed.
            f.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_membership BEFORE UPDATE OF ordinal ON Page WHEN NEW.pageId='${f.pages[1].value}' AND NEW.ordinal>=100 BEGIN SELECT RAISE(ABORT,'synthetic membership fault'); END")
            val result = if (remove) f.repository.removePage(f.pages[0], 1) else f.repository.reorderPages(f.document, 1, f.pages.reversed())
            assertEquals(OperationErrorCode.STORAGE_FAILED, (result as DocumentMutationResult.Rejected).error.code)
            f.reopen()
            val q = f.database.catalog()
            assertEquals(1L, q.document(f.document.value)!!.documentRevision); assertEquals(f.pages.map { it.value }, q.activePages(f.document.value).map { it.pageId })
            assertEquals(listOf(0, 1), q.activePages(f.document.value).map { it.ordinal }); assertEquals(0L, f.count("UndoEntry"))
            assertEquals(4L, f.count("SourceReference")); assertEquals(4L, q.source(f.source)!!.refCount); f.assertForeignKeys()
        }
    }

    @Test fun membershipUndoKeepsCurrentOrderWhenExpectedDocumentRevisionIsStale() = runBlocking {
        RoomRepositoryIntegrationTest.Fixture().use { f ->
            val removed = f.repository.removePage(f.pages[0], 1) as DocumentMutationResult.Applied
            val undoId = checkNotNull(removed.undoId)
            assertTrue(f.repository.renameDocument(f.document, 2, "Newer revision") is DocumentMutationResult.Applied)
            val conflict = f.repository.undoDocumentEdit(undoId, 2) as DocumentMutationResult.Conflict
            assertEquals(3L, conflict.actualRevision)
            f.reopen()
            assertEquals("REMOVED", f.database.catalog().page(f.pages[0].value)!!.state)
            assertEquals("AVAILABLE", f.database.catalog().undo(undoId.value)!!.state)
            assertTrue(f.repository.undoDocumentEdit(undoId, 3) is DocumentMutationResult.Applied)
            assertEquals(listOf(0, 1), f.database.catalog().activePages(f.document.value).map { it.ordinal }); f.assertForeignKeys()
        }
    }

    @Test fun quarantinedSourceCannotBeReactivatedByRemovalUndoAfterReopen() = runBlocking {
        RoomRepositoryIntegrationTest.Fixture().use { f ->
            val removed = f.repository.removePage(f.pages[0], 1) as DocumentMutationResult.Applied
            val undoId = checkNotNull(removed.undoId)
            f.database.openHelper.writableDatabase.execSQL("UPDATE Source SET state='QUARANTINED' WHERE sourceId=?", arrayOf<Any>(f.source))
            f.reopen()
            val result = f.repository.undoDocumentEdit(undoId, 2) as DocumentMutationResult.Rejected
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, result.error.code)
            assertEquals(2L, f.database.catalog().document(f.document.value)!!.documentRevision)
            assertEquals("REMOVED", f.database.catalog().page(f.pages[0].value)!!.state)
            assertEquals("AVAILABLE", f.database.catalog().undo(undoId.value)!!.state)
            assertEquals(5L, f.count("SourceReference")); assertEquals(5L, f.database.catalog().source(f.source)!!.refCount)
            f.assertForeignKeys()
        }
    }

    @Test fun canonicalRemovalHistoryCannotReorderUntouchedSurvivorsAfterReopen() = runBlocking {
        RoomRepositoryIntegrationTest.Fixture(3).use { f ->
            val removed = f.repository.removePage(f.pages[0], 1) as DocumentMutationResult.Applied
            val undoId = checkNotNull(removed.undoId); val entry = f.database.catalog().undo(undoId.value)!!
            val state = Json.parseToJsonElement(entry.beforeState.toString(Charsets.UTF_8)).jsonObject
            val pages = state.getValue("pages").jsonArray.map { element ->
                val row = element.jsonObject
                val ordinal = when (row.getValue("pageId").jsonPrimitive.content) { f.pages[1].value -> 2; f.pages[2].value -> 1; else -> 0 }
                JsonObject(row + ("ordinal" to JsonPrimitive(ordinal)))
            }
            val corrupt = JsonObject(state + ("pages" to JsonArray(pages))).toString().toByteArray(Charsets.UTF_8)
            f.database.runInTransaction { assertEquals(1, f.database.catalog().updateUndo(entry.copy(beforeState = corrupt))) }
            f.reopen()
            val result = f.repository.undoDocumentEdit(undoId, 2) as DocumentMutationResult.Rejected
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, result.error.code)
            assertEquals(f.pages.drop(1).map { it.value }, f.database.catalog().activePages(f.document.value).map { it.pageId })
            assertEquals(2L, f.database.catalog().document(f.document.value)!!.documentRevision)
            assertEquals("AVAILABLE", f.database.catalog().undo(undoId.value)!!.state); assertEquals(7L, f.count("SourceReference")); f.assertForeignKeys()
        }
    }

    @Test fun unavailableBackingBlobCannotBeReactivatedWhenSourceRemainsReady() = runBlocking {
        for (state in listOf("QUARANTINED", "DELETING")) RoomRepositoryIntegrationTest.Fixture().use { f ->
            val removed = f.repository.removePage(f.pages[0], 1) as DocumentMutationResult.Applied
            val undoId = checkNotNull(removed.undoId); val source = f.database.catalog().source(f.source)!!
            f.database.openHelper.writableDatabase.execSQL("UPDATE SourceBlob SET state=? WHERE sourceBlobId=?", arrayOf<Any>(state, source.sourceBlobId))
            f.reopen()
            assertEquals("READY", f.database.catalog().source(f.source)!!.state)
            val result = f.repository.undoDocumentEdit(undoId, 2) as DocumentMutationResult.Rejected
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, result.error.code)
            assertEquals("REMOVED", f.database.catalog().page(f.pages[0].value)!!.state)
            assertEquals(2L, f.database.catalog().document(f.document.value)!!.documentRevision)
            assertEquals("AVAILABLE", f.database.catalog().undo(undoId.value)!!.state); assertEquals(5L, f.count("SourceReference")); f.assertForeignKeys()
        }
    }

    @Test fun lateDocumentWriteFailureRollsBackOrderRemovalAndAlreadyInsertedUndoPins() = runBlocking {
        for (remove in listOf(false, true)) RoomRepositoryIntegrationTest.Fixture().use { f ->
            // The document update follows page writes, Undo insertion and source pin creation in the real repository transaction. A trigger abort there must roll back all earlier participants.
            f.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_membership_commit BEFORE UPDATE OF documentRevision ON Document WHEN NEW.documentId='${f.document.value}' AND NEW.documentRevision=2 BEGIN SELECT RAISE(ABORT,'synthetic late membership fault'); END")
            val result = if (remove) f.repository.removePage(f.pages[0], 1) else f.repository.reorderPages(f.document, 1, f.pages.reversed())
            assertEquals(OperationErrorCode.STORAGE_FAILED, (result as DocumentMutationResult.Rejected).error.code)
            f.reopen()
            val q = f.database.catalog()
            assertEquals(1L, q.document(f.document.value)!!.documentRevision); assertEquals(f.pages.map { it.value }, q.activePages(f.document.value).map { it.pageId })
            assertEquals(listOf(0, 1), q.activePages(f.document.value).map { it.ordinal }); assertEquals(0L, f.count("UndoEntry"))
            assertEquals(4L, f.count("SourceReference")); assertEquals(4L, q.source(f.source)!!.refCount); f.assertForeignKeys()
        }
    }
}
