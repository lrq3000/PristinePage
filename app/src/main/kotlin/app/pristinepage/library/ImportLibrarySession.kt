package app.pristinepage.library

import app.pristinepage.capture.PhotoImportRequest
import app.pristinepage.capture.PhotoImportKind
import app.pristinepage.data.library.LibraryActivation
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.AlbumSearchQuery
import java.time.ZoneId
import java.util.Collections
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Main-confined state owned by a lifecycle-retained scope, not by one Activity registration. IO is delegated to the existing Application graph; only opaque IDs are restorable. No selected provider reference enters StateFlow, a saved identity or an error. */
class ImportLibrarySession(private val actions: LibraryActions, restored: LibraryIdentity, private val scope: CoroutineScope) : LibrarySession {
    private var pendingSelection = restored.takeIf { it.selectedAt != null }
    private val current = MutableStateFlow(LibraryState(selectedId = restored.selected.takeIf { restored.selectedAt == null },
        selectedPage = restored.selectedPage.takeIf { restored.selectedAt == null }, request = restored.pending?.let { PhotoImportRequest(it.value, restored.kind, restored.issuedAt) },
        phase = restored.pending?.let { ImportPhase.INTERRUPTED }, canSearchAlbums = actions is SearchableLibraryActions,
        canSearchPages = actions is SearchablePageActions, restoringSelection = pendingSelection != null))
    override val state: StateFlow<LibraryState> = current.asStateFlow()
    private val initialization = Mutex()
    private var started = false
    private var navigation = 0L
    private var metadataGeneration = 0L
    private var copying: Job? = null
    private var metadataRead: Job? = null
    private var albumLocale = Locale.getDefault()
    private var albumCursor: AlbumPosition? = null
    private var retryFirstAlbumPage = false
    private var pageCursor: (suspend () -> HomePageSearchPage)? = null
    private var pageWindows = 0
    private var hitNavigation = 0L
    private var openingHit: Job? = null
    private var searchZone = ZoneId.systemDefault()

    init { refresh(true) }

    override fun identity() = current.value.let { value ->
        val waiting = pendingSelection.takeIf { navigation == 0L }
        LibraryIdentity(waiting?.selected ?: value.selectedId, value.request?.session, waiting?.selectedPage ?: value.selectedPage,
            value.request?.kind ?: PhotoImportKind.GALLERY, value.request?.issuedAt,
            waiting?.selectedAt ?: value.activation.takeIf { value.selectedId != null })
    }

    override suspend fun observePageSearch(query: String) {
        val search = actions as? SearchablePageActions ?: return
        if (query.isBlank()) return
        fun stillOnQuery() = current.value.let { it.selectedId == null && it.albumSearch == query }
        search.pageSearchChanges(query).catch {
            if (it is CancellationException) throw it
            // A failed observation does not disable an explicit query or refresh.
        }.conflate().collect {
            while (stillOnQuery()) {
                val pending = metadataRead
                pending?.join()
                if (pending !== metadataRead) continue
                if (!stillOnQuery()) break
                val token = metadataGeneration
                refreshPageWindow(query, search) { token == metadataGeneration && stillOnQuery() }
                if (token == metadataGeneration) break
            }
        }
    }

    override fun claimPickerLaunch(batchId: ImportSessionId): Boolean {
        val value = current.value
        if (value.request?.batchId != batchId.value || !value.request.canDeliver || !value.launchPending || value.phase != ImportPhase.WAITING_PICKER) return false
        current.value = value.copy(launchPending = false)
        return true
    }

