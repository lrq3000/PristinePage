package app.pristinepage

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.core.pipeline.RenderRequest
import app.pristinepage.core.pipeline.RenderResult
import app.pristinepage.capture.PhotoImportController
import app.pristinepage.capture.PhotoImportKind
import app.pristinepage.capture.PhotoImportRequest
import app.pristinepage.data.library.AndroidLibraryStartup
import app.pristinepage.data.library.AndroidLibraryBackupHistory
import app.pristinepage.data.library.DefaultLibraryOperations
import app.pristinepage.data.library.LibraryActivation
import app.pristinepage.data.library.LibraryFolderExportReceipt
import app.pristinepage.data.library.LibraryFolderExportRequest
import app.pristinepage.data.library.LibraryFolderExportState
import app.pristinepage.data.model.AlbumOrder
import app.pristinepage.data.model.AlbumPage
import app.pristinepage.data.model.AlbumPosition
import app.pristinepage.data.model.BatchRecipeChange
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.DocumentMutationResult
import app.pristinepage.data.model.DocumentOverview
import app.pristinepage.data.model.DocumentPage
import app.pristinepage.data.model.DocumentSummary
import app.pristinepage.data.model.EditablePage
import app.pristinepage.data.model.ExportSettings
import app.pristinepage.data.model.ImportSessionId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.PageId
import app.pristinepage.data.model.RecoveryReport
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.SaveRecipeResult
import app.pristinepage.data.model.UndoId
import app.pristinepage.editor.EditorIdentity
import app.pristinepage.data.files.SourceInspector
import app.pristinepage.data.repository.PrivateImportStore
import app.pristinepage.data.repository.ImportSessionLifecycle
import app.pristinepage.data.repository.AtelierAcquisition
import app.pristinepage.data.repository.AlbumSearchQuery
import app.pristinepage.data.repository.AtelierDraftView
import app.pristinepage.data.repository.AtelierSavedPages
import app.pristinepage.data.repository.DocumentCatalog
import app.pristinepage.data.repository.DocumentRepository
import app.pristinepage.data.repository.EditablePages
import app.pristinepage.data.repository.SourceInput
import app.pristinepage.data.repository.SavedOcrWork
import app.pristinepage.data.repository.SavedOcrWork.TextSaveOutcome
import app.pristinepage.data.repository.ImportInitializationCleanup
import app.pristinepage.data.repository.NamedAlbumExports
import app.pristinepage.export.ExportPagePlan
import app.pristinepage.image.decode.AndroidSourceInspector
import app.pristinepage.image.decode.ImageAllocationBudget
import app.pristinepage.image.decode.SourceDecoder
import app.pristinepage.image.export.AlbumArtifactEncoder
import app.pristinepage.core.pipeline.RenderCoordinator
import app.pristinepage.core.pipeline.RenderStatus
import app.pristinepage.image.render.DefaultRenderCoordinator
import app.pristinepage.image.render.GeometryEngine
import app.pristinepage.image.render.AppearanceEngine
import app.pristinepage.image.render.ClassicalRecipeAdmission
import app.pristinepage.image.ocr.SavedOcrPreparer
import app.pristinepage.image.preview.OriginalPreviewBitmap
import app.pristinepage.image.preview.OriginalPreviewBitmapLoader
import app.pristinepage.image.preview.OriginalPreviewLoader
import app.pristinepage.image.preview.RenderedPreviewBitmap
import app.pristinepage.image.preview.RenderedPreviewBitmapLoader
import app.pristinepage.library.LibraryBackupConfirmation
import app.pristinepage.library.LibraryBackupPickerRegistration
import app.pristinepage.library.LibraryFolderGrantHandoff
import app.pristinepage.library.AtelierThumbnailLoader
import app.pristinepage.library.AlbumExportReview
import app.pristinepage.library.HomePageCursor
import app.pristinepage.library.HomePageSearchHit
import app.pristinepage.library.HomePageSearchPage
import app.pristinepage.library.LibraryStartup
import app.pristinepage.library.LibraryFolderPickerContract
import app.pristinepage.library.queryHomePages
import java.io.FileNotFoundException
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** An explicit application-owned composition, not an Activity-scoped allowance or a global lookup registry. Construction opens no DB/provider; the first suspending request serializes initialization and actual recovery. A failed/canceled initialization is not cached as a clean startup. */
class ApplicationServices(context: Context, internal val budget: ImageAllocationBudget, private val provider: SourceInput) {
    private companion object {
        val displayNames = OptionalSourceDisplayName(Dispatchers.IO.asExecutor(), timeoutMillis = 750)
    }

    private val context = context.applicationContext
    private val initialization = Mutex()
    private val atelierPhotoGate = Mutex()
    private data class AtelierPhoto(val draftId: String, val generation: Long, val request: PhotoImportRequest)
    private var atelierPhoto: AtelierPhoto? = null // One reservation in the sole unfinished Atelier draft; never restored from picker data.
    private val libraryStartup = AndroidLibraryStartup(this.context)
    // The selected reader rebuilds one bounded working graph at a time. Queue metadata and pixels before that lease, together with generation transitions.
    private val selectedLibraryTransition = Mutex()
    private val selectedDecodeQueue = Mutex()
    private var initialized: ApplicationImports? = null
    private var defaultOperations: DefaultLibraryOperations<ApplicationImports>? = null
    private data class OperationDelivery<T : Any>(val value: T?)
    private data class ReconciledPage(val target: SavedOcrWork.Target?)
    private data class ActiveImport(val batch: ImportSessionId?)

    internal suspend fun recordedDefaultActivation(): LibraryActivation? = libraryStartup.recordedDefaultActivation()

    internal suspend fun storageLocation() = libraryStartup.location()

    private suspend fun selectedLibraryScratch(): File = withContext(Dispatchers.IO) {
        val files = context.filesDir.canonicalFile
        val requested = File(files, "library-working-scratch")
        if ((!requested.isDirectory && !requested.mkdir()) || requested.canonicalFile != requested || !requested.isDirectory) {
            throw IOException("The selected library scratch directory is unavailable")
        }
        requested
    }

