package app.pristinepage.data

import androidx.room.Room
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
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.EditablePage
import app.pristinepage.data.model.JobId
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
import app.pristinepage.data.repository.RoomMetadataAccess
import app.pristinepage.data.repository.RoomOcrAttempts
import app.pristinepage.data.repository.RoomOcrCurrentWork
import app.pristinepage.data.repository.RoomOcrQueue
import app.pristinepage.data.repository.RoomOcrResults
import app.pristinepage.data.repository.RoomOcrSearch
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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

    private data class AttemptFixture(
        val sql: SupportSQLiteDatabase,
        val attempts: RoomOcrAttempts,
        val request: RoomOcrAttempts.Request,
        val owners: ConcurrentHashMap<String, LeaseOwnerState>,
        val clock: AtomicLong,
        val results: RoomOcrResults,
        val matches: (String) -> List<String>,
        val queueAgain: suspend () -> JobId,
        val search: RoomOcrSearch,
    ) {
        suspend fun start(): JobLease {
            assertTrue(attempts.admit(request))
            val owner = UUID.randomUUID().toString()
            owners[owner] = LeaseOwnerState.ALIVE
            return (attempts.start(request, owner, 500) as AcquireLeaseResult.Granted).lease
        }
    }

    private fun withOcrAttempts(previousResult: Boolean = false, test: suspend (AttemptFixture) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder(context, PristineDatabase::class.java).build()
        try {
            val sql = database.openHelper.writableDatabase
            val page = seedQueuePage(sql)
            val owners = ConcurrentHashMap<String, LeaseOwnerState>()
            val clock = AtomicLong(10)
            val liveness = LeaseOwnerLiveness { owners[it] ?: LeaseOwnerState.UNKNOWN }
            runBlocking {
                val queue = RoomOcrQueue(database)
                val config = queueConfig(OcrLayout.AUTOMATIC)
                val initial = queue.enqueue(page, config, rerun = false)
                val id = if (previousResult) {
                    insertRecognition(sql, "Previous recognition")
                    sql.execSQL("UPDATE Job SET state='SUCCEEDED',attempt=1,leaseGeneration=1 WHERE jobId=?", arrayOf(initial.value))
                    sql.execSQL("UPDATE OcrPageWork SET resultJobId=?,projectionVersion=1 WHERE jobId=?", arrayOf(initial.value, initial.value))
                    queue.enqueue(page, config, rerun = true)
                } else initial
                val stored = RoomMetadataAccess(database).transaction {
                    checkNotNull(RoomOcrCurrentWork(database).readInTransaction(page.pageId))
                }
                assertEquals(id, JobId(stored.job.jobId))
                test(AttemptFixture(sql, RoomOcrAttempts(database, liveness, leaseClock = clock::get),
                    RoomOcrAttempts.Request(id, stored.intent), owners, clock, RoomOcrResults(database, liveness, leaseClock = clock::get),
                    { query -> database.ocrResults().matchPages(checkNotNull(OcrSearchTerms.create(query)), null, 2).map { it.pageId } },
                    { queue.enqueue(page, config, rerun = true) }, RoomOcrSearch(database)))
            }
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