    override fun accept(event: LibraryEvent) {
        when (event) {
            is LibraryEvent.Open -> {
                ++navigation
                discardSelectionRestore()
                val same = current.value.selectedId == event.id
                current.value = current.value.copy(selectedId = event.id, selectedPage = current.value.selectedPage.takeIf { same }, document = null, loading = true, error = null,
                    results = if (same) current.value.results else emptyList(), resultBatch = current.value.resultBatch.takeIf { same })
                refresh(true, includeList = false)
            }
            is LibraryEvent.SelectPage -> {
                val value = current.value
                if (value.document?.pages?.any { it.pageId == event.id } == true) current.value = value.copy(selectedPage = event.id)
            }
            LibraryEvent.CloseAlbum -> {
                if (current.value.request != null || current.value.preparingImport || current.value.renaming) return
                ++navigation
                discardSelectionRestore()
                current.value = current.value.copy(selectedId = null, selectedPage = null, document = null, error = null, renameResult = null)
                refresh(true)
            }
            is LibraryEvent.SetAlbumSort -> {
                if (event.locale == albumLocale && event.order == current.value.albumOrder) return
                albumLocale = event.locale
                current.value = current.value.copy(albumOrder = event.order, documents = emptyList(), hasMore = false)
                refresh(true)
            }
            is LibraryEvent.SetAlbumSearch -> {
                val value = current.value
                if (value.selectedId != null || !value.canSearchAlbums) return
                val text = event.text.take(AlbumSearchQuery.MAX_LENGTH)
                if (text == value.albumSearch && searchZone == event.zone) return
                searchZone = event.zone
                albumCursor = null
                pageCursor = null
                pageWindows = 0
                current.value = value.copy(albumSearch = text, documents = emptyList(), pageMatches = emptyList(),
                    pageSearchUnavailable = false, albumSearchUnavailable = false, hasMore = false, error = null)
                refresh(true, debounceSearch = text.isNotBlank())
            }
            is LibraryEvent.OpenPageMatch -> openPageMatch(event)
            LibraryEvent.LeaveHomeSearch -> {
                cancelOpeningHit()
                discardSelectionRestore()
            }
            is LibraryEvent.RenameAlbum -> rename(event)
            LibraryEvent.ClearRenameResult -> if (!current.value.renaming) current.value = current.value.copy(renameResult = null)
            is LibraryEvent.NewDocument -> if (!current.value.loading && (!event.importAfterCreate || (!current.value.preparingImport && current.value.request == null))) {
                current.value = current.value.copy(loading = true, error = null, preparingImport = current.value.preparingImport || event.importAfterCreate)
                run(onFailure = { error -> if (event.importAfterCreate) current.value = current.value.copy(preparingImport = false); failed(error) }) {
                    try {
                        val id = actions.create(event.title); ++navigation
                        current.value = current.value.copy(selectedId = id, selectedPage = null, document = null)
                        refresh(true).join()
                        if (event.importAfterCreate) reserveFor(id, event.kind)
                    } finally { if (event.importAfterCreate) current.value = current.value.copy(preparingImport = false) }
                }
            }
            LibraryEvent.Refresh -> { current.value = current.value.copy(error = null); refresh(true) }
            LibraryEvent.RefreshAlbum -> { current.value = current.value.copy(error = null); refresh(true, includeList = false) }
            LibraryEvent.More -> if (!current.value.loading && current.value.hasMore) {
                current.value = current.value.copy(error = null); refresh(false)
            }
            LibraryEvent.BeginImport -> prepare(PhotoImportKind.GALLERY)
            LibraryEvent.BeginFiles -> prepare(PhotoImportKind.FILES)
            LibraryEvent.BeginCapture -> prepare(PhotoImportKind.CAMERA)
            is LibraryEvent.PhotoResult -> consume(event)
            LibraryEvent.CancelImport -> abandon(null)
            is LibraryEvent.PickerFailed -> if (event.request.canDeliver && current.value.request?.matches(event.request) == true) abandon(OperationError(OperationErrorCode.PROVIDER_UNAVAILABLE, false, if (event.request.kind == PhotoImportKind.FILES) "files_picker_unavailable" else "gallery_unavailable", event.request.batchId))
        }
    }

