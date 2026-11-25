package app.pristinepage.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pristinepage.ApplicationImports
import app.pristinepage.data.library.LibraryActivation
import app.pristinepage.data.model.*
import app.pristinepage.image.preview.RenderedPreviewBitmapLoader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EditorIdentity(val documentId: DocumentId, val pageId: PageId)
data class EditorHostState(val identity: EditorIdentity? = null, val loading: Boolean = false, val error: OperationError? = null)

/** Retains draft/history across Activity recreation, not process death. Only opaque identity is saved by the Activity; a new process performs a guarded reopen of the accepted revision. The Application alone owns the graph/decoder/budget. */
class EditorViewModel(
    private val openGraph: suspend () -> ApplicationImports,
    private val onPagesSaved: (LibraryActivation, DocumentId, List<PageId>) -> Unit,
) : ViewModel() {
    private val current = MutableStateFlow<EditorSession?>(null)
    val session: StateFlow<EditorSession?> = current.asStateFlow()
    private val host = MutableStateFlow(EditorHostState())
    val state: StateFlow<EditorHostState> = host.asStateFlow()
    private var opening: Job? = null
    private var generation = 0L

    fun open(documentId: String, pageId: String) {
        val identity = EditorIdentity(DocumentId(documentId), PageId(pageId))
        if (host.value.identity == identity && (current.value != null || opening?.isActive == true)) return
        closeEditor()
        val attempt = generation
        host.value = EditorHostState(identity, loading = true)
        opening = viewModelScope.launch {
            try {
                val graph = openGraph()
                currentCoroutineContext().ensureActive()
                if (generation != attempt) return@launch
                val editor = EditorSession(documentId, pageId, ApplicationEditorActions { graph }, graph.renderer, viewModelScope,
                     RenderedPreviewBitmapLoader(graph.budget), graph.budget, { album, pages -> onPagesSaved(graph.activation, album, pages) })
                // Construction can begin guarded metadata loading, but rendering belongs to a STARTED composition, not an invisible retained ViewModel.
                editor.setActive(false)
                current.value = editor
                host.value = EditorHostState(identity)
            } catch (canceled: CancellationException) { throw canceled }
            catch (failure: RepositoryFailure) { failed(attempt, identity, failure.error) }
            catch (_: Exception) { failed(attempt, identity, OperationError(OperationErrorCode.STORAGE_FAILED, true, "editor_open_failed", pageId)) }
        }
    }

    private fun failed(attempt: Long, identity: EditorIdentity, error: OperationError) {
        if (generation == attempt) host.value = EditorHostState(identity, error = error)
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
