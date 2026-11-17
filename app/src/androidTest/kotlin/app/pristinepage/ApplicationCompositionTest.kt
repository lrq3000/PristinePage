package app.pristinepage

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.capture.PhotoImportController
import app.pristinepage.core.pipeline.OutputSpec
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.RenderPurpose
import app.pristinepage.core.pipeline.RenderCoordinator
import app.pristinepage.core.pipeline.RenderRequest
import app.pristinepage.core.pipeline.RenderStatus
import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.core.pipeline.FallbackPolicy
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.data.files.SourceInspector
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import app.pristinepage.image.decode.AndroidSourceInspector
import app.pristinepage.image.decode.ImageAllocationBudget
import app.pristinepage.image.decode.SourceDecoder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import java.util.UUID
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real production composition, Room/private files/owners/codec/controller. Only external provider delivery and an explicit storage-failure boundary are injected. Unique private roots and all originals/journals are retained; no grants, picker UI, OS death or physical-memory claim. */
@RunWith(AndroidJUnit4::class)
class ApplicationCompositionTest {
    private val output = OutputSpec(PixelSize(2, 2), RenderPurpose.PREVIEW)

    @Test fun orderedMixedResultsSurviveReopenWithoutReopeningAProvider() = runBlocking {
        Fixture().use { f ->
            f.open()
            val document = f.documents.createDocument("Composition mixed results")
            val batch = f.sessions.beginImport(document, 1, null)
            val refs = listOf(f.good, f.bad, f.good)
            val results = f.controller.importProviderReferences(refs, batch.parentId)
            val first = results[0] as IngestResult.Committed
            assertTrue(results[1] is IngestResult.Rejected); assertTrue(results[2] is IngestResult.Committed)
            assertNotEquals(first.pageId, (results[2] as IngestResult.Committed).pageId)
            assertEquals(listOf(f.good, f.bad, f.good), f.opened)
            val selected = f.selections.prepareSelection(batch.parentId, refs)
            assertEquals(selected.childIds, results.map { when (it) { is IngestResult.Committed -> it.sessionId; is IngestResult.Rejected -> it.sessionId; is IngestResult.Pending -> it.sessionId } })
            val original = f.committed.load(first.sourceId)
            assertArrayEquals(f.png, original.file.readBytes())
            val inventory = f.inventory()
            f.reopen(); f.forbid = true
            assertEquals(results, f.controller.importProviderReferences(refs, batch.parentId))
            f.decoder.decode(first.sourceId.value, output).use { assertArrayEquals(f.rgb, it.image.toPackedByteArray()) }
            assertEquals(inventory, f.inventory()); assertEquals(3, f.opened.size); assertEquals(0L, f.budget.usedBytes)
        }
    }

    @Test fun canceledRemainderKeepsCommittedReceiptThroughTheRealControllerAndReopen() = runBlocking {
        Fixture().use { f ->
            f.open(); val doc = f.documents.createDocument("Composition cancellation")
            val batch = f.sessions.beginImport(doc, 1, null)
            f.beforeOpen = { if (f.opened.size == 2) runBlocking { f.lifecycle.cancelBatch(batch.parentId) } }
            val refs = listOf(f.good, f.good, f.good)
            val results = f.controller.importProviderReferences(refs, batch.parentId)
            assertTrue(results[0] is IngestResult.Committed)
            for (result in results.drop(1)) assertEquals(OperationErrorCode.CANCELED, (result as IngestResult.Rejected).error.code)
            val first = results[0] as IngestResult.Committed
            val inventory = f.inventory(); f.reopen(); f.forbid = true
            assertEquals(results, f.controller.importProviderReferences(refs, batch.parentId))
            assertArrayEquals(f.png, f.committed.load(first.sourceId).file.readBytes())
            assertEquals(inventory, f.inventory()); assertEquals(2, f.opened.size)
        }
    }

