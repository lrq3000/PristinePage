package app.pristinepage.data

import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.core.ocr.OcrInput
import app.pristinepage.core.ocr.OcrLanguagePack
import app.pristinepage.core.ocr.OcrLanguagePacks
import app.pristinepage.core.ocr.OcrLayout
import app.pristinepage.core.ocr.OcrText
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.library.LibrarySavedOcr
import app.pristinepage.data.library.RoomLibraryLocalExport
import app.pristinepage.data.library.RoomLibraryLocalRestore
import app.pristinepage.data.library.RoomLibrarySavedOcr
import app.pristinepage.data.model.DocumentMutationResult
import app.pristinepage.data.model.IngestResult
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.SourceCaptureDate
import app.pristinepage.data.repository.OcrJobInput
import app.pristinepage.data.repository.RecipeAdmission
import app.pristinepage.data.repository.RoomDocumentRepository
import app.pristinepage.data.repository.RoomEditablePages
import app.pristinepage.data.repository.RoomMetadataAccess
import app.pristinepage.data.repository.RoomOcrSearch
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomLibraryLocalRestoreTest {
    @Test fun aClosedBackupRestoresOrderedOriginalsEditsAndSearchWithoutRerunningRecognition() = runBlocking<Unit> {
        RoomSourceIngestIntegrationTest.Fixture(captureDate = SourceCaptureDate(19_000)).use { source ->
            val context = InstrumentationRegistry.getInstrumentation().context
            val root = File(context.filesDir.canonicalFile, "library-restore-fixture-${UUID.randomUUID()}").apply { check(mkdir()) }
            val scratch = File(root, "scratch").apply { check(mkdir()) }
            val (first, second) = source.reserve(count = 2)
            source.sourceDisplayName = "Front.png"
            val front = source.store.ingest(source.reference, first) as IngestResult.Committed
            source.sourceDisplayName = "Back.png"
            val back = source.store.ingest(source.reference, second) as IngestResult.Committed
            val access = RoomMetadataAccess(source.database)
            val commands = RoomDocumentRepository(access, RecipeAdmission { _, _ -> null })
            assertTrue(commands.reorderPages(source.document, back.documentRevision, listOf(back.pageId, front.pageId)) is DocumentMutationResult.Applied)
            assertTrue(commands.rotatePage(back.pageId, 1, 1) is DocumentMutationResult.Applied)
            val accepted = RoomEditablePages(access, source.database.catalog()).load(source.document, back.pageId)
            val producer = JobId(UUID.randomUUID().toString())
            val text = "  Verbatim café\r\nportable invoice\n"
            val config = OcrConfiguration("fixture-ocr", 1, OcrLayout.SPARSE_TEXT, OcrLanguagePacks(listOf(OcrLanguagePack("eng", "b".repeat(64)))))
            val intent = OcrJobInput(source.document, OcrInput(back.pageId.value, 2, back.sourcePixelHash), back.sourceId, accepted.recipeHash, 4, config)
            access.transaction { RoomLibrarySavedOcr(source.database).restoreInTransaction(LibrarySavedOcr(producer, intent, OcrText(text), 2, 10, 20)) }

            val backup = File(root, "backup")
            val generation = RoomLibraryLocalExport(context, source.database, source.root.canonicalFile, scratch).writeNewFolder(backup)
            val backupFiles = backup.walkTopDown().filter(File::isFile).associate { it.relativeTo(backup).path to it.readBytes().toList() }
            val destination = File(root, "restored")
            val restore = RoomLibraryLocalRestore(context, scratch)
            val completed = restore.restore(backup, destination)
            assertEquals(generation, completed.backup)
            val receipt = File(destination, "catalog.ready")
            val rebuilt = File(destination, "catalog.sqlite")
            assertEquals(rebuilt.length(), completed.catalog.byteCount)
            val digest = MessageDigest.getInstance("SHA-256").digest(rebuilt.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(digest, completed.catalog.sha256)
            assertEquals("pristinepage-private-restore-v1\n${generation.libraryId}\n${generation.id}\n${generation.catalog.sha256}\n${rebuilt.length()}\n$digest\n", receipt.readText())
            assertEquals(backupFiles, backup.walkTopDown().filter(File::isFile).associate { it.relativeTo(backup).path to it.readBytes().toList() })

            val restoredContext = object : ContextWrapper(context) {
                override fun getDatabasePath(name: String): File {
                    check(name == "catalog.sqlite")
                    return File(destination, name)
                }
            }
            val database = Room.databaseBuilder(restoredContext, PristineDatabase::class.java, "catalog.sqlite").build()
            try {
                val sql = database.openHelper.readableDatabase
                sql.query("""SELECT n.filename,s.pixelSha256,b.sourceBlobId,day.epochDay,b.relativePath
                    FROM Page p JOIN LibraryScanName n USING(pageId,documentId) JOIN Source s USING(sourceId)
                    JOIN SourceBlob b USING(sourceBlobId) JOIN SourceCaptureDay day USING(sourceBlobId) ORDER BY p.ordinal""").use { rows ->
                    val labels = buildList {
                        while (rows.moveToNext()) {
                            assertEquals("a".repeat(64), rows.getString(1))
                            assertEquals(19_000L, rows.getLong(3))
                            val relative = "sources/${rows.getString(2)}/original.bin"
                            assertEquals(relative, rows.getString(4))
                            assertArrayEquals(source.bytes, File(destination, relative).readBytes())
                            add(rows.getString(0))
                        }
                    }
                    assertEquals(listOf("Back.png", "Front.png"), labels)
                }
                assertEquals(accepted, RoomEditablePages(RoomMetadataAccess(database), database.catalog()).load(source.document, back.pageId))
                val before = checkNotNull(database.catalog().recipe(back.pageId.value, 1))
                assertEquals(0, RecipeCodec.decode(before.canonicalRecipe).geometry.outputQuarterTurns)
                val search = RoomOcrSearch(database)
                val hit = search.find("invoice", null, 10).hits.single()
                assertEquals(back.pageId, hit.page)
                assertEquals(producer, hit.producingJob)
                val restored = checkNotNull(search.readText(source.document, back.pageId, 2))
                assertEquals(text, restored.text.value)
                assertEquals(config, restored.configuration)
                sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            } finally { database.close() }
        }
    }

    @Test fun aBackupWithADamagedOriginalLeavesTheFreshTargetUnclaimed() = runBlocking<Unit> {
        RoomSourceIngestIntegrationTest.Fixture().use { source ->
            val context = InstrumentationRegistry.getInstrumentation().context
            val root = File(context.filesDir.canonicalFile, "library-restore-damaged-${UUID.randomUUID()}").apply { check(mkdir()) }
            val scratch = File(root, "scratch").apply { check(mkdir()) }
            val page = source.store.ingest(source.reference, source.reserve().single()) as IngestResult.Committed
            val backup = File(root, "backup")
            RoomLibraryLocalExport(context, source.database, source.root.canonicalFile, scratch).writeNewFolder(backup)
            val bad = File(backup, "objects/${page.sourceByteHash}")
            val damaged = byteArrayOf(4, 3, 2, 1)
            bad.writeBytes(damaged)
            val destination = File(root, "restored")
            assertTrue(runCatching { RoomLibraryLocalRestore(context, scratch).restore(backup, destination) }.exceptionOrNull() is IOException)
            assertFalse(destination.exists())
            assertArrayEquals(damaged, bad.readBytes())
        }
    }
}
