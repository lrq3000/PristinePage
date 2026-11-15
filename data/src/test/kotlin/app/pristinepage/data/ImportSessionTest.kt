package app.pristinepage.data

import app.pristinepage.data.db.ImportSessionRow
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Android metadata is the only substituted boundary; real page IDs, row types and session command logic are exercised. URI binding, file ingest and actual Room session serialization remain separate integration gates. */
class ImportSessionTest {
    private class Fixture(count: Int = 2) : ImportSessionQueries {
        val pages = RepositoryFixture(count)
        val rows = linkedMapOf<String, ImportSessionRow>()
        val access = object : MetadataAccess {
            override val queries: CatalogQueries get() = pages.metadata
            override fun <T> transaction(action: () -> T): T {
                val before = rows.toMap()
                return try { pages.metadata.transaction(action) }
                catch (failure: Throwable) { rows.clear(); rows.putAll(before); throw failure }
            }
        }
        var now = 1000L
        val commands: ImportSessionCommands
        init {
            val type = try { Class.forName("app.pristinepage.data.repository.RoomImportSessions") }
            catch (error: ClassNotFoundException) { throw AssertionError("Durable session reservation commands are absent", error) }
            commands = try { type.constructors.single { it.parameterCount == 4 }.newInstance(access, this, { now }, Dispatchers.Unconfined) as ImportSessionCommands }
            catch (error: InvocationTargetException) { throw error.targetException }
        }
        override fun session(id: String) = rows[id]
        override fun children(parentId: String) = rows.values.filter { it.parentSessionId == parentId }.sortedBy { it.selectionOrdinal }
        override fun activeBatches(documentId: String) = rows.values.filter { it.documentId == documentId && it.kind == "BATCH" && it.state !in setOf("COMMITTED", "CANCELED", "FAILED", "QUARANTINED") }
        override fun insertSession(row: ImportSessionRow) { check(rows.putIfAbsent(row.sessionId, row) == null) }
        override fun updateSession(row: ImportSessionRow): Int { if (row.sessionId !in rows) return 0; rows[row.sessionId] = row; return 1 }
    }

    @Test fun parentIsDurableBeforePickerAndReservingChildrenDoesNotExposePages() = runTest {
        val f = Fixture(); val doc = f.pages.document
        val batch = f.commands.beginImport(doc, 1, f.pages.pageIds[0])
        assertTrue(batch.childIds.isEmpty())
        val parent = f.rows[batch.parentId.value]!!
        assertEquals(doc.value, parent.documentId); assertEquals("BATCH", parent.kind)
        assertEquals(f.pages.pageIds[0].value, parent.anchorPageId); assertNull(parent.uriText)
        val reserved = f.commands.reserveImportItems(batch.parentId, 3)
        assertEquals(3, reserved.childIds.size); assertEquals(3, reserved.childIds.toSet().size)
        val children = f.children(batch.parentId.value)
        assertEquals(listOf(0, 1, 2), children.map { it.selectionOrdinal })
        assertEquals(3, children.map { PageId(it.plannedPageId!!) }.toSet().size)
        assertTrue(children.all { it.kind == "ITEM" && it.documentId == doc.value && it.uriText == null })
        assertEquals(2, f.pages.metadata.pages.size)
        assertEquals(1L, f.pages.metadata.documents[doc.value]!!.documentRevision)
    }

    @Test fun repeatedReservationReturnsTheSameChildrenInsteadOfDuplicatingResults() = runTest {
        val f = Fixture(); val batch = f.commands.beginImport(f.pages.document, 1, null)
        val first = f.commands.reserveImportItems(batch.parentId, 2)
        val replay = f.commands.reserveImportItems(batch.parentId, 2)
        assertEquals(first, replay); assertEquals(3, f.rows.size)
        try { f.commands.reserveImportItems(batch.parentId, 3); fail("Changed picker result cannot replace reserved identities") }
        catch (failure: RepositoryFailure) { assertEquals(OperationErrorCode.INVALID_INPUT, failure.error.code) }
        assertEquals(3, f.rows.size)
    }

    @Test fun anotherUnresolvedInsertionBatchCannotCompeteForOneDocument() = runTest {
        val f = Fixture(); f.commands.beginImport(f.pages.document, 1, null)
        try { f.commands.beginImport(f.pages.document, 1, null); fail("Competing insertion batch accepted") }
        catch (failure: RepositoryFailure) { assertEquals(OperationErrorCode.REVISION_CONFLICT, failure.error.code) }
        assertEquals(1, f.rows.size)
    }

    @Test fun overSelectionRejectsAllChildrenRatherThanTruncatingToCapacity() = runTest {
        val f = Fixture(99); val batch = f.commands.beginImport(f.pages.document, 1, null)
        try { f.commands.reserveImportItems(batch.parentId, 2); fail("Over-selection accepted") }
        catch (failure: RepositoryFailure) { assertEquals(OperationErrorCode.RESOURCE_LIMIT, failure.error.code) }
        assertTrue(f.children(batch.parentId.value).isEmpty()); assertEquals(99, f.pages.metadata.pages.size)
    }

    @Test fun recipeOnlyDocumentRevisionChangeDoesNotInvalidateMembershipReservation() = runTest {
        val f = Fixture(); val doc = f.pages.document
        val batch = f.commands.beginImport(doc, 1, null)
        assertTrue(f.pages.repository.saveRecipe(f.pages.pageIds[0], 1, f.pages.turned(1)) is SaveRecipeResult.Saved)
        assertEquals(2, f.commands.reserveImportItems(batch.parentId, 2).childIds.size)
        assertEquals(2L, f.pages.metadata.documents[doc.value]!!.documentRevision)
    }

    @Test fun membershipChangeOrExpiredSessionCannotReserveLateResultIntoNewState() = runTest {
        for (expire in listOf(false, true)) {
            val f = Fixture(); val batch = f.commands.beginImport(f.pages.document, 1, null)
            if (expire) f.now = f.rows[batch.parentId.value]!!.expiresAt
            else {
                f.pages.metadata.pages.remove(f.pages.pageIds[0].value)
                val remaining = f.pages.pageIds[1].value
                f.pages.metadata.pages[remaining] = f.pages.metadata.pages[remaining]!!.copy(ordinal = 0)
            }
            try { f.commands.reserveImportItems(batch.parentId, 1); fail("Stale result accepted") }
            catch (failure: RepositoryFailure) { assertEquals(if (expire) OperationErrorCode.SESSION_EXPIRED else OperationErrorCode.REVISION_CONFLICT, failure.error.code) }
            assertTrue(f.children(batch.parentId.value).isEmpty())
        }
    }
}