    @Test fun retainedDecodedPixelsConsumeTheSameAllowanceAsSubsequentIngressAndDecode() = runBlocking {
        Fixture().use { f ->
            f.open(); val doc = f.documents.createDocument("Composition budget")
            val batch = f.sessions.beginImport(doc, 1, null)
            val first = f.controller.importProviderReferences(listOf(f.good), batch.parentId).single() as IngestResult.Committed
            f.decoder.decode(first.sourceId.value, output).use { retained ->
                assertTrue(f.budget.usedBytes > 0); val owned = f.budget.usedBytes
                f.budget.reserve(f.budget.capacityBytes - owned).use {
                    val second = f.sessions.beginImport(doc, first.documentRevision, null)
                    val rejected = f.controller.importProviderReferences(listOf(f.good), second.parentId).single() as IngestResult.Rejected
                    assertEquals(OperationErrorCode.RESOURCE_LIMIT, rejected.error.code)
                    try { f.decoder.decode(first.sourceId.value, output).close(); fail("Decode escaped shared admission") }
                    catch (failure: RepositoryFailure) { assertEquals(OperationErrorCode.RESOURCE_LIMIT, failure.error.code) }
                    assertEquals(f.budget.capacityBytes, f.budget.usedBytes); assertArrayEquals(f.rgb, retained.image.toPackedByteArray())
                }
                assertEquals(owned, f.budget.usedBytes)
            }
            assertEquals(0L, f.budget.usedBytes)
            f.decoder.decode(first.sourceId.value, output).use { assertArrayEquals(f.rgb, it.image.toPackedByteArray()) }
        }
    }

