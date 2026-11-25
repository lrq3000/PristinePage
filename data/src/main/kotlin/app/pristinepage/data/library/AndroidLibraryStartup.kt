package app.pristinepage.data.library

import android.content.Context
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.requireOpaqueId
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Opaque issuing identity for one graph lifetime. It does not contain a path, grant, or storage capability. */
data class LibraryActivation(val libraryId: String, val number: Long) {
    init {
        requireOpaqueId(libraryId)
        require(number > 0)
    }
}

/** Startup port for the ordinary app-owned store. It can never open default storage over a known folder selection. */
class AndroidLibraryStartup(context: Context) {
    private val app = context.applicationContext

    suspend fun requireDefaultActivation(): LibraryActivation = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val selected = try {
            LocalLibrarySettingsBootstrap(app.filesDir.canonicalFile).open().read()
                ?: throw IOException("The initial library selection disappeared")
        } catch (_: IOException) { selectionUnavailable() }
        catch (_: DirectoryIteratorException) { selectionUnavailable() }
        coroutineContext.ensureActive()
        if (selected !is LocalSelectedLibraryAuthority.Selected.Default) {
            throw RepositoryFailure(OperationError(OperationErrorCode.PERMISSION_LOST, true, "library_reconnect_required", null))
        }
        selected.selection.identity.let { LibraryActivation(it.libraryId, it.activation) }
    }

    private fun selectionUnavailable(): Nothing =
        throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "library_selection_unavailable", null))
}
