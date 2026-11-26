package app.pristinepage.editor

import app.pristinepage.data.library.LibraryActivation
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.PageId
import app.pristinepage.data.model.RepositoryFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One selected save. Writer progress is captured before returning across dispatchers; it grants no retry or delivery authority. */
internal class SelectedEditorSaveDelivery(
    private val issuedAt: LibraryActivation,
    private val page: PageId,
    private val reportUnconfirmed: (LibraryActivation?) -> Unit,
) {
    private sealed interface Progress {
        data object NotStarted : Progress
        data object Spent : Progress
        data class Verified(val activation: LibraryActivation) : Progress
    }

    @Volatile private var progress: Progress = Progress.NotStarted
    private var reported = false

    fun publicationStarted() {
        check(progress == Progress.NotStarted)
        progress = Progress.Spent
    }

    fun pointerVerified(activation: LibraryActivation) {
        check(progress == Progress.Spent && activation.libraryId == issuedAt.libraryId &&
            activation.number == Math.addExact(issuedAt.number, 1))
        progress = Progress.Verified(activation)
    }

    suspend fun <T> run(operation: suspend SelectedEditorSaveDelivery.() -> T): T {
        try {
            val result = operation()
            currentCoroutineContext().ensureActive()
            return result
        } catch (failure: Exception) {
            if (progress == Progress.NotStarted) throw failure
            abandon()
            if (failure is CancellationException) throw failure
            throw Unconfirmed(page)
        }
    }

    /** Invoke only on the foreground caller's context, even if that caller is canceled. */
    fun abandon() {
        val interrupted = progress
        if (reported || interrupted == Progress.NotStarted) return
        reported = true
        reportUnconfirmed((interrupted as? Progress.Verified)?.activation)
    }

    class Unconfirmed(page: PageId) : RepositoryFailure(
        OperationError(OperationErrorCode.STORAGE_FAILED, false, "editor_save_delivery_unconfirmed", page.value),
    )
}
