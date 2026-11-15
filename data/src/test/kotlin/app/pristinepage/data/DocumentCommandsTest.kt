package app.pristinepage.data

import app.pristinepage.data.model.*
import app.pristinepage.data.repository.DocumentRepository
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import org.junit.Assert.*
import org.junit.Test

/** Real repository commands/codec/rows. Reflection lets the absent routes fail as assertions before the interface grows; only the Android transaction engine is substituted by the existing fixture. */
class DocumentCommandsTest {
    private suspend fun command(repository: DocumentRepository, name: String, vararg args: Any): DocumentMutationResult = suspendCoroutineUninterceptedOrReturn { continuation ->
        val method = repository.javaClass.methods.singleOrNull { it.name.substringBefore('-') == name && it.parameterCount == args.size + 1 }
            ?: throw AssertionError("Required document command is absent: $name")
        try { method.invoke(repository, *args, continuation) }
        catch (error: InvocationTargetException) { throw error.targetException }
    }
    private suspend fun rename(f: RepositoryFixture, title: String, revision: Long = 1) = command(f.repository, "renameDocument", f.document.value, revision, title)
    private suspend fun reorder(f: RepositoryFixture, order: List<PageId>, revision: Long = 1) = command(f.repository, "reorderPages", f.document.value, revision, order)
    private suspend fun remove(f: RepositoryFixture, page: PageId, revision: Long = 1) = command(f.repository, "removePage", page.value, revision)
    private suspend fun rotate(f: RepositoryFixture, page: PageId, turns: Int, revision: Long = 1) = command(f.repository, "rotatePage", page.value, revision, turns)

    @Test fun renameIsRevisionFencedAndNeverChangesIdsOrSourceMetadata() = runTest {
        val f = RepositoryFixture(); val sources = f.metadata.sources.toMap(); val pages = f.metadata.pages.toMap()
        val result = rename(f, "  New / private title  ") as DocumentMutationResult.Applied
        assertEquals(2L, result.documentRevision); assertTrue(result.affectedPageIds.isEmpty())
        assertEquals("New / private title", f.metadata.documents[f.document.value]!!.title)
        assertEquals(2L, (rename(f, "Stale", 1) as DocumentMutationResult.Conflict).actualRevision)
        assertEquals(sources, f.metadata.sources); assertEquals(pages, f.metadata.pages)
    }

    @Test fun invalidAndUnchangedRenameCannotCreateHistory() = runTest {
        val f = RepositoryFixture()
        for (title in listOf(" ", "x".repeat(161), "\uD800", "Synthetic")) assertTrue(rename(f, title) is DocumentMutationResult.Rejected)
        assertEquals(1L, f.metadata.documents[f.document.value]!!.documentRevision); assertTrue(f.metadata.undos.isEmpty())
    }

    @Test fun signedRotationAccumulatesIndependentTurnsAndUndoCreatesNewRevision() = runTest {
        val f = RepositoryFixture(); val page = f.pageIds[0]; val source = f.metadata.sources[f.source.value]!!
        val mask = f.protectionMask(page, 1)
        val first = rotate(f, page, -1) as DocumentMutationResult.Applied
        assertEquals(3, f.current(page).geometry.outputQuarterTurns); assertEquals(2L, f.metadata.pages[page.value]!!.currentRecipeRevision)
        assertNotNull(first.undoId); assertEquals(listOf(page), first.affectedPageIds)
        val second = rotate(f, page, Int.MAX_VALUE, 2) as DocumentMutationResult.Applied
        assertEquals(2, f.current(page).geometry.outputQuarterTurns)
        assertTrue(f.repository.undoDocumentEdit(second.undoId!!, 3) is DocumentMutationResult.Applied)
        assertEquals(3, f.current(page).geometry.outputQuarterTurns); assertEquals(4L, f.metadata.pages[page.value]!!.currentRecipeRevision)
        assertEquals(source.copy(refCount = f.metadata.countSourceReferences(f.source.value)), f.metadata.sources[f.source.value])
        assertEquals(listOf(mask), f.metadata.recipeMasks(page.value, 4).map { it.maskId }); assertEquals(0, f.current(f.pageIds[1]).geometry.outputQuarterTurns)
    }

    @Test fun staleOrWholeTurnRotationDoesNotAppendAnotherRecipe() = runTest {
        val f = RepositoryFixture(); val page = f.pageIds[0]
        assertTrue(rotate(f, page, 4) is DocumentMutationResult.Rejected)
        assertTrue(rotate(f, page, 1) is DocumentMutationResult.Applied)
        assertEquals(2L, (rotate(f, page, 1, 1) as DocumentMutationResult.Conflict).actualRevision)
        assertEquals(3, f.metadata.recipes.size); assertEquals(1, f.current(page).geometry.outputQuarterTurns)
    }

