package app.pristinepage.library

import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.DocumentOverview
import app.pristinepage.data.model.DocumentPage
import app.pristinepage.data.model.PageId
import java.util.Collections
import java.util.Locale

/** Reviewed names and metadata only. File creation needs a separate revision-checked freeze and verified export. */
internal class AlbumExportReview private constructor(
    val documentId: DocumentId,
    val expectedDocumentRevision: Long,
    val albumTitle: String,
    pages: List<Page>,
    basename: String,
) {
    data class Page(val page: DocumentPage, val jpegName: String)

    val pages: List<Page> = Collections.unmodifiableList(ArrayList(pages))
    val singleJpegName: String? get() = pages.singleOrNull()?.jpegName
    val pdfName = "$basename.pdf"
    val zipName = "$basename.zip"

    companion object {
        private val reservedDevice = Regex("CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³]")

        /** Null requests the whole album explicitly; an empty, stale or foreign selection always refuses. */
        fun prepare(album: DocumentOverview, selection: Set<PageId>?): AlbumExportReview? {
            val ordered = album.pages
            if (ordered.size !in 1..100 || album.document.pageCount != ordered.size ||
                ordered.withIndex().any { (ordinal, page) -> ordinal != page.ordinal || page.recipeRevision < 1L } ||
                ordered.map { it.pageId }.toSet().size != ordered.size || album.document.documentRevision < 1L) return null
            val chosen = if (selection == null) ordered else ordered.filter { it.pageId in selection }
            if (chosen.isEmpty() || (selection != null && selection.size != chosen.size)) return null
            val name = basename(album.document.title)
            return AlbumExportReview(album.document.documentId, album.document.documentRevision, album.document.title, chosen.map {
                Page(it, "${name}_${it.ordinal + 1}.jpg")
            }, name)
        }

        private fun basename(title: String): String {
            val clean = title.map { if (it.isISOControl() || it in "\\/:*?\"<>|") '_' else it }
                .joinToString("").trim { it == '.' || it.isWhitespace() }.ifBlank { "pristinepage" }
            // Reserve nine bytes for "_100.jpg" and a possible device-name prefix; never cut a Unicode code point.
            var end = 0
            var bytes = 0
            while (end < clean.length) {
                val point = clean.codePointAt(end)
                val length = when { point < 0x80 -> 1; point < 0x800 -> 2; point < 0x10000 -> 3; else -> 4 }
                if (bytes + length > 246) break
                bytes += length
                end += Character.charCount(point)
            }
            val truncated = clean.substring(0, end).trimEnd { it == '.' || it.isWhitespace() }
            val stem = truncated.substringBefore('.').trimEnd().uppercase(Locale.ROOT)
            return if (reservedDevice.matches(stem)) "_$truncated" else truncated
        }
    }
}
