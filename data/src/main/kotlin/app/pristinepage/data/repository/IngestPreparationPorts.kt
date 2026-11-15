package app.pristinepage.data.repository

import app.pristinepage.data.db.FileTransactionRow
import app.pristinepage.data.db.JobRow
import app.pristinepage.data.model.ImportSessionId
import app.pristinepage.data.model.IngestJournalIntent

/** Same-database participants for recording an ingest intent before filesystem/provider IO. No READY source/page or admitted execution is created by preparation. */
interface IngestPreparationQueries {
    fun fileTransaction(id: String): FileTransactionRow?
    fun insertFileTransaction(row: FileTransactionRow)
    fun insertIngestJob(row: JobRow)
}

interface IngestPreparation {
    fun prepare(sessionId: ImportSessionId, boundUriText: String): IngestJournalIntent
}
