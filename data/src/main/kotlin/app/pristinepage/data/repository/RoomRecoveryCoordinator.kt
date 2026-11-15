package app.pristinepage.data.repository

import app.pristinepage.data.db.FileTransactionRow
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.files.SourceFiles
import app.pristinepage.data.journal.IngestJournalCodec
import app.pristinepage.data.model.*
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Initial ingest-only reconciliation: valid sealed ownership may advance under fresh per-job DB leases; unknown/live owners, unsealed streams and unimplemented restart routes stay blocked. A zero result covers only this pass, not snapshots/refcounts/orphans or whole recovery acceptance. No provider/decoder is opened and no original/staging evidence is deleted. */
class RoomRecoveryCoordinator @JvmOverloads constructor(private val metadata: MetadataAccess, private val queries: IngestRecoveryQueries, private val sessions: ImportSessionQueries, private val journal: IngestPreparationQueries, private val jobs: JobLeaseQueries, private val files: SourceFiles, private val lookup: IngestOperationLookup, private val replay: IngestReplay, private val completion: IngestCompletion, private val phases: IngestPhases, private val readmission: IngestReadmission, private val leases: JobLeaseCommands, private val owners: OperationOwners, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) : RecoveryCoordinator {
    constructor(database: PristineDatabase, files: SourceFiles, owners: OperationOwners) : this(RoomMetadataAccess(database), database.ingestRecovery(), database.importSessions(), database.ingestPreparation(), database.jobLeases(), files, RoomIngestLookup(database), RoomIngestCompletion(database, files), RoomIngestCompletion(database, files), RoomIngestPhases(database), RoomIngestReadmission(database, owners), RoomJobLeases(database, owners), owners)

    private data class Outcome(val result: IngestResult? = null, val resumed: Boolean = false, val completed: Boolean = false, val action: RecoveryAction = RecoveryAction.RETRY, val error: OperationError? = null)

    override suspend fun reconcile(): RecoveryReport = withContext(dispatcher) {
        reconciliation.withLock {
            var inspected = 0L; var completed = 0L; var resumed = 0L; var blocked = 0L
            val issues = mutableListOf<RecoveryIssue>()
            var after = ""
            while (true) {
                coroutineContext.ensureActive()
                val batch = unfinishedAfter(after)
                if (batch.isEmpty()) break
                for (row in batch) {
                    coroutineContext.ensureActive()
                    after = row.transactionId; inspected++
                    val outcome = SourceCopyAdmission.mutex.withLock { recover(row) }
                    if (outcome.resumed) resumed++
                    val result = outcome.result
                    if (outcome.completed) completed++
                    if (result !is IngestResult.Committed) {
                        blocked++
                        // Counts cover the whole paginated pass; diagnostic text is bounded and contains only safe states/codes and opaque IDs, never provider references or parser excerpts.
                        if (issues.size < 128) issues += RecoveryIssue(row.transactionId, safeState(row.state), outcome.error ?: (result as? IngestResult.Rejected)?.error, outcome.action)
                    }
                }
            }
            RecoveryReport(inspected, completed, resumed, 0, blocked, issues)
        }
    }

