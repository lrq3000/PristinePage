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
) : ViewModel() {
    private val current = MutableStateFlow<EditorSession?>(null)
    val session: StateFlow<EditorSession?> = current.asStateFlow()
    private val host = MutableStateFlow(EditorHostState())
    val state: StateFlow<EditorHostState> = host.asStateFlow()
    private var opening: Job? = null
    private var generation = 0L

    fun open(identity: EditorIdentity, onAdmitted: () -> Unit = {}) {
        if (host.value.identity == identity && (current.value != null || opening?.isActive == true)) return
        val previous = current.value
        val previousIdentity = host.value.identity.takeIf { previous != null }
        generation = Math.addExact(generation, 1)
        opening?.cancel()
        val attempt = generation
        host.value = EditorHostState(previousIdentity ?: identity, loading = true)
        opening = viewModelScope.launch {
            try {
                // Do not change the retained selection or release the visible editor until the exact target can open.
                val accepted = services.loadDefaultEditorPage(identity)
                currentCoroutineContext().ensureActive()
                if (generation != attempt) return@launch
                if (previous?.state?.value?.let { it.dirty || it.saving || it.confirmExit || it.exitReady } == true) {
                    host.value = EditorHostState(checkNotNull(previousIdentity))
                    return@launch
                }
                val actions = object : EditorActions {
                    private var first: EditablePage? = accepted
                    override suspend fun load(documentId: DocumentId, pageId: PageId): EditablePage =
                        first?.also { first = null } ?: services.loadDefaultEditorPage(identity)
                    override suspend fun save(pageId: PageId, expectedRevision: Long, recipe: Recipe) =
                        services.saveDefaultEditorPage(identity, expectedRevision, recipe)
                }
                val renderer = object : RenderCoordinator {
                    override suspend fun render(request: RenderRequest) = services.renderDefaultEditor(identity, request)
                }
                val editor = EditorSession(identity.documentId.value, identity.pageId.value, actions, renderer, viewModelScope,
                     RenderedPreviewBitmapLoader(services.budget), services.budget, { album, pages -> onPagesSaved(identity.issuedAt, album, pages) })
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
