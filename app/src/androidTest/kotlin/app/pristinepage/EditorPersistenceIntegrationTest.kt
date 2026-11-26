package app.pristinepage

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.core.geometry.GeometrySizing
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.*
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.EditablePages
import app.pristinepage.data.repository.SourceInput
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Application/controller/Room/codec/read/save/reopen/renderer consumers using operation-private synthetic originals only. No picker/media/grant/UI/export or source deletion is performed. */
@RunWith(AndroidJUnit4::class)
class EditorPersistenceIntegrationTest {
    @Test fun applicationLoadsTheCurrentPageRecipeWithoutReopeningItsProvider() = runBlocking {
        Fixture().use { f ->
            val imported = f.ingest(); val graph = checkNotNull(f.graph); f.forbid = true
            val page = reader(graph).load(imported.documentId,imported.pageId)
            assertEquals(imported.documentId,page.documentId); assertEquals(imported.pageId,page.pageId); assertEquals(imported.sourceId,page.sourceId)
            assertEquals(imported.documentRevision,page.documentRevision); assertEquals(1L,page.recipeRevision)
            assertEquals(imported.sourcePixelHash,page.recipe.geometry.source.sourcePixelHash); assertEquals(RecipeCodec.hash(page.recipe),page.recipeHash)
            assertEquals(1,f.providerReads); assertEquals(0L,graph.budget.usedBytes)
        }
    }

    @Test fun applicationSavesThenReopensTheExactRecipeAndRendersItsUnchangedSource() = runBlocking {
        Fixture().use { f ->
            val imported = f.ingest(); val graph = checkNotNull(f.graph); val source = graph.store.committedSources.load(imported.sourceId); val info = source.inspection
            val recipe = Recipe(geometry = GeometryRecipe.None(SourceBinding(info.sourcePixelHash,info.normalizedSize,info.conversionFingerprint),outputQuarterTurns = 1))
            f.forbid = true
            val result = graph.store.documents.saveRecipe(imported.pageId,1,recipe)
            assertTrue("Application-backed editor persistence is unavailable",result is SaveRecipeResult.Saved)
            val saved = result as SaveRecipeResult.Saved
            val conflict = graph.store.documents.saveRecipe(imported.pageId,1,Recipe(geometry = recipe.geometry.withOutputQuarterTurns(2)))
            assertEquals(saved.recipeRevision,(conflict as SaveRecipeResult.Conflict).actualRevision)
            f.reopen(); val reopened = checkNotNull(f.graph); val page = reader(reopened).load(imported.documentId,imported.pageId)
            assertEquals(saved.recipeRevision,page.recipeRevision); assertEquals(saved.documentRevision,page.documentRevision); assertEquals(recipe,page.recipe); assertEquals(saved.recipeHash,page.recipeHash)
            val request = RenderRequest(page.sourceId.value,info.sourcePixelHash,page.pageId.value,page.recipeRevision,page.recipe,OutputSpec(GeometrySizing.target(recipe.geometry),RenderPurpose.PREVIEW),1,null,FallbackPolicy.FAIL)
            reopened.renderer.render(request).use {
                assertEquals(RenderStatus.OK,it.status)
                assertArrayEquals(ByteArray(18) { intArrayOf(35,5,45,15,55,25)[it/3].toByte() },checkNotNull(it.image).toPackedByteArray())
            }
            assertArrayEquals(f.png,source.file.readBytes()); assertEquals(1,f.providerReads); assertEquals(0L,reopened.budget.usedBytes)
        }
    }

    @Test fun nativeRecipeBlobAndSchemaBoundsRefuseBeforeRoomCanNarrowOrMaterializeThem() = runBlocking {
        for ((field,value,code) in listOf(Triple("recipeSchemaVersion",4294967297L,OperationErrorCode.INTEGRITY_FAILED),Triple("recipeSchemaVersion",2L,OperationErrorCode.UNSUPPORTED_VERSION),Triple("canonicalRecipe",ByteArray(524289),OperationErrorCode.INTEGRITY_FAILED))) {
            Fixture().use { f ->
                val imported = f.ingest(); val graph = checkNotNull(f.graph); f.forbid = true
                f.raw().use { db -> db.execSQL("UPDATE RecipeRevision SET $field=? WHERE pageId=?",arrayOf(value,imported.pageId.value)) }
                try { reader(graph).load(imported.documentId,imported.pageId); fail("Corrupt/future metadata became an editable page") } catch (error: RepositoryFailure) { assertEquals(code,error.error.code) }
                assertEquals(1,f.providerReads); assertEquals(0L,graph.budget.usedBytes)
            }
        }
    }

    @Test fun nativeRecipeSourceBindingMismatchRefusesWithoutChangingTheOriginal() = runBlocking {
        Fixture().use { f ->
            val imported = f.ingest(); val graph = checkNotNull(f.graph); val original = graph.store.committedSources.load(imported.sourceId).file; f.forbid = true
            f.raw().use { db -> db.execSQL("UPDATE Source SET pixelSha256=? WHERE sourceId=?",arrayOf("b".repeat(64),imported.sourceId.value)) }
            try { reader(graph).load(imported.documentId,imported.pageId); fail("Mismatched source binding became editable") } catch (error: RepositoryFailure) { assertEquals(OperationErrorCode.INTEGRITY_FAILED,error.error.code) }
            assertArrayEquals(f.png,original.readBytes()); assertEquals(1,f.providerReads); assertEquals(0L,graph.budget.usedBytes)
        }
    }

