package app.pristinepage.data

import app.pristinepage.data.model.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DuplicateUndoTest {
    private fun metadataState(f: RepositoryFixture): List<Any> = with(f.metadata) {
        listOf(documents.toMap(), pages.toMap(), sources.toMap(), blobs.toMap(), recipes.toMap(), references.toMap(), undos.toMap(), masks.toMap(), associations.toMap(), maskReferences.toMap())
    }

    /** A second independent source chain makes an unavailable survivor observable: duplication's Undo covers the complete active set, not just the copied page. */
    private fun separateSecondSource(f: RepositoryFixture) {
        val page = f.pageIds[1].value; val sourceId = java.util.UUID.randomUUID().toString(); val blobId = java.util.UUID.randomUUID().toString()
        val original = f.metadata.sources.getValue(f.source.value); val blob = f.metadata.blobs.getValue(original.sourceBlobId)
        f.metadata.blobs[blobId] = blob.copy(sourceBlobId = blobId, relativePath = "sources/$blobId/original.bin", ingestTransactionId = java.util.UUID.randomUUID().toString())
        f.metadata.sources[sourceId] = original.copy(sourceId = sourceId, sourceBlobId = blobId, refCount = 2)
        f.metadata.pages[page] = f.metadata.pages.getValue(page).copy(sourceId = sourceId)
        f.metadata.recipes[page to 1L] = f.metadata.recipes.getValue(page to 1L).copy(sourceId = sourceId)
        for ((type, owner) in listOf("PAGE" to page, "RECIPE_HISTORY" to "recipe-v1/$page/1")) {
            val ref = f.metadata.references.remove(Triple(type, owner, f.source.value))!!
            f.metadata.references[Triple(type, owner, sourceId)] = ref.copy(sourceId = sourceId)
        }
        f.metadata.sources[f.source.value] = original.copy(refCount = 2)
    }

    private suspend fun duplicate(f: RepositoryFixture, page: PageId = f.pageIds[0], revision: Long = 1): DocumentMutationResult.Applied {
        val result = f.repository.duplicatePage(page, revision) as DocumentMutationResult.Applied
        assertNotNull("Duplication must provide a usable membership Undo token", result.undoId)
        return result
    }

    @Test fun duplicateUndoRestoresOrderAndRetainsTheNewPagesHistoryAndSourcePins() = runTest {
        val f = RepositoryFixture(); val result = duplicate(f); val copy = result.affectedPageIds.single()
        assertEquals(listOf(f.pageIds[0].value, copy.value, f.pageIds[1].value), f.metadata.activePages(f.document.value).map { it.pageId })
        assertEquals(7L, f.metadata.sources[f.source.value]!!.refCount)
        val undone = f.repository.undoDocumentEdit(checkNotNull(result.undoId), 2) as DocumentMutationResult.Applied
        assertEquals(3L, undone.documentRevision); assertEquals(f.pageIds.map { it.value }, f.metadata.activePages(f.document.value).map { it.pageId })
        assertEquals("REMOVED", f.metadata.pages[copy.value]!!.state); assertNull(f.metadata.pages[copy.value]!!.ordinal)
        assertNotNull(f.metadata.recipes[copy.value to 1L]); assertEquals(7L, f.metadata.sources[f.source.value]!!.refCount)
    }

    @Test fun undoingDuplicationDoesNotRevertLaterIndependentRecipeEdits() = runTest {
        val f = RepositoryFixture(); val result = duplicate(f); val copy = result.affectedPageIds.single()
        assertTrue(f.repository.saveRecipe(copy, 1, f.turned(3)) is SaveRecipeResult.Saved)
        assertTrue(f.repository.undoDocumentEdit(checkNotNull(result.undoId), 3) is DocumentMutationResult.Applied)
        assertEquals(3, f.current(copy).geometry.outputQuarterTurns); assertEquals(2L, f.metadata.pages[copy.value]!!.currentRecipeRevision)
        assertEquals(0, f.current(f.pageIds[0]).geometry.outputQuarterTurns); assertEquals(9L, f.metadata.sources[f.source.value]!!.refCount)
        assertEquals("REMOVED", f.metadata.pages[copy.value]!!.state)
    }

    @Test fun successiveDuplicateUndosIgnoreOtherRetainedTombstonesButRestoreExactActiveOrder() = runTest {
        val f = RepositoryFixture(); val first = duplicate(f); val copy = first.affectedPageIds.single()
        val second = duplicate(f, copy, 2)
        assertTrue(f.repository.undoDocumentEdit(checkNotNull(second.undoId), 3) is DocumentMutationResult.Applied)
        assertTrue(f.repository.undoDocumentEdit(checkNotNull(first.undoId), 4) is DocumentMutationResult.Applied)
        assertEquals(f.pageIds.map { it.value }, f.metadata.activePages(f.document.value).map { it.pageId })
        assertEquals(2, f.metadata.pages.values.count { it.state == "REMOVED" }); assertEquals(10L, f.metadata.sources[f.source.value]!!.refCount)
    }

    @Test fun staleRevisionOrPendingImportCannotUndoDuplication() = runTest {
        val f = RepositoryFixture(); val result = duplicate(f); val undo = checkNotNull(result.undoId)
        assertTrue(f.repository.renameDocument(f.document, 2, "Newer revision") is DocumentMutationResult.Applied)
        assertEquals(3L, (f.repository.undoDocumentEdit(undo, 2) as DocumentMutationResult.Conflict).actualRevision)
        f.metadata.pendingImports.add(f.document.value)
        assertEquals(OperationErrorCode.REVISION_CONFLICT, (f.repository.undoDocumentEdit(undo, 3) as DocumentMutationResult.Rejected).error.code)
        assertEquals(3, f.metadata.activePages(f.document.value).size); assertEquals("AVAILABLE", f.metadata.undos[undo.value]!!.state)
    }

    @Test fun duplicateUndoRetainsCopiedMaskOwnership() = runTest {
        val f = RepositoryFixture(); val mask = f.protectionMask(f.pageIds[0], 1); val result = duplicate(f); val copy = result.affectedPageIds.single()
        assertEquals(listOf(mask), f.metadata.recipeMasks(copy.value, 1).map { it.maskId })
        assertTrue(f.repository.undoDocumentEdit(checkNotNull(result.undoId), 2) is DocumentMutationResult.Applied)
        assertEquals(2L, f.metadata.masks[mask]!!.refCount); assertEquals(8L, f.metadata.sources[f.source.value]!!.refCount)
        assertEquals(listOf(mask), f.metadata.recipeMasks(copy.value, 1).map { it.maskId })
    }

    @Test fun inconsistentDuplicateHistoryCannotHideAPreexistingPage() = runTest {
        val f = RepositoryFixture(); val result = duplicate(f); val undo = checkNotNull(result.undoId); val entry = f.metadata.undos[undo.value]!!
        f.metadata.undos[undo.value] = entry.copy(afterState = entry.beforeState.copyOf())
        val pages = f.metadata.pages.toMap(); val refs = f.metadata.references.toMap()
        val rejected = f.repository.undoDocumentEdit(undo, 2) as DocumentMutationResult.Rejected
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, rejected.error.code); assertEquals(pages, f.metadata.pages); assertEquals(refs, f.metadata.references)
        assertEquals("AVAILABLE", f.metadata.undos[undo.value]!!.state)
    }

    @Test fun canonicalNonActiveBeforeStateIsAnIntegrityErrorNotAStorageRetry() = runTest {
        val f = RepositoryFixture(); val result = duplicate(f); val undo = checkNotNull(result.undoId); val entry = f.metadata.undos[undo.value]!!
        val state = Json.parseToJsonElement(entry.beforeState.toString(Charsets.UTF_8)).jsonObject
        val pages = state.getValue("pages").jsonArray.map { element ->
            val row = element.jsonObject
            if (row.getValue("pageId").jsonPrimitive.content == f.pageIds[0].value) JsonObject(row + mapOf("state" to JsonPrimitive("REMOVED"), "ordinal" to JsonNull, "removedAt" to JsonPrimitive(1000L)))
            else JsonObject(row + ("ordinal" to JsonPrimitive(0)))
        }
        // This individual snapshot is canonical with one contiguous active page, but it cannot be a duplication's all-active before-state.
        f.metadata.undos[undo.value] = entry.copy(beforeState = JsonObject(state + ("pages" to JsonArray(pages))).toString().toByteArray(Charsets.UTF_8))
        val before = f.metadata.pages.toMap(); val refs = f.metadata.references.toMap()
        val rejected = f.repository.undoDocumentEdit(undo, 2) as DocumentMutationResult.Rejected
        assertEquals("Malformed duplicate history was mislabeled as retryable storage", OperationErrorCode.INTEGRITY_FAILED, rejected.error.code)
        assertFalse(rejected.error.retryable); assertEquals(before, f.metadata.pages); assertEquals(refs, f.metadata.references)
    }

    @Test fun duplicateRejectsUnavailableBackingBlobBeforePublishingCopyOrUndo() = runTest {
        for (state in listOf("QUARANTINED", "DELETING")) for (duringAdmission in listOf(false, true)) {
            lateinit var f: RepositoryFixture
            var before: List<Any>? = null
            f = RepositoryFixture(admission = { _, _ ->
                val blobId = f.metadata.sources.getValue(f.source.value).sourceBlobId
                if (duringAdmission) f.metadata.blobs[blobId] = f.metadata.blobs.getValue(blobId).copy(state = state)
                before = metadataState(f); null
            })
            val blobId = f.metadata.sources.getValue(f.source.value).sourceBlobId
            if (!duringAdmission) f.metadata.blobs[blobId] = f.metadata.blobs.getValue(blobId).copy(state = state)
            val rejected = f.repository.duplicatePage(f.pageIds[0], 1) as? DocumentMutationResult.Rejected
                ?: throw AssertionError("Duplication committed with an unavailable backing blob")
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, rejected.error.code); assertFalse(rejected.error.retryable)
            assertEquals(checkNotNull(before), metadataState(f)); assertEquals("READY", f.metadata.sources.getValue(f.source.value).state)
        }
    }

    @Test fun duplicateRejectsUnavailableSurvivorChainRequiredByItsMembershipUndo() = runTest {
        for (unavailable in listOf("SOURCE", "BLOB")) {
            val f = RepositoryFixture(); separateSecondSource(f)
            val survivorSource = f.metadata.sources.getValue(f.metadata.pages.getValue(f.pageIds[1].value).sourceId)
            if (unavailable == "SOURCE") f.metadata.sources[survivorSource.sourceId] = survivorSource.copy(state = "QUARANTINED")
            else f.metadata.blobs[survivorSource.sourceBlobId] = f.metadata.blobs.getValue(survivorSource.sourceBlobId).copy(state = "QUARANTINED")
            val before = metadataState(f)
            val rejected = f.repository.duplicatePage(f.pageIds[0], 1) as? DocumentMutationResult.Rejected
                ?: throw AssertionError("Duplication committed with an unavailable survivor backing blob")
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, rejected.error.code); assertFalse(rejected.error.retryable)
            assertEquals(before, metadataState(f))
        }
    }

    @Test fun duplicateRejectsInconsistentActiveMembershipBeforeRecordingUnusableHistory() = runTest {
        for (index in 0..1) {
            val f = RepositoryFixture(); val page = f.pageIds[index].value
            f.metadata.pages[page] = f.metadata.pages.getValue(page).copy(removedAt = 1000L)
            val before = metadataState(f)
            val rejected = f.repository.duplicatePage(f.pageIds[0], 1) as? DocumentMutationResult.Rejected
                ?: throw AssertionError("Duplication committed invalid ACTIVE membership into its Undo history")
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, rejected.error.code); assertFalse(rejected.error.retryable)
            assertEquals(before, metadataState(f))
        }
    }
}
