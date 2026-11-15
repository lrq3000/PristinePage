package app.pristinepage.data

import app.pristinepage.core.recipe.BinaryRecipe
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.data.db.RecipeMaskRow
import app.pristinepage.data.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Independent-review counterexamples use the real command implementation. They model retained histories/metadata writers, not a mask editor or Android database implementation. */
class RepositoryReviewRegressionTest {
    @Test fun successiveSavedBatchUndosRestoreLogicalStatesWithIncreasingRevisions() = runTest {
        val f = RepositoryFixture(); val page = f.pageIds[0]
        val first = f.repository.applyBatchRecipes(f.document, 1, listOf(f.change(page, 1))) as DocumentMutationResult.Applied
        val second = f.repository.applyBatchRecipes(f.document, 2, listOf(f.change(page, 2, 2))) as DocumentMutationResult.Applied
        assertTrue(f.repository.undoDocumentEdit(second.undoId!!, 3) is DocumentMutationResult.Applied)
        assertEquals(4L, f.metadata.pages[page.value]!!.currentRecipeRevision)
        val earlier = f.repository.undoDocumentEdit(first.undoId!!, 4)
        assertTrue("Restoring revision2 logically at new revision4 must not strand the earlier Undo", earlier is DocumentMutationResult.Applied)
        assertEquals(5L, f.metadata.pages[page.value]!!.currentRecipeRevision)
        assertEquals(5L, f.metadata.documents[f.document.value]!!.documentRevision)
        assertEquals(0, f.current(page).geometry.outputQuarterTurns)
        assertTrue((1L..5L).all { f.metadata.recipes.containsKey(page.value to it) })
    }

    @Test fun genuinelyDivergentLaterEditCannotBeOverwrittenByAnOlderUndo() = runTest {
        val f = RepositoryFixture(); val page = f.pageIds[0]
        val first = f.repository.applyBatchRecipes(f.document, 1, listOf(f.change(page, 1))) as DocumentMutationResult.Applied
        assertTrue(f.repository.applyBatchRecipes(f.document, 2, listOf(f.change(page, 2, 2))) is DocumentMutationResult.Applied)
        assertTrue(f.repository.undoDocumentEdit(first.undoId!!, 3) is DocumentMutationResult.Conflict)
        assertEquals(2, f.current(page).geometry.outputQuarterTurns)
        assertEquals(3L, f.metadata.pages[page.value]!!.currentRecipeRevision)
    }

    @Test fun quarantineDuringAdmissionRejectsTheEntireBatchBeforeWrites() = runTest {
        lateinit var f: RepositoryFixture
        lateinit var mask: String
        f = RepositoryFixture(admission = { _, _ -> f.metadata.masks[mask] = f.metadata.masks[mask]!!.copy(state = "QUARANTINED"); null })
        mask = f.protectionMask(f.pageIds[0], 1)
        val refs = f.metadata.countSourceReferences(f.source.value)
        assertTrue(f.repository.applyBatchRecipes(f.document, 1, f.pageIds.map { f.change(it, 1) }) is DocumentMutationResult.Rejected)
        assertEquals(listOf(1L, 1L), f.pageIds.map { f.metadata.pages[it.value]!!.currentRecipeRevision })
        assertEquals(2, f.metadata.recipes.size); assertTrue(f.metadata.undos.isEmpty())
        assertEquals(refs, f.metadata.countSourceReferences(f.source.value))
        assertEquals("QUARANTINED", f.metadata.masks[mask]!!.state)
    }

    @Test fun associationChangeDuringAdmissionCannotCopyTheStaleMask() = runTest {
        lateinit var f: RepositoryFixture
        lateinit var old: String
        lateinit var replacement: String
        f = RepositoryFixture(admission = { _, _ ->
            val page = f.pageIds[0].value
            f.metadata.associations.remove(Triple(page, 1L, old))
            f.metadata.associations[Triple(page, 1L, replacement)] = RecipeMaskRow(page, 1, replacement, "PROTECTION")
            f.metadata.masks[old] = f.metadata.masks[old]!!.copy(refCount = 0)
            f.metadata.masks[replacement] = f.metadata.masks[replacement]!!.copy(refCount = 1)
            null
        })
        old = f.protectionMask(f.pageIds[0], 1)
        replacement = f.protectionMask(f.pageIds[0], 1, attach = false)
        assertTrue(f.repository.applyBatchRecipes(f.document, 1, f.pageIds.map { f.change(it, 1) }) is DocumentMutationResult.Rejected)
        assertEquals(2, f.metadata.recipes.size); assertTrue(f.metadata.undos.isEmpty())
        assertTrue(f.metadata.recipeMasks(f.pageIds[0].value, 2).isEmpty())
    }

