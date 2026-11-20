package app.pristinepage.data

import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AtelierDraftPageReadTest {
    @Test fun onlyTheCurrentEditingGenerationCanReadTheHiddenSourceBoundRecipe() = runBlocking {
        val f = Fixture()
        f.rejected(OperationErrorCode.SOURCE_UNAVAILABLE) { f.reader.load(f.base.document, f.page) }
        assertEquals(f.base.initial, f.load().recipe)
        f.rejected(OperationErrorCode.SOURCE_UNAVAILABLE) { f.load(generation = 1) }
        f.rejected(OperationErrorCode.SOURCE_UNAVAILABLE) { f.load(draftId = UUID.randomUUID().toString()) }
        f.rejected(OperationErrorCode.SOURCE_UNAVAILABLE) { f.load(pageId = PageId(UUID.randomUUID().toString())) }
        f.editing = false
        f.rejected(OperationErrorCode.SOURCE_UNAVAILABLE) { f.load() }
    }

    @Test fun wizardCannotReadARecipeThatTheSavedEditorWouldReject() = runBlocking {
        val f = Fixture()
        val key = f.page.value to 1L
        val row = f.base.metadata.recipes.getValue(key)
        // Trailing valid JSON whitespace decodes and hashes to the same recipe, but these are not the canonical stored bytes.
        f.base.metadata.recipes[key] = row.copy(canonicalRecipe = row.canonicalRecipe + byteArrayOf(32))
        f.rejected(OperationErrorCode.INTEGRITY_FAILED) { f.load() }
    }

    private class Fixture {
        val base = RepositoryFixture()
        val page = base.pageIds.first()
        val id = UUID.randomUUID().toString()
        var editing = true
        private val access = object : MetadataAccess by base.metadata {
            override fun atelierEditingDocument(draftId: String, generation: Long): String? {
                check(base.metadata.inTransaction)
                return base.document.value.takeIf { editing && draftId == id && generation == 2L }
            }
        }
        val reader = RoomEditablePages(access, base.metadata, Dispatchers.Unconfined)
        init { base.metadata.hiddenDraftDocuments.add(base.document.value) }

        suspend fun load(draftId: String = id, generation: Long = 2L, pageId: PageId = page): EditablePage =
            reader.loadForDraft(draftId, generation, pageId)

        suspend fun rejected(code: OperationErrorCode, action: suspend () -> Any?) {
            try { action(); fail("A draft page read unexpectedly succeeded") }
            catch (failure: RepositoryFailure) { assertEquals(code, failure.error.code) }
        }
    }
}
