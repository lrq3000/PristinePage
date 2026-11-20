package app.pristinepage.data.repository

import app.pristinepage.data.db.FileTransactionRow
import app.pristinepage.data.db.JobRow
import app.pristinepage.data.journal.IngestJournalCodec
import app.pristinepage.data.model.IngestJournalIntent
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure

/** Shared row encoding for initial preparation and preserved successor attempts. The caller owns the surrounding metadata transaction; no admission or lease is created. */
internal fun insertIngestIntent(metadata: MetadataAccess, journal: IngestPreparationQueries, intent: IngestJournalIntent, now: Long) {
    // A durable INGEST attempt reserves the full per-copy payload cap, even if no byte count was sealed. Retired partial copies can still occupy disk. Count and insert under the same Room writer transaction.
    if (metadata.isHiddenAtelierDraft(intent.documentId.value) &&
        metadata.retainedAtelierCopyAttempts(intent.documentId.value) >= RoomAtelierDrafts.MAX_COPY_ATTEMPTS_PER_DRAFT) {
        throw RepositoryFailure(OperationError(OperationErrorCode.RESOURCE_LIMIT, false, "draft_copy_limit", intent.sessionId.value))
    }
    val bytes = IngestJournalCodec.encodeIntent(intent)
    journal.insertIngestJob(JobRow(intent.jobId.value, "RECONCILE", IngestJobIdentity.key(intent), IngestJobIdentity.hash(bytes), null, null, bytes.copyOf(), "PENDING", 0, null, null, 0, null, null, now, now))
    journal.insertFileTransaction(FileTransactionRow(intent.transactionId.value, "INGEST", intent.sessionId.value, "INTENT", "staging/${intent.transactionId.value}/original.part", "sources/${intent.sourceBlobId.value}/original.bin", null, null, null, bytes, intent.attempt, null, now))
}