    @Test fun reorderThenUndoRestoresOrderWithoutRevertingLaterRecipeEdit() = runTest {
        val f = RepositoryFixture(3); val order = listOf(f.pageIds[2], f.pageIds[0], f.pageIds[1])
        val result = reorder(f, order) as DocumentMutationResult.Applied
        assertEquals(order.map { it.value }, f.metadata.activePages(f.document.value).map { it.pageId })
        assertNotNull(result.undoId)
        assertTrue(f.repository.saveRecipe(f.pageIds[0], 1, f.turned(2)) is SaveRecipeResult.Saved)
        val undone = f.repository.undoDocumentEdit(result.undoId!!, 3) as DocumentMutationResult.Applied
        assertEquals(4L, undone.documentRevision); assertEquals(f.pageIds.map { it.value }, f.metadata.activePages(f.document.value).map { it.pageId })
        assertEquals(2, f.current(f.pageIds[0]).geometry.outputQuarterTurns); assertEquals(4, f.metadata.recipes.size)
        assertTrue(f.repository.undoDocumentEdit(result.undoId!!, 4) is DocumentMutationResult.Rejected)
    }

    @Test fun reorderRejectsMissingDuplicateForeignRemovedAndUnchangedSets() = runTest {
        val f = RepositoryFixture(3); val original = f.metadata.pages.toMap()
        for (order in listOf(f.pageIds.dropLast(1), listOf(f.pageIds[0], f.pageIds[0], f.pageIds[2]), listOf(f.pageIds[0], f.pageIds[1], PageId(java.util.UUID.randomUUID().toString())), f.pageIds)) assertTrue(reorder(f, order) is DocumentMutationResult.Rejected)
        assertEquals(original, f.metadata.pages); assertEquals(1L, f.metadata.documents[f.document.value]!!.documentRevision)
    }

    @Test fun removeRetainsTombstoneHistoryAndSourcePinsThenUndoRestoresPosition() = runTest {
        val f = RepositoryFixture(3); val removed = f.pageIds[1]; val refs = f.metadata.references.toMap(); val recipes = f.metadata.recipes.toMap()
        val result = remove(f, removed) as DocumentMutationResult.Applied
        val row = f.metadata.pages[removed.value]!!
        assertEquals("REMOVED", row.state); assertNull(row.ordinal); assertNotNull(row.removedAt)
        assertEquals(listOf(f.pageIds[0].value, f.pageIds[2].value), f.metadata.activePages(f.document.value).map { it.pageId })
        assertEquals(listOf(0, 1), f.metadata.activePages(f.document.value).map { it.ordinal })
        assertTrue(f.metadata.references.entries.containsAll(refs.entries)); assertEquals(recipes, f.metadata.recipes)
        assertEquals(7L, f.metadata.sources[f.source.value]!!.refCount)
        val undone = f.repository.undoDocumentEdit(result.undoId!!, 2) as DocumentMutationResult.Applied
        assertEquals(3L, undone.documentRevision); assertEquals(f.pageIds.map { it.value }, f.metadata.activePages(f.document.value).map { it.pageId })
        assertEquals("ACTIVE", f.metadata.pages[removed.value]!!.state); assertNull(f.metadata.pages[removed.value]!!.removedAt)
        assertEquals(recipes, f.metadata.recipes); assertEquals(7L, f.metadata.sources[f.source.value]!!.refCount)
    }

    @Test fun lastPageRemovalCanUndoAnEmptyDocumentWithoutDroppingOriginalOwnership() = runTest {
        val f = RepositoryFixture(1); val result = remove(f, f.pageIds.single()) as DocumentMutationResult.Applied
        assertTrue(f.metadata.activePages(f.document.value).isEmpty()); assertEquals(3L, f.metadata.sources[f.source.value]!!.refCount)
        assertTrue(f.repository.undoDocumentEdit(result.undoId!!, 2) is DocumentMutationResult.Applied)
        assertEquals(listOf(0), f.metadata.activePages(f.document.value).map { it.ordinal })
    }

