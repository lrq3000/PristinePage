package app.pristinepage.data.repository

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.data.db.JobRow
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.db.RenderedPageRow
import app.pristinepage.data.model.ExportPageSnapshot
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.OrdinaryExportProfile
import app.pristinepage.data.model.OrdinaryRenderExecutionRead
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.requireOpaqueId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Verifies one ordinary authenticated frozen page and its JobId at the matching verified assembly ordinal. The caller retains the snapshot/job ownership and separately proves lease, runtime and file availability; no rows are returned. */
internal class RoomFrozenRenderReads(
    private val metadata: MetadataAccess,
    jobBounds: (String, Int) -> Boolean?,
    jobIdentity: (String) -> FrozenRenderJobIdentityRow?,
    pinSourceIds: (String) -> List<String>,
    sourceBounds: (String) -> Boolean?,
    private val runtime: OrdinaryExportProfile,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(database: PristineDatabase, runtime: OrdinaryExportProfile) : this(
        RoomMetadataAccess(database),
        database.exportSnapshots()::renderJobReadIsBounded,
        database.exportSnapshots()::renderJobIdentity,
        database.exportSnapshots()::boundedRenderJobPinSourceIds,
        database.catalog()::sourceReadIsBounded,
        runtime,
    )

    private val projection = Projection(metadata.queries, jobBounds, jobIdentity, pinSourceIds, sourceBounds)

    suspend fun verify(jobId: JobId, page: ExportPageSnapshot) {
        authenticate(jobId, page)
    }

    suspend fun verified(jobId: JobId, page: ExportPageSnapshot): Verified = authenticate(jobId, page)

    /** This is an immutable binding, not a lease or a live pin. Keep the canonical bytes private and compare them again before writing. */
    class Verified private constructor(
        val jobId: JobId,
        val page: ExportPageSnapshot,
        val pinSourceId: String,
        identity: FrozenRenderJobIdentityRow,
        private val pinnedBinding: ExportSourceBinding,
    ) {
        private val input = identity.input
        internal val preExecutionKey = identity.idempotenceKey
        val canonicalInputSha256: String = identity.canonicalInputSha256
        val inputBytes: Int get() = input.size

        fun matches(row: JobRow): Boolean = row.snapshotId == null && row.pageOrdinal == null &&
            matchesIdentity(row.jobId, row.kind, row.idempotenceKey, row.canonicalInputSha256, row.input)

        private fun matchesIdentity(id: String, kind: String, key: String, digest: String, bytes: ByteArray): Boolean =
            id == jobId.value && kind == "PAGE_RENDER" && key == preExecutionKey && digest == canonicalInputSha256 && bytes.contentEquals(input)

        internal fun requireUnchangedForLease(row: FrozenRenderJobIdentityRow, pinId: String, binding: ExportSourceBinding) {
            if (!matchesIdentity(row.jobId, row.kind, row.idempotenceKey, row.canonicalInputSha256, row.input)) {
                refuse(jobId, OperationErrorCode.INTEGRITY_FAILED, "frozen_render_job_invalid")
            }
            try {
                FrozenRenderJobProof.verifyPin(page, pinId, binding)
            } catch (_: IllegalArgumentException) {
                refuse(jobId, OperationErrorCode.INTEGRITY_FAILED, "frozen_render_job_invalid")
            }
            if (pinId != pinSourceId || !binding.sameImmutableBindingAs(pinnedBinding)) {
                refuse(jobId, OperationErrorCode.INTEGRITY_FAILED, "frozen_render_lease_binding_changed")
            }
        }

        /** Keep the exact page and JobId that produced Expected together through final authentication. Construct outside Room. */
        class Prepared(private val jobId: JobId, private val page: ExportPageSnapshot, runtime: OrdinaryExportProfile) {
            private val expected = FrozenRenderJobProof.prepare(jobId, page, runtime)
            val inputBytes: Int get() = expected.inputBytes

            fun authenticate(snapshot: Projection.JobSnapshot, active: () -> Unit): Verified {
                val row = snapshot.authenticate(expected)
                active()
                requireAvailable()
                return Verified(jobId, page, snapshot.pinId, row, snapshot.binding)
            }

            /** Only for the writer transaction, after the detached initial binding has authenticated and preflight has completed. */
            internal fun admitUnchanged(
                initial: Projection.JobSnapshot,
                live: Projection.JobSnapshot,
                updatedAt: Long,
                active: () -> Unit,
                write: (String, String, String, ByteArray, Long) -> Int,
            ): Int = live.admitUnchanged(expected, initial, updatedAt, active, write)

            fun <T> authenticateTerminal(snapshot: Projection.JobSnapshot, active: () -> Unit, validate: (TerminalProof) -> T): T {
                val row = snapshot.authenticate(expected)
                active()
                // Retained execution is readable as metadata, but an unchecked historical Verified must not reach execution callers.
                val terminal = validate(TerminalProof(Verified(jobId, page, snapshot.pinId, row, snapshot.binding)))
                active()
                requireAvailable()
                return terminal
            }

            private fun requireAvailable() {
                if (expected.historical) refuse(jobId, OperationErrorCode.PROVIDER_UNAVAILABLE, "frozen_render_profile_unavailable")
            }
        }

        /** Only the fields and pure parser needed by the terminal validator; never a runnable frozen-job proof. */
        class TerminalProof internal constructor(private val proof: Verified) {
            val jobId: JobId get() = proof.jobId
            val page: ExportPageSnapshot get() = proof.page
            val pinSourceId: String get() = proof.pinSourceId
            val canonicalInputSha256: String get() = proof.canonicalInputSha256

            fun readExecution(row: RenderedPageRow, size: PixelSize): OrdinaryRenderExecutionRead =
                OrdinaryRenderExecutionRead.read(row.execution, proof, row.executionSha256, row.cacheKey, row.rasterPixelSha256, size)
        }
    }

    private suspend fun authenticate(jobId: JobId, page: ExportPageSnapshot): Verified = withContext(dispatcher) {
        coroutineContext.ensureActive()
        val prepared = Verified.Prepared(jobId, page, runtime)
        coroutineContext.ensureActive()
        val snapshot = try {
            metadata.transaction { projection.job(jobId, prepared.inputBytes) { coroutineContext.ensureActive() } }
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (failure: RepositoryFailure) {
            throw failure
        } catch (_: Exception) {
            refuse(jobId, OperationErrorCode.STORAGE_FAILED, "frozen_render_job_read_failed", retryable = true)
        }
        coroutineContext.ensureActive()
        prepared.authenticate(snapshot) { coroutineContext.ensureActive() }
    }

    /** Only bounded projections; no transaction opener, file or lease. Use these queries inside the caller's one same-database transaction. */
    class Projection(
        private val catalog: CatalogQueries,
        private val jobBounds: (String, Int) -> Boolean?,
        private val jobIdentity: (String) -> FrozenRenderJobIdentityRow?,
        private val pinSourceIds: (String) -> List<String>,
        private val sourceBounds: (String) -> Boolean?,
    ) {
        fun job(jobId: JobId, inputBytes: Int, active: () -> Unit): JobSnapshot = JobSnapshot.capture(this, jobId, inputBytes, active)

        class JobSnapshot private constructor(
            private val row: FrozenRenderJobIdentityRow, val pinId: String, internal val binding: ExportSourceBinding,
            private val jobId: JobId, private val inputBytes: Int,
        ) {
            /** Opt in before transaction exit when composing with other borrowed projections. The legacy standalone reader authenticates a copy only after Room returns. */
            fun detached(): JobSnapshot = JobSnapshot(copyBoundedInput(), pinId, binding, jobId, inputBytes)

            fun authenticate(expected: FrozenRenderJobProof.Expected): FrozenRenderJobIdentityRow {
                val owned = copyBoundedInput()
                expected.verify(owned, pinId, binding)
                return owned
            }

            internal fun admitUnchanged(
                expected: FrozenRenderJobProof.Expected,
                initial: JobSnapshot,
                updatedAt: Long,
                active: () -> Unit,
                write: (String, String, String, ByteArray, Long) -> Int,
            ): Int {
                // The prepared expectation owns the canonical bytes. The live borrowed row is checked and consumed in this transaction only.
                val owned = copyBoundedInput()
                expected.verify(owned, pinId, binding)
                if (pinId != initial.pinId || !binding.sameImmutableBindingAs(initial.binding)) {
                    refuse(jobId, OperationErrorCode.INTEGRITY_FAILED, "frozen_render_admission_binding_changed")
                }
                active()
                return write(owned.jobId, owned.idempotenceKey, owned.canonicalInputSha256, owned.input, updatedAt)
            }

            internal fun requireUnchangedForLease(proof: Verified) {
                if (row.input.size != inputBytes) invalidMetadata(jobId)
                proof.requireUnchangedForLease(row, pinId, binding)
            }

            private fun copyBoundedInput(): FrozenRenderJobIdentityRow {
                if (row.input.size != inputBytes) invalidMetadata(jobId)
                return row.copy(input = row.input.copyOf())
            }

            companion object {
                fun capture(projection: Projection, jobId: JobId, inputBytes: Int, active: () -> Unit): JobSnapshot {
                    when (projection.jobBounds(jobId.value, inputBytes)) {
                        null -> refuse(jobId, OperationErrorCode.SOURCE_UNAVAILABLE, "frozen_render_job_unavailable")
                        false -> invalidMetadata(jobId)
                        true -> Unit
                    }
                    active()
                    val row = projection.jobIdentity(jobId.value) ?: invalidMetadata(jobId)
                    val pin = projection.pinSourceIds(jobId.value).singleOrNull() ?: invalidMetadata(jobId)
                    return JobSnapshot(row, pin, projection.source(pin, jobId, active), jobId, inputBytes)
                }
            }
        }

        fun source(id: String, jobId: JobId, active: () -> Unit): ExportSourceBinding {
            validateId(id, jobId)
            // A missing Source behind a retained reference is corruption. This scalar also guards its exact Blob before either projection.
            if (sourceBounds(id) != true) invalidMetadata(jobId)
            active()
            val source = catalog.source(id) ?: invalidMetadata(jobId)
            if (source.sourceId != id) invalidMetadata(jobId)
            validateId(source.sourceBlobId, jobId)
            val blob = catalog.sourceBlob(source.sourceBlobId) ?: invalidMetadata(jobId)
            if (blob.sourceBlobId != source.sourceBlobId) invalidMetadata(jobId)
            return ExportSourceBinding(source, blob)
        }

        private fun validateId(id: String, jobId: JobId) {
            try {
                requireOpaqueId(id)
            } catch (_: IllegalArgumentException) {
                invalidMetadata(jobId)
            }
        }
    }

    private companion object {
        // Each view separately proves the counters are positive; other legitimate owners may change the counts.
        fun ExportSourceBinding.sameImmutableBindingAs(other: ExportSourceBinding): Boolean =
            source.copy(refCount = other.source.refCount) == other.source &&
                blob.copy(sourceCount = other.blob.sourceCount) == other.blob

        fun invalidMetadata(jobId: JobId): Nothing =
            refuse(jobId, OperationErrorCode.INTEGRITY_FAILED, "frozen_render_job_metadata_invalid")

        fun refuse(jobId: JobId, code: OperationErrorCode, key: String, retryable: Boolean = false): Nothing =
            throw RepositoryFailure(OperationError(code, retryable, key, jobId.value))
    }
}
