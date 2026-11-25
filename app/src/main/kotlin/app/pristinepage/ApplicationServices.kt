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
import app.pristinepage.data.library.DefaultLibraryOperations
import app.pristinepage.data.library.LibraryActivation
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.DocumentPage
import app.pristinepage.data.model.DocumentSummary
import app.pristinepage.data.model.EditablePage
import app.pristinepage.data.model.ImportSessionId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.PageId
import app.pristinepage.data.model.RecoveryReport
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.SaveRecipeResult
import app.pristinepage.editor.EditorIdentity
import app.pristinepage.data.files.SourceInspector
import app.pristinepage.data.repository.PrivateImportStore
import app.pristinepage.data.repository.ImportSessionLifecycle
import app.pristinepage.data.repository.AtelierAcquisition
import app.pristinepage.data.repository.AtelierDraftView
import app.pristinepage.data.repository.AtelierSavedPages
import app.pristinepage.data.repository.SourceInput
import app.pristinepage.data.repository.SavedOcrWork
import app.pristinepage.data.repository.ImportInitializationCleanup
import app.pristinepage.image.decode.AndroidSourceInspector
import app.pristinepage.image.decode.ImageAllocationBudget
import app.pristinepage.image.decode.SourceDecoder
import app.pristinepage.core.pipeline.RenderCoordinator
import app.pristinepage.image.render.DefaultRenderCoordinator
import app.pristinepage.image.render.GeometryEngine
import app.pristinepage.image.render.AppearanceEngine
import app.pristinepage.image.render.ClassicalRecipeAdmission
import app.pristinepage.image.ocr.SavedOcrPreparer
import app.pristinepage.image.preview.OriginalPreviewBitmap
import app.pristinepage.image.preview.OriginalPreviewBitmapLoader
import app.pristinepage.image.preview.OriginalPreviewLoader
import java.io.FileNotFoundException
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private var initialized: ApplicationImports? = null
    private var defaultOperations: DefaultLibraryOperations<ApplicationImports>? = null
    private data class OperationDelivery<T : Any>(val value: T?)
    private data class ReconciledPage(val target: SavedOcrWork.Target?)
    private data class ActiveImport(val batch: ImportSessionId?)

    internal suspend fun recordedDefaultActivation(): LibraryActivation? = libraryStartup.recordedDefaultActivation()

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
        initialized ?: openDefault(libraryStartup.requireDefaultActivation())
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
        (withDefaultLibrary(issuedAt) { ActiveImport(it.store.catalog.activeImport(album)) } ?: activationChanged()).batch

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

    internal suspend fun loadDefaultEditorPage(identity: EditorIdentity) =
        defaultEditorOperation(identity) { it.store.editablePages.load(identity.documentId, identity.pageId) }

    internal suspend fun saveDefaultEditorPage(identity: EditorIdentity, revision: Long, recipe: Recipe) =
        defaultEditorOperation(identity) { graph ->
            editorPage(graph, identity)
            graph.store.documents.saveRecipe(identity.pageId, revision, recipe)
        }

    internal suspend fun readDefaultEditorOcrStatus(identity: EditorIdentity, revision: Long): SavedOcrWork.PageStatus? =
        defaultEditorOperation(identity) { it.store.savedOcr.readStatus(identity.documentId, identity.pageId, revision) }

    internal suspend fun readDefaultEditorOcrText(identity: EditorIdentity, revision: Long): SavedOcrWork.ReadText? =
        defaultEditorOperation(identity) { it.store.savedOcr.readText(identity.documentId, identity.pageId, revision) }

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

    private suspend fun editorPage(graph: ApplicationImports, identity: EditorIdentity): DocumentPage =
        graph.store.catalog.load(identity.documentId).pages.singleOrNull { it.pageId == identity.pageId }
            ?: editorPageUnavailable(identity)

    private fun editorPageUnavailable(identity: EditorIdentity): Nothing =
        throw RepositoryFailure(OperationError(OperationErrorCode.SOURCE_UNAVAILABLE, false, "editor_page_unavailable", identity.pageId.value))

    /** Capture the owned, detached pixels before leaving the lease so cancellation/late refusal cannot strand their allocation. */
    private suspend fun <T : AutoCloseable> editorPixels(identity: EditorIdentity, sourceId: String, produce: suspend (ApplicationImports) -> T): T {
        return activationPixels(identity.issuedAt) { graph ->
            if (editorPage(graph, identity).sourceId.value != sourceId) editorPageUnavailable(identity)
            produce(graph)
        }
    }

    private suspend fun requireSameDefault(issuedAt: LibraryActivation) {
        if (libraryStartup.recordedDefaultActivation() != issuedAt) activationChanged()
    }

    private suspend fun <T : AutoCloseable> activationPixels(issuedAt: LibraryActivation, produce: suspend (ApplicationImports) -> T): T {
        var provisional: T? = null
        try {
            withDefaultLibrary(issuedAt) { graph ->
                provisional = produce(graph)
                requireSameDefault(issuedAt)
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