    @Test fun saveRejectsRawSchemaAliasWithoutChangingPointersHistoryOrPins() = runBlocking {
        Fixture().use { f ->
            val imported = f.ingest(); val graph = checkNotNull(f.graph); val page = reader(graph).load(imported.documentId,imported.pageId); f.forbid = true
            f.raw().use { it.execSQL("UPDATE RecipeRevision SET recipeSchemaVersion=4294967297 WHERE pageId=?",arrayOf(imported.pageId.value)) }
            val before = f.signature()
            guardedSave(graph.store.documents.saveRecipe(imported.pageId,1,page.recipe.copy(geometry = page.recipe.geometry.withOutputQuarterTurns(1))))
            assertEquals(before,f.signature()); assertEquals(1,f.providerReads); assertEquals(0L,graph.budget.usedBytes)
        }
    }

    @Test fun saveRejectsOvercapOrTextRecipeBeforeAnyCommit() = runBlocking {
        for (payload in listOf<Any>(ByteArray(524289),"{\"future\":true}")) {
            Fixture().use { f ->
                val imported = f.ingest(); val graph = checkNotNull(f.graph); val page = reader(graph).load(imported.documentId,imported.pageId); f.forbid = true
                f.raw().use { it.execSQL("UPDATE RecipeRevision SET canonicalRecipe=? WHERE pageId=?",arrayOf(payload,imported.pageId.value)) }
                val before = f.signature()
                guardedSave(graph.store.documents.saveRecipe(imported.pageId,1,page.recipe.copy(geometry = page.recipe.geometry.withOutputQuarterTurns(1))))
                assertEquals(before,f.signature()); assertEquals(1,f.providerReads); assertEquals(0L,graph.budget.usedBytes)
            }
        }
    }

    @Test fun saveRejectsAValidForeignRevisionSourceWithoutRepairingHistory() = runBlocking {
        Fixture().use { f ->
            val imported = f.ingest(); val graph = checkNotNull(f.graph); val page = reader(graph).load(imported.documentId,imported.pageId)
            val foreign = f.ingest(); assertNotEquals(imported.sourceId,foreign.sourceId); f.forbid = true
            f.raw().use { it.execSQL("UPDATE RecipeRevision SET sourceId=? WHERE pageId=?",arrayOf(foreign.sourceId.value,imported.pageId.value)) }
            val before = f.signature()
            guardedSave(graph.store.documents.saveRecipe(imported.pageId,1,page.recipe.copy(geometry = page.recipe.geometry.withOutputQuarterTurns(1))))
            assertEquals(before,f.signature()); assertEquals(2,f.providerReads); assertEquals(0L,graph.budget.usedBytes)
        }
    }

    private class Fixture : AutoCloseable {
        private val base = InstrumentationRegistry.getInstrumentation().targetContext
        private val root = File(base.filesDir,"t09-editor-persistence-${UUID.randomUUID()}")
        private val files = File(root, "files").apply { check(mkdirs()) }
        private val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getDatabasePath(name: String): File = File(root,"databases/$name").also { it.parentFile!!.mkdirs() }
        }
        val png = Bitmap.createBitmap(intArrayOf(5,15,25,35,45,55).map { gray -> (0xff shl 24) or (gray shl 16) or (gray shl 8) or gray }.toIntArray(),3,2,Bitmap.Config.ARGB_8888).let { bitmap -> try { ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }.toByteArray() } finally { bitmap.recycle() } }
        var forbid = false; var providerReads = 0
        private val budget = ApplicationImageBudget.create(64)
        private val provider = SourceInput { check(!forbid); providerReads++; ByteArrayInputStream(png) }
        var graph: ApplicationImports? = null
            private set
        suspend fun ingest(): IngestResult.Committed {
            val opened = graph ?: ApplicationServices(context,budget,provider).imports().also { graph = it }
            val doc = opened.store.documents.createDocument("Editor persistence fixture")
            val batch = opened.store.imports.beginImport(doc,1,null)
            return opened.photoImports.importProviderReferences(listOf("content://fixture.documents/editor"),batch.parentId).single() as IngestResult.Committed
        }
        suspend fun reopen() { close(); graph = null; graph = ApplicationServices(context,budget,provider).imports() }
        fun raw(): SQLiteDatabase = SQLiteDatabase.openDatabase(context.getDatabasePath("pristinepage.db").path,null,SQLiteDatabase.OPEN_READWRITE)
        fun signature(): List<String> = raw().use { db ->
            listOf("SELECT documentId,documentRevision FROM Document ORDER BY documentId","SELECT pageId,sourceId,currentRecipeRevision FROM Page ORDER BY pageId","SELECT pageId,recipeRevision,sourceId,recipeSchemaVersion,typeof(canonicalRecipe),length(canonicalRecipe),recipeSha256 FROM RecipeRevision ORDER BY pageId,recipeRevision","SELECT ownerType,ownerId,sourceId FROM SourceReference ORDER BY ownerType,ownerId,sourceId","SELECT undoId,documentId,action,appliedDocumentRevision,state FROM UndoEntry ORDER BY undoId").flatMap { sql ->
                db.rawQuery(sql,null).use { cursor -> buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).joinToString("\u0000") { column -> if (cursor.isNull(column)) "<null>" else cursor.getString(column) }) } }
            }
        }
        override fun close() { graph?.store?.close() }
    }

    private companion object {
        fun guardedSave(result: SaveRecipeResult) { assertTrue("Guarded recipe save is absent: $result",result is SaveRecipeResult.Rejected && result.error.code == OperationErrorCode.INTEGRITY_FAILED) }
        fun reader(graph: ApplicationImports): EditablePages = try { graph.store.javaClass.getMethod("getEditablePages").invoke(graph.store) as EditablePages } catch (_: NoSuchMethodException) { throw AssertionError("Application-backed editor persistence is unavailable") }
    }
}
