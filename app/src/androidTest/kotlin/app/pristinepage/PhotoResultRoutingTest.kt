package app.pristinepage

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.capture.PhotoImportRequest
import app.pristinepage.capture.PhotoImportResultRouter
import app.pristinepage.capture.PhotoSelection
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.SourceInput
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real AndroidX key/dispatch/contract and actual Room/controller/codec/private bytes. Only onLaunch substitutes the external Activity. This is registry plus DB recreation, not an actual picker, Activity/OS death or grant test; all unique fixtures remain retained. */
@RunWith(AndroidJUnit4::class)
class PhotoResultRoutingTest {
    private val uri = Uri.parse("content://fixture.documents/selected")

    @Test fun earlierCallbackKeepsItsDocumentAfterANewerRequestIsRegistered() = runBlocking {
        Fixture().use { f ->
            val graph = f.open()
            val a = graph.store.documents.createDocument("Original request")
            val b = graph.store.documents.createDocument("Newer request")
            val first = graph.store.imports.beginImport(a, 1, null).parentId
            val second = graph.store.imports.beginImport(b, 1, null).parentId
            val registry = RecordingRegistry(); val delivered = mutableListOf<Pair<Any, PhotoSelection>>()
            val old = register(registry, request(first.value), delivered)
            val newer = register(registry, request(second.value), delivered)
            try {
                main { launch(old); launch(newer) }
                assertEquals(2, registry.codes.distinct().size)
                main { assertTrue(registry.dispatchResult(registry.codes[0], Activity.RESULT_OK, Intent().setData(uri))) }
                assertEquals(1, delivered.size); assertEquals(first.value, batchId(delivered.single().first))
                val committed = deliver(graph, delivered.single()).single() as IngestResult.Committed
                assertEquals(a, committed.documentId); assertEquals(1L, f.pages(a)); assertEquals(0L, f.pages(b))
                assertEquals(1, f.providerOpens)
            } finally { main { old.close(); newer.close() } }
        }
    }

