package app.pristinepage.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.core.ocr.OcrInput
import app.pristinepage.core.ocr.OcrLanguagePack
import app.pristinepage.core.ocr.OcrLanguagePacks
import app.pristinepage.core.ocr.OcrLayout
import app.pristinepage.core.ocr.OcrText
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.data.db.AtelierDraftMigration
import app.pristinepage.data.db.OcrIndexMigration
import app.pristinepage.data.db.OcrResultRow
import app.pristinepage.data.db.OcrWorkMigration
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.EditablePage
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.PageId
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.SourceId
import app.pristinepage.data.repository.OcrJobInput
import app.pristinepage.data.repository.OcrSearchTerms
import app.pristinepage.data.repository.RoomMetadataAccess
import app.pristinepage.data.repository.RoomOcrCurrentWork
import app.pristinepage.data.repository.RoomOcrQueue
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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

    @Test fun populatedV1ToV3MigrateWithoutInventingWorkAndRetainTheIndexOnReopen() {
        for (version in 1..3) {
            val name = "ocr-migration-${UUID.randomUUID()}"
            fun open() = Room.databaseBuilder(context, PristineDatabase::class.java, name)
                .addMigrations(AtelierDraftMigration, OcrIndexMigration, OcrWorkMigration).build()
            try {
                migration.createDatabase(name, version).use { sql ->
                    seed(sql)
                    if (version == 3) insertText(sql)
                }
                val database = open()
                try {
                    val sql = database.openHelper.writableDatabase
                    assertEquals(4, sql.version)
                    assertEquals("010203", sql.scalar("SELECT hex(canonicalRecipe) FROM RecipeRevision"))
                    assertEquals("Existing album", sql.scalar("SELECT title FROM Document"))
                    assertEquals("0", sql.scalar("SELECT COUNT(*) FROM OcrPageWork"))
                    assertEquals("0", sql.scalar("SELECT COUNT(*) FROM Job"))
                    assertEquals(if (version == 3) "1" else "0", sql.scalar("SELECT COUNT(*) FROM OcrResult"))
                    if (version < 3) insertText(sql)
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

    @Test fun currentJobRecoversExactStoredPacksButRefusesHiddenStaleOrMisboundWork() {
        val database = Room.inMemoryDatabaseBuilder(context, PristineDatabase::class.java).build()
        try {
            val sql = database.openHelper.writableDatabase
            seed(sql)
            val reader = RoomOcrCurrentWork(database)
            val metadata = RoomMetadataAccess(database)
            val page = PageId(PAGE)
            fun current() = metadata.transaction { reader.readInTransaction(page) }
            assertNull(current())
            assertThrows(IllegalStateException::class.java) { reader.readInTransaction(page) }
            val jobId = UUID.randomUUID().toString()
            val config = OcrConfiguration("fixture", 1, OcrLayout.AUTOMATIC,
                OcrLanguagePacks(listOf(OcrLanguagePack("fra", "d".repeat(64)), OcrLanguagePack("eng", "e".repeat(64)))))
            val binding = OcrJobInput(DocumentId(DOCUMENT), OcrInput(PAGE, 1, PIXELS), SourceId(SOURCE), "c".repeat(64), 1, config)
            sql.execSQL("""INSERT INTO Job (jobId,kind,idempotenceKey,canonicalInputSha256,input,state,attempt,leaseGeneration,createdAt,updatedAt)
                VALUES (?,'PAGE_OCR',?,?,?,'PENDING',0,0,10,10)""",
                arrayOf(jobId, binding.key, binding.digest, binding.encode()))
            sql.execSQL("INSERT INTO OcrPageWork VALUES (?,?,1,?,?,NULL,NULL)",
                arrayOf<Any>(PAGE, jobId, binding.digest, binding.encode().size))
            assertEquals(binding, current()?.intent)
            assertEquals("PENDING", current()?.job?.state)

            sql.execSQL("INSERT INTO RecipeRevision VALUES (?,2,?,1,X'010203',?,NULL,0)",
                arrayOf(PAGE, SOURCE, "c".repeat(64)))
            sql.execSQL("UPDATE Page SET currentRecipeRevision=2 WHERE pageId=?", arrayOf(PAGE))
            assertEquals(OperationErrorCode.REVISION_CONFLICT, assertThrows(RepositoryFailure::class.java) { current() }.error.code)
            sql.execSQL("UPDATE Page SET currentRecipeRevision=1 WHERE pageId=?", arrayOf(PAGE))
            sql.execSQL("""INSERT INTO AtelierDraft (draftId,stagingDocumentId,state,generation,step,createdAt)
                VALUES (?,?,'EDITING',1,1,0)""", arrayOf(DRAFT, DOCUMENT))
            assertEquals(OperationErrorCode.REVISION_CONFLICT, assertThrows(RepositoryFailure::class.java) { current() }.error.code)
            sql.execSQL("DELETE FROM AtelierDraft WHERE draftId=?", arrayOf(DRAFT))

            val receipt = "atelier-publish-v1\n1\n$DOCUMENT\n$PAGE,1,${"c".repeat(64)},$PAGE"
            sql.execSQL("""INSERT INTO AtelierDraft (draftId,stagingDocumentId,state,generation,step,createdAt,committedDocumentId,receiptPageIds)
                VALUES (?,?,'COMMITTED',2,2,0,?,?)""", arrayOf(DRAFT, DOCUMENT, DOCUMENT, receipt))
            assertEquals(binding, current()?.intent)
            sql.execSQL("UPDATE AtelierDraft SET receiptPageIds=? WHERE draftId=?", arrayOf(receipt.replace("$PAGE,1,", "$PAGE,0,"), DRAFT))
            assertEquals(false, database.atelierDrafts().savedDocumentIsHidden(DOCUMENT))
            assertEquals(OperationErrorCode.REVISION_CONFLICT, assertThrows(RepositoryFailure::class.java) { current() }.error.code)
            sql.execSQL("DELETE FROM AtelierDraft WHERE draftId=?", arrayOf(DRAFT))

            val wrong = binding.copy(generation = 2)
            sql.execSQL("UPDATE Job SET input=?,canonicalInputSha256=? WHERE jobId=?", arrayOf(wrong.encode(), wrong.digest, jobId))
            sql.execSQL("UPDATE OcrPageWork SET inputSha256=? WHERE pageId=?", arrayOf(wrong.digest, PAGE))
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, assertThrows(RepositoryFailure::class.java) { current() }.error.code)
        } finally { database.close() }
    }

    @Test fun queueDeduplicatesTheSavedInputAndExplicitRerunRetainsItsProducingJob() {
        val database = Room.inMemoryDatabaseBuilder(context, PristineDatabase::class.java).build()
        try {
            val sql = database.openHelper.writableDatabase
            val page = seedQueuePage(sql)
            val queue = RoomOcrQueue(database)
            val config = queueConfig(OcrLayout.AUTOMATIC)
            val newDefault = queueConfig(OcrLayout.SINGLE_BLOCK)
            val first = runBlocking {
                val signals = List(2) { async { queue.enqueue(page, config, rerun = false) } }
                val ids = signals.map { it.await() }
                assertEquals(ids[0], ids[1])
                assertEquals(ids[0], queue.enqueue(page, newDefault, rerun = false))
                ids[0]
            }
            assertEquals("1", sql.scalar("SELECT COUNT(*) FROM Job"))
            assertEquals("1", sql.scalar("SELECT COUNT(*) FROM SourceReference WHERE ownerType='JOB'"))
            assertEquals("3", sql.scalar("SELECT refCount FROM Source"))
            sql.execSQL("UPDATE Job SET state='SUCCEEDED' WHERE jobId=?", arrayOf(first.value))
            sql.execSQL("UPDATE OcrPageWork SET resultJobId=?,projectionVersion=1 WHERE pageId=?", arrayOf(first.value, PAGE))
            val second = runBlocking { queue.enqueue(page, newDefault, rerun = true) }
            assertNotEquals(first, second)
            val current = RoomMetadataAccess(database).transaction { RoomOcrCurrentWork(database).readInTransaction(PageId(PAGE)) }
            assertEquals(2L, current?.intent?.generation)
            assertEquals(newDefault, current?.intent?.configuration)
            assertEquals(first, current?.resultJobId)
            assertEquals(1, current?.projectionVersion)
            assertEquals("2", sql.scalar("SELECT COUNT(*) FROM SourceReference WHERE ownerType='JOB'"))
            assertEquals("4", sql.scalar("SELECT refCount FROM Source"))
            assertEquals("2", sql.scalar("SELECT COUNT(*) FROM SourceReference WHERE ownerType IN ('PAGE','RECIPE_HISTORY')"))
        } finally { database.close() }
    }

    @Test fun queueDoesNotCommitAJobOrItsPinAfterThePointerWriteIsRefused() {
        val database = Room.inMemoryDatabaseBuilder(context, PristineDatabase::class.java).build()
        try {
            val sql = database.openHelper.writableDatabase
            val page = seedQueuePage(sql)
            sql.execSQL("""CREATE TRIGGER refuse_ocr_pointer BEFORE INSERT ON OcrPageWork
                BEGIN SELECT RAISE(ABORT,'test_pointer_refusal'); END""")
            val error = assertThrows(RepositoryFailure::class.java) {
                runBlocking { RoomOcrQueue(database).enqueue(page, queueConfig(OcrLayout.AUTOMATIC), rerun = false) }
            }
            assertEquals(OperationErrorCode.STORAGE_FAILED, error.error.code)
            for (table in listOf("OcrPageWork", "Job")) {
                assertEquals("0", sql.scalar("SELECT COUNT(*) FROM $table"))
            }
            assertEquals("0", sql.scalar("SELECT COUNT(*) FROM SourceReference WHERE ownerType='JOB'"))
            assertEquals("2", sql.scalar("SELECT COUNT(*) FROM SourceReference"))
            assertEquals("2", sql.scalar("SELECT refCount FROM Source"))
        } finally { database.close() }
    }

    @Test fun queueRollsBackWhenTheFinalPointerWriteRestoresAnIncorrectRefCount() {
        val database = Room.inMemoryDatabaseBuilder(context, PristineDatabase::class.java).build()
        try {
            val sql = database.openHelper.writableDatabase
            val page = seedQueuePage(sql)
            sql.execSQL("""CREATE TRIGGER revert_count AFTER INSERT ON OcrPageWork
                BEGIN UPDATE Source SET refCount=2; END""")
            val error = assertThrows(RepositoryFailure::class.java) {
                runBlocking { RoomOcrQueue(database).enqueue(page, queueConfig(OcrLayout.AUTOMATIC), rerun = false) }
            }
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, error.error.code)
            assertEquals("0", sql.scalar("SELECT COUNT(*) FROM OcrPageWork"))
            assertEquals("0", sql.scalar("SELECT COUNT(*) FROM Job"))
            assertEquals("0", sql.scalar("SELECT COUNT(*) FROM SourceReference WHERE ownerType='JOB'"))
            assertEquals("2", sql.scalar("SELECT COUNT(*) FROM SourceReference"))
            assertEquals("2", sql.scalar("SELECT refCount FROM Source"))
        } finally { database.close() }
    }

    @Test fun queueRefusesHiddenAndStaleInputsAndAdvancesOnceAfterASave() {
        val database = Room.inMemoryDatabaseBuilder(context, PristineDatabase::class.java).build()
        try {
            val sql = database.openHelper.writableDatabase
            val page = seedQueuePage(sql)
            val queue = RoomOcrQueue(database)
            val config = queueConfig(OcrLayout.AUTOMATIC)
            fun queueSaved(candidate: EditablePage) = runBlocking { queue.enqueue(candidate, config, rerun = false) }
            sql.execSQL("""INSERT INTO AtelierDraft (draftId,stagingDocumentId,state,generation,step,createdAt)
                VALUES (?,?,'EDITING',1,1,0)""", arrayOf(DRAFT, DOCUMENT))
            assertEquals(OperationErrorCode.REVISION_CONFLICT, assertThrows(RepositoryFailure::class.java) { queueSaved(page) }.error.code)
            assertEquals("0", sql.scalar("SELECT COUNT(*) FROM Job"))
            sql.execSQL("DELETE FROM AtelierDraft WHERE draftId=?", arrayOf(DRAFT))
            val original = queueSaved(page)
            sql.execSQL("INSERT INTO RecipeRevision VALUES (?,2,?,1,?,?,1,0)",
                arrayOf<Any>(PAGE, SOURCE, RecipeCodec.encode(page.recipe), page.recipeHash))
            sql.execSQL("INSERT INTO SourceReference VALUES ('RECIPE_HISTORY',?,?,0)", arrayOf("recipe-v1/$PAGE/2", SOURCE))
            sql.execSQL("UPDATE Source SET refCount=4")
            sql.execSQL("UPDATE Page SET currentRecipeRevision=2")
            assertEquals(OperationErrorCode.REVISION_CONFLICT, assertThrows(RepositoryFailure::class.java) { queueSaved(page) }.error.code)
            assertEquals("1", sql.scalar("SELECT COUNT(*) FROM Job"))
            val latest = page.copy(recipeRevision = 2)
            val next = queueSaved(latest)
            assertNotEquals(original, next)
            assertEquals(next, queueSaved(latest))
            assertEquals("2", sql.scalar("SELECT COUNT(*) FROM Job"))
            assertEquals("2", sql.scalar("SELECT generation FROM OcrPageWork"))
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

    private fun seedQueuePage(sql: SupportSQLiteDatabase): EditablePage {
        seed(sql)
        val recipe = Recipe(geometry = GeometryRecipe.None(SourceBinding(PIXELS, PixelSize(3, 2), "srgb-v1")))
        val hash = RecipeCodec.hash(recipe)
        sql.execSQL("UPDATE RecipeRevision SET canonicalRecipe=?,recipeSha256=? WHERE pageId=?",
            arrayOf(RecipeCodec.encode(recipe), hash, PAGE))
        sql.execSQL("INSERT INTO SourceReference VALUES ('PAGE',?,?,0)", arrayOf(PAGE, SOURCE))
        sql.execSQL("INSERT INTO SourceReference VALUES ('RECIPE_HISTORY',?,?,0)", arrayOf("recipe-v1/$PAGE/1", SOURCE))
        sql.execSQL("UPDATE Source SET refCount=2")
        return EditablePage(DocumentId(DOCUMENT), 1, PageId(PAGE), SourceId(SOURCE), 1, hash, recipe)
    }

    private fun queueConfig(layout: OcrLayout) = OcrConfiguration("fixture", 1, layout,
        OcrLanguagePacks(listOf(OcrLanguagePack("eng", "d".repeat(64)))))

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
