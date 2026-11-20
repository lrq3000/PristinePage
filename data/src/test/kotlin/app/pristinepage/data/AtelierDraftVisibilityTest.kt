package app.pristinepage.data

import app.pristinepage.data.model.DocumentMutationResult
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.SaveRecipeResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AtelierDraftVisibilityTest {
    @Test fun ordinaryCommandsCannotChangeDraftMembershipOrRecipeById() = runBlocking {
        val f = RepositoryFixture()
        f.metadata.hiddenDraftDocuments.add(f.document.value)
        val membership = f.metadata.pages.toMap()
        val recipes = f.metadata.recipes.toMap()
        val refs = f.metadata.references.toMap()
        val albums = f.metadata.documents.toMap()
        val page = f.pageIds.first()

        val mutations = listOf(
            f.repository.reorderPages(f.document, 1, f.pageIds.reversed()),
            f.repository.removePage(page, 1),
            f.repository.duplicatePage(page, 1),
            f.repository.renameDocument(f.document, 1, "Hidden draft renamed"),
        )
        assertEquals(List(4) { OperationErrorCode.SOURCE_UNAVAILABLE },
            mutations.map { (it as? DocumentMutationResult.Rejected)?.error?.code })
        val save = f.repository.saveRecipe(page, 1, f.turned(1))
        assertEquals(OperationErrorCode.SOURCE_UNAVAILABLE, (save as? SaveRecipeResult.Rejected)?.error?.code)
        assertEquals(membership, f.metadata.pages)
        assertEquals(recipes, f.metadata.recipes)
        assertEquals(refs, f.metadata.references)
        assertEquals(albums, f.metadata.documents)
        assertEquals(emptyMap<String, Any>(), f.metadata.undos)
    }
}
