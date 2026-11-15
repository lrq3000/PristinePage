package app.pristinepage.data.repository

import app.pristinepage.data.db.FileTransactionRow
import app.pristinepage.data.db.SourceBlobRow
import app.pristinepage.data.db.SourceRow
import app.pristinepage.data.model.IngestJournalIntent
import app.pristinepage.data.model.IngestResult

interface IngestCompletionQueries {
    fun sourceBlob(id: String): SourceBlobRow?
    fun insertSourceBlob(row: SourceBlobRow)
    fun insertSource(row: SourceRow)
    fun updateFileTransaction(row: FileTransactionRow, expectedState: String): Int
}

/** Final original-file readback occurs outside the writer transaction, then source/page/reference/session/journal rows and fenced job success commit together. This does not open provider URIs or replace the SourceInspector admission/normalization gate. */
interface IngestCompletion {
    suspend fun commit(intent: IngestJournalIntent, lease: JobLease): IngestResult
}
