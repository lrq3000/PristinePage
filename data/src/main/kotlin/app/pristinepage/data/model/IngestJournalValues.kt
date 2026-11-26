package app.pristinepage.data.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** Private ownership metadata only. The intent, job input and diagnostics contain no provider filename. The v3 file seal may carry a bounded logical label, never a path or provider URI. */
@Serializable data class IngestJournalIntent(val transactionId: TransactionId, val sessionId: ImportSessionId, val documentId: DocumentId, val pageId: PageId, val sourceBlobId: SourceBlobId, val sourceId: SourceId, val jobId: JobId, val attempt: Long, val schemaVersion: Int = 1)

/** Presence records that the allowlisted projection was attempted. Null day is not a guessed date or an uninspected legacy source. */
@Serializable data class SourceCaptureDate(val epochDay: Long?) {
    init { require(epochDay == null || epochDay in -719162L..2932896L) { "Capture day is outside years 0001–9999" } }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable data class IngestJournalSeal(
    val intent: IngestJournalIntent, val inspection: SourceInspection, val sourceByteHash: String,
    val byteCount: Long, val schemaVersion: Int = 1,
    // Absent only for v1. Omitting this default preserves the exact bytes of already-sealed attempts.
    @EncodeDefault(EncodeDefault.Mode.NEVER) val captureDate: SourceCaptureDate? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val preferredFilename: String? = null,
)

/** Retains the original commit revision for exact result replay even after later document edits. It is written only with page/source/reference/journal completion, never inferred from a live current pointer. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class IngestJournalCommit(val seal: IngestJournalSeal, val documentRevision: Long, val schemaVersion: Int = seal.schemaVersion,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val libraryFilename: String? = null)