    @Test fun unresolvedImportBlocksMembershipEditsAndUndoButNotRecipeOnlyRotationOrRename() = runTest {
        val f = RepositoryFixture(); val result = reorder(f, f.pageIds.reversed()) as DocumentMutationResult.Applied
        f.metadata.pendingImports.add(f.document.value)
        for (blocked in listOf(reorder(f, f.pageIds, 2), remove(f, f.pageIds[0], 2), f.repository.undoDocumentEdit(result.undoId!!, 2))) {
            assertEquals(OperationErrorCode.REVISION_CONFLICT, (blocked as DocumentMutationResult.Rejected).error.code)
        }
        assertTrue(rename(f, "Allowed", 2) is DocumentMutationResult.Applied); assertTrue(rotate(f, f.pageIds[0], 1) is DocumentMutationResult.Applied)
    }

    @Test fun staleMembershipCommandAndUndoCannotOverwriteDifferentOrder() = runTest {
        val f = RepositoryFixture(3); val first = reorder(f, f.pageIds.reversed()) as DocumentMutationResult.Applied
        assertEquals(2L, (remove(f, f.pageIds[0], 1) as DocumentMutationResult.Conflict).actualRevision)
        val secondOrder = listOf(f.pageIds[1], f.pageIds[0], f.pageIds[2]); assertTrue(reorder(f, secondOrder, 2) is DocumentMutationResult.Applied)
        assertTrue(f.repository.undoDocumentEdit(first.undoId!!, 3) is DocumentMutationResult.Conflict)
        assertEquals(secondOrder.map { it.value }, f.metadata.activePages(f.document.value).map { it.pageId })
    }

    @Test fun ordinalWriteFailureRollsBackEveryPageUndoPinAndDocumentRevision() = runTest {
        val f = RepositoryFixture(3); val pages = f.metadata.pages.toMap(); val refs = f.metadata.references.toMap()
        f.metadata.failPageUpdateAt = 2
        val result = reorder(f, f.pageIds.reversed()) as DocumentMutationResult.Rejected
        assertEquals(OperationErrorCode.STORAGE_FAILED, result.error.code); assertEquals(pages, f.metadata.pages); assertEquals(refs, f.metadata.references)
        assertTrue(f.metadata.undos.isEmpty()); assertEquals(1L, f.metadata.documents[f.document.value]!!.documentRevision)
    }

    @Test fun membershipUndoRejectsExpiredOrMalformedRecordWithoutMutation() = runTest {
        val f = RepositoryFixture(); val result = remove(f, f.pageIds[0]) as DocumentMutationResult.Applied
        val undo = f.metadata.undos[result.undoId!!.value]!!; val pages = f.metadata.pages.toMap()
        f.metadata.undos[undo.undoId] = undo.copy(beforeState = "{}".toByteArray())
        assertTrue(f.repository.undoDocumentEdit(result.undoId!!, 2) is DocumentMutationResult.Rejected); assertEquals(pages, f.metadata.pages)
        f.metadata.undos[undo.undoId] = undo.copy(expiresAt = 1000L)
        assertTrue(f.repository.undoDocumentEdit(result.undoId!!, 2) is DocumentMutationResult.Rejected); assertEquals(pages, f.metadata.pages)
    }

