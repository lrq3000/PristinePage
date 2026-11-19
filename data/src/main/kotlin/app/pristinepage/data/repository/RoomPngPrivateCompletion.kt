package app.pristinepage.data.repository

import app.pristinepage.data.db.ExportSnapshotRow
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.PngExportItemIntent
import app.pristinepage.data.model.RepositoryFailure

/** Same-store metadata leaf; only the retained Acquired traversal opens a transaction or grants file access. */
internal class RoomPngPrivateCompletion private constructor(
    internal val writes: RoomPngExportItemWrites,
    private val commitMoved: (String, String, Long, Long, Long, String, String, ByteArray, String, String, Long) -> Int,
    private val insertReady: (String, String, Int, String, Long, Long) -> Unit,
    private val readyMatches: (String, String, Int, String, Long, Long) -> Boolean,
    private val markPrivateReady: (String, String, Long, String, ByteArray, Long, Long, Long) -> Int,
    private val inventory: (String, Int) -> List<String>,
) {
    constructor(store: PrivateImportStore.ExportComposition) : this(
        RoomPngExportItemWrites(store.database), store.database.exportSnapshots()::commitMovedExportItem, store.database.exportSnapshots()::insertReadyPngExportArtifact,
        store.database.exportSnapshots()::readyPngExportArtifactMatches, store.database.exportSnapshots()::markOrdinaryPngSnapshotPrivateReady,
        store.database.exportSnapshots()::exportArtifactIdsForSnapshot,
    )

    // This data is assembled and consumed in the same retained traversal. Neither it nor the prepared check is returned to an entry caller.
    internal class MovedItem(
        val plan: RoomPngExportItemWrites.Plan, val source: RoomTerminalRenderReads.Metadata,
        val seal: String, val movedAudit: Long,
    )

    internal fun prepare(
        traversal: RoomTerminalPngReads.Retained.AcquiredSetReader,
        assembly: PngExportItemIntent.Assembly,
        items: List<MovedItem>,
    ): Set {
        // Only the live retained bridge can form this set; the metadata adapter is not another MOVED entry.
        traversal.requireCompletion(this)
        val checkMoved = writes.movedCheck(assembly, items.map { RoomPngExportItemReads.ExpectedMove(it.plan.item, it.seal, it.plan.cap) })
        return PreparedSet(assembly, items, checkMoved)
    }

    internal sealed interface Set {
        fun write(header: ExportSnapshotRow, auditClock: () -> Long, fence: () -> Unit): Long
        fun verify(audit: Long, active: () -> Unit)
    }

    private inner class PreparedSet(
        private val assembly: PngExportItemIntent.Assembly,
        private val items: List<MovedItem>,
        private val checkMoved: () -> RoomPngExportItemReads.Verdict,
    ) : Set {
        override fun write(header: ExportSnapshotRow, auditClock: () -> Long, fence: () -> Unit): Long {
            checkMoved()
            for (entry in items) {
                fence()
                entry.source.requireUnchangedInTransaction(fence)
                writes.authenticate(entry.plan, "MOVED", entry.movedAudit, entry.seal)
            }
            val audit = auditClock()
            if (audit < 0) refuse("audit")
            for (entry in items) {
                fence()
                val plan = entry.plan
                val item = plan.item
                insertReady(item.artifactId.value, header.snapshotId, item.ordinal, item.byteSha256, item.byteCount, audit)
                requireOne(commitMoved(item.exportTransactionId.value, plan.key, assembly.attempt, entry.movedAudit, audit, plan.stage,
                    plan.final, plan.payload(), entry.seal, item.byteSha256, item.byteCount), "journal")
            }
            fence()
            requireOne(markPrivateReady(header.snapshotId, header.documentId, header.documentRevision, header.snapshotSha256,
                header.settings, header.createdAt, header.retainedUntil, auditClock()), "header")
            return audit
        }

        override fun verify(audit: Long, active: () -> Unit) {
            val snapshot = assembly.snapshotId.value
            if (inventory(snapshot, assembly.pageCount + 1) != items.map { it.plan.item.artifactId.value }) refuse("inventory")
            writes.requireCommittedKeys(assembly)
            for (entry in items) {
                active()
                val item = entry.plan.item
                writes.authenticate(entry.plan, "COMMITTED", audit, entry.seal)
                if (!readyMatches(item.artifactId.value, snapshot, item.ordinal, item.byteSha256, item.byteCount, audit)) refuse("artifact")
            }
        }

        private fun requireOne(count: Int, step: String) {
            if (count == 0) refuse("${step}_conflict", OperationErrorCode.REVISION_CONFLICT)
            if (count != 1) refuse("${step}_count")
        }

        private fun refuse(reason: String, code: OperationErrorCode = OperationErrorCode.INTEGRITY_FAILED): Nothing =
            throw RepositoryFailure(OperationError(code, false, "png_private_completion_$reason", assembly.jobId.value))
    }

    @RoomPngExportItemWrites.SyntheticJournal
    internal abstract class SyntheticStore : RoomPngExportItemWrites.SyntheticStore() {
        abstract fun commitMoved(transaction: String, key: String, attempt: Long, oldAudit: Long, audit: Long,
            stage: String, final: String, payload: ByteArray, seal: String, hash: String, count: Long): Int
        abstract fun insertReady(artifact: String, snapshot: String, ordinal: Int, hash: String, count: Long, audit: Long)
        abstract fun readyMatches(artifact: String, snapshot: String, ordinal: Int, hash: String, count: Long, audit: Long): Boolean
        abstract fun markPrivateReady(snapshot: String, document: String, revision: Long, hash: String, settings: ByteArray,
            created: Long, retention: Long, now: Long): Int
    }

    companion object {
        @JvmSynthetic
        @RoomPngExportItemWrites.SyntheticJournal
        internal fun forSyntheticTests(store: SyntheticStore) = RoomPngPrivateCompletion(RoomPngExportItemWrites.forSyntheticTests(store),
            store::commitMoved, store::insertReady, store::readyMatches, store::markPrivateReady, store::inventory)
    }
}
