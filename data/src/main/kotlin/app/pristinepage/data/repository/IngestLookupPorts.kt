package app.pristinepage.data.repository

import app.pristinepage.data.db.JobRow
import app.pristinepage.data.model.ImportSessionId
import app.pristinepage.data.model.IngestJournalIntent
import app.pristinepage.data.model.IngestResult

interface IngestLookupQueries {
    fun ingestJobByKey(key: String): JobRow?
}

/** Read-only operation identity lookup, not file verification or import admission. A committed journal contains a receipt, while its immutable Job.input retains the original canonical intent. */
interface IngestOperationLookup {
    fun find(sessionId: ImportSessionId): IngestJournalIntent?
}

/** Verifies and returns an already committed result without creating a fake runner/lease. Noncommitted work may be reported Pending but must never be published through this read-only route. */
interface IngestReplay {
    suspend fun replay(intent: IngestJournalIntent): IngestResult
}
