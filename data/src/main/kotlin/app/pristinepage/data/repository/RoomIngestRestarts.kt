package app.pristinepage.data.repository

import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.files.SourceFiles
import app.pristinepage.data.model.*
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Caller holds the shared source-copy permit, not a Room transaction. A null result leaves the existing pending operation for blocked/reselect or valid-seal recovery, without reopening its provider or deleting evidence. */
interface IngestRestarts {
    suspend fun restartUnsealed(intent: IngestJournalIntent): IngestJournalIntent?
}

/** Verifies the native ownership/file precondition outside Room before invoking the short metadata successor transaction. It never copies/decodes a source and never abandons a valid seal or unexplained final file. */
class RoomIngestRestarts @JvmOverloads constructor(private val metadata: MetadataAccess, private val journal: IngestPreparationQueries, private val jobs: JobLeaseQueries, private val files: SourceFiles, private val owners: OperationOwners, private val attempts: IngestAttempts, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) : IngestRestarts {
    constructor(database: PristineDatabase, files: SourceFiles, owners: OperationOwners) : this(RoomMetadataAccess(database), database.ingestPreparation(), database.jobLeases(), files, owners, RoomIngestAttempts(database, owners))

    override suspend fun restartUnsealed(intent: IngestJournalIntent): IngestJournalIntent? = withContext(dispatcher) {
        try {
            coroutineContext.ensureActive()
            val (row, job) = metadata.transaction {
                (journal.fileTransaction(intent.transactionId.value) ?: reject(intent, "ingest_intent_missing")) to (jobs.job(intent.jobId.value) ?: reject(intent, "ingest_job_missing"))
            }
            if (row.state !in setOf("COPYING", "RETRYABLE") || row.sealSha256 != null || row.expectedByteSha256 != null || row.expectedByteCount != null) return@withContext null
            val owner = job.leaseOwner
            if (owner != null) {
                if (job.state !in setOf("RUNNING", "CHECKPOINTED")) return@withContext null
                val state = if (owners is OperationOwnerProof) owners.refresh(owner) else owners.state(owner)
                if (state != LeaseOwnerState.ENDED) return@withContext null
            } else if (job.state !in setOf("RETRYABLE", "INTERRUPTED") || job.leaseUntil != null) return@withContext null
            // A seal can lead its journal and must be recovered rather than discarded as another copy. A final file without its required seal is preserved as an integrity problem, never treated as permission to replace it.
            if (files.recover(intent.transactionId.value, null) != null) return@withContext null
            if (files.finalFile(intent.sourceBlobId.value).exists()) reject(intent, "ingest_unsealed_final_present")
            coroutineContext.ensureActive()
            attempts.restartUnsealed(intent, job.leaseGeneration, owner)
        } catch (canceled: CancellationException) { throw canceled }
        catch (failure: RepositoryFailure) { throw failure }
        catch (_: IllegalArgumentException) { reject(intent, "invalid_ingest_retry_evidence") }
        catch (_: IOException) { throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "ingest_retry_io_failed", intent.sessionId.value)) }
    }

    private fun reject(intent: IngestJournalIntent, key: String): Nothing = throw RepositoryFailure(OperationError(OperationErrorCode.INTEGRITY_FAILED, false, key, intent.sessionId.value))
}