    internal suspend fun openLibraryPresentation(): LibraryStartup {
        val location = storageLocation()
        if (location.folderLabel == null) return imports().let { LibraryStartup(it.startupRecovery, it.activation) }
        val activation = location.activation
        val report = RecoveryReport(0, 0, 0, 0, 0, emptyList())
        if (location.needsReconnect) return LibraryStartup(report, activation, selectedFolder = true, reconnect = true)
        return try {
            withSelectedLibraryLane {
                libraryStartup.useIssuedSelectedCatalog(selectedLibraryScratch(), activation) { _, _ ->
                    LibraryStartup(report, activation, selectedFolder = true)
                }
            }
        } catch (failure: RepositoryFailure) {
            if (failure.error.safeMessageKey != "library_reconnect_required") throw failure
            LibraryStartup(report, activation, selectedFolder = true, reconnect = true)
        }
    }

    internal suspend fun inspectLibraryFolder(activation: LibraryActivation, tree: Uri): AndroidLibraryStartup.FolderPreview =
        withSelectedLibraryLane { libraryStartup.prepareFolder(selectedLibraryScratch(), activation, tree) }

    internal suspend fun reconnectLibrary(activation: LibraryActivation, tree: Uri): LibraryActivation =
        withSelectedLibraryLane { libraryStartup.reconnectSelectedFolder(selectedLibraryScratch(), activation, tree) }

    internal suspend fun returnToDefaultLibrary(activation: LibraryActivation): LibraryActivation =
        withSelectedLibraryLane { libraryStartup.returnToDefault(activation) }

    internal suspend fun inspectSelectedImport(activation: LibraryActivation, source: Uri): AndroidLibraryStartup.SelectedImportPreview =
        withSelectedLibraryLane { libraryStartup.prepareSelectedImport(selectedLibraryScratch(), activation, source) }

    internal suspend fun importIntoSelected(preview: AndroidLibraryStartup.SelectedImportPreview): LibraryActivation =
        withSelectedLibraryLane { libraryStartup.importIntoSelected(selectedLibraryScratch(), preview) }

