package app.pristinepage

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.capture.PhotoImportController
import app.pristinepage.data.library.AndroidLibraryStartup
import app.pristinepage.data.library.DefaultLibraryOperations
import app.pristinepage.data.library.LibraryActivation
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.ImportSessionId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.PageId
import app.pristinepage.data.model.RecoveryReport
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.files.SourceInspector
import app.pristinepage.data.repository.PrivateImportStore
import app.pristinepage.data.repository.ImportSessionLifecycle
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
