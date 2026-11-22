package app.pristinepage.data.repository

import app.pristinepage.core.ocr.OcrConfiguration
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

/** Store-owned OCR boundary for the application graph. Construction does not schedule work, resolve assets, read files or enable an engine. */
class SavedOcrWork private constructor(store: PrivateImportStore.OcrComposition) {
    private val lifetime = store.lifetime
    private val backfill = RoomOcrBackfill(store.database)
    private val execution = RoomOcrExecution(store.database, store.owners)
    private val search = RoomOcrSearch(store.database)

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
    class ReadText internal constructor(val text: OcrText, val configuration: OcrConfiguration, val previousGeneration: Boolean)

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

    suspend fun readText(document: DocumentId, page: PageId): ReadText? =
        retained(page.value, "ocr_store_read_failed") { search.readText(document, page) }

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