    @Test fun removalUndoCannotReactivateADeletingOrQuarantinedSource() = runTest {
        for (state in listOf("DELETING", "QUARANTINED")) {
            val f = RepositoryFixture(); val result = remove(f, f.pageIds[0]) as DocumentMutationResult.Applied
            f.metadata.sources[f.source.value] = f.metadata.sources[f.source.value]!!.copy(state = state)
            val before = f.metadata.pages.toMap()
            val undo = f.repository.undoDocumentEdit(result.undoId!!, 2) as? DocumentMutationResult.Rejected
                ?: throw AssertionError("Membership Undo reactivated a page whose source is not READY")
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, undo.error.code); assertEquals(before, f.metadata.pages)
            assertEquals(2L, f.metadata.documents[f.document.value]!!.documentRevision); assertEquals("AVAILABLE", f.metadata.undos[result.undoId!!.value]!!.state)
        }
    }

    @Test fun membershipEditsPreserveUnsupportedRecipeBytesWithoutExecutingThem() = runTest {
        val f = RepositoryFixture(); val page = f.pageIds[0]; val future = byteArrayOf(0xFF.toByte(), 1, 2)
        f.metadata.recipes[page.value to 1L] = f.metadata.recipes[page.value to 1L]!!.copy(recipeSchemaVersion = 9, canonicalRecipe = future)
        val result = remove(f, page) as DocumentMutationResult.Applied
        assertTrue(f.repository.undoDocumentEdit(result.undoId!!, 2) is DocumentMutationResult.Applied)
        assertArrayEquals(future, f.metadata.recipes[page.value to 1L]!!.canonicalRecipe); assertEquals(1L, f.metadata.pages[page.value]!!.currentRecipeRevision)
        assertEquals(OperationErrorCode.UNSUPPORTED_VERSION, (rotate(f, page, 1) as DocumentMutationResult.Rejected).error.code)
    }

    @Test fun revisionLimitCannotLeaveTemporaryOrdinalsOrATombstone() = runTest {
        val f = RepositoryFixture(); val pages = f.metadata.pages.toMap()
        f.metadata.documents[f.document.value] = f.metadata.documents[f.document.value]!!.copy(documentRevision = Long.MAX_VALUE)
        assertEquals(OperationErrorCode.RESOURCE_LIMIT, (remove(f, f.pageIds[0], Long.MAX_VALUE) as DocumentMutationResult.Rejected).error.code)
        assertEquals(OperationErrorCode.RESOURCE_LIMIT, (reorder(f, f.pageIds.reversed(), Long.MAX_VALUE) as DocumentMutationResult.Rejected).error.code)
        assertEquals(pages, f.metadata.pages); assertTrue(f.metadata.undos.isEmpty())
    }

    @Test fun canonicalButInconsistentRemovalHistoryCannotReorderSurvivorsOnUndo() = runTest {
        val f = RepositoryFixture(3); val result = remove(f, f.pageIds[0]) as DocumentMutationResult.Applied
        val entry = f.metadata.undos[result.undoId!!.value]!!
        val state = Json.parseToJsonElement(entry.beforeState.toString(Charsets.UTF_8)).jsonObject
        val pages = state.getValue("pages").jsonArray.map { element ->
            val row = element.jsonObject
            val ordinal = when (row.getValue("pageId").jsonPrimitive.content) { f.pageIds[1].value -> 2; f.pageIds[2].value -> 1; else -> 0 }
            JsonObject(row + ("ordinal" to JsonPrimitive(ordinal)))
        }
        // Field order, UUIDs, version and contiguous ordinals remain canonical; only the implied removal transition is inconsistent with the unchanged after-state.
        f.metadata.undos[entry.undoId] = entry.copy(beforeState = JsonObject(state + ("pages" to JsonArray(pages))).toString().toByteArray(Charsets.UTF_8))
        val beforePages = f.metadata.pages.toMap(); val refs = f.metadata.references.toMap()
        val undo = f.repository.undoDocumentEdit(result.undoId!!, 2) as? DocumentMutationResult.Rejected
            ?: throw AssertionError("Inconsistent canonical removal history reordered untouched survivors")
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, undo.error.code); assertEquals(beforePages, f.metadata.pages); assertEquals(refs, f.metadata.references)
        assertEquals(2L, f.metadata.documents[f.document.value]!!.documentRevision); assertEquals("AVAILABLE", f.metadata.undos[entry.undoId]!!.state)
    }

    @Test fun removalUndoCannotReactivateAnUnavailableBackingBlobEvenWhenSourceIsReady() = runTest {
        for (state in listOf("QUARANTINED", "DELETING")) {
            val f = RepositoryFixture(); val result = remove(f, f.pageIds[0]) as DocumentMutationResult.Applied
            val source = f.metadata.sources[f.source.value]!!; f.metadata.blobs[source.sourceBlobId] = f.metadata.blobs[source.sourceBlobId]!!.copy(state = state)
            val before = f.metadata.pages.toMap(); val refs = f.metadata.references.toMap()
            val undo = f.repository.undoDocumentEdit(result.undoId!!, 2) as? DocumentMutationResult.Rejected
                ?: throw AssertionError("Membership Undo reactivated a page whose backing blob is not READY")
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, undo.error.code); assertEquals(before, f.metadata.pages); assertEquals(refs, f.metadata.references)
            assertEquals(2L, f.metadata.documents[f.document.value]!!.documentRevision); assertEquals("AVAILABLE", f.metadata.undos[result.undoId!!.value]!!.state)
        }
    }

    @Test fun cancellationDuringRotationAdmissionCannotCommitAnUnreportedEdit() = runTest {
        val job = Job(); val f = RepositoryFixture(admission = { _, _ -> job.cancel(); null })
        var canceled = false
        try { withContext(job) { rotate(f, f.pageIds[0], 1) } } catch (_: CancellationException) { canceled = true }
        assertTrue(canceled)
        assertEquals("Canceled admission committed a new rotation revision", 1L, f.metadata.pages[f.pageIds[0].value]!!.currentRecipeRevision)
        assertEquals(1L, f.metadata.documents[f.document.value]!!.documentRevision); assertTrue(f.metadata.undos.isEmpty()); assertEquals(4, f.metadata.references.size)
    }
}
