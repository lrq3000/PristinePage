package app.pristinepage

import app.pristinepage.capture.PhotoImportRequest
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.AlbumSearchQuery
import app.pristinepage.library.*
import java.time.ZoneOffset
import java.util.Locale
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Real retained library reducer; only metadata IO is controlled. Selection is an opaque page identity validated against the open document, never an index, provider or bitmap. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryPageSelectionTest {
    @Test fun anOpenQueryShowsNewlyCommittedOcrWithoutReloadingAlbumMatches() = runTest {
        val f = Fixture()
        var albumReads = 0
        f.find = { _, _ -> albumReads++; f.page(f.other) }
        val hit = HomePageSearchHit(f.doc, f.second, "Synthetic selection", 2) { true }
        val session = ImportLibrarySession(f, LibraryIdentity(), this)
        session.accept(LibraryEvent.SetAlbumSearch("receipt", ZoneOffset.UTC))
        advanceUntilIdle()
        val observer = backgroundScope.launch { session.observePageSearch("receipt") }
        runCurrent()
        assertTrue(session.state.value.pageMatches.isEmpty())
        f.textMatches = { HomePageSearchPage(listOf(hit), null) }
        f.searchChanges.emit(Unit)
        runCurrent()
        assertEquals(listOf(hit), session.state.value.pageMatches)
        assertEquals(listOf(f.other), session.state.value.documents.map { it.documentId })
        assertEquals(1, albumReads)
        session.accept(LibraryEvent.OpenPageMatch(hit) { album, page ->
            assertEquals(f.doc to f.second, album to page)
        })
        advanceUntilIdle()
        assertEquals(f.second, session.identity().selectedPage)
        observer.cancel()
    }

    @Test fun aCommitBeforeObserverRegistrationIsPickedUpByItsInitialRead() = runTest {
        val f = Fixture().apply { initialSearchSignal = true }
        val session = ImportLibrarySession(f, LibraryIdentity(), this)
        session.accept(LibraryEvent.SetAlbumSearch("receipt", ZoneOffset.UTC))
        advanceUntilIdle()
        assertTrue(session.state.value.pageMatches.isEmpty())
        val hit = HomePageSearchHit(f.doc, f.first, "Synthetic selection", 1) { true }
        f.textMatches = { HomePageSearchPage(listOf(hit), null) }
        val observer = backgroundScope.launch { session.observePageSearch("receipt") }
        runCurrent()
        assertEquals(listOf(hit), session.state.value.pageMatches)
        observer.cancel()
    }

    @Test fun committedOcrRefreshPreservesTheLoadedPageWindowAndItsCursor() = runTest {
        val f = Fixture()
        var extra = false
        var noRead = false
        val first = HomePageSearchHit(f.doc, f.first, "Synthetic selection", 1) { true }
        val second = HomePageSearchHit(f.doc, f.second, "Synthetic selection", 2) { true }
        val appended = HomePageSearchHit(f.other, f.foreign, "Synthetic selection", 1) { true }
        f.textMatches = {
            check(!noRead)
            HomePageSearchPage(listOf(first)) { HomePageSearchPage(if (extra) listOf(second, appended) else listOf(second), null) }
        }
        val session = ImportLibrarySession(f, LibraryIdentity(), this)
        session.accept(LibraryEvent.SetAlbumSearch("receipt", ZoneOffset.UTC))
        advanceUntilIdle()
        session.accept(LibraryEvent.More)
        advanceUntilIdle()
        val observer = backgroundScope.launch { session.observePageSearch("receipt") }
        runCurrent()
        noRead = true
        f.searchChanges.emit(Unit)
        runCurrent()
        assertEquals(listOf(first, second), session.state.value.pageMatches)
        assertFalse(session.state.value.hasMore)
        noRead = false
        extra = true
        f.searchChanges.emit(Unit)
        runCurrent()
        assertEquals(listOf(first, second, appended), session.state.value.pageMatches)
        assertFalse(session.state.value.hasMore)
        observer.cancel()
    }

    @Test fun aRefreshThatRewrapsTheSameProducerDoesNotInterruptAnAdmittedTap() = runTest {
        val f = Fixture()
        val verification = CompletableDeferred<Boolean>()
        fun hit() = HomePageSearchHit(f.doc, f.second, "Synthetic selection", 2) { verification.await() }
        val original = hit()
        var newest = original
        f.textMatches = { HomePageSearchPage(listOf(newest), null) }
        val session = ImportLibrarySession(f, LibraryIdentity(), this)
        session.accept(LibraryEvent.SetAlbumSearch("receipt", ZoneOffset.UTC))
        advanceUntilIdle()
        val observer = backgroundScope.launch { session.observePageSearch("receipt") }
        runCurrent()
        val opened = mutableListOf<PageId>()
        session.accept(LibraryEvent.OpenPageMatch(original) { _, page -> opened += page })
        runCurrent()
        newest = hit()
        f.searchChanges.emit(Unit)
        runCurrent()
        assertSame(newest, session.state.value.pageMatches.single())
        verification.complete(true)
        advanceUntilIdle()
        assertEquals(listOf(f.second), opened)
        observer.cancel()
    }

    @Test fun homeKeepsSeparateOcrHitsAndOpensTheExactMatchingPageOnly() = runTest {
        val f = Fixture()
        var allowed = true
        val match = HomePageSearchHit(f.doc, f.second, "Synthetic selection", 2) { allowed }
        f.textMatches = { HomePageSearchPage(listOf(match), null) }
        val session = ImportLibrarySession(f, LibraryIdentity(), this)
        session.accept(LibraryEvent.SetAlbumSearch("receipt", ZoneOffset.UTC))
        advanceUntilIdle()
        assertEquals(listOf(f.other), session.state.value.documents.map { it.documentId })
        assertEquals(listOf(match), session.state.value.pageMatches)
        val opened = mutableListOf<Pair<DocumentId, PageId>>()
        allowed = false
        session.accept(LibraryEvent.OpenPageMatch(match) { album, page -> opened += album to page })
        advanceUntilIdle()
        assertTrue(opened.isEmpty())
        assertNull(session.identity().selected)
        allowed = true
        session.accept(LibraryEvent.OpenPageMatch(match) { album, page -> opened += album to page })
        advanceUntilIdle()
        assertEquals(listOf(f.doc to f.second), opened)
        assertEquals(f.second, session.identity().selectedPage)
    }

    @Test fun changingTheQueryFencesEvenAnUncooperativeOldPageClick() = runTest {
        val f = Fixture()
        lateinit var check: Continuation<Boolean>
        val match = HomePageSearchHit(f.doc, f.second, "Synthetic selection", 2) { suspendCoroutine { check = it } }
        f.textMatches = { if (it == "receipt") HomePageSearchPage(listOf(match), null) else HomePageSearchPage(emptyList(), null) }
        f.find = { _, _ -> f.page(f.other) }
        val session = ImportLibrarySession(f, LibraryIdentity(), this)
        session.accept(LibraryEvent.SetAlbumSearch("receipt", ZoneOffset.UTC))
        advanceUntilIdle()
        session.accept(LibraryEvent.OpenPageMatch(match) { _, _ -> throw AssertionError("Stale click opened a page") })
        runCurrent()
        session.accept(LibraryEvent.SetAlbumSearch("other", ZoneOffset.UTC))
        advanceUntilIdle()
        check.resume(true)
        advanceUntilIdle()
        assertNull(session.identity().selected)
        assertTrue(session.state.value.pageMatches.isEmpty())
    }

    @Test fun leavingHomeAndASecondTapFenceEarlierOpenRequests() = runTest {
        val f = Fixture()
        val firstCheck = CompletableDeferred<Boolean>()
        val first = HomePageSearchHit(f.doc, f.first, "Synthetic selection", 1) { firstCheck.await() }
        val second = HomePageSearchHit(f.doc, f.second, "Synthetic selection", 2) { false }
        f.textMatches = { HomePageSearchPage(listOf(first, second), null) }
        val session = ImportLibrarySession(f, LibraryIdentity(), this)
        session.accept(LibraryEvent.SetAlbumSearch("receipt", ZoneOffset.UTC))
        advanceUntilIdle()
        session.accept(LibraryEvent.OpenPageMatch(first) { _, _ -> throw AssertionError("Earlier tap was applied") })
        runCurrent()
        session.accept(LibraryEvent.OpenPageMatch(second) { _, _ -> throw AssertionError("Refused tap was applied") })
        runCurrent()
        firstCheck.complete(true)
        advanceUntilIdle()
        assertNull(session.identity().selected)

        val outsideHome = CompletableDeferred<Boolean>()
        val delayed = HomePageSearchHit(f.doc, f.first, "Synthetic selection", 1) { outsideHome.await() }
        f.textMatches = { HomePageSearchPage(listOf(delayed), null) }
        session.accept(LibraryEvent.Refresh)
        advanceUntilIdle()
        session.accept(LibraryEvent.OpenPageMatch(delayed) { _, _ -> throw AssertionError("Tap reopened an editor over Settings") })
        runCurrent()
        session.accept(LibraryEvent.LeaveHomeSearch)
        outsideHome.complete(true)
        advanceUntilIdle()
        assertNull(session.identity().selected)
    }

    @Test fun eitherFailedSearchStreamKeepsItsContinuationAndDoesNotBlockTheOther() = runTest {
        val f = Fixture()
        val cursor = AlbumPosition(f.doc, "Synthetic selection", 0, 0)
        val hit = HomePageSearchHit(f.doc, f.second, "Synthetic selection", 2) { true }
        var albumReads = 0
        var ocrReads = 0
        f.find = { _, after ->
            if (after == null) f.page(f.doc, cursor)
            else if (++albumReads == 1) error("Temporary album read failure")
            else f.page(f.other)
        }
        f.textMatches = { HomePageSearchPage(emptyList()) {
            if (++ocrReads == 1) error("Temporary OCR read failure")
            HomePageSearchPage(listOf(hit), null)
        } }
        val session = ImportLibrarySession(f, LibraryIdentity(), this)
        session.accept(LibraryEvent.SetAlbumSearch("receipt", ZoneOffset.UTC))
        advanceUntilIdle()
        session.accept(LibraryEvent.More)
        advanceUntilIdle()
        assertEquals(1, albumReads)
        assertEquals(1, ocrReads)
        assertTrue(session.state.value.hasMore)
        session.accept(LibraryEvent.More)
        advanceUntilIdle()
        assertEquals(listOf(f.doc, f.other), session.state.value.documents.map { it.documentId })
        assertEquals(listOf(hit), session.state.value.pageMatches)
        assertFalse(session.state.value.hasMore)
    }

    @Test fun homeSearchReplacesResultsAndClearRestoresTheLibrary() = runTest {
        val f = Fixture()
        val session = ImportLibrarySession(f, LibraryIdentity(), this)
        advanceUntilIdle()
        session.accept(LibraryEvent.SetAlbumSearch("receipt", ZoneOffset.UTC))
        assertTrue(session.state.value.documents.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf(f.other), session.state.value.documents.map { it.documentId })
        session.accept(LibraryEvent.SetAlbumSearch("", ZoneOffset.UTC))
        advanceUntilIdle()
        assertEquals(listOf(f.doc, f.other), session.state.value.documents.map { it.documentId })
    }

    @Test fun searchQueryIsNotPrintedOrRestored() = runTest {
        val f = Fixture()
        val session = ImportLibrarySession(f, LibraryIdentity(), this)
        session.accept(LibraryEvent.SetAlbumSearch("receipt", ZoneOffset.UTC))
        advanceUntilIdle()
        assertEquals("receipt", session.state.value.albumSearch)
        assertFalse(session.state.value.toString().contains("receipt"))
        val reopened = ImportLibrarySession(f, session.identity(), this)
        advanceUntilIdle()
        assertEquals("", reopened.state.value.albumSearch)
    }

    @Test fun changingSearchDropsTheCursorAndRejectsALateOldPage() = runTest {
        val f = Fixture()
        val cursor = AlbumPosition(f.doc, "Synthetic selection", 0, 0)
        lateinit var oldPage: Continuation<AlbumPage>
        f.find = { query, after ->
            when {
                query.text == "receipt" && after == null -> f.page(f.doc, cursor)
                query.text == "receipt" && after == cursor -> suspendCoroutine { oldPage = it }
                query.text == "invoice" && after == null -> f.page(f.other)
                else -> error("Unexpected search term or cursor")
            }
        }
        val session = ImportLibrarySession(f, LibraryIdentity(), this)
        session.accept(LibraryEvent.SetAlbumSearch("receipt", ZoneOffset.UTC))
        advanceUntilIdle()
        assertTrue(session.state.value.hasMore)
        session.accept(LibraryEvent.More)
        runCurrent()
        session.accept(LibraryEvent.SetAlbumSearch("invoice", ZoneOffset.UTC))
        advanceUntilIdle()
        oldPage.resume(f.page(f.doc, cursor))
        advanceUntilIdle()
        assertEquals(listOf(f.other), session.state.value.documents.map { it.documentId })
        assertFalse(session.state.value.hasMore)
    }

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

    private class Fixture : LibraryActions, SearchableLibraryActions, SearchablePageActions {
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
        var find: (suspend (AlbumSearchQuery, AlbumPosition?) -> AlbumPage)? = null
        var textMatches: (suspend (String) -> HomePageSearchPage) = { HomePageSearchPage(emptyList(), null) }
        val searchChanges = MutableSharedFlow<Unit>()
        var initialSearchSignal = false
        override suspend fun rename(document: DocumentId, revision: Long, title: String): DocumentMutationResult {
            renameWrites++
            return afterRename()
        }
        override suspend fun list(after: DocumentId?, limit: Int): List<DocumentSummary> {
            check(!catalogUnavailable)
            return listOf(summary(doc), summary(other))
        }
        override suspend fun searchAlbums(query: AlbumSearchQuery, order: AlbumOrder, locale: Locale, after: AlbumPosition?, limit: Int): AlbumPage {
            find?.let { return it(query, after) }
            assertEquals("receipt", query.text)
            assertEquals(null, after)
            return page(other)
        }
        fun page(id: DocumentId, next: AlbumPosition? = null) = AlbumPage(listOf(summary(id)), next)
        override suspend fun searchPages(query: String) = textMatches(query)
        override fun pageSearchChanges(query: String) = searchChanges.onStart { if (initialSearchSignal) emit(Unit) }
        override suspend fun load(id: DocumentId): DocumentOverview { val snapshot = DocumentOverview(summary(id),if (id == doc) pages.toList() else emptyList()); gate?.await(); return snapshot }
        private fun summary(id: DocumentId) = DocumentSummary(id,"Synthetic selection",revision,if (id == doc) pages.size else 0,false)
        override suspend fun activeImport(id: DocumentId): ImportSessionId? = null
        override suspend fun create(title: String): DocumentId = error("Selection does not create documents")
        override suspend fun begin(document: DocumentSummary): PhotoImportRequest = error("Selection does not reserve imports")
        override suspend fun deliver(request: PhotoImportRequest, selection: LibraryPhotoResult): List<IngestResult> = error("Selection does not read providers")
        override suspend fun cancel(request: PhotoImportRequest) = error("Selection does not abandon imports")
    }
}
