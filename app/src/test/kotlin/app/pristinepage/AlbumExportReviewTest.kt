package app.pristinepage

import app.pristinepage.data.model.*
import app.pristinepage.library.AlbumExportReview
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class AlbumExportReviewTest {
    @Test fun selectedFilesKeepAlbumOrderAndOriginalPositionsAndUseTheReviewedAlbumName() {
        val album = album("Invoices")
        val second = album.pages[1].pageId
        val fifth = album.pages[4].pageId
        val review = checkNotNull(AlbumExportReview.prepare(album, linkedSetOf(fifth, second)))
        assertEquals(listOf(second, fifth), review.pages.map { it.page.pageId })
        assertEquals(listOf("Invoices_2.jpg", "Invoices_5.jpg"), review.pages.map { it.jpegName })
        assertEquals("Invoices.pdf", review.pdfName)
        assertEquals("Invoices.zip", review.zipName)
        assertNull(review.singleJpegName)
        assertEquals(7L, review.expectedDocumentRevision)
        assertEquals("Invoices_5.jpg", AlbumExportReview.prepare(album, setOf(fifth))?.singleJpegName)
    }

    @Test fun anEmptyOrStaleSelectionCannotBecomeAnEntireAlbumExport() {
        val album = album("Invoices")
        assertNull(AlbumExportReview.prepare(album, emptySet()))
        assertNull(AlbumExportReview.prepare(album, setOf(album.pages.first().pageId, PageId(id()))))
        assertEquals(5, AlbumExportReview.prepare(album, null)?.pages?.size)
    }

    @Test fun unsafeFilenameCharactersAndWindowsDeviceNamesCannotCreateUnsafeArchiveEntries() {
        val unsafe = album("../2026\\taxes:*")
        val plan = checkNotNull(AlbumExportReview.prepare(unsafe, setOf(unsafe.pages[1].pageId)))
        assertEquals("_2026_taxes___2.jpg", plan.singleJpegName)
        assertEquals("_CON.pdf", AlbumExportReview.prepare(album("CON"), null)?.pdfName)
        val longName = checkNotNull(AlbumExportReview.prepare(album("📄".repeat(80)), null)).pages.last().jpegName
        assertTrue(longName.toByteArray(Charsets.UTF_8).size <= 255)
        assertTrue(longName.endsWith("_5.jpg"))
        assertFalse(longName.contains('\uFFFD'))
    }

    @Test fun truncationCannotRecreateAReservedFilenameAfterTrimming() {
        assertEquals("_CON.pdf", AlbumExportReview.prepare(album("CON" + "\u3000".repeat(82) + "X"), null)?.pdfName)
        assertEquals("_LPT1.zip", AlbumExportReview.prepare(album("LPT1" + "\u3000".repeat(81) + "XY"), null)?.zipName)
    }

    private fun album(name: String) = DocumentOverview(DocumentSummary(DocumentId(id()), name, 7, 5, false),
        List(5) { DocumentPage(PageId(id()), SourceId(id()), 1, it) })

    private fun id() = UUID.randomUUID().toString()
}
