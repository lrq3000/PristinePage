package app.pristinepage.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.data.library.LibraryGeneration
import app.pristinepage.data.library.RoomLibraryCatalogReader
import app.pristinepage.data.library.RoomLibraryLocalBackup
import app.pristinepage.data.library.RoomLibraryLocalExport
import app.pristinepage.data.model.IngestResult
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomLibraryLocalExportTest {
    @Test fun anIsolatedFolderContainsOneExactOriginalAndAReopenableCatalogForTwoScanSlots() = runBlocking<Unit> {
        RoomSourceIngestIntegrationTest.Fixture().use { source ->
            val context = InstrumentationRegistry.getInstrumentation().context
            val root = File(context.filesDir.canonicalFile, "library-export-fixture-${UUID.randomUUID()}").apply { check(mkdir()) }
            val first = source.reserve(count = 2)
            val commits = first.mapIndexed { index, child ->
                source.sourceDisplayName = "Report-${index + 1}.png"
                source.store.ingest(source.reference, child) as IngestResult.Committed
            }

            val backup = File(root, "backup")
            val scratch = File(root, "private-scratch").apply { check(mkdir()) }
            val exported = RoomLibraryLocalExport(context, source.database, source.root.canonicalFile, scratch).writeNewFolder(backup)

            assertEquals(exported, File(backup, "generations/${exported.id}.complete").inputStream().use(LibraryGeneration::read))
            val files = checkNotNull(File(backup, "objects").listFiles()).toList()
            assertEquals(listOf(commits.first().sourceByteHash), files.map { it.name })
            assertArrayEquals(source.bytes, files.single().readBytes())
            val catalogFile = File(backup, exported.catalogPath)
            catalogFile.inputStream().use { exported.verifyCatalog(it) }
            val labels = RoomLibraryCatalogReader(context, exported.catalog.byteCount).read(catalogFile) { db ->
                db.rawQuery("""SELECT n.filename,b.byteSha256,b.byteCount FROM LibraryScanName n JOIN Page p USING(pageId,documentId)
                    JOIN Source s USING(sourceId) JOIN SourceBlob b USING(sourceBlobId) ORDER BY p.ordinal""", null).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            assertEquals(files.single().name, cursor.getString(1))
                            assertEquals(source.bytes.size.toLong(), cursor.getLong(2))
                            add(cursor.getString(0))
                        }
                    }
                }
            }
            assertEquals(listOf("Report-1.png", "Report-2.png"), labels)
            val backupReader = RoomLibraryLocalBackup(context, scratch)
            val reopened = backupReader.read(backup)
            assertEquals(exported, reopened.generation)
            val expectedBlobIds = commits.map { checkNotNull(source.database.catalog().source(it.sourceId.value)).sourceBlobId }.toSet()
            assertEquals(expectedBlobIds, reopened.originals.keys)
            assertEquals(1, reopened.originals.values.toSet().size)
            val damaged = source.bytes.copyOf().also { it[0] = 7 }
            files.single().writeBytes(damaged)
            assertTrue(runCatching { backupReader.read(backup) }.exceptionOrNull() is IOException)
            assertArrayEquals(damaged, files.single().readBytes())
            for (commit in commits) {
                val row = checkNotNull(source.database.catalog().source(commit.sourceId.value))
                assertArrayEquals(source.bytes, source.files.finalFile(row.sourceBlobId).readBytes())
            }
            assertTrue(backup.isDirectory)
            assertTrue(scratch.list().orEmpty().none { it.startsWith("library-stage-") })
        }
    }

    @Test fun anExistingEmptyFolderCannotBeClaimedAsANewExport() = runBlocking<Unit> {
        RoomSourceIngestIntegrationTest.Fixture().use { source ->
            val context = InstrumentationRegistry.getInstrumentation().context
            val root = File(context.filesDir.canonicalFile, "library-export-fixture-${UUID.randomUUID()}").apply { check(mkdir()) }
            val backup = File(root, "backup").apply { check(mkdir()) }
            val scratch = File(root, "scratch").apply { check(mkdir()) }
            val exception = runCatching { RoomLibraryLocalExport(context, source.database, source.root.canonicalFile, scratch).writeNewFolder(backup) }.exceptionOrNull()
            assertTrue(exception is IOException)
            assertEquals(emptyList<String>(), backup.list().orEmpty().toList())
            assertFalse(File(backup, "generations").exists())
        }
    }

    @Test fun aSameLengthDamagedSecondOriginalCannotPublishEvenTheFirstObject() = runBlocking<Unit> {
        RoomSourceIngestIntegrationTest.Fixture().use { source ->
            val context = InstrumentationRegistry.getInstrumentation().context
            val root = File(context.filesDir.canonicalFile, "library-export-fixture-${UUID.randomUUID()}").apply { check(mkdir()) }
            val children = source.reserve(count = 2)
            val first = source.store.ingest(source.reference, children[0]) as IngestResult.Committed
            val second = source.store.ingest(source.reference, children[1]) as IngestResult.Committed
            val firstBlob = checkNotNull(source.database.catalog().source(first.sourceId.value)).sourceBlobId
            val damagedBlob = checkNotNull(source.database.catalog().source(second.sourceId.value)).sourceBlobId
            val different = source.bytes.copyOf().also { it[0] = 7 }
            source.files.finalFile(damagedBlob).writeBytes(different)
            val backup = File(root, "backup")
            val scratch = File(root, "scratch").apply { check(mkdir()) }
            val exception = runCatching { RoomLibraryLocalExport(context, source.database, source.root.canonicalFile, scratch).writeNewFolder(backup) }.exceptionOrNull()
            assertTrue(exception is IOException)
            assertFalse(File(backup, "generations").exists())
            assertFalse(File(backup, "objects").exists())
            assertArrayEquals(source.bytes, source.files.finalFile(firstBlob).readBytes())
            assertArrayEquals(different, source.files.finalFile(damagedBlob).readBytes())
        }
    }
}
