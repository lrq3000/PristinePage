package app.pristinepage.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.data.db.*
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android Room/SQLite with operation-owned synthetic metadata only. Databases are retained in the test package for evidence, never deleted. Closing/reopening is not called process-death or decoder/file-ingestion acceptance. */
@RunWith(AndroidJUnit4::class)
class RoomRepositoryIntegrationTest {
    @Test fun populatedDatabaseReopensWithImmutableHistoryAndReferences() = runBlocking {
        Fixture().use { f ->
            val page = f.pages[0]
            assertTrue(f.repository.saveRecipe(page, 1, f.turned(1)) is SaveRecipeResult.Saved)
            val old = f.database.catalog().recipe(page.value, 1)!!.canonicalRecipe.copyOf()
            f.reopen()
            val q = f.database.catalog()
            assertEquals(2L, q.page(page.value)!!.currentRecipeRevision)
            assertArrayEquals(old, q.recipe(page.value, 1)!!.canonicalRecipe)
            assertEquals(1, RecipeCodec.decode(q.recipe(page.value, 2)!!.canonicalRecipe).geometry.outputQuarterTurns)
            assertEquals(q.countSourceReferences(f.source), q.source(f.source)!!.refCount)
            f.assertForeignKeys()
        }
    }

    @Test fun sqliteFailureOnSecondRecipeRollsBackWholeBatchAndUndoPins() = runBlocking {
        Fixture().use { f ->
            // A real SQLite failure, not a fake transaction rollback. Only this fresh test database has the injected trigger.
            f.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_second_recipe BEFORE INSERT ON RecipeRevision WHEN NEW.pageId='${f.pages[1].value}' AND NEW.recipeRevision=2 BEGIN SELECT RAISE(ABORT,'synthetic recipe write fault'); END")
            val result = f.repository.applyBatchRecipes(f.document, 1, f.pages.map { f.change(it, 1) })
            assertTrue(result is DocumentMutationResult.Rejected)
            f.reopen()
            assertEquals(1L, f.database.catalog().document(f.document.value)!!.documentRevision)
            assertEquals(listOf(1L, 1L), f.pages.map { f.database.catalog().page(it.value)!!.currentRecipeRevision })
            assertEquals(2L, f.count("RecipeRevision")); assertEquals(0L, f.count("UndoEntry"))
            assertEquals(4L, f.count("SourceReference")); assertEquals(4L, f.database.catalog().source(f.source)!!.refCount)
            f.assertForeignKeys()
        }
    }

    @Test fun duplicateRenumbersUnderImmediateUniqueConstraintAndKeepsIndependentHistory() = runBlocking {
        Fixture().use { f ->
            val result = f.repository.duplicatePage(f.pages[0], 1) as DocumentMutationResult.Applied
            val added = result.affectedPageIds.single()
            assertTrue(f.repository.saveRecipe(added, 1, f.turned(3)) is SaveRecipeResult.Saved)
            f.reopen()
            val q = f.database.catalog()
            assertEquals(listOf(f.pages[0].value, added.value, f.pages[1].value), q.activePages(f.document.value).map { it.pageId })
            assertEquals(listOf(0, 1, 2), q.activePages(f.document.value).map { it.ordinal })
            assertEquals(f.source, q.page(added.value)!!.sourceId)
            assertEquals(1L, q.page(f.pages[0].value)!!.currentRecipeRevision)
            assertEquals(2L, q.page(added.value)!!.currentRecipeRevision)
            assertEquals(q.countSourceReferences(f.source), q.source(f.source)!!.refCount)
            f.assertForeignKeys()
        }
    }

    @Test fun successiveSavedBatchUndosCreateNewRevisionsOnRoom() = runBlocking {
        Fixture().use { f ->
            val first = f.repository.applyBatchRecipes(f.document, 1, f.pages.map { f.change(it, 1) }) as DocumentMutationResult.Applied
            val second = f.repository.applyBatchRecipes(f.document, 2, f.pages.map { f.change(it, 2, 2) }) as DocumentMutationResult.Applied
            assertTrue(f.repository.undoDocumentEdit(second.undoId!!, 3) is DocumentMutationResult.Applied)
            assertTrue(f.repository.undoDocumentEdit(first.undoId!!, 4) is DocumentMutationResult.Applied)
            f.reopen()
            val q = f.database.catalog()
            assertEquals(5L, q.document(f.document.value)!!.documentRevision)
            for (page in f.pages) {
                assertEquals(5L, q.page(page.value)!!.currentRecipeRevision)
                assertEquals(0, RecipeCodec.decode(q.recipe(page.value, 5)!!.canonicalRecipe).geometry.outputQuarterTurns)
            }
            assertEquals(10L, f.count("RecipeRevision")); assertEquals(2L, f.count("UndoEntry"))
            assertEquals(q.countSourceReferences(f.source), q.source(f.source)!!.refCount)
            f.assertForeignKeys()
        }
    }

