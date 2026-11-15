package app.pristinepage.data.repository

import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Composes implemented ingest, counter/required/direct-source-pin audits and Undo availability expiry only. A clean pass is not all-owner/schema/file/export integrity or reclaim authority. No Room transaction spans suspension/file/provider work; ordinary commands may change metadata between component snapshots. */
class RoomStoreRecovery @JvmOverloads constructor(private val metadata: MetadataAccess, private val ingest: RecoveryCoordinator, private val references: RoomReferenceAudit, private val undo: RoomUndoRetention, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) : RecoveryCoordinator {
    constructor(database: PristineDatabase, ingest: RecoveryCoordinator) : this(RoomMetadataAccess(database), ingest, RoomReferenceAudit(database), RoomUndoRetention(database))

    override suspend fun reconcile(): RecoveryReport = withContext(dispatcher) {
        reconciliation.withLock {
            try {
                var result = RecoveryReport(0, 0, 0, 0, 0, emptyList())
                coroutineContext.ensureActive()
                result = merge(result, ingest.reconcile())
                coroutineContext.ensureActive()
                result = merge(result, references.audit())
                coroutineContext.ensureActive()
                result = merge(result, references.auditRequiredPins())
                coroutineContext.ensureActive()
                result = merge(result, references.auditDirectOwners())
                var after: String? = null
                while (true) {
                    coroutineContext.ensureActive()
                    val ids = documentsAfter(after)
                    coroutineContext.ensureActive()
                    if (ids.isEmpty()) break
                    for (id in ids) {
                        coroutineContext.ensureActive()
                        result = merge(result, undo.expireDocument(DocumentId(id)))
                        after = id
                    }
                }
                coroutineContext.ensureActive()
                result
            } catch (canceled: CancellationException) { throw canceled }
            catch (failure: RepositoryFailure) { throw failure }
            catch (_: Exception) { throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "store_recovery_failed", null)) }
        }
    }

    /** Validate the entire page before any member's expiry write. Inserts behind this exclusive cursor wait until a later pass; a later listing failure never undoes earlier committed document transactions. */
    private fun documentsAfter(after: String?): List<String> {
        try {
            val ids = metadata.transaction { metadata.queries.retainedDocumentIdsAfter(after, 64) }
            if (ids.size > 64) invalidListing()
            var previous = after
            for (id in ids) {
                try { requireOpaqueId(id) } catch (_: IllegalArgumentException) { invalidListing() }
                if (previous != null && id <= previous) invalidListing()
                previous = id
            }
            return ids
        } catch (canceled: CancellationException) { throw canceled }
        catch (failure: RepositoryFailure) { throw failure }
        catch (_: Exception) { throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "store_recovery_listing_failed", null)) }
    }

    private fun merge(total: RecoveryReport, next: RecoveryReport): RecoveryReport {
        if (next.inspected < 0 || listOf(next.completed, next.resumed, next.quarantined, next.blocked).any { it < 0 || it > next.inspected } || next.safeIssues.size > 128 || next.safeIssues.size.toLong() > next.blocked) invalidReport()
        try {
            // Observations may overlap across components. Bound retained diagnostics, not the actual action/blocked totals; checked sums must never wrap into a false-clean report.
            return RecoveryReport(Math.addExact(total.inspected, next.inspected), Math.addExact(total.completed, next.completed), Math.addExact(total.resumed, next.resumed), Math.addExact(total.quarantined, next.quarantined), Math.addExact(total.blocked, next.blocked), (total.safeIssues + next.safeIssues).take(128))
        } catch (_: ArithmeticException) { invalidReport() }
    }

    private fun invalidListing(): Nothing = throw RepositoryFailure(OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "invalid_store_recovery_listing", null))
    private fun invalidReport(): Nothing = throw RepositoryFailure(OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "invalid_store_recovery_report", null))
    private companion object { val reconciliation = Mutex() }
}
