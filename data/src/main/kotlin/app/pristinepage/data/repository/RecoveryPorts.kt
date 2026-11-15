package app.pristinepage.data.repository

import app.pristinepage.data.db.FileTransactionRow
import app.pristinepage.data.model.RecoveryReport

/** Stable opaque-ID pagination, not an unbounded directory walk. A coordinator must re-read every row's current identity/state before doing work; this listing grants no ownership. */
interface IngestRecoveryQueries {
    fun unfinishedIngestsAfter(transactionId: String, limit: Int): List<FileTransactionRow>
}

interface RecoveryCoordinator {
    suspend fun reconcile(): RecoveryReport
}