    @Test fun idOnlySavedRequestAndRegistryRestoreKeepTheOriginalRoomBatch() = runBlocking {
        Fixture().use { f ->
            val graph = f.open(); val document = graph.store.documents.createDocument("Restored request")
            val batch = graph.store.imports.beginImport(document, 1, null).parentId
            val original = request(batch.value)
            val savedRequest = original.javaClass.getMethod("save").invoke(original) as Bundle
            assertEquals(setOf("pristinepage.photo.batch.v1"), savedRequest.keySet())
            assertEquals(batch.value, savedRequest.getString("pristinepage.photo.batch.v1"))
            val oldRegistry = RecordingRegistry(); val oldResults = mutableListOf<Pair<Any, PhotoSelection>>()
            val old = register(oldRegistry, original, oldResults); val registryState = Bundle()
            main { launch(old); oldRegistry.onSaveInstanceState(registryState); old.close() }
            val requestCode = oldRegistry.codes.single()
            val reopened = f.reopen()
            val otherDocument = reopened.store.documents.createDocument("Visible later")
            reopened.store.imports.beginImport(otherDocument, 1, null)
            val restored = restore(savedRequest)!!; assertEquals(batch.value, batchId(restored))
            val registry = RecordingRegistry(); val delivered = mutableListOf<Pair<Any, PhotoSelection>>()
            main { registry.onRestoreInstanceState(registryState) }
            val registration = register(registry, restored, delivered)
            try {
                main { assertTrue(registry.dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(uri))) }
                assertEquals(1, delivered.size); assertTrue(oldResults.isEmpty())
                val committed = deliver(reopened, delivered.single()).single() as IngestResult.Committed
                assertEquals(document, committed.documentId); assertEquals(1L, f.pages(document)); assertEquals(0L, f.pages(otherDocument))
                assertEquals(1, f.providerOpens)
            } finally { main { registration.close() } }
        }
    }

    @Test fun absentOrMalformedSavedIdentityNeverInventsANewRequest() {
        assertNull(restore(null)); assertNull(restore(Bundle()))
        for (bad in listOf("", "not-a-session", "content://private.example/name")) {
            val bundle = Bundle().apply { putString("pristinepage.photo.batch.v1", bad) }
            try { restore(bundle); fail("Invalid saved identity became a request") }
            catch (failure: RepositoryFailure) { assertEquals(OperationErrorCode.SESSION_EXPIRED, failure.error.code) }
        }
        try { restore(Bundle().apply { putInt("pristinepage.photo.batch.v1", 3) }); fail("Wrong saved type became a request") }
        catch (failure: RepositoryFailure) { assertEquals(OperationErrorCode.SESSION_EXPIRED, failure.error.code) }
    }

    @Test fun pickerCancellationClosesOnlyItsUnselectedBatchWithoutAPageOrProviderRead() = runBlocking {
        Fixture().use { f ->
            val graph = f.open(); val doc = graph.store.documents.createDocument("Canceled picker")
            val batch = graph.store.imports.beginImport(doc, 1, null).parentId
            val registry = RecordingRegistry(); val results = mutableListOf<Pair<Any, PhotoSelection>>()
            val registration = register(registry, request(batch.value), results)
            try {
                main { launch(registration); assertTrue(registry.dispatchResult(registry.codes.single(), Activity.RESULT_CANCELED, null)) }
                assertTrue(results.single().second is PhotoSelection.Canceled)
                val rejected = deliver(graph, results.single()).single() as IngestResult.Rejected
                assertEquals(batch, rejected.sessionId); assertEquals(OperationErrorCode.CANCELED, rejected.error.code)
                assertEquals(0L, f.pages(doc)); assertEquals(0, f.providerOpens)
            } finally { main { registration.close() } }
        }
    }

    @Test fun expiredPickerCancellationClosesItsReservationAndAllowsANewBatch() = runBlocking {
        Fixture().use { f ->
            val graph = f.open(); val doc = graph.store.documents.createDocument("Expired picker")
            val other = graph.store.documents.createDocument("Unrelated request")
            val batch = graph.store.imports.beginImport(doc, 1, null).parentId
            val otherBatch = graph.store.imports.beginImport(other, 1, null).parentId
            f.expireBatch(batch)
            val registry = RecordingRegistry(); val results = mutableListOf<Pair<Any, PhotoSelection>>()
            val registration = register(registry, request(batch.value), results)
            try {
                main { launch(registration); registry.dispatchResult(registry.codes.single(), Activity.RESULT_CANCELED, null) }
                val rejected = try { deliver(graph, results.single()).single() as IngestResult.Rejected }
                catch (failure: RepositoryFailure) { throw AssertionError("Expired picker cancellation must close its reservation", failure) }
                assertEquals(batch, rejected.sessionId); assertEquals(OperationErrorCode.CANCELED, rejected.error.code)
                assertEquals("CANCELED", f.batchState(batch)); assertEquals(0L, f.batchChildren(batch))
                assertEquals("WAITING_SELECTION", f.batchState(otherBatch)); assertEquals(0L, f.batchChildren(otherBatch))
                assertEquals(0L, f.pages(doc)); assertEquals(0L, f.pages(other)); assertEquals(0, f.providerOpens)
                assertNotEquals(batch, graph.store.imports.beginImport(doc, 1, null).parentId)
                assertEquals("WAITING_SELECTION", f.batchState(otherBatch)); assertEquals(0, f.providerOpens)
            } finally { main { registration.close() } }
        }
    }

    @Test fun rejectedOverSelectionClosesItsReservationWithoutImportingAPrefix() = runBlocking {
        Fixture().use { f ->
            val graph = f.open(); val doc = graph.store.documents.createDocument("Rejected selection")
            val batch = graph.store.imports.beginImport(doc, 1, null).parentId
            val registry = RecordingRegistry(); val results = mutableListOf<Pair<Any, PhotoSelection>>()
            val registration = register(registry, request(batch.value), results)
            val clip = ClipData.newRawUri("synthetic", uri).apply { repeat(100) { addItem(ClipData.Item(uri)) } }
            try {
                main { launch(registration); registry.dispatchResult(registry.codes.single(), Activity.RESULT_OK, Intent().apply { clipData = clip }) }
                assertTrue(results.single().second is PhotoSelection.Rejected)
                val rejected = deliver(graph, results.single()).single() as IngestResult.Rejected
                assertEquals(batch, rejected.sessionId); assertEquals(batch.value, rejected.error.affectedId)
                assertEquals(OperationErrorCode.RESOURCE_LIMIT, rejected.error.code)
                try { graph.store.selections.prepareSelection(batch, listOf(uri.toString())); fail("Rejected result left insertion open") }
                catch (failure: RepositoryFailure) { assertEquals(OperationErrorCode.SESSION_EXPIRED, failure.error.code) }
                assertEquals(0L, f.pages(doc)); assertEquals(0, f.providerOpens)
            } finally { main { registration.close() } }
        }
    }

    @Test fun restoredCanceledRequestCannotAttachALateSelectionToANewerBatch() = runBlocking {
        Fixture().use { f ->
            val graph = f.open(); val doc = graph.store.documents.createDocument("Canceled before return")
            val oldBatch = graph.store.imports.beginImport(doc, 1, null).parentId
            val saved = request(oldBatch.value).let { it.javaClass.getMethod("save").invoke(it) as Bundle }
            graph.store.lifecycle.cancelBatch(oldBatch)
            val nextBatch = graph.store.imports.beginImport(doc, 1, null).parentId
            val registry = RecordingRegistry(); val results = mutableListOf<Pair<Any, PhotoSelection>>()
            val registration = register(registry, restore(saved)!!, results)
            try {
                main { launch(registration); registry.dispatchResult(registry.codes.single(), Activity.RESULT_OK, Intent().setData(uri)) }
                assertEquals(oldBatch.value, batchId(results.single().first))
                try { deliver(graph, results.single()); fail("Canceled callback was reassigned") }
                catch (failure: RepositoryFailure) { assertEquals(OperationErrorCode.SESSION_EXPIRED, failure.error.code) }
                assertEquals(1, graph.store.selections.prepareSelection(nextBatch, listOf(uri.toString())).childIds.size)
                assertEquals(0L, f.pages(doc)); assertEquals(0, f.providerOpens)
            } finally { main { registration.close() } }
        }
    }

    private class RecordingRegistry : ActivityResultRegistry() {
        val codes = mutableListOf<Int>()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) { codes += requestCode }
    }

    private class Fixture : AutoCloseable {
        private val base = InstrumentationRegistry.getInstrumentation().targetContext
        private val root = File(base.filesDir, "t04-result-routing-${UUID.randomUUID()}")
        private val files = File(root, "files").apply { check(mkdirs()) }
        private val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getDatabasePath(name: String): File = File(root, "databases/$name").also { it.parentFile!!.mkdirs() }
        }
        var providerOpens = 0
        private val png = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).let { bitmap ->
            try { bitmap.eraseColor(0xffffffff.toInt()); ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray() } finally { bitmap.recycle() }
        }
        private var graph: ApplicationImports? = null
        suspend fun open(): ApplicationImports = ApplicationServices(context, ApplicationImageBudget.create(64), SourceInput { reference ->
            check(reference == "content://fixture.documents/selected"); providerOpens++; ByteArrayInputStream(png)
        }).imports().also { graph = it }
        suspend fun reopen(): ApplicationImports { close(); graph = null; return open() }
        fun pages(document: DocumentId): Long = read("SELECT COUNT(*) FROM Page WHERE documentId=?", document.value) { it.getLong(0) }
        fun batchState(batch: ImportSessionId): String = read("SELECT state FROM ImportSession WHERE sessionId=?", batch.value) { it.getString(0) }
        fun batchChildren(batch: ImportSessionId): Long = read("SELECT COUNT(*) FROM ImportSession WHERE parentSessionId=?", batch.value) { it.getLong(0) }
        fun expireBatch(batch: ImportSessionId) {
            SQLiteDatabase.openDatabase(context.getDatabasePath("pristinepage.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.compileStatement("UPDATE ImportSession SET expiresAt=0 WHERE sessionId=? AND kind='BATCH'").use { update -> update.bindString(1, batch.value); assertEquals(1, update.executeUpdateDelete()) }
            }
        }
        private fun <T> read(sql: String, id: String, action: (android.database.Cursor) -> T): T = SQLiteDatabase.openDatabase(context.getDatabasePath("pristinepage.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(sql, arrayOf(id)).use { c -> check(c.moveToFirst()); action(c) }
        }
        override fun close() { graph?.store?.close() }
    }

    private companion object {
        fun type(name: String): Class<*> = try { Class.forName("app.pristinepage.capture.$name") }
        catch (_: ClassNotFoundException) { throw AssertionError("Request-scoped photo-result routing is absent") }
        fun request(id: String): Any = type("PhotoImportRequest").getConstructor(String::class.java).newInstance(id)
        fun batchId(request: Any): String = request.javaClass.getMethod("getBatchId").invoke(request) as String
        fun restore(bundle: Bundle?): Any? = try { type("PhotoImportRequest").getMethod("restore", Bundle::class.java).invoke(null, bundle) }
        catch (failure: InvocationTargetException) { throw failure.targetException }
        fun register(registry: ActivityResultRegistry, request: Any, results: MutableList<Pair<Any, PhotoSelection>>): AutoCloseable {
            var result: AutoCloseable? = null
            val callback: (Any, PhotoSelection) -> Unit = { owner, selection -> results += owner to selection }
            main { result = type("PhotoPickerRegistration").getConstructor(ActivityResultRegistry::class.java, type("PhotoImportRequest"), Function2::class.java).newInstance(registry, request, callback) as AutoCloseable }
            return checkNotNull(result)
        }
        fun launch(registration: AutoCloseable) { registration.javaClass.getMethod("launch").invoke(registration) }
        fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
        suspend fun deliver(graph: ApplicationImports, result: Pair<Any, PhotoSelection>): List<IngestResult> =
            PhotoImportResultRouter(graph.photoImports, graph.store.lifecycle).deliver(result.first as PhotoImportRequest, result.second)
    }
}
