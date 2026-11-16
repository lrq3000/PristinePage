package app.pristinepage.library

import app.pristinepage.capture.PhotoImportRequest
import app.pristinepage.data.model.*
import kotlinx.coroutines.flow.StateFlow

/** Immediate foreground commands against the existing Application-owned store. These ports carry no Activity, live bitmap, private path or persistable-grant promise. Real adapter delegates to the already tested repositories/controller. */
interface LibraryActions {
    suspend fun startup(): RecoveryReport
    suspend fun list(after: DocumentId?, limit: Int): List<DocumentSummary>
    suspend fun load(id: DocumentId): DocumentOverview
    suspend fun activeImport(id: DocumentId): ImportSessionId?
    suspend fun create(title: String): DocumentId
    suspend fun begin(document: DocumentSummary): PhotoImportRequest
    suspend fun deliver(request: PhotoImportRequest, selection: LibraryPhotoResult): List<IngestResult>
    suspend fun cancel(request: PhotoImportRequest)
}

/** Only opaque identity may be placed in app saved state. A restored batch is not permission to relaunch a picker, reopen a URI, or infer a successful import. */
data class LibraryIdentity(val selected: DocumentId? = null, val pending: ImportSessionId? = null)
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
    val error: OperationError? = null,
)

sealed interface LibraryEvent {
    data class Open(val id: DocumentId) : LibraryEvent
    data class NewDocument(val title: String, val importAfterCreate: Boolean = false) : LibraryEvent
    data object More : LibraryEvent
    data object Refresh : LibraryEvent
    data object BeginImport : LibraryEvent
    data class PhotoResult(val request: PhotoImportRequest, val selection: LibraryPhotoResult) : LibraryEvent
    data object CancelImport : LibraryEvent
    data class PickerFailed(val request: PhotoImportRequest) : LibraryEvent
}

interface LibrarySession {
    val state: StateFlow<LibraryState>
    fun accept(event: LibraryEvent)
    fun identity(): LibraryIdentity
    fun claimPickerLaunch(batchId: ImportSessionId): Boolean
}