    internal suspend fun selectLibraryFolder(activation: LibraryActivation, preview: AndroidLibraryStartup.FolderPreview,
        beforePointerReplace: () -> Unit,
    ): LibraryActivation =
        withSelectedLibraryLane {
            val old = storageLocation()
            if (old.activation != activation) activationChanged()
            val scratch = selectedLibraryScratch()
            suspend fun adopt() = when (preview) {
                is AndroidLibraryStartup.FolderPreview.Existing -> libraryStartup.openVerifiedExistingFolder(scratch, preview.token, beforePointerReplace)
                is AndroidLibraryStartup.FolderPreview.Empty -> libraryStartup.startVerifiedEmptyFolder(scratch, preview, beforePointerReplace)
            }
            if (old.folderLabel != null) return@withSelectedLibraryLane adopt()
            initialization.withLock {
                requireSameDefault(activation)
                requireDefaultGraphReady()
                val graph = initialized ?: openDefault(activation)
                val oldOperations = checkNotNull(defaultOperations)
                try {
                    oldOperations.cutoverAndRetire(activation, hasLeft = { libraryStartup.recordedDefaultActivation() != activation }) {
                        graph.store.requireNoUnfinishedLibraryWork()
                        adopt()
                    } ?: throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "library_busy", null))
                } finally {
                    if (oldOperations.isRetired() && !oldOperations.hasPendingGraphClose()) {
                        initialized = null
                        defaultOperations = null
                    }
                    // An unacknowledged close retains its old owner and blocks another ordinary graph in this process.
                }
            }
        }

    internal suspend fun loadLibraryAlbum(issuedAt: LibraryActivation, document: DocumentId): DocumentOverview {
        val active = storageLocation()
        if (active.activation != issuedAt) activationChanged()
        if (active.folderLabel == null) return loadDefaultAlbum(issuedAt, document)
        return withSelectedLibraryLane {
            libraryStartup.useIssuedSelectedCatalog(selectedLibraryScratch(), issuedAt) { catalog, _ -> catalog.load(document) }
        }
    }

    internal suspend fun readLibraryHomeAlbums(issuedAt: LibraryActivation?, query: AlbumSearchQuery?, order: AlbumOrder,
        locale: Locale, after: AlbumPosition?, limit: Int,
    ): AlbumPage {
        val active = storageLocation()
        if (active.activation != issuedAt) activationChanged()
        if (active.folderLabel == null) return readDefaultHomeAlbums(issuedAt, query, order, locale, after, limit)
        return withSelectedLibraryLane {
            libraryStartup.useIssuedSelectedCatalog(selectedLibraryScratch(), active.activation) { _, albums ->
                if (query == null) albums.listAlbums(order, locale, after, limit)
                else albums.searchAlbums(query, order, locale, after, limit)
            }
        }
    }

    internal suspend fun renderLibraryThumbnail(issuedAt: LibraryActivation, album: DocumentSummary, tile: DocumentPage): RenderedPreviewBitmap {
        val active = storageLocation()
        if (active.activation != issuedAt) activationChanged()
        if (active.folderLabel == null) return renderDefaultLibraryThumbnail(issuedAt, album, tile)
        return selectedLibraryPixels(issuedAt) { _, pages, decoder ->
            AtelierThumbnailLoader(pages::load, selectedRenderer(decoder), RenderedPreviewBitmapLoader(budget)).load(album, tile)
        }
    }

    internal suspend fun loadLibraryEditorPage(identity: EditorIdentity): EditablePage {
        val active = storageLocation()
        if (active.activation != identity.issuedAt) activationChanged()
        if (active.folderLabel == null) return loadDefaultEditorPage(identity)
        return withSelectedLibraryLane {
            libraryStartup.loadSelectedEditablePage(selectedLibraryScratch(), identity.issuedAt, identity.documentId, identity.pageId)
        }
    }

    internal suspend fun renderLibraryEditor(identity: EditorIdentity, request: RenderRequest): RenderResult {
        val active = storageLocation()
        if (active.activation != identity.issuedAt) activationChanged()
        if (active.folderLabel == null) return renderDefaultEditor(identity, request)
        return selectedLibraryPixels(identity.issuedAt) { _, pages, decoder ->
            val page = pages.load(identity.documentId, identity.pageId)
            if (request.pageId != page.pageId.value || request.sourceId != page.sourceId.value ||
                request.recipeRevision != page.recipeRevision) activationChanged()
            selectedRenderer(decoder).render(request)
        }
    }

    internal suspend fun originalLibraryEditor(identity: EditorIdentity, sourceId: String): OriginalPreviewBitmap {
        val active = storageLocation()
        if (active.activation != identity.issuedAt) activationChanged()
        if (active.folderLabel == null) return originalDefaultEditor(identity, sourceId)
        return selectedLibraryPixels(identity.issuedAt) { catalog, _, decoder ->
            // Once the portable catalog opens, frame admission uses the saved slot rather than exercising the accepted recipe.
            if (editorPage(catalog, identity).sourceId.value != sourceId) editorPageUnavailable(identity)
            selectedDecodeQueue.withLock { OriginalPreviewBitmapLoader(OriginalPreviewLoader(decoder, budget), budget).load(sourceId) }
        }
    }

    private fun selectedRenderer(decoder: SourceDecoder) =
        DefaultRenderCoordinator(decoder, GeometryEngine(budget), AppearanceEngine(budget), queue = selectedDecodeQueue)

    private suspend fun <T : AutoCloseable> selectedLibraryPixels(issuedAt: LibraryActivation,
        produce: suspend (DocumentCatalog, EditablePages, SourceDecoder) -> T,
    ): T {
        var provisional: T? = null
        try {
            withSelectedLibraryLane {
                libraryStartup.useIssuedSelectedPageSources(selectedLibraryScratch(), issuedAt) { catalog, pages, originals ->
                    val decoder = SourceDecoder(originals, AndroidSourceInspector(budget))
                    provisional = produce(catalog, pages, decoder)
                }
            }
            currentCoroutineContext().ensureActive()
            return checkNotNull(provisional).also { provisional = null }
        } finally { provisional?.close() }
    }

    internal suspend fun saveLibraryEditorPage(identity: EditorIdentity, revision: Long, recipe: Recipe): Pair<LibraryActivation, SaveRecipeResult> {
        val active = storageLocation()
        if (active.activation != identity.issuedAt) activationChanged()
        if (active.folderLabel == null) return identity.issuedAt to saveDefaultEditorPage(identity, revision, recipe)
        return withSelectedLibraryLane {
            when (val result = libraryStartup.advanceSelectedRecipeEdit(selectedLibraryScratch(), identity.issuedAt,
                java.util.UUID.randomUUID().toString(), identity.documentId, identity.pageId, revision, ClassicalRecipeAdmission()) { recipe }) {
                is AndroidLibraryStartup.SelectedRecipeSave.Refused -> identity.issuedAt to result.result
                is AndroidLibraryStartup.SelectedRecipeSave.Activated -> {
                    val reopen = libraryStartup.loadSelectedEditablePage(selectedLibraryScratch(), result.activation, identity.documentId, identity.pageId)
                    if (reopen.recipeRevision != result.saved.recipeRevision || reopen.recipeHash != result.saved.recipeHash) activationChanged()
                    result.activation to result.saved
                }
            }
        }
    }

    /** Only a live, launched confirmation can enter the exact Files grant and provider-write lane. */
    internal suspend fun exportConfirmedDefaultLibrary(
        delivery: LibraryBackupPickerRegistration.Delivery,
    ): LibraryFolderExportReceipt? {
        if (!delivery.isPending()) return null
        val ticket = delivery.ticket
        val selection = delivery.selection
        if (selection !is LibraryFolderPickerContract.Picked || !selection.grant.returnedWrite) {
            delivery.cancel()
            return null
        }
        val issue = ticket.issuedAt
        try {
            return withDefaultLibrary(issue) { graph ->
                if (!delivery.consume(libraryStartup.recordedDefaultActivation())) return@withDefaultLibrary EditorValue(null)
                // Spend authority before checking the destination. The live backup never acquires a persistent grant.
                AndroidLibraryBackupHistory(context).preflightNew(ticket.operationId, issue)
                val exact = withContext(Dispatchers.IO) {
                    currentCoroutineContext().ensureActive()
                    LibraryFolderGrantHandoff(context).resolveLiveWrite(selection)
                }
                if (exact.toString() != selection.uri.toString()) throw IOException("The export destination identity changed")
                requireSameDefault(issue)
                val request = LibraryFolderExportRequest.bindLiveFilesResult(ticket.operationId, issue, exact, returnedModes = 3)
                val receipt = graph.store.exportLibraryTo(request)
                requireSameDefault(issue)
                EditorValue(receipt)
            }?.value
        } finally { delivery.cancel() }
    }

    internal suspend fun inspectConfirmedDefaultBackup(ticket: LibraryBackupConfirmation.Ticket): LibraryFolderExportState =
        AndroidLibraryBackupHistory(context).inspect(ticket.operationId, ticket.issuedAt)

    internal suspend fun discoverDefaultBackups(issuedAt: LibraryActivation) =
        AndroidLibraryBackupHistory(context).discoverActive(issuedAt)

    internal suspend fun acknowledgeConfirmedDefaultBackup(ticket: LibraryBackupConfirmation.Ticket, receipt: LibraryFolderExportReceipt) {
        if (ticket.operationId != receipt.operationId) throw IOException("This backup does not match the retained confirmation")
        AndroidLibraryBackupHistory(context).acknowledge(receipt, ticket.issuedAt)
    }

    internal suspend fun archiveAcknowledgedDefaultBackup(ticket: LibraryBackupConfirmation.Ticket): Boolean =
        AndroidLibraryBackupHistory(context).archiveAcknowledged(ticket.operationId, ticket.issuedAt)

    constructor(context: Context) : this(
        context.applicationContext,
        ApplicationImageBudget.create(checkNotNull(context.applicationContext.getSystemService(ActivityManager::class.java)).memoryClass),
        object : SourceInput {
            private val resolver = context.applicationContext.contentResolver

            override fun open(boundReference: String): java.io.InputStream {
                // SourceStore has already checked the exact private binding; do not expand the foreground photo route into an arbitrary file/network URI loader.
                val uri = Uri.parse(boundReference)
                require(uri.scheme == "content")
                return resolver.openInputStream(uri) ?: throw FileNotFoundException("Selected source is unavailable")
            }

            override suspend fun suggestedDisplayName(boundReference: String): String? {
                val uri = Uri.parse(boundReference)
                if (uri.scheme != "content" || uri.authority == "${context.packageName}.capture-staging") return null
                return displayNames.read {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.count != 1 || !it.moveToFirst()) return@use null
                        val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index < 0 || it.getType(index) != android.database.Cursor.FIELD_TYPE_STRING) return@use null
                        it.getString(index)?.takeIf { name -> name.length <= 160 }
                    }
                }
            }
        },
    )

    suspend fun imports(): ApplicationImports = initialization.withLock {
        val expected = libraryStartup.requireDefaultActivation()
        requireDefaultGraphReady()
        initialized?.takeIf { it.activation == expected } ?: openDefault(expected)
    }

    private fun requireDefaultGraphReady() {
        if (defaultOperations?.hasPendingGraphClose() == true) {
            throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, false, "library_graph_close_unconfirmed", null))
        }
    }

    /** One lock order for folder operations: transition, then inspect the Default owner. An uncertain close fences every successor, not only Default imports. */
    private suspend fun <T> withSelectedLibraryLane(operation: suspend () -> T): T = selectedLibraryTransition.withLock {
        initialization.withLock { requireDefaultGraphReady() }
        operation()
    }

    internal suspend fun reconcileDefaultOcrBatch(
        issuedAt: LibraryActivation, configuration: OcrConfiguration?, after: SavedOcrWork.Continuation?, limit: Int,
    ): SavedOcrWork.Reconciliation? = withDefaultLibrary(issuedAt) { graph ->
        graph.store.savedOcr.reconcile(configuration, after, limit)
    }

    /** A refused lease is not an admitted Room lookup with no pending target. The scheduler must leave a refused pass incomplete. */
    internal suspend fun reconcileDefaultOcrPage(
        issuedAt: LibraryActivation, document: DocumentId, page: PageId, configuration: OcrConfiguration?,
    ): SavedOcrWork.Target? {
        val observed = withDefaultLibrary(issuedAt) { graph ->
            ReconciledPage(graph.store.savedOcr.reconcilePage(document, page, configuration))
        } ?: throw RepositoryFailure(OperationError(OperationErrorCode.SOURCE_UNAVAILABLE, false, "library_activation_changed", null))
        return observed.target
    }

    internal suspend fun recognizeDefaultOcrPage(
        issuedAt: LibraryActivation, target: SavedOcrWork.Target, sessions: SavedOcrPreparer.SessionFactory,
    ): SavedOcrWork.Outcome = withDefaultLibrary(issuedAt) { graph ->
        graph.store.savedOcr.execute(target, SavedOcrPreparer(graph.renderer, graph.budget, sessions))
    } ?: SavedOcrWork.Outcome.OBSOLETE

    internal suspend fun reserveDefaultPhotoBatch(issuedAt: LibraryActivation, album: DocumentId, revision: Long): ImportSessionId =
        withDefaultLibrary(issuedAt) { it.store.imports.beginImport(album, revision, null).parentId } ?: activationChanged()

    internal suspend fun findDefaultPhotoBatch(issuedAt: LibraryActivation, album: DocumentId): ImportSessionId? =
        (withDefaultLibrary(issuedAt) {
            val batch = it.store.catalog.activeImport(album)
            requireSameDefault(issuedAt)
            ActiveImport(batch)
        } ?: activationChanged()).batch

    internal data class AtelierOpening(val draft: AtelierDraftView, val needsResume: Boolean)

    internal suspend fun openDefaultAtelier(issuedAt: LibraryActivation, title: String, target: DocumentSummary?): AtelierOpening =
        useDefaultAtelier(issuedAt) { atelier, _ ->
            val pending = atelier.pending()
            if (pending != null) AtelierOpening(pending, true)
            else AtelierOpening(atelier.create(title, target?.documentId, target?.documentRevision), false)
        }

    internal suspend fun reserveDefaultAtelier(issuedAt: LibraryActivation, draft: AtelierDraftView, kind: PhotoImportKind) =
        atelierPhotoGate.withLock {
            useDefaultAtelier(issuedAt) { atelier, _ ->
                PhotoImportRequest(atelier.begin(draft, camera = kind == PhotoImportKind.CAMERA).value, kind, issuedAt)
                    .also { atelierPhoto = AtelierPhoto(draft.id, draft.generation, it) }
            }
        }

    internal suspend fun refreshDefaultAtelier(issuedAt: LibraryActivation, draft: AtelierDraftView) =
        useDefaultAtelier(issuedAt) { atelier, _ -> atelier.refresh(draft) }

    internal suspend fun finishDefaultAtelier(issuedAt: LibraryActivation, draft: AtelierDraftView) =
        useDefaultAtelier(issuedAt) { atelier, _ -> atelier.finish(draft) }

    internal suspend fun navigateDefaultAtelier(issuedAt: LibraryActivation, draft: AtelierDraftView, page: PageId, step: Int) =
        useDefaultAtelier(issuedAt) { atelier, _ -> atelier.navigate(draft, page, step) }

    internal suspend fun addToDefaultAtelier(issuedAt: LibraryActivation, draft: AtelierDraftView) =
        useDefaultAtelier(issuedAt) { atelier, _ -> atelier.addMore(draft) }

    internal suspend fun abandonDefaultAtelier(issuedAt: LibraryActivation, draft: AtelierDraftView) =
        useDefaultAtelier(issuedAt) { atelier, _ -> atelier.abandon(draft) }

    internal suspend fun loadDefaultAtelierPage(issuedAt: LibraryActivation, draft: AtelierDraftView, page: PageId) =
        useDefaultAtelier(issuedAt) { atelier, _ -> atelier.load(draft, page) }

    internal suspend fun checkpointDefaultAtelier(issuedAt: LibraryActivation, draft: AtelierDraftView, page: PageId, revision: Long, recipe: Recipe) =
        useDefaultAtelier(issuedAt) { atelier, renderer -> atelier.checkpoint(draft, page, revision, recipe, renderer) }

    internal class AtelierSave(
        val issuedAt: LibraryActivation, val draft: AtelierDraftView, reviewed: List<EditablePage>,
    ) {
        val reviewed: List<EditablePage> = java.util.Collections.unmodifiableList(reviewed.toList())
    }

    internal suspend fun freezeDefaultAtelierSave(issuedAt: LibraryActivation, draft: AtelierDraftView, selected: EditablePage): AtelierSave =
        useDefaultAtelier(issuedAt) { atelier, _ ->
            if (!draft.editing || draft.step != 2 || draft.selected != selected.pageId) atelierReviewChanged()
            val ordered = draft.pages.map { item ->
                val live = atelier.load(draft, item.pageId)
                val isSelected = item.pageId == selected.pageId
                val revision = if (isSelected) selected.recipeRevision else item.recipeRevision
                if (live.sourceId != item.sourceId || live.recipeRevision != revision ||
                    isSelected && (live.documentId != selected.documentId || live.recipeHash != selected.recipeHash)) atelierReviewChanged()
                live
            }
            if (ordered.none { it.pageId == selected.pageId }) atelierReviewChanged()
            AtelierSave(issuedAt, draft, ordered)
        }

    internal suspend fun saveDefaultAtelier(input: AtelierSave): AtelierSavedPages =
        useDefaultAtelier(input.issuedAt) { atelier, renderer -> atelier.save(input.draft, input.reviewed, renderer) }

    internal suspend fun recoverDefaultAtelierSave(input: AtelierSave): AtelierSavedPages? =
        useDefaultAtelier(input.issuedAt) { atelier, _ -> EditorValue(atelier.inspectSaved(input.draft, input.reviewed)) }.value

    private fun atelierReviewChanged(): Nothing =
        throw RepositoryFailure(OperationError(OperationErrorCode.REVISION_CONFLICT, false, "draft_preview_changed", null))

    /** The wizard and its retained editor receive only detached values; raw ports stay within the lease. */
    private suspend fun <T : Any> useDefaultAtelier(issuedAt: LibraryActivation, operation: suspend (AtelierAcquisition, RenderCoordinator) -> T): T =
        withDefaultLibrary(issuedAt) { graph ->
            val value = operation(graph.store.atelier, graph.renderer)
            try { requireSameDefault(issuedAt) } catch (failure: RepositoryFailure) {
                if (value !is AtelierSavedPages) throw failure
                throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, false, "atelier_save_delivery_unconfirmed", null))
            }
            value
        } ?: activationChanged()

    internal suspend fun useDefaultAtelierPhoto(issuedAt: LibraryActivation, draft: AtelierDraftView, request: PhotoImportRequest,
        allowAlreadySettled: Boolean = false,
        route: suspend (PhotoImportController, ImportSessionLifecycle) -> Unit,
    ): AtelierDraftView = atelierPhotoGate.withLock {
        if (request.issuedAt != issuedAt) activationChanged()
        withDefaultLibrary(issuedAt) { graph ->
            val owner = atelierPhoto
            if (request.canDeliver && (owner == null || owner.request !== request || owner.draftId != draft.id || owner.generation != draft.generation)) {
                atelierPhotoExpired()
            }
            val current = graph.store.atelier.refresh(draft)
            if (current.generation != draft.generation) atelierPhotoExpired()
            if (current.pending == null && allowAlreadySettled) {
                requireSameDefault(issuedAt)
                return@withDefaultLibrary current // A previous command already settled it; no provider, cancellation or cleanup replay.
            }
            if (current.pending != request.session) atelierPhotoExpired()
            route(graph.photoImports, graph.store.lifecycle)
            val observed = graph.store.atelier.refresh(draft)
            if (observed.generation != draft.generation) atelierPhotoExpired()
            requireSameDefault(issuedAt)
            observed
        } ?: activationChanged()
    }

    private fun atelierPhotoExpired(): Nothing =
        throw RepositoryFailure(OperationError(OperationErrorCode.SESSION_EXPIRED, false, "photo_request_expired", null))

    internal suspend fun renderDefaultAtelier(issuedAt: LibraryActivation, draft: AtelierDraftView, request: RenderRequest): RenderResult =
        activationPixels(issuedAt) { graph ->
            val live = graph.store.atelier.load(draft, PageId(request.pageId))
            if (request.sourceId != live.sourceId.value) draftSourceUnavailable()
            graph.renderer.render(request)
        }

    internal suspend fun originalDefaultAtelier(issuedAt: LibraryActivation, draft: AtelierDraftView, sourceId: String, longEdge: Int): OriginalPreviewBitmap =
        activationPixels(issuedAt) { graph ->
            val live = graph.store.atelier.refresh(draft)
            if (live.generation != draft.generation || live.pages.none { it.sourceId.value == sourceId }) draftSourceUnavailable()
            graph.originalPreview(sourceId, longEdge)
        }

    private fun draftSourceUnavailable(): Nothing =
        throw RepositoryFailure(OperationError(OperationErrorCode.SOURCE_UNAVAILABLE, false, "draft_source_unavailable", null))

    internal suspend fun loadDefaultBatchPages(issuedAt: LibraryActivation, album: DocumentOverview): List<EditablePage> =
        withDefaultLibrary(issuedAt) { graph ->
            val id = album.document.documentId
            suspend fun requireAlbum() {
                val live = graph.store.catalog.load(id)
                if (live.document.documentRevision != album.document.documentRevision || live.pages != album.pages) batchChanged()
            }
            requireAlbum()
            val pages = album.pages.map { graph.store.editablePages.load(id, it.pageId) }
            if (pages.indices.any { i ->
                    val page = pages[i]
                    val expected = album.pages[i]
                    page.documentRevision != album.document.documentRevision ||
                        page.recipeRevision != expected.recipeRevision || page.sourceId != expected.sourceId
                }) batchChanged()
            requireSameDefault(issuedAt)
            requireAlbum()
            requireSameDefault(issuedAt)
            pages
        } ?: activationChanged()

    internal suspend fun loadDefaultAlbum(issuedAt: LibraryActivation, album: DocumentId): DocumentOverview =
        readDefaultHome(issuedAt) { it.store.catalog.load(album) }

    internal suspend fun createDefaultAlbum(issuedAt: LibraryActivation?, title: String): DocumentId =
        withDefaultLibrary(issuedAt ?: activationChanged()) { it.store.documents.createDocument(title) } ?: activationChanged()

    internal suspend fun renameDefaultAlbum(issuedAt: LibraryActivation?, album: DocumentId, revision: Long, title: String): DocumentMutationResult =
        withDefaultLibrary(issuedAt ?: activationChanged()) { it.store.documents.renameDocument(album, revision, title) } ?: activationChanged()

    internal suspend fun loadDefaultAlbumExport(issuedAt: LibraryActivation, review: AlbumExportReview): List<ExportPagePlan> =
        readDefaultHome(issuedAt) { graph ->
            review.pages.map {
                val page = graph.store.editablePages.load(review.documentId, it.page.pageId)
                if (page.documentRevision != review.expectedDocumentRevision || page.recipeRevision != it.page.recipeRevision ||
                    page.sourceId != it.page.sourceId || page.pageId != it.page.pageId) {
                    throw RepositoryFailure(OperationError(OperationErrorCode.REVISION_CONFLICT, false, "named_export_review_changed", null))
                }
                ExportPagePlan(page, it.page.ordinal + 1)
            }
        }

    internal suspend fun generateDefaultAlbumExport(issuedAt: LibraryActivation, review: AlbumExportReview,
        settings: ExportSettings): NamedAlbumExports.Ready = readDefaultHome(issuedAt) { graph ->
        // The renderer and original store stay paired for the entire private artifact transaction.
        val encoder = AlbumArtifactEncoder(File(context.cacheDir.canonicalFile, "album-export-scratch"), graph.store.committedSources,
            graph.renderer, graph.budget)
        graph.store.albumExports.export(review.documentId, review.expectedDocumentRevision, settings, AlbumArtifactEncoder.PROFILE, encoder)
    }

    /** Borrow the issuing port through the actual external operation and its cleanup; nothing returns a live port. */
    internal suspend fun <T : Any> useDefaultAlbumExport(issuedAt: LibraryActivation,
        operation: suspend (NamedAlbumExports, suspend () -> Unit) -> T): T =
        withDefaultLibrary(issuedAt) { graph -> operation(graph.store.albumExports) { requireSameDefault(issuedAt) } } ?: activationChanged()

    internal fun requireDefaultAlbumWriteOnIo(issuedAt: LibraryActivation) {
        if (libraryStartup.recordedDefaultActivationOnIo() != issuedAt) activationChanged()
    }

    internal suspend fun mayOpenDefaultAlbumShare(id: String, name: String, size: Long): Boolean {
        val issuedAt = recordedDefaultActivation()?.takeIf { it.number == 1L } ?: return false
        return useDefaultAlbumExport(issuedAt) { store, confirm -> store.canOpenShare(id, name, size).also { confirm() } }
    }

    internal suspend fun readDefaultHomeAlbums(issuedAt: LibraryActivation?, query: AlbumSearchQuery?, order: AlbumOrder,
        locale: Locale, after: AlbumPosition?, limit: Int): AlbumPage = readDefaultHome(issuedAt) { graph ->
        if (query == null) graph.store.albums.listAlbums(order, locale, after, limit)
        else graph.store.albumSearch.searchAlbums(query, order, locale, after, limit)
    }

    internal suspend fun readDefaultHomePage(issuedAt: LibraryActivation?, query: String): HomePageSearchPage =
        readDefaultHomePage(issuedAt ?: activationChanged(), query, null)

    private suspend fun readDefaultHomePage(issuedAt: LibraryActivation, query: String, cursor: HomePageCursor?): HomePageSearchPage =
        readDefaultHome(issuedAt) { graph -> queryHomePages(graph, query, cursor) { readDefaultHomePage(issuedAt, query, it) } }

    private suspend fun <T : Any> readDefaultHome(issuedAt: LibraryActivation?, read: suspend (ApplicationImports) -> T): T {
        val expected = issuedAt ?: activationChanged()
        return withDefaultLibrary(expected) { graph ->
            val result = read(graph)
            requireSameDefault(expected)
            result
        } ?: activationChanged()
    }

    /** An idle observer is not a graph-change lease. Its Room subscription owns only the issuing store's read lifetime; a switch must drain the foreground collector. */
    private fun <T> observeDefaultOcr(issuedAt: LibraryActivation?, observe: (SavedOcrWork) -> Flow<T>): Flow<T> = flow {
        val expected = issuedAt ?: activationChanged()
        val issuingStore = initialization.withLock {
            requireSameDefault(expected)
            requireDefaultGraphReady()
            initialized?.takeIf { it.activation == expected }?.store?.savedOcr ?: activationChanged()
        }
        observe(issuingStore).collect {
            requireSameDefault(expected)
            emit(it)
        }
    }

    internal fun observeDefaultHomeSearch(issuedAt: LibraryActivation?): Flow<Unit> =
        observeDefaultOcr(issuedAt, SavedOcrWork::observeSearchChanges)

    internal fun observeDefaultAlbumOcrTarget(issuedAt: LibraryActivation, album: DocumentId, tile: DocumentPage): Flow<SavedOcrWork.Target?> =
        observeDefaultOcr(issuedAt) { it.observePendingTarget(album, tile.pageId, tile.recipeRevision) }

    internal suspend fun openDefaultPageHit(issuedAt: LibraryActivation, hit: HomePageSearchHit): DocumentOverview? {
        if (hit.issuedAt != issuedAt) return null
        return (withDefaultLibrary(issuedAt) { graph ->
            val album = graph.store.catalog.load(hit.document)
            currentCoroutineContext().ensureActive()
            val matched = hit.belongsTo(album) && hit.stillMatches()
            requireSameDefault(issuedAt)
            EditorValue(album.takeIf { matched })
        } ?: activationChanged()).value
    }

    internal suspend fun renderDefaultLibraryThumbnail(
        issuedAt: LibraryActivation, album: DocumentSummary, tile: DocumentPage,
    ): RenderedPreviewBitmap = activationPixels(issuedAt) { graph ->
        AtelierThumbnailLoader(graph.store.editablePages::load, graph.renderer, RenderedPreviewBitmapLoader(graph.budget))
            .load(album, tile)
    }

    internal suspend fun renderDefaultBatchPreview(issuedAt: LibraryActivation, page: EditablePage, request: RenderRequest): RenderedPreviewBitmap =
        activationPixels(issuedAt, revalidate = { graph ->
            requireBatchPage(graph, page)
            requireSameDefault(issuedAt)
        }) { graph ->
            requireBatchPage(graph, page)
            if (request.pageId != page.pageId.value || request.sourceId != page.sourceId.value ||
                request.recipeRevision != page.recipeRevision) batchChanged()
            graph.renderer.render(request).use { result ->
                if (result.request != request || result.status != RenderStatus.OK) {
                    throw RepositoryFailure(OperationError(OperationErrorCode.PROCESSING_FAILED, true, "batch_preview_failed", null))
                }
                RenderedPreviewBitmapLoader(graph.budget).convert(result)
            }
        }

    private suspend fun requireBatchPage(graph: ApplicationImports, page: EditablePage) {
        val live = try { graph.store.editablePages.load(page.documentId, page.pageId) }
        catch (failure: RepositoryFailure) {
            if (failure.error.code == OperationErrorCode.SOURCE_UNAVAILABLE) batchChanged()
            throw failure
        }
        if (live != page) batchChanged()
    }

    internal suspend fun applyDefaultBatch(issuedAt: LibraryActivation, album: DocumentId, revision: Long, changes: List<BatchRecipeChange>) =
        defaultBatchMutation(issuedAt) { it.applyBatchRecipes(album, revision, changes) }

    internal suspend fun undoDefaultBatch(issuedAt: LibraryActivation, undo: UndoId, revision: Long) =
        defaultBatchMutation(issuedAt) { it.undoDocumentEdit(undo, revision) }

    private suspend fun defaultBatchMutation(issuedAt: LibraryActivation, operation: suspend (DocumentRepository) -> DocumentMutationResult): DocumentMutationResult {
        var started = false
        try {
            return withDefaultLibrary(issuedAt) { graph ->
                started = true
                val applied = operation(graph.store.documents)
                try { requireSameDefault(issuedAt) }
                catch (failure: RepositoryFailure) {
                    if (applied is DocumentMutationResult.Applied) {
                        throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, false, "batch_mutation_delivery_unconfirmed", null))
                    }
                    // Do not expose obsolete conflict details, but keep a proved no-write result distinct from an uncertain save.
                    return@withDefaultLibrary DocumentMutationResult.Rejected(failure.error)
                }
                applied
            } ?: activationChanged()
        } catch (refused: RepositoryFailure) {
            if (started) throw refused
            return DocumentMutationResult.Rejected(refused.error)
        }
    }

    private fun batchChanged(): Nothing =
        throw RepositoryFailure(OperationError(OperationErrorCode.REVISION_CONFLICT, false, "batch_snapshot_changed", null))

    internal suspend fun loadDefaultEditorPage(identity: EditorIdentity) =
        defaultEditorOperation(identity) { it.store.editablePages.load(identity.documentId, identity.pageId) }

    internal suspend fun saveDefaultEditorPage(identity: EditorIdentity, revision: Long, recipe: Recipe) =
        defaultEditorOperation(identity) { graph ->
            editorPage(graph.store.catalog, identity)
            graph.store.documents.saveRecipe(identity.pageId, revision, recipe)
        }

    internal suspend fun readDefaultEditorOcrStatus(identity: EditorIdentity, revision: Long): SavedOcrWork.PageStatus? =
        defaultEditorOperation(identity) { it.store.savedOcr.readStatus(identity.documentId, identity.pageId, revision) }

    internal suspend fun readDefaultEditorOcrText(identity: EditorIdentity, revision: Long): SavedOcrWork.ReadText? =
        defaultEditorOperation(identity) { it.store.savedOcr.readText(identity.documentId, identity.pageId, revision) }

    internal suspend fun prepareDefaultTextExport(
        issuedAt: LibraryActivation, observed: SavedOcrWork.TextExportObservation,
    ): SavedOcrWork.PreparedTextExport? = defaultTextOperation(issuedAt) { it.prepareTextExport(observed) }

    internal suspend fun recoverDefaultTextPublications(issuedAt: LibraryActivation): SavedOcrWork.TextSaveRecovery {
        // The legacy rows have no activation field. They are admissible only on the original default graph, never reattached after a return to it.
        if (issuedAt.number != 1L) throw RepositoryFailure(
            OperationError(OperationErrorCode.SOURCE_UNAVAILABLE, false, "library_text_recovery_activation_unqualified", null),
        )
        return defaultTextOperation(issuedAt, SavedOcrWork::recoverTextPublications)
    }

    /** Keep the issuing graph through picker wait, actual transfer, and the old journal's terminal write or cleanup. */
    internal suspend fun publishDefaultTextExport(
        issuedAt: LibraryActivation, prepared: SavedOcrWork.PreparedTextExport,
        choose: suspend (String) -> String?, transfer: suspend (String, ByteArray, suspend () -> Boolean) -> TextSaveOutcome,
    ): TextSaveOutcome = withDefaultLibrary(issuedAt) { graph ->
        var obsolete = false
        suspend fun admitted(): Boolean {
            if (!obsolete) obsolete = libraryStartup.recordedDefaultActivation() != issuedAt
            return !obsolete
        }
        val outcome = graph.store.savedOcr.publishPreparedText(prepared, choose = pick@{ consent ->
            if (!admitted()) return@pick null
            val target = choose(consent)
            // The old journal can close a definitely obsolete chooser with no target. Do not bind its returned URI or open a provider.
            target.takeIf { admitted() }
        }) { target, bytes ->
            if (admitted()) transfer(target, bytes, ::admitted) else TextSaveOutcome.DESTINATION_REFUSED
        }
        try {
            if (!admitted()) activationChanged()
        } catch (failure: RepositoryFailure) {
            when (outcome) {
                TextSaveOutcome.READBACK_VERIFIED, TextSaveOutcome.OUTPUT_COMPLETED, TextSaveOutcome.NEEDS_VERIFICATION, TextSaveOutcome.PARTIAL_EXTERNAL ->
                    throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, false, "library_text_output_delivery_unconfirmed", null))
                else -> throw failure
            }
        }
        outcome
    } ?: activationChanged()

    /** Preparation/recovery issue only detached opaque results from the checked graph. */
    private suspend fun <T> defaultTextOperation(issuedAt: LibraryActivation, operation: suspend (SavedOcrWork) -> T): T =
        (withDefaultLibrary(issuedAt) { graph ->
            val value = operation(graph.store.savedOcr)
            requireSameDefault(issuedAt)
            EditorValue(value)
        } ?: activationChanged()).value

    internal suspend fun rerunDefaultEditorOcr(identity: EditorIdentity, observed: SavedOcrWork.PageStatus, configuration: OcrConfiguration) =
        defaultEditorOperation(identity) { it.store.savedOcr.requestRerun(observed, configuration) }

    private data class EditorValue<out T>(val value: T)
    private suspend fun <T> defaultEditorOperation(identity: EditorIdentity, operation: suspend (ApplicationImports) -> T): T {
        val delivered = withDefaultLibrary(identity.issuedAt) { graph ->
            val value = operation(graph)
            // Withhold obsolete reads and receipts. A completed write remains in the issuing library even if the view cannot acknowledge it.
            try {
                requireSameDefault(identity.issuedAt)
            } catch (failure: RepositoryFailure) {
                if (value !is SaveRecipeResult.Saved) throw failure
                throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, false, "editor_save_delivery_unconfirmed", identity.pageId.value))
            }
            EditorValue(value)
        } ?: activationChanged()
        return delivered.value
    }

    internal suspend fun renderDefaultEditor(identity: EditorIdentity, request: RenderRequest): RenderResult =
        editorPixels(identity, request.sourceId) { graph ->
            if (request.pageId != identity.pageId.value) editorPageUnavailable(identity)
            graph.renderer.render(request)
        }

    internal suspend fun originalDefaultEditor(identity: EditorIdentity, sourceId: String): OriginalPreviewBitmap =
        editorPixels(identity, sourceId) { it.originalPreview(sourceId) }

    private suspend fun editorPage(catalog: DocumentCatalog, identity: EditorIdentity): DocumentPage =
        catalog.load(identity.documentId).pages.singleOrNull { it.pageId == identity.pageId }
            ?: editorPageUnavailable(identity)

    private fun editorPageUnavailable(identity: EditorIdentity): Nothing =
        throw RepositoryFailure(OperationError(OperationErrorCode.SOURCE_UNAVAILABLE, false, "editor_page_unavailable", identity.pageId.value))

    /** Capture the owned, detached pixels before leaving the lease so cancellation/late refusal cannot strand their allocation. */
    private suspend fun <T : AutoCloseable> editorPixels(identity: EditorIdentity, sourceId: String, produce: suspend (ApplicationImports) -> T): T {
        return activationPixels(identity.issuedAt) { graph ->
            if (editorPage(graph.store.catalog, identity).sourceId.value != sourceId) editorPageUnavailable(identity)
            produce(graph)
        }
    }

    private suspend fun requireSameDefault(issuedAt: LibraryActivation) {
        if (libraryStartup.recordedDefaultActivation() != issuedAt) activationChanged()
    }

    private suspend fun <T : AutoCloseable> activationPixels(issuedAt: LibraryActivation,
        revalidate: suspend (ApplicationImports) -> Unit = {}, produce: suspend (ApplicationImports) -> T,
    ): T {
        var provisional: T? = null
        try {
            withDefaultLibrary(issuedAt) { graph ->
                provisional = produce(graph)
                requireSameDefault(issuedAt)
                revalidate(graph)
            } ?: activationChanged()
            currentCoroutineContext().ensureActive()
            return checkNotNull(provisional).also { provisional = null }
        } finally { provisional?.close() }
    }

    /** Only the photo adapter borrows these two ports, through ingest/cancellation and camera-file cleanup. Nothing graph-backed is returned. */
    internal suspend fun useDefaultPhotoRouting(
        issuedAt: LibraryActivation?, route: suspend (PhotoImportController, ImportSessionLifecycle) -> Unit,
    ) {
        withDefaultLibrary(issuedAt ?: activationChanged()) { route(it.photoImports, it.store.lifecycle) } ?: activationChanged()
    }

    private fun activationChanged(): Nothing =
        throw RepositoryFailure(OperationError(OperationErrorCode.SOURCE_UNAVAILABLE, false, "library_activation_changed", null))

    /** Borrow only for the typed operations above; never return a graph, store, or live port from this boundary. */
    private suspend fun <T : Any> withDefaultLibrary(
        issuedAt: LibraryActivation, operation: suspend CoroutineScope.(ApplicationImports) -> T,
    ): T? {
        val operations = initialization.withLock {
            requireDefaultGraphReady()
            val graph = initialized ?: run {
                if (libraryStartup.recordedDefaultActivation() != issuedAt) return null
                openDefault(issuedAt)
            }
            if (graph.activation != issuedAt) return null
            checkNotNull(defaultOperations)
        }
        return operations.use(issuedAt) { graph ->
            OperationDelivery(
                if (libraryStartup.recordedDefaultActivation() == issuedAt) operation(graph) else null,
            )
        }?.value
    }

    private suspend fun openDefault(activation: LibraryActivation): ApplicationImports {
        val normalizer = AndroidSourceInspector(budget)
        val decodeQueue = Mutex()
        // Scope the queue to normalization, not a provider read or the surrounding ingest transaction.
        val ingress = object : SourceInspector {
            override suspend fun inspect(file: File, expectedByteSha256: String) =
                decodeQueue.withLock { normalizer.inspect(file, expectedByteSha256) }

            override suspend fun inspectForIngest(file: File, expectedByteSha256: String) =
                decodeQueue.withLock { normalizer.inspectForIngest(file, expectedByteSha256) }
        }
        val store = PrivateImportStore.open(context, provider, ingress, ClassicalRecipeAdmission())
        try {
            val report = store.documents.reconcile()
            currentCoroutineContext().ensureActive()
            return ApplicationImports(store, PhotoImportController(store.selections, store.sources), SourceDecoder(store.committedSources, normalizer), budget, report, activation, decodeQueue)
                .also {
                    defaultOperations = DefaultLibraryOperations(activation, it)
                    initialized = it
                }
        } catch (failure: Throwable) {
            ImportInitializationCleanup.close(store)
            throw failure
        }
    }
}

