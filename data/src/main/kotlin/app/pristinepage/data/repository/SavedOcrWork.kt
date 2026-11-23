package app.pristinepage.data.repository

import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.core.ocr.OcrError
import app.pristinepage.core.ocr.OcrText
import android.database.SQLException
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.EditablePage
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.PageId
import app.pristinepage.data.model.RepositoryFailure
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** Store-owned OCR boundary for the application graph. Construction does not schedule work, resolve assets, read files or enable an engine. */
class SavedOcrWork private constructor(store: PrivateImportStore.OcrComposition) {
    private val lifetime = store.lifetime
    private val backfill = RoomOcrBackfill(store.database)
    private val execution = RoomOcrExecution(store.database, store.owners)
    private val search = RoomOcrSearch(store.database)
    private val textExport by lazy { RoomOcrTextExport(store, search) }
    private val textPublication by lazy { RoomOcrTextPublication(store, textExport) }
    private val editable = RoomEditablePages(RoomMetadataAccess(store.database), store.database.catalog())
    private val queue = RoomOcrQueue(store.database)
    private val invalidation = store.database.invalidationTracker

    /** IDs only, suitable for a scheduler hint. They are re-resolved before any work. */
    data class Target(val page: PageId, val job: JobId)

    internal enum class Scan { BACKFILL, SEARCH }

    /** In-process continuation only. It can include a rejected scan position and never exposes its page ID. */
    class Continuation private constructor(private val issuer: SavedOcrWork, private val scan: Scan, private val position: PageId) {
        @JvmSynthetic
        internal fun positionFor(owner: SavedOcrWork, scope: Scan): PageId? =
            if (issuer === owner && scan == scope) position else null

        companion object {
            @JvmSynthetic
            internal fun issue(owner: SavedOcrWork, scope: Scan, position: PageId?): Continuation? =
                position?.let { Continuation(owner, scope, it) }
        }
    }

    data class Reconciliation(val targets: List<Target>, val nextAfter: Continuation?)

    class Hit private constructor(private val issuer: SavedOcrWork, private val binding: RoomOcrSearch.Hit) {
        val document: DocumentId get() = binding.document
        val page: PageId get() = binding.page

        @JvmSynthetic
        internal fun bindingFor(owner: SavedOcrWork): RoomOcrSearch.Hit? = binding.takeIf { issuer === owner }

        companion object {
            @JvmSynthetic
            internal fun issue(owner: SavedOcrWork, binding: RoomOcrSearch.Hit): Hit = Hit(owner, binding)
        }
    }

    data class SearchPage(val hits: List<Hit>, val nextAfter: Continuation?)

    /** Private on-device display/export value. Keep it out of store diagnostics and scheduler/search payloads. */
    class ReadText internal constructor(val text: OcrText, val configuration: OcrConfiguration, val previousGeneration: Boolean,
        val exportObservation: TextExportObservation,
    )

    /** Opaque process-local identity, not plaintext, destination consent, or a permission to reopen a provider. */
    class TextExportObservation private constructor(private val issuer: SavedOcrWork, private val binding: RoomOcrSearch.TextBinding) {
        @JvmSynthetic
        internal fun bindingFor(owner: SavedOcrWork): RoomOcrSearch.TextBinding? = binding.takeIf { issuer === owner }

        companion object {
            @JvmSynthetic
            internal fun issue(owner: SavedOcrWork, binding: RoomOcrSearch.TextBinding): TextExportObservation =
                TextExportObservation(owner, binding)
        }
    }

    /** Verified private completion from this facade only. It is not consent and exposes no filesystem location. */
    class PreparedTextExport private constructor(private val issuer: SavedOcrWork, private val receipt: RoomOcrTextExport.Receipt) {
        val byteCount: Long get() = receipt.intent.byteCount
        val byteSha256: String get() = receipt.intent.textSha256

        @JvmSynthetic
        internal fun receiptFor(owner: SavedOcrWork): RoomOcrTextExport.Receipt? = receipt.takeIf { issuer === owner }

        companion object {
            @JvmSynthetic
            internal fun issue(owner: SavedOcrWork, receipt: RoomOcrTextExport.Receipt): PreparedTextExport = PreparedTextExport(owner, receipt)
        }
    }

