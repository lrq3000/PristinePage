package app.pristinepage.data

import app.pristinepage.data.db.DocumentRow
import app.pristinepage.data.db.MaskRow
import app.pristinepage.data.db.RecipeMaskRow
import app.pristinepage.data.db.SourceReferenceRow
import app.pristinepage.data.model.*
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BatchRecipeTest {
    @Test fun batchSavePreservesSourceMaskAssociationsAndPinsUndoOwnership() = runTest {
        val f = RepositoryFixture(); val page = f.pageIds[0]; val maskId = UUID.randomUUID().toString()
        f.metadata.masks[maskId] = MaskRow(maskId, f.source.value, "PROTECTION", "b".repeat(64), 3, 2, "masks/$maskId/mask.bin", 1, 1, "READY")
        f.metadata.associations[Triple(page.value, 1L, maskId)] = RecipeMaskRow(page.value, 1, maskId, "PROTECTION")
        f.metadata.references[Triple("MASK", maskId, f.source.value)] = SourceReferenceRow("MASK", maskId, f.source.value, 0)
        f.metadata.sources[f.source.value] = f.metadata.sources[f.source.value]!!.copy(refCount = 5)
        val saved = f.repository.applyBatchRecipes(f.document, 1, f.pageIds.map { f.change(it, 1) }) as DocumentMutationResult.Applied
        assertEquals(listOf(maskId), f.metadata.recipeMasks(page.value, 2).map { it.maskId })
        assertEquals("PROTECTION", f.metadata.recipeMasks(page.value, 2).single().role)
        assertTrue(f.metadata.maskReferences.values.any { it.ownerType == "UNDO" && it.ownerId == saved.undoId!!.value && it.maskId == maskId })
        assertEquals(3L, f.metadata.masks[maskId]!!.refCount)
        assertEquals(f.source.value, f.metadata.masks[maskId]!!.sourceId)
        assertEquals(3, f.metadata.masks[maskId]!!.width)
        assertEquals(f.metadata.countSourceReferences(f.source.value), f.metadata.sources[f.source.value]!!.refCount)
    }

    @Test fun batchSaveIsAtomic() = runTest {
        val f = RepositoryFixture(); f.metadata.failRecipeInsertAt = 2
        val result = f.repository.applyBatchRecipes(f.document, 1, f.pageIds.mapIndexed { i, p -> f.change(p, i + 1) })
        assertTrue(result is DocumentMutationResult.Rejected)
        assertEquals(1L, f.metadata.documents[f.document.value]!!.documentRevision)
        assertEquals(listOf(1L, 1L), f.pageIds.map { f.metadata.pages[it.value]!!.currentRecipeRevision })
        assertEquals(2, f.metadata.recipes.size); assertEquals(0, f.metadata.undos.size)
        assertEquals(4L, f.metadata.sources[f.source.value]!!.refCount)
    }

    @Test fun batchRevisionConflictCommitsNothing() = runTest {
        val f = RepositoryFixture()
        val conflict = f.repository.applyBatchRecipes(f.document, 1, listOf(f.change(f.pageIds[0], 1), f.change(f.pageIds[1], 2, 9))) as DocumentMutationResult.Conflict
        assertEquals(1L, conflict.actualRevision)
        assertEquals(listOf(0, 0), f.pageIds.map { f.current(it).geometry.outputQuarterTurns })
        assertEquals(2, f.metadata.recipes.size); assertTrue(f.metadata.undos.isEmpty())
    }

    @Test fun batchRejectsDuplicateForeignOrRemovedPages() = runTest {
        for (kind in listOf("duplicate", "foreign", "removed")) {
            val f = RepositoryFixture(); val first = f.change(f.pageIds[0], 1)
            val invalid = when (kind) {
                "duplicate" -> first
                "foreign" -> {
                    val other = UUID.randomUUID().toString()
                    f.metadata.documents[other] = DocumentRow(other, "Other synthetic", 1, "ACTIVE", 0, 0, null)
                    f.metadata.pages[f.pageIds[1].value] = f.metadata.pages[f.pageIds[1].value]!!.copy(documentId = other)
                    f.change(f.pageIds[1], 2)
                }
                else -> {
                    f.metadata.pages[f.pageIds[1].value] = f.metadata.pages[f.pageIds[1].value]!!.copy(state = "REMOVED", ordinal = null)
                    f.change(f.pageIds[1], 2)
                }
            }
            assertTrue(f.repository.applyBatchRecipes(f.document, 1, listOf(first, invalid)) is DocumentMutationResult.Rejected)
            assertEquals(2, f.metadata.recipes.size); assertTrue(f.metadata.undos.isEmpty())
            assertEquals(1L, f.metadata.documents[f.document.value]!!.documentRevision)
        }
    }

    @Test fun batchSaveHasOneDocumentUndo() = runTest {
        val f = RepositoryFixture()
        val result = f.repository.applyBatchRecipes(f.document, 1, listOf(f.change(f.pageIds[0], 1), f.change(f.pageIds[1], 2))) as DocumentMutationResult.Applied
        assertEquals(2L, result.documentRevision); assertEquals(f.pageIds.toSet(), result.affectedPageIds.toSet())
        assertNotNull(result.undoId); assertEquals(1, f.metadata.undos.size)
        assertEquals("BATCH_RECIPE_EDIT", f.metadata.undos[result.undoId!!.value]!!.action)
        assertEquals(listOf(1, 2), f.pageIds.map { f.current(it).geometry.outputQuarterTurns })
    }

    @Test fun batchUndoCreatesNewRevisions() = runTest {
        val f = RepositoryFixture()
        val saved = f.repository.applyBatchRecipes(f.document, 1, f.pageIds.map { f.change(it, 1) }) as DocumentMutationResult.Applied
        val undone = f.repository.undoDocumentEdit(saved.undoId!!, 2) as DocumentMutationResult.Applied
        assertEquals(3L, undone.documentRevision)
        assertEquals(listOf(3L, 3L), f.pageIds.map { f.metadata.pages[it.value]!!.currentRecipeRevision })
        assertEquals(listOf(0, 0), f.pageIds.map { f.current(it).geometry.outputQuarterTurns })
        assertEquals(6, f.metadata.recipes.size)
        assertTrue(f.pageIds.all { f.metadata.recipes.containsKey(it.value to 2L) })
    }

    @Test fun duplicateDoneCannotCommitTwice() = runTest {
        val f = RepositoryFixture(); val changes = f.pageIds.map { f.change(it, 1) }
        assertTrue(f.repository.applyBatchRecipes(f.document, 1, changes) is DocumentMutationResult.Applied)
        val again = f.repository.applyBatchRecipes(f.document, 1, changes) as DocumentMutationResult.Conflict
        assertEquals(2L, again.actualRevision); assertEquals(4, f.metadata.recipes.size); assertEquals(1, f.metadata.undos.size)
    }

    @Test fun unsupportedSelectionPreflightChangesNoPage() = runTest {
        val f = RepositoryFixture(admission = { recipe, _ -> if (recipe.geometry.outputQuarterTurns == 2) OperationError(OperationErrorCode.PROVIDER_UNAVAILABLE, false, "selection_unavailable", null) else null })
        assertTrue(f.repository.applyBatchRecipes(f.document, 1, listOf(f.change(f.pageIds[0], 1), f.change(f.pageIds[1], 2))) is DocumentMutationResult.Rejected)
        assertEquals(listOf(0, 0), f.pageIds.map { f.current(it).geometry.outputQuarterTurns })
        assertEquals(1L, f.metadata.documents[f.document.value]!!.documentRevision); assertTrue(f.metadata.undos.isEmpty())
    }

    @Test fun admissionRaceRechecksAggregateDocumentRevision() = runTest {
        lateinit var f: RepositoryFixture
        f = RepositoryFixture(admission = { _, _ ->
            f.metadata.documents[f.document.value] = f.metadata.documents[f.document.value]!!.copy(documentRevision = 2)
            null
        })
        val result = f.repository.applyBatchRecipes(f.document, 1, f.pageIds.map { f.change(it, 1) }) as DocumentMutationResult.Conflict
        assertEquals(2L, result.actualRevision); assertEquals(2, f.metadata.recipes.size); assertTrue(f.metadata.undos.isEmpty())
    }
}
