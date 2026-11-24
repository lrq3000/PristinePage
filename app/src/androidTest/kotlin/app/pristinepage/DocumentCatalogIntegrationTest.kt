package app.pristinepage

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.SourceInput
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual generated Room queries and Application-owned composition; only external byte delivery is injected. Unique private roots/originals remain retained. No Activity/picker/grant/process-death or physical-device claim. */
@RunWith(AndroidJUnit4::class)
class DocumentCatalogIntegrationTest {
    @Test fun committedMixedSelectionReopensWithExactOrderedPageMetadataWithoutProviderReads() = runBlocking {
        Fixture().use { f ->
            var graph = f.open()
            val doc = graph.store.documents.createDocument("Catalog ordered pages")
            val other = graph.store.documents.createDocument("Empty catalog entry")
            val batch = graph.store.imports.beginImport(doc, 1, null)
            val results = graph.photoImports.importProviderReferences(listOf(f.good, f.bad, f.good), batch.parentId)
            val first = results[0] as IngestResult.Committed; val second = results[2] as IngestResult.Committed
            assertTrue(results[1] is IngestResult.Rejected)
            val catalog = graph.store.catalog
            val before = catalog.load(doc)
            assertEquals(listOf(first.pageId, second.pageId), before.pages.map { it.pageId })
            assertEquals(listOf(first.sourceId, second.sourceId), before.pages.map { it.sourceId })
            assertEquals(listOf(0, 1), before.pages.map { it.ordinal }); assertEquals(listOf(1L, 1L), before.pages.map { it.recipeRevision })
            assertEquals(2, before.document.pageCount); assertEquals(3L, before.document.documentRevision); assertFalse(before.document.hasPendingImport)
            assertEquals(0, catalog.load(other).document.pageCount)
            val ids = listOf(doc, other).sortedBy { it.value }
            val page1 = catalog.list(null, 1); val page2 = catalog.list(page1.single().documentId, 1)
            assertEquals(ids, (page1 + page2).map { it.documentId }); assertTrue(catalog.list(page2.single().documentId, 1).isEmpty())
            val original = graph.store.committedSources.load(first.sourceId)
            assertArrayEquals(f.png, original.file.readBytes())
            graph = f.reopen(); f.forbid = true
            val retainedFiles = f.files()
            assertEquals(before, graph.store.catalog.load(doc)); assertEquals(3, f.providerOpens)
            assertEquals(retainedFiles, f.files()); assertArrayEquals(f.png, original.file.readBytes())
            try { (graph.store.catalog.load(doc).pages as MutableList<DocumentPage>).clear(); fail("Native overview was mutable") }
            catch (_: UnsupportedOperationException) { }
        }
    }

    @Test fun pendingEmptyBatchAndItsAbandonmentRemainMetadataNotSavedPageSuccess() = runBlocking {
        Fixture().use { f ->
            val graph = f.open(); val doc = graph.store.documents.createDocument("Catalog pending")
            val request = graph.store.imports.beginImport(doc, 1, null)
            val pending = graph.store.catalog.load(doc)
            assertTrue(pending.document.hasPendingImport); assertTrue(pending.pages.isEmpty()); assertEquals(0, pending.document.pageCount)
            graph.store.lifecycle.cancelBatch(request.parentId)
            val closed = graph.store.catalog.load(doc)
            assertFalse(closed.document.hasPendingImport); assertTrue(closed.pages.isEmpty()); assertEquals(1L, closed.document.documentRevision)
            assertEquals(0, f.providerOpens)
        }
    }

    @Test fun nativeRawTitleAndOrdinalBoundsRefuseBeforeTypedProjectionWithoutChangingSources() = runBlocking {
        Fixture().use { f ->
            val graph = f.open(); val doc = graph.store.documents.createDocument("Synthetic")
            val batch = graph.store.imports.beginImport(doc, 1, null)
            graph.photoImports.importProviderReferences(listOf(f.good), batch.parentId)
            val originalFiles = f.files()
            val badTitle = "Synthetic\u0000" + "x".repeat(4096)
            f.write("UPDATE Document SET title=? WHERE documentId=?", badTitle, doc.value)
            assertEquals(listOf("text", "9", "4106"), f.scalars("SELECT typeof(title),length(title),length(CAST(title AS BLOB)) FROM Document WHERE documentId=?", doc.value))
            refused(OperationErrorCode.INTEGRITY_FAILED) { graph.store.catalog.load(doc) }
            f.write("UPDATE Document SET title=? WHERE documentId=?", ByteArray(4096), doc.value)
            refused(OperationErrorCode.INTEGRITY_FAILED) { graph.store.catalog.load(doc) }
            f.write("UPDATE Document SET title='Synthetic' WHERE documentId=?", doc.value)
            f.write("UPDATE Page SET ordinal=4294967296 WHERE documentId=?", doc.value)
            refused(OperationErrorCode.INTEGRITY_FAILED) { graph.store.catalog.load(doc) }
            f.write("UPDATE Page SET ordinal=0 WHERE documentId=?", doc.value)
            f.write("UPDATE Document SET documentRevision=1.5 WHERE documentId=?", doc.value)
            refused(OperationErrorCode.INTEGRITY_FAILED) { graph.store.catalog.load(doc) }
            f.write("UPDATE Document SET documentRevision=2 WHERE documentId=?", doc.value)
            assertEquals(1, graph.store.catalog.load(doc).pages.size)
            assertEquals(originalFiles, f.files()); assertEquals(1, f.providerOpens)
        }
    }

