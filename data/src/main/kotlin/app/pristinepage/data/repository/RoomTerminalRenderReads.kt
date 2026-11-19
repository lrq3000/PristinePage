package app.pristinepage.data.repository

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.requireSha256
import app.pristinepage.data.db.FileTransactionRow
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.db.RenderedPageRow
import app.pristinepage.data.model.ExportPageSnapshot
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.OrdinaryExportProfile
import app.pristinepage.data.model.OrdinaryRenderExecution
import app.pristinepage.data.model.RenderFileIntent
import app.pristinepage.data.model.RenderId
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.SourceId
import app.pristinepage.data.model.SourceInspection
import app.pristinepage.data.model.TransactionId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Metadata only. Pair the authenticated page with the assembly reader's JobId at that same ordinal; retain the snapshot/job ownership independently. */
internal class RoomTerminalRenderReads(
    private val metadata: MetadataAccess,
    private val runtime: OrdinaryExportProfile,
    private val dispatcher: CoroutineDispatcher,
    private val jobBounds: (String, Int) -> Boolean?,
    private val jobIdentity: (String) -> FrozenRenderJobIdentityRow?,
    private val pinSourceIds: (String) -> List<String>,
    private val sourceBounds: (String) -> Boolean?,
    private val attempt: (String) -> Long?,
    private val renderIds: (String) -> List<String>,
    private val renderBounds: (String, String, String, Long, Int, Int, Int) -> Boolean?,
    private val renderRow: (String) -> RenderedPageRow?,
    private val journalBounds: (String, Long, Int) -> Boolean?,
    private val journalRow: (String, Long) -> FileTransactionRow?,
) {
    constructor(database: PristineDatabase, runtime: OrdinaryExportProfile) : this(
        RoomMetadataAccess(database), runtime, Dispatchers.IO,
        database.exportSnapshots()::renderJobReadIsBounded, database.exportSnapshots()::renderJobIdentity,
        database.exportSnapshots()::boundedRenderJobPinSourceIds, database.catalog()::sourceReadIsBounded,
        database.exportSnapshots()::terminalRenderAttempt, database.exportSnapshots()::terminalRenderIds,
        database.exportSnapshots()::terminalRenderReadIsBounded, database.exportSnapshots()::terminalRenderRow,
        database.exportSnapshots()::renderIntentReadIsBounded, database.exportSnapshots()::renderIntentRow,
    )

    internal fun usesSyntheticStore(store: RoomFreshExportAssembly.SyntheticStore) = metadata === store

    suspend fun load(jobId: JobId, page: ExportPageSnapshot): Metadata = withContext(dispatcher) {
        val active: () -> Unit = { coroutineContext.ensureActive() }
        active()
        val prepared = RoomFrozenRenderReads.Verified.Prepared(jobId, page, runtime)
        active()
        val snapshot = try {
            metadata.transaction { project(jobId, page, prepared.inputBytes, active) }
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (failure: RepositoryFailure) {
            throw failure
        } catch (_: Exception) {
            throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "terminal_render_read_failed", jobId.value))
        }
        active()
        // Hash, parse, and recompose only after releasing Room. The guarded, copied BLOBs refer to the same transaction as the scalars and source identities.
        prepared.authenticateTerminal(snapshot.job, active) { proof ->
            try {
                authenticate(proof, snapshot, active)
            } catch (_: IllegalArgumentException) {
                invalid(jobId)
            }
        }
    }

    private fun project(jobId: JobId, page: ExportPageSnapshot, inputBytes: Int, active: () -> Unit): Snapshot {
        lateinit var identity: FrozenRenderJobIdentityRow
        val projection = RoomFrozenRenderReads.Projection(metadata.queries, jobBounds, { id ->
            jobIdentity(id)?.let {
                if (it.input.size != inputBytes) invalid(jobId)
                it.copy(input = it.input.copyOf()).also { owned -> identity = owned }
            }
        }, pinSourceIds, sourceBounds)
        val job = projection.job(jobId, inputBytes, active)
        val rows = terminalRows(jobId, page, active)
        val producer = projection.source(rows.render.sourceId, jobId, active)
        return Snapshot(job, identity, rows.copy(render = rows.render.copy(execution = rows.render.execution.copyOf()),
            journal = rows.journal.copy(payload = rows.journal.payload.copyOf())), producer)
    }

    private fun terminalRows(jobId: JobId, page: ExportPageSnapshot, active: () -> Unit, expectedId: String? = null): TerminalRows {
        val id = jobId.value
        val terminalAttempt = attempt(id)?.takeIf { it > 0 } ?: invalid(jobId)
        val renderId = renderIds(id).singleOrNull() ?: invalid(jobId)
        // The final transaction compares against a previously parsed canonical ID, not an ID supplied by the current row.
        if (expectedId != null) {
            if (renderId != expectedId) invalid(jobId)
        } else {
            try { RenderId(renderId) } catch (_: IllegalArgumentException) { invalid(jobId) }
        }
        val size = page.output.size
        val maxBytes = OrdinaryRenderExecution.maxEnvelopeBytes
        if (renderBounds(renderId, id, page.pageId.value, page.recipeRevision, size.width, size.height, maxBytes) != true) invalid(jobId)
        active()
        val row = renderRow(renderId) ?: invalid(jobId)
        if (row.renderId != renderId || row.execution.size !in 1..maxBytes) invalid(jobId)
        if (journalBounds(id, terminalAttempt, RenderFileIntent.payloadBytes) != true) invalid(jobId)
        active()
        val journal = journalRow(id, terminalAttempt) ?: invalid(jobId)
        if (journal.payload.size != RenderFileIntent.payloadBytes) invalid(jobId)
        return TerminalRows(terminalAttempt, row, journal)
    }

    private fun authenticate(proof: RoomFrozenRenderReads.Verified.TerminalProof, snapshot: Snapshot, active: () -> Unit): Metadata {
        val page = proof.page
        val (source, blob) = snapshot.producer
        val (attempt, row, journal) = snapshot.rows
        val size = PixelSize(row.width, row.height)
        require(row.jobId == proof.jobId.value && row.pageId == page.pageId.value && row.recipeRevision == page.recipeRevision)
        require(row.state == "READY" && row.renderStatus == "OK" && size == page.output.size && row.byteCount > 0)
        requireSha256(row.byteSha256)
        require(journal.kind == "RENDER" && journal.ownerId == row.jobId && journal.attempt == attempt)
        require(journal.state == "COMMITTED" && journal.errorCode == null)
        require(journal.expectedByteCount == row.byteCount && journal.expectedByteSha256 == row.byteSha256)
        val sealHash = requireNotNull(journal.sealSha256).also(::requireSha256)

        val execution = proof.readExecution(row, size)
        val producer = execution.unprovenProducer
        require(producer.sourceId.value == row.sourceId && producer.sourceId.value == source.sourceId)
        require(producer.blobId.value == blob.sourceBlobId && producer.byteSha256 == blob.byteSha256)
        TransactionId(blob.ingestTransactionId)
        require(source.state == "READY" && blob.state == "READY" && source.refCount >= 0 && blob.sourceCount > 0)
        require(blob.byteCount in 1..104857600L && blob.relativePath == "sources/${blob.sourceBlobId}/original.bin")
        val inspection = SourceInspection(blob.mime, PixelSize(blob.encodedWidth, blob.encodedHeight), PixelSize(source.width, source.height),
            source.exifOrientation, source.profileSha256, source.conversionFingerprint, source.pixelSha256)
        require(inspection.normalizedSize == page.sourceSize && inspection.sourcePixelHash == page.pixelHash &&
            inspection.conversionFingerprint == page.conversionFingerprint && inspection.encodedSize == execution.stages.first().inputSize)

        // Success cleared the Job lease. Only the exact historical owner field is taken from the payload; every other field is independently known.
        val intent = RenderFileIntent(TransactionId(journal.transactionId), RenderId(row.renderId), proof.jobId,
            RenderFileIntent.historicalOwner(journal.payload), attempt, attempt, proof.canonicalInputSha256,
            page.pageId, producer.sourceId, producer.blobId, SourceId(proof.pinSourceId), producer.byteSha256,
            page.pixelHash, page.recipeRevision, page.recipeHash, page.output)
        require(journal.stagingPath == intent.stagingPath && journal.finalPath == intent.finalPath && row.relativePath == intent.finalPath)
        require(journal.payload.contentEquals(intent.journalPayload()))
        return Metadata(intent, sealHash, row.byteSha256, row.byteCount, execution.rasterSha256) { owningContext ->
            requireUnchanged(proof.jobId, page, snapshot, owningContext)
        }.also { active() }
    }

    /** On the original issuer's final transaction: raw admission first, then exact bounded projections. No file or execution decoder is involved. */
    private fun requireUnchanged(jobId: JobId, page: ExportPageSnapshot, expected: Snapshot, active: () -> Unit) {
        active()
        val identity = expected.identity
        if (jobBounds(jobId.value, identity.input.size) != true) invalid(jobId)
        val live = jobIdentity(jobId.value) ?: invalid(jobId)
        if (!live.input.contentEquals(identity.input) || live.copy(input = identity.input) != identity ||
            pinSourceIds(jobId.value).singleOrNull() != expected.job.pinId) invalid(jobId)
        requireSource(jobId, expected.job.binding, pinned = true)
        val (attempt, render, journal) = terminalRows(jobId, page, active, expected.rows.render.renderId)
        val before = expected.rows
        if (attempt != before.attempt || !render.execution.contentEquals(before.render.execution) ||
            render.copy(execution = before.render.execution) != before.render ||
            !journal.payload.contentEquals(before.journal.payload) || journal.copy(payload = before.journal.payload) != before.journal) invalid(jobId)
        if (expected.producer != expected.job.binding) requireSource(jobId, expected.producer, pinned = false)
    }

    private fun requireSource(job: JobId, before: ExportSourceBinding, pinned: Boolean) {
        val (source, blob) = before
        if (sourceBounds(source.sourceId) != true) invalid(job)
        val current = metadata.queries.source(source.sourceId) ?: invalid(job)
        if (current.refCount < (if (pinned) 1 else 0) || current.copy(refCount = source.refCount) != source) invalid(job)
        val currentBlob = metadata.queries.sourceBlob(blob.sourceBlobId) ?: invalid(job)
        // Other retained owners can legitimately change these counters without altering the immutable source identity.
        if (currentBlob.sourceCount <= 0 || currentBlob.copy(sourceCount = blob.sourceCount) != blob) invalid(job)
    }

    private class Snapshot(
        val job: RoomFrozenRenderReads.Projection.JobSnapshot,
        val identity: FrozenRenderJobIdentityRow,
        val rows: TerminalRows,
        val producer: ExportSourceBinding,
    )

    private data class TerminalRows(
        val attempt: Long,
        val render: RenderedPageRow,
        val journal: FileTransactionRow,
    )

    /** Expected values for a later stable sealed read, not path authority, a lease, verified bytes, or PRIVATE_READY. */
    class Metadata internal constructor(
        private val intent: RenderFileIntent,
        val sealSha256: String,
        val byteSha256: String,
        val byteCount: Long,
        val rasterSha256: String,
        private val recheck: (() -> Unit) -> Unit,
    ) {
        val transactionId: TransactionId get() = intent.transactionId
        val renderId: RenderId get() = intent.renderId
        val size: PixelSize get() = intent.output.size
        fun intentPayload(): ByteArray = intent.journalPayload()
        internal fun requireUnchangedInTransaction(active: () -> Unit) = recheck(active)
    }

    private fun invalid(jobId: JobId): Nothing =
        throw RepositoryFailure(OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "terminal_render_metadata_invalid", jobId.value))
}
