package app.pristinepage.data.repository

import app.pristinepage.core.pipeline.requireSha256
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.PngExportItemIntent
import app.pristinepage.data.model.RepositoryFailure
import kotlinx.coroutines.CancellationException

/** Synchronous metadata leaf. The owning caller supplies authenticated frozen order, the exact attempt and expected moves, retains any live authority, and chooses the IO context. */
internal class RoomPngExportItemReads private constructor(
    private val metadata: MetadataAccess,
    private val inventory: (String, Int) -> List<String>,
    private val bounds: (String, Long, Int) -> Boolean?,
    private val project: (String, Long) -> ExportItemIntentRow?,
) {
    constructor(database: PristineDatabase) : this(
        RoomMetadataAccess(database),
        database.exportSnapshots()::exportArtifactIdsForSnapshot,
        database.exportSnapshots()::exportItemIntentReadIsBounded,
        database.exportSnapshots()::exportItemIntentRow,
    )

    /** Expected EXPORT seal observed for this specific planned move, never obtained from the journal or inferred from the RENDER seal. */
    data class ExpectedMove(val intent: PngExportItemIntent.Item, val exportSealSha256: String, val maxBytes: Long)

    enum class Verdict { FRESH, MOVED }

    fun checkFresh(assembly: PngExportItemIntent.Assembly): Verdict {
        validatePlan(assembly) { assembly.validate() }
        return inspect(assembly, null)
    }

    fun checkMoved(assembly: PngExportItemIntent.Assembly, expected: List<ExpectedMove>): Verdict {
        val prepared = validatePlan(assembly) {
            assembly.validate()
            require(expected.size == assembly.pageCount)
            // Detach and prepare only the known bounded count, outside Room. Verify v1 job order against the caller's exact assembly input digest.
            val moves = List(assembly.pageCount) { expected[it] }
            require(moves.withIndex().all { (i, move) -> move.intent.ordinal == i })
            val items = moves.map { it.intent }
            val assemblyInput = ExportAssemblyInput.encode(assembly.snapshotId, assembly.snapshotSha256, items.map { it.renderJobId })
            require(ExportAssemblyInput.digest(assemblyInput) == assembly.canonicalInputSha256)
            require(items.map { it.artifactId }.toSet().size == items.size)
            require(items.flatMap { listOf(it.renderTransactionId, it.exportTransactionId) }.toSet().size == 2 * items.size)
            require(items.map { it.renderId }.toSet().size == items.size)
            moves.map { move ->
                val intent = PngExportItemIntent(assembly, move.intent)
                requireSha256(move.exportSealSha256)
                require(move.maxBytes > 0)
                if (move.intent.byteCount > move.maxBytes) refuse(assembly, "byte_cap", OperationErrorCode.RESOURCE_LIMIT)
                PreparedMove(move, intent.stagingPath, intent.finalPath, intent.journalPayload())
            }
        }
        return inspect(assembly, prepared)
    }

    private fun inspect(assembly: PngExportItemIntent.Assembly, expected: List<PreparedMove>?): Verdict {
        val keys = List(PngExportItemIntent.MAX_ITEMS) { PngExportItemIntent.ownerKey(assembly.jobId, it) }
        return try {
            metadata.transaction {
                // The inventory is only a bounded occupancy check; the invalid-ID sentinel still counts as present.
                if (inventory(assembly.snapshotId.value, assembly.pageCount + 1).isNotEmpty()) refuse(assembly, "artifacts_present")
                // Finish scalar admission across the entire relevant key space before loading even the first typed row. False is a match, including on extra keys.
                for ((ordinal, key) in keys.withIndex()) {
                    val flag = bounds(key, assembly.attempt, PngExportItemIntent.payloadBytes)
                    when {
                        expected == null && flag == false -> refuse(assembly, "unbounded")
                        expected == null && flag == true -> refuse(assembly, "occupied")
                        expected != null && ordinal >= expected.size && flag != null -> refuse(assembly, "extra")
                        expected != null && ordinal < expected.size && flag == null -> refuse(assembly, "missing")
                        expected != null && ordinal < expected.size && flag == false -> refuse(assembly, "unbounded")
                    }
                }
                if (expected == null) Verdict.FRESH else {
                    for ((ordinal, move) in expected.withIndex()) {
                        val key = keys[ordinal]
                        val row = project(key, assembly.attempt)
                        if (row == null || !move.matches(row, key, assembly.attempt)) refuse(assembly, "mismatch")
                    }
                    Verdict.MOVED
                }
            }
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (failure: RepositoryFailure) {
            throw failure
        } catch (_: Exception) {
            refuse(assembly, "read_failed", OperationErrorCode.STORAGE_FAILED)
        }
    }

    private fun <T> validatePlan(assembly: PngExportItemIntent.Assembly, action: () -> T): T = try {
        action()
    } catch (_: IllegalArgumentException) {
        refuse(assembly, "invalid_plan", OperationErrorCode.INVALID_INPUT)
    }

    private fun refuse(
        assembly: PngExportItemIntent.Assembly, reason: String, code: OperationErrorCode = OperationErrorCode.INTEGRITY_FAILED,
    ): Nothing = throw RepositoryFailure(OperationError(
        code, code == OperationErrorCode.STORAGE_FAILED, "png_export_item_$reason", assembly.jobId.value,
    ))

    private class PreparedMove(val expected: ExpectedMove, val stagingPath: String, val finalPath: String, val payload: ByteArray) {
        fun matches(row: ExportItemIntentRow, key: String, attempt: Long): Boolean =
            row.kind == "EXPORT" && row.ownerId == key && row.attempt == attempt &&
                row.transactionId == expected.intent.exportTransactionId.value &&
                row.state == "MOVED" && row.errorCode == null &&
                row.stagingPath == stagingPath && row.finalPath == finalPath &&
                row.sealSha256 == expected.exportSealSha256 &&
                row.expectedByteSha256 == expected.intent.byteSha256 && row.expectedByteCount == expected.intent.byteCount &&
                row.payload.contentEquals(payload)
    }

    companion object {
        /** Host fixtures only; ordinary callers derive the transaction and all reads from one database. */
        @JvmSynthetic
        @SyntheticJournalPorts
        internal fun forSyntheticTests(
            metadata: MetadataAccess,
            inventory: (String, Int) -> List<String>,
            bounds: (String, Long, Int) -> Boolean?,
            project: (String, Long) -> ExportItemIntentRow?,
        ): RoomPngExportItemReads = RoomPngExportItemReads(metadata, inventory, bounds, project)
    }

    @RequiresOptIn(message = "Independent journal read ports are for synthetic tests only.")
    @Retention(AnnotationRetention.BINARY)
    internal annotation class SyntheticJournalPorts
}

/** Type admission comes from the exact same-key scalar query in the same transaction, not from this projection. */
internal data class ExportItemIntentRow(
    val transactionId: String,
    val kind: String,
    val ownerId: String,
    val attempt: Long,
    val state: String,
    val stagingPath: String?,
    val finalPath: String?,
    val sealSha256: String?,
    val expectedByteSha256: String?,
    val expectedByteCount: Long?,
    val payload: ByteArray,
    val errorCode: String?,
)
