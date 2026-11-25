package app.pristinepage.data

import app.pristinepage.data.db.DocumentRow
import app.pristinepage.data.db.PageRow
import app.pristinepage.data.db.RecipeRevisionRow
import app.pristinepage.data.db.UndoEntryRow
import app.pristinepage.data.library.PortableUndoEntry
import app.pristinepage.data.model.DocumentMutationResult
import app.pristinepage.data.model.SaveRecipeResult
import app.pristinepage.data.repository.LibraryUndoCodec
import app.pristinepage.data.repository.LibraryUndoCodec.MembershipPointer
import app.pristinepage.data.repository.LibraryUndoCodec.RecipePointer
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PortableUndoEntryTest {
    private val doc = DocumentRow(id(1), "Receipts", 5, "ACTIVE", 0, 0, null)
    private val cover = PageRow(id(3), doc.documentId, id(13), 3, "ACTIVE", 0, null, 0)
    private val insert = PageRow(id(4), doc.documentId, cover.sourceId, 1, "ACTIVE", 1, null, 0)
    private val back = PageRow(id(5), doc.documentId, id(15), 1, "ACTIVE", 2, null, 0)

    @Test fun pairedRecipeStatesBindBothRevisionsToTheSameOwningPage() {
        val row = entry("RECIPE_EDIT", recipe(cover, 1), recipe(cover, 2))
        val admitted = admit(row)
        row.beforeState.fill(0)
        admitted.row.afterState.fill(0)
        assertArrayEquals(recipe(cover, 1), admitted.row.beforeState)
        assertArrayEquals(recipe(cover, 2), admitted.row.afterState)
        assertEquals(setOf(cover.sourceId), admitted.pinnedSources)
        assertEquals(4L, admitted.row.appliedDocumentRevision)
        assertEquals(100L, admitted.row.expiresAt)
    }

    @Test fun independentValidPayloadsDoNotAuthorizeAnUnrelatedOrUnownedEdit() {
        val row = entry("RECIPE_EDIT", recipe(cover, 1), recipe(cover, 2))
        assertThrows(IllegalArgumentException::class.java) { admit(row.copy(afterState = recipe(insert, 1))) }
        assertThrows(IllegalArgumentException::class.java) { admit(row.copy(afterState = recipe(cover, 3))) }
        assertThrows(IllegalArgumentException::class.java) { admit(row.copy(beforeState = recipe(cover.copy(sourceId = back.sourceId), 1))) }
        assertThrows(IllegalArgumentException::class.java) { admit(row.copy(appliedDocumentRevision = 6)) }
        assertThrows(IllegalArgumentException::class.java) { admit(row, pages = listOf(cover.copy(documentId = id(2)), back, insert)) }
        assertThrows(IllegalArgumentException::class.java) { admit(row.copy(state = "PENDING")) }
    }

    @Test fun membershipUndoCannotUseRemovalToReorderUnchangedSurvivors() {
        val unique = insert.copy(sourceId = id(14))
        val before = membership(pointer(cover, 0), pointer(unique, 1), pointer(back, 2))
        val removed = pointer(unique, 1).copy(state = "REMOVED", ordinal = null, removedAt = 9)
        val normal = entry("PAGE_REMOVE", before, membership(pointer(cover, 0), removed, pointer(back, 1)))
        val owners = listOf(cover, unique, back)
        assertEquals(setOf(cover.sourceId, unique.sourceId, back.sourceId), admit(normal, owners).pinnedSources)
        val reordered = normal.copy(afterState = membership(pointer(cover, 1), removed, pointer(back, 0)))
        assertThrows(IllegalArgumentException::class.java) { admit(reordered, owners) }
    }

    @Test fun admitsAnOrdinaryReorderButNotAnUnchangedCopyOfItsBeforeState() = runTest {
        val source = RepositoryFixture()
        val applied = source.repository.reorderPages(source.document, 1, source.pageIds.reversed()) as DocumentMutationResult.Applied
        val row = checkNotNull(source.metadata.undo(checkNotNull(applied.undoId).value))
        assertEquals(setOf(source.source.value), admit(source, row).pinnedSources)
        assertThrows(IllegalArgumentException::class.java) { admit(source, row.copy(afterState = row.beforeState)) }
    }

    @Test fun everyPageInAnOrdinaryBatchMustHaveChangedBytes() = runTest {
        val source = RepositoryFixture()
        val applied = source.repository.applyBatchRecipes(source.document, 1, source.pageIds.map { source.change(it, 1) }) as DocumentMutationResult.Applied
        val row = checkNotNull(source.metadata.undo(checkNotNull(applied.undoId).value))
        assertEquals(setOf(source.source.value), admit(source, row).pinnedSources)
        val page = source.pageIds.last().value
        val unchanged = source.metadata.recipes.getValue(page to 1L)
        source.metadata.recipes[page to 2L] = unchanged.copy(recipeRevision = 2, parentRevision = 1)
        assertThrows(IllegalArgumentException::class.java) { admit(source, row) }
    }

    @Test fun ordinarySinglePageSaveMayHaveIdenticalRecipeBytes() = runTest {
        val source = RepositoryFixture()
        assertTrue(source.repository.saveRecipe(source.pageIds.first(), 1, source.initial) is SaveRecipeResult.Saved)
        val row = source.metadata.undos.values.single()
        assertEquals(setOf(source.source.value), admit(source, row).pinnedSources)
    }

    @Test fun duplicateUndoOnlyTombstonesTheOneProvenDuplicate() {
        val before = membership(pointer(cover, 0), pointer(back, 1))
        val after = membership(pointer(cover, 0), pointer(insert, 1), pointer(back, 2))
        val row = entry("PAGE_DUPLICATE", before, after)
        assertEquals(setOf(cover.sourceId, back.sourceId), admit(row).pinnedSources)
        val misplaced = row.copy(afterState = membership(pointer(cover, 0), pointer(back, 1), pointer(insert, 2)))
        assertThrows(IllegalArgumentException::class.java) { admit(misplaced) }
        assertThrows(IllegalArgumentException::class.java) { admit(row.copy(afterState = after.copyOf().also { it[0] = 0 })) }
    }

    private fun admit(row: UndoEntryRow, pages: List<PageRow> = listOf(cover, insert, back)) =
        PortableUndoEntry.admit(row, doc, pages.associateBy { it.pageId }::get) { page, rev ->
            if (page != cover.pageId || rev !in 1..3) null else RecipeRevisionRow(
                page, rev, cover.sourceId, 1, byteArrayOf(1), "a".repeat(64), when (rev) {
                    1L -> null
                    2L -> 1L
                    else -> 2L // Revision three exists, but it does not finish the recorded edit from revision one.
                }, 0,
            )
        }

    private fun entry(action: String, before: ByteArray, after: ByteArray) =
        UndoEntryRow(id(12), doc.documentId, before, after, action, 4, 100, "AVAILABLE")

    private fun admit(fixture: RepositoryFixture, row: UndoEntryRow) =
        PortableUndoEntry.admit(row, fixture.metadata.documents.getValue(fixture.document.value), fixture.metadata::page, fixture.metadata::recipe)

    private fun recipe(page: PageRow, revision: Long) =
        LibraryUndoCodec.encodeRecipe(listOf(RecipePointer(page.pageId, page.sourceId, revision)))

    private fun membership(vararg pages: MembershipPointer) = LibraryUndoCodec.encodeMembership(doc.documentId, pages.toList())
    private fun pointer(page: PageRow, position: Int) = MembershipPointer(page.pageId, page.sourceId, "ACTIVE", position, null)
    private fun id(value: Int) = UUID(0, value.toLong()).toString()
}
