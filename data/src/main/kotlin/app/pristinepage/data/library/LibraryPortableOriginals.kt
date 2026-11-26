package app.pristinepage.data.library

import android.database.sqlite.SQLiteDatabase
import app.pristinepage.data.model.requireOpaqueId
import java.io.IOException

/** Admit original locators without letting a SQLite row choose a path or materialize an unchecked string. Source/recipe/owner verification remains a separate join. */
internal object LibraryPortableOriginals {
    private const val ROW_LIMIT = 4_097
    private const val ADMITTED = """(
        typeof(sourceBlobId)='text' AND length(CAST(sourceBlobId AS BLOB))=36
        AND typeof(byteSha256)='text' AND length(CAST(byteSha256 AS BLOB))=64
        AND length(byteSha256)=64 AND byteSha256 NOT GLOB '*[^0-9a-f]*'
        AND typeof(byteCount)='integer' AND byteCount BETWEEN 1 AND 104857600
        AND typeof(relativePath)='text' AND relativePath='sources/'||sourceBlobId||'/original.bin'
        AND typeof(state)='text' AND state='READY'
        AND typeof(mime)='text' AND mime IN ('image/png','image/jpeg','image/webp')
        AND typeof(encodedWidth)='integer' AND encodedWidth BETWEEN 1 AND 12000
        AND typeof(encodedHeight)='integer' AND encodedHeight BETWEEN 1 AND 12000
        AND encodedWidth*encodedHeight<=48000000
    )"""
    private const val READ_ORIGINALS = """SELECT $ADMITTED AS valid,
        CASE WHEN $ADMITTED THEN sourceBlobId END AS blobId, CASE WHEN $ADMITTED THEN byteSha256 END AS byteSha,
        CASE WHEN $ADMITTED THEN byteCount END AS byteCount FROM SourceBlob LIMIT $ROW_LIMIT"""

    fun read(database: SQLiteDatabase, checkpoint: () -> Unit): Map<String, LibraryObjectTransfer.ObjectId> {
        val originals = LinkedHashMap<String, LibraryObjectTransfer.ObjectId>()
        database.rawQuery(READ_ORIGINALS, null).use { rows ->
            while (rows.moveToNext()) {
                checkpoint()
                if (rows.getInt(0) != 1 || originals.size == ROW_LIMIT - 1) invalid()
                try {
                    val id = rows.getString(1)
                    requireOpaqueId(id)
                    val item = LibraryObjectTransfer.ObjectId(rows.getString(2), rows.getLong(3))
                    if (originals.put(id, item) != null) invalid()
                } catch (_: IllegalArgumentException) { invalid() }
            }
        }
        return originals
    }

    private fun invalid(): Nothing = throw IOException("Invalid or oversized original references in library catalog")
}
