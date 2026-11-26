package app.pristinepage.data.library

import android.content.Context
import android.net.Uri
import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.EditablePage
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.PageId
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.SaveRecipeResult
import app.pristinepage.data.model.requireOpaqueId
import app.pristinepage.data.repository.DocumentCatalog
import app.pristinepage.data.repository.EditablePages
import app.pristinepage.data.repository.RecipeAdmission
import app.pristinepage.data.repository.ScopedCommittedOriginals
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

    /** Work delivered after its activation ended must not bootstrap a default library or open Room. */
    suspend fun recordedDefaultActivation(): LibraryActivation? =
        (readSelection(allowBootstrap = false)?.selected as? LocalSelectedLibraryAuthority.Selected.Default)?.activation()

    /** For a synchronous byte handoff already running on IO. Never bootstrap or open Room. */
    fun recordedDefaultActivationOnIo(): LibraryActivation? =
        (readSelectionOnIo(allowBootstrap = false)?.selected as? LocalSelectedLibraryAuthority.Selected.Default)?.activation()

    /** Scoped metadata startup for a selected folder. No grant, selection change, provider write or original copy is made. */
    suspend fun <T : Any> useSelectedCatalog(
        scratchDirectory: File, block: suspend CoroutineScope.(LibraryActivation, DocumentCatalog) -> T,
    ): T = useRecordedWorkingLibrary(scratchDirectory) { activation, catalog, _, _ -> block(activation, catalog) }

    /** Admit the catalog and original loans under the same activation; finish using and close any owned results before returning. */
    suspend fun useSelectedLibrary(
        scratchDirectory: File, block: suspend CoroutineScope.(LibraryActivation, DocumentCatalog, ScopedCommittedOriginals) -> Unit,
    ) = useRecordedWorkingLibrary(scratchDirectory) { activation, catalog, originals, _ ->
        block(activation, catalog, originals)
    }

    /** Read a saved recipe and its source binding under the exact selected activation, without lending or materializing an original. */
    suspend fun loadSelectedEditablePage(
        scratchDirectory: File, issuedAt: LibraryActivation, documentId: DocumentId, pageId: PageId,
    ): EditablePage = useRecordedWorkingLibrary(scratchDirectory, issuedAt) { _, _, _, editable ->
        editable.load(documentId, pageId)
    }

    /** Durable provider publication only. The caller must still advance the exact current private binding before delivering a Save. */
    internal suspend fun publishSelectedRecipeEdit(
        scratchDirectory: File, issuedAt: LibraryActivation, operation: String, document: DocumentId, page: PageId,
        expectedRevision: Long, admission: RecipeAdmission, edit: (Recipe) -> Recipe,
    ): AndroidLibraryTreeRead.SelectedRecipeEdit = withSelectedRecipePublication(scratchDirectory, issuedAt, operation, document, page,
        expectedRevision, admission, edit) { _, _, result, _, _ -> result }

    sealed interface SelectedRecipeSave {
        data class Activated(val activation: LibraryActivation, val saved: SaveRecipeResult.Saved) : SelectedRecipeSave
        data class Refused(val result: SaveRecipeResult) : SelectedRecipeSave
    }

    /** A verified persistence result, not user-visible delivery. The foreground must accept it under its adopted activation after dispatch. */
    suspend fun advanceSelectedRecipeEdit(
        scratchDirectory: File, issuedAt: LibraryActivation, operation: String, document: DocumentId, page: PageId,
        expectedRevision: Long, admission: RecipeAdmission, edit: (Recipe) -> Recipe,
    ): SelectedRecipeSave = withSelectedRecipePublication(scratchDirectory, issuedAt, operation, document, page,
        expectedRevision, admission, edit) { reader, authority, result, lease, requirePredecessor ->
        when (result) {
            is AndroidLibraryTreeRead.SelectedRecipeEdit.Refused -> SelectedRecipeSave.Refused(result.result)
            is AndroidLibraryTreeRead.SelectedRecipeEdit.Published -> throw IOException("The active edit has no live receipt")
            is AndroidLibraryTreeRead.SelectedRecipeEdit.Completed -> {
                val (binding, _, successor) = result.receipt.intent
                val next = reader.withBoundRecipeAdoption(result, lease, requirePredecessor,
                    advance = { beforeSwap -> authority.advanceOrReadVerifiedFolderGeneration(binding, successor, beforeSwap) },
                    requireAdvanced = { if (authority.read() != it) reconnect() })
                SelectedRecipeSave.Activated(next.activation(), result.projection)
            }
        }
    }

    private suspend fun <T : Any> withSelectedRecipePublication(
        scratchDirectory: File, issuedAt: LibraryActivation, operation: String, document: DocumentId, page: PageId,
        expectedRevision: Long, admission: RecipeAdmission, edit: (Recipe) -> Recipe,
        consume: suspend (AndroidLibraryTreeRead, LocalSelectedLibraryAuthority, AndroidLibraryTreeRead.SelectedRecipeEdit,
            SelectedLibraryWriteLease, () -> Unit) -> T,
    ): T {
        val (authority, loaded) = readSelection(allowBootstrap = false) ?: folderNotSelected()
        if (loaded.activation() != issuedAt) {
            throw RepositoryFailure(OperationError(OperationErrorCode.SOURCE_UNAVAILABLE, false, "library_activation_changed", null))
        }
        val selected = when (loaded) {
            is LocalSelectedLibraryAuthority.Selected.Folder -> loaded
            is LocalSelectedLibraryAuthority.Selected.Default -> folderNotSelected()
            is LocalSelectedLibraryAuthority.Selected.Reconnect -> reconnect()
        }
        if (selected.grant.returnedModes != 3) reconnect()
        fun requireSelected() {
            if (authority.read() != selected) reconnect()
        }
        val reader = AndroidLibraryTreeRead(app, scratchDirectory)
        return try {
            reader.appendBoundRecipeEdit(selected, operation, document, page, expectedRevision, admission, ::requireSelected, edit) { result, lease ->
                consume(reader, authority, result, lease, ::requireSelected)
            }
        } catch (_: LibraryTreeUnavailable) { reconnect() }
        catch (_: ExactLibraryWriteAccessUnavailable) { reconnect() }
    }

    private suspend fun <T : Any> useRecordedWorkingLibrary(
        scratchDirectory: File, issuedAt: LibraryActivation? = null,
        block: suspend CoroutineScope.(LibraryActivation, DocumentCatalog, ScopedCommittedOriginals, EditablePages) -> T,
    ): T {
        val (authority, loaded) = readSelection(allowBootstrap = false) ?: folderNotSelected()
        if (issuedAt != null && loaded.activation() != issuedAt) {
            throw RepositoryFailure(OperationError(OperationErrorCode.SOURCE_UNAVAILABLE, false, "library_activation_changed", null))
        }
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
            reader.useSelectedWorkingLibrary(Uri.parse(selected.grant.exactUri), selected.expectedGeneration, ::confirmSelection) { catalog, originals, editable ->
                block(selected.activation(), catalog, originals, editable)
            }
        } catch (_: LibraryTreeUnavailable) { reconnect() }
    }

    private suspend fun readSelection(allowBootstrap: Boolean): LoadedSelection? = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        readSelectionOnIo(allowBootstrap).also { coroutineContext.ensureActive() }
    }

    private fun readSelectionOnIo(allowBootstrap: Boolean): LoadedSelection? = try {
        val bootstrap = LocalLibrarySettingsBootstrap(app.filesDir.canonicalFile)
        val authority = if (allowBootstrap) bootstrap.open() else bootstrap.openExisting()
        authority?.let { LoadedSelection(it, it.read() ?: throw IOException("The initial library selection disappeared")) }
    } catch (_: IOException) { selectionUnavailable() }
    catch (_: DirectoryIteratorException) { selectionUnavailable() }

    private fun LocalSelectedLibraryAuthority.Selected.activation() =
        selection.identity.let { LibraryActivation(it.libraryId, it.activation) }

    private fun selectionUnavailable(): Nothing = fail(OperationErrorCode.STORAGE_FAILED, "library_selection_unavailable")
    private fun folderNotSelected(): Nothing = fail(OperationErrorCode.SOURCE_UNAVAILABLE, "library_folder_not_selected")
    private fun reconnect(): Nothing = fail(OperationErrorCode.PERMISSION_LOST, "library_reconnect_required")
    private fun fail(code: OperationErrorCode, key: String): Nothing = throw RepositoryFailure(OperationError(code, true, key, null))
}
