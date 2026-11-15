package app.pristinepage.data

import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** Exercise real synchronous preflight/metadata command publication. Cancellation is injected before the first intended write, not after an already committed edit; only Android Room is replaced by the existing transactional fixture. */
class DocumentCancellationTest {
    private fun state(f: RepositoryFixture): List<Any> = with(f.metadata) {
        listOf(documents.toMap(), pages.toMap(), sources.toMap(), blobs.toMap(), recipes.toMap(), references.toMap(), undos.toMap(), masks.toMap(), associations.toMap(), maskReferences.toMap())
    }

    private suspend fun canceledWithoutWrites(f: RepositoryFixture, job: Job, action: suspend () -> Any) {
        val before = state(f); var canceled = false
        try { withContext(job) { action() } } catch (_: CancellationException) { canceled = true }
        assertTrue("The command must propagate cancellation instead of a successful edit", canceled)
        assertEquals("Canceled document command changed persisted metadata", before, state(f))
    }

    private fun clockCanceledRepository(f: RepositoryFixture, job: Job) = RoomDocumentRepository(f.metadata, RecipeAdmission { _, _ -> null }, { job.cancel(); 1000L }, Dispatchers.Unconfined)

    @Test fun canceledSaveAdmissionCannotPublishRecipeOrUndo() = runTest {
        val job = Job(); val f = RepositoryFixture(admission = { _, _ -> job.cancel(); null })
        canceledWithoutWrites(f, job) { f.repository.saveRecipe(f.pageIds[0], 1, f.turned(1)) }
    }

    @Test fun canceledBatchAdmissionCannotPublishAnyPageOrPin() = runTest {
        val job = Job(); val f = RepositoryFixture(admission = { _, _ -> job.cancel(); null })
        canceledWithoutWrites(f, job) { f.repository.applyBatchRecipes(f.document, 1, f.pageIds.map { f.change(it, 1) }) }
    }

    @Test fun canceledDuplicateAdmissionCannotInsertPageOrShiftOrdinals() = runTest {
        val job = Job(); val f = RepositoryFixture(admission = { _, _ -> job.cancel(); null })
        canceledWithoutWrites(f, job) { f.repository.duplicatePage(f.pageIds[0], 1) }
    }

    @Test fun canceledRecipeUndoAdmissionCannotConsumeUndoOrAppendHistory() = runTest {
        val job = Job(); var cancel = false
        val f = RepositoryFixture(admission = { _, _ -> if (cancel) job.cancel(); null })
        assertTrue(f.repository.saveRecipe(f.pageIds[0], 1, f.turned(1)) is SaveRecipeResult.Saved)
        val undo = UndoId(f.metadata.undos.keys.single()); cancel = true
        canceledWithoutWrites(f, job) { f.repository.undoDocumentEdit(undo, 2) }
    }

    @Test fun canceledCreatePreflightCannotPublishAnUnreportedDocument() = runTest {
        val job = Job(); val f = RepositoryFixture(); val repository = clockCanceledRepository(f, job)
        canceledWithoutWrites(f, job) { repository.createDocument("Synthetic canceled creation") }
    }

    @Test fun canceledRenamePreflightCannotChangeTitleOrRevision() = runTest {
        val job = Job(); val f = RepositoryFixture(); val repository = clockCanceledRepository(f, job)
        canceledWithoutWrites(f, job) { repository.renameDocument(f.document, 1, "Canceled title") }
    }

    @Test fun canceledReorderPreflightCannotLeaveNewOrTemporaryOrdinals() = runTest {
        val job = Job(); val f = RepositoryFixture(); val repository = clockCanceledRepository(f, job)
        canceledWithoutWrites(f, job) { repository.reorderPages(f.document, 1, f.pageIds.reversed()) }
    }

    @Test fun canceledRemovalPreflightCannotTombstoneOrAddUndoPins() = runTest {
        val job = Job(); val f = RepositoryFixture(); val repository = clockCanceledRepository(f, job)
        canceledWithoutWrites(f, job) { repository.removePage(f.pageIds[0], 1) }
    }

    @Test fun canceledMembershipUndoPreflightCannotReactivateOrConsumeUndo() = runTest {
        val job = Job(); val f = RepositoryFixture()
        val removed = f.repository.removePage(f.pageIds[0], 1) as DocumentMutationResult.Applied
        val repository = clockCanceledRepository(f, job)
        canceledWithoutWrites(f, job) { repository.undoDocumentEdit(checkNotNull(removed.undoId), 2) }
    }
}
