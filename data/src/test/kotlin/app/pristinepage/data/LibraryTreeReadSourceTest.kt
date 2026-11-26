package app.pristinepage.data

import app.pristinepage.data.library.LibraryBackupRead
import app.pristinepage.data.library.LibraryGeneration
import app.pristinepage.data.library.LibraryObjectTransfer.ObjectId
import app.pristinepage.data.library.LibraryTreeReadSource
import app.pristinepage.data.library.LibraryTreeReadSource.Entry
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryTreeReadSourceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun readsBoundedTreeWithOpaqueIdsThroughTheRealBackupVerifier() {
        val fixture = Fixture()
        val scratch = temporary.newFolder().canonicalFile.toPath()
        val reader = LibraryBackupRead(fixture.source(), scratch, 1024)
        val result = reader.read(8) { _, catalog ->
            assertEquals("catalog", catalog.readText())
            LibraryBackupRead.Projection("admitted", listOf(fixture.a, fixture.b))
        }
        assertEquals(fixture.generation, result.generation)
        assertEquals("admitted", result.value)
        assertEquals(1, fixture.backend.listed.count { it == "n3" })
        assertEquals(1, fixture.backend.opened.count { it == "opaque-original-a" })
        assertEquals(1, fixture.backend.opened.count { it == "opaque-original-b" })
    }

    @Test fun duplicateManagedNameRejectsBeforeOpeningAnObject() {
        val fixture = Fixture()
        fixture.backend.tree.getValue("n3") += Entry("competing-id", fixture.a.sha256, false)
        val scratch = temporary.newFolder().canonicalFile.toPath()
        assertThrows(IOException::class.java) {
            LibraryBackupRead(fixture.source(), scratch, 1024).read(8) { _, _ ->
                LibraryBackupRead.Projection(Unit, listOf(fixture.a))
            }
        }
        assertEquals(emptyList<String>(), fixture.backend.opened.filter { it.startsWith("opaque-original") })
    }

    @Test fun providerListFailureNeverBecomesAReportedMissingObject() {
        val fixture = Fixture()
        fixture.backend.refused = "n3"
        val scratch = temporary.newFolder().canonicalFile.toPath()
        val failure = assertThrows(IOException::class.java) {
            LibraryBackupRead(fixture.source(), scratch, 1024).read(8) { _, _ ->
                LibraryBackupRead.Projection(Unit, listOf(fixture.a))
            }
        }
        assertEquals("Selected tree access lost", failure.message)
    }

    @Test fun aCompletionMarkerCannotBeAnAliasedOrMalformedChild() {
        val fixture = Fixture()
        fixture.backend.tree.getValue("n1").apply {
            clear()
            add(Entry("m", "not-a-generation-id.complete", false))
        }
        assertThrows(IOException::class.java) { fixture.source().generationInventory() }
        assertEquals(emptyList<String>(), fixture.backend.opened)
    }

    @Test fun anOpaqueOriginalIdCannotAlsoNameAnUnrelatedRootChild() {
        val fixture = Fixture()
        fixture.backend.tree.getValue("root") += Entry("opaque-original-a", "unexpected.txt", false)
        val scratch = temporary.newFolder().canonicalFile.toPath()
        assertThrows(IOException::class.java) {
            LibraryBackupRead(fixture.source(), scratch, 1024).read(8) { _, _ ->
                LibraryBackupRead.Projection(Unit, listOf(fixture.a))
            }
        }
        assertEquals(false, "opaque-original-a" in fixture.backend.opened)
    }

    @Test fun cancellationReturningAnEmptyObjectListingIsNotMissingData() {
        val fixture = Fixture()
        fixture.backend.tree.getValue("n3").clear()
        var canceled = false
        fixture.backend.afterList = { if (it == "n3") canceled = true }
        val scratch = temporary.newFolder().canonicalFile.toPath()
        assertThrows(CancellationException::class.java) {
            LibraryBackupRead(fixture.source(), scratch, 1024, checkpoint = {
                if (canceled) throw CancellationException("test cancellation")
            }).read(8) { _, _ -> LibraryBackupRead.Projection(Unit, listOf(fixture.a)) }
        }
    }

    @Test fun cancellationDuringMarkerOpenClosesTheStreamWithoutReadingIt() {
        val fixture = Fixture()
        var canceled = false
        fixture.backend.afterOpen = { if (it == "m") canceled = true }
        assertThrows(CancellationException::class.java) {
            fixture.source().generationInventory { if (canceled) throw CancellationException("test cancellation") }
        }
        assertEquals(listOf("m"), fixture.backend.closed)
        assertEquals(emptyList<String>(), fixture.backend.singleByteReads)
    }

    @Test fun replacingTheCatalogDocumentWithIdenticalBytesDoesNotAcknowledgeIt() {
        val fixture = Fixture()
        val scratch = temporary.newFolder().canonicalFile.toPath()
        assertThrows(IOException::class.java) {
            LibraryBackupRead(fixture.source(), scratch, 1024).read(8) { _, _ ->
                fixture.backend.tree.getValue("n2")[0] = Entry("substitute-catalog", "${fixture.generation.id}.sqlite", false)
                fixture.backend.files["substitute-catalog"] = fixture.backend.files.getValue("catalog-id")
                LibraryBackupRead.Projection(Unit, listOf(fixture.a))
            }
        }
        assertEquals(false, "substitute-catalog" in fixture.backend.opened)
    }

    @Test fun recoveryFinalReadRefusesAnIdenticalCatalogUnderADifferentProviderId() {
        val fixture = Fixture()
        val scratch = temporary.newFolder().canonicalFile.toPath()
        val recovery = LibraryBackupRead(LibraryTreeReadSource("root", fixture.backend, bindAcrossChecks = true), scratch, 1024)
        assertEquals(fixture.generation, recovery.observeTip(fixture.generation))
        recovery.read(8, fixture.generation, fixture.generation) { _, _ ->
            LibraryBackupRead.Projection(Unit, listOf(fixture.a))
        }

        fixture.backend.tree.getValue("n2")[0] = Entry("substitute-catalog", "${fixture.generation.id}.sqlite", false)
        fixture.backend.files["substitute-catalog"] = fixture.backend.files.getValue("catalog-id")
        assertThrows(IOException::class.java) { recovery.requireCurrent(fixture.generation) }
        assertEquals(false, "substitute-catalog" in fixture.backend.opened)
    }

    @Test fun aNewTipDuringProjectionRefusesTheEarlierGeneration() {
        val fixture = Fixture()
        val scratch = temporary.newFolder().canonicalFile.toPath()
        val successor = fixture.generation.copy(id = "1bbb6a89-836f-4858-811d-e73237b95422", predecessorId = fixture.generation.id)
        assertThrows(IOException::class.java) {
            LibraryBackupRead(fixture.source(), scratch, 1024).read(8) { _, _ ->
                fixture.backend.tree.getValue("n1") += Entry("successor", "${successor.id}.complete", false)
                fixture.backend.files["successor"] = successor.encode()
                LibraryBackupRead.Projection(Unit, listOf(fixture.a))
            }
        }
    }

    private class Fixture {
        val a = identify("a".encodeToByteArray())
        val b = identify("b".encodeToByteArray())
        val generation = LibraryGeneration(
            "26ff0221-9e6e-4aae-8c4b-39aa9ea07d32", "9dfc2b46-9d57-43f4-946a-ea1670e78616", null,
            8, identify("catalog".encodeToByteArray()),
        )
        val backend = TestBackend(
            mutableMapOf(
                "root" to mutableListOf(Entry("n1", "generations", true), Entry("n2", "catalogs", true), Entry("n3", "objects", true), Entry("other", "unrelated.txt", false)),
                "n1" to mutableListOf(Entry("m", "${generation.id}.complete", false)),
                "n2" to mutableListOf(Entry("catalog-id", "${generation.id}.sqlite", false)),
                "n3" to mutableListOf(Entry("opaque-original-a", a.sha256, false), Entry("opaque-original-b", b.sha256, false)),
            ),
            mutableMapOf("m" to generation.encode(), "catalog-id" to "catalog".encodeToByteArray(), "opaque-original-a" to byteArrayOf(97), "opaque-original-b" to byteArrayOf(98)),
        )

        fun source() = LibraryTreeReadSource("root", backend)
    }

    private class TestBackend(
        val tree: MutableMap<String, MutableList<Entry>>,
        val files: MutableMap<String, ByteArray>,
    ) : LibraryTreeReadSource.Backend {
        val listed = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val closed = mutableListOf<String>()
        val singleByteReads = mutableListOf<String>()
        var refused: String? = null
        var afterList: (String) -> Unit = {}
        var afterOpen: (String) -> Unit = {}
        override fun requireIndependentScratch(scratch: Path) = Unit
        override fun list(parentId: String, maximum: Int, checkpoint: () -> Unit): List<Entry> {
            listed += parentId
            if (refused == parentId) throw IOException("Selected tree access lost")
            return tree.getValue(parentId).onEach { checkpoint() }.toList().also { afterList(parentId) }
        }
        override fun open(id: String): InputStream {
            opened += id
            return object : java.io.ByteArrayInputStream(files.getValue(id)) {
                override fun read(): Int { singleByteReads += id; return super.read() }
                override fun close() { closed += id }
            }.also { afterOpen(id) }
        }
    }

    private companion object {
        fun identify(bytes: ByteArray): ObjectId = ObjectId(
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) },
            bytes.size.toLong(),
        )
    }
}
