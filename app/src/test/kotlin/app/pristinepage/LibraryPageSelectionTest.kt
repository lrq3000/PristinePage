package app.pristinepage

import app.pristinepage.capture.PhotoImportRequest
import app.pristinepage.data.model.*
import app.pristinepage.library.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Real retained library reducer; only metadata IO is controlled. Selection is an opaque page identity validated against the open document, never an index, provider or bitmap. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryPageSelectionTest {
    @Test fun obsoleteResultCannotResurrectAClosedAlbum() = runTest {
        val f = Fixture()
        val session = ImportLibrarySession(f, LibraryIdentity(f.doc), this)
        advanceUntilIdle()
        f.gate = CompletableDeferred()
        session.accept(LibraryEvent.Refresh)
        runCurrent()
        session.accept(LibraryEvent.CloseAlbum)
        advanceUntilIdle()
        f.gate!!.complete(Unit)
        advanceUntilIdle()
        assertNull(session.state.value.selectedId)
        assertNull(session.state.value.document)
        assertNull(session.identity().selectedPage)
        assertEquals(listOf(f.doc, f.other), session.state.value.documents.map { it.documentId })
    }

    @Test fun unchangedTitleAndStaleLocalRevisionDoNotWrite() = runTest {
        val f = Fixture()
        val session = ImportLibrarySession(f, LibraryIdentity(f.doc), this)
        advanceUntilIdle()
        session.accept(LibraryEvent.RenameAlbum(f.doc, 1, " Synthetic selection "))
        assertTrue(session.state.value.renameResult is DocumentMutationResult.Applied)
        session.accept(LibraryEvent.ClearRenameResult)
        session.accept(LibraryEvent.RenameAlbum(f.doc, 2, "Changed"))
        advanceUntilIdle()
        assertEquals(DocumentMutationResult.Conflict(1), session.state.value.renameResult)
        assertEquals(0, f.renameWrites)
    }

    @Test fun renameConflictRefreshesTheOpenAlbumEvenWhenHomeCannotLoad() = runTest {
        val f = Fixture()
        val session = ImportLibrarySession(f, LibraryIdentity(f.doc), this)
        advanceUntilIdle()
        f.catalogUnavailable = true
        f.afterRename = {
            f.revision = 2
            DocumentMutationResult.Conflict(2)
        }
        session.accept(LibraryEvent.RenameAlbum(f.doc, 1, "New title"))
        advanceUntilIdle()
        assertEquals(DocumentMutationResult.Conflict(2), session.state.value.renameResult)
        assertEquals(2L, session.state.value.document?.document?.documentRevision)
        assertEquals(1, f.renameWrites)
    }

    @Test fun selectionSurvivesRefreshAndIdentityCaptureWithoutOpeningAnImport() = runTest {
        val f = Fixture(); val session = ImportLibrarySession(f,LibraryIdentity(f.doc),this); advanceUntilIdle()
        choose(session,f.second); session.accept(LibraryEvent.Refresh); advanceUntilIdle()
        assertEquals(f.second,selected(session.state.value)); assertEquals(f.second,selected(session.identity())); assertNull(session.identity().pending)
    }

    @Test fun foreignOrStalePageCannotReplaceTheCurrentValidSelection() = runTest {
        val f = Fixture(); val session = ImportLibrarySession(f,LibraryIdentity(f.doc),this); advanceUntilIdle()
        choose(session,f.second); choose(session,f.foreign)
        assertEquals(f.second,selected(session.state.value)); assertEquals(f.doc,session.state.value.selectedId)
    }

    @Test fun openingAnotherDocumentDropsOnlyTheOldPageContext() = runTest {
        val f = Fixture(); val session = ImportLibrarySession(f,LibraryIdentity(f.doc),this); advanceUntilIdle(); choose(session,f.second)
        session.accept(LibraryEvent.Open(f.other)); advanceUntilIdle(); choose(session,f.second)
        assertNull(selected(session.state.value)); assertNull(selected(session.identity())); assertEquals(f.other,session.state.value.document!!.document.documentId)
    }

    @Test fun restoredPageMustStillBelongToTheCurrentOverviewAfterRemoval() = runTest {
        val f = Fixture()
        val identity = LibraryIdentity(f.doc,null,f.second)
        val session = ImportLibrarySession(f,identity,this); advanceUntilIdle(); assertEquals(f.second,selected(session.state.value))
        f.pages = f.pages.take(1); session.accept(LibraryEvent.Refresh); advanceUntilIdle()
        assertNull(selected(session.state.value)); assertNull(selected(session.identity())); assertEquals(listOf(f.first),session.state.value.document!!.pages.map { it.pageId })
    }

    @Test fun completingAnOlderMetadataReadCannotRollBackAMoreRecentValidPageChoice() = runTest {
        val f = Fixture(); val session = ImportLibrarySession(f,LibraryIdentity(f.doc),this); advanceUntilIdle()
        f.gate = CompletableDeferred(); session.accept(LibraryEvent.Refresh); runCurrent(); choose(session,f.second)
        f.gate!!.complete(Unit); advanceUntilIdle(); assertEquals(f.second,selected(session.state.value))
    }

    private fun choose(session: LibrarySession, page: PageId) {
        session.accept(LibraryEvent.SelectPage(page))
    }
    private fun selected(value: LibraryState) = value.selectedPage
    private fun selected(value: LibraryIdentity) = value.selectedPage

    private class Fixture : LibraryActions {
        val doc = DocumentId("00000000-0000-0000-0000-000000000001"); val other = DocumentId("00000000-0000-0000-0000-000000000002")
        val first = PageId("00000000-0000-0000-0000-000000000003"); val second = PageId("00000000-0000-0000-0000-000000000004"); val foreign = PageId("00000000-0000-0000-0000-000000000005")
        private val source = SourceId("00000000-0000-0000-0000-000000000006")
        var pages = listOf(DocumentPage(first,source,1,0),DocumentPage(second,source,1,1))
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun startup() = RecoveryReport(0,0,0,0,0,emptyList())
        var catalogUnavailable = false
        var revision = 1L
        var renameWrites = 0
        var afterRename: () -> DocumentMutationResult = { error("Unexpected rename write") }
        override suspend fun rename(document: DocumentId, revision: Long, title: String): DocumentMutationResult {
            renameWrites++
            return afterRename()
        }
        override suspend fun list(after: DocumentId?, limit: Int): List<DocumentSummary> {
            check(!catalogUnavailable)
            return listOf(summary(doc), summary(other))
        }
        override suspend fun load(id: DocumentId): DocumentOverview { val snapshot = DocumentOverview(summary(id),if (id == doc) pages.toList() else emptyList()); gate?.await(); return snapshot }
        private fun summary(id: DocumentId) = DocumentSummary(id,"Synthetic selection",revision,if (id == doc) pages.size else 0,false)
        override suspend fun activeImport(id: DocumentId): ImportSessionId? = null
        override suspend fun create(title: String): DocumentId = error("Selection does not create documents")
        override suspend fun begin(document: DocumentSummary): PhotoImportRequest = error("Selection does not reserve imports")
        override suspend fun deliver(request: PhotoImportRequest, selection: LibraryPhotoResult): List<IngestResult> = error("Selection does not read providers")
        override suspend fun cancel(request: PhotoImportRequest) = error("Selection does not abandon imports")
    }
}