    @Test fun recoveryReportsUnsealedWorkWithoutCallingItsTemporaryProviderAgain() = runBlocking {
        Fixture().use { f ->
            val normalizer = AndroidSourceInspector(f.budget)
            var rejectInspection = true
            val inspector = SourceInspector { file, hash ->
                if (rejectInspection) throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "synthetic_inspection_io", null))
                normalizer.inspect(file, hash)
            }
            var store = privateStore(f.context, f.provider, inspector)
            try {
                val documents = property<DocumentRepository>(store, "Documents")
                val sessions = property<ImportSessionCommands>(store, "Imports")
                val selections = property<ImportSelections>(store, "Selections")
                val controller = PhotoImportController(selections, property(store, "Sources"))
                val doc = documents.createDocument("Composition unsealed recovery")
                val batch = sessions.beginImport(doc, 1, null)
                assertTrue(controller.importProviderReferences(listOf(f.good), batch.parentId).single() is IngestResult.Pending)
                (store as AutoCloseable).close(); f.forbid = true; rejectInspection = false
                store = privateStore(f.context, f.provider, inspector)
                val repository = property<DocumentRepository>(store, "Documents")
                val inventory = f.inventory()
                repeat(2) {
                    val report = repository.reconcile()
                    assertTrue(report.blocked >= 1); assertEquals(0L, report.completed); assertEquals(0L, report.resumed)
                    assertTrue(report.safeIssues.any { it.action == RecoveryAction.RESELECT })
                }
                assertEquals(1, f.opened.size); assertEquals(inventory, f.inventory())
            } finally { (store as AutoCloseable).close() }
        }
    }

    @Test fun concurrentConsumersReceiveOneInitializedStoreAndBudget() = runBlocking {
        Fixture().use { f ->
            val services = services(f.context, f.budget, f.provider)
            val runtimes = coroutineScope { List(12) { async { runtime(services) } }.map { it.await() } }
            try {
                assertTrue(runtimes.all { it === runtimes[0] })
                assertSame(f.budget, property<ImageAllocationBudget>(runtimes[0], "Budget"))
                val documents = property<DocumentRepository>(property<Any>(runtimes[0], "Store"), "Documents")
                documents.createDocument("Shared initialized store")
                assertEquals(0L, documents.reconcile().blocked)
            } finally { (property<Any>(runtimes[0], "Store") as AutoCloseable).close() }
        }
    }

    @Test fun installedApplicationOwnsTheServicesRatherThanEachActivity() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        assertEquals("Application-scoped composition is absent", "app.pristinepage.PristinePageApplication", app.javaClass.name)
        val method = app.javaClass.getMethod("getServices")
        assertSame(method.invoke(app), method.invoke(app))
    }

    @Test fun applicationRendererRetainsTheSharedAllowanceAndReadsOnlyCommittedSources() = runBlocking {
        Fixture().use { f ->
            f.open(); val doc = f.documents.createDocument("Application renderer source")
            val batch = f.sessions.beginImport(doc,1,null)
            val imported = f.controller.importProviderReferences(listOf(f.good),batch.parentId).single() as IngestResult.Committed
            val source = f.committed.load(imported.sourceId); val info = source.inspection
            val binding = SourceBinding(info.sourcePixelHash,info.normalizedSize,info.conversionFingerprint)
            val request = RenderRequest(imported.sourceId.value,binding.sourcePixelHash,imported.pageId.value,1,Recipe(geometry = GeometryRecipe.None(binding,outputQuarterTurns = 1)),output,1,null,FallbackPolicy.FAIL)
            val renderer = f.renderer; f.forbid = true
            assertSame(renderer,f.renderer)
            renderer.render(request).use {
                assertEquals(RenderStatus.OK,it.status); val image = checkNotNull(it.image)
                val rotated = byteArrayOf(0,0,-1,-1,0,0,-1,-1,-1,0,-1,0)
                assertArrayEquals(rotated,image.toPackedByteArray()); val owned = f.budget.usedBytes; assertTrue(owned > 0)
                f.budget.reserve(f.budget.capacityBytes-owned).use {
                    f.renderer.render(request.copy(generation = 2)).use { assertEquals(RenderStatus.RESOURCE_LIMIT,it.status); assertNull(it.image) }
                    assertArrayEquals(rotated,image.toPackedByteArray())
                }
                assertEquals(owned,f.budget.usedBytes)
            }
            assertEquals(0L,f.budget.usedBytes); assertArrayEquals(f.png,source.file.readBytes()); assertEquals(1,f.opened.size)
        }
    }

    @Test fun concurrentApplicationConsumersShareOneRendererNotIndependentProcessingQueues() = runBlocking {
        Fixture().use { f ->
            val services = services(f.context,f.budget,f.provider)
            val graphs = coroutineScope { List(12) { async { runtime(services) } }.map { it.await() } }
            try {
                val renderers = graphs.map { renderer(it) }
                assertTrue(renderers.all { it === renderers[0] }); assertTrue(f.opened.isEmpty()); assertEquals(0L,f.budget.usedBytes)
            } finally { (property<Any>(graphs[0],"Store") as AutoCloseable).close() }
        }
    }

    @Test fun failedRecoveryDoesNotCacheReadinessAndTheSameRootCanRetry() = runBlocking {
        Fixture().use { f ->
            val owner = services(f.context, f.budget, f.provider)
            f.beforeFiles = {
                // Factory has opened the actual schema, but no graph is published. Corrupt only this fixture's otherwise unreferenced row so canonical startup listing must refuse, not report a clean pass.
                f.beforeFiles = {}
                SQLiteDatabase.openDatabase(f.context.getDatabasePath("pristinepage.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                    db.execSQL("INSERT INTO Document(documentId,title,documentRevision,lifecycle,createdAt,updatedAt,deletedAt) VALUES ('','Synthetic recovery fault',1,'ACTIVE',1,1,NULL)")
                }
            }
            try { withContext(Dispatchers.Main) { runtime(owner) }; fail("Failed initialization was reported ready") }
            catch (failure: RepositoryFailure) { assertEquals(OperationErrorCode.INTEGRITY_FAILED, failure.error.code) }
            f.beforeFiles = {}
            SQLiteDatabase.openDatabase(f.context.getDatabasePath("pristinepage.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("UPDATE Document SET documentId=? WHERE documentId=''", arrayOf(UUID.randomUUID().toString()))
            }
            val graph = withContext(Dispatchers.Main) { runtime(owner) }
            try {
                val documents = property<DocumentRepository>(property<Any>(graph, "Store"), "Documents")
                documents.createDocument("Initialization retry")
                assertEquals(0L, documents.reconcile().blocked); assertTrue(f.opened.isEmpty())
            } finally { (property<Any>(graph, "Store") as AutoCloseable).close() }
        }
    }

    @Test fun cancellationDuringIoDeliveryLeavesNoCachedCanceledGraphAndAllowsRetry() = runBlocking {
        Fixture().use { f ->
            val owner = services(f.context, f.budget, f.provider)
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            f.beforeFiles = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            val request = launch(Dispatchers.Main) { runtime(owner) }
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                request.cancel()
            } finally { release.countDown() }
            request.join(); assertTrue(request.isCancelled)
            f.beforeFiles = {}
            val graph = withContext(Dispatchers.Main) { runtime(owner) }
            try {
                val documents = property<DocumentRepository>(property<Any>(graph, "Store"), "Documents")
                documents.createDocument("Canceled initialization retry")
                assertEquals(0L, documents.reconcile().blocked); assertTrue(f.opened.isEmpty())
            } finally { (property<Any>(graph, "Store") as AutoCloseable).close() }
        }
    }

    private class Fixture : AutoCloseable {
        private val base = InstrumentationRegistry.getInstrumentation().targetContext
        private val root = File(base.filesDir, "t04-composition-${UUID.randomUUID()}")
        var beforeFiles: () -> Unit = {}
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File { beforeFiles(); return File(root, "files") }
            override fun getDatabasePath(name: String): File = File(root, "databases/$name").also { it.parentFile!!.mkdirs() }
        }
        val good = "content://fixture.documents/good"; val bad = "content://fixture.documents/corrupt"
        val rgb = byteArrayOf(-1, 0, 0, 0, -1, 0, 0, 0, -1, -1, -1, -1)
        val png = Bitmap.createBitmap(intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), 0xffffffff.toInt()), 2, 2, Bitmap.Config.ARGB_8888).let { bitmap ->
            try { ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray() } finally { bitmap.recycle() }
        }
        val budget = ApplicationImageBudget.create(64)
        val opened = mutableListOf<String>()
        var forbid = false; var beforeOpen: () -> Unit = {}
        val provider = SourceInput { reference ->
            check(!forbid) { "Replay reopened a temporary provider" }; check(reference == good || reference == bad)
            opened += reference; beforeOpen()
            ByteArrayInputStream(if (reference == good) png else byteArrayOf(1, 2, 3, 4))
        }
        private var current: Any? = null
        private val graph get() = checkNotNull(current)
        val documents get() = property<DocumentRepository>(property<Any>(graph, "Store"), "Documents")
        val sessions get() = property<ImportSessionCommands>(property<Any>(graph, "Store"), "Imports")
        val selections get() = property<ImportSelections>(property<Any>(graph, "Store"), "Selections")
        val lifecycle get() = property<ImportSessionLifecycle>(property<Any>(graph, "Store"), "Lifecycle")
        val committed get() = property<CommittedSources>(property<Any>(graph, "Store"), "CommittedSources")
        val controller get() = property<PhotoImportController>(graph, "PhotoImports")
        val decoder get() = property<SourceDecoder>(graph, "Decoder")
        val renderer get() = renderer(graph)
        suspend fun open() { current = runtime(services(context, budget, provider)) }
        suspend fun reopen() { close(); current = null; open() }
        fun inventory(): Map<String, String> = File(root, "files/store").walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { b -> "%02x".format(b.toInt() and 255) } }
        override fun close() { current?.let { (property<Any>(it, "Store") as AutoCloseable).close() } }
    }

    private companion object {
        fun renderer(graph: Any): RenderCoordinator = try { property(graph,"Renderer") } catch (_: NoSuchMethodException) { throw AssertionError("Application-scoped render coordinator is absent") }
        @Suppress("UNCHECKED_CAST") fun <T> property(receiver: Any, name: String): T = receiver.javaClass.getMethod("get$name").invoke(receiver) as T
        fun type(name: String): Class<*> = try { Class.forName(name) } catch (_: ClassNotFoundException) { throw AssertionError("Application-scoped composition is absent: $name") }
        fun services(context: Context, budget: ImageAllocationBudget, provider: SourceInput): Any = type("app.pristinepage.ApplicationServices").getConstructor(Context::class.java, ImageAllocationBudget::class.java, SourceInput::class.java).newInstance(context, budget, provider)
        suspend fun runtime(services: Any): Any = invoke(services.javaClass.getMethod("imports", kotlin.coroutines.Continuation::class.java), services)
        suspend fun privateStore(context: Context, provider: SourceInput, inspector: SourceInspector): Any {
            val method = type("app.pristinepage.data.repository.PrivateImportStore").getMethod("open", Context::class.java, SourceInput::class.java, SourceInspector::class.java, kotlin.coroutines.Continuation::class.java)
            return invoke(method, null, context, provider, inspector)
        }
        suspend fun invoke(method: java.lang.reflect.Method, receiver: Any?, vararg args: Any?): Any = suspendCoroutineUninterceptedOrReturn { continuation ->
            try { method.invoke(receiver, *args, continuation) } catch (failure: InvocationTargetException) { throw failure.targetException }
        }
    }
}