    private fun openPageMatch(event: LibraryEvent.OpenPageMatch) {
        val hit = event.hit
        val token = metadataGeneration
        val nav = navigation
        val issuedAt = current.value.activation
        fun eligible(): Boolean {
            val value = current.value
            return token == metadataGeneration && nav == navigation && value.activation == issuedAt && value.selectedId == null &&
                !value.loading && !value.preparingImport && value.request == null
        }
        if (!eligible() || hit !in current.value.pageMatches) return
        cancelOpeningHit()
        val opening = hitNavigation
        fun currentHit() = opening == hitNavigation && eligible()
        openingHit = run(onFailure = { if (currentHit()) current.value = current.value.copy(pageSearchUnavailable = true) }) {
            val overview = actions.openPageHit(hit, issuedAt) ?: return@run
            currentCoroutineContext().ensureActive()
            if (!currentHit()) return@run
            ++navigation
            current.value = current.value.copy(selectedId = hit.document, selectedPage = hit.page, document = overview, error = null)
            event.open(hit.document, hit.page)
        }
    }

    private fun cancelOpeningHit() {
        ++hitNavigation
        openingHit?.cancel()
        openingHit = null
    }

    /** Home is usable while a cold restore waits. A newer navigation decision must not dismiss Settings or take Back away from the user. */
    private fun discardSelectionRestore() {
        if (pendingSelection == null) return
        pendingSelection = null
        current.value = current.value.copy(restoringSelection = false)
    }

    private fun rename(event: LibraryEvent.RenameAlbum) {
        val value = current.value
        val document = value.document?.document ?: return
        if (value.renaming || value.request != null || value.preparingImport || value.selectedId != event.documentId || document.documentId != event.documentId) return
        val title = event.title.trim()
        if (event.expectedDocumentRevision != document.documentRevision) {
            current.value = value.copy(renameResult = DocumentMutationResult.Conflict(document.documentRevision))
            refresh(true, includeList = false)
            return
        }
        if (title == document.title) {
            current.value = value.copy(renameResult = DocumentMutationResult.Applied(document.documentRevision, emptyList(), null))
            return
        }
        current.value = value.copy(renaming = true, renameResult = null)
        run(onFailure = { error -> current.value = current.value.copy(renaming = false, renameResult = DocumentMutationResult.Rejected(error)) }) {
            val result = actions.rename(document.documentId, event.expectedDocumentRevision, title)
            current.value = current.value.copy(renaming = false, renameResult = result)
            if (result !is DocumentMutationResult.Rejected) refresh(true, includeList = false).join()
        }
    }

    private fun prepare(kind: PhotoImportKind) {
        val value = current.value
        val target = value.selectedId ?: return
        if (value.loading || value.request != null || value.preparingImport) return
        current.value = value.copy(loading = true, error = null, preparingImport = true)
        run(onFailure = { error -> current.value = current.value.copy(preparingImport = false); failed(error) }) {
            try { reserveFor(target, kind) }
            finally { current.value = current.value.copy(preparingImport = false) }
        }
    }

    private suspend fun reserveFor(target: DocumentId, kind: PhotoImportKind) {
        // Preparation admission is independent of navigation/loading and spans create through durable reservation. Never reread the visible destination after suspension or drop an existing durable request.
        check(current.value.preparingImport && current.value.request == null)
        val reserved = actions.begin(actions.load(target).document)
        val request = reserved.routedAs(kind)
        current.value = current.value.copy(request = request, launchPending = kind != PhotoImportKind.CAMERA, phase = ImportPhase.WAITING_PICKER, results = emptyList(), resultBatch = null, loading = false)
    }

    /** A declined camera callback leaves the staged file owned by its writer, not the importer. */
    internal fun submitCapture(event: LibraryEvent.PhotoResult): Boolean =
        event.request.kind == PhotoImportKind.CAMERA && consume(event)