    enum class PagePhase { QUEUED, RUNNING, READY, FAILED, CANCELED }

    /** Snapshot of the current durable generation. QUEUED/RUNNING do not prove scheduler or owner liveness. READY verifies producer metadata; a separate readText also decodes/validates the payload and may see a newer generation. */
    class PageStatus private constructor(private val issuer: SavedOcrWork, private val snapshot: RoomOcrSearch.Status) {
        val phase: PagePhase get() = snapshot.phase
        val attemptsStarted: Int get() = snapshot.attemptsStarted
        val lastError: OcrError? get() = snapshot.lastError
        val configuration: OcrConfiguration get() = snapshot.binding.intent.configuration

        @JvmSynthetic
        internal fun bindingFor(owner: SavedOcrWork): OcrRerunBinding? = snapshot.binding.takeIf { issuer === owner }

        companion object {
            @JvmSynthetic
            internal fun issue(owner: SavedOcrWork, snapshot: RoomOcrSearch.Status): PageStatus = PageStatus(owner, snapshot)
        }
    }

    /** Own every budgeted resource needed to recognize the supplied saved page and exact installed configuration. Close must finish all use. Preparation and closure may not perform recognition. */
    interface Prepared : AutoCloseable {
        suspend fun recognize(): OcrText
    }

    /** Transfer the complete session synchronously at its allocation site, before a canceled dispatcher hop could lose it. The producer still owns anything it did not successfully hand off. */
    fun interface Handoff {
        fun retain(resource: Prepared)
    }

    fun interface Preparer {
        suspend fun prepare(page: EditablePage, configuration: OcrConfiguration, handoff: Handoff)
    }

    /** Trusted preparer reports a measured admission decision for the input it was just given. This is not a public renderer warning, a lease, or evidence of native OOM. */
    class IdleBudgetRefusal(internal val input: EditablePage) :
        RepositoryFailure(OperationError(OperationErrorCode.RESOURCE_LIMIT, false, "ocr_render_exceeds_idle_budget", null))

    enum class Outcome { PUBLISHED, OBSOLETE, TERMINAL, WAITING_FOR_OWNER, BUSY, FAILURE_RECORDED }

    /** A non-null config must already refer to a qualified local runtime and verified installed packs. Null allows rediscovery but does not queue a new page generation. */
    suspend fun reconcile(configuration: OcrConfiguration?, after: Continuation?, limit: Int): Reconciliation =
        retained(null, "ocr_store_read_failed") {
            if (limit !in 1..50 || (configuration != null && configuration.languagePacks == null)) invalid("ocr_invalid_reconciliation")
            val batch = backfill.reconcile(configuration, after.position(Scan.BACKFILL), limit)
            Reconciliation(Collections.unmodifiableList(batch.targets.map { Target(it.page, it.job) }),
                Continuation.issue(this, Scan.BACKFILL, batch.nextAfter))
        }

    /** Recheck an exact saved page after commit; an obsolete or repeated signal cannot rerun unchanged terminal work. */
    suspend fun reconcilePage(document: DocumentId, page: PageId, configuration: OcrConfiguration?): Target? =
        retained(page.value, "ocr_store_read_failed") {
            if (configuration != null && configuration.languagePacks == null) invalid("ocr_invalid_reconciliation")
            backfill.reconcilePage(document, page, configuration)?.let { Target(it.page, it.job) }
        }

    suspend fun execute(target: Target, preparer: Preparer): Outcome = retained(target.page.value, "ocr_store_execution_failed") {
        execution.run(target.page, target.job) { page, config, handoff ->
            adapter(Stage.PREPARE) {
                preparer.prepare(page, config) { session ->
                    // Keep the native allocation's synchronous handoff; diagnostics are sanitized by phase.
                    handoff.retain(object : Prepared {
                        override suspend fun recognize(): OcrText = adapter(Stage.RECOGNIZE) { session.recognize() }
                        override fun close() = adapter(Stage.CLOSE) { session.close() }
                    })
                }
            }
        }
    }

