package app.pristinepage.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.core.ocr.OcrError
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
import app.pristinepage.data.files.SourceInspector
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.EditablePage
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.PageId
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.SourceId
import app.pristinepage.data.repository.OcrJobInput
import app.pristinepage.data.repository.OcrSearchTerms
import app.pristinepage.data.repository.AcquireLeaseResult
import app.pristinepage.data.repository.JobLease
import app.pristinepage.data.repository.LeaseOwnerLiveness
import app.pristinepage.data.repository.LeaseOwnerState
import app.pristinepage.data.repository.OperationOwnerProof
import app.pristinepage.data.repository.RoomMetadataAccess
import app.pristinepage.data.repository.RoomOcrAttempts
import app.pristinepage.data.repository.RoomOcrBackfill
import app.pristinepage.data.repository.RoomOcrCurrentWork
import app.pristinepage.data.repository.RoomOcrDispatch
import app.pristinepage.data.repository.RoomOcrExecution
import app.pristinepage.data.repository.FileOperationOwners
import app.pristinepage.data.repository.RoomOcrQueue
import app.pristinepage.data.repository.RoomOcrResults
import app.pristinepage.data.repository.RoomOcrSearch
import app.pristinepage.data.repository.SavedOcrWork
import app.pristinepage.data.repository.PrivateImportStore
import app.pristinepage.data.repository.SourceInput
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class RoomOcrIndexIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    @get:Rule val migration = MigrationTestHelper(instrumentation, PristineDatabase::class.java)

    @Test fun populatedV1ToV3MigrateWithoutInventingWorkAndRetainTheIndexOnReopen() {
        for (version in 1..3) {
            val name = "ocr-migration-${UUID.randomUUID()}"
            assertFalse(context.getDatabasePath(name).exists())
            fun open() = Room.databaseBuilder(context, PristineDatabase::class.java, name)
                .addMigrations(AtelierDraftMigration, OcrIndexMigration, OcrWorkMigration).build()
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
            // Keep the distinct synthetic database under the self-target test package; device validation never deletes a guest database.
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

    @Test fun admissionIsFreeAndTheThirdFailureRetainsItsErrorWithoutAFourthStart() = withOcrAttempts { fixture ->
        val (sql, attempts, request, owners, _) = fixture
        assertTrue(attempts.admit(request))
        assertTrue(attempts.admit(request))
        assertEquals("ADMITTED:0:", sql.scalar("SELECT state||':'||attempt||':'||coalesce(errorCode,'') FROM Job"))
        for ((ordinal, error) in listOf(OcrError.ENGINE_FAILED, OcrError.INVALID_OUTPUT, OcrError.RESOURCE_LIMIT).withIndex()) {
            val owner = UUID.randomUUID().toString()
            owners[owner] = LeaseOwnerState.ALIVE
            val lease = (attempts.start(request, owner, 500) as AcquireLeaseResult.Granted).lease
            assertEquals((ordinal + 1).toLong(), lease.generation)
            assertFalse(attempts.start(request, owner, 500) is AcquireLeaseResult.Granted)
            val another = UUID.randomUUID().toString()
            owners[another] = LeaseOwnerState.ALIVE
            assertFalse(attempts.fail(request, JobLease(lease.jobId, another, lease.generation), error))
            val interruption = runCatching { attempts.fail(request, lease, OcrError.INTERRUPTED) }.exceptionOrNull()
            assertEquals(OperationErrorCode.INVALID_INPUT, (interruption as? RepositoryFailure)?.error?.code)
            assertTrue(attempts.fail(request, lease, error))
            assertFalse(attempts.fail(request, lease, OcrError.INVALID_OUTPUT))
            val state = if (ordinal == 2) "FAILED" else "ADMITTED"
            assertEquals("$state:${ordinal + 1}:${error.name}", sql.scalar("SELECT state||':'||attempt||':'||errorCode FROM Job"))
            assertEquals("0", sql.scalar("SELECT COUNT(*) FROM Job WHERE leaseOwner IS NOT NULL OR leaseUntil IS NOT NULL"))
        }
        val fourth = UUID.randomUUID().toString()
        owners[fourth] = LeaseOwnerState.ALIVE
        assertFalse(attempts.admit(request))
        assertFalse(attempts.start(request, fourth, 500) is AcquireLeaseResult.Granted)
        assertEquals("FAILED:3:RESOURCE_LIMIT", sql.scalar("SELECT state||':'||attempt||':'||errorCode FROM Job"))
    }

    @Test fun abandonedAttemptsRequirePositiveEndProofAndCountEvenBeforeTheRecordedDeadline() = withOcrAttempts { fixture ->
        val (sql, attempts, request, owners, clock) = fixture
        assertTrue(attempts.admit(request))
        repeat(3) { index ->
            val owner = UUID.randomUUID().toString()
            owners[owner] = LeaseOwnerState.ALIVE
            clock.set(10)
            val lease = (attempts.start(request, owner, 500) as AcquireLeaseResult.Granted).lease
            clock.set(1_000)
            assertFalse(attempts.recoverEnded(request, lease))
            owners[owner] = LeaseOwnerState.UNKNOWN
            assertFalse(attempts.recoverEnded(request, lease))
            assertEquals("RUNNING:${index + 1}", sql.scalar("SELECT state||':'||attempt FROM Job"))
            owners[owner] = LeaseOwnerState.ENDED
            val next = UUID.randomUUID().toString()
            owners[next] = LeaseOwnerState.ALIVE
            assertFalse(attempts.start(request, next, 500) is AcquireLeaseResult.Granted)
            assertEquals("RUNNING:${index + 1}", sql.scalar("SELECT state||':'||attempt FROM Job"))
            clock.set(1) // A restarted clock cannot authenticate an old persisted deadline.
            assertTrue(attempts.recoverEnded(request, lease))
            assertFalse(attempts.recoverEnded(request, lease))
            val state = if (index == 2) "FAILED" else "ADMITTED"
            assertEquals("$state:${index + 1}:INTERRUPTED", sql.scalar("SELECT state||':'||attempt||':'||errorCode FROM Job"))
            assertEquals("0", sql.scalar("SELECT COUNT(*) FROM Job WHERE leaseOwner IS NOT NULL OR leaseUntil IS NOT NULL"))
            assertFalse(attempts.start(request, owner, 500) is AcquireLeaseResult.Granted)
        }
    }

    @Test fun aLateLeaseWriteCannotGrantWorkAfterItsSourcePinIsRemoved() = withOcrAttempts { fixture ->
        val (sql, attempts, request, owners) = fixture
        assertTrue(attempts.admit(request))
        sql.execSQL("""CREATE TRIGGER remove_pin_on_start AFTER UPDATE OF state ON Job WHEN NEW.state='RUNNING'
            BEGIN DELETE FROM SourceReference WHERE ownerType='JOB' AND ownerId=NEW.jobId; END""")
        val owner = UUID.randomUUID().toString()
        owners[owner] = LeaseOwnerState.ALIVE
        val result = attempts.start(request, owner, 500)
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, (result as? AcquireLeaseResult.Rejected)?.error?.code)
        assertEquals("ADMITTED:0", sql.scalar("SELECT state||':'||attempt FROM Job"))
        assertEquals("1", sql.scalar("SELECT COUNT(*) FROM SourceReference WHERE ownerType='JOB'"))
    }

    @Test fun ignoredStartUpdateRollsBackItsTriggerSideEffects() = withOcrAttempts { fixture ->
        val (sql, attempts, request, owners) = fixture
        assertTrue(attempts.admit(request))
        sql.execSQL("""CREATE TRIGGER ignore_start_after_removing_pin BEFORE UPDATE OF state ON Job WHEN NEW.state='RUNNING'
            BEGIN DELETE FROM SourceReference WHERE ownerType='JOB' AND ownerId=NEW.jobId; SELECT RAISE(IGNORE); END""")
        val owner = UUID.randomUUID().toString()
        owners[owner] = LeaseOwnerState.ALIVE
        assertEquals(AcquireLeaseResult.Busy("ADMITTED"), attempts.start(request, owner, 500))
        assertEquals("ADMITTED:0", sql.scalar("SELECT state||':'||attempt FROM Job"))
        assertEquals("1", sql.scalar("SELECT COUNT(*) FROM SourceReference WHERE ownerType='JOB'"))
        assertEquals("3", sql.scalar("SELECT refCount FROM Source"))
    }

    @Test fun startingARerunRollsBackIfThePreviousResultLosesItsProvenance() = withOcrAttempts(previousResult = true) { fixture ->
        val (sql, attempts, request, owners) = fixture
        assertTrue(attempts.admit(request))
        val previous = sql.scalar("SELECT resultJobId FROM OcrPageWork")
        assertNotEquals(request.jobId.value, previous)
        sql.execSQL("""CREATE TRIGGER clear_provenance_on_start AFTER UPDATE OF state ON Job WHEN NEW.state='RUNNING'
            BEGIN UPDATE OcrPageWork SET resultJobId=NULL,projectionVersion=NULL WHERE jobId=NEW.jobId; END""")
        val owner = UUID.randomUUID().toString()
        owners[owner] = LeaseOwnerState.ALIVE
        val result = attempts.start(request, owner, 500)
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, (result as? AcquireLeaseResult.Rejected)?.error?.code)
        assertEquals("ADMITTED:0", sql.scalar("SELECT state||':'||attempt FROM Job JOIN OcrPageWork USING(jobId)"))
        assertEquals("$previous:1", sql.scalar("SELECT resultJobId||':'||projectionVersion FROM OcrPageWork"))
        assertEquals("Previous recognition", sql.scalar("SELECT COALESCE(verbatimText,indexedText) FROM OcrResult"))
    }

    @Test fun completionReplacesSearchTextAndRecordsTheActualProducingJob() = withOcrAttempts(previousResult = true) { f ->
        val lease = f.start()
        val original = "  École 東京都の領収書です\n"
        assertEquals(listOf(PAGE), f.matches("previous"))
        f.results.publish(f.request, lease, OcrText(original))
        assertEquals(original, f.sql.scalar("SELECT COALESCE(verbatimText,indexedText) FROM OcrResult"))
        assertEquals("${f.request.jobId.value}:1", f.sql.scalar("SELECT resultJobId||':'||projectionVersion FROM OcrPageWork"))
        assertEquals("SUCCEEDED:1", f.sql.scalar("SELECT state||':'||attempt FROM Job JOIN OcrPageWork USING(jobId)"))
        assertEquals(listOf(PAGE), f.matches("領収書"))
        assertEquals(emptyList<String>(), f.matches("previous"))
    }

    @Test fun completionRollsBackTextIndexAndProvenanceWhenTheFinalReadbackFails() = withOcrAttempts(previousResult = true) { f ->
        val previous = f.sql.scalar("SELECT resultJobId FROM OcrPageWork")
        val lease = f.start()
        f.sql.execSQL("""CREATE TRIGGER hide_result_after_success AFTER UPDATE OF state ON Job WHEN NEW.state='SUCCEEDED'
            BEGIN UPDATE OcrResult SET indexedText='wrong text'; END""")
        val failure = runCatching { f.results.publish(f.request, lease, OcrText("Replacement recognition")) }.exceptionOrNull()
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, (failure as? RepositoryFailure)?.error?.code)
        assertEquals("Previous recognition", f.sql.scalar("SELECT COALESCE(verbatimText,indexedText) FROM OcrResult"))
        assertEquals("$previous:1", f.sql.scalar("SELECT resultJobId||':'||projectionVersion FROM OcrPageWork"))
        assertEquals("RUNNING:1", f.sql.scalar("SELECT state||':'||attempt FROM Job JOIN OcrPageWork USING(jobId)"))
        assertEquals(listOf(PAGE), f.matches("previous"))
        assertEquals(emptyList<String>(), f.matches("replacement"))
        assertEquals(emptyList<String>(), f.matches("wrong"))
    }

    @Test fun anOldStillLiveLeaseCannotPublishAfterAnExplicitRerun() = withOcrAttempts { f ->
        val lease = f.start()
        val next = f.queueAgain()
        val failure = runCatching { f.results.publish(f.request, lease, OcrText("Superseded recognition")) }.exceptionOrNull()
        assertEquals(OperationErrorCode.REVISION_CONFLICT, (failure as? RepositoryFailure)?.error?.code)
        assertEquals("0", f.sql.scalar("SELECT COUNT(*) FROM OcrResult"))
        assertEquals("0", f.sql.scalar("SELECT COUNT(*) FROM OcrPageWork WHERE resultJobId IS NOT NULL"))
        assertEquals(next.value, f.sql.scalar("SELECT jobId FROM OcrPageWork"))
        assertEquals(emptyList<String>(), f.matches("superseded"))
    }

    @Test fun firstSuccessfulEmptyResultIsStoredAndCannotBeReplayed() = withOcrAttempts { f ->
        val lease = f.start()
        assertEquals("0", f.sql.scalar("SELECT COUNT(*) FROM OcrResult"))
        f.results.publish(f.request, lease, OcrText(""))
        assertEquals("1", f.sql.scalar("SELECT COUNT(*) FROM OcrResult WHERE indexedText='' AND verbatimText IS NULL"))
        assertEquals("${f.request.jobId.value}:1", f.sql.scalar("SELECT resultJobId||':'||projectionVersion FROM OcrPageWork"))
        assertEquals("SUCCEEDED", f.sql.scalar("SELECT state FROM Job"))
        val replay = runCatching { f.results.publish(f.request, lease, OcrText("Unexpected replay")) }.exceptionOrNull()
        assertEquals(OperationErrorCode.REVISION_CONFLICT, (replay as? RepositoryFailure)?.error?.code)
        assertEquals("", f.sql.scalar("SELECT indexedText FROM OcrResult"))
        assertEquals(emptyList<String>(), f.matches("replay"))
    }

    @Test fun searchKeepsTheActualPreviousProducerDuringARerunButRejectsAMisboundProducer() = withOcrAttempts { f ->
        val lease = f.start()
        f.results.publish(f.request, lease, OcrText("Receipt, 東京都の領収書です"))
        val hit = f.search.find("領収書", after = null, limit = 1).hits.single()
        assertEquals(PageId(PAGE), hit.page)
        assertEquals(DocumentId(DOCUMENT), hit.document)
        assertEquals(f.request.jobId, hit.producingJob)
        assertTrue(f.search.canOpen(hit))
        assertNotEquals(hit.producingJob, f.queueAgain())
        assertEquals(hit, f.search.find("領収書", after = null, limit = 1).hits.single())
        assertTrue(f.search.canOpen(hit))
        f.sql.execSQL("UPDATE Job SET idempotenceKey=? WHERE jobId=?", arrayOf("misbound-key", hit.producingJob.value))
        assertFalse(f.search.canOpen(hit))
        val rejected = f.search.find("領収書", after = null, limit = 1)
        assertTrue(rejected.hits.isEmpty())
        assertEquals(PageId(PAGE), rejected.nextAfter)
        assertNull(f.search.find("領収書", rejected.nextAfter, limit = 1).nextAfter)
    }

    @Test fun guardedVerbatimReadReturnsTheActualProducerAndRefusesAnotherAlbumOrCorruptText() = withOcrAttempts { f ->
        val original = "  École 東京都の領収書です\n"
        val page = PageId(PAGE)
        val document = DocumentId(DOCUMENT)
        assertNull(f.search.readText(document, page))
        f.results.publish(f.request, f.start(), OcrText(original))
        val current = checkNotNull(f.search.readText(document, page))
        assertEquals(original, current.text.value)
        assertEquals(f.request.binding.configuration, current.configuration)
        assertFalse(current.previousGeneration)
        assertNull(f.search.readText(DocumentId(UUID.randomUUID().toString()), page))
        val successorConfig = f.request.binding.configuration.copy(layout = OcrLayout.SPARSE_TEXT)
        f.queueWithConfig(successorConfig)
        val retained = checkNotNull(f.search.readText(document, page))
        assertTrue(retained.previousGeneration)
        assertEquals(original, retained.text.value)
        assertEquals(f.request.binding.configuration, retained.configuration)
        f.sql.execSQL("UPDATE OcrResult SET verbatimText=CAST(X'61FF62' AS TEXT)")
        assertNull(f.search.readText(document, page))
        f.sql.execSQL("UPDATE OcrResult SET verbatimText=CAST(X'610062' AS TEXT)")
        assertNull(f.search.readText(document, page))
    }

    @Test fun guardedVerbatimReadDistinguishesSuccessfulBlankFromAbsent() = withOcrAttempts { f ->
        val page = PageId(PAGE)
        val document = DocumentId(DOCUMENT)
        assertNull(f.search.readText(document, page))
        f.results.publish(f.request, f.start(), OcrText(""))
        assertEquals("", f.search.readText(document, page)?.text?.value)
    }

    @Test fun liveOcrStatusSeparatesQueuedRunningAndTerminalFailure() = withOcrAttempts(publicStore = true) { f ->
        val work = f.ocrPort()
        val page = PageId(PAGE)
        val document = DocumentId(DOCUMENT)
        val queued = checkNotNull(work.readStatus(document, page))
        assertEquals(SavedOcrWork.PagePhase.QUEUED, queued.phase)
        assertEquals(0, queued.attemptsStarted)
        assertNull(queued.lastError)
        assertNull(work.readStatus(DocumentId(UUID.randomUUID().toString()), page))
        assertNull(work.readStatus(document, f.seedUnqueuedPage()))
        repeat(3) { zeroBased ->
            val lease = f.start()
            val running = checkNotNull(work.readStatus(document, page))
            assertEquals(SavedOcrWork.PagePhase.RUNNING, running.phase)
            assertEquals(zeroBased + 1, running.attemptsStarted)
            assertNull(running.lastError)
            assertTrue(f.attempts.fail(f.request, lease, OcrError.ENGINE_FAILED))
            val after = checkNotNull(work.readStatus(document, page))
            assertEquals(if (zeroBased < 2) SavedOcrWork.PagePhase.QUEUED else SavedOcrWork.PagePhase.FAILED, after.phase)
            assertEquals(zeroBased + 1, after.attemptsStarted)
            assertEquals(OcrError.ENGINE_FAILED, after.lastError)
        }
        assertNull(work.readText(document, page))
        f.sql.execSQL("UPDATE Page SET state='REMOVED',ordinal=NULL,removedAt=10 WHERE pageId=?", arrayOf(PAGE))
        assertNull(work.readStatus(document, page))
    }

    @Test fun nonVisibleOcrStatusDoesNotInspectEvenKnownCorruptWork() = withOcrAttempts(publicStore = true) { f ->
        val work = f.ocrPort()
        val page = PageId(PAGE)
        val document = DocumentId(DOCUMENT)
        f.sql.execSQL("UPDATE Job SET attempt=4 WHERE jobId=?", arrayOf(f.request.jobId.value))
        assertNull(work.readStatus(DocumentId(UUID.randomUUID().toString()), page))
        val failure = runCatching { work.readStatus(document, page) }.exceptionOrNull() as? RepositoryFailure
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, failure?.error?.code)
        f.sql.execSQL("""INSERT INTO AtelierDraft (draftId,stagingDocumentId,state,generation,step,createdAt)
            VALUES (?,?,'ACQUIRING',1,0,0)""", arrayOf(DRAFT, DOCUMENT))
        assertNull(work.readStatus(document, page))
    }

    @Test fun readyOcrStatusRequiresTheCurrentProducingResultAndReportsAnExplicitBlankAsReady() = withOcrAttempts(publicStore = true) { f ->
        val work = f.ocrPort()
        val page = PageId(PAGE)
        val document = DocumentId(DOCUMENT)
        f.results.publish(f.request, f.start(), OcrText(""))
        assertNull(work.readStatus(document, page, expectedRevision = 2))
        assertNull(work.readText(document, page, expectedRevision = 2))
        assertEquals("", work.readText(document, page, expectedRevision = 1)?.text?.value)
        val ready = checkNotNull(work.readStatus(document, page, expectedRevision = 1))
        assertEquals(SavedOcrWork.PagePhase.READY, ready.phase)
        assertEquals(1, ready.attemptsStarted)
        assertNull(ready.lastError)
        f.sql.execSQL("UPDATE OcrPageWork SET projectionVersion=2 WHERE pageId=?", arrayOf(PAGE))
        val failure = runCatching { work.readStatus(document, page) }.exceptionOrNull() as? RepositoryFailure
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, failure?.error?.code)
        f.sql.execSQL("UPDATE OcrPageWork SET projectionVersion=1 WHERE pageId=?", arrayOf(PAGE))
        f.queueWithConfig(f.request.binding.configuration.copy(layout = OcrLayout.SPARSE_TEXT))
        assertEquals(SavedOcrWork.PagePhase.QUEUED, work.readStatus(document, page, expectedRevision = 1)?.phase)
        val previous = checkNotNull(work.readText(document, page, expectedRevision = 1))
        assertTrue(previous.previousGeneration)
        assertEquals("", previous.text.value)
    }

    @Test fun explicitOcrRerunConsumesOnlyOneObservedGenerationAndKeepsProducingText() = withOcrAttempts(publicStore = true) { f ->
        val work = f.ocrPort()
        val page = PageId(PAGE)
        val document = DocumentId(DOCUMENT)
        val prior = "Receipt from the first run"
        f.results.publish(f.request, f.start(), OcrText(prior))
        val observed = checkNotNull(work.readStatus(document, page, 1))
        val effective = observed.configuration.copy(layout = OcrLayout.SPARSE_TEXT)
        val next = work.requestRerun(observed, effective)
        assertEquals(page, next.page)
        assertNotEquals(f.request.jobId, next.job)
        val retry = checkNotNull(work.readStatus(document, page, 1))
        assertEquals(SavedOcrWork.PagePhase.QUEUED, retry.phase)
        assertEquals(0, retry.attemptsStarted)
        assertEquals(effective, retry.configuration)
        val retained = checkNotNull(work.readText(document, page, 1))
        assertTrue(retained.previousGeneration)
        assertEquals(observed.configuration, retained.configuration)
        assertEquals(prior, retained.text.value)
        val replay = runCatching { work.requestRerun(observed, effective) }.exceptionOrNull() as? RepositoryFailure
        assertEquals(OperationErrorCode.REVISION_CONFLICT, replay?.error?.code)
        assertEquals("2", f.sql.scalar("SELECT COUNT(*) FROM Job WHERE kind='PAGE_OCR'"))
        assertEquals("2", f.sql.scalar("SELECT COUNT(*) FROM SourceReference WHERE ownerType='JOB'"))
    }

    @Test fun explicitOcrRerunCannotUseAnObservationAfterTheAcceptedRecipeChanges() = withOcrAttempts(publicStore = true) { f ->
        val work = f.ocrPort()
        val page = PageId(PAGE)
        val document = DocumentId(DOCUMENT)
        val observed = checkNotNull(work.readStatus(document, page, 1))
        f.sql.execSQL("""INSERT INTO RecipeRevision SELECT pageId,2,sourceId,recipeSchemaVersion,canonicalRecipe,
            recipeSha256,1,createdAt FROM RecipeRevision WHERE pageId=? AND recipeRevision=1""", arrayOf(PAGE))
        f.sql.execSQL("UPDATE Page SET currentRecipeRevision=2 WHERE pageId=?", arrayOf(PAGE))
        val stale = runCatching { work.requestRerun(observed, observed.configuration) }.exceptionOrNull() as? RepositoryFailure
        assertEquals(OperationErrorCode.REVISION_CONFLICT, stale?.error?.code)
        assertEquals("1", f.sql.scalar("SELECT COUNT(*) FROM Job WHERE kind='PAGE_OCR'"))
        assertEquals("1", f.sql.scalar("SELECT COUNT(*) FROM SourceReference WHERE ownerType='JOB'"))
    }

    @Test fun explicitOcrRerunRollsBackWhenALateWriteMovesTheRunningPredecessorsPin() = withOcrAttempts(publicStore = true) { f ->
        val work = f.ocrPort()
        f.start()
        val observed = checkNotNull(work.readStatus(DocumentId(DOCUMENT), PageId(PAGE), 1))
        f.sql.execSQL("""CREATE TRIGGER move_old_ocr_pin AFTER UPDATE OF jobId ON OcrPageWork BEGIN
            UPDATE SourceReference SET ownerId='$DRAFT' WHERE ownerType='JOB' AND ownerId=OLD.jobId;
            END""")
        val refusal = runCatching { work.requestRerun(observed, observed.configuration) }.exceptionOrNull() as? RepositoryFailure
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, refusal?.error?.code)
        assertEquals(f.request.jobId.value, f.sql.scalar("SELECT jobId FROM OcrPageWork"))
        assertEquals("${f.request.jobId.value}:$SOURCE", f.sql.scalar("SELECT ownerId||':'||sourceId FROM SourceReference WHERE ownerType='JOB'"))
        assertEquals("1", f.sql.scalar("SELECT COUNT(*) FROM Job WHERE kind='PAGE_OCR'"))
    }

    @Test fun explicitOcrRerunRollsBackWhenALateWriteSpendsTheSuccessorsFirstStart() = withOcrAttempts(publicStore = true) { f ->
        val work = f.ocrPort()
        val observed = checkNotNull(work.readStatus(DocumentId(DOCUMENT), PageId(PAGE), 1))
        f.sql.execSQL("""CREATE TRIGGER spend_new_ocr_start AFTER UPDATE OF jobId ON OcrPageWork BEGIN
            UPDATE Job SET attempt=1 WHERE jobId=NEW.jobId;
            END""")
        val refusal = runCatching { work.requestRerun(observed, observed.configuration) }.exceptionOrNull() as? RepositoryFailure
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, refusal?.error?.code)
        assertEquals(f.request.jobId.value, f.sql.scalar("SELECT jobId FROM OcrPageWork"))
        assertEquals("PENDING:0", f.sql.scalar("SELECT state||':'||attempt FROM Job WHERE kind='PAGE_OCR'"))
        assertEquals("${f.request.jobId.value}:$SOURCE", f.sql.scalar("SELECT ownerId||':'||sourceId FROM SourceReference WHERE ownerType='JOB'"))
    }

    @Test fun noTextHitCanOpenAfterTheSavedRevisionChangesOrItsProjectionIsUnsupported() = withOcrAttempts { f ->
        val lease = f.start()
        f.results.publish(f.request, lease, OcrText("Receipt only for revision one"))
        val hit = f.search.find("receipt", after = null, limit = 10).hits.single()
        f.sql.execSQL("UPDATE OcrPageWork SET projectionVersion=2")
        assertTrue(f.search.find("receipt", after = null, limit = 10).hits.isEmpty())
        assertFalse(f.search.canOpen(hit))
        f.sql.execSQL("UPDATE OcrPageWork SET projectionVersion=1")
        assertTrue(f.search.canOpen(hit))
        f.sql.execSQL("""INSERT INTO RecipeRevision SELECT pageId,2,sourceId,recipeSchemaVersion,canonicalRecipe,
            recipeSha256,1,createdAt FROM RecipeRevision WHERE pageId=? AND recipeRevision=1""", arrayOf(PAGE))
        f.sql.execSQL("UPDATE Page SET currentRecipeRevision=2 WHERE pageId=?", arrayOf(PAGE))
        assertTrue(f.search.find("receipt", after = null, limit = 10).hits.isEmpty())
        assertFalse(f.search.canOpen(hit))
    }

    @Test fun unboundLegacyTextIsNotPublicSearchAuthority() = withOcrAttempts { f ->
        insertText(f.sql)
        assertEquals(listOf(PAGE), f.matches("ecole"))
        assertTrue(f.search.find("ecole", after = null, limit = 10).hits.isEmpty())
    }

    @Test fun malformedEarlierPageIdDoesNotDisplaceTheValidFirstHit() = withOcrAttempts { f ->
        f.results.publish(f.request, f.start(), OcrText("Receipt"))
        val malformed = "0".repeat(36)
        f.sql.beginTransaction()
        try {
            f.sql.execSQL("""INSERT INTO Page SELECT ?,documentId,sourceId,1,'ACTIVE',1,NULL,createdAt
                FROM Page WHERE pageId=?""", arrayOf(malformed, PAGE))
            f.sql.execSQL("""INSERT INTO RecipeRevision SELECT ?,1,sourceId,recipeSchemaVersion,canonicalRecipe,recipeSha256,
                NULL,createdAt FROM RecipeRevision WHERE pageId=? AND recipeRevision=1""", arrayOf(malformed, PAGE))
            f.sql.execSQL("""INSERT INTO OcrResult (pageId,recipeRevision,sourcePixelSha256,engineId,engineVersion,layout,indexedText)
                SELECT ?,recipeRevision,sourcePixelSha256,engineId,engineVersion,layout,indexedText FROM OcrResult WHERE pageId=?""",
                arrayOf(malformed, PAGE))
            f.sql.setTransactionSuccessful()
        } finally { f.sql.endTransaction() }
        val first = f.search.find("receipt", after = null, limit = 1)
        assertEquals(PageId(PAGE), first.hits.single().page)
        assertEquals(PageId(PAGE), first.nextAfter)
        assertTrue(f.search.canOpen(first.hits.single()))
    }

    @Test fun storedBlobTextCannotAuthorizeAHitOrAPreviouslyDisplayedClick() = withOcrAttempts { f ->
        f.results.publish(f.request, f.start(), OcrText("Receipt"))
        val hit = f.search.find("receipt", after = null, limit = 10).hits.single()
        assertTrue(f.search.canOpen(hit))
        f.sql.execSQL("UPDATE OcrResult SET indexedText=CAST('receipt' AS BLOB)")
        assertEquals(listOf(PAGE), f.matches("receipt")) // SQLite can index the malformed value.
        assertTrue(f.search.find("receipt", after = null, limit = 10).hits.isEmpty())
        assertFalse(f.search.canOpen(hit))
        f.sql.execSQL("UPDATE OcrResult SET indexedText='receipt',verbatimText=CAST('receipt' AS BLOB)")
        assertTrue(f.search.find("receipt", after = null, limit = 10).hits.isEmpty())
        assertFalse(f.search.canOpen(hit))
        f.sql.execSQL("UPDATE OcrResult SET verbatimText=NULL")
        assertTrue(f.search.canOpen(hit))
    }

    @Test fun backfillQueuesMissingAndChangedSavedRevisionsButNotNewDefaults() = withBackfill { sql, backfill ->
        assertTrue(backfill.reconcile(configuration = null, after = null, limit = 1).targets.isEmpty())
        assertEquals("0", sql.scalar("SELECT COUNT(*) FROM Job"))
        val first = backfill.reconcile(queueConfig(OcrLayout.AUTOMATIC), after = null, limit = 1)
        val original = first.targets.single()
        assertEquals(PageId(PAGE), original.page)
        assertEquals(PageId(PAGE), first.nextAfter)
        assertNull(backfill.reconcile(null, first.nextAfter, limit = 1).nextAfter)
        assertEquals(original, backfill.reconcile(null, null, 1).targets.single())
        assertEquals(original, backfill.reconcile(queueConfig(OcrLayout.SPARSE_TEXT), null, 1).targets.single())
        assertEquals("1", sql.scalar("SELECT COUNT(*) FROM Job"))
        assertEquals("0", sql.scalar("SELECT attempt FROM Job"))

        sql.execSQL("""INSERT INTO RecipeRevision SELECT pageId,2,sourceId,recipeSchemaVersion,canonicalRecipe,
            recipeSha256,1,createdAt FROM RecipeRevision WHERE pageId=? AND recipeRevision=1""", arrayOf(PAGE))
        sql.execSQL("UPDATE Page SET currentRecipeRevision=2 WHERE pageId=?", arrayOf(PAGE))
        assertTrue(backfill.reconcile(null, null, 1).targets.isEmpty())
        val revised = backfill.reconcile(queueConfig(OcrLayout.SPARSE_TEXT), null, 1).targets.single()
        assertEquals(original.page, revised.page)
        assertNotEquals(original.job, revised.job)
        assertEquals("2", sql.scalar("SELECT generation FROM OcrPageWork"))
        assertEquals("0", sql.scalar("SELECT SUM(attempt) FROM Job"))
        assertEquals("0", sql.scalar("SELECT COUNT(*) FROM OcrResult"))
    }

    @Test fun backfillDoesNotSignalTerminalWorkOrExposedStagingRows() = withBackfill { sql, backfill ->
        sql.execSQL("""INSERT INTO AtelierDraft (draftId,stagingDocumentId,state,generation,step,createdAt)
            VALUES (?,?,'EDITING',1,1,0)""", arrayOf(DRAFT, DOCUMENT))
        assertTrue(backfill.reconcile(queueConfig(OcrLayout.AUTOMATIC), null, 5).targets.isEmpty())
        assertEquals("0", sql.scalar("SELECT COUNT(*) FROM Job"))
        sql.execSQL("DELETE FROM AtelierDraft WHERE draftId=?", arrayOf(DRAFT))
        val current = backfill.reconcile(queueConfig(OcrLayout.AUTOMATIC), null, 5).targets.single()
        sql.execSQL("""UPDATE Job SET state='FAILED',attempt=3,leaseGeneration=3,errorCode='ENGINE_FAILED' WHERE jobId=?""",
            arrayOf(current.job.value))
        assertTrue(backfill.reconcile(queueConfig(OcrLayout.SPARSE_TEXT), null, 5).targets.isEmpty())
        assertEquals("FAILED:3:ENGINE_FAILED", sql.scalar("SELECT state||':'||attempt||':'||errorCode FROM Job"))
    }

    @Test fun malformedCappedAdmissionIsNotSignaledButAThirdRunningAttemptRemainsDiscoverable() = withBackfill { sql, backfill ->
        val config = queueConfig(OcrLayout.AUTOMATIC)
        val target = backfill.reconcile(config, null, 1).targets.single()
        sql.execSQL("""UPDATE Job SET state='ADMITTED',attempt=3,leaseGeneration=3,errorCode='ENGINE_FAILED' WHERE jobId=?""",
            arrayOf(target.job.value))
        val refused = backfill.reconcile(config, null, 1)
        assertTrue(refused.targets.isEmpty())
        assertEquals(target.page, refused.nextAfter)
        assertEquals("ADMITTED:3:ENGINE_FAILED", sql.scalar("SELECT state||':'||attempt||':'||errorCode FROM Job"))
        sql.execSQL("""UPDATE Job SET state='RUNNING',leaseOwner=?,leaseUntil=100,errorCode=NULL WHERE jobId=?""",
            arrayOf(UUID.randomUUID().toString(), target.job.value))
        assertEquals(target, backfill.reconcile(null, null, 1).targets.single())
        assertEquals("RUNNING:3", sql.scalar("SELECT state||':'||attempt FROM Job"))
    }

    @Test fun invalidDecodedAtelierReceiptAdvancesOnlyTheInternalCursor() = withBackfill { sql, backfill ->
        val receipt = "atelier-publish-v1\n1\n$DOCUMENT\n$PAGE,0,${"c".repeat(64)},$PAGE"
        sql.execSQL("""INSERT INTO AtelierDraft (draftId,stagingDocumentId,state,generation,step,createdAt,committedDocumentId,receiptPageIds)
            VALUES (?,?,'COMMITTED',2,2,0,?,?)""", arrayOf(DRAFT, DOCUMENT, DOCUMENT, receipt))
        val batch = backfill.reconcile(queueConfig(OcrLayout.AUTOMATIC), null, 1)
        assertTrue(batch.targets.isEmpty())
        assertEquals(PageId(PAGE), batch.nextAfter)
        assertNull(backfill.reconcile(null, batch.nextAfter, 1).nextAfter)
        assertEquals("0", sql.scalar("SELECT COUNT(*) FROM Job"))
    }

    private fun withBackfill(test: suspend (SupportSQLiteDatabase, RoomOcrBackfill) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder(context, PristineDatabase::class.java).build()
        try {
            val sql = database.openHelper.writableDatabase
            seedQueuePage(sql)
            runBlocking { test(sql, RoomOcrBackfill(database)) }
        } finally { database.close() }
    }

    @Test fun dispatchRequiresPositiveOwnerProofAndRetainsTheStoredConfiguration() = withOcrAttempts(
        configuration = OcrConfiguration("fixture", 1, OcrLayout.SPARSE_TEXT, OcrLanguagePacks(listOf(
            OcrLanguagePack("fra", "f".repeat(64)), OcrLanguagePack("eng", "d".repeat(64)),
        ))),
    ) { f ->
        val lease = f.start()
        f.owners.remove(lease.owner)
        var result = LeaseOwnerState.UNKNOWN
        var probes = 0
        val dispatch = f.dispatch(object : OperationOwnerProof {
            override suspend fun refresh(owner: String): LeaseOwnerState {
                assertFalse(f.sql.inTransaction())
                assertEquals(lease.owner, owner)
                probes++
                if (result == LeaseOwnerState.ENDED) f.owners[owner] = result
                return result
            }
        })
        assertEquals(RoomOcrDispatch.Decision.WaitingForOwner, dispatch.resolve(f.request.page, f.request.jobId))
        result = LeaseOwnerState.ALIVE
        assertEquals(RoomOcrDispatch.Decision.WaitingForOwner, dispatch.resolve(f.request.page, f.request.jobId))
        assertEquals("RUNNING:1", f.sql.scalar("SELECT state||':'||attempt FROM Job"))
        result = LeaseOwnerState.ENDED
        val resumed = dispatch.resolve(f.request.page, f.request.jobId) as RoomOcrDispatch.Decision.Ready
        assertEquals(f.request, resumed.request)
        assertEquals(listOf("fra", "eng"), resumed.request.binding.configuration.languagePacks?.ordered?.map { it.code })
        assertEquals("ADMITTED:1:INTERRUPTED", f.sql.scalar("SELECT state||':'||attempt||':'||errorCode FROM Job"))
        assertEquals(3, probes)
        assertNotEquals(f.request.jobId, f.queueAgain())
        assertEquals(RoomOcrDispatch.Decision.Obsolete, dispatch.resolve(f.request.page, f.request.jobId))
        assertEquals(3, probes)
    }

    @Test fun dispatchNeverStartsOrOffersAFourthAttemptAfterRecoveringTheThird() = withOcrAttempts { f ->
        repeat(2) { assertTrue(f.attempts.fail(f.request, f.start(), OcrError.ENGINE_FAILED)) }
        val third = f.start()
        f.owners[third.owner] = LeaseOwnerState.ENDED
        val dispatch = f.dispatch(object : OperationOwnerProof {
            override suspend fun refresh(owner: String): LeaseOwnerState = error("Already proved locally")
        })
        assertEquals(RoomOcrDispatch.Decision.Terminal, dispatch.resolve(f.request.page, f.request.jobId))
        assertEquals(RoomOcrDispatch.Decision.Terminal, dispatch.resolve(f.request.page, f.request.jobId))
        assertEquals("FAILED:3:INTERRUPTED", f.sql.scalar("SELECT state||':'||attempt||':'||errorCode FROM Job"))
    }

    @Test fun ownerProofCannotResumeAGenerationSupersededWhileTheProbeWasOutsideRoom() = withOcrAttempts { f ->
        val lease = f.start()
        f.owners.remove(lease.owner)
        var successor: JobId? = null
        val dispatch = f.dispatch(object : OperationOwnerProof {
            override suspend fun refresh(owner: String): LeaseOwnerState {
                assertEquals(lease.owner, owner)
                successor = f.queueAgain()
                f.owners[owner] = LeaseOwnerState.ENDED
                return LeaseOwnerState.ENDED
            }
        })
        assertEquals(RoomOcrDispatch.Decision.Obsolete, dispatch.resolve(f.request.page, f.request.jobId))
        val next = dispatch.resolve(f.request.page, checkNotNull(successor)) as RoomOcrDispatch.Decision.Ready
        assertEquals(2L, next.request.binding.generation)
        assertEquals("PENDING:0", f.sql.scalar("SELECT state||':'||attempt FROM Job JOIN OcrPageWork USING(jobId)"))
        assertEquals("RUNNING:1", f.sql.scalar("SELECT state||':'||attempt FROM Job WHERE state='RUNNING'"))
    }

    @Test fun refusedRecoveryOfTheSameEndedLeaseIsNotAnOwnerWait() = withOcrAttempts { f ->
        val lease = f.start()
        f.owners[lease.owner] = LeaseOwnerState.ENDED
        val dispatch = f.dispatch(noProofExpected())
        f.sql.execSQL("""CREATE TRIGGER refuse_finished_owner BEFORE UPDATE OF state ON Job
            WHEN OLD.state='RUNNING' AND NEW.state='ADMITTED' BEGIN SELECT RAISE(IGNORE); END""")
        val refusal = runCatching { dispatch.resolve(f.request.page, f.request.jobId) }.exceptionOrNull() as? RepositoryFailure
        assertEquals(OperationErrorCode.STORAGE_FAILED, refusal?.error?.code)
        assertEquals("ocr_dispatch_recovery_refused", refusal?.error?.safeMessageKey)
        assertEquals("RUNNING:1:${lease.owner}", f.sql.scalar("SELECT state||':'||attempt||':'||leaseOwner FROM Job"))
        assertEquals("1", f.sql.scalar("SELECT COUNT(*) FROM SourceReference WHERE ownerType='JOB'"))
    }

    @Test fun dispatchReadStorageFailureIsStructuredAndRetryable() = withOcrAttempts { f ->
        val dispatch = f.dispatch(noProofExpected())
        f.sql.execSQL("ALTER TABLE OcrPageWork RENAME TO UnavailableWorkFixture")
        val failure = runCatching { dispatch.resolve(f.request.page, f.request.jobId) }.exceptionOrNull() as? RepositoryFailure
        assertEquals(OperationErrorCode.STORAGE_FAILED, failure?.error?.code)
        assertTrue(failure?.error?.retryable == true)
        assertEquals("ocr_dispatch_read_failed", failure?.error?.safeMessageKey)
    }

    @Test fun cancellationDuringAnObsoleteLookupDoesNotDeliverADecision() {
        val cancelOnRead = AtomicReference<Job?>()
        withOcrAttempts(onQuery = { sql ->
            if (sql.startsWith("SELECT CASE WHEN") && "FROM OcrPageWork WHERE pageId=" in sql) {
                cancelOnRead.getAndSet(null)?.cancel()
            }
        }) { f ->
            val dispatch = f.dispatch(noProofExpected())
            var returned: RoomOcrDispatch.Decision? = null
            val failure = runCatching {
                withContext(Dispatchers.IO) {
                    cancelOnRead.set(checkNotNull(coroutineContext[Job]))
                    returned = dispatch.resolve(PageId(UUID.randomUUID().toString()), f.request.jobId)
                }
            }.exceptionOrNull()
            assertNull("The cancellation did not run inside the Room read", cancelOnRead.get())
            assertTrue(failure is CancellationException)
            assertNull("An obsolete decision crossed the cancellation fence", returned)
        }
    }

    @Test fun hiddenSavedBindingIsObsoleteEvenWhenItsJobAndPinStillExist() = withOcrAttempts { f ->
        val receipt = "atelier-publish-v1\n1\n$DOCUMENT\n$PAGE,0,${"c".repeat(64)},$PAGE"
        f.sql.execSQL("""INSERT INTO AtelierDraft (draftId,stagingDocumentId,state,generation,step,createdAt,committedDocumentId,receiptPageIds)
            VALUES (?,?,'COMMITTED',2,2,0,?,?)""", arrayOf(DRAFT, DOCUMENT, DOCUMENT, receipt))
        assertEquals(RoomOcrDispatch.Decision.Obsolete, f.dispatch(noProofExpected()).resolve(f.request.page, f.request.jobId))
        assertEquals("PENDING:0", f.sql.scalar("SELECT state||':'||attempt FROM Job"))
    }

    @Test fun readyWorkRequiresExactlyItsOwnJobSourcePin() = withOcrAttempts { f ->
        val dispatch = f.dispatch(noProofExpected())
        assertEquals(RoomOcrDispatch.Decision.Ready(f.request), dispatch.resolve(f.request.page, f.request.jobId))
        val otherSource = UUID.randomUUID().toString()
        f.sql.execSQL("INSERT INTO Source VALUES (?,?,?,3,2,1,'other-conversion-v1',NULL,1,'READY',0)",
            arrayOf(otherSource, BLOB, PIXELS))
        f.sql.execSQL("INSERT INTO SourceReference VALUES ('JOB',?,?,0)", arrayOf(f.request.jobId.value, otherSource))
        suspend fun assertRefused() {
            val failure = runCatching { dispatch.resolve(f.request.page, f.request.jobId) }.exceptionOrNull() as? RepositoryFailure
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, failure?.error?.code)
        }
        assertRefused() // Valid pin plus an extra pin.
        f.sql.execSQL("DELETE FROM SourceReference WHERE ownerType='JOB' AND ownerId=? AND sourceId=?",
            arrayOf(f.request.jobId.value, SOURCE))
        assertRefused() // Only the wrong pin.
        f.sql.execSQL("DELETE FROM SourceReference WHERE ownerType='JOB' AND ownerId=?", arrayOf(f.request.jobId.value))
        assertRefused() // No pin.
        assertEquals("PENDING:0", f.sql.scalar("SELECT state||':'||attempt FROM Job"))
    }

    private fun noProofExpected() = object : OperationOwnerProof {
        override suspend fun refresh(owner: String): LeaseOwnerState = error("This scenario must not invoke external proof")
    }

    @Test fun unpreparedRuntimeOrCapacityDoesNotSpendAnOcrStart() = withOcrAttempts { f ->
        val worker = f.execution()
        for (code in listOf(OperationErrorCode.PROVIDER_UNAVAILABLE, OperationErrorCode.RESOURCE_LIMIT)) {
            val refused = runCatching {
                worker.run(f.request.page, f.request.jobId) { _, _, _ ->
                    throw RepositoryFailure(OperationError(code, true, "fixture_not_prepared", null))
                }
            }.exceptionOrNull() as? RepositoryFailure
            assertEquals(code, refused?.error?.code)
            assertEquals("PENDING:0", f.sql.scalar("SELECT state||':'||attempt FROM Job"))
            assertEquals("0", f.sql.scalar("SELECT COUNT(*) FROM OcrResult"))
        }
    }

    @Test fun oneFencedExecutionPublishesVerbatimTextAndAnExactSearchHit() = withOcrAttempts { f ->
        var closed = 0
        val worker = f.execution()
        val outcome = worker.run(f.request.page, f.request.jobId) { page, config, handoff ->
            assertEquals(PageId(PAGE), page.pageId)
            assertEquals(f.request.binding.configuration, config)
            assertEquals("PENDING:0", f.sql.scalar("SELECT state||':'||attempt FROM Job"))
            handoff.retain(object : SavedOcrWork.Prepared {
                override suspend fun recognize(): OcrText {
                    assertEquals("RUNNING:1", f.sql.scalar("SELECT state||':'||attempt FROM Job"))
                    return OcrText(" École 東京都の領収書です\n")
                }
                override fun close() { closed++ }
            })
        }
        assertEquals(SavedOcrWork.Outcome.PUBLISHED, outcome)
        assertEquals(1, closed)
        assertEquals("SUCCEEDED:1", f.sql.scalar("SELECT state||':'||attempt FROM Job"))
        assertEquals(" École 東京都の領収書です\n", f.sql.scalar("SELECT COALESCE(verbatimText,indexedText) FROM OcrResult"))
        assertEquals(f.request.jobId, f.search.find("領収書", null, 1).hits.single().producingJob)
    }

    @Test fun supersededPreparationClosesWithoutAnOcrStartOrRecognition() = withOcrAttempts { f ->
        var recognized = 0
        var closed = 0
        val worker = f.execution()
        val outcome = worker.run(f.request.page, f.request.jobId) { _, _, handoff ->
            assertNotEquals(f.request.jobId, f.queueAgain())
            handoff.retain(object : SavedOcrWork.Prepared {
                override suspend fun recognize(): OcrText { recognized++; return OcrText("must not index") }
                override fun close() { closed++ }
            })
        }
        assertEquals(SavedOcrWork.Outcome.OBSOLETE, outcome)
        assertEquals(0, recognized)
        assertEquals(1, closed)
        assertEquals("0", f.sql.scalar("SELECT SUM(attempt) FROM Job"))
        assertEquals("0", f.sql.scalar("SELECT COUNT(*) FROM OcrResult"))
    }

    @Test fun startedEngineFailuresAreRecordedWithoutAStartBeyondThree() = withOcrAttempts { f ->
        var started = 0
        var closed = 0
        val worker = f.execution()
        repeat(3) {
            assertEquals(SavedOcrWork.Outcome.FAILURE_RECORDED, worker.run(f.request.page, f.request.jobId) { _, _, handoff ->
                handoff.retain(object : SavedOcrWork.Prepared {
                    override suspend fun recognize(): OcrText {
                        started++
                        throw RepositoryFailure(OperationError(OperationErrorCode.PROCESSING_FAILED, true, "fixture_ocr_engine_failed", null))
                    }
                    override fun close() { closed++ }
                })
            })
        }
        assertEquals(SavedOcrWork.Outcome.TERMINAL, worker.run(f.request.page, f.request.jobId) { _, _, _ ->
            error("Terminal job must not prepare the engine")
        })
        assertEquals(3, started)
        assertEquals(3, closed)
        assertEquals("FAILED:3:ENGINE_FAILED", f.sql.scalar("SELECT state||':'||attempt||':'||errorCode FROM Job"))
        assertEquals("0", f.sql.scalar("SELECT COUNT(*) FROM OcrResult"))
    }

    @Test fun twoExecutorsForTheSameStoreCannotOverlapEvenDuringResourceClose() = withOcrAttempts { f ->
        val (otherPage, otherJob) = f.additionalWork()
        val first = f.execution()
        val second = f.execution()
        assertNotSame(first, second)
        val closing = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        coroutineScope {
            val pending = async {
                first.run(f.request.page, f.request.jobId) { _, _, handoff ->
                    handoff.retain(object : SavedOcrWork.Prepared {
                        override suspend fun recognize() = OcrText("first")
                        override fun close() {
                            closing.complete(Unit)
                            check(release.await(20, TimeUnit.SECONDS))
                        }
                    })
                }
            }
            var secondPrepared = false
            val other = try {
                withTimeout(10_000) { closing.await() }
                second.run(otherPage, otherJob) { _, _, handoff ->
                    secondPrepared = true
                    handoff.retain(preparedText("second"))
                }
            } finally { release.countDown() }
            assertEquals(SavedOcrWork.Outcome.PUBLISHED, pending.await())
            assertEquals(SavedOcrWork.Outcome.BUSY, other)
            assertFalse(secondPrepared)
            assertEquals("1", f.sql.scalar("SELECT SUM(attempt) FROM Job"))
        }
    }

    @Test fun startedStorageFailureIsNotPersistedAsAnEngineError() = assertWorkerInfrastructureFailure(OperationErrorCode.STORAGE_FAILED)

    @Test fun startedTypedCancellationIsNotPersistedAsAnEngineError() = assertWorkerInfrastructureFailure(OperationErrorCode.CANCELED)

    private fun assertWorkerInfrastructureFailure(code: OperationErrorCode) = withOcrAttempts { f ->
        val failure = runCatching {
            f.execution().run(f.request.page, f.request.jobId) { _, _, handoff ->
                handoff.retain(object : SavedOcrWork.Prepared {
                    override suspend fun recognize(): OcrText {
                        throw RepositoryFailure(OperationError(code, false, "fixture_infrastructure_fault", null))
                    }
                    override fun close() = Unit
                })
            }
        }.exceptionOrNull() as? RepositoryFailure
        assertEquals(code, failure?.error?.code)
        assertEquals("RUNNING:1:none", f.sql.scalar("SELECT state||':'||attempt||':'||COALESCE(errorCode,'none') FROM Job"))
    }

    @Test fun canceledPreparationDeliveryClosesTheAlreadyAllocatedResource() = withOcrAttempts { f ->
        var allocated = false
        var closed = 0
        val failure = runCatching {
            f.execution().run(f.request.page, f.request.jobId) { _, _, handoff ->
                withContext(Dispatchers.Default) {
                    val ready = object : SavedOcrWork.Prepared {
                        override suspend fun recognize(): OcrText = error("Preparation was canceled")
                        override fun close() { closed++ }
                    }
                    allocated = true
                    handoff.retain(ready)
                    checkNotNull(coroutineContext[Job]).cancel()
                }
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue(allocated)
        assertEquals(1, closed)
        assertEquals("PENDING:0", f.sql.scalar("SELECT state||':'||attempt FROM Job"))
    }

    @Test fun oversizedSearchProjectionRecordsInvalidOutputUnderItsLease() = withOcrAttempts { f ->
        val result = runCatching {
            f.execution().run(f.request.page, f.request.jobId) { _, _, handoff -> handoff.retain(preparedText("\uFB2C".repeat(131_072))) }
        }
        assertEquals(SavedOcrWork.Outcome.FAILURE_RECORDED, result.getOrNull())
        assertEquals("ADMITTED:1:INVALID_OUTPUT", f.sql.scalar("SELECT state||':'||attempt||':'||errorCode FROM Job"))
        assertEquals("0", f.sql.scalar("SELECT COUNT(*) FROM OcrResult"))
    }

    @Test fun lostPublicationLeaseDoesNotRecoverAsASideEffectOfCheckingObsolescence() = withOcrAttempts { f ->
        val failure = runCatching {
            f.execution().run(f.request.page, f.request.jobId) { _, _, handoff ->
                handoff.retain(object : SavedOcrWork.Prepared {
                    override suspend fun recognize(): OcrText {
                        f.clock.addAndGet(900_000)
                        return OcrText("too late")
                    }
                    override fun close() = Unit
                })
            }
        }.exceptionOrNull() as? RepositoryFailure
        assertEquals(OperationErrorCode.REVISION_CONFLICT, failure?.error?.code)
        assertEquals("RUNNING:1:none", f.sql.scalar("SELECT state||':'||attempt||':'||COALESCE(errorCode,'none') FROM Job"))
        assertEquals("0", f.sql.scalar("SELECT COUNT(*) FROM OcrResult"))
    }

    private fun preparedText(text: String) = object : SavedOcrWork.Prepared {
        override suspend fun recognize() = OcrText(text)
        override fun close() = Unit
    }

    @Test fun storeOcrPortConnectsDurableTargetsToVerifiedSearch() = withOcrAttempts(publicStore = true) { f ->
        val port = f.ocrPort()
        val batch = port.reconcile(null, null, limit = 1)
        val target = batch.targets.single()
        assertEquals(PageId(PAGE), target.page)
        assertEquals(f.request.jobId, target.job)
        assertNotNull(batch.nextAfter)
        val wrongCursor = runCatching { port.find("receipt", batch.nextAfter, 1) }.exceptionOrNull() as? RepositoryFailure
        assertEquals("ocr_invalid_continuation", wrongCursor?.error?.safeMessageKey)
        assertNull(wrongCursor?.error?.affectedId)
        assertEquals(SavedOcrWork.Outcome.PUBLISHED, port.execute(target) { page, config, handoff ->
            assertEquals(PageId(PAGE), page.pageId)
            assertEquals(f.request.binding.configuration, config)
            handoff.retain(object : SavedOcrWork.Prepared {
                override suspend fun recognize() = OcrText("Receipt 東京都の領収書です")
                override fun close() = Unit
            })
        })
        val hit = port.find("領収書", null, 1).hits.single()
        assertEquals(PageId(PAGE), hit.page)
        assertEquals(DocumentId(DOCUMENT), hit.document)
        assertTrue(port.canOpen(hit))
        f.queueAgain()
        assertTrue(port.canOpen(hit))
    }

    @Test fun storeOcrPortRetainsDatabaseUntilPreparedResourcesAreClosed() = withOcrAttempts(publicStore = true) { f ->
        val port = f.ocrPort()
        val target = port.reconcile(null, null, 1).targets.single()
        var closeBlocked = false
        val result = port.execute(target) { _, _, handoff ->
            handoff.retain(object : SavedOcrWork.Prepared {
                override suspend fun recognize() = OcrText("")
                override fun close() {
                    val failure = runCatching { f.closeStore() }.exceptionOrNull() as? RepositoryFailure
                    closeBlocked = failure?.error?.code == OperationErrorCode.REVISION_CONFLICT
                }
            })
        }
        assertEquals(SavedOcrWork.Outcome.PUBLISHED, result)
        assertTrue(closeBlocked)
        f.closeStore()
        val refusal = runCatching { port.reconcile(null, null, 1) }.exceptionOrNull() as? RepositoryFailure
        assertEquals(OperationErrorCode.STORAGE_FAILED, refusal?.error?.code)
        assertEquals("ocr_store_closed", refusal?.error?.safeMessageKey)
    }

    @Test fun impossibleIdleCapacityStopsRedeliveryWithoutSpendingARecognitionStart() = withOcrAttempts(publicStore = true) { f ->
        val port = f.ocrPort()
        val document = DocumentId(DOCUMENT)
        val target = port.reconcile(null, null, 1).targets.single()
        var preparations = 0
        assertEquals(SavedOcrWork.Outcome.TERMINAL, port.execute(target) { page, _, _ ->
            preparations++
            throw SavedOcrWork.IdleBudgetRefusal(page)
        })
        assertEquals("FAILED:0:0:PREPARATION_RESOURCE_LIMIT",
            f.sql.scalar("SELECT state||':'||attempt||':'||leaseGeneration||':'||errorCode FROM Job"))
        assertEquals("0", f.sql.scalar("SELECT COUNT(*) FROM OcrResult"))
        assertEquals(SavedOcrWork.Outcome.TERMINAL, port.execute(target) { _, _, _ -> preparations++ })
        assertEquals(1, preparations)
        assertTrue(port.reconcile(null, null, 1).targets.isEmpty())
        val status = checkNotNull(port.readStatus(document, target.page))
        assertEquals(SavedOcrWork.PagePhase.FAILED, status.phase)
        assertEquals(0, status.attemptsStarted)
        assertEquals(OcrError.PREPARATION_RESOURCE_LIMIT, status.lastError)
        val successor = port.requestRerun(status, status.configuration)
        assertNotEquals(target.job, successor.job)
        assertEquals(SavedOcrWork.Outcome.PUBLISHED, port.execute(successor) { _, _, handoff ->
            handoff.retain(preparedText("Recognized after an explicit rerun"))
        })
        assertEquals(1, checkNotNull(port.readStatus(document, successor.page)).attemptsStarted)
    }

    @Test fun onlyExactPreHandoffIdleProofCanBlockTheCurrentGeneration() = withOcrAttempts(publicStore = true) { f ->
        val port = f.ocrPort()
        val document = DocumentId(DOCUMENT)
        val target = port.reconcile(null, null, 1).targets.single()
        for (code in listOf(OperationErrorCode.PROVIDER_UNAVAILABLE, OperationErrorCode.RESOURCE_LIMIT)) {
            val refusal = runCatching {
                port.execute(target) { _, _, _ ->
                    throw RepositoryFailure(OperationError(code, false, "ocr_render_exceeds_idle_budget", null))
                }
            }.exceptionOrNull() as? RepositoryFailure
            assertEquals(code, refusal?.error?.code)
            assertEquals("PENDING:0", f.sql.scalar("SELECT state||':'||attempt FROM Job"))
        }
        var closed = 0
        val late = runCatching {
            port.execute(target) { page, _, handoff ->
                handoff.retain(object : SavedOcrWork.Prepared {
                    override suspend fun recognize(): OcrText = error("A refused preparation must not start")
                    override fun close() { closed++ }
                })
                throw SavedOcrWork.IdleBudgetRefusal(page)
            }
        }.exceptionOrNull() as? RepositoryFailure
        assertEquals(OperationErrorCode.RESOURCE_LIMIT, late?.error?.code)
        assertEquals(1, closed)
        val wrongBinding = runCatching {
            port.execute(target) { page, _, _ -> throw SavedOcrWork.IdleBudgetRefusal(page.copy()) }
        }.exceptionOrNull() as? RepositoryFailure
        assertEquals(OperationErrorCode.RESOURCE_LIMIT, wrongBinding?.error?.code)
        assertEquals(SavedOcrWork.PagePhase.QUEUED, port.readStatus(document, target.page)?.phase)
        assertEquals("PENDING:0", f.sql.scalar("SELECT state||':'||attempt FROM Job"))
    }

    @Test fun ignoredIdleBlockWriteIsNotACompetingOwnerAndRollsBack() = withOcrAttempts(publicStore = true) { f ->
        val port = f.ocrPort()
        val target = port.reconcile(null, null, 1).targets.single()
        f.sql.execSQL("""CREATE TRIGGER IgnoreOcrIdleRefusal BEFORE UPDATE ON Job WHEN NEW.errorCode='PREPARATION_RESOURCE_LIMIT'
            BEGIN
                UPDATE OcrPageWork SET projectionVersion=2 WHERE jobId=OLD.jobId;
                SELECT RAISE(IGNORE);
            END""")
        val refusal = runCatching { port.execute(target) { page, _, _ -> throw SavedOcrWork.IdleBudgetRefusal(page) } }
            .exceptionOrNull() as? RepositoryFailure
        assertEquals(OperationErrorCode.STORAGE_FAILED, refusal?.error?.code)
        assertEquals("PENDING:0", f.sql.scalar("SELECT state||':'||attempt FROM Job"))
        assertEquals("0", f.sql.scalar("SELECT COUNT(*) FROM OcrPageWork WHERE projectionVersion IS NOT NULL"))
    }

    @Test fun rejectedSearchMatchDoesNotRevealItsPageIdThroughContinuation() = withOcrAttempts(publicStore = true) { f ->
        f.results.publish(f.request, f.start(), OcrText("receipt"))
        f.sql.execSQL("UPDATE OcrPageWork SET projectionVersion=2")
        val port = f.ocrPort()
        val rejected = port.find("receipt", null, 1)
        assertTrue(rejected.hits.isEmpty())
        val cursor: Any? = rejected.nextAfter
        assertNotNull(cursor)
        assertFalse(cursor is PageId)
        assertFalse(cursor.toString().contains(PAGE))
        assertNull(port.find("receipt", rejected.nextAfter, 1).nextAfter)
    }

    @Test fun hiddenBackfillLocatorDoesNotRevealItsPageIdThroughContinuation() = withOcrAttempts(publicStore = true) { f ->
        val receipt = "atelier-publish-v1\n1\n$DOCUMENT\n$PAGE,0,${"c".repeat(64)},$PAGE"
        f.sql.execSQL("""INSERT INTO AtelierDraft (draftId,stagingDocumentId,state,generation,step,createdAt,committedDocumentId,receiptPageIds)
            VALUES (?,?,'COMMITTED',2,2,0,?,?)""", arrayOf(DRAFT, DOCUMENT, DOCUMENT, receipt))
        val port = f.ocrPort()
        val rejected = port.reconcile(null, null, 1)
        assertTrue(rejected.targets.isEmpty())
        val cursor: Any? = rejected.nextAfter
        assertNotNull(cursor)
        assertFalse(cursor is PageId)
        assertFalse(cursor.toString().contains(PAGE))
        assertNull(port.reconcile(null, rejected.nextAfter, 1).nextAfter)
    }

    @Test fun rawAdapterFailuresAndSuppressedCleanupDoNotCrossTheStorePort() = withOcrAttempts(publicStore = true) { f ->
        val port = f.ocrPort()
        val target = port.reconcile(null, null, 1).targets.single()
        val failure = runCatching {
            port.execute(target) { _, _, handoff ->
                handoff.retain(object : SavedOcrWork.Prepared {
                    override suspend fun recognize(): OcrText = error("private synthetic OCR output and path")
                    override fun close() { error("private synthetic cleanup path") }
                })
            }
        }.exceptionOrNull() as? RepositoryFailure
        assertEquals(OperationErrorCode.PROCESSING_FAILED, failure?.error?.code)
        assertEquals("ocr_recognition_fault", failure?.error?.safeMessageKey)
        assertNull(failure?.cause)
        assertTrue(failure?.suppressed?.isEmpty() == true)
        assertEquals("RUNNING:1:none", f.sql.scalar("SELECT state||':'||attempt||':'||COALESCE(errorCode,'none') FROM Job"))
    }

    @Test fun rawBackfillSqlFailureDoesNotCrossTheStorePort() = withOcrAttempts(publicStore = true) { f ->
        f.sql.execSQL("ALTER TABLE OcrPageWork RENAME TO UnavailableOcrWorkFixture")
        val failure = runCatching { f.ocrPort().reconcile(null, null, 1) }.exceptionOrNull() as? RepositoryFailure
        assertEquals(OperationErrorCode.STORAGE_FAILED, failure?.error?.code)
        assertEquals("ocr_store_read_failed", failure?.error?.safeMessageKey)
        assertNull(failure?.cause)
        assertTrue(failure?.suppressed?.isEmpty() == true)
    }

    @Test fun savedPageSignalQueuesOnlyItsNamedPageAndDeduplicatesConcurrentDelivery() = withOcrAttempts(publicStore = true) { f ->
        val port = f.ocrPort()
        val fresh = f.seedUnqueuedPage()
        val document = DocumentId(DOCUMENT)
        val config = queueConfig(OcrLayout.SINGLE_BLOCK)
        assertNull(port.reconcilePage(DocumentId(UUID.randomUUID().toString()), fresh, config))
        assertNull(port.reconcilePage(document, PageId(UUID.randomUUID().toString()), config))
        assertNull(port.reconcilePage(document, fresh, null))
        assertEquals("1", f.sql.scalar("SELECT COUNT(*) FROM OcrPageWork"))
        val target = coroutineScope {
            val first = async { port.reconcilePage(document, fresh, config) }
            val second = async { port.reconcilePage(document, fresh, config) }
            checkNotNull(first.await()).also { assertEquals(it, second.await()) }
        }
        assertEquals(fresh, target.page)
        assertNotEquals(f.request.jobId, target.job)
        assertEquals("2", f.sql.scalar("SELECT COUNT(*) FROM OcrPageWork"))
        assertEquals("PENDING:0", f.sql.scalar("SELECT state||':'||attempt FROM Job WHERE jobId='${f.request.jobId.value}'"))
        assertEquals(target, port.reconcilePage(document, fresh, queueConfig(OcrLayout.SPARSE_TEXT)))
        assertEquals(SavedOcrWork.Outcome.PUBLISHED, port.execute(target) { page, effective, handoff ->
            assertEquals(fresh, page.pageId)
            assertEquals(config, effective)
            handoff.retain(preparedText("Saved page notification"))
        })
        assertNull(port.reconcilePage(document, fresh, queueConfig(OcrLayout.SPARSE_TEXT)))
        assertEquals("2", f.sql.scalar("SELECT COUNT(*) FROM Job WHERE kind='PAGE_OCR'"))
    }

    @Test fun exactSavedPageSignalRefusesHiddenAtelierWithoutCreatingWork() = withOcrAttempts(publicStore = true) { f ->
        val port = f.ocrPort()
        val fresh = f.seedUnqueuedPage()
        val receipt = "atelier-publish-v1\n1\n$DOCUMENT\n$PAGE,0,${"c".repeat(64)},$PAGE"
        f.sql.execSQL("""INSERT INTO AtelierDraft (draftId,stagingDocumentId,state,generation,step,createdAt,committedDocumentId,receiptPageIds)
            VALUES (?,?,'COMMITTED',2,2,0,?,?)""", arrayOf(DRAFT, DOCUMENT, DOCUMENT, receipt))
        assertNull(port.reconcilePage(DocumentId(DOCUMENT), fresh, queueConfig(OcrLayout.SINGLE_BLOCK)))
        assertEquals("1", f.sql.scalar("SELECT COUNT(*) FROM OcrPageWork"))
        assertEquals("1", f.sql.scalar("SELECT COUNT(*) FROM Job WHERE kind='PAGE_OCR'"))
    }

    private data class AttemptFixture(
        val sql: SupportSQLiteDatabase,
        val attempts: RoomOcrAttempts,
        val request: RoomOcrAttempts.Request,
        val owners: ConcurrentHashMap<String, LeaseOwnerState>,
        val clock: AtomicLong,
        val results: RoomOcrResults,
        val matches: (String) -> List<String>,
        val queueAgain: suspend () -> JobId,
        val queueWithConfig: suspend (OcrConfiguration) -> JobId,
        val search: RoomOcrSearch,
        val dispatch: (OperationOwnerProof) -> RoomOcrDispatch,
        val execution: () -> RoomOcrExecution,
        val additionalWork: suspend () -> Pair<PageId, JobId>,
        val seedUnqueuedPage: () -> PageId,
        val ocrPort: () -> SavedOcrWork,
        val closeStore: () -> Unit,
    ) {
        suspend fun start(): JobLease {
            assertTrue(attempts.admit(request))
            val owner = UUID.randomUUID().toString()
            owners[owner] = LeaseOwnerState.ALIVE
            return (attempts.start(request, owner, 500) as AcquireLeaseResult.Granted).lease
        }
    }

    private fun withOcrAttempts(
        previousResult: Boolean = false,
        configuration: OcrConfiguration = queueConfig(OcrLayout.AUTOMATIC),
        onQuery: ((String) -> Unit)? = null,
        publicStore: Boolean = false,
        test: suspend (AttemptFixture) -> Unit,
    ) {
        val isolated = if (publicStore) isolatedOcrStoreContext() else null
        val builder = if (isolated == null) Room.inMemoryDatabaseBuilder(context, PristineDatabase::class.java)
            else Room.databaseBuilder(isolated, PristineDatabase::class.java, "pristinepage.db")
        if (onQuery != null) builder.setQueryCallback(RoomDatabase.QueryCallback { sql, _ -> onQuery(sql) }, Executor { it.run() })
        val database = builder.build()
        var store: PrivateImportStore? = null
        try {
            val sql = database.openHelper.writableDatabase
            if (isolated != null) assertEquals(isolated.getDatabasePath("pristinepage.db").absolutePath, sql.path)
            val page = seedQueuePage(sql)
            val owners = ConcurrentHashMap<String, LeaseOwnerState>()
            val clock = AtomicLong(10)
            val liveness = LeaseOwnerLiveness { owners[it] ?: LeaseOwnerState.UNKNOWN }
            runBlocking {
                val queue = RoomOcrQueue(database)
                val initial = queue.enqueue(page, configuration, rerun = false)
                val id = if (previousResult) {
                    insertRecognition(sql, "Previous recognition")
                    sql.execSQL("UPDATE Job SET state='SUCCEEDED',attempt=1,leaseGeneration=1 WHERE jobId=?", arrayOf(initial.value))
                    sql.execSQL("UPDATE OcrPageWork SET resultJobId=?,projectionVersion=1 WHERE jobId=?", arrayOf(initial.value, initial.value))
                    queue.enqueue(page, configuration, rerun = true)
                } else initial
                val stored = RoomMetadataAccess(database).transaction {
                    checkNotNull(RoomOcrCurrentWork(database).readInTransaction(page.pageId))
                }
                assertEquals(id, JobId(stored.job.jobId))
                val executionRoot by lazy { File(context.filesDir, "ocr-execution-${UUID.randomUUID()}") }
                if (isolated != null) {
                    val opened = PrivateImportStore.open(isolated, SourceInput { error("Unexpected provider access in OCR port test") },
                        SourceInspector { _, _ -> error("Unexpected source inspection in OCR port test") })
                    store = opened
                    assertSame(opened.savedOcr, opened.savedOcr)
                }
                test(AttemptFixture(sql, RoomOcrAttempts(database, liveness, leaseClock = clock::get),
                    RoomOcrAttempts.Request(id, stored.intent), owners, clock, RoomOcrResults(database, liveness, leaseClock = clock::get),
                    { query -> database.ocrResults().matchPages(checkNotNull(OcrSearchTerms.create(query)), null, 2).map { it.pageId } },
                     { queue.enqueue(page, configuration, rerun = true) }, { settings -> queue.enqueue(page, settings, rerun = true) }, RoomOcrSearch(database),
                    { proof -> RoomOcrDispatch(database, liveness, proof, leaseClock = clock::get) },
                    { RoomOcrExecution(database, FileOperationOwners(executionRoot), leaseClock = clock::get) },
                    {
                        val additional = seedAdditionalQueuePage(sql, page)
                        additional.pageId to queue.enqueue(additional, configuration, rerun = false)
                    }, { seedAdditionalQueuePage(sql, page).pageId }, { checkNotNull(store).savedOcr }, { checkNotNull(store).close() }))
            }
        } finally {
            try { store?.close() } finally { database.close() }
        }
    }

    /** Use the same Context API as the production store, but never the application's persisted database or file root. */
    private fun isolatedOcrStoreContext(): Context {
        val root = File(context.filesDir, "ocr-port-fixture-${UUID.randomUUID()}")
        check(root.mkdir())
        val databaseDir = File(root, "databases").also { check(it.mkdir()) }
        val fileDir = File(root, "files").also { check(it.mkdir()) }
        return object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = fileDir
            override fun getDatabasePath(name: String): File {
                check(name == "pristinepage.db")
                return File(databaseDir, name)
            }
        }
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

    private fun seedAdditionalQueuePage(sql: SupportSQLiteDatabase, original: EditablePage): EditablePage {
        val page = PageId(UUID.randomUUID().toString())
        sql.beginTransaction()
        try {
            sql.execSQL("INSERT INTO Page VALUES (?,?,?,1,'ACTIVE',1,NULL,0)", arrayOf(page.value, DOCUMENT, SOURCE))
            sql.execSQL("INSERT INTO RecipeRevision VALUES (?,1,?,1,?,?,NULL,0)",
                arrayOf(page.value, SOURCE, RecipeCodec.encode(original.recipe), original.recipeHash))
            sql.execSQL("INSERT INTO SourceReference VALUES ('PAGE',?,?,0)", arrayOf(page.value, SOURCE))
            sql.execSQL("INSERT INTO SourceReference VALUES ('RECIPE_HISTORY',?,?,0)", arrayOf("recipe-v1/${page.value}/1", SOURCE))
            sql.execSQL("UPDATE Source SET refCount=(SELECT COUNT(*) FROM SourceReference WHERE sourceId=?) WHERE sourceId=?",
                arrayOf(SOURCE, SOURCE))
            sql.setTransactionSuccessful()
        } finally { sql.endTransaction() }
        return original.copy(pageId = page)
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