    private fun consume(event: LibraryEvent.PhotoResult): Boolean {
        val value = current.value
        if (value.request?.matches(event.request) != true || !event.request.canDeliver || copying?.isActive == true || value.phase !in setOf(ImportPhase.WAITING_PICKER, ImportPhase.INTERRUPTED)) return false
        if (event.request.kind == PhotoImportKind.CAMERA && value.phase != ImportPhase.WAITING_PICKER) return false
        val selection = when (val result = event.selection) {
            is LibraryPhotoResult.Selected -> result.copy(references = result.references.toList())
            else -> result
        }
        current.value = value.copy(phase = ImportPhase.COPYING, launchPending = false, error = null)
        // Install the real consumer handle before it can run/reenter Main. Presentation changes and unrelated errors cannot replace the Job which cancellation must join.
        copying = run(start = CoroutineStart.LAZY, initialize = event.request.issuedAt == null, onFailure = { error ->
            if (!detachStale(event.request, error) && current.value.request?.matches(event.request) == true && current.value.phase == ImportPhase.COPYING) {
                current.value = current.value.copy(phase = ImportPhase.INTERRUPTED, error = error, loading = false)
            }
        }) {
            val results = actions.deliver(event.request, selection)
            if (current.value.request?.matches(event.request) == true && current.value.phase == ImportPhase.COPYING) {
                val interrupted = results.any { it is IngestResult.Pending }
                val canceled = results.isNotEmpty() && results.all { it is IngestResult.Rejected && it.error.code == OperationErrorCode.CANCELED }
                // Outcomes are durable, but the displayed counts/order still precede this copy. Keep metadata visibly refreshing until loadSelected publishes the new snapshot; otherwise COMPLETE+idle can expose an empty pre-import overview.
                current.value = current.value.copy(request = if (interrupted) event.request else null, phase = if (interrupted) ImportPhase.INTERRUPTED else if (canceled) ImportPhase.CANCELED else ImportPhase.COMPLETE, resultBatch = ImportSessionId(event.request.batchId), results = owned(results), loading = true)
            }
            refresh(true).join()
        }
        copying!!.start()
        return true
    }