    @Test fun immutableMaskIdentityChangeDuringAdmissionRejectsSaveEvenWhenItStaysReady() = runTest {
        lateinit var f: RepositoryFixture
        lateinit var mask: String
        f = RepositoryFixture(admission = { _, _ ->
            f.metadata.masks[mask] = f.metadata.masks[mask]!!.copy(sha256 = "c".repeat(64))
            null
        })
        mask = f.protectionMask(f.pageIds[0], 1)
        assertTrue(f.repository.saveRecipe(f.pageIds[0], 1, f.turned(1)) is SaveRecipeResult.Rejected)
        assertEquals(1L, f.metadata.pages[f.pageIds[0].value]!!.currentRecipeRevision)
        assertEquals(1L, f.metadata.documents[f.document.value]!!.documentRevision)
        assertEquals(2, f.metadata.recipes.size); assertTrue(f.metadata.undos.isEmpty())
    }

    @Test fun undoAdmitsAnUnprotectedHistoricalBinaryRecipeUsingItsOwnMasks() = runTest {
        val observed = mutableListOf<Boolean>()
        val f = RepositoryFixture(admission = { recipe, protected ->
            observed += protected
            if (recipe.binary != null && protected) OperationError(OperationErrorCode.PROVIDER_UNAVAILABLE, false, "binary_protection_unsupported", null) else null
        })
        val page = f.pageIds[0]
        val historical = f.initial.copy(binary = BinaryRecipe.GrayscaleV1)
        val initialRow = f.metadata.recipes[page.value to 1L]!!
        f.metadata.recipes[page.value to 1L] = initialRow.copy(canonicalRecipe = RecipeCodec.encode(historical), recipeSha256 = RecipeCodec.hash(historical))
        assertTrue(f.repository.saveRecipe(page, 1, f.turned(1)) is SaveRecipeResult.Saved)
        val undo = UndoId(f.metadata.undos.keys.single())
        f.protectionMask(page, 2)
        observed.clear()
        val result = f.repository.undoDocumentEdit(undo, 2)
        assertTrue("Unprotected historical binary target must remain admissible", result is DocumentMutationResult.Applied)
        assertEquals(listOf(false), observed)
        assertEquals(BinaryRecipe.GrayscaleV1, f.current(page).binary)
        assertTrue(f.metadata.recipeMasks(page.value, 3).isEmpty())
    }

    @Test fun undoRejectsAnUnavailableHistoricalMaskInsteadOfUsingCurrentUnprotectedState() = runTest {
        val f = RepositoryFixture(admission = { _, protected -> if (protected) OperationError(OperationErrorCode.PROVIDER_UNAVAILABLE, false, "protected_target_unavailable", null) else null })
        val page = f.pageIds[0]
        assertTrue(f.repository.saveRecipe(page, 1, f.turned(1)) is SaveRecipeResult.Saved)
        val undo = UndoId(f.metadata.undos.keys.single())
        f.protectionMask(page, 1)
        assertTrue(f.repository.undoDocumentEdit(undo, 2) is DocumentMutationResult.Rejected)
        assertEquals(2L, f.metadata.pages[page.value]!!.currentRecipeRevision)
        assertEquals(2L, f.metadata.documents[f.document.value]!!.documentRevision)
        assertEquals(3, f.metadata.recipes.size)
    }

    @Test fun undoRejectsHistoricalTargetMutationDuringAdmissionBeforeCreatingARevision() = runTest {
        lateinit var f: RepositoryFixture
        var changeHistory = false
        f = RepositoryFixture(admission = { _, _ ->
            if (changeHistory) {
                val key = f.pageIds[0].value to 1L
                val changed = f.turned(3)
                f.metadata.recipes[key] = f.metadata.recipes[key]!!.copy(canonicalRecipe = RecipeCodec.encode(changed), recipeSha256 = RecipeCodec.hash(changed))
            }
            null
        })
        val page = f.pageIds[0]
        assertTrue(f.repository.saveRecipe(page, 1, f.turned(1)) is SaveRecipeResult.Saved)
        val undo = UndoId(f.metadata.undos.keys.single())
        changeHistory = true
        assertTrue(f.repository.undoDocumentEdit(undo, 2) is DocumentMutationResult.Rejected)
        assertEquals(2L, f.metadata.pages[page.value]!!.currentRecipeRevision)
        assertEquals(2L, f.metadata.documents[f.document.value]!!.documentRevision)
        assertEquals(3, f.metadata.recipes.size)
        assertEquals("AVAILABLE", f.metadata.undos[undo.value]!!.state)
    }
}
