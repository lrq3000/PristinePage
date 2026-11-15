package app.pristinepage.data.repository

import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Availability policy only: retain all Undo bytes, page/history/mask/source ownership and files. EXPIRED is never permission to finalize a removed page or reclaim its source. Consumers must wire the explicit per-document pass at startup/library entry; no scheduler is implied. */
class RoomUndoRetention @JvmOverloads constructor(private val metadata: MetadataAccess, private val clock: () -> Long = System::currentTimeMillis, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    constructor(database: PristineDatabase) : this(RoomMetadataAccess(database))

    suspend fun expireDocument(documentId: DocumentId): RecoveryReport = withContext(dispatcher) {
        try {
            val now = clock()
            metadata.transaction {
                val document = metadata.queries.document(documentId.value) ?: throw RepositoryFailure(OperationError(OperationErrorCode.SOURCE_UNAVAILABLE, false, "document_unavailable", documentId.value))
                if (document.documentRevision < 1) invalidRetention()
                // An injected clock or synchronous read can finish after cancellation. Refuse a known-canceled pass before its first state write; a cancellation racing a committed transaction still needs reconciliation.
                coroutineContext.ensureActive()
                expireWithinTransaction(metadata.queries, documentId.value, now)
            }
        } catch (canceled: CancellationException) { throw canceled }
        catch (failure: RepositoryFailure) { throw failure }
        catch (_: Exception) { throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "undo_retention_write_failed", null)) }
    }

    companion object {
        /** Caller holds the creating command's original metadata transaction, or the standalone expiry transaction. Ordering includes APPLIED/EXPIRED edits, so their removal from availability cannot promote an older token back into the window. No reference/counter is adjusted. */
        internal fun expireWithinTransaction(queries: CatalogQueries, documentId: String, now: Long): RecoveryReport {
            val inspected = queries.countUnexpiredUndoEntries(documentId)
            if (inspected < 0) invalidRetention()
            val completed = if (inspected == 0L) 0 else queries.expireUndoEntries(documentId, now)
            if (completed < 0 || completed.toLong() > inspected) invalidRetention()
            return RecoveryReport(inspected, completed.toLong(), 0, 0, 0, emptyList())
        }

        private fun invalidRetention(): Nothing = throw RepositoryFailure(OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "invalid_undo_retention_state", null))
    }
}
