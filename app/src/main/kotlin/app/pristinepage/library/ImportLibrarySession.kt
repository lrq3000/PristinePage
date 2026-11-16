package app.pristinepage.library

import app.pristinepage.capture.PhotoImportRequest
import app.pristinepage.data.model.*
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Main-confined state owned by a lifecycle-retained scope, not by one Activity registration. IO is delegated to the existing Application graph; only opaque IDs are restorable. No selected provider reference enters StateFlow, a saved identity or an error. */
class ImportLibrarySession(private val actions: LibraryActions, restored: LibraryIdentity, private val scope: CoroutineScope) : LibrarySession {
    private val current = MutableStateFlow(LibraryState(selectedId = restored.selected, request = restored.pending?.let { PhotoImportRequest(it.value) }, phase = restored.pending?.let { ImportPhase.INTERRUPTED }))
    override val state: StateFlow<LibraryState> = current.asStateFlow()
    private val initialization = Mutex()
    private var started = false
    private var navigation = 0L
    private var metadataGeneration = 0L
    private var copying: Job? = null

    init { refresh(true) }

    override fun identity() = LibraryIdentity(current.value.selectedId, current.value.request?.let { ImportSessionId(it.batchId) })

    override fun claimPickerLaunch(batchId: ImportSessionId): Boolean {
        val value = current.value
        if (value.request?.batchId != batchId.value || !value.launchPending || value.phase != ImportPhase.WAITING_PICKER) return false
        current.value = value.copy(launchPending = false)
        return true
    }

    override fun accept(event: LibraryEvent) {
        when (event) {
            is LibraryEvent.Open -> {
                ++navigation
                current.value = current.value.copy(selectedId = event.id, document = null, loading = true, error = null)
                refresh(true, includeList = false)
            }
            is LibraryEvent.NewDocument -> if (!current.value.loading && (!event.importAfterCreate || (!current.value.preparingImport && current.value.request == null))) {
                current.value = current.value.copy(loading = true, error = null, preparingImport = current.value.preparingImport || event.importAfterCreate)
                run(onFailure = { error -> if (event.importAfterCreate) current.value = current.value.copy(preparingImport = false); failed(error) }) {
                    try {
                        val id = actions.create(event.title); ++navigation
                        current.value = current.value.copy(selectedId = id, document = null)
                        refresh(true).join()
                        if (event.importAfterCreate) reserveFor(id)
                    } finally { if (event.importAfterCreate) current.value = current.value.copy(preparingImport = false) }
                }
            }
            LibraryEvent.Refresh -> { current.value = current.value.copy(error = null); refresh(true) }
            LibraryEvent.More -> if (!current.value.loading && current.value.hasMore) {
                current.value = current.value.copy(error = null); refresh(false)
            }
            LibraryEvent.BeginImport -> prepare()
            is LibraryEvent.PhotoResult -> consume(event)
            LibraryEvent.CancelImport -> abandon(null)
            is LibraryEvent.PickerFailed -> if (current.value.request?.batchId == event.request.batchId) abandon(OperationError(OperationErrorCode.PROVIDER_UNAVAILABLE, false, "photo_picker_unavailable", event.request.batchId))
        }
    }

    private fun prepare() {
        val value = current.value
        val target = value.selectedId ?: return
        if (value.loading || value.request != null || value.preparingImport) return
        current.value = value.copy(loading = true, error = null, preparingImport = true)
        run(onFailure = { error -> current.value = current.value.copy(preparingImport = false); failed(error) }) {
            try { reserveFor(target) }
            finally { current.value = current.value.copy(preparingImport = false) }
        }
    }

    private suspend fun reserveFor(target: DocumentId) {
        // Preparation admission is independent of navigation/loading and spans create through durable reservation. Never reread the visible destination after suspension or drop an existing durable request.
        check(current.value.preparingImport && current.value.request == null)
        val request = actions.begin(actions.load(target).document)
        current.value = current.value.copy(request = request, launchPending = true, phase = ImportPhase.WAITING_PICKER, results = emptyList(), resultBatch = null, loading = false)
    }

