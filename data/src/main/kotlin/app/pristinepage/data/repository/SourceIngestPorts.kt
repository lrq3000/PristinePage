package app.pristinepage.data.repository

import android.net.Uri
import app.pristinepage.data.db.FileTransactionRow
import app.pristinepage.data.files.SealedSource
import app.pristinepage.data.model.*
import java.io.InputStream

/** Opening is synchronous and called only from SourceStore's IO scope, preventing a suspending adapter from losing an opened descriptor at a canceled dispatch boundary. The returned stream transfers close responsibility to the consumer. */
fun interface SourceInput {
    fun open(boundReference: String): InputStream
}

interface SourceStore {
    suspend fun ingest(uri: Uri, session: ImportSessionId): IngestResult
}

/** The native Uri route delegates to this private bound-provider metadata boundary; it is not a general filesystem/network loader. */
interface BoundSourceIngest {
    suspend fun ingestBoundProvider(boundReference: String, session: ImportSessionId): IngestResult
}

interface IngestPhaseQueries {
    fun updateIngestPhase(row: FileTransactionRow, expectedState: String): Int
}

/** Metadata-only conditional phase transitions. Resource admission precedes admit; a current fenced lease precedes all copying/seal/move/failure writes. No provider/hash/file/decoder work belongs inside these methods. */
interface IngestPhases {
    fun state(intent: IngestJournalIntent): FileTransactionState
    fun admit(intent: IngestJournalIntent): Boolean
    fun copying(intent: IngestJournalIntent, lease: JobLease): Boolean
    fun sealed(intent: IngestJournalIntent, lease: JobLease, sealed: SealedSource): Boolean
    fun moved(intent: IngestJournalIntent, lease: JobLease): Boolean
    fun failed(intent: IngestJournalIntent, lease: JobLease, error: OperationError): Boolean
}
