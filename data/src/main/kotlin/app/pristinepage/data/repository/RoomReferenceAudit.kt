package app.pristinepage.data.repository

import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Read-only periodic counter comparison, not repair, owner validation, GC authority or complete recovery. Each bounded SQL page is consistent; concurrent inserts behind its cursor may wait until the next pass. */
class RoomReferenceAudit @JvmOverloads constructor(private val metadata: MetadataAccess, private val queries: ReferenceAuditQueries, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    constructor(database: PristineDatabase) : this(RoomMetadataAccess(database), database.referenceAudit())

    private enum class Kind(val state: String, val messageKey: String = "reference_counter_mismatch") {
        SOURCE("SOURCE_REFERENCE_COUNT"), BLOB("BLOB_SOURCE_COUNT"), MASK("MASK_REFERENCE_COUNT"),
        PAGE_PIN("PAGE_SOURCE_PIN", "required_source_pin_missing"), RECIPE_PINS("RECIPE_SOURCE_PINS", "required_source_pin_missing"), MASK_PIN("MASK_SOURCE_PIN", "required_source_pin_missing")
    }

    suspend fun audit(): RecoveryReport = auditKinds(listOf(Kind.SOURCE, Kind.BLOB, Kind.MASK))

    /** Required source pins for retained pages, each immutable recipe and each mask only. This does not validate the reverse/dangling owner relation or Undo/import/job/export membership, and cannot authorize repair/GC. */
    suspend fun auditRequiredPins(): RecoveryReport = auditKinds(listOf(Kind.PAGE_PIN, Kind.RECIPE_PINS, Kind.MASK_PIN))

    private suspend fun auditKinds(kinds: List<Kind>): RecoveryReport = withContext(dispatcher) {
        var inspected = 0L; var blocked = 0L
        val issues = mutableListOf<RecoveryIssue>()
        for (kind in kinds) {
            var after: String? = null
            while (true) {
                coroutineContext.ensureActive()
                val rows = page(kind, after)
                coroutineContext.ensureActive()
                if (rows.isEmpty()) break
                for (row in rows) {
                    coroutineContext.ensureActive()
                    inspected++; after = row.objectId
                    if (row.storedCount != row.actualCount) {
                        blocked++
                        if (issues.size < 128) issues += RecoveryIssue(row.objectId, kind.state, OperationError(OperationErrorCode.INTEGRITY_FAILED, false, kind.messageKey, row.objectId), RecoveryAction.REVIEW_INTEGRITY)
                    }
                }
            }
        }
        // No row, file or lifecycle was changed, including a negative/zero stale stored count. Never report a repair or infer that a mismatched counter permits reclaiming anything.
        RecoveryReport(inspected, 0, 0, 0, blocked, issues)
    }

    private fun page(kind: Kind, after: String?): List<ReferenceCountRow> {
        try {
            val rows = metadata.transaction {
                when (kind) {
                    Kind.SOURCE -> queries.sourcesAfter(after, 64)
                    Kind.BLOB -> queries.sourceBlobsAfter(after, 64)
                    Kind.MASK -> queries.masksAfter(after, 64)
                    Kind.PAGE_PIN -> queries.pageSourcePinsAfter(after, 64)
                    Kind.RECIPE_PINS -> queries.recipeSourcePinsAfter(after, 64)
                    Kind.MASK_PIN -> queries.maskSourcePinsAfter(after, 64)
                }
            }
            if (rows.size > 64) invalidListing()
            var previous = after
            for (row in rows) {
                try { requireOpaqueId(row.objectId) } catch (_: IllegalArgumentException) { invalidListing() }
                if (previous != null && row.objectId <= previous || row.actualCount < 0) invalidListing()
                previous = row.objectId
            }
            return rows
        } catch (canceled: CancellationException) { throw canceled }
        catch (failure: RepositoryFailure) { throw failure }
        catch (_: Exception) { throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "reference_audit_listing_failed", null)) }
    }

    private fun invalidListing(): Nothing = throw RepositoryFailure(OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "invalid_reference_audit_listing", null))
}