    private fun abandon(reason: OperationError?) {
        val request = current.value.request ?: return
        if (current.value.phase == ImportPhase.CANCELING) return
        val consumer = copying
        current.value = current.value.copy(phase = ImportPhase.CANCELING, launchPending = false)
        run(initialize = request.issuedAt == null, onFailure = { error ->
            if (!detachStale(request, error) && current.value.request?.matches(request) == true && current.value.phase == ImportPhase.CANCELING) {
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
            if (current.value.request?.matches(request) == true) current.value = current.value.copy(request = null, phase = if (unavailable == null) ImportPhase.CANCELED else null, error = unavailable ?: reason, loading = true)
            refresh(true).join()
        }
    }

    /** Detach only invalidated presentation authority. A transient storage/selection error must not discard the user's pending request. */
    private fun detachStale(request: PhotoImportRequest, error: OperationError): Boolean {
        if (error.retryable || error.safeMessageKey != "library_activation_changed" || current.value.request?.matches(request) != true) return false
        current.value = current.value.copy(request = null, launchPending = false, phase = null, loading = false, error = error)
        return true
    }

    /** Reconcile a retained command with only the issuing album; no normal startup, provider callback or successor lookup. */
    internal fun refreshIssuedAlbum(issuedAt: LibraryActivation, id: DocumentId, read: suspend () -> DocumentOverview) {
        if (current.value.activation != issuedAt || current.value.selectedId != id) return
        val nav = navigation
        val token = ++metadataGeneration
        fun stillCurrent() = token == metadataGeneration && nav == navigation &&
            current.value.let { it.activation == issuedAt && it.selectedId == id }
        metadataRead?.cancel()
        current.value = current.value.copy(loading = true, error = null)
        metadataRead = run(initialize = false, onFailure = { error -> if (stillCurrent()) failed(error) }) {
            val document = read()
            currentCoroutineContext().ensureActive()
            if (!stillCurrent()) return@run
            if (document.document.documentId != id) {
                throw RepositoryFailure(OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "album_refresh_identity_changed", id.value))
            }
            current.value = current.value.let { value ->
                value.copy(document = document, loading = false,
                    selectedPage = value.selectedPage?.takeIf { page -> document.pages.any { it.pageId == page } })
            }
        }
    }

    private fun refresh(reset: Boolean, includeList: Boolean = true, debounceSearch: Boolean = false): Job {
        cancelOpeningHit()
        val token = ++metadataGeneration
        metadataRead?.cancel()
        val nav = navigation
        current.value = current.value.copy(loading = true)
        return run(onFailure = { error -> if (token == metadataGeneration && nav == navigation) failed(error) }) {
            if (debounceSearch) delay(220)
            if (includeList) reload(reset, token)
            if (token == metadataGeneration && nav == navigation) loadSelected(current.value.selectedId, nav, token)
        }.also { metadataRead = it }
    }

    private suspend fun reload(reset: Boolean, token: Long) {
        val value = current.value
        val search = (actions as? SearchableLibraryActions)?.takeIf { value.albumSearch.isNotBlank() }
        if (search != null) {
            reloadSearch(reset, token, value, search)
            return
        }
        val ordered = actions as? OrderedLibraryActions
        if (ordered != null) {
            val cursor = if (reset) null else albumCursor
            val page = ordered.listAlbums(value.albumOrder, albumLocale, cursor, 50)
            if (token == metadataGeneration) {
                albumCursor = page.next
                // A remote/concurrent writer may move an album across cursors. Avoid duplicate lazy keys even before the next explicit refresh.
                val albums = if (reset) page.albums else (current.value.documents + page.albums).distinctBy { it.documentId }
                current.value = current.value.copy(documents = owned(albums), hasMore = page.next != null)
            }
            return
        }
        val after = if (reset) null else current.value.documents.lastOrNull()?.documentId
        val documents = actions.list(after, 50)
        if (token == metadataGeneration) current.value = current.value.copy(documents = owned(if (reset) documents else current.value.documents + documents), hasMore = documents.size == 50)
    }

    private suspend fun reloadSearch(reset: Boolean, token: Long, value: LibraryState, search: SearchableLibraryActions) {
        val firstAlbum = reset || retryFirstAlbumPage
        val afterAlbum = if (firstAlbum) null else albumCursor
        if (firstAlbum || afterAlbum != null) try {
            val found = search.searchAlbums(AlbumSearchQuery(value.albumSearch, searchZone), value.albumOrder, albumLocale, afterAlbum, 50)
            if (token != metadataGeneration) return
            albumCursor = found.next
            retryFirstAlbumPage = false
            val albums = (if (firstAlbum) emptyList() else current.value.documents) + found.albums
            current.value = current.value.copy(documents = owned(albums.distinctBy { it.documentId }), albumSearchUnavailable = false)
        } catch (canceled: CancellationException) { throw canceled }
        catch (_: Exception) {
            if (token != metadataGeneration) return
            retryFirstAlbumPage = firstAlbum
            current.value = current.value.copy(documents = if (reset) emptyList() else current.value.documents, albumSearchUnavailable = true)
        }
        reloadSearchPages(reset, value, actions as? SearchablePageActions) { token == metadataGeneration }
    }

    private suspend fun refreshPageWindow(query: String, search: SearchablePageActions, currentRead: () -> Boolean) {
        val hits = mutableListOf<HomePageSearchHit>()
        val extent = pageWindows.coerceAtLeast(1)
        var next: (suspend () -> HomePageSearchPage)? = { search.searchPages(query) }
        var windows = 0
        try {
            while (windows < extent) {
                val read = next ?: break
                val found = read()
                currentCoroutineContext().ensureActive()
                if (!currentRead()) return
                hits += found.hits
                next = found.next
                ++windows
            }
        } catch (canceled: CancellationException) { throw canceled }
        catch (_: Exception) {
            // The current window and its cursor remain usable; exact hit revalidation still controls navigation.
            return
        }
        if (currentRead()) publishPageWindow(hits, next, windows)
    }

    private fun publishPageWindow(hits: List<HomePageSearchHit>, next: (suspend () -> HomePageSearchPage)?, windows: Int) {
        pageCursor = next
        pageWindows = windows
        current.value = current.value.copy(pageMatches = owned(hits.distinctBy { it.document to it.page }),
            pageSearchUnavailable = false, hasMore = retryFirstAlbumPage || albumCursor != null || next != null)
    }

    private suspend fun reloadSearchPages(reset: Boolean, value: LibraryState, pageActions: SearchablePageActions?, currentRead: () -> Boolean) {
        val next: (suspend () -> HomePageSearchPage)? = if (reset) pageActions?.let { { it.searchPages(value.albumSearch) } } else pageCursor
        if (next != null) try {
            val found = next()
            currentCoroutineContext().ensureActive()
            if (!currentRead()) return
            val hits = (if (reset) emptyList() else current.value.pageMatches) + found.hits
            publishPageWindow(hits, found.next, if (reset) 1 else pageWindows + 1)
        } catch (canceled: CancellationException) { throw canceled }
        catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            if (!currentRead()) return
            pageCursor = next
            if (reset) pageWindows = 0
            current.value = current.value.copy(pageMatches = if (reset) emptyList() else current.value.pageMatches, pageSearchUnavailable = true)
        }
        if (currentRead()) current.value = current.value.copy(hasMore = retryFirstAlbumPage || albumCursor != null || pageCursor != null)
    }

