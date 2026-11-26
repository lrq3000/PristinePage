package app.pristinepage.data

import app.pristinepage.data.db.SourceBlobRow
import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.library.LibraryCatalogMerge
import app.pristinepage.data.library.LibraryBackupRead
import app.pristinepage.data.library.LibraryMergePayloads
import app.pristinepage.data.library.LibraryObjectTransfer.ObjectId
import app.pristinepage.data.library.LocalLibraryDirectory
import app.pristinepage.data.library.RoomLibrarySavedCatalog.Snapshot
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryMergePayloadsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val abc = ObjectId("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", 3)
    private val hello = ObjectId("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", 5)

    @Test fun readOnlyPreflightDoesNotCloneObjectsAndCountsOnlyOneMissingPayload() {
        val local = folder("destination", abc to "abc")
        val backup = folder("incoming", abc to "abc", hello to "hello")
        val prior = blob(1, abc)
        val matched = blob(2, abc)
        val firstNew = blob(3, hello)
        val secondNew = blob(4, hello)
        val plan = candidate(listOf(prior, firstNew, secondNew), listOf(match(1, 2)), mapOf(id(3) to id(3), id(4) to id(4)))
        val estimate = LibraryMergePayloads.check(plan, listOf(prior), listOf(matched, firstNew, secondNew), local, backup, 12, 17)
        assertEquals(5L, estimate.missingBytes)
        assertEquals(17L, estimate.requiredBytes)
        assertNull(local.open(hello))
        assertEquals("abc", local.open(abc)?.use { it.readBytes().decodeToString() })
        assertEquals("hello", backup.open(hello)?.use { it.readBytes().decodeToString() })
        assertThrows(IOException::class.java) {
            LibraryMergePayloads.check(plan, listOf(prior), listOf(matched, firstNew, secondNew), local, backup, 12, 16)
        }
        assertNull(local.open(hello))
    }

    @Test fun anIncomingMatchCannotRepairOrPaperOverAMissingDestinationOriginal() {
        val local = folder("destination")
        val backup = folder("incoming", abc to "abc")
        val prior = blob(1, abc)
        val matched = blob(2, abc)
        assertThrows(IOException::class.java) {
            LibraryMergePayloads.check(candidate(listOf(prior), listOf(match(1, 2))), listOf(prior), listOf(matched), local, backup, 0, 500)
        }
        assertNull(local.open(abc))
    }

    @Test fun sameCatalogDigestAndLengthAreNotEvidenceOfEqualIncomingBytes() {
        val local = folder("destination", abc to "abc")
        val backup = folder("incoming", abc to "xyz")
        val prior = blob(1, abc)
        val matched = blob(2, abc)
        assertThrows(IOException::class.java) {
            LibraryMergePayloads.check(candidate(listOf(prior), listOf(match(1, 2))), listOf(prior), listOf(matched), local, backup, 0, 0)
        }
        assertEquals("abc", local.open(abc)?.use { it.readBytes().decodeToString() })
        assertEquals("xyz", backup.open(abc)?.use { it.readBytes().decodeToString() })
    }

    @Test fun readOnlyPortsCompareMatchedBytesAndEstimateMissingObjectsWithoutAWriter() {
        val local = folder("destination", abc to "abc")
        val good = folder("good", abc to "abc", hello to "hello")
        val bad = folder("bad", abc to "xyz", hello to "hello")
        val prior = blob(1, abc)
        val matched = blob(2, abc)
        val appended = blob(3, hello)
        val plan = candidate(listOf(prior, appended), listOf(match(1, 2)), mapOf(id(3) to id(3)))
        fun estimate(source: LocalLibraryDirectory) =
            LibraryMergePayloads.estimate(plan, listOf(prior), listOf(matched, appended), local::open, source::open, {}, 0, Long.MAX_VALUE)
        assertEquals(5L, estimate(good).missingBytes)
        assertThrows(IOException::class.java) { estimate(bad) }
        assertNull(local.open(hello))
    }

    @Test fun theUnionMustFitEvenWhenTheTwoInputInventoriesFitIndividually() {
        assertEquals(listOf(abc), LibraryBackupRead.admitOriginals(listOf(abc, abc), 5).toList())
        assertEquals(listOf(hello), LibraryBackupRead.admitOriginals(listOf(hello), 5).toList())
        assertThrows(IOException::class.java) { LibraryBackupRead.admitOriginals(listOf(abc, hello), 5) }
        assertThrows(IOException::class.java) { LibraryBackupRead.admitOriginals(listOf(abc, abc.copy(byteCount = 2)), 8) }
    }

    @Test fun onlyTheExactReferencedSnapshotsCanSatisfyThePlan() {
        val prior = blob(1, abc)
        val local = folder("destination", abc to "abc", hello to "hello")
        val backup = folder("incoming", abc to "abc", hello to "hello")
        assertThrows(IOException::class.java) {
            LibraryMergePayloads.check(candidate(listOf(prior), listOf(match(1, 2))), listOf(prior), listOf(blob(2, hello)), local, backup, 0, 0)
        }
        assertThrows(IOException::class.java) {
            LibraryMergePayloads.check(candidate(listOf(prior)), listOf(prior, blob(3, hello)), emptyList(), local, backup, 0, 0)
        }
    }

    @Test fun aPreparedMergeCannotCopyAfterItsRootSeparationProofIsLost() {
        val local: LibraryMergePayloads.Destination = folder("destination", abc to "abc")
        val backup = folder("incoming", hello to "hello")
        val prior = blob(1, abc)
        val added = blob(2, hello)
        val plan = candidate(listOf(prior, added), incoming = mapOf(id(2) to id(2)))
        var current = true
        val prepared = LibraryMergePayloads.prepare(plan, listOf(prior), listOf(added), local, backup::open,
            requireSeparate = { if (!current) throw IOException("Root proof changed") }, 12, 17, published = false, checkpoint = {})
        assertEquals(5L, prepared.missingBytes)
        current = false
        assertThrows(IOException::class.java) { prepared.copyAndVerify() }
        assertNull(local.open(hello))
        assertEquals("hello", backup.open(hello)?.use { it.readBytes().decodeToString() })
    }

    @Test fun aChangedDestinationObservationCannotReachTheOtherRootsOriginal() {
        val local = folder("destination", abc to "abc")
        val backup = folder("incoming", abc to "abc")
        var separated = true
        var sourceOpens = 0
        val destination = object : LibraryMergePayloads.Destination by local {
            override fun open(id: ObjectId) = local.open(id).also { separated = false }
        }
        val prior = blob(1, abc)
        val incoming = blob(2, abc)
        val plan = candidate(listOf(prior), listOf(match(1, 2)))
        assertThrows(IOException::class.java) {
            LibraryMergePayloads.prepare(plan, listOf(prior), listOf(incoming), destination, {
                sourceOpens++
                backup.open(it)
            }, requireSeparate = { if (!separated) throw IOException("Root proof changed") }, 0, 0, published = false, checkpoint = {})
        }
        assertEquals(0, sourceOpens)
    }

    @Test fun lossAfterStagingAnOriginalCannotPublishItsFinalName() {
        val local = LocalLibraryDirectory(folder("destination").rootPath.toFile(), DirectorySync { true })
        val backup = folder("incoming", hello to "hello")
        val added = blob(2, hello)
        var separated = true
        var copying = false
        val prepared = LibraryMergePayloads.prepare(candidate(listOf(added), incoming = mapOf(id(2) to id(2))),
            emptyList(), listOf(added), local, { object : FilterInputStream(checkNotNull(backup.open(it))) {
                override fun close() {
                    super.close()
                    if (copying) separated = false
                }
            } }, requireSeparate = { if (!separated) throw IOException("Root proof changed") }, 0, 5,
            published = false, checkpoint = {})

        copying = true
        val failure = assertThrows(IOException::class.java) { prepared.copyAndVerify() }
        assertFalse("The incoming stream must close while copying", separated)
        assertNull(local.open(hello)?.use { it.readBytes() })
        assertEquals("Root proof changed", failure.message)
        assertEquals("hello", backup.open(hello)?.use { it.readBytes().decodeToString() })
    }

    private fun candidate(blobs: List<SourceBlobRow>, matches: List<LibraryCatalogMerge.RequiredMatch> = emptyList(),
        incoming: Map<String, String> = emptyMap(),
    ) = LibraryCatalogMerge.Candidate(Snapshot(emptyList(), emptyList(), blobs, emptyList()), matches, incoming)

    private fun match(left: Int, right: Int) = LibraryCatalogMerge.RequiredMatch(id(left), id(right))
    private fun id(i: Int) = UUID(0, i.toLong()).toString()
    private fun blob(i: Int, payload: ObjectId) = SourceBlobRow(id(i), payload.sha256, payload.byteCount,
        "image/png", 2, 2, "sources/${id(i)}/original.bin", "READY", 0, id(i + 100), 1)

    private fun folder(name: String, vararg objects: Pair<ObjectId, String>): LocalLibraryDirectory {
        val root = temporary.newFolder(name).canonicalFile
        val directory = File(root, "objects").apply { check(mkdir()) }
        for ((id, data) in objects) File(directory, id.sha256).writeText(data)
        return LocalLibraryDirectory(root)
    }
}
