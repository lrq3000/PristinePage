package app.pristinepage.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.core.ocr.OcrInput
import app.pristinepage.core.ocr.OcrLayout
import app.pristinepage.core.ocr.OcrText
import app.pristinepage.data.db.AtelierDraftMigration
import app.pristinepage.data.db.OcrIndexMigration
import app.pristinepage.data.db.OcrResultRow
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.repository.OcrSearchTerms
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoomOcrIndexIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    @get:Rule val migration = MigrationTestHelper(instrumentation, PristineDatabase::class.java)

    @Test fun populatedV1AndV2MigrateWithoutInventedTextAndRetainTheIndexOnReopen() {
        for (version in 1..2) {
            val name = "ocr-migration-${UUID.randomUUID()}"
            fun open() = Room.databaseBuilder(context, PristineDatabase::class.java, name)
                .addMigrations(AtelierDraftMigration, OcrIndexMigration).build()
            try {
                migration.createDatabase(name, version).use(::seed)
                val database = open()
                try {
                    val sql = database.openHelper.writableDatabase
                    assertEquals(3, sql.version)
                    assertEquals("010203", sql.scalar("SELECT hex(canonicalRecipe) FROM RecipeRevision"))
                    assertEquals("Existing album", sql.scalar("SELECT title FROM Document"))
                    assertEquals("0", sql.scalar("SELECT COUNT(*) FROM OcrResult"))
                    insertText(sql)
                    assertEquals("1", sql.scalar("SELECT COUNT(*) FROM OcrResultFts WHERE OcrResultFts MATCH 'ecole'"))
                    sql.execSQL("UPDATE OcrResult SET indexedText='replacement' WHERE pageId=?", arrayOf(PAGE))
                    assertEquals("0", sql.scalar("SELECT COUNT(*) FROM OcrResultFts WHERE OcrResultFts MATCH 'ecole'"))
                } finally { database.close() }
                val reopened = open()
                try {
                    assertEquals("1", reopened.openHelper.writableDatabase.scalar("SELECT COUNT(*) FROM OcrResultFts WHERE OcrResultFts MATCH 'replacement'"))
                } finally { reopened.close() }
            } finally { context.deleteDatabase(name) }
        }
    }

    @Test fun freshIndexOnlyReturnsLivePageRevisionAndRollsBackTogetherWithText() {
        val database = Room.inMemoryDatabaseBuilder(context, PristineDatabase::class.java).build()
        try {
            val sql = database.openHelper.writableDatabase
            seed(sql)
            insertText(sql)
            val dao = database.ocrResults()
            fun matches(term: String = "ecole") = dao.matchPages(checkNotNull(OcrSearchTerms.create(term)), null, 2).map { it.pageId }
            assertEquals(listOf(PAGE), matches())
            sql.beginTransaction()
            try {
                sql.execSQL("UPDATE OcrResult SET indexedText='transient' WHERE pageId=?", arrayOf(PAGE))
                assertEquals(listOf(PAGE), matches("transient"))
            } finally { sql.endTransaction() }
            assertEquals(listOf(PAGE), matches())
            assertTrue(matches("transient").isEmpty())
            sql.execSQL("UPDATE OcrResult SET recipeRevision=2 WHERE pageId=?", arrayOf(PAGE))
            assertTrue(matches().isEmpty())
            sql.execSQL("UPDATE OcrResult SET recipeRevision=1 WHERE pageId=?", arrayOf(PAGE))
            sql.execSQL("UPDATE Page SET state='REMOVED', ordinal=NULL, removedAt=1 WHERE pageId=?", arrayOf(PAGE))
            assertTrue(matches().isEmpty())
            sql.execSQL("UPDATE Page SET state='ACTIVE', ordinal=0, removedAt=NULL WHERE pageId=?", arrayOf(PAGE))
            sql.execSQL("""INSERT INTO AtelierDraft (draftId, stagingDocumentId, state, generation, step, createdAt) VALUES (?,?,'EDITING',1,1,0)""", arrayOf(DRAFT, DOCUMENT))
            assertTrue(matches().isEmpty())
        } finally { database.close() }
    }

    @Test fun findsAnOrderedPhraseInsideUnspacedCjkWithoutMatchingSkippedCharacters() {
        val database = Room.inMemoryDatabaseBuilder(context, PristineDatabase::class.java).build()
        try {
            val sql = database.openHelper.writableDatabase
            seed(sql)
            val text = "東京都の領収書です"
            insertRecognition(sql, text)
            val dao = database.ocrResults()
            fun matches(query: String) = dao.matchPages(checkNotNull(OcrSearchTerms.create(query)), null, 2).map { it.pageId }
            assertEquals(listOf(PAGE), matches("領収書"))
            assertTrue(matches("領書").isEmpty())
            assertEquals(text, sql.scalar("SELECT COALESCE(verbatimText,indexedText) FROM OcrResult"))
        } finally { database.close() }
    }

    @Test fun voicedKanaSearchMatchesBothWidthsButNotSeparateCharacters() {
        val database = Room.inMemoryDatabaseBuilder(context, PristineDatabase::class.java).build()
        try {
            val sql = database.openHelper.writableDatabase
            seed(sql)
            val dao = database.ocrResults()
            fun matches(query: String) = dao.matchPages(checkNotNull(OcrSearchTerms.create(query)), null, 2).map { it.pageId }
            insertRecognition(sql, "ｶ ｷﾞ")
            assertTrue(matches("ｶﾞ").isEmpty())
            assertTrue(matches("ガ").isEmpty())
            sql.execSQL("DELETE FROM OcrResult WHERE pageId=?", arrayOf(PAGE))
            val text = "ｷｶﾞｺ"
            insertRecognition(sql, text)
            assertEquals(listOf(PAGE), matches("ｶﾞ"))
            assertEquals(listOf(PAGE), matches("ガ"))
            assertEquals(text, sql.scalar("SELECT COALESCE(verbatimText,indexedText) FROM OcrResult"))
        } finally { database.close() }
    }

    private fun insertRecognition(sql: SupportSQLiteDatabase, original: String) {
        val row = OcrResultRow.fromRecognition(OcrInput(PAGE, 1, PIXELS),
            OcrConfiguration("fixture", 1, OcrLayout.AUTOMATIC), OcrText(original))
        sql.execSQL("""INSERT INTO OcrResult (pageId,recipeRevision,sourcePixelSha256,engineId,engineVersion,layout,indexedText,verbatimText)
            VALUES (?,1,?,'fixture',1,'AUTOMATIC',?,?)""", arrayOf(PAGE, PIXELS, row.indexedText, row.verbatimText))
    }

    private fun seed(sql: SupportSQLiteDatabase) {
        sql.beginTransaction()
        try {
            sql.execSQL("INSERT INTO Document VALUES (?,'Existing album',1,'ACTIVE',0,0,NULL)", arrayOf(DOCUMENT))
            sql.execSQL("INSERT INTO SourceBlob VALUES (?,?,4,'image/png',3,2,?,'READY',1,?,0)", arrayOf(BLOB, "a".repeat(64), "sources/$BLOB/original.bin", DRAFT))
            sql.execSQL("INSERT INTO Source VALUES (?,?,?,3,2,1,'srgb-v1',NULL,0,'READY',0)", arrayOf(SOURCE, BLOB, PIXELS))
            sql.execSQL("INSERT INTO Page VALUES (?,?,?,1,'ACTIVE',0,NULL,0)", arrayOf(PAGE, DOCUMENT, SOURCE))
            sql.execSQL("INSERT INTO RecipeRevision VALUES (?,1,?,1,X'010203',?,NULL,0)", arrayOf(PAGE, SOURCE, "c".repeat(64)))
            sql.setTransactionSuccessful()
        } finally { sql.endTransaction() }
    }

    private fun insertText(sql: SupportSQLiteDatabase) {
        sql.execSQL("""INSERT INTO OcrResult (pageId,recipeRevision,sourcePixelSha256,engineId,engineVersion,layout,indexedText)
            VALUES (?,1,?,'fixture',1,'AUTOMATIC','École invoice')""", arrayOf(PAGE, PIXELS))
    }

    private fun SupportSQLiteDatabase.scalar(sql: String) = query(sql).use { check(it.moveToFirst()); it.getString(0) }

    private companion object {
        private const val DOCUMENT = "aaaaaaaa-aaaa-4aaa-8aaa-000000000001"
        private const val SOURCE = "aaaaaaaa-aaaa-4aaa-8aaa-000000000002"
        private const val BLOB = "aaaaaaaa-aaaa-4aaa-8aaa-000000000003"
        private const val PAGE = "aaaaaaaa-aaaa-4aaa-8aaa-000000000004"
        private const val DRAFT = "aaaaaaaa-aaaa-4aaa-8aaa-000000000005"
        private val PIXELS = "b".repeat(64)
    }
}
