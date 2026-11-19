package app.pristinepage.data.repository

import app.pristinepage.data.db.FileTransactionRow
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.PngExportItemIntent
import app.pristinepage.data.model.RepositoryFailure
import kotlinx.coroutines.CancellationException

/** Metadata-only journal participant. The retained owner is responsible for the issuing transaction and attempt fences. This does not authorize files or publication. */
internal class RoomPngExportItemWrites private constructor(
    private val reads: RoomPngExportItemReads,
    private val bounds: (String, Long, Int) -> Boolean?,
    private val project: (String, Long) -> ExportItemIntentRow?,
    private val byPrimaryKey: (String) -> FileTransactionRow?,
    private val insert: (String, String, String, String, Int, Long, ByteArray, Long) -> Long,
    private val advance: (String, String, Long, String, String, Long, Long, String, String, ByteArray, String?, String?, Long?) -> Int,
) {
    constructor(database: PristineDatabase) : this(
        RoomPngExportItemReads(database), database.exportSnapshots()::exportItemIntentReadIsBounded,
        database.exportSnapshots()::exportItemIntentRow, database.ingestPreparation()::fileTransaction,
        database.exportSnapshots()::insertExportItemIntent, database.exportSnapshots()::advanceExportItemPhase,
    )

    internal class Plan(val assembly: PngExportItemIntent.Assembly, val item: PngExportItemIntent.Item, val cap: Long) {
        private val intent = PngExportItemIntent(assembly, item)
        val key = PngExportItemIntent.ownerKey(assembly.jobId, item.ordinal)
        val stage = intent.stagingPath
        val final = intent.finalPath
        private val canonicalPayload = intent.journalPayload()
        fun payload(): ByteArray = canonicalPayload.copyOf()
    }

    internal fun insertFreshSet(assembly: PngExportItemIntent.Assembly, plans: List<Plan>, audit: Long, fence: () -> Unit) {
        reads.checkFreshInTransaction(assembly)
        for (plan in plans) {
            fence()
            val item = plan.item
            // Room returns a generated last-insert row ID; only the exact new row under this transaction proves insertion.
            insert(item.exportTransactionId.value, assembly.jobId.value, assembly.snapshotId.value, item.artifactId.value,
                item.ordinal, assembly.attempt, plan.payload(), audit)
            authenticate(plan, "INTENT", audit, null)
        }
    }

    internal fun authenticate(plan: Plan, phase: String, audit: Long, seal: String?) {
        val item = plan.item
        val attempt = plan.assembly.attempt
        val payload = plan.payload()
        val count = item.byteCount.takeIf { seal != null }
        val digest = item.byteSha256.takeIf { seal != null }
        val transaction = item.exportTransactionId.value
        // PHASE-M1: Same-key raw admission comes first, even when the forthcoming exact CAS would not update a malformed match.
        if (bounds(plan.key, attempt, PngExportItemIntent.payloadBytes) != true) invalid(plan)
        val row = project(plan.key, attempt)
        if (row == null || row.transactionId != transaction || row.kind != "EXPORT" || row.ownerId != plan.key ||
            row.attempt != attempt || row.state != phase || row.stagingPath != plan.stage || row.finalPath != plan.final ||
            row.sealSha256 != seal || row.expectedByteSha256 != digest || row.expectedByteCount != count ||
            row.errorCode != null || !row.payload.contentEquals(payload)
        ) invalid(plan)
        // The guarded projection omits audit time. Only now is this exact immutable PK known to name the admitted same row.
        if (byPrimaryKey(transaction)?.updatedAt != audit) invalid(plan)
    }

    internal fun advance(plan: Plan, previous: String, next: String, oldAudit: Long, newAudit: Long, exportSeal: String?, fence: () -> Unit) {
        authenticate(plan, previous, oldAudit, exportSeal.takeIf { previous == "SEALED" })
        fence()
        val item = plan.item
        when (advance(item.exportTransactionId.value, plan.key, plan.assembly.attempt, previous, next, oldAudit,
            newAudit, plan.stage, plan.final, plan.payload(), exportSeal, item.byteSha256.takeIf { exportSeal != null },
            item.byteCount.takeIf { exportSeal != null })) {
            1 -> authenticate(plan, next, newAudit, exportSeal)
            0 -> throw RepositoryFailure(OperationError(OperationErrorCode.REVISION_CONFLICT, false,
                "png_export_item_phase_conflict", plan.assembly.jobId.value))
            else -> invalid(plan)
        }
    }

    internal fun movedCheck(assembly: PngExportItemIntent.Assembly, moves: List<RoomPngExportItemReads.ExpectedMove>): () -> RoomPngExportItemReads.Verdict =
        reads.prepareMovedCheck(assembly, moves)

    internal fun requireCommittedKeys(assembly: PngExportItemIntent.Assembly) = reads.requireKeys(assembly, assembly.pageCount)

    private fun invalid(plan: Plan): Nothing = throw RepositoryFailure(OperationError(
        OperationErrorCode.INTEGRITY_FAILED, false, "png_export_item_phase_invalid", plan.assembly.jobId.value,
    ))

    /** Deliberately not a production port: only the same synthetic participant that issues an Acquired can implement these reads/writes. */
    @SyntheticJournal
    internal abstract class SyntheticStore : RoomFreshExportAssembly.SyntheticStore() {
        abstract fun inventory(snapshotId: String, limit: Int): List<String>
        abstract fun bounds(key: String, attempt: Long, payloadBytes: Int): Boolean?
        abstract fun project(key: String, attempt: Long): ExportItemIntentRow?
        abstract fun byPrimaryKey(transactionId: String): FileTransactionRow?
        abstract fun insert(transaction: String, assembly: String, snapshot: String, artifact: String, ordinal: Int,
            attempt: Long, payload: ByteArray, audit: Long): Long
        abstract fun advance(transaction: String, key: String, attempt: Long, previous: String, next: String, oldAudit: Long,
            newAudit: Long, stage: String, final: String, payload: ByteArray, seal: String?, digest: String?, count: Long?): Int
    }

    companion object {
        @JvmSynthetic
        @SyntheticJournal
        @OptIn(RoomPngExportItemReads.SyntheticJournalPorts::class)
        internal fun forSyntheticTests(store: SyntheticStore): RoomPngExportItemWrites = RoomPngExportItemWrites(
            RoomPngExportItemReads.forSyntheticTests(store, store::inventory, store::bounds, store::project),
            store::bounds, store::project, store::byPrimaryKey, store::insert, store::advance,
        )
    }

    @RequiresOptIn(level = RequiresOptIn.Level.ERROR, message = "Copy without co-commit is only available to the coherent synthetic journal participant.")
    @Retention(AnnotationRetention.BINARY)
    internal annotation class SyntheticJournal
}
