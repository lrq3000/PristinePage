package app.pristinepage.data.library

import android.database.Cursor
import app.pristinepage.data.db.UndoEntryRow
import app.pristinepage.data.model.requireOpaqueId

/** Read bounded rows before decoding either state. Both callers own a stable SQLite snapshot. */
internal object LibraryUndoRows {
    private const val FIELDS = "undoId,documentId,action,appliedDocumentRevision,expiresAt,state,beforeState,afterState"
    private const val MAX_ROWS = 16 * 1024 * 1024 / 128
    private const val EXTENT_SQL = """SELECT count(*) AS rows,
        coalesce(sum(length(beforeState) + length(afterState)),0) AS payloadBytes FROM UndoEntry"""
    private const val VALID = """typeof(undoId)='text' AND length(CAST(undoId AS BLOB))=36
        AND typeof(documentId)='text' AND length(CAST(documentId AS BLOB))=36
        AND typeof(appliedDocumentRevision)='integer' AND appliedDocumentRevision>0 AND typeof(expiresAt)='integer'
        AND action IN ('RECIPE_EDIT','BATCH_RECIPE_EDIT','PAGE_REORDER','PAGE_REMOVE','PAGE_DUPLICATE')
        AND state IN ('AVAILABLE','APPLIED','EXPIRED')
        AND typeof(beforeState)='blob' AND length(beforeState) BETWEEN 1 AND ${PortableUndoEntry.MAX_STATE_BYTES}
        AND typeof(afterState)='blob' AND length(afterState) BETWEEN 1 AND ${PortableUndoEntry.MAX_STATE_BYTES}"""

    fun read(query: (String, Array<String>) -> Cursor, document: String?, charge: (Long) -> Unit,
        checkpoint: () -> Unit, invalid: () -> Nothing, limit: () -> Nothing, consume: (UndoEntryRow) -> Unit,
    ) {
        val args = if (document == null) emptyArray() else arrayOf(document)
        val scope = if (document == null) "" else "documentId=? AND "
        val where = if (document == null) "" else "WHERE documentId=?"
        checkpoint()
        query("SELECT 1 FROM UndoEntry WHERE ${scope}NOT coalesce(($VALID),0) LIMIT 1", args).use { if (it.moveToFirst()) invalid() }
        // ORDER BY may sort the full payload, even if Kotlin would stop after a few rows.
        // Admit the total extent with lengths only while the same read snapshot is still held.
        query("$EXTENT_SQL $where", args).use { extent ->
            if (!extent.moveToFirst()) invalid()
            val count = extent.getLong(0)
            if (count !in 0L..MAX_ROWS.toLong()) limit()
            charge(128L * count + extent.getLong(1))
        }
        checkpoint()
        query("SELECT $FIELDS FROM UndoEntry $where ORDER BY documentId,appliedDocumentRevision,undoId LIMIT ${MAX_ROWS + 1}", args).use { cursor ->
            var count = 0
            var priorDocument: String? = null
            var priorRevision = 0L
            while (cursor.moveToNext()) {
                checkpoint()
                if (++count > MAX_ROWS) limit()
                val owner = cursor.getString(1).also(::requireOpaqueId)
                val revision = cursor.getLong(3)
                // Ordinary writers use a fresh document revision per command. Ties would make collision remapping change rank.
                if (priorDocument == owner && priorRevision >= revision) invalid()
                priorDocument = owner
                priorRevision = revision
                consume(UndoEntryRow(cursor.getString(0), owner, cursor.getBlob(6), cursor.getBlob(7),
                    cursor.getString(2), revision, cursor.getLong(4), cursor.getString(5)))
            }
        }
    }
}
