package app.pristinepage.library

import android.content.Context
import android.net.Uri
import app.pristinepage.ApplicationImports
import app.pristinepage.capture.CaptureStage
import app.pristinepage.capture.PhotoImportKind
import app.pristinepage.capture.PhotoImportRequest
import app.pristinepage.capture.PhotoImportResultRouter
import app.pristinepage.capture.PhotoSelection
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.AlbumSearchQuery
import app.pristinepage.data.repository.DocumentCatalog
import app.pristinepage.data.repository.RoomSourceMetadataSearch
import app.pristinepage.data.repository.SavedOcrWork
import app.pristinepage.data.repository.SourceMetadataQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.util.Locale

/** A cold adapter to the one Application graph, not another store or acquisition path. Temporary references return to the existing router/controller; no grant, new journal, recovery engine or source normalization is duplicated here. */
class ApplicationLibraryActions(
    private val context: Context?,
    private val onPagesSaved: ((DocumentId, List<PageId>) -> Unit)?,
    private val open: suspend () -> ApplicationImports,
) : LibraryActions, OrderedLibraryActions, SearchableLibraryActions, SearchablePageActions {
    constructor(context: Context? = null, open: suspend () -> ApplicationImports) : this(context, null, open)

    override suspend fun startup() = open().startupRecovery
    override suspend fun list(after: DocumentId?, limit: Int) = open().store.catalog.list(after, limit)
    override suspend fun listAlbums(order: AlbumOrder, locale: Locale, after: AlbumPosition?, limit: Int) = open().store.albums.listAlbums(order, locale, after, limit)
    override suspend fun searchAlbums(query: AlbumSearchQuery, order: AlbumOrder, locale: Locale, after: AlbumPosition?, limit: Int) =
        open().store.albumSearch.searchAlbums(query, order, locale, after, limit)

    override suspend fun searchPages(query: String): HomePageSearchPage =
        if (SourceMetadataQuery.parse(query) != null) searchMetadata(query, null) else searchRecognizedText(query, null)

    override fun pageSearchChanges(query: String) = flow {
        if (SourceMetadataQuery.parse(query) == null) emitAll(open().store.savedOcr.observeSearchChanges())
    }

    private suspend fun searchRecognizedText(query: String, cursor: SavedOcrWork.Continuation?): HomePageSearchPage {
        val store = open().store
        val page = store.savedOcr.find(query, cursor, 20)
        return HomePageSearchPage(presentPageHits(page.hits, { it.document }, { it.page }, store.catalog, store.savedOcr::canOpen),
            page.nextAfter?.let { next -> { searchRecognizedText(query, next) } })
    }

    private suspend fun searchMetadata(query: String, cursor: RoomSourceMetadataSearch.Continuation?): HomePageSearchPage {
        val store = open().store
        val page = store.sourceMetadata.find(query, cursor, 20)
        return HomePageSearchPage(presentPageHits(page.hits, { it.document }, { it.page }, store.catalog, store.sourceMetadata::canOpen,
            skipMalformedAlbums = true),
            page.next?.let { next -> { searchMetadata(query, next) } })
    }

    override suspend fun rename(document: DocumentId, revision: Long, title: String) = open().store.documents.renameDocument(document, revision, title)
    override suspend fun load(id: DocumentId) = open().store.catalog.load(id)
    override suspend fun activeImport(id: DocumentId) = open().store.catalog.activeImport(id)
    override suspend fun create(title: String) = open().store.documents.createDocument(title)
    override suspend fun begin(document: DocumentSummary): PhotoImportRequest = PhotoImportRequest(open().store.imports.beginImport(document.documentId, document.documentRevision, null).parentId.value)
    override suspend fun deliver(request: PhotoImportRequest, selection: LibraryPhotoResult): List<IngestResult> {
        val graph = open()
        val capture = if (request.kind == PhotoImportKind.CAMERA) CaptureStage(checkNotNull(context), request.batchId) else null
        val native = when (selection) {
            is LibraryPhotoResult.Selected -> {
                val uris = selection.references.map(Uri::parse)
                val captureAuthority = context?.let(CaptureStage::authority)
                // Even a gallery implementation cannot redirect a picker result into private camera staging.
                val expected = if (capture == null) uris.none { it.authority == captureAuthority } else uris == listOf(capture.uri)
                if (expected) PhotoSelection.Selected(uris, selection.offeredReadFlags)
                else PhotoSelection.Rejected(OperationError(OperationErrorCode.INVALID_INPUT, false, "invalid_photo_selection", null))
            }
            LibraryPhotoResult.Canceled -> PhotoSelection.Canceled
            is LibraryPhotoResult.Rejected -> PhotoSelection.Rejected(selection.error)
        }
        val outcomes = PhotoImportResultRouter(graph.photoImports, graph.store.lifecycle, onPagesSaved).deliver(request, native)
        // A late staging refusal can follow a real shutter but precede ingest. Keep the camera handoff
        // available until the user explicitly clears its pending batch; do not treat it as a completed copy.
        if (capture != null && outcomes.none { it is IngestResult.Pending || it is IngestResult.Rejected &&
                it.error.code == OperationErrorCode.RESOURCE_LIMIT &&
                it.error.safeMessageKey in setOf("draft_staging_full", "draft_staging_selection_limit", "draft_copy_limit") }) {
            withContext(Dispatchers.IO) { capture.discard() }
        }
        return outcomes
    }
    override suspend fun cancel(request: PhotoImportRequest) {
        open().store.lifecycle.cancelBatch(request.session)
        // A durable batch rediscovered without Activity state no longer carries its original UI route.
        context?.let { app -> withContext(Dispatchers.IO) { CaptureStage(app, request.batchId).discard() } }
    }
}

internal suspend fun <Hit> presentPageHits(
    candidates: List<Hit>, document: (Hit) -> DocumentId, page: (Hit) -> PageId,
    catalog: DocumentCatalog, revalidate: suspend (Hit) -> Boolean, skipMalformedAlbums: Boolean = false,
): List<HomePageSearchHit> {
    val albums = mutableMapOf<DocumentId, DocumentOverview?>()
    return candidates.mapNotNull { hit ->
        val documentId = document(hit)
        val pageId = page(hit)
        if (!albums.containsKey(documentId)) {
            albums[documentId] = try { catalog.load(documentId) }
            catch (failure: RepositoryFailure) {
                // Metadata selection can match a page whose sibling prevents the album from opening. Keep the other hits and the cursor.
                if (failure.error.code != OperationErrorCode.SOURCE_UNAVAILABLE &&
                    !(skipMalformedAlbums && failure.error.code == OperationErrorCode.INTEGRITY_FAILED && !failure.error.retryable)
                ) throw failure
                null
            }
        }
        val album = albums[documentId] ?: return@mapNotNull null
        val member = album.pages.firstOrNull { it.pageId == pageId } ?: return@mapNotNull null
        HomePageSearchHit(documentId, pageId, album.document.title, member.ordinal + 1) { revalidate(hit) }
    }
}
