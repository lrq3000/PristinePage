package app.pristinepage.data.repository

import app.pristinepage.data.db.FileTransactionRow
import app.pristinepage.data.db.JobRow
import app.pristinepage.data.journal.IngestJournalCodec
import app.pristinepage.data.model.IngestJournalIntent

/** Shared row encoding for initial preparation and preserved successor attempts. The caller owns the surrounding metadata transaction; no admission or lease is created. */
internal fun insertIngestIntent(journal: IngestPreparationQueries, intent: IngestJournalIntent, now: Long) {
    val bytes = IngestJournalCodec.encodeIntent(intent)
    journal.insertIngestJob(JobRow(intent.jobId.value, "RECONCILE", IngestJobIdentity.key(intent), IngestJobIdentity.hash(bytes), null, null, bytes.copyOf(), "PENDING", 0, null, null, 0, null, null, now, now))
    journal.insertFileTransaction(FileTransactionRow(intent.transactionId.value, "INGEST", intent.sessionId.value, "INTENT", "staging/${intent.transactionId.value}/original.part", "sources/${intent.sourceBlobId.value}/original.bin", null, null, null, bytes, intent.attempt, null, now))
}
