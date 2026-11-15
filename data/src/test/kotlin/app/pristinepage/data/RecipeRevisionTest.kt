package app.pristinepage.data

import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.data.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RecipeRevisionTest {
    @Test fun createDocumentKeepsNormalizedTitlePrivateAndRejectsInvalidMetadata() = runTest {
        val f = RepositoryFixture()
        val id = f.repository.createDocument("  Receipt / draft  ")
        val row = f.metadata.documents[id.value]!!
        assertEquals("Receipt / draft", row.title); assertEquals(1L, row.documentRevision)
        assertEquals("ACTIVE", row.lifecycle); assertFalse(id.value.contains("Receipt"))
        for (bad in listOf("  ", "x".repeat(161), "\uD800")) {
            try { f.repository.createDocument(bad); fail("Invalid title committed") }
            catch (failure: RepositoryFailure) { assertEquals(OperationErrorCode.INVALID_INPUT, failure.error.code) }
        }
        assertEquals(2, f.metadata.documents.size)
    }

    @Test fun saveRecipeCompareAndSwapRejectsStaleRevision() = runTest {
        val f = RepositoryFixture(); val page = f.pageIds[0]
        val saved = f.repository.saveRecipe(page, 1, f.turned(1)) as SaveRecipeResult.Saved
        assertEquals(2L, saved.recipeRevision); assertEquals(2L, saved.documentRevision)
        val conflict = f.repository.saveRecipe(page, 1, f.turned(2)) as SaveRecipeResult.Conflict
        assertEquals(2L, conflict.actualRevision)
        assertEquals(1, f.current(page).geometry.outputQuarterTurns)
        assertEquals(3, f.metadata.recipes.size)
    }

    @Test fun sourceBindingMismatchCannotReplaceAcceptedHistory() = runTest {
        val f = RepositoryFixture(); val page = f.pageIds[0]
        val wrong = f.initial.copy(geometry = GeometryRecipe.None(f.binding.copy(sourcePixelHash = "b".repeat(64))))
        assertTrue(f.repository.saveRecipe(page, 1, wrong) is SaveRecipeResult.Rejected)
        assertEquals(1L, f.metadata.documents[f.document.value]!!.documentRevision)
        assertEquals(2, f.metadata.recipes.size); assertEquals(0, f.current(page).geometry.outputQuarterTurns)
    }

    @Test fun duplicateSharesSourceButEditsIndependentHistory() = runTest {
        val f = RepositoryFixture(); val original = f.pageIds[0]
        val duplicated = f.repository.duplicatePage(original, 1) as DocumentMutationResult.Applied
        val added = duplicated.affectedPageIds.single()
        assertNotEquals(original, added)
        assertEquals(f.source.value, f.metadata.pages[added.value]!!.sourceId)
        assertEquals(listOf(0, 1, 2), f.metadata.activePages(f.document.value).map { it.ordinal })
        val saved = f.repository.saveRecipe(added, 1, f.turned(3)) as SaveRecipeResult.Saved
        assertEquals(2L, saved.recipeRevision); assertEquals(0, f.current(original).geometry.outputQuarterTurns)
        assertEquals(3, f.current(added).geometry.outputQuarterTurns)
        assertEquals(f.metadata.countSourceReferences(f.source.value), f.metadata.sources[f.source.value]!!.refCount)
    }

    @Test fun futureStoredRecipeRemainsReadOnlyAndBytePreserved() = runTest {
        val f = RepositoryFixture(); val page = f.pageIds[0]
        val future = "opaque future recipe bytes".toByteArray()
        f.metadata.recipes[page.value to 1L] = f.metadata.recipes[page.value to 1L]!!.copy(recipeSchemaVersion = 99, canonicalRecipe = future)
        val result = f.repository.saveRecipe(page, 1, f.turned(1)) as SaveRecipeResult.Rejected
        assertEquals(OperationErrorCode.UNSUPPORTED_VERSION, result.error.code)
        assertArrayEquals(future, f.metadata.recipes[page.value to 1L]!!.canonicalRecipe)
        assertEquals(1L, f.metadata.pages[page.value]!!.currentRecipeRevision)
        assertEquals(1L, f.metadata.documents[f.document.value]!!.documentRevision)
    }

    @Test fun documentChangesDuringAdmissionAreRecheckedWithoutLostRename() = runTest {
        lateinit var f: RepositoryFixture
        f = RepositoryFixture(admission = { _, _ ->
            val doc = f.metadata.documents[f.document.value]!!
            f.metadata.documents[f.document.value] = doc.copy(title = "Changed separately", documentRevision = 2)
            null
        })
        val result = f.repository.saveRecipe(f.pageIds[0], 1, f.turned(1)) as SaveRecipeResult.Saved
        assertEquals(3L, result.documentRevision)
        assertEquals("Changed separately", f.metadata.documents[f.document.value]!!.title)
    }
}
