package app.pristinepage.data.library

import android.content.Context
import android.net.Uri
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.requireOpaqueId
import app.pristinepage.data.repository.DocumentCatalog
import java.io.File
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import kotlinx.coroutines.CoroutineScope
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

/** Reopen one durable selection without treating a known folder, or a malformed pointer, as default storage. */
class AndroidLibraryStartup(context: Context) {
    private val app = context.applicationContext
    private data class LoadedSelection(val authority: LocalSelectedLibraryAuthority, val selected: LocalSelectedLibraryAuthority.Selected)

    suspend fun requireDefaultActivation(): LibraryActivation {
        val selected = checkNotNull(readSelection(allowBootstrap = true)).selected
        if (selected !is LocalSelectedLibraryAuthority.Selected.Default) reconnect()
        return selected.activation()
    }

    /** Scoped metadata startup for a selected folder. No grant, selection change, provider write or original copy is made. */
    suspend fun <T : Any> useSelectedCatalog(
        scratchDirectory: File, block: suspend CoroutineScope.(LibraryActivation, DocumentCatalog) -> T,
    ): T {
        val (authority, loaded) = readSelection(allowBootstrap = false) ?: folderNotSelected()
        val selected = when (val recorded = loaded) {
            is LocalSelectedLibraryAuthority.Selected.Folder -> recorded
            is LocalSelectedLibraryAuthority.Selected.Reconnect -> reconnect()
            is LocalSelectedLibraryAuthority.Selected.Default -> folderNotSelected()
        }
        val reader = AndroidLibraryTreeRead(app, scratchDirectory)
        suspend fun confirmSelection() = withContext(Dispatchers.IO) {
            val current = try { authority.read() }
            catch (_: IOException) { reconnect() }
            catch (_: DirectoryIteratorException) { reconnect() }
            if (current != selected) reconnect()
        }
        return try {
            reader.useSelectedWorkingCatalog(Uri.parse(selected.grant.exactUri), selected.expectedGeneration, ::confirmSelection) { catalog ->
                block(selected.activation(), catalog)
            }
        } catch (_: LibraryTreeUnavailable) { reconnect() }
    }

    private suspend fun readSelection(allowBootstrap: Boolean): LoadedSelection? = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val selected = try {
            val bootstrap = LocalLibrarySettingsBootstrap(app.filesDir.canonicalFile)
            val authority = if (allowBootstrap) bootstrap.open() else bootstrap.openExisting() ?: return@withContext null
            LoadedSelection(authority, authority.read() ?: throw IOException("The initial library selection disappeared"))
        } catch (_: IOException) { selectionUnavailable() }
        catch (_: DirectoryIteratorException) { selectionUnavailable() }
        coroutineContext.ensureActive()
        selected
    }

    private fun LocalSelectedLibraryAuthority.Selected.activation() =
        selection.identity.let { LibraryActivation(it.libraryId, it.activation) }

    private fun selectionUnavailable(): Nothing = fail(OperationErrorCode.STORAGE_FAILED, "library_selection_unavailable")
    private fun folderNotSelected(): Nothing = fail(OperationErrorCode.SOURCE_UNAVAILABLE, "library_folder_not_selected")
    private fun reconnect(): Nothing = fail(OperationErrorCode.PERMISSION_LOST, "library_reconnect_required")
    private fun fail(code: OperationErrorCode, key: String): Nothing = throw RepositoryFailure(OperationError(code, true, key, null))
}