    @Test fun missingInactiveAndMalformedIdentityRemainDistinctWithoutFalseEmptyLibrary() = runBlocking {
        Fixture().use { f ->
            val graph = f.open(); val doc = graph.store.documents.createDocument("Catalog lifecycle")
            refused(OperationErrorCode.SOURCE_UNAVAILABLE) { graph.store.catalog.load(DocumentId(UUID.randomUUID().toString())) }
            f.write("UPDATE Document SET lifecycle='TOMBSTONED',deletedAt=1 WHERE documentId=?", doc.value)
            refused(OperationErrorCode.SOURCE_UNAVAILABLE) { graph.store.catalog.load(doc) }
            assertTrue(graph.store.catalog.list(null, 50).isEmpty())
            f.write("UPDATE Document SET documentId=?,lifecycle='ACTIVE',deletedAt=NULL WHERE documentId=?", "x".repeat(4096), doc.value)
            refused(OperationErrorCode.INTEGRITY_FAILED) { graph.store.catalog.list(null, 50) }
            assertEquals(0, f.providerOpens)
        }
    }

    @Test fun nativeKeysetPlanAndAcceptedTitleCommandsStayCompatibleWithTheReader() = runBlocking {
        Fixture().use { f ->
            val graph = f.open()
            for (title in listOf("\u0000Synthetic", "Syn\u0000thetic", "é".repeat(160), "😀".repeat(80))) {
                val id = graph.store.documents.createDocument(title)
                assertEquals(title, graph.store.catalog.load(id).document.title)
                assertTrue(graph.store.documents.renameDocument(id, 1, "\u0000Renamed") is DocumentMutationResult.Applied)
                assertEquals("\u0000Renamed", graph.store.catalog.load(id).document.title)
            }
            // Read the production annotation's constant, not a handwritten query mirror. Reflection keeps a private SQL detail out of the app's production API and adds no test-only production method.
            val field = Class.forName("app.pristinepage.data.db.CatalogDaoKt").getDeclaredField("ACTIVE_DOCUMENT_IDS_SQL").apply { isAccessible = true }
            val sql = field.get(null) as String
            f.explain(sql, null).forEach { assertFalse("Native keyset sorts active library: $it", it.contains("TEMP B-TREE")) }
            f.explain(sql, graph.store.catalog.list(null, 1).single().documentId.value).forEach { assertFalse("Native continuation sorts active library: $it", it.contains("TEMP B-TREE")) }
            assertEquals(4, graph.store.catalog.list(null, 50).size); assertEquals(0, f.providerOpens)
        }
    }

    internal class Fixture(encoded: ByteArray? = null, private val realProvider: Boolean = false, private val memoryClassMiB: Int = 64) : AutoCloseable {
        private val base = InstrumentationRegistry.getInstrumentation().targetContext
        private val root = File(base.filesDir, "t04-catalog-${UUID.randomUUID()}")
        private val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(root, "files")
            override fun getDatabasePath(name: String): File = File(root, "databases/$name").also { it.parentFile!!.mkdirs() }
        }
        val good = "content://fixture.documents/good"; val bad = "content://fixture.documents/bad"
        val png = encoded ?: Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).let { bitmap ->
            try { bitmap.eraseColor(0xffabcdef.toInt()); ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray() } finally { bitmap.recycle() }
        }
        var providerOpens = 0; var forbid = false
        var beforeProvider: (String, Int) -> Unit = { _, _ -> }
        private var graph: ApplicationImports? = null
        suspend fun open(): ApplicationImports = (if (realProvider) ApplicationServices(context) else ApplicationServices(context, ApplicationImageBudget.create(memoryClassMiB), SourceInput { reference ->
            check(!forbid); check(reference == good || reference == bad); providerOpens++; beforeProvider(reference, providerOpens)
            ByteArrayInputStream(if (reference == good) png else byteArrayOf(1, 2, 3, 4))
        })).imports().also { graph = it }
        suspend fun reopen(): ApplicationImports { close(); graph = null; return open() }
        fun write(sql: String, vararg args: Any) = SQLiteDatabase.openDatabase(context.getDatabasePath("pristinepage.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL(sql, args) }
        fun scalars(sql: String, id: String): List<String> = SQLiteDatabase.openDatabase(context.getDatabasePath("pristinepage.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(sql, arrayOf(id)).use { c -> check(c.moveToFirst()); List(c.columnCount) { c.getString(it) } }
        }
        fun explain(sql: String, after: String?): List<String> = SQLiteDatabase.openDatabase(context.getDatabasePath("pristinepage.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            // Match Room's typed null/Long bindings, not rawQuery's string-only selectionArgs convenience route.
            db.rawQueryWithFactory({ _, driver, editTable, query ->
                if (after == null) query.bindNull(1) else query.bindString(1, after)
                query.bindLong(2, 1)
                android.database.sqlite.SQLiteCursor(driver, editTable, query)
            }, "EXPLAIN QUERY PLAN $sql", null, "").use { c -> buildList { while (c.moveToNext()) add(c.getString(3)) } }
        }
        fun files(): Map<String, String> = File(root, "files/store").walkTopDown().filter { it.isFile }.associate { file ->
            file.relativeTo(root).path to MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        override fun close() { graph?.store?.close() }
    }

    private companion object {
        suspend fun refused(code: OperationErrorCode, action: suspend () -> Any?) {
            try { action(); fail("Catalog unexpectedly accepted unsafe/unavailable metadata") }
            catch (failure: RepositoryFailure) { assertEquals(code, failure.error.code) }
        }
    }
}
