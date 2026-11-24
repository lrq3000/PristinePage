package app.pristinepage.data.repository

import app.pristinepage.core.library.LibraryScanName
import app.pristinepage.data.db.LibraryScanNameDao
import app.pristinepage.data.db.LibraryScanNameRow

/** Only the existing ingest transaction may allocate and commit a name using this port. */
interface IngestScanNames {
    fun insert(page: String, document: String, mime: String, preferred: String?): String
    fun matches(page: String, document: String, filename: String): Boolean
}

/** Metadata only. The existing page writer transaction owns allocation, insertion and its final exact readback. */
internal class RoomLibraryScanNames(private val dao: LibraryScanNameDao) : IngestScanNames {
    override fun insert(page: String, document: String, mime: String, preferred: String?): String {
        val label = LibraryScanName.forIngest(preferred, page, mime) { dao.containsKey(document, it) }
        dao.insert(LibraryScanNameRow(page, document, label, LibraryScanName.comparisonKey(label)))
        return label
    }

    override fun matches(page: String, document: String, filename: String) =
        dao.matches(page, document, filename, LibraryScanName.comparisonKey(filename))

    fun copyName(originalPage: String, originalDocument: String, page: String, document: String, mime: String): String {
        val preferred = dao.storedName(originalPage, originalDocument)
        if (preferred != null) require(matches(originalPage, originalDocument, preferred))
        return insert(page, document, mime, preferred)
    }
}
