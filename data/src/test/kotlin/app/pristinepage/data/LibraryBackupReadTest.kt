package app.pristinepage.data

import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.library.LibraryBackupRead
import app.pristinepage.data.library.LibraryGeneration
import app.pristinepage.data.library.LibraryGenerationInventory
import app.pristinepage.data.library.LibraryObjectTransfer.ObjectId
import app.pristinepage.data.library.LocalLibraryDirectory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryBackupReadTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun originalBudgetCountsUniqueObjectsAndRefusesBeforeOpeningAnyOriginal() {
        val fixture = fixture()
        fun verify(store: StreamBackup, maximum: Long) =
            LibraryBackupRead(store, fixture.scratch.toPath(), 1024).read(8, maximumOriginalBytes = maximum) { _, _ ->
                LibraryBackupRead.Projection(Unit, listOf(fixture.original, fixture.original))
            }
        val exact = StreamBackup(fixture)
        verify(exact, 3)
        assertEquals(1, exact.originalsOpened)
        val refused = StreamBackup(fixture)
        val failure = assertThrows(IOException::class.java) { verify(refused, 2) }
        assertEquals("The library exceeds the original verification budget", failure.message)
        assertEquals(0, refused.originalsOpened)
    }

    @Test fun detachedOriginalCopyRechecksTheExactCatalogBeforeAndAfterStreamingOneObject() {
        val fixture = fixture()
        val store = StreamBackup(fixture)
        val output = ByteArrayOutputStream()
        LibraryBackupRead(store, fixture.scratch.toPath(), 1024).copyExactOriginal(fixture.generation, fixture.original, output)
        assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
        assertEquals(1, store.originalsOpened)
        assertEquals(2, store.catalogsOpened)
        assertEquals(emptyList<String>(), fixture.scratch.list().orEmpty().toList())
    }

    @Test fun detachedOriginalCopyRejectsLostAccessAfterStreamCloseWithoutReturningSuccess() {
        val fixture = fixture()
        var closed = false
        val store = StreamBackup(fixture, beforeCatalogOpen = {
            if (closed) throw IOException("Selected tree is unavailable")
            true
        }, afterOriginalClose = { closed = true })
        val failure = assertThrows(IOException::class.java) {
            LibraryBackupRead(store, fixture.scratch.toPath(), 1024)
                .copyExactOriginal(fixture.generation, fixture.original, ByteArrayOutputStream())
        }
        assertEquals("Selected tree is unavailable", failure.message)
        assertEquals(1, store.originalsOpened)
    }

    @Test fun streamBackedBackupUsesTheSameByteAndGenerationProofWithoutAFileOrWriteCapability() {
        val fixture = fixture()
        val store = StreamBackup(fixture)
        val read = LibraryBackupRead(store, fixture.scratch.toPath(), 1024).read(8) { marker, catalog ->
            assertEquals(fixture.generation, marker)
            assertArrayEquals(fixture.catalog, catalog.readBytes())
            LibraryBackupRead.Projection("verified", listOf(fixture.original, fixture.original))
        }
        assertEquals("verified", read.value)
        assertEquals(2, store.inventoriesRead)
        assertEquals(2, store.catalogsOpened)
        assertEquals(1, store.originalsOpened)
        assertFalse(fixture.scratch.list().orEmpty().any())
    }

    @Test fun streamBackedProviderLossDuringFinalCheckCannotAcknowledgeTheCatalog() {
        val fixture = fixture()
        val store = StreamBackup(fixture, failReread = true)
        val failure = assertThrows(IOException::class.java) {
            LibraryBackupRead(store, fixture.scratch.toPath(), 1024).read(8) { _, _ ->
                LibraryBackupRead.Projection(Unit, listOf(fixture.original))
            }
        }
        assertEquals("Selected tree is unavailable", failure.message)
        assertEquals(1, store.originalsOpened)
        assertFalse(fixture.scratch.list().orEmpty().any())
    }

    @Test fun conflictingExtentsForOneDigestRejectBeforeOpeningAnyOriginal() {
        val fixture = fixture()
        val store = StreamBackup(fixture)
        assertThrows(IOException::class.java) {
            LibraryBackupRead(store, fixture.scratch.toPath(), 1024).read(8) { _, _ ->
                LibraryBackupRead.Projection(Unit, listOf(fixture.original, fixture.original.copy(byteCount = 4)))
            }
        }
        assertEquals(0, store.originalsOpened)
    }

    @Test fun cancellationAtTheLastOriginalClosePreventsReopeningTheCatalog() {
        val fixture = fixture()
        var canceled = false
        val store = StreamBackup(fixture, afterOriginalClose = { canceled = true })
        assertThrows(CancellationException::class.java) {
            LibraryBackupRead(store, fixture.scratch.toPath(), 1024, checkpoint = {
                if (canceled) throw CancellationException("test cancellation")
            }).read(8) { _, _ -> LibraryBackupRead.Projection(Unit, listOf(fixture.original)) }
        }
        assertEquals(1, store.catalogsOpened)
        assertEquals(1, store.originalsOpened)
    }

    @Test fun catalogOpenerDistinguishesKnownAbsenceFromInaccessibleProvider() {
        val fixture = fixture()
        val missing = StreamBackup(fixture, beforeCatalogOpen = { false })
        val inaccessible = StreamBackup(fixture, beforeCatalogOpen = { throw IOException("Selected tree is unavailable") })
        val messages = listOf(missing, inaccessible).map { source ->
            assertThrows(IOException::class.java) {
                LibraryBackupRead(source, fixture.scratch.toPath(), 1024).read<Unit>(8) { _, _ ->
                    error("The row reader must not receive an absent or inaccessible catalog")
                }
            }.message
        }
        assertEquals(listOf("Library catalog is missing", "Selected tree is unavailable"), messages)
    }

    @Test fun readsAClosedCatalogAndEveryDistinctReferencedObjectWithoutChangingTheBackup() {
        val fixture = fixture()
        val verified = fixture.reader.read(8) { _, catalog ->
            assertFalse(catalog.canonicalPath.startsWith(fixture.backup.path + File.separator))
            assertArrayEquals(fixture.catalog, catalog.readBytes())
            LibraryBackupRead.Projection("validated records", listOf(fixture.original, fixture.original))
        }
        assertEquals(fixture.generation, verified.generation)
        assertEquals("validated records", verified.value)
        assertArrayEquals(fixture.catalog, File(fixture.backup, fixture.generation.catalogPath).readBytes())
        assertArrayEquals(byteArrayOf(1, 2, 3), fixture.originalFile.readBytes())
        assertEquals(emptyList<String>(), fixture.scratch.list().orEmpty().toList())
    }

    @Test fun aChangedCatalogIsRejectedBeforeTheRowReaderRuns() {
        val fixture = fixture()
        File(fixture.backup, fixture.generation.catalogPath).writeBytes(byteArrayOf(9, 6, 5))
        assertThrows(IOException::class.java) {
            fixture.reader.read<Unit>(8) { _, _ -> error("Unauthenticated bytes reached the row reader") }
        }
        assertFalse(fixture.scratch.list().orEmpty().any())
    }

    @Test fun scratchCannotBeInsideTheBackupEvenAtTheLowerReadBoundary() {
        val fixture = fixture()
        val scratch = File(fixture.backup, "owned-test-child").apply { check(mkdir()) }
        val reader = LibraryBackupRead(fixture.store, scratch.toPath(), 1024)
        assertThrows(IOException::class.java) {
            reader.read(8) { _, _ -> LibraryBackupRead.Projection(Unit, listOf(fixture.original)) }
        }
        assertEquals(emptyList<String>(), scratch.list().orEmpty().toList())
    }

    @Test fun oversizedOrUnsupportedMarkersNeverReachTheRowReader() {
        for ((schema, maximum) in listOf(9 to 1024L, 8 to 2L)) {
            val fixture = fixture(schema, maximum)
            assertThrows(IOException::class.java) {
                fixture.reader.read<Unit>(8) { _, _ -> error("Unsupported catalog reached the row reader") }
            }
            assertFalse(fixture.scratch.list().orEmpty().any())
        }
    }

    @Test fun aSameLengthCorruptOriginalPreventsReadSuccess() {
        val fixture = fixture()
        val damage = byteArrayOf(1, 3, 2)
        fixture.originalFile.writeBytes(damage)
        assertThrows(IOException::class.java) {
            fixture.reader.read(8) { _, _ -> LibraryBackupRead.Projection(Unit, listOf(fixture.original)) }
        }
        assertArrayEquals(damage, fixture.originalFile.readBytes())
    }

    @Test fun anAliasedOriginalRefusesWithAnIoFailureWithoutTouchingTheLinkTarget() {
        val fixture = fixture()
        val unexpected = ObjectId("f".repeat(64), 3)
        val link = File(fixture.backup, "objects/${unexpected.sha256}").toPath()
        try { Files.createSymbolicLink(link, fixture.originalFile.toPath()) }
        catch (unsupported: UnsupportedOperationException) { assumeNoException(unsupported) }
        catch (unprivileged: IOException) { assumeNoException(unprivileged) }
        assertThrows(IOException::class.java) {
            fixture.reader.read(8) { _, _ -> LibraryBackupRead.Projection(Unit, listOf(unexpected)) }
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), fixture.originalFile.readBytes())
    }

    @Test fun replacingTheCatalogAtTheSameLocatorDoesNotAcknowledgeTheEarlierCopy() {
        val fixture = fixture()
        val damage = byteArrayOf(9, 6, 5)
        val catalog = File(fixture.backup, fixture.generation.catalogPath)
        assertThrows(IOException::class.java) {
            fixture.reader.read(8) { _, _ ->
                catalog.writeBytes(damage)
                LibraryBackupRead.Projection(Unit, listOf(fixture.original))
            }
        }
        assertArrayEquals(damage, catalog.readBytes())
    }

    @Test fun aNewTipDuringValidationNeverAcknowledgesTheSupersededBackup() {
        val fixture = fixture()
        val successor = fixture.generation.copy(id = "1bbb6a89-836f-4858-811d-e73237b95422", predecessorId = fixture.generation.id)
        assertThrows(IOException::class.java) {
            fixture.reader.read(8) { _, _ ->
                fixture.store.createMarker(successor, successor.encode())
                LibraryBackupRead.Projection(Unit, listOf(fixture.original))
            }
        }
        assertEquals(successor, fixture.store.readTip())
    }

    @Test fun aRetainedGenerationCanBeVerifiedAgainstTheExactExpectedPublishedTip() {
        val fixture = fixture()
        val successor = fixture.generation.copy(id = "1bbb6a89-836f-4858-811d-e73237b95422", predecessorId = fixture.generation.id)
        fixture.store.createMarker(successor, successor.encode())
        val verified = fixture.reader.read(8, fixture.generation, successor) { marker, file ->
            assertEquals(fixture.generation, marker)
            assertArrayEquals(fixture.catalog, file.readBytes())
            LibraryBackupRead.Projection(Unit, listOf(fixture.original))
        }
        assertEquals(fixture.generation, verified.generation)
        assertThrows(IOException::class.java) {
            fixture.reader.read<Unit>(8, fixture.generation, fixture.generation) { _, _ -> error("Unexpected tip reached projection") }
        }
        assertEquals(successor, fixture.store.readTip())
    }

    @Test fun checkingAnAdmittedCatalogAfterASuccessorPublishesReportsATypedTipChange() {
        val fixture = fixture()
        fixture.reader.requireCurrent(fixture.generation)
        val successor = fixture.generation.copy(id = "1bbb6a89-836f-4858-811d-e73237b95422", predecessorId = fixture.generation.id)
        fixture.store.createMarker(successor, successor.encode())
        assertThrows(app.pristinepage.data.library.UnexpectedLibraryTip::class.java) {
            fixture.reader.requireCurrent(fixture.generation)
        }
    }

    @Test fun exactSuccessorCheckCannotAcceptASameIdReplacementOfItsPredecessor() {
        val fixture = fixture()
        val predecessor = fixture.generation
        val successor = predecessor.copy(id = "1bbb6a89-836f-4858-811d-e73237b95422", predecessorId = predecessor.id)
        fixture.store.createCatalog(successor) { it.write(fixture.catalog) }
        fixture.store.createMarker(successor, successor.encode())
        fixture.reader.requireCurrent(successor, predecessor)

        val changed = predecessor.copy(catalog = predecessor.catalog.copy(sha256 = "b".repeat(64)))
        File(fixture.backup, "generations/${predecessor.id}.complete").writeBytes(changed.encode())
        fixture.reader.requireCurrent(successor)
        assertThrows(IOException::class.java) { fixture.reader.requireCurrent(successor, predecessor) }
    }

    @Test fun anUnchangedTipOnlyNominatesAndRecoveryReadbackRequiresThePredecessorCatalogBytes() {
        val fixture = fixture()
        val predecessor = fixture.generation
        assertEquals(predecessor, fixture.reader.observeTip(predecessor))
        val successor = predecessor.copy(id = "1bbb6a89-836f-4858-811d-e73237b95422", predecessorId = predecessor.id)
        fixture.store.createCatalog(successor) { it.write(fixture.catalog) }
        fixture.store.createMarker(successor, successor.encode())
        assertEquals(successor, fixture.reader.observeTip(predecessor))
        fixture.reader.requireCurrent(successor, predecessor, verifyRetainedCatalog = true)

        val changed = fixture.catalog.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        File(fixture.backup, predecessor.catalogPath).writeBytes(changed)
        assertEquals(successor, fixture.reader.observeTip(predecessor))
        assertThrows(IOException::class.java) { fixture.reader.requireCurrent(successor, predecessor, verifyRetainedCatalog = true) }
    }

    @Test fun changingTheRetainedMarkerDuringProjectionCannotBeAcknowledgedEvenWhenTheTipStaysExact() {
        val fixture = fixture()
        val original = fixture.generation
        val child = original.copy(id = "1bbb6a89-836f-4858-811d-e73237b95422", predecessorId = original.id)
        fixture.store.createMarker(child, child.encode())
        assertThrows(IOException::class.java) {
            fixture.reader.read(8, original, child) { _, _ ->
                val changed = original.copy(catalog = original.catalog.copy(sha256 = "b".repeat(64)))
                File(fixture.backup, "generations/${original.id}.complete").writeBytes(changed.encode())
                LibraryBackupRead.Projection(Unit, listOf(fixture.original))
            }
        }
        assertEquals(child, fixture.store.readTip())
    }

    private class Fixture(
        val backup: File, val scratch: File, val store: LocalLibraryDirectory, val reader: LibraryBackupRead,
        val generation: LibraryGeneration, val catalog: ByteArray, val original: ObjectId,
    ) {
        val originalFile = File(backup, "objects/${original.sha256}")
    }

    private class StreamBackup(
        private val fixture: Fixture,
        private val failReread: Boolean = false,
        private val beforeCatalogOpen: () -> Boolean = { true },
        private val afterOriginalClose: () -> Unit = {},
    ) : LibraryBackupRead.Source {
        var inventoriesRead = 0
            private set
        var catalogsOpened = 0
            private set
        var originalsOpened = 0
            private set

        override fun requireIndependentScratch(scratch: Path) {
            assertEquals(fixture.scratch.toPath(), scratch)
        }

        override fun generationInventory(checkpoint: () -> Unit): LibraryGenerationInventory {
            checkpoint()
            if (++inventoriesRead > 1 && failReread) throw IOException("Selected tree is unavailable")
            return LibraryGenerationInventory(listOf(fixture.generation), 1)
        }

        override fun openCatalog(generation: LibraryGeneration): InputStream? {
            assertEquals(fixture.generation, generation)
            catalogsOpened++
            return if (beforeCatalogOpen()) fixture.catalog.inputStream() else null
        }

        override fun open(id: ObjectId): InputStream {
            assertEquals(fixture.original, id)
            originalsOpened++
            return object : java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
                override fun close() = afterOriginalClose()
            }
        }
    }

    private fun fixture(schema: Int = 8, maximum: Long = 1024): Fixture {
        val backup = temporary.newFolder().canonicalFile
        val scratch = temporary.newFolder().canonicalFile
        val store = LocalLibraryDirectory(backup, DirectorySync { true })
        val catalog = byteArrayOf(7, 6, 5)
        val bytes = byteArrayOf(1, 2, 3)
        val original = identity(bytes)
        val marker = LibraryGeneration("26ff0221-9e6e-4aae-8c4b-39aa9ea07d32", "9dfc2b46-9d57-43f4-946a-ea1670e78616", null, schema, identity(catalog))
        store.createCatalog(marker) { it.write(catalog) }
        store.create(original) { it.write(bytes) }
        store.createMarker(marker, marker.encode())
        return Fixture(backup, scratch, store, LibraryBackupRead(store, scratch.toPath(), maximum), marker, catalog, original)
    }

    private fun identity(bytes: ByteArray) = ObjectId(
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) },
        bytes.size.toLong(),
    )
}