    @Test fun unsupportedFutureDatabaseIsNotDestructivelyReset() = runBlocking {
        Fixture().use { f ->
            val database = f.database.openHelper.writableDatabase
            val futureVersion = database.version + 1
            database.version = futureVersion
            f.database.close()
            var rejected = false
            try { f.reopen(); f.database.catalog().document(f.document.value) }
            catch (_: IllegalStateException) { rejected = true }
            assertTrue("An unsupported populated schema must not be reset", rejected)
            SQLiteDatabase.openDatabase(f.context.getDatabasePath(f.name).path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
                assertEquals(futureVersion, raw.version)
                raw.rawQuery("SELECT COUNT(*) FROM Page", null).use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals(2L, cursor.getLong(0)) }
            }
        }
    }

    internal class Fixture(count: Int = 2) : AutoCloseable {
        val context: Context = InstrumentationRegistry.getInstrumentation().context
        val name = "t03-room-${UUID.randomUUID()}.db"
        val source = UUID.randomUUID().toString()
        private val blob = UUID.randomUUID().toString()
        val pages = List(count) { PageId(UUID.randomUUID().toString()) }
        private val binding = SourceBinding("a".repeat(64), PixelSize(3, 2), "srgb-v1")
        private val initial = Recipe(geometry = GeometryRecipe.None(binding))
        var database = open()
            private set
        var repository = repository()
            private set
        val document = runBlocking { repository.createDocument("Synthetic Room fixture") }

        init {
            database.runInTransaction {
                val sql = database.openHelper.writableDatabase
                sql.execSQL("INSERT INTO SourceBlob(sourceBlobId,byteSha256,byteCount,mime,encodedWidth,encodedHeight,relativePath,state,sourceCount,ingestTransactionId,createdAt) VALUES(?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any>(blob, "b".repeat(64), 4L, "image/png", 3, 2, "sources/$blob/original.bin", "READY", 1L, UUID.randomUUID().toString(), 0L))
                sql.execSQL("INSERT INTO Source(sourceId,sourceBlobId,pixelSha256,width,height,exifOrientation,conversionFingerprint,profileSha256,refCount,state,createdAt) VALUES(?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(source, blob, binding.sourcePixelHash, 3, 2, 1, "srgb-v1", null, pages.size * 2L, "READY", 0L))
                val q = database.catalog()
                for ((ordinal, page) in pages.withIndex()) {
                    q.insertPage(PageRow(page.value, document.value, source, 1, "ACTIVE", ordinal, null, 0))
                    q.insertRecipe(RecipeRevisionRow(page.value, 1, source, 1, RecipeCodec.encode(initial), RecipeCodec.hash(initial), null, 0))
                    q.insertReference(SourceReferenceRow("PAGE", page.value, source, 0))
                    q.insertReference(SourceReferenceRow("RECIPE_HISTORY", "recipe-v1/${page.value}/1", source, 0))
                }
            }
        }

        fun turned(q: Int) = initial.copy(geometry = initial.geometry.withOutputQuarterTurns(q))
        fun change(page: PageId, q: Int, revision: Long = 1) = BatchRecipeChange(page, revision, turned(q))
        private fun open(): PristineDatabase = Room.databaseBuilder(context, PristineDatabase::class.java, name).build()
        private fun repository() = RoomDocumentRepository(RoomMetadataAccess(database), RecipeAdmission { _, _ -> null })
        fun reopen() { database.close(); database = open(); repository = repository() }
        fun count(table: String): Long {
            require(table in setOf("RecipeRevision", "UndoEntry", "SourceReference", "LibraryScanName"))
            return database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }
        }
        /** Complete bounded synthetic metadata, including BLOB bytes. Availability-only expiry may explicitly ignore just UndoEntry.state; all history/pins/pointers/content remain compared. No production database or image file is inspected. */
        fun metadataState(ignoreUndoState: Boolean = false): List<List<List<String>>> = listOf("Document", "Page", "Source", "SourceBlob", "RecipeRevision", "SourceReference", "UndoEntry", "Mask", "RecipeMask", "MaskReference").map { table ->
            database.openHelper.readableDatabase.query("SELECT * FROM $table LIMIT 1001").use { cursor ->
                val rows = mutableListOf<List<String>>()
                while (cursor.moveToNext()) rows.add((0 until cursor.columnCount).map { column ->
                    if (ignoreUndoState && table == "UndoEntry" && cursor.getColumnName(column) == "state") "IGNORED_UNDO_STATE"
                    else when (cursor.getType(column)) {
                        android.database.Cursor.FIELD_TYPE_NULL -> "NULL"
                        android.database.Cursor.FIELD_TYPE_BLOB -> "B:" + java.util.Base64.getEncoder().encodeToString(cursor.getBlob(column))
                        else -> cursor.getType(column).toString() + ":" + cursor.getString(column)
                    }
                })
                check(rows.size <= 1000); rows.sortedBy { it.toString() }
            }
        }
        fun assertForeignKeys() { database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { cursor -> assertFalse(cursor.moveToFirst()) } }
        override fun close() { database.close() }
    }
}
