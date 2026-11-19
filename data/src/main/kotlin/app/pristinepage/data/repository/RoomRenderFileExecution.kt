package app.pristinepage.data.repository

import app.pristinepage.core.pipeline.requireSha256
import app.pristinepage.data.files.DerivedByteLimitExceeded
import app.pristinepage.data.files.DerivedFileTarget
import app.pristinepage.data.files.DerivedFiles
import app.pristinepage.data.files.DerivedKind
import app.pristinepage.data.model.*
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** Joins one exact committed intent to private bytes under the stable store's ordinary derived-file lane. The caller still retains the verified frozen owners and current acquired same-boot lease; neither the lane nor MOVED is recovery/takeover authority. */
internal class RoomRenderFileExecution private constructor(
    private val intents: RoomRenderIntents,
    private val files: DerivedFiles,
    private val owners: FileOperationOwners,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val lifetime: OrdinaryExportLifetime? = null,
    private val completionRows: RenderCompletionQueries?,
) {
    constructor(store: PrivateImportStore.ExportComposition) :
        this(RoomRenderIntents(store.database, store.owners), store.files, store.owners, lifetime = store.lifetime,
            completionRows = store.database.exportSnapshots().renderCompletionQueries())

    init {
        require(owners.ownsCanonicalRoot(files.canonicalRoot))
    }

    /**
     * Call outside Room. The writer borrows only the synchronous output stream. Trusted readback borrows the closed File read-only, without retaining it, verifies complete lossless content against the exact frozen request and returns its canonical raster SHA-256 (null rejects). It may use the supplied local checkpoint between decode steps; it must supply its own decode/allocation admission. Recovery never calls the writer, and uses the same readback contract to match the recorded semantic identity.
     */
    suspend fun execute(
        proof: RoomFrozenRenderReads.Verified,
        lease: JobLease,
        intent: RenderFileIntent,
        maxBytes: Long,
        writer: (OutputStream) -> Unit,
        readback: (File, () -> Unit) -> String?,
    ) {
        withContext(dispatcher) {
            withFileLane(lease.jobId) { active ->
                verifyMoved(RenderAttemptAuthority.Legacy(proof, lease), intent, maxBytes, { sink, _ -> writer(sink) }, readback, active)
            }
        }
    }

    private suspend fun <T> withFileLane(job: JobId, block: (() -> Unit) -> T): T {
        val run = suspend { owners.withOrdinaryDerivedLane(job) { block { coroutineContext.ensureActive() } } }
        val storeLifetime = lifetime
        return if (storeLifetime == null) run() else storeLifetime.retain(job.value, run)
    }

    /** Only issued and consumed within the completion attempt after byte, semantic and exact MOVED verification. */
    class VerifiedMoved private constructor(
        private val authority: RoomRenderIntents,
        val attemptAuthority: RenderAttemptAuthority, val intent: RenderFileIntent,
        val phase: RenderFilePhase, val rasterSha256: String, val execution: OrdinaryRenderExecution, val active: () -> Unit,
    ) {
        // This is a synchronous borrow, not a portable proof. Even an internal consumer cannot take it into a later transaction or use another database authority.
        private var borrower: Thread? = Thread.currentThread()

        fun claim(intents: RoomRenderIntents) {
            check(intents === authority && borrower === Thread.currentThread())
            borrower = null
        }

        /** One owner-retained memory scope. A fresh scope cannot adopt an existing file checkpoint, even when its result has equal pixels and provenance. */
        class Attempt private constructor(private val executor: RoomRenderFileExecution, private val rows: RenderCompletionQueries) {
            private class Producing(val authority: RenderAttemptAuthority, val intent: RenderFileIntent, val execution: OrdinaryRenderExecution) {
                fun matches(candidate: RenderAttemptAuthority): Boolean =
                    if (authority is RenderAttemptAuthority.Legacy && candidate is RenderAttemptAuthority.Legacy) {
                        authority.lease == candidate.lease
                    } else authority === candidate
            }

            private val gate = Mutex()
            private var producing: Producing? = null

            suspend fun complete(
                authority: RenderAttemptAuthority, intent: RenderFileIntent,
                candidate: OrdinaryRenderExecution, maxBytes: Long, writer: (OutputStream) -> Unit, readback: (File, () -> Unit) -> String?,
            ): RenderId = completeWithCheckpoint(authority, intent, candidate, maxBytes, { sink, _ -> writer(sink) }, readback)

            internal suspend fun completeWithCheckpoint(
                authority: RenderAttemptAuthority, intent: RenderFileIntent,
                candidate: OrdinaryRenderExecution, maxBytes: Long,
                writer: (OutputStream, () -> Unit) -> Unit, readback: (File, () -> Unit) -> String?,
            ): RenderId = withContext(executor.dispatcher) {
                val lease = authority.lease
                coroutineContext.ensureActive()
                if (!gate.tryLock()) executor.refuse(lease, OperationErrorCode.REVISION_CONFLICT, "render_completion_in_use")
                try {
                    if (!candidate.capturedFor(authority)) executor.invalid(lease)
                    val retained = producing
                    if (retained != null && (!retained.matches(authority) || retained.intent != intent ||
                            !retained.execution.sameExecution(candidate))) executor.invalid(lease)
                    val execution = retained?.execution ?: candidate
                    executor.withFileLane(lease.jobId) { active ->
                        val (phase, raster) = executor.verifyMoved(authority, intent, maxBytes, writer,
                            readback = { file, checkpoint -> readback(file, checkpoint)?.takeIf { it == execution.rasterSha256 } },
                            active = active,
                            admitPhase = { state ->
                                // A fresh write grant is the only source of provenance. A bound scope may only continue that grant, never redo it.
                                if ((state == FileTransactionState.INTENT) != (retained == null)) executor.invalid(lease)
                            },
                            copyingGranted = { producing = Producing(authority, intent, execution) },
                        )
                        active()
                        val receipt = VerifiedMoved(executor.intents, authority, intent, phase, raster, checkNotNull(producing).execution, active)
                        executor.intents.commitMoved(receipt, rows)
                        intent.renderId
                    }
                } finally {
                    gate.unlock()
                }
            }

            companion object {
                fun from(executor: RoomRenderFileExecution) = Attempt(executor, checkNotNull(executor.completionRows))
            }
        }
    }

    internal fun completionAttempt(): VerifiedMoved.Attempt = VerifiedMoved.Attempt.from(this)

    private fun verifyMoved(
        authority: RenderAttemptAuthority, intent: RenderFileIntent,
        maxBytes: Long, writer: (OutputStream, () -> Unit) -> Unit, readback: (File, () -> Unit) -> String?, active: () -> Unit,
        admitPhase: (FileTransactionState) -> Unit = {},
        copyingGranted: () -> Unit = {},
    ): Pair<RenderFilePhase, String> {
        val lease = authority.lease
        active()
        if (maxBytes <= 0) refuse(lease, OperationErrorCode.INVALID_INPUT, "render_file_invalid_cap")
        fun fence(expected: RenderFilePhase? = null, next: FileTransactionState? = null, seal: RenderSealIdentity? = null) =
            intents.executionPhase(authority, intent, active, expected, next, seal)

        try {
            var admission = fence()
            admitPhase(admission.phase.state)
            val target = DerivedFileTarget(DerivedKind.RENDER, intent.renderId.value)
            val sealMetadata = OrdinaryRenderSealV1(intent.journalPayload())
            fun verify(file: File, checkpoint: () -> Unit): String {
                checkpoint()
                val hash = readback(file, checkpoint) ?: invalid(lease)
                checkpoint()
                requireSha256(hash)
                return hash
            }

            val sealed = if (admission.phase.state == FileTransactionState.INTENT) {
                admission = fence(admission.phase, FileTransactionState.COPYING)
                // The transaction returned successfully, and no stage file or encoder has been opened yet.
                copyingGranted()
                val checkpoint = admission.checkpoint
                val staged = files.stage(intent.transactionId.value, target, maxBytes, { sink -> writer(sink, checkpoint) }, checkpoint)
                val rasterHash = files.readStaged(staged, checkpoint) { verify(it, checkpoint) }
                files.seal(staged, sealMetadata.encode(rasterHash), checkpoint)
            } else {
                val phase = admission.phase
                val recorded = phase.seal
                val checkpoint = admission.checkpoint
                if (recorded != null && recorded.byteCount > maxBytes) throw DerivedByteLimitExceeded()
                files.recoverBound(intent.transactionId.value, recorded?.sealSha256, target, maxBytes,
                    requireFinal = phase.state == FileTransactionState.MOVED, checkpoint = checkpoint,
                    authenticate = { byteHash, count, metadata ->
                        if (recorded != null && (recorded.byteSha256 != byteHash || recorded.byteCount != count)) invalid(lease)
                        sealMetadata.rasterSha256(metadata)
                    },
                    readback = { file, metadata ->
                        if (verify(file, checkpoint) != sealMetadata.rasterSha256(metadata)) invalid(lease)
                    },
                ) ?: invalid(lease)
            }

            val identity = RenderSealIdentity(sealed.sealSha256, sealed.staged.byteSha256, sealed.staged.byteCount)
            if (admission.phase.state == FileTransactionState.COPYING) {
                admission = fence(admission.phase, FileTransactionState.SEALED, identity)
            }
            val phase = admission.phase
            val moved = if (phase.state == FileTransactionState.MOVED) {
                // MOVED replay requires the exact surviving final; it never repairs or records another transition.
                fence(phase)
            } else {
                check(phase.state == FileTransactionState.SEALED && phase.seal == identity)
                files.moveWithFence(sealed, admission.checkpoint) { fence(phase) }
                fence(phase, FileTransactionState.MOVED, identity)
            }
            active()
            return moved.phase to sealMetadata.rasterSha256(sealed.metadata)
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (failure: RepositoryFailure) {
            throw failure
        } catch (_: DerivedByteLimitExceeded) {
            refuse(lease, OperationErrorCode.RESOURCE_LIMIT, "render_file_byte_cap")
        } catch (_: IllegalArgumentException) {
            invalid(lease)
        } catch (_: Exception) {
            refuse(lease, OperationErrorCode.STORAGE_FAILED, "render_file_storage_failed", retryable = true)
        }
    }

    private fun invalid(lease: JobLease): Nothing = refuse(lease, OperationErrorCode.INTEGRITY_FAILED, "render_file_binding_invalid")

    private fun refuse(lease: JobLease, code: OperationErrorCode, key: String, retryable: Boolean = false): Nothing =
        throw RepositoryFailure(OperationError(code, retryable, key, lease.jobId.value))

    companion object {
        @JvmSynthetic
        internal fun forSyntheticTests(
            intents: RoomRenderIntents, files: DerivedFiles, owners: FileOperationOwners, dispatcher: CoroutineDispatcher,
            completionRows: RenderCompletionQueries? = null,
        ): RoomRenderFileExecution = RoomRenderFileExecution(intents, files, owners, dispatcher, completionRows = completionRows)
    }
}

/** The v1 domain tag and exact intent bind closed raster readback to one attempt and target. The outer DerivedFiles seal carries the encoded hash and extent. */
internal class OrdinaryRenderSealV1(intentPayload: ByteArray) {
    private val prefix = TAG + intentPayload

    fun encode(rasterSha256: String): ByteArray {
        requireSha256(rasterSha256)
        return prefix + rasterSha256.encodeToByteArray()
    }

    fun rasterSha256(metadata: ByteArray): String {
        require(metadata.size == prefix.size + 64 && metadata.copyOf(prefix.size).contentEquals(prefix))
        return metadata.decodeToString(startIndex = prefix.size).also(::requireSha256)
    }

    private companion object {
        val TAG = "pristinepage-ordinary-render-readback-v1\u0000".encodeToByteArray()
    }
}
