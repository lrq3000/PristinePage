package app.pristinepage.data.library

import androidx.sqlite.db.SupportSQLiteDatabase
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.repository.AtelierPublicationReceipt

/** Before projecting saved records, refuse unfinished operations in the same source snapshot. A deadline alone never abandons a recoverable import. */
internal object LibrarySnapshotAdmission {
    private const val BLOCKED = """SELECT EXISTS (
        SELECT 1 FROM AtelierDraft WHERE typeof(state)!='text' OR state NOT IN ('ABANDONED','COMMITTED') OR (
            state='COMMITTED' AND NOT (
                typeof(stagingDocumentId)='text' AND length(CAST(stagingDocumentId AS BLOB))=36
                AND typeof(committedDocumentId)='text' AND length(CAST(committedDocumentId AS BLOB))=36
                AND committedDocumentId IS coalesce(targetDocumentId,stagingDocumentId)
                AND ((targetDocumentId IS NULL AND targetRevision IS NULL) OR
                    (typeof(targetDocumentId)='text' AND targetDocumentId!=stagingDocumentId AND typeof(targetRevision)='integer' AND targetRevision>0))
                AND typeof(generation)='integer' AND generation>1 AND typeof(step)='integer' AND step=2 AND activeBatchId IS NULL
                AND typeof(receiptPageIds)='text' AND length(CAST(receiptPageIds AS BLOB)) BETWEEN 197 AND 1400
            )
        )
        UNION ALL
        SELECT 1 FROM ImportSession WHERE typeof(state)!='text' OR state NOT IN ('COMMITTED','CANCELED','FAILED','QUARANTINED')
    )"""

    fun verifyInTransaction(database: SupportSQLiteDatabase, checkpoint: () -> Unit) {
        check(database.inTransaction())
        database.query(BLOCKED).use { cursor ->
            check(cursor.moveToFirst())
            if (cursor.getInt(0) != 0) blocked()
        }
        database.query("""SELECT committedDocumentId,generation,targetDocumentId IS NOT NULL,receiptPageIds
            FROM AtelierDraft WHERE state='COMMITTED' LIMIT 4097""").use { cursor ->
            var count = 0
            while (cursor.moveToNext()) {
                checkpoint()
                if (++count > 4096) throw RepositoryFailure(OperationError(OperationErrorCode.RESOURCE_LIMIT, false, "library_draft_receipts_limit", null))
                val receipt = try { AtelierPublicationReceipt.decode(cursor.getString(3)) }
                catch (_: IllegalArgumentException) { blocked() }
                val inputs = receipt.witnessed.map { it.page }
                val append = cursor.getInt(2) != 0
                if (receipt.generation != cursor.getLong(1) - 1 || receipt.document.value != cursor.getString(0) ||
                    (append && inputs.any { it in receipt.saved }) || (!append && inputs != receipt.saved)
                ) blocked()
            }
        }
    }

    private fun blocked(): Nothing = throw RepositoryFailure(
        OperationError(OperationErrorCode.REVISION_CONFLICT, false, "library_finish_pending_import_or_draft", null),
    )
}
