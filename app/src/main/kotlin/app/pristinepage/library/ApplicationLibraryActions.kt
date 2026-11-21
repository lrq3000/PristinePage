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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** A cold adapter to the one Application graph, not another store or acquisition path. Temporary references return to the existing router/controller; no grant, new journal, recovery engine or source normalization is duplicated here. */
class ApplicationLibraryActions(private val context: Context? = null, private val open: suspend () -> ApplicationImports) : LibraryActions, OrderedLibraryActions, SearchableLibraryActions {
    override suspend fun startup() = open().startupRecovery
    override suspend fun list(after: DocumentId?, limit: Int) = open().store.catalog.list(after, limit)
    override suspend fun listAlbums(order: AlbumOrder, locale: Locale, after: AlbumPosition?, limit: Int) = open().store.albums.listAlbums(order, locale, after, limit)
    override suspend fun searchAlbums(query: AlbumSearchQuery, order: AlbumOrder, locale: Locale, after: AlbumPosition?, limit: Int) =
        open().store.albumSearch.searchAlbums(query, order, locale, after, limit)
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
        val outcomes = PhotoImportResultRouter(graph.photoImports, graph.store.lifecycle).deliver(request, native)
        if (capture != null && outcomes.none { it is IngestResult.Pending }) withContext(Dispatchers.IO) { capture.discard() }
        return outcomes
    }
    override suspend fun cancel(request: PhotoImportRequest) {
        open().store.lifecycle.cancelBatch(request.session)
        // A durable batch rediscovered without Activity state no longer carries its original UI route.
        context?.let { app -> withContext(Dispatchers.IO) { CaptureStage(app, request.batchId).discard() } }
    }
}
