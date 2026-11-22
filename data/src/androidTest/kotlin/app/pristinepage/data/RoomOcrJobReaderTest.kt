package app.pristinepage.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.core.ocr.OcrInput
import app.pristinepage.core.ocr.OcrLanguagePack
import app.pristinepage.core.ocr.OcrLanguagePacks
import app.pristinepage.core.ocr.OcrLayout
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.SourceId
import app.pristinepage.data.repository.OcrJobInput
import app.pristinepage.data.repository.RoomMetadataAccess
import app.pristinepage.data.repository.RoomOcrJobReader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomOcrJobReaderTest {
    private val job = JobId("00000000-0000-0000-0000-000000000001")
    private val binding = OcrJobInput(
        DocumentId("00000000-0000-0000-0000-000000000002"),
        OcrInput("00000000-0000-0000-0000-000000000003", 7, "b".repeat(64)),
        SourceId("00000000-0000-0000-0000-000000000004"), "a".repeat(64), 2,
        OcrConfiguration("fixture", 1, OcrLayout.AUTOMATIC, OcrLanguagePacks(listOf(OcrLanguagePack("eng", "c".repeat(64))))),
    )

    @Test fun exactPayloadCannotBeReplacedByEqualLengthBytesUnderTheSameKeyAndDigest() = withDatabase { database ->
        val reader = RoomOcrJobReader(database)
        val metadata = RoomMetadataAccess(database)
        val sql = database.openHelper.writableDatabase
        assertNull(metadata.transaction { reader.readInTransaction(job, binding) })
        assertThrows(IllegalStateException::class.java) { reader.readInTransaction(job, binding) }
        insert(database, binding)
        assertArrayEquals(binding.encode(), metadata.transaction { reader.readInTransaction(job, binding) }!!.input)
        val changed = binding.encode().also { it[it.lastIndex] = 'd'.code.toByte() }
        sql.execSQL("UPDATE Job SET input=? WHERE jobId=?", arrayOf(changed, job.value))
        val failure = assertThrows(RepositoryFailure::class.java) {
            metadata.transaction { reader.readInTransaction(job, binding) }
        }
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, failure.error.code)
        assertEquals("ocr_job_input_invalid", failure.error.safeMessageKey)
    }

    @Test fun malformedPresentRowsAndFixtureOnlyV1DoNotLookAbsent() = withDatabase { database ->
        val reader = RoomOcrJobReader(database)
        val metadata = RoomMetadataAccess(database)
        val v1 = binding.copy(configuration = binding.configuration.copy(languagePacks = null))
        insert(database, v1)
        for (expected in listOf(binding, v1)) {
            val failure = assertThrows(RepositoryFailure::class.java) {
                metadata.transaction { reader.readInTransaction(job, expected) }
            }
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, failure.error.code)
        }
    }

    private fun insert(database: PristineDatabase, input: OcrJobInput) {
        database.openHelper.writableDatabase.execSQL(
            """INSERT INTO Job (jobId,kind,idempotenceKey,canonicalInputSha256,input,state,attempt,leaseGeneration,createdAt,updatedAt)
               VALUES (?,'PAGE_OCR',?,?,?,'ADMITTED',0,0,10,10)""",
            arrayOf(job.value, input.key, input.digest, input.encode()),
        )
    }

    private fun withDatabase(action: (PristineDatabase) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, PristineDatabase::class.java).build()
        try { action(database) } finally { database.close() }
    }
}
