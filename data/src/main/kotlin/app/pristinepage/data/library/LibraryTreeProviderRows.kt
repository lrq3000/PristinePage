package app.pristinepage.data.library

import android.database.Cursor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.IOException

/** A complete provider query is required even when there are no rows. Provider error details are never propagated to callers. */
internal object LibraryTreeProviderRows {
    val projection: Array<String> get() = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
    private const val MAX_TEXT = 4L * 1024 * 1024

    fun read(cursor: Cursor?, maximum: Int, checkpoint: () -> Unit): List<LibraryTreeReadSource.Entry> {
        checkpoint()
        cursor ?: throw IOException("The selected folder did not return a document listing")
        requireComplete(cursor)
        val id = cursor.getColumnIndex(Document.COLUMN_DOCUMENT_ID)
        val name = cursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME)
        val type = cursor.getColumnIndex(Document.COLUMN_MIME_TYPE)
        if (id < 0 || name < 0 || type < 0 || cursor.count !in 0..maximum) invalid()
        val result = ArrayList<LibraryTreeReadSource.Entry>()
        val seen = HashSet<String>()
        var total = 0L
        while (cursor.moveToNext()) {
            checkpoint()
            if (result.size == maximum) invalid()
            val documentId = text(cursor, id, 8_192)
            val displayName = text(cursor, name, 255)
            val mime = text(cursor, type, 255)
            if (!seen.add(documentId)) invalid()
            total += documentId.length + displayName.length
            if (total > MAX_TEXT) invalid()
            result += LibraryTreeReadSource.Entry(documentId, displayName, mime == Document.MIME_TYPE_DIR)
        }
        requireComplete(cursor)
        if (result.size != cursor.count) invalid()
        checkpoint()
        return result
    }

    private fun requireComplete(cursor: Cursor) {
        val extras = cursor.extras
        if (extras.getBoolean(DocumentsContract.EXTRA_LOADING) || extras.containsKey(DocumentsContract.EXTRA_ERROR)) invalid()
    }

    private fun text(cursor: Cursor, column: Int, maximum: Int): String {
        if (cursor.getType(column) != Cursor.FIELD_TYPE_STRING) invalid()
        return cursor.getString(column)?.takeIf { it.length in 1..maximum && '\u0000' !in it } ?: invalid()
    }

    private fun invalid(): Nothing = throw IOException("The selected folder document listing is incomplete or invalid")
}
