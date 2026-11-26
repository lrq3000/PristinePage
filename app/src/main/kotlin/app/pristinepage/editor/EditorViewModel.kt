package app.pristinepage.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pristinepage.ApplicationServices
import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.core.pipeline.RenderCoordinator
import app.pristinepage.core.pipeline.RenderRequest
import app.pristinepage.data.library.LibraryActivation
import app.pristinepage.data.model.*
import app.pristinepage.image.preview.RenderedPreviewBitmapLoader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EditorIdentity(val documentId: DocumentId, val pageId: PageId, val issuedAt: LibraryActivation)
data class EditorHostState(val identity: EditorIdentity? = null, val loading: Boolean = false, val error: OperationError? = null)

/** Retains draft/history across Activity recreation, not process death. Only opaque identity is saved by the Activity; a new process performs a guarded reopen of the accepted revision. The Application alone owns the graph/decoder/budget. */
class EditorViewModel(
    private val services: ApplicationServices,
    private val onPagesSaved: (LibraryActivation, DocumentId, List<PageId>) -> Unit,
    private val onSelectedWriteRefused: (LibraryActivation) -> Unit = {},
    private val onActivationRefreshed: (EditorIdentity, EditorIdentity) -> Unit,
) : ViewModel() {
    constructor(services: ApplicationServices, onPagesSaved: (LibraryActivation, DocumentId, List<PageId>) -> Unit) :
        this(services, onPagesSaved, onActivationRefreshed = { _, _ -> })

    private val current = MutableStateFlow<EditorSession?>(null)
    val session: StateFlow<EditorSession?> = current.asStateFlow()
    private val host = MutableStateFlow(EditorHostState())
    val state: StateFlow<EditorHostState> = host.asStateFlow()
    private var opening: Job? = null
    private var generation = 0L

    fun open(identity: EditorIdentity, onAdmitted: () -> Unit = {}) {
        if (host.value.identity == identity && (current.value != null || opening?.isActive == true)) return
        val previous = current.value
        if (previous?.state?.value?.saving == true) return
        val previousIdentity = host.value.identity.takeIf { previous != null }
        generation = Math.addExact(generation, 1)
        opening?.cancel()
        val attempt = generation
        host.value = EditorHostState(previousIdentity ?: identity, loading = true)
        opening = viewModelScope.launch {
            try {
                // Do not change the retained selection or release the visible editor until the exact target can open.
                val location = services.storageLocation()
                val accepted = services.loadLibraryEditorPage(identity)
                currentCoroutineContext().ensureActive()
                if (generation != attempt) return@launch
                if (previous?.state?.value?.let { it.dirty || it.saving || it.confirmExit || it.exitReady } == true) {
                    host.value = EditorHostState(checkNotNull(previousIdentity))
                    return@launch
                }
                var live = identity
                lateinit var owningEditor: EditorSession
                val actions = object : SelectedEditorActions {
                    override val usesSelectedFolder = location.activation == identity.issuedAt && location.folderLabel != null
                    private var first: EditablePage? = accepted
                    override suspend fun load(documentId: DocumentId, pageId: PageId): EditablePage =
                        first?.also { first = null } ?: services.loadLibraryEditorPage(live)
                    override suspend fun save(pageId: PageId, expectedRevision: Long, recipe: Recipe): SaveRecipeResult =
                        error("The foreground editor must acknowledge the exact candidate")

                    override suspend fun prepareSave(pageId: PageId, expectedRevision: Long, recipe: Recipe): EditorSaveCandidate {
                        val issuing = live
                        fun requireOwner() {
                            if (host.value.identity != issuing || current.value !== owningEditor) {
                                throw RepositoryFailure(OperationError(OperationErrorCode.SOURCE_UNAVAILABLE, false, "library_activation_changed", pageId.value))
                            }
                        }
                        requireOwner()
                        if (usesSelectedFolder) {
                            // An in-flight neighbor read may fail on the predecessor after this save. It no longer owns the editor's host state.
                            generation = Math.addExact(generation, 1L)
                            opening?.cancel()
                            opening = null
                            host.value = EditorHostState(live)
                        }
                        val delivery = SelectedEditorSaveDelivery(issuing.issuedAt, pageId) { verified ->
                            if (host.value.identity == issuing && current.value === owningEditor) owningEditor.reportUnconfirmedSave()
                            // Refresh the catalogue without inventing an adoption when only the publication was spent.
                            onActivationRefreshed(issuing, issuing.copy(issuedAt = verified ?: issuing.issuedAt))
                        }
                        val (activation, result) = try {
                            services.saveLibraryEditorPage(issuing, expectedRevision, recipe, delivery)
                        } catch (failure: RepositoryFailure) {
                            if (usesSelectedFolder && failure.error.safeMessageKey == "library_reconnect_required") {
                                onSelectedWriteRefused(issuing.issuedAt)
                            }
                            throw failure
                        }
                        return EditorSaveCandidate(result, requireOwner = ::requireOwner, onAbandoned = delivery::abandon, onAccepted = {
                            if (activation != issuing.issuedAt) {
                                val next = issuing.copy(issuedAt = activation)
                                live = next
                                host.value = EditorHostState(next)
                                onActivationRefreshed(issuing, next)
                            }
                        })
                    }
                }
                val renderer = object : RenderCoordinator {
                    override suspend fun render(request: RenderRequest) = services.renderLibraryEditor(live, request)
                }
                val editor = EditorSession(identity.documentId.value, identity.pageId.value, actions, renderer, viewModelScope,
                     RenderedPreviewBitmapLoader(services.budget), services.budget, { album, pages -> onPagesSaved(live.issuedAt, album, pages) })
                owningEditor = editor
                // Construction can begin guarded metadata loading, but rendering belongs to a STARTED composition, not an invisible retained ViewModel.
                editor.setActive(false)
                try { onAdmitted() } catch (failure: Exception) { editor.close(); throw failure }
                current.value = editor
                host.value = EditorHostState(identity)
                previous?.close()
            } catch (canceled: CancellationException) { throw canceled }
            catch (failure: RepositoryFailure) { failed(attempt, previousIdentity ?: identity, failure.error) }
            catch (_: Exception) { failed(attempt, previousIdentity ?: identity, OperationError(OperationErrorCode.STORAGE_FAILED, true, "editor_open_failed", identity.pageId.value)) }
        }
    }

    private fun failed(attempt: Long, identity: EditorIdentity, error: OperationError) {
        if (generation != attempt) return
        current.value?.showNavigationRefusal(error)
        host.value = EditorHostState(identity, error = error)
    }

    fun closeEditor() {
        generation = Math.addExact(generation, 1)
        opening?.cancel(); opening = null
        val previous = current.value
        current.value = null
        host.value = EditorHostState()
        previous?.close()
    }

    override fun onCleared() { closeEditor() }
}
