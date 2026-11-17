package app.pristinepage.testing

import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException

/** In-memory generated fixtures in the test APK, not a production data/export provider. MANAGE_DOCUMENTS protects discovery; the OS may issue only exact selected-document read access. No filesystem roots, user media, write/delete/admin call or network path is exposed. */
class SyntheticDocumentsProvider : DocumentsProvider() {
    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: ROOT_COLUMNS).apply {
        addRow(columnNames.map { column -> when (column) {
            Root.COLUMN_ROOT_ID -> ROOT_ID
            Root.COLUMN_DOCUMENT_ID -> ROOT_ID
            Root.COLUMN_TITLE -> "PristinePage QA"
            Root.COLUMN_SUMMARY -> "Generated local test images"
            Root.COLUMN_MIME_TYPES -> "image/png"
            Root.COLUMN_FLAGS -> Root.FLAG_LOCAL_ONLY
            else -> null
        } }.toTypedArray<Any?>())
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { append(documentId) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        if (parentDocumentId != ROOT_ID) throw FileNotFoundException("Unknown fixture directory")
        return MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { DOCUMENT_IDS.forEach { append(it) } }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String) = parentDocumentId == ROOT_ID && documentId in DOCUMENT_IDS

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Fixtures are read-only")
        signal?.throwIfCanceled()
        val bytes = encoded(documentId)
        return openPipeHelper(DocumentsContract.buildDocumentUri(AUTHORITY, documentId), "image/png", null, bytes) { output, _, _, _, data ->
            ParcelFileDescriptor.AutoCloseOutputStream(output).use { it.write(data) }
        }
    }

    private fun MatrixCursor.append(id: String) {
        if (id != ROOT_ID && id !in DOCUMENT_IDS) throw FileNotFoundException("Unknown fixture document")
        addRow(columnNames.map { column -> when (column) {
            Document.COLUMN_DOCUMENT_ID -> id
            Document.COLUMN_DISPLAY_NAME -> when (id) { ROOT_ID -> "Generated fixtures"; "original-a" -> "Original A.png"; "original-b" -> "Original B.png"; else -> "Corrupt image.png" }
            Document.COLUMN_MIME_TYPE -> if (id == ROOT_ID) Document.MIME_TYPE_DIR else "image/png"
            Document.COLUMN_FLAGS -> 0
            Document.COLUMN_SIZE -> if (id == ROOT_ID) null else encoded(id).size.toLong()
            Document.COLUMN_LAST_MODIFIED -> 0L
            else -> null
        } }.toTypedArray<Any?>())
    }

    private fun encoded(id: String): ByteArray {
        if (id == "corrupt") return byteArrayOf(1, 2, 3, 4)
        if (id !in DOCUMENT_IDS) throw FileNotFoundException("Unknown fixture document")
        val bitmap = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(if (id == "original-a") 0xff641428.toInt() else 0xff1464c8.toInt())
            ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        } finally { bitmap.recycle() }
    }

    private companion object {
        const val AUTHORITY = "app.pristinepage.debug.test.documents"
        const val ROOT_ID = "generated-fixtures-v1"
        val DOCUMENT_IDS = listOf("original-a", "original-b", "corrupt")
        val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_MIME_TYPES, Root.COLUMN_FLAGS)
        val DOCUMENT_COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
    }
}
