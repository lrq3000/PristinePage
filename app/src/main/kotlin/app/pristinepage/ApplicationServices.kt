package app.pristinepage

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.pristinepage.capture.PhotoImportController
import app.pristinepage.data.library.AndroidLibraryStartup
import app.pristinepage.data.library.LibraryActivation
import app.pristinepage.data.model.RecoveryReport
import app.pristinepage.data.files.SourceInspector
import app.pristinepage.data.repository.PrivateImportStore
import app.pristinepage.data.repository.SourceInput
import app.pristinepage.data.repository.ImportInitializationCleanup
import app.pristinepage.image.decode.AndroidSourceInspector
import app.pristinepage.image.decode.ImageAllocationBudget
import app.pristinepage.image.decode.SourceDecoder
import app.pristinepage.core.pipeline.RenderCoordinator
import app.pristinepage.image.render.DefaultRenderCoordinator
import app.pristinepage.image.render.GeometryEngine
import app.pristinepage.image.render.AppearanceEngine
import app.pristinepage.image.render.ClassicalRecipeAdmission
import app.pristinepage.image.preview.OriginalPreviewBitmap
import app.pristinepage.image.preview.OriginalPreviewBitmapLoader
import app.pristinepage.image.preview.OriginalPreviewLoader
import java.io.FileNotFoundException
import java.io.File
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
        initialized ?: run {
            val activation = libraryStartup.requireDefaultActivation()
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
                ApplicationImports(store, PhotoImportController(store.selections, store.sources), SourceDecoder(store.committedSources, normalizer), budget, report, activation, decodeQueue)
                    .also { initialized = it }
            } catch (failure: Throwable) {
                ImportInitializationCleanup.close(store)
                throw failure
            }
        }
    }
}

/** One identity/lifetime for foreground import and later source consumption. Reports describe implemented recovery domains only; a blocked report is not successful provider resumption or all-domain integrity proof. */
class ApplicationImports internal constructor(val store: PrivateImportStore, val photoImports: PhotoImportController, val decoder: SourceDecoder, val budget: ImageAllocationBudget, val startupRecovery: RecoveryReport, val activation: LibraryActivation, private val decodeQueue: Mutex = Mutex()) {
    private val originals = OriginalPreviewBitmapLoader(OriginalPreviewLoader(store.committedSources, decoder, budget), budget)
    /** One queue per initialized Application graph, sharing the SAME decoded/intermediate/retained-output allowance. A val, not a factory/getter: Activity/editor consumers must not create independent processing slots or budgets. Construction opens no files and starts no work. */
    val renderer: RenderCoordinator = DefaultRenderCoordinator(decoder, GeometryEngine(budget), AppearanceEngine(budget), queue = decodeQueue)

    /** Source overlays and recipe results can be requested together, but cannot both hold a full-source decode. */
    internal suspend fun originalPreview(sourceId: String, longEdge: Int = 1024): OriginalPreviewBitmap =
        decodeQueue.withLock { originals.load(sourceId, longEdge) }
}
