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
import app.pristinepage.data.repository.SearchableAlbumCatalog
import java.io.File
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.util.UUID
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

    data class Location(val activation: LibraryActivation, val folderLabel: String?, val needsReconnect: Boolean,
        val selectedRecipeWritable: Boolean = false)

    /** Read the remembered location without opening Room, acquiring a grant, or assuming that a known folder is currently usable. */
    suspend fun location(): Location {
        val state = checkNotNull(readSelection(allowBootstrap = true)).selected
        return when (state) {
            is LocalSelectedLibraryAuthority.Selected.Default -> Location(state.activation(), null, false)
            is LocalSelectedLibraryAuthority.Selected.Reconnect -> Location(state.activation(), "", true)
            is LocalSelectedLibraryAuthority.Selected.Folder -> {
                val tree = Uri.parse(state.grant.exactUri)
                val leaf = AndroidLibraryTreeRead.localTreeDocumentId(tree)?.substringAfter(':') ?: ""
                val granted = withContext(Dispatchers.IO) { canUseRecordedGrant(state.grant) }
                Location(state.activation(), leaf, !granted, selectedRecipeWritable = granted && state.grant.returnedModes == 3)
            }
        }
    }

    /** An opaque preview is tied to the issuing activation and a physical, fully verified writable Files tree. */
    class ExistingFolderPreview internal constructor(
        internal val issuing: LibraryActivation, internal val tree: Uri, internal val verified: AndroidLibraryTreeRead.Verified,
    ) {
        val albums: Int get() = verified.albums
        val scans: Int get() = verified.scans
    }

    sealed interface FolderPreview {
        class Existing internal constructor(val token: ExistingFolderPreview) : FolderPreview
        class Empty internal constructor(
            internal val issuing: LibraryActivation, internal val tree: Uri, internal val root: AndroidLibraryDescriptorAncestry.Observation,
        ) : FolderPreview {
            private val available = java.util.concurrent.atomic.AtomicBoolean(true)
            internal fun claim(): Boolean = available.compareAndSet(true, false)
        }
    }

    suspend fun prepareFolder(scratchDirectory: File, issuedAt: LibraryActivation, tree: Uri): FolderPreview {
        val (_, before) = readSelection(allowBootstrap = false) ?: folderNotSelected()
        if (before.activation() != issuedAt) activationChanged()
        val root = AndroidEmptyLibraryFolder(app, scratchDirectory).observeEmpty(tree)
        return if (root == null) FolderPreview.Existing(prepareOpenExistingFolder(scratchDirectory, issuedAt, tree))
        else FolderPreview.Empty(issuedAt, tree, root)
    }

    suspend fun prepareOpenExistingFolder(scratchDirectory: File, issuedAt: LibraryActivation, tree: Uri): ExistingFolderPreview {
        val (authority, before) = readSelection(allowBootstrap = false) ?: folderNotSelected()
        if (before.activation() != issuedAt) activationChanged()
        val reader = AndroidLibraryTreeRead(app, scratchDirectory)
        return ExistingFolderPreview(issuedAt, tree, reader.verifyRecipeEditTarget(tree) { if (authority.read() != before) activationChanged() })
    }

    /** Reconnect only the exact tree, at the recorded generation or its verified interrupted edit. */
    suspend fun reconnectSelectedFolder(
        scratchDirectory: File, issuedAt: LibraryActivation, tree: Uri, beforePointerReplace: () -> Unit = {},
    ): LibraryActivation {
        val (authority, current) = readSelection(allowBootstrap = false) ?: folderNotSelected()
        if (current.activation() != issuedAt) activationChanged()
        val selected = current as? LocalSelectedLibraryAuthority.Selected.Folder ?: reconnect()
        if (selected.grant.exactUri != tree.toString()) reconnect()
        val reader = AndroidLibraryTreeRead(app, scratchDirectory)
        return reconnectRecordedRecipe(issuedAt, verifyRemembered = {
            reader.verifyRecipeEditTarget(tree, selected.expectedGeneration) {
                if (authority.read() != selected) activationChanged()
            }
        }, recoverVerified = {
            reader.recoverInterruptedRecipeSuccessor(issuedAt, selected, beforePointerReplace)
        })
    }

    /** An explicit action, never a recovery fallback for interrupted selected-folder work. */
    suspend fun returnToDefault(issuedAt: LibraryActivation): LibraryActivation = withContext(Dispatchers.IO) {
        val (authority, before) = readSelection(allowBootstrap = false) ?: folderNotSelected()
        if (before.activation() != issuedAt || before is LocalSelectedLibraryAuthority.Selected.Default) activationChanged()
        val database = app.getDatabasePath("pristinepage.db")
        if (!java.nio.file.Files.isRegularFile(database.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("The original default database is unavailable")
        }
        val files = app.filesDir.toPath().toRealPath()
        withSelectedLibraryWriteLane(files) { lease ->
            authority.returnToDefault(before.selection) {
                coroutineContext.ensureActive()
                lease.requireCurrent(files)
                if (authority.read() != before) activationChanged()
            }.activation()
        }
    }

    class SelectedImportPreview internal constructor(
        internal val operation: String, internal val issuedAt: LibraryActivation,
        internal val destination: AndroidLibraryTreeRead.PairInput, internal val source: AndroidLibraryTreeRead.PairInput,
        val hasAlbums: Boolean,
    )

    /** No destination changes. The closed dry run authenticates both read-only inventories and the deduplicated transfer budget. */
    suspend fun prepareSelectedImport(scratchDirectory: File, issuedAt: LibraryActivation, source: Uri): SelectedImportPreview {
        val (authority, before) = readSelection(allowBootstrap = false) ?: folderNotSelected()
        if (before.activation() != issuedAt) activationChanged()
        val selected = before as? LocalSelectedLibraryAuthority.Selected.Folder ?: folderNotSelected()
        if (selected.grant.returnedModes != 3) reconnect()
        val reader = AndroidLibraryTreeRead(app, scratchDirectory)
        val tree = Uri.parse(selected.grant.exactUri)
        fun requireActive() { if (authority.read() != selected) activationChanged() }
        val target = reader.verifyRecipeEditTarget(tree, selected.expectedGeneration, ::requireActive)
        val operation = UUID.randomUUID().toString()
        val preview = RoomLibraryTreeMergePreflight(app, scratchDirectory).verify(operation,
            AndroidLibraryTreeRead.PairInput(tree, selected.expectedGeneration), AndroidLibraryTreeRead.PairInput(source), ::requireActive)
        reader.requireConfirmedRoot(target, checkNotNull(preview.plan.destination.root))
        return SelectedImportPreview(operation, issuedAt, preview.plan.destination, preview.plan.incoming, target.albums > 0)
    }

    /** The accepted merge advances a generation in the same root; the incoming Files tree is never writable here. */
    suspend fun importIntoSelected(scratchDirectory: File, preview: SelectedImportPreview): LibraryActivation = withContext(Dispatchers.IO) {
        val (authority, before) = readSelection(allowBootstrap = false) ?: folderNotSelected()
        if (before.activation() != preview.issuedAt) activationChanged()
        val selected = before as? LocalSelectedLibraryAuthority.Selected.Folder ?: reconnect()
        selected.requireExact(preview.destination.tree.toString(), checkNotNull(preview.destination.expected))
        fun requireActive() {
            coroutineContext.ensureActive()
            if (authority.read() != selected) activationChanged()
        }
        RoomSelectedLibraryMerge(app, scratchDirectory).appendAndAdopt(preview.operation, preview.destination, preview.source, ::requireActive) { published, requireLane ->
            val reader = AndroidLibraryTreeRead(app, scratchDirectory)
            val verified = reader.verifyRecipeEditTarget(preview.destination.tree, published, ::requireActive)
            reader.requireConfirmedRoot(verified, checkNotNull(preview.destination.root))
            val next = reader.openConfirmedFolder(verified, { requireLane(); requireActive() }) { generation, checkDestination ->
                 authority.advanceOrReadVerifiedFolderGeneration(selected, generation, requireCurrent = checkDestination)
            }
            requireLane()
            if (authority.read() != next) reconnect()
            next.activation()
        }
    }

    /** Select the existing root without moving, copying or altering it. The foreground caller must drain the previous graph. */
    suspend fun openVerifiedExistingFolder(scratchDirectory: File, preview: ExistingFolderPreview,
        beforePointerReplace: () -> Unit = {},
    ): LibraryActivation = selectVerifiedFolder(scratchDirectory, preview.issuing, preview.tree, beforePointerReplace) { _, _ -> preview.verified }

    suspend fun startVerifiedEmptyFolder(scratchDirectory: File, preview: FolderPreview.Empty,
        beforePointerReplace: () -> Unit = {},
    ): LibraryActivation =
        selectVerifiedFolder(scratchDirectory, preview.issuing, preview.tree, beforePointerReplace) { before, requireCurrent ->
            val previous = when (before) {
                is LocalSelectedLibraryAuthority.Selected.Default -> null
                is LocalSelectedLibraryAuthority.Selected.Folder -> Uri.parse(before.grant.exactUri)
                is LocalSelectedLibraryAuthority.Selected.Reconnect -> reconnect()
            }
            if (!preview.claim()) throw IOException("The empty-folder confirmation was already used")
            val generation = AndroidEmptyLibraryFolder(app, scratchDirectory).start(preview.tree, preview.root, previous, requireCurrent)
            val reader = AndroidLibraryTreeRead(app, scratchDirectory)
            reader.verifyRecipeEditTarget(preview.tree, generation, requireCurrent).also { reader.requireConfirmedRoot(it, preview.root) }
        }

    private suspend fun selectVerifiedFolder(scratchDirectory: File, issuing: LibraryActivation, tree: Uri,
        beforePointerReplace: () -> Unit,
        admit: suspend (LocalSelectedLibraryAuthority.Selected, () -> Unit) -> AndroidLibraryTreeRead.Verified,
    ): LibraryActivation = withContext(Dispatchers.IO) {
        val (authority, before) = readSelection(allowBootstrap = false) ?: folderNotSelected()
        if (before.activation() != issuing) activationChanged()
        if (before is LocalSelectedLibraryAuthority.Selected.Folder && before.grant.exactUri == tree.toString()) {
            throw IOException("The selected library is already active")
        }
        val files = app.filesDir.toPath().toRealPath()
        withSelectedLibraryWriteLane(files) { lease ->
            fun requireCurrent() {
                coroutineContext.ensureActive()
                lease.requireCurrent(files)
                if (authority.read() != before) activationChanged()
            }
            requireCurrent()
            val verified = admit(before, ::requireCurrent)
            val next = AndroidLibraryTreeRead(app, scratchDirectory).openConfirmedFolder(verified, ::requireCurrent) { generation, checkDestination ->
                val grant = LibraryFolderGrantRecord(UUID.randomUUID().toString(), tree.toString(), 3)
                val identity = ActiveLibraryFence.Identity(generation.libraryId, Math.addExact(issuing.number, 1))
                val offered = ActiveLibraryFence.Selection(ActiveLibraryFence.Location.Folder(grant.grantId, generation.id), identity)
                val exact = LocalSelectedLibraryAuthority.boundSelection(offered, grant, generation)
                try {
                    authority.chooseFolder(before.selection, exact, grant, generation, beforePointerReplace, checkDestination)
                } catch (failure: IOException) {
                    if (authority.read()?.selection != exact) throw failure
                }
                val retained = authority.read() as? LocalSelectedLibraryAuthority.Selected.Folder ?: reconnect()
                if (retained.selection != exact || retained.grant != grant || retained.expectedGeneration != generation) reconnect()
                retained
            }
            coroutineContext.ensureActive()
            next.activation()
        }
    }

    /** Scoped metadata startup for a selected folder. No grant, selection change, provider write or original copy is made. */
    suspend fun <T : Any> useSelectedCatalog(
        scratchDirectory: File, block: suspend CoroutineScope.(LibraryActivation, DocumentCatalog) -> T,
    ): T = useRecordedWorkingLibrary(scratchDirectory) { activation, catalog, _, _ -> block(activation, catalog) }

    suspend fun <T : Any> useIssuedSelectedCatalog(
        scratchDirectory: File, issuedAt: LibraryActivation,
        block: suspend CoroutineScope.(DocumentCatalog, SearchableAlbumCatalog) -> T,
    ): T = useRecordedWorkingLibrary(scratchDirectory, issuedAt) { _, catalog, _, _ ->
        block(catalog, catalog as SearchableAlbumCatalog)
    }

    /** Original loans and accepted editor metadata are scoped to the same activation. */
    suspend fun <T : Any> useIssuedSelectedPageSources(
        scratchDirectory: File, issuedAt: LibraryActivation,
        block: suspend CoroutineScope.(DocumentCatalog, EditablePages, ScopedCommittedOriginals) -> T,
    ): T = useRecordedWorkingLibrary(scratchDirectory, issuedAt) { _, catalog, originals, editable -> block(catalog, editable, originals) }

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
        expectedRevision, admission, edit, {}) { _, _, result, _, _ -> result }

    sealed interface SelectedRecipeSave {
        data class Activated(val activation: LibraryActivation, val saved: SaveRecipeResult.Saved, val cleanupPending: Boolean) : SelectedRecipeSave
        data class Refused(val result: SaveRecipeResult) : SelectedRecipeSave
    }

    /** A verified persistence result, not user-visible delivery. The foreground must accept it under its adopted activation after dispatch. */
    suspend fun advanceSelectedRecipeEdit(
        scratchDirectory: File, issuedAt: LibraryActivation, operation: String, document: DocumentId, page: PageId,
        expectedRevision: Long, admission: RecipeAdmission, onPublicationStarted: () -> Unit = {},
        onPointerVerified: (LibraryActivation) -> Unit = {}, edit: (Recipe) -> Recipe,
    ): SelectedRecipeSave = withSelectedRecipePublication(scratchDirectory, issuedAt, operation, document, page,
        expectedRevision, admission, edit, { onPublicationStarted() }) { reader, authority, result, lease, requirePredecessor ->
        when (result) {
            is AndroidLibraryTreeRead.SelectedRecipeEdit.Refused -> SelectedRecipeSave.Refused(result.result)
            is AndroidLibraryTreeRead.SelectedRecipeEdit.Published -> throw IOException("The active edit has no live receipt")
            is AndroidLibraryTreeRead.SelectedRecipeEdit.Completed -> {
                val (binding, _, successor) = result.receipt.intent
                val next = reader.withBoundRecipeAdoption(result, lease, requirePredecessor,
                     advance = { beforeSwap -> authority.advanceOrReadVerifiedFolderGeneration(binding, successor, requireCurrent = beforeSwap) },
                    requireAdvanced = {
                        if (authority.read() != it) reconnect()
                        // Notify the caller while the exact pointer proof is still held, before later provider checks or dispatcher delivery.
                        onPointerVerified(it.activation())
                    })
                val settled = reader.settleLiveRecipeAdoption(result, lease, next, authority)
                SelectedRecipeSave.Activated(next.activation(), result.projection, cleanupPending = !settled)
            }
        }
    }

    private suspend fun <T : Any> withSelectedRecipePublication(
        scratchDirectory: File, issuedAt: LibraryActivation, operation: String, document: DocumentId, page: PageId,
        expectedRevision: Long, admission: RecipeAdmission, edit: (Recipe) -> Recipe,
        onPublicationStarted: (LibraryGeneration) -> Unit,
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
            reader.appendBoundRecipeEdit(selected, authority, operation, document, page, expectedRevision, admission, ::requireSelected, edit, onPublicationStarted) { result, lease ->
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
            if (current != selected || !canUseRecordedGrant(selected.grant)) reconnect()
        }
        return try {
            reader.useSelectedWorkingLibrary(Uri.parse(selected.grant.exactUri), selected.expectedGeneration, ::confirmSelection) { catalog, originals, editable ->
                block(selected.activation(), catalog, originals, editable)
            }
        } catch (_: LibraryTreeUnavailable) { reconnect() }
    }

    private fun canUseRecordedGrant(grant: LibraryFolderGrantRecord): Boolean =
        AndroidLibraryTreeRead.hasTrustedLocalProvider(app) && app.contentResolver.persistedUriPermissions.any {
            it.uri.toString() == grant.exactUri && it.isReadPermission && (grant.returnedModes != 3 || it.isWritePermission)
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
    private fun activationChanged(): Nothing =
        throw RepositoryFailure(OperationError(OperationErrorCode.SOURCE_UNAVAILABLE, false, "library_activation_changed", null))
    private fun folderNotSelected(): Nothing = fail(OperationErrorCode.SOURCE_UNAVAILABLE, "library_folder_not_selected")
    private fun reconnect(): Nothing = fail(OperationErrorCode.PERMISSION_LOST, "library_reconnect_required")
    private fun fail(code: OperationErrorCode, key: String): Nothing = throw RepositoryFailure(OperationError(code, true, key, null))
}
