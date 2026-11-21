package app.pristinepage.library

import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.AlbumExportNames
import app.pristinepage.data.model.AlbumExportScope
import app.pristinepage.data.model.DocumentOverview
import app.pristinepage.data.model.DocumentPage
import app.pristinepage.data.model.PageId
import java.util.Collections

/** Reviewed names and metadata only. File creation needs a separate revision-checked freeze and verified export. */
internal class AlbumExportReview private constructor(
    val documentId: DocumentId,
    val expectedDocumentRevision: Long,
    val albumTitle: String,
    pages: List<Page>,
    names: AlbumExportNames,
) {
    data class Page(val page: DocumentPage, val jpegName: String)

    val pages: List<Page> = Collections.unmodifiableList(ArrayList(pages))
    val singleJpegName: String? get() = pages.singleOrNull()?.jpegName
    val pdfName = names.pdf
    val zipName = names.zip

    companion object {
        /** Null requests the whole album explicitly; an empty, stale or foreign selection always refuses. */
        fun prepare(album: DocumentOverview, selection: Set<PageId>?): AlbumExportReview? {
            val ordered = album.pages
            if (ordered.size !in 1..100 || album.document.pageCount != ordered.size ||
                ordered.withIndex().any { (ordinal, page) -> ordinal != page.ordinal || page.recipeRevision < 1L } ||
                ordered.map { it.pageId }.toSet().size != ordered.size || album.document.documentRevision < 1L) return null
            val chosen = if (selection == null) ordered else ordered.filter { it.pageId in selection }
            if (chosen.isEmpty() || (selection != null && selection.size != chosen.size)) return null
            val names = try { AlbumExportNames(AlbumExportScope(album.document.title, chosen.map { it.ordinal + 1 })) }
            catch (_: IllegalArgumentException) { return null }
            return AlbumExportReview(album.document.documentId, album.document.documentRevision, album.document.title,
                chosen.zip(names.jpegs, ::Page), names)
        }
    }
}
