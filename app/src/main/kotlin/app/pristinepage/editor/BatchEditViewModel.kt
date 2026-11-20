package app.pristinepage.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pristinepage.ApplicationImports
import app.pristinepage.data.model.*
import app.pristinepage.image.render.ClassicalRecipeAdmission
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class BatchHost(
    val album: DocumentOverview? = null,
    val pages: List<EditablePage> = emptyList(),
    val refresh: Int = 0,
    val saving: Boolean = false,
    val error: OperationErrorCode? = null,
    val uncertain: Boolean = false,
    val undo: UndoId? = null,
    val savedDocument: DocumentId? = null,
    val savedRevision: Long? = null,
)

internal class BatchEditViewModel(private val openGraph: suspend () -> ApplicationImports) : ViewModel() {
    private val mutable = MutableStateFlow(BatchHost())
    val state = mutable.asStateFlow()
    var draft: BatchEditSession? = null
        private set
    private var opening: Job? = null

    fun open(album: DocumentOverview) {
        if (mutable.value.album != null || mutable.value.saving) return
        mutable.value = BatchHost(album = album)
        opening = viewModelScope.launch {
            try {
                val graph = openGraph()
                val id = album.document.documentId
                val pages = album.pages.map { graph.store.editablePages.load(id, it.pageId) }
                if (pages.indices.any { i -> pages[i].documentRevision != album.document.documentRevision ||
                        pages[i].recipeRevision != album.pages[i].recipeRevision }) {
                    mutable.value = mutable.value.copy(error = OperationErrorCode.REVISION_CONFLICT)
                    return@launch
                }
                val admission = ClassicalRecipeAdmission()
                draft = BatchEditSession(pages, { _, recipe -> admission.check(recipe, false) }, graph.store.documents::applyBatchRecipes)
                mutable.value = mutable.value.copy(pages = pages)
            } catch (canceled: CancellationException) { throw canceled }
            catch (failure: RepositoryFailure) { mutable.value = mutable.value.copy(error = failure.error.code) }
            catch (_: Exception) { mutable.value = mutable.value.copy(error = OperationErrorCode.STORAGE_FAILED) }
        }
    }

    fun edit(action: BatchEditSession.() -> BatchEditRejection?) {
        if (mutable.value.saving || mutable.value.uncertain) return
        val error = draft?.action()?.error?.code
        mutable.value = mutable.value.let { it.copy(error = error, refresh = it.refresh + 1) }
    }

    fun done() {
        val session = draft ?: return
        if (mutable.value.saving || mutable.value.uncertain) return
        mutable.value = mutable.value.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                when (val result = session.done()) {
                    is BatchEditOutcome.Saved -> {
                        val document = checkNotNull(mutable.value.album).document
                        draft = null
                        mutable.value = BatchHost(undo = result.undo, savedDocument = document.documentId, savedRevision = document.documentRevision + 1)
                    }
                    BatchEditOutcome.Unchanged -> dismiss()
                    is BatchEditOutcome.Conflict -> mutable.value = mutable.value.copy(error = OperationErrorCode.REVISION_CONFLICT)
                    is BatchEditOutcome.Failed -> mutable.value = mutable.value.copy(error = result.error.code)
                    BatchEditOutcome.NeedsRefresh -> mutable.value = mutable.value.copy(uncertain = true)
                    BatchEditOutcome.Busy -> Unit
                }
            } finally { mutable.value = mutable.value.copy(saving = false) }
        }
    }

    fun cancel() {
        if (mutable.value.saving) return
        if (mutable.value.uncertain || draft?.cancel() != false) dismiss()
    }

    fun undoSaved() {
        val value = mutable.value
        val token = value.undo ?: return
        val revision = value.savedRevision ?: return
        if (value.saving) return
        mutable.value = value.copy(saving = true)
        viewModelScope.launch {
            try {
                val outcome = openGraph().store.documents.undoDocumentEdit(token, revision)
                val error = when (outcome) {
                    is DocumentMutationResult.Applied -> null
                    is DocumentMutationResult.Conflict -> OperationErrorCode.REVISION_CONFLICT
                    is DocumentMutationResult.Rejected -> outcome.error.code
                }
                mutable.value = BatchHost(error = error, savedDocument = value.savedDocument,
                    savedRevision = (outcome as? DocumentMutationResult.Applied)?.documentRevision)
            } catch (canceled: CancellationException) { throw canceled }
            catch (failure: RepositoryFailure) { mutable.value = BatchHost(error = failure.error.code, savedDocument = value.savedDocument) }
            catch (_: Exception) { mutable.value = BatchHost(error = OperationErrorCode.STORAGE_FAILED, savedDocument = value.savedDocument) }
            finally { mutable.value = mutable.value.copy(saving = false) }
        }
    }

    fun clearNotice() { if (!mutable.value.saving) mutable.value = BatchHost() }

    private fun dismiss() {
        opening?.cancel(); opening = null
        draft = null
        mutable.value = BatchHost()
    }
}