/** One identity/lifetime for foreground import and later source consumption. Reports describe implemented recovery domains only; a blocked report is not successful provider resumption or all-domain integrity proof. */
class ApplicationImports internal constructor(val store: PrivateImportStore, val photoImports: PhotoImportController, val decoder: SourceDecoder, val budget: ImageAllocationBudget, val startupRecovery: RecoveryReport, val activation: LibraryActivation, private val decodeQueue: Mutex = Mutex()) : AutoCloseable {
    private val originals = OriginalPreviewBitmapLoader(OriginalPreviewLoader(store.committedSources, decoder, budget), budget)
    /** One queue per initialized Application graph, sharing the SAME decoded/intermediate/retained-output allowance. A val, not a factory/getter: Activity/editor consumers must not create independent processing slots or budgets. Construction opens no files and starts no work. */
    val renderer: RenderCoordinator = DefaultRenderCoordinator(decoder, GeometryEngine(budget), AppearanceEngine(budget), queue = decodeQueue)

    /** Source overlays and recipe results can be requested together, but cannot both hold a full-source decode. */
    internal suspend fun originalPreview(sourceId: String, longEdge: Int = 1024): OriginalPreviewBitmap =
        decodeQueue.withLock { originals.load(sourceId, longEdge) }

    override fun close() = store.close()
}
