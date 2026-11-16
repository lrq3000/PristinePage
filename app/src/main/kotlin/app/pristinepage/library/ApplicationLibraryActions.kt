package app.pristinepage.library

import android.net.Uri
import app.pristinepage.ApplicationImports
import app.pristinepage.capture.PhotoImportRequest
import app.pristinepage.capture.PhotoImportResultRouter
import app.pristinepage.capture.PhotoSelection
import app.pristinepage.data.model.*

/** A cold adapter to the one Application graph, not another store or acquisition path. Temporary references return to the existing router/controller; no grant, new journal, recovery engine or source normalization is duplicated here. */
class ApplicationLibraryActions(private val open: suspend () -> ApplicationImports) : LibraryActions {
    override suspend fun startup() = open().startupRecovery
    override suspend fun list(after: DocumentId?, limit: Int) = open().store.catalog.list(after, limit)
    override suspend fun load(id: DocumentId) = open().store.catalog.load(id)
    override suspend fun activeImport(id: DocumentId) = open().store.catalog.activeImport(id)
    override suspend fun create(title: String) = open().store.documents.createDocument(title)
    override suspend fun begin(document: DocumentSummary): PhotoImportRequest = PhotoImportRequest(open().store.imports.beginImport(document.documentId, document.documentRevision, null).parentId.value)
    override suspend fun deliver(request: PhotoImportRequest, selection: LibraryPhotoResult): List<IngestResult> {
        val graph = open()
        val native = when (selection) {
            is LibraryPhotoResult.Selected -> PhotoSelection.Selected(selection.references.map(Uri::parse), selection.offeredReadFlags)
            LibraryPhotoResult.Canceled -> PhotoSelection.Canceled
            is LibraryPhotoResult.Rejected -> PhotoSelection.Rejected(selection.error)
        }
        return PhotoImportResultRouter(graph.photoImports, graph.store.lifecycle).deliver(request, native)
    }
    override suspend fun cancel(request: PhotoImportRequest) { open().store.lifecycle.cancelBatch(ImportSessionId(request.batchId)) }
}