    /** Search returns no OCR plaintext or raw database data. Recheck the exact hit again before navigation. */
    suspend fun find(query: String, after: Continuation?, limit: Int): SearchPage = retained(null, "ocr_store_read_failed") {
        if (limit !in 1..50) invalid("ocr_invalid_search_limit")
        val batch = search.find(query, after.position(Scan.SEARCH), limit)
        SearchPage(Collections.unmodifiableList(batch.hits.map { Hit.issue(this, it) }), Continuation.issue(this, Scan.SEARCH, batch.nextAfter))
    }

    suspend fun canOpen(hit: Hit): Boolean {
        val binding = hit.bindingFor(this) ?: return false
        return retained(hit.page.value, "ocr_store_read_failed") { search.canOpen(binding) }
    }

    /** Committed result changes, not WorkManager deliveries. The initial emission closes the query-before-subscription race; no query, page ID or text leaves the tracker. */
    fun observeSearchChanges(): Flow<Unit> = flow {
        retained(null, "ocr_search_observation_failed") {
            invalidation.createFlow("OcrResult", "Page", "Document", "AtelierDraft").collect { emit(Unit) }
        }
    }

    suspend fun readText(document: DocumentId, page: PageId, expectedRevision: Long? = null): ReadText? =
        retained(page.value, "ocr_store_read_failed") {
            search.readText(document, page, expectedRevision)?.let {
                ReadText(it.text, it.configuration, it.previousGeneration, TextExportObservation.issue(this, it.binding))
            }
        }

    /** Empty bytes are a verified blank. Null means the observed result no longer authorizes copying any text. */
    suspend fun resolveTextExport(observation: TextExportObservation): ByteArray? {
        val binding = observation.bindingFor(this) ?: return null
        return retained(binding.producer.page.value, "ocr_store_read_failed") { search.resolveTextExport(binding) }
    }

    /** Freeze and verify one private .txt from the exact displayed result. Access to any destination is separate. */
    suspend fun prepareTextExport(observation: TextExportObservation): PreparedTextExport? {
        val binding = observation.bindingFor(this) ?: return null
        return retained(binding.producer.page.value, "ocr_text_export_failed") {
            textExport.prepare(binding)?.let { PreparedTextExport.issue(this, it) }
        }
    }

    /** A detached private read, not permission to write an external destination. */
    suspend fun readPreparedTextExport(prepared: PreparedTextExport): ByteArray? {
        val receipt = prepared.receiptFor(this) ?: return null
        return retained(null, "ocr_text_export_read_failed") { textExport.read(receipt) }
    }

    enum class TextSaveOutcome { CANCELED, READBACK_VERIFIED, OUTPUT_COMPLETED, PARTIAL_EXTERNAL, NEEDS_VERIFICATION, DESTINATION_REFUSED, PERMISSION_LOST }

    /** Private presentation only, never Activity state or a substitute for a new destination grant. */
    class TextSaveRecovery internal constructor(val needsVerification: Boolean, val priorDestination: String?, val retry: PreparedTextExport?)

    /** The app supplies the exactly correlated document result and owns the temporary grant and provider verification. */
    suspend fun publishPreparedText(prepared: PreparedTextExport, choose: suspend (consentId: String) -> String?,
        transfer: suspend (target: String, bytes: ByteArray) -> TextSaveOutcome): TextSaveOutcome {
        val receipt = prepared.receiptFor(this) ?: invalid("ocr_text_publication_invalid_receipt")
        return retained(null, "ocr_text_publication_failed") { textPublication.publish(receipt, choose, transfer) }
    }

    /** Only metadata is reconciled; an ended writer's target is never reopened or rewritten. */
    suspend fun recoverTextPublications(): TextSaveRecovery =
        retained(null, "ocr_text_publication_recovery_failed") {
            val result = textPublication.recover()
            TextSaveRecovery(result != null, result?.target, result?.retry?.let { PreparedTextExport.issue(this, it) })
        }

    /** Metadata only: observing a live page's status does not load recognition text, grant a job lease or touch its original. */
    suspend fun readStatus(document: DocumentId, page: PageId, expectedRevision: Long? = null): PageStatus? =
        retained(page.value, "ocr_store_read_failed") { search.readStatus(document, page, expectedRevision)?.let { PageStatus.issue(this, it) } }

