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
    val documentId = intent.documentId.value
    if (metadata.hasAtelierCopyOwner(documentId))
        requireAtelierCopyAllowance(metadata, documentId, 1, intent.sessionId.value, metadata.isHiddenAtelierDraft(documentId))
    val bytes = IngestJournalCodec.encodeIntent(intent)
    journal.insertIngestJob(JobRow(intent.jobId.value, "RECONCILE", IngestJobIdentity.key(intent), IngestJobIdentity.hash(bytes), null, null, bytes.copyOf(), "PENDING", 0, null, null, 0, null, null, now, now))
    journal.insertFileTransaction(FileTransactionRow(intent.transactionId.value, "INGEST", intent.sessionId.value, "INTENT", "staging/${intent.transactionId.value}/original.part", "sources/${intent.sourceBlobId.value}/original.bin", null, null, null, bytes, intent.attempt, null, now))
}

/** Charge the full payload allowance for each owned attempt, including unsealed and retired copies. Call in the same writer transaction as reservation/intent insertion. */
internal fun requireAtelierCopyAllowance(metadata: MetadataAccess, documentId: String, count: Int, affectedId: String, hidden: Boolean) {
    val limit = RoomAtelierDrafts.MAX_RETAINED_STAGING_COPY_ATTEMPTS
    val remaining = limit - metadata.retainedStagingCopyAttempts(limit)
    val refusal = when {
        remaining <= 0 -> "draft_staging_full"
        count > remaining -> "draft_staging_selection_limit"
        hidden && metadata.retainedAtelierCopyAttempts(documentId) > RoomAtelierDrafts.MAX_COPY_ATTEMPTS_PER_DRAFT - count -> "draft_copy_limit"
        else -> return
    }
    throw RepositoryFailure(OperationError(OperationErrorCode.RESOURCE_LIMIT, false, refusal, affectedId))
}
