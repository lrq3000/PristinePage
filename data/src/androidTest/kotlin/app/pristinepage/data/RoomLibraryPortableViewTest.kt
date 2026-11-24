package app.pristinepage.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.data.library.LocalLibraryDirectory
import app.pristinepage.data.library.RoomLibraryLocalExport
import app.pristinepage.data.library.RoomLibraryPortableView
import app.pristinepage.data.model.IngestResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomLibraryPortableViewTest {
    @Test fun openReadsARealCatalogAndOriginalWithoutCopyingScansIntoWorkingStorage() = runBlocking<Unit> {
        RoomSourceIngestIntegrationTest.Fixture().use { fixture ->
            fixture.sourceDisplayName = "Retained.png"
            val page = fixture.store.ingest(fixture.reference, fixture.reserve().single()) as IngestResult.Committed
            val context = InstrumentationRegistry.getInstrumentation().context
            val root = File(context.filesDir.canonicalFile, "library-open-fixture-${UUID.randomUUID()}").apply { check(mkdir()) }
            val scratch = File(root, "scratch").apply { check(mkdir()) }
            val backup = File(root, "library")
            val marker = RoomLibraryLocalExport(context, fixture.database, fixture.root.canonicalFile, scratch).writeNewFolder(backup)
            val before = files(backup)
            val verifier = RoomLibraryPortableView(context, scratch)
            val proposal = verifier.prepare(backup)
            assertEquals(marker, proposal.generation)
            assertTrue(runCatching {
                verifier.open(proposal, marker.copy(id = UUID.randomUUID().toString()))
            }.exceptionOrNull() is IOException)
            val opened = verifier.open(proposal, marker)
            val album = opened.catalog.albums.single()
            assertEquals(fixture.document.value, album.row.documentId)
            assertEquals(page.pageId.value, album.pages.single().row.pageId)
            assertEquals("Retained.png", album.pages.single().filename)
            val output = ByteArrayOutputStream()
            opened.copyOriginal(opened.catalog.originals.single().sourceBlobId, output)
            assertArrayEquals(fixture.bytes, output.toByteArray())
            assertEquals(before, files(backup))
            assertEquals(emptyMap<String, List<Byte>>(), files(scratch))

            val detached = File(root, "detached")
            check(backup.renameTo(detached))
            check(detached.copyRecursively(backup))
            assertEquals(before, files(backup))
            assertTrue(runCatching { verifier.open(proposal, marker) }.exceptionOrNull() is IOException)
            val incomplete = ByteArrayOutputStream()
            assertTrue(runCatching { opened.copyOriginal(opened.catalog.originals.single().sourceBlobId, incomplete) }.exceptionOrNull() is IOException)
            assertEquals(0, incomplete.size())
            assertEquals(before, files(detached))
            val replacement = File(root, "replacement")
            check(backup.renameTo(replacement))
            check(detached.renameTo(backup))

            val original = File(backup, before.keys.single { it.startsWith("objects/") })
            val damage = fixture.bytes.copyOf().apply { this[0] = 0 }
            original.writeBytes(damage)
            assertTrue(runCatching {
                opened.copyOriginal(opened.catalog.originals.single().sourceBlobId, ByteArrayOutputStream())
            }.exceptionOrNull() is IOException)
            assertArrayEquals(damage, original.readBytes())
            original.writeBytes(fixture.bytes)

            val child = marker.copy(id = UUID.randomUUID().toString(), predecessorId = marker.id)
            LocalLibraryDirectory(backup).apply {
                createCatalog(child) { it.write(File(backup, marker.catalogPath).readBytes()) }
                createMarker(child, child.encode())
            }
            val afterPublication = files(backup)
            val stale = ByteArrayOutputStream()
            assertTrue(runCatching { opened.copyOriginal(opened.catalog.originals.single().sourceBlobId, stale) }.exceptionOrNull() is IOException)
            assertEquals(0, stale.size())
            assertEquals(afterPublication, files(backup))
        }
    }

    private fun files(root: File) = root.walkTopDown().filter(File::isFile)
        .associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes().toList() }
}
