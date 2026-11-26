package app.pristinepage.library

import app.pristinepage.capture.PhotoImportRequest
import app.pristinepage.capture.PhotoImportKind
import app.pristinepage.data.library.LibraryActivation
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.AlbumSearchQuery
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.ZoneId
import java.util.Locale

data class LibraryStartup(val report: RecoveryReport, val activation: LibraryActivation? = null,
    val selectedFolder: Boolean = false, val reconnect: Boolean = false, val selectedRecipeWritable: Boolean = false)

/** Immediate foreground commands against the existing Application-owned store. These ports carry no Activity, live bitmap, private path or persistable-grant promise. Real adapter delegates to the already tested repositories/controller. */
interface LibraryActions {
    suspend fun startup(): RecoveryReport
    suspend fun openLibrary(): LibraryStartup = LibraryStartup(startup())
    suspend fun list(after: DocumentId?, limit: Int): List<DocumentSummary>
    suspend fun load(id: DocumentId): DocumentOverview
    suspend fun loadIssued(id: DocumentId, issuedAt: LibraryActivation): DocumentOverview = load(id)
    suspend fun openPageHit(hit: HomePageSearchHit, issuedAt: LibraryActivation?): DocumentOverview? {
        val overview = if (issuedAt != null) loadIssued(hit.document, issuedAt) else load(hit.document)
        currentCoroutineContext().ensureActive()
        if (!hit.belongsTo(overview) || !hit.stillMatches()) return null
        currentCoroutineContext().ensureActive()
        return if (issuedAt == null) overview else loadIssued(hit.document, issuedAt).takeIf(hit::belongsTo)
    }
    suspend fun activeImport(id: DocumentId): ImportSessionId?
    suspend fun activeImportRequest(id: DocumentId): PhotoImportRequest? = activeImport(id)?.let { PhotoImportRequest.abandonmentOnly(it) }
    suspend fun activeIssuedImport(id: DocumentId, issuedAt: LibraryActivation): PhotoImportRequest? = activeImportRequest(id)
    suspend fun create(title: String): DocumentId
    suspend fun createIssued(title: String, issuedAt: LibraryActivation?): DocumentId = create(title)
    suspend fun begin(document: DocumentSummary): PhotoImportRequest
    suspend fun beginIssued(document: DocumentSummary, issuedAt: LibraryActivation?): PhotoImportRequest = begin(document)
    suspend fun deliver(request: PhotoImportRequest, selection: LibraryPhotoResult): List<IngestResult>
    suspend fun cancel(request: PhotoImportRequest)
    suspend fun rename(document: DocumentId, revision: Long, title: String): DocumentMutationResult =
        DocumentMutationResult.Rejected(OperationError(OperationErrorCode.PROVIDER_UNAVAILABLE, false, "rename_unavailable", document.value))
    suspend fun renameIssued(document: DocumentId, revision: Long, title: String, issuedAt: LibraryActivation?): DocumentMutationResult =
        rename(document, revision, title)
}

/** Opt-in so older/synthetic consumers of the plain ID-ordered catalog keep their own cursor contract. */
internal interface OrderedLibraryActions {
    suspend fun listAlbums(order: AlbumOrder, locale: Locale, after: AlbumPosition?, limit: Int, issuedAt: LibraryActivation? = null): AlbumPage
}

internal interface SearchableLibraryActions {
    suspend fun searchAlbums(query: AlbumSearchQuery, order: AlbumOrder, locale: Locale, after: AlbumPosition?, limit: Int,
        issuedAt: LibraryActivation? = null): AlbumPage
}

internal interface SearchablePageActions {
    suspend fun searchPages(query: String, issuedAt: LibraryActivation? = null): HomePageSearchPage
    fun pageSearchChanges(query: String, issuedAt: LibraryActivation? = null): Flow<Unit> = emptyFlow()
}

/** No recognized text or persistent search token. Revalidation belongs to the store that issued the hit. */
class HomePageSearchHit internal constructor(
    val document: DocumentId, val page: PageId, val albumTitle: String, val pageNumber: Int,
    internal val issuedAt: LibraryActivation? = null,
    internal val stillMatches: suspend () -> Boolean,
) {
    internal fun belongsTo(overview: DocumentOverview): Boolean =
        overview.document.documentId == document && overview.pages.any { it.pageId == page }
}

class HomePageSearchPage internal constructor(val hits: List<HomePageSearchHit>, internal val next: (suspend () -> HomePageSearchPage)?)

