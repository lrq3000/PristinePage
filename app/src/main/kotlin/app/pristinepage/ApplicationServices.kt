package app.pristinepage

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import app.pristinepage.capture.PhotoImportController
import app.pristinepage.data.model.RecoveryReport
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
import java.io.FileNotFoundException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** An explicit application-owned composition, not an Activity-scoped allowance or a global lookup registry. Construction opens no DB/provider; the first suspending request serializes initialization and actual recovery. A failed/canceled initialization is not cached as a clean startup. */
class ApplicationServices(context: Context, private val budget: ImageAllocationBudget, private val provider: SourceInput) {
    private val context = context.applicationContext
    private val initialization = Mutex()
    private var initialized: ApplicationImports? = null

    constructor(context: Context) : this(
        context.applicationContext,
        ApplicationImageBudget.create(checkNotNull(context.applicationContext.getSystemService(ActivityManager::class.java)).memoryClass),
        SourceInput { reference ->
            // SourceStore has already checked the exact private binding; do not expand the foreground photo route into an arbitrary file/network URI loader.
            val uri = Uri.parse(reference)
            require(uri.scheme == "content")
            context.applicationContext.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("Selected source is unavailable")
        },
    )

    suspend fun imports(): ApplicationImports = initialization.withLock {
        initialized ?: run {
            val normalizer = AndroidSourceInspector(budget)
            val store = PrivateImportStore.open(context, provider, normalizer)
            try {
                val report = store.documents.reconcile()
                currentCoroutineContext().ensureActive()
                ApplicationImports(store, PhotoImportController(store.selections, store.sources), SourceDecoder(store.committedSources, normalizer), budget, report).also { initialized = it }
            } catch (failure: Throwable) {
                ImportInitializationCleanup.close(store)
                throw failure
            }
        }
    }
}

/** One identity/lifetime for foreground import and later source consumption. Reports describe implemented recovery domains only; a blocked report is not successful provider resumption or all-domain integrity proof. */
class ApplicationImports internal constructor(val store: PrivateImportStore, val photoImports: PhotoImportController, val decoder: SourceDecoder, val budget: ImageAllocationBudget, val startupRecovery: RecoveryReport) {
    /** One queue per initialized Application graph, sharing the SAME decoded/intermediate/retained-output allowance. A val, not a factory/getter: Activity/editor consumers must not create independent processing slots or budgets. Construction opens no files and starts no work. */
    val renderer: RenderCoordinator = DefaultRenderCoordinator(decoder, GeometryEngine(budget), AppearanceEngine(budget))
}