    /** A failed/incomplete scan is not a successful zero-report. Validate the whole bounded page before executing it, so a malformed/nonadvancing cursor cannot escape as raw DB text or create an infinite pass. Prior independent commits remain durable if a later page cannot be listed. */
    private fun unfinishedAfter(after: String): List<FileTransactionRow> {
        try {
            val batch = metadata.transaction { queries.unfinishedIngestsAfter(after, 16) }
            if (batch.size > 16) invalidListing()
            var previous = after
            for (row in batch) {
                try { requireOpaqueId(row.transactionId) }
                catch (_: IllegalArgumentException) { invalidListing() }
                if (row.transactionId <= previous) invalidListing()
                previous = row.transactionId
            }
            return batch
        } catch (canceled: CancellationException) { throw canceled }
        catch (failure: RepositoryFailure) { throw failure }
        catch (_: Exception) { throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "ingest_recovery_listing_failed", null)) }
    }

    private fun invalidListing(): Nothing = throw RepositoryFailure(OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "invalid_ingest_recovery_listing", null))

    private suspend fun recover(listed: FileTransactionRow): Outcome {
        val session = try { ImportSessionId(listed.ownerId) }
        catch (_: IllegalArgumentException) { return Outcome(error = OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "invalid_ingest_owner", listed.transactionId), action = RecoveryAction.REVIEW_INTEGRITY) }
        var resumed = false
        try {
            val context = kotlinx.coroutines.currentCoroutineContext()
            val intent = lookup.find(session) ?: return blocked(session, "ingest_intent_missing")
            if (intent.transactionId.value != listed.transactionId) return blocked(session, "ingest_attempt_superseded")
            val before = metadata.transaction { journal.fileTransaction(intent.transactionId.value) } ?: return blocked(session, "ingest_intent_missing")
            val recordedResult = replay.replay(intent)
            if (recordedResult !is IngestResult.Pending) return Outcome(recordedResult)
            val job = metadata.transaction { jobs.job(intent.jobId.value) } ?: return blocked(session, "ingest_job_missing")
            val recorded = when (job.state) {
                "RUNNING", "CHECKPOINTED" -> {
                    val owner = job.leaseOwner ?: return blocked(session, "ingest_owner_missing")
                    // Native proof refresh is explicit IO outside Room; readmission below consults only its bounded cached result. Registry-only owners retain the conservative local/UNKNOWN behavior.
                    val priorState = if (owners is OperationOwnerProof) owners.refresh(owner) else owners.state(owner)
                    if (priorState != LeaseOwnerState.ENDED) return Outcome(recordedResult)
                    JobLease(intent.jobId, owner, job.leaseGeneration)
                }
                "ADMITTED" -> {
                    if (job.leaseOwner != null || job.leaseUntil != null) return blocked(session, "ingest_owner_mismatch")
                    null
                }
                "RETRYABLE", "INTERRUPTED" -> {
                    if (job.leaseOwner != null || job.leaseUntil != null || before.state != "RETRYABLE") return Outcome(recordedResult)
                    null // Actual sealed proof below is mandatory before restoring an owner-free retry to ADMITTED.
                }
                else -> return Outcome(recordedResult) // INTENT provider resumption and owner-free retry/new-attempt routes are separate, still-unimplemented recovery work.
            }
            context.ensureActive()
            val sealed = files.recover(intent.transactionId.value, before.sealSha256)
                ?: return Outcome(recordedResult, action = RecoveryAction.RESELECT)
            val seal = IngestJournalCodec.decodeSeal(sealed.metadata, intent)
            if (sealed.staged.sourceBlobId != intent.sourceBlobId.value || sealed.staged.transactionId != intent.transactionId.value || seal.sourceByteHash != sealed.staged.byteSha256 || seal.byteCount != sealed.staged.byteCount || before.state in setOf("SEALED", "MOVED") && (before.sealSha256 == null || before.expectedByteSha256 != seal.sourceByteHash || before.expectedByteCount != seal.byteCount)) return blocked(session, "ingest_source_binding_mismatch")
            if (recorded != null && !readmission.readmitEnded(intent, recorded)) return Outcome(recordedResult)
            if (job.state in setOf("RETRYABLE", "INTERRUPTED") && !phases.readmitSealedRetry(intent, job.leaseGeneration, sealed)) return Outcome(recordedResult)
            return owners.run { owner ->
                when (val acquired = leases.acquireAdmitted(intent.jobId, owner, 300_000L)) {
                    is AcquireLeaseResult.Busy -> Outcome(replay.replay(intent))
                    is AcquireLeaseResult.Rejected -> Outcome(IngestResult.Rejected(session, acquired.error))
                    is AcquireLeaseResult.Granted -> {
                        resumed = true
                        val lease = acquired.lease
                        try {
                            context.ensureActive()
                            var state = phases.state(intent)
                            if (state == FileTransactionState.INTENT) {
                                if (!phases.copying(intent, lease)) return@run Outcome(replay.replay(intent), resumed = true)
                                state = FileTransactionState.COPYING
                            }
                            if (state == FileTransactionState.COPYING) {
                                if (!phases.sealed(intent, lease, sealed)) return@run Outcome(replay.replay(intent), resumed = true)
                                state = FileTransactionState.SEALED
                            }
                            if (state !in setOf(FileTransactionState.SEALED, FileTransactionState.MOVED)) return@run Outcome(replay.replay(intent), resumed = true)
                            context.ensureActive()
                            files.move(sealed)
                            context.ensureActive()
                            if (state == FileTransactionState.SEALED && !phases.moved(intent, lease)) return@run Outcome(replay.replay(intent), resumed = true)
                            val result = completion.commit(intent, lease)
                            Outcome(result, resumed = true, completed = result is IngestResult.Committed)
                        } catch (canceled: CancellationException) { throw canceled }
                    }
                }
            }
        } catch (canceled: CancellationException) { throw canceled }
        catch (failure: RepositoryFailure) { return Outcome(IngestResult.Rejected(session, failure.error), resumed, action = if (failure.error.code == OperationErrorCode.INTEGRITY_FAILED) RecoveryAction.REVIEW_INTEGRITY else RecoveryAction.RETRY) }
        catch (_: IllegalArgumentException) { return blocked(session, "invalid_ingest_recovery_data", resumed) }
        catch (_: IOException) { return Outcome(IngestResult.Rejected(session, OperationError(OperationErrorCode.STORAGE_FAILED, true, "ingest_recovery_io_failed", session.value)), resumed) }
        catch (_: Exception) { return Outcome(IngestResult.Rejected(session, OperationError(OperationErrorCode.STORAGE_FAILED, true, "ingest_recovery_failed", session.value)), resumed) }
    }

    private fun blocked(session: ImportSessionId, key: String, resumed: Boolean = false) = Outcome(IngestResult.Rejected(session, OperationError(OperationErrorCode.INTEGRITY_FAILED, false, key, session.value)), resumed, action = RecoveryAction.REVIEW_INTEGRITY)
    private fun safeState(state: String) = FileTransactionState.entries.firstOrNull { it.name == state }?.name ?: "UNSUPPORTED"
    private companion object { val reconciliation = Mutex() }
}