/** Only opaque identity may be placed in app saved state. A restored batch is not permission to relaunch a picker, reopen a URI, or infer a successful import. */
data class LibraryIdentity @JvmOverloads constructor(
    val selected: DocumentId? = null, val pending: ImportSessionId? = null, val selectedPage: PageId? = null,
    val kind: PhotoImportKind = PhotoImportKind.GALLERY, val issuedAt: LibraryActivation? = null,
    val selectedAt: LibraryActivation? = null,
) {
    init {
        require(selected != null || selectedPage == null) { "A selected page needs its document identity" }
        require(pending != null || issuedAt == null) { "A batch activation needs its batch identity" }
        require(selected != null || selectedAt == null) { "A selection activation needs its document identity" }
    }
}
/** Transient process-private handoff, never saved UI state or scheduler data. URI-specific parsing/grants stay at the Activity/Android adapter; the session owns only immediate consumption lifetime. */
sealed interface LibraryPhotoResult {
    data class Selected(val references: List<String>, val offeredReadFlags: Int) : LibraryPhotoResult
    data object Canceled : LibraryPhotoResult
    data class Rejected(val error: OperationError) : LibraryPhotoResult
}
enum class ImportPhase { WAITING_PICKER, COPYING, CANCELING, INTERRUPTED, COMPLETE, CANCELED }
data class LibraryState(
    val loading: Boolean = true,
    val documents: List<DocumentSummary> = emptyList(),
    val hasMore: Boolean = false,
    val selectedId: DocumentId? = null,
    val document: DocumentOverview? = null,
    val request: PhotoImportRequest? = null,
    val launchPending: Boolean = false,
    val preparingImport: Boolean = false,
    val phase: ImportPhase? = null,
    val resultBatch: ImportSessionId? = null,
    val results: List<IngestResult> = emptyList(),
    val recovery: RecoveryReport? = null,
    val activation: LibraryActivation? = null,
    val error: OperationError? = null,
    val selectedPage: PageId? = null,
    val albumOrder: AlbumOrder = AlbumOrder.MODIFIED,
    val renaming: Boolean = false,
    val renameResult: DocumentMutationResult? = null,
    val albumSearch: String = "",
    val canSearchAlbums: Boolean = false,
    val canSearchPages: Boolean = false,
    val pageMatches: List<HomePageSearchHit> = emptyList(),
    val pageSearchUnavailable: Boolean = false,
    val albumSearchUnavailable: Boolean = false,
    val restoringSelection: Boolean = false,
    val selectedFolder: Boolean = false,
    val reconnect: Boolean = false,
    val selectedRecipeWritable: Boolean = false,
    val recipeWriteNeedsReconnect: Boolean = false,
) {
    /** Presentation eligibility only: the selected writer checks the grant, physical root and pointer again at use. */
    internal fun canApplyRecipe(issuedAt: LibraryActivation): Boolean =
        activation == issuedAt && !reconnect && (!selectedFolder || selectedRecipeWritable && !recipeWriteNeedsReconnect)

    internal val settingsReconnectAt: LibraryActivation?
        get() = activation.takeIf { reconnect || selectedFolder && recipeWriteNeedsReconnect }

    // Keep transient search input (which can contain a private quote) out of incidental diagnostics.
    override fun toString() = "LibraryState(loading=$loading, albumCount=${documents.size}, hasMore=$hasMore, phase=$phase)"
}

sealed interface LibraryEvent {
    data class Open(val id: DocumentId) : LibraryEvent
    data class SelectPage(val id: PageId) : LibraryEvent
    data object CloseAlbum : LibraryEvent
    data class SetAlbumSort(val order: AlbumOrder, val locale: Locale) : LibraryEvent
    class SetAlbumSearch(val text: String, val zone: ZoneId) : LibraryEvent
    class OpenPageMatch(val hit: HomePageSearchHit, val open: (DocumentId, PageId) -> Unit) : LibraryEvent
    data object LeaveHomeSearch : LibraryEvent
    data class RenameAlbum(val documentId: DocumentId, val expectedDocumentRevision: Long, val title: String) : LibraryEvent
    data object ClearRenameResult : LibraryEvent
    data class NewDocument @JvmOverloads constructor(val title: String, val importAfterCreate: Boolean = false, val kind: PhotoImportKind = PhotoImportKind.GALLERY) : LibraryEvent
    data object More : LibraryEvent
    data object Refresh : LibraryEvent
    data object RefreshAlbum : LibraryEvent
    data object BeginImport : LibraryEvent
    data object BeginFiles : LibraryEvent
    data object BeginCapture : LibraryEvent
    data class PhotoResult(val request: PhotoImportRequest, val selection: LibraryPhotoResult) : LibraryEvent
    data object CancelImport : LibraryEvent
    data class PickerFailed(val request: PhotoImportRequest) : LibraryEvent
}

interface LibrarySession {
    val state: StateFlow<LibraryState>
    fun accept(event: LibraryEvent)
    fun identity(): LibraryIdentity
    fun claimPickerLaunch(batchId: ImportSessionId): Boolean
    suspend fun observePageSearch(query: String, issuedAt: LibraryActivation? = state.value.activation) = Unit
}
