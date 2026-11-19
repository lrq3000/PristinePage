package app.pristinepage.data.repository

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.data.files.DerivedByteLimitExceeded
import app.pristinepage.data.files.DerivedFileTarget
import app.pristinepage.data.files.DerivedFiles
import app.pristinepage.data.files.DerivedKind
import app.pristinepage.data.model.ExportPageSnapshot
import app.pristinepage.data.model.ArtifactId
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.OrdinaryExportProfile
import app.pristinepage.data.model.PngExportItemIntent
import app.pristinepage.data.model.RenderId
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.TransactionId
import java.io.File
import java.util.Collections
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The trusted caller pairs the authenticated frozen page with its ordered JobId and retains stable ownership of the same private store, transaction and terminal target for the entire read. */
internal class RoomTerminalPngReads(
    private val metadata: RoomTerminalRenderReads,
    private val files: DerivedFiles,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    data class ObservedIdentity(val byteSha256: String, val byteCount: Long, val rasterSha256: String)

    /** Readback borrows only the exact closed final, synchronously and read-only. It must not retain the file; it owns codec admission and returns actual observed identities, never echoes the expectation. Null refuses. */
    fun interface Readback {
        fun verify(file: File, size: PixelSize, expectedRasterSha256: String, maxBytes: Long, checkpoint: () -> Unit): ObservedIdentity?
    }

    /** Installed together at trusted store construction. The bound codec still follows the synchronous read-only File contract; it is not sandboxed. */
    internal class AcquiredSetVerification(val stagedByteCap: (PixelSize) -> Long, val readback: Readback)

    /** Evidence for one retained render, not a pathname, pin, lease, artifact or readiness grant. */
    sealed interface Receipt {
        val renderId: RenderId
        val transactionId: TransactionId
        val size: PixelSize
        val sealSha256: String
        val observed: ObservedIdentity
    }

    private class VerifiedReceipt(
        override val renderId: RenderId,
        override val transactionId: TransactionId,
        override val size: PixelSize,
        override val sealSha256: String,
        override val observed: ObservedIdentity,
    ) : Receipt

    /** The callback can read only its issued slot, synchronously on its issuing thread. No caller-selected file, target, page, job or cap is accepted at use time. */
    sealed interface RetainedFinal {
        fun read(readback: Readback): Receipt
    }

    /** Prepared is an immutable reference, not Acquired or current snapshot liveness. This scope does not authorize artifact writes. */
    class Retained private constructor(
        private val reader: RoomTerminalPngReads,
        private val owners: FileOperationOwners,
        private val lifetime: OrdinaryExportLifetime,
        private val slot: (RoomFreshExportAssembly.Prepared, Int) -> Pair<JobId, ExportPageSnapshot>,
        private val setVerification: AcquiredSetVerification?,
        private val journal: ExportJournal?,
    ) {
        constructor(store: PrivateImportStore.ExportComposition, profile: OrdinaryExportProfile) :
            this(RoomTerminalPngReads(RoomTerminalRenderReads(store.database, profile), store.files), store.owners, store.lifetime,
                { prepared, ordinal -> prepared.terminalSlot(store, ordinal) }, store.terminalPngVerification, ExportJournal.Room(store))

        init {
            require(owners.ownsCanonicalRoot(reader.files.canonicalRoot))
        }

        suspend fun withRetainedFinal(
            prepared: RoomFreshExportAssembly.Prepared, ordinal: Int, maxBytes: Long,
            consume: (RetainedFinal) -> Unit,
        ) = withContext(reader.dispatcher) {
            coroutineContext.ensureActive()
            val (job, page) = slot(prepared, ordinal)
            reader.requireCap(job, maxBytes)
            lifetime.retain(job.value) {
                owners.withOrdinaryDerivedLane(job) {
                    val expected = reader.metadata.load(job, page)
                    val active = { coroutineContext.ensureActive() }
                    useBoundFinal(job, expected, maxBytes, active, consume)
                }
            }
        }

        /** Evidence only: one cooperative lane and one issuing-store borrow; neither a file location nor a partial set escapes. */
        suspend fun readAcquiredSet(acquired: RoomFreshExportAssembly.Acquired): List<Receipt> =
            AcquiredSetReader.start(this, acquired)

        /** Dormant internal entry. Consume only the live issuer's claim; transfer no file, metadata port or completion grant. */
        @JvmSynthetic
        internal suspend fun completeAcquiredSet(acquired: RoomFreshExportAssembly.Acquired) =
            AcquiredSetReader.complete(this, acquired)

        /** Host-only checkpoint. The Room composition cannot request a copy without the in-scope co-commit. */
        @JvmSynthetic
        @RoomPngExportItemWrites.SyntheticJournal
        internal suspend fun copyAcquiredForSyntheticTests(acquired: RoomFreshExportAssembly.Acquired) =
            AcquiredSetReader.copy(this, acquired)

        private sealed interface ExportJournal {
            class Room(store: PrivateImportStore.ExportComposition) : ExportJournal {
                val completion by lazy { RoomPngPrivateCompletion(store) }
            }

            class Synthetic(val writes: RoomPngExportItemWrites, val completion: RoomPngPrivateCompletion?) : ExportJournal
        }

        // Only this final, privately constructed bridge receives the owned operations. It has no factory or callback that can hand them (or itself) to another consumer.
        internal class AcquiredSetReader private constructor(
            private val retained: Retained,
            private val writes: RoomPngExportItemWrites?,
            private val completion: RoomPngPrivateCompletion? = null,
        ) {
            @JvmSynthetic
            internal fun requireCompletion(participant: RoomPngPrivateCompletion) {
                check(completion === participant)
            }

            @JvmSynthetic
            internal suspend fun read(
                prepared: RoomFreshExportAssembly.Prepared,
                lease: JobLease,
                checkSet: (CoroutineContext) -> () -> Unit,
                checkAttempt: (CoroutineContext) -> Unit,
                copyTransaction: (CoroutineContext, Boolean, (Long, () -> Unit) -> Unit) -> Unit,
                finish: (CoroutineContext, RoomPngPrivateCompletion.Set) -> Unit,
            ): List<Receipt> = retained.readWithRunningFences(prepared, lease, checkSet, checkAttempt, writes, copyTransaction,
                completion?.let { participant -> { assembly, items -> participant.prepare(this, assembly, items) } }, finish)

            companion object {
                @JvmSynthetic
                internal suspend fun start(retained: Retained, acquired: RoomFreshExportAssembly.Acquired): List<Receipt> =
                    acquired.readRetainedTerminalSet(AcquiredSetReader(retained, null))

                @JvmSynthetic
                internal suspend fun complete(retained: Retained, acquired: RoomFreshExportAssembly.Acquired) {
                    val completion = when (val journal = retained.journal) {
                        is ExportJournal.Room -> journal.completion
                        is ExportJournal.Synthetic -> journal.completion
                        null -> null
                    } ?: retained.reader.refuse(acquired.lease.jobId, OperationErrorCode.PROVIDER_UNAVAILABLE, "png_private_completion_unavailable")
                    acquired.readRetainedTerminalSet(AcquiredSetReader(retained, completion.writes, completion))
                }

                @JvmSynthetic
                @RoomPngExportItemWrites.SyntheticJournal
                internal suspend fun copy(retained: Retained, acquired: RoomFreshExportAssembly.Acquired) {
                    val synthetic = retained.journal as? ExportJournal.Synthetic
                        ?: retained.reader.refuse(acquired.lease.jobId, OperationErrorCode.INTEGRITY_FAILED, "png_export_copy_synthetic_only")
                    acquired.readRetainedTerminalSet(AcquiredSetReader(retained, synthetic.writes))
                }
            }
        }

        private suspend fun readWithRunningFences(
            prepared: RoomFreshExportAssembly.Prepared,
            lease: JobLease,
            checkSet: (CoroutineContext) -> () -> Unit,
            checkAttempt: (CoroutineContext) -> Unit,
            writes: RoomPngExportItemWrites?,
            copyTransaction: (CoroutineContext, Boolean, (Long, () -> Unit) -> Unit) -> Unit,
            prepareCompletion: ((PngExportItemIntent.Assembly, List<RoomPngPrivateCompletion.MovedItem>) -> RoomPngPrivateCompletion.Set)?,
            finish: (CoroutineContext, RoomPngPrivateCompletion.Set) -> Unit,
        ): List<Receipt> = withContext(reader.dispatcher) {
            coroutineContext.ensureActive()
            val assembly = prepared.jobId
            slot(prepared, 0) // Refuse a wrong store/lifetime before acquiring either retention guard.
            val verification = setVerification
                ?: reader.refuse(assembly, OperationErrorCode.PROVIDER_UNAVAILABLE, "terminal_png_verifier_unavailable")
            lifetime.retain(assembly.value) {
                owners.withOrdinaryDerivedLane(assembly) {
                    val context = coroutineContext
                    val active = checkSet(context)
                    // Bind the trusted store's limit once to each frozen output size.
                    val slots = prepared.snapshot.pages.indices.map { ordinal ->
                        active()
                        val (job, page) = slot(prepared, ordinal)
                        val cap = verification.stagedByteCap(page.output.size)
                        reader.requireCap(job, cap)
                        TerminalSlot(job, page, cap)
                    }
                    val checkOrdinal = {
                        active()
                        checkAttempt(context)
                        active()
                    }
                    val guardedReadback = Readback { file, size, raster, cap, _ ->
                        // The compressed-byte scan can be long. Verify the current attempt again just before handing the exact borrowed file to the verifier.
                        checkOrdinal()
                        verification.readback.verify(file, size, raster, cap, active).also { active() }
                    }
                    val verified = slots.map { slot ->
                        val (job, page, cap) = slot
                        active()
                        val expected = reader.metadata.load(job, page)
                        checkOrdinal()
                        val receipt = useBoundFinal(job, expected, cap, active) { it.read(guardedReadback) }
                        checkOrdinal()
                        VerifiedSlot(slot, expected, receipt)
                    }
                    // Re-read the complete frozen identity and bounded attempt after all file postchecks, while the same lane is still held.
                    if (writes == null) {
                        checkSet(context)
                        Collections.unmodifiableList(verified.map { it.receipt })
                    } else {
                        copyWithinLane(prepared, lease, verified, writes, active, prepareCompletion,
                            { full, action -> copyTransaction(context, full, action) }, { finish(context, it) })
                        emptyList() // This branch cannot return a copy result, file grant, or completion authorization.
                    }
                }
            }
        }

        private data class TerminalSlot(val job: JobId, val page: ExportPageSnapshot, val cap: Long)
        private data class VerifiedSlot(val slot: TerminalSlot, val source: RoomTerminalRenderReads.Metadata, val receipt: Receipt)

        /** All file effects finish before the same issuer's final short transaction, while the lane and borrow still belong to this traversal. */
        private fun copyWithinLane(
            prepared: RoomFreshExportAssembly.Prepared, lease: JobLease, verified: List<VerifiedSlot>,
            writes: RoomPngExportItemWrites, active: () -> Unit,
            prepareCompletion: ((PngExportItemIntent.Assembly, List<RoomPngPrivateCompletion.MovedItem>) -> RoomPngPrivateCompletion.Set)?,
            transaction: (Boolean, (Long, () -> Unit) -> Unit) -> Unit,
            finish: (RoomPngPrivateCompletion.Set) -> Unit,
        ) {
            val job = prepared.jobId
            val assembly = PngExportItemIntent.Assembly(prepared.snapshot.snapshotId, prepared.snapshot.snapshotHash,
                job, prepared.digest, lease.owner, lease.generation, lease.generation, verified.size)
            try {
                val renderTransactions = verified.map { it.receipt.transactionId.value }
                val renderIds = verified.map { it.receipt.renderId.value }
                if (renderTransactions.toSet().size != verified.size || renderIds.toSet().size != verified.size) {
                    reader.refuse(job, OperationErrorCode.INTEGRITY_FAILED, "png_export_copy_duplicate_render")
                }
                val ids = (renderTransactions + renderIds).toMutableSet()
                fun newId(): String = UUID.randomUUID().toString().also { require(ids.add(it)) }
                var budget = 0L
                var total = 0L
                val plans = verified.map { (slot, _, receipt) ->
                    active()
                    budget = Math.addExact(budget, slot.cap)
                    total = Math.addExact(total, receipt.observed.byteCount)
                    val item = PngExportItemIntent.Item(slot.page.ordinal, receipt.size, slot.job, receipt.renderId,
                        receipt.transactionId, receipt.sealSha256, receipt.observed.byteSha256, receipt.observed.byteCount,
                        receipt.observed.rasterSha256, ArtifactId(newId()), TransactionId(newId()))
                    RoomPngExportItemWrites.Plan(assembly, item, slot.cap)
                }
                if (total > budget) throw DerivedByteLimitExceeded()
                var intentAudit = -1L
                transaction(true) { audit, fence ->
                    writes.insertFreshSet(assembly, plans, audit, fence)
                    intentAudit = audit
                }

                val moves = plans.mapIndexed { ordinal, plan ->
                    val item = plan.item
                    val source = verified[ordinal].source
                    val target = DerivedFileTarget(DerivedKind.EXPORT, item.artifactId.value, assembly.snapshotId.value)
                    var lastAudit = intentAudit
                    fun advance(previous: String, next: String, seal: String? = null) {
                        transaction(false) { audit, fence ->
                            writes.advance(plan, previous, next, lastAudit, audit, seal, fence)
                            lastAudit = audit
                        }
                    }
                    advance("INTENT", "COPYING")
                    val authorizeCopying = {
                        transaction(false) { _, _ -> writes.authenticate(plan, "COPYING", lastAudit, null) }
                    }
                    // Observe the persisted attempt again after each long scan. The transaction ends before the file effect; the two are not atomic.
                    val staged = reader.files.stageBoundExportCopy(item.renderTransactionId.value, item.renderSealSha256,
                        DerivedFileTarget(DerivedKind.RENDER, item.renderId.value), OrdinaryRenderSealV1(source.intentPayload()).encode(item.rasterSha256),
                        item.byteSha256, item.byteCount, plan.cap, item.exportTransactionId.value, target, active, authorizeCopying)
                    // No destination seal until the source stream closed AND the bound source borrow finished its postchecks.
                    val sealed = reader.files.sealWithFence(staged, plan.payload(), active, authorizeCopying)
                    val seal = sealed.sealSha256
                    advance("COPYING", "SEALED", seal)
                    reader.files.moveWithFence(sealed, active, allowVerifiedExisting = false) {
                        transaction(false) { _, _ -> writes.authenticate(plan, "SEALED", lastAudit, seal) }
                    }
                    advance("SEALED", "MOVED", seal)
                    RoomPngPrivateCompletion.MovedItem(plan, source, seal, lastAudit)
                }
                if (prepareCompletion != null) {
                    finish(prepareCompletion(assembly, moves))
                } else {
                    val checkMoved = writes.movedCheck(assembly, moves.map { RoomPngExportItemReads.ExpectedMove(it.plan.item, it.seal, it.plan.cap) })
                    transaction(true) { _, _ -> checkMoved() }
                }
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (failure: RepositoryFailure) {
                throw failure
            } catch (_: ArithmeticException) {
                reader.refuse(job, OperationErrorCode.RESOURCE_LIMIT, "png_export_copy_byte_cap")
            } catch (_: DerivedByteLimitExceeded) {
                reader.refuse(job, OperationErrorCode.RESOURCE_LIMIT, "png_export_copy_byte_cap")
            } catch (_: IllegalArgumentException) {
                reader.refuse(job, OperationErrorCode.INTEGRITY_FAILED, "png_export_copy_binding_invalid")
            } catch (_: Exception) {
                reader.refuse(job, OperationErrorCode.STORAGE_FAILED, "png_export_copy_storage_failed", retryable = true)
            }
        }

        private fun <T> useBoundFinal(
            job: JobId, expected: RoomTerminalRenderReads.Metadata, maxBytes: Long, active: () -> Unit, consume: (RetainedFinal) -> T,
        ): T {
            val borrow = BoundFinal(reader, job, expected, maxBytes, active)
            try {
                active()
                return consume(borrow).also { active() }
            } finally {
                borrow.end()
            }
        }

        companion object {
            @JvmSynthetic
            internal fun forSyntheticTests(
                reader: RoomTerminalPngReads, owners: FileOperationOwners, store: RoomFreshExportAssembly.SyntheticStore, lifetime: OrdinaryExportLifetime,
                setVerification: AcquiredSetVerification? = null,
            ): Retained = Retained(reader, owners, lifetime, { prepared, ordinal -> prepared.terminalSlot(store, lifetime, ordinal) },
                setVerification, null)

            @JvmSynthetic
            @RoomPngExportItemWrites.SyntheticJournal
            internal fun forSyntheticCopyTests(
                reader: RoomTerminalPngReads, owners: FileOperationOwners, store: RoomPngExportItemWrites.SyntheticStore,
                lifetime: OrdinaryExportLifetime, setVerification: AcquiredSetVerification?,
            ): Retained {
                require(reader.metadata.usesSyntheticStore(store))
                val completion = (store as? RoomPngPrivateCompletion.SyntheticStore)?.let(RoomPngPrivateCompletion::forSyntheticTests)
                return Retained(reader, owners, lifetime, { prepared, ordinal -> prepared.terminalSlot(store, lifetime, ordinal) },
                    setVerification, ExportJournal.Synthetic(completion?.writes ?: RoomPngExportItemWrites.forSyntheticTests(store), completion))
            }
        }
    }

    private class BoundFinal(
        private val reader: RoomTerminalPngReads,
        private val job: JobId,
        private val expected: RoomTerminalRenderReads.Metadata,
        private val maxBytes: Long,
        private val active: () -> Unit,
    ) : RetainedFinal {
        private val borrower = Thread.currentThread()
        private var ended = false

        fun end() { ended = true }

        override fun read(readback: Readback): Receipt {
            // Only the issuing thread can reach inspection or read ended; it cannot leave the callback while a synchronous read is in flight.
            if (Thread.currentThread() !== borrower || ended) {
                reader.refuse(job, OperationErrorCode.INTEGRITY_FAILED, "terminal_png_retention_ended")
            }
            return reader.inspect(job, expected, maxBytes, readback, active)
        }
    }

    /** Plain trusted-caller entry: the caller, not this method, retains lifetime and authenticates frozen assembly order. */
    suspend fun load(jobId: JobId, page: ExportPageSnapshot, maxBytes: Long, readback: Readback): Receipt = withContext(dispatcher) {
        val active = { coroutineContext.ensureActive() }
        active()
        requireCap(jobId, maxBytes)
        inspect(jobId, metadata.load(jobId, page), maxBytes, readback, active)
    }

    private fun requireCap(jobId: JobId, maxBytes: Long) {
        if (maxBytes <= 0) refuse(jobId, OperationErrorCode.INVALID_INPUT, "terminal_png_invalid_cap")
    }

    private fun inspect(
        jobId: JobId, expected: RoomTerminalRenderReads.Metadata, maxBytes: Long, readback: Readback, active: () -> Unit,
    ): Receipt {
        active()
        val expectedSealMetadata = OrdinaryRenderSealV1(expected.intentPayload()).encode(expected.rasterSha256)
        val target = DerivedFileTarget(DerivedKind.RENDER, expected.renderId.value)
        var verified: ObservedIdentity? = null
        val seal = try {
            files.inspectBoundFinal(expected.transactionId.value, expected.sealSha256, target, maxBytes, active,
                authenticate = { byteSha256, count, sealMetadata ->
                    if (byteSha256 != expected.byteSha256 || count != expected.byteCount ||
                        !sealMetadata.contentEquals(expectedSealMetadata)) invalidSeal(jobId)
                },
                readback = { file, _ ->
                    val observed = readback.verify(file, expected.size, expected.rasterSha256, maxBytes, active)
                    active()
                    if (observed == null || observed.byteSha256 != expected.byteSha256 || observed.byteCount != expected.byteCount ||
                        observed.rasterSha256 != expected.rasterSha256) {
                        refuse(jobId, OperationErrorCode.INTEGRITY_FAILED, "terminal_png_readback_mismatch")
                    }
                    verified = observed
                },
            )
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (failure: RepositoryFailure) {
            throw failure
        } catch (_: DerivedByteLimitExceeded) {
            refuse(jobId, OperationErrorCode.RESOURCE_LIMIT, "terminal_png_byte_cap")
        } catch (_: IllegalArgumentException) {
            invalidSeal(jobId)
        } catch (_: Exception) {
            refuse(jobId, OperationErrorCode.STORAGE_FAILED, "terminal_png_read_failed", retryable = true)
        }
        // The callback result alone proves nothing: require the file layer's post-readback copy checks and closed inspection to finish first.
        active()
        if (seal == null) invalidSeal(jobId)
        return VerifiedReceipt(expected.renderId, expected.transactionId, expected.size, seal.sealSha256, verified ?: invalidSeal(jobId))
    }

    private fun invalidSeal(jobId: JobId): Nothing =
        refuse(jobId, OperationErrorCode.INTEGRITY_FAILED, "terminal_png_sealed_final_invalid")

    private fun refuse(jobId: JobId, code: OperationErrorCode, key: String, retryable: Boolean = false): Nothing =
        throw RepositoryFailure(OperationError(code, retryable, key, jobId.value))
}