    /** A lazy album tile can observe only its exact accepted revision; neither Room state nor this opaque hint proves the wake is active. */
    fun observePendingTarget(document: DocumentId, page: PageId, revision: Long): Flow<Target?> =
        invalidation.createFlow("Page", "Document", "AtelierDraft", "RecipeRevision", "Source", "SourceBlob", "OcrPageWork", "Job", "SourceReference")
            .map {
                // Clear uncertain evidence, but keep listening for a committed change that can revalidate this same tile.
                try { retained(page.value, "ocr_store_read_failed") { search.pendingTarget(document, page, revision) } }
                catch (_: RepositoryFailure) { null }
            }

    /** Queue a successor to the observed job, not whatever happens to be current at delivery. The caller resolves a qualified local configuration before invoking this explicit command, then offers the returned ID-only wake. */
    suspend fun requestRerun(observed: PageStatus, configuration: OcrConfiguration): Target {
        val binding = observed.bindingFor(this) ?: invalid("ocr_rerun_invalid_observation")
        val input = binding.intent
        val page = PageId(input.input.pageId)
        return retained(page.value, "ocr_store_rerun_failed") {
            if (configuration.languagePacks == null) invalid("ocr_rerun_invalid_configuration")
            val saved = editable.load(input.documentId, page)
            if (saved.recipeRevision != input.input.recipeRevision || saved.sourceId != input.sourceId ||
                saved.recipeHash != input.recipeSha256 || saved.recipe.geometry.source.sourcePixelHash != input.input.sourcePixelSha256
            ) throw RepositoryFailure(OperationError(OperationErrorCode.REVISION_CONFLICT, false, "ocr_rerun_observation_changed", page.value))
            Target(page, queue.enqueue(saved, configuration, rerun = true, observed = binding))
        }
    }

    private fun Continuation?.position(scan: Scan): PageId? =
        this?.let { cursor -> cursor.positionFor(this@SavedOcrWork, scan) ?: invalid("ocr_invalid_continuation") }

    private fun invalid(key: String): Nothing = throw RepositoryFailure(OperationError(OperationErrorCode.INVALID_INPUT, false, key, null))

    private suspend fun <T> retained(affected: String?, storageKey: String, block: suspend () -> T): T = try {
        lifetime.retainOcr(affected, block)
    } catch (_: CancellationException) {
        throw CancellationException("OCR work canceled")
    } catch (fault: AdapterFault) {
        throw RepositoryFailure(OperationError(fault.code, true, fault.stage.key, affected))
    } catch (failure: RepositoryFailure) {
        // A private scan locator is never the affected ID of a public error. Also discard causes and suppressed failures from resource cleanup.
        throw RepositoryFailure(failure.error.copy(affectedId = affected))
    } catch (_: Exception) {
        throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, storageKey, affected))
    }

    private enum class Stage(val key: String) {
        PREPARE("ocr_preparation_fault"), RECOGNIZE("ocr_recognition_fault"), CLOSE("ocr_cleanup_fault"),
    }

    /** Unexpected exceptions are not explicit recognition classifications and must not become stored engine errors. */
    private class AdapterFault(val stage: Stage, val code: OperationErrorCode) : RuntimeException(stage.key)

    private inline fun <T> adapter(stage: Stage, block: () -> T): T = try {
        block()
    } catch (canceled: CancellationException) {
        throw canceled
    } catch (fault: AdapterFault) {
        throw fault
    } catch (failure: RepositoryFailure) {
        if (stage == Stage.PREPARE && failure is IdleBudgetRefusal) throw IdleBudgetRefusal(failure.input)
        throw RepositoryFailure(OperationError(failure.error.code, failure.error.retryable, stage.key, null))
    } catch (failure: Exception) {
        val code = if (failure is SQLException || failure is IOException) OperationErrorCode.STORAGE_FAILED else OperationErrorCode.PROCESSING_FAILED
        throw AdapterFault(stage, code)
    }

    companion object {
        @JvmSynthetic
        internal fun bind(store: PrivateImportStore.OcrComposition): SavedOcrWork = SavedOcrWork(store)
    }
}
