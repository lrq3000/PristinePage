package app.pristinepage.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pristinepage.ApplicationServices
import app.pristinepage.data.library.LibraryActivation
import app.pristinepage.data.model.*
import app.pristinepage.image.render.ClassicalRecipeAdmission
import app.pristinepage.ocr.sendCommittedOcrHint
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
    val undoUnconfirmed: Boolean = false,
    val undo: UndoId? = null,
    val savedDocument: DocumentId? = null,
    val savedRevision: Long? = null,
    val issuingActivation: LibraryActivation? = null,
)

internal class BatchEditViewModel(
    private val services: ApplicationServices,
    private val onPagesSaved: (LibraryActivation, DocumentId, List<PageId>) -> Unit,
    private val onUnconfirmedMutation: (LibraryActivation) -> Unit,
) : ViewModel() {
    private val mutable = MutableStateFlow(BatchHost())
    val state = mutable.asStateFlow()
    var draft: BatchEditSession? = null
        private set
    private var opening: Job? = null

    fun open(issuedAt: LibraryActivation, album: DocumentOverview, firstSelected: PageId? = null) {
        if (mutable.value.album != null || mutable.value.saving) return
        if (firstSelected != null && album.pages.none { it.pageId == firstSelected }) return
        mutable.value = BatchHost(album = album, issuingActivation = issuedAt)
        opening = viewModelScope.launch {
            try {
                val pages = services.loadDefaultBatchPages(issuedAt, album)
                currentCoroutineContext().ensureActive()
                val admission = ClassicalRecipeAdmission()
                draft = BatchEditSession(pages, { _, recipe -> admission.check(recipe, false) },
                    { document, revision, changes -> services.applyDefaultBatch(issuedAt, document, revision, changes) },
                    setOfNotNull(firstSelected))
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
                        val activation = checkNotNull(mutable.value.issuingActivation)
                        draft = null
                        mutable.value = BatchHost(undo = result.undo, savedDocument = document.documentId, savedRevision = document.documentRevision + 1,
                            issuingActivation = activation)
                        sendCommittedOcrHint(document.documentId, result.pageIds) { album, pages -> onPagesSaved(activation, album, pages) }
                    }
                    BatchEditOutcome.Unchanged -> dismiss()
                    is BatchEditOutcome.Conflict -> mutable.value = mutable.value.copy(error = OperationErrorCode.REVISION_CONFLICT)
                    is BatchEditOutcome.Failed -> mutable.value = mutable.value.copy(error = result.error.code)
                    BatchEditOutcome.NeedsRefresh -> {
                        mutable.value = mutable.value.copy(uncertain = true)
                        onUnconfirmedMutation(checkNotNull(mutable.value.issuingActivation))
                    }
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
        val document = value.savedDocument ?: return
        val issuing = value.issuingActivation ?: return
        if (value.saving) return
        mutable.value = value.copy(saving = true)
        viewModelScope.launch {
            try {
                val outcome = services.undoDefaultBatch(issuing, token, revision)
                val error = when (outcome) {
                    is DocumentMutationResult.Applied -> null
                    is DocumentMutationResult.Conflict -> OperationErrorCode.REVISION_CONFLICT
                    is DocumentMutationResult.Rejected -> outcome.error.code
                }
                mutable.value = BatchHost(error = error, savedDocument = document, issuingActivation = issuing,
                    savedRevision = (outcome as? DocumentMutationResult.Applied)?.documentRevision)
                if (outcome is DocumentMutationResult.Applied) sendCommittedOcrHint(document, outcome.affectedPageIds) { album, pages ->
                    onPagesSaved(issuing, album, pages)
                }
            } catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) {
                mutable.value = BatchHost(undoUnconfirmed = true, savedDocument = document, issuingActivation = issuing)
                onUnconfirmedMutation(issuing)
            }
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