    private fun consume(event: LibraryEvent.PhotoResult) {
        val value = current.value
        if (value.request?.batchId != event.request.batchId || copying?.isActive == true || value.phase !in setOf(ImportPhase.WAITING_PICKER, ImportPhase.INTERRUPTED)) return
        val selection = when (val result = event.selection) {
            is LibraryPhotoResult.Selected -> result.copy(references = result.references.toList())
            else -> result
        }
        current.value = value.copy(phase = ImportPhase.COPYING, launchPending = false, error = null)
        // Install the real consumer handle before it can run/reenter Main. Presentation changes and unrelated errors cannot replace the Job which cancellation must join.
        copying = run(start = CoroutineStart.LAZY, onFailure = { error ->
            if (current.value.request?.batchId == event.request.batchId && current.value.phase == ImportPhase.COPYING) current.value = current.value.copy(phase = ImportPhase.INTERRUPTED, error = error, loading = false)
        }) {
            val results = actions.deliver(event.request, selection)
            if (current.value.request?.batchId == event.request.batchId && current.value.phase == ImportPhase.COPYING) {
                val interrupted = results.any { it is IngestResult.Pending }
                val canceled = results.isNotEmpty() && results.all { it is IngestResult.Rejected && it.error.code == OperationErrorCode.CANCELED }
                // Outcomes are durable, but the displayed counts/order still precede this copy. Keep metadata visibly refreshing until loadSelected publishes the new snapshot; otherwise COMPLETE+idle can expose an empty pre-import overview.
                current.value = current.value.copy(request = if (interrupted) event.request else null, phase = if (interrupted) ImportPhase.INTERRUPTED else if (canceled) ImportPhase.CANCELED else ImportPhase.COMPLETE, resultBatch = ImportSessionId(event.request.batchId), results = owned(results), loading = true)
            }
            refresh(true).join()
        }
        copying!!.start()
    }

    private fun abandon(reason: OperationError?) {
        val request = current.value.request ?: return
        if (current.value.phase == ImportPhase.CANCELING) return
        val consumer = copying
        current.value = current.value.copy(phase = ImportPhase.CANCELING, launchPending = false)
        run(onFailure = { error ->
            if (current.value.request?.batchId == request.batchId && current.value.phase == ImportPhase.CANCELING) {
                // Only failure of this abandonment can restore retry admission. A metadata read failure has no authority over consumer or cancellation lifetime.
                current.value = current.value.copy(phase = if (consumer?.isActive == true) ImportPhase.COPYING else ImportPhase.INTERRUPTED, loading = false, error = error)
            }
        }) {
            // Fence durable publication before canceling the coroutine. CANCELING stays visible until the consumer actually ends; blocked provider IO is not advertised as stopped merely because metadata abandonment returned.
            val unavailable = try { actions.cancel(request); null } catch (failure: RepositoryFailure) {
                val error = failure.error
                // This exact cancelBatch refusal proves missing/wrong-kind identity, unlike expiry of a usable BATCH (which cancellation handles) or transient storage failure. Forget only the unusable UI request, never another durable reservation.
                if (error.code == OperationErrorCode.SESSION_EXPIRED && error.safeMessageKey == "import_session_unavailable" && error.affectedId == request.batchId) error else throw failure
            }
            consumer?.cancelAndJoin()
            if (current.value.request?.batchId == request.batchId) current.value = current.value.copy(request = null, phase = if (unavailable == null) ImportPhase.CANCELED else null, error = unavailable ?: reason, loading = true)
            refresh(true).join()
        }
    }

    private fun refresh(reset: Boolean, includeList: Boolean = true): Job {
        val token = ++metadataGeneration
        val selected = current.value.selectedId
        val nav = navigation
        current.value = current.value.copy(loading = true)
        return run(onFailure = { error -> if (token == metadataGeneration && nav == navigation) failed(error) }) {
            if (includeList) reload(reset, token)
            loadSelected(selected, nav, token)
        }
    }

    private suspend fun reload(reset: Boolean, token: Long) {
        val after = if (reset) null else current.value.documents.lastOrNull()?.documentId
        val documents = actions.list(after, 50)
        if (token == metadataGeneration) current.value = current.value.copy(documents = owned(if (reset) documents else current.value.documents + documents), hasMore = documents.size == 50)
    }

    private suspend fun loadSelected(id: DocumentId?, nav: Long, token: Long) {
        val document = id?.let { actions.load(it) }
        if (token != metadataGeneration || nav != navigation || current.value.selectedId != id) return
        val discover = id != null && document?.document?.hasPendingImport == true && current.value.request == null && !current.value.preparingImport
        val pending = if (discover) actions.activeImport(id) ?: throw RepositoryFailure(OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "pending_import_identity_missing", id.value)) else null
        if (token == metadataGeneration && nav == navigation && current.value.selectedId == id) {
            val value = current.value
            current.value = if (pending != null && value.request == null) value.copy(document = document, loading = false, request = PhotoImportRequest(pending.value), launchPending = false, phase = ImportPhase.INTERRUPTED)
            else value.copy(document = document, loading = false)
        }
    }

    private fun run(onFailure: (OperationError) -> Unit = ::failed, start: CoroutineStart = CoroutineStart.DEFAULT, action: suspend () -> Unit): Job = scope.launch(start = start) {
        try {
            initialization.withLock {
                if (!started) { val report = actions.startup(); current.value = current.value.copy(recovery = report); started = true }
            }
            action()
        } catch (canceled: CancellationException) { throw canceled }
        catch (failure: RepositoryFailure) { onFailure(failure.error) }
        catch (_: Exception) { onFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "library_operation_failed", current.value.request?.batchId ?: current.value.selectedId?.value)) }
    }

    private fun failed(error: OperationError) {
        current.value = current.value.copy(loading = false, error = error)
    }
    private fun <T> owned(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
}