    private suspend fun loadSelected(id: DocumentId?, nav: Long, token: Long) {
        val issuedAt = current.value.activation
        fun stillCurrent() = token == metadataGeneration && nav == navigation &&
            current.value.let { it.selectedId == id && it.activation == issuedAt }
        val document = id?.let { if (issuedAt != null) actions.loadIssued(it, issuedAt) else actions.load(it) }
        if (!stillCurrent()) return
        val discover = id != null && document?.document?.hasPendingImport == true && current.value.request == null && !current.value.preparingImport
        val pending = if (discover) {
            (if (issuedAt != null) actions.activeIssuedImport(id, issuedAt) else actions.activeImportRequest(id))
                ?: throw RepositoryFailure(OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "pending_import_identity_missing", id.value))
        } else null
        if (stillCurrent()) {
            // Read the newest Main-confined choice after IO. A refresh started before a tap must not restore its older captured selection; membership is still revalidated against this returned overview.
            val value = current.value.copy(selectedPage = current.value.selectedPage?.takeIf { page -> document?.pages?.any { it.pageId == page } == true })
            current.value = if (pending != null && value.request == null) value.copy(document = document, loading = false, request = pending, launchPending = false, phase = ImportPhase.INTERRUPTED)
            else value.copy(document = document, loading = false)
        }
    }

    private fun run(onFailure: (OperationError) -> Unit = ::failed, start: CoroutineStart = CoroutineStart.DEFAULT,
        initialize: Boolean = true, action: suspend () -> Unit): Job = scope.launch(start = start) {
        try {
            // Scoped callbacks enter the activation-checked adapter directly; only ordinary UI startup may bootstrap an unrecorded store.
            if (initialize) initialization.withLock {
                if (!started) {
                    val opened = actions.openLibrary()
                    currentCoroutineContext().ensureActive()
                    var value = current.value.copy(recovery = opened.report, activation = opened.activation, restoringSelection = false)
                    pendingSelection?.takeIf { navigation == 0L && it.selectedAt == opened.activation }?.let {
                        value = value.copy(selectedId = it.selected, selectedPage = it.selectedPage)
                    }
                    pendingSelection = null
                    val picker = value.request
                    if (opened.activation != null && picker?.issuedAt != null && picker.issuedAt != opened.activation && value.phase == ImportPhase.INTERRUPTED) {
                        value = value.copy(request = null, phase = null, launchPending = false)
                    }
                    current.value = value
                    started = true
                }
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
