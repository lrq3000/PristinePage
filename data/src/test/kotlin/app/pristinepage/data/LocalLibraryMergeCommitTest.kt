package app.pristinepage.data

import app.pristinepage.data.db.SourceBlobRow
import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.library.LibraryCatalogMerge
import app.pristinepage.data.library.LibraryGeneration
import app.pristinepage.data.library.LibraryGenerationChain
import app.pristinepage.data.library.LibraryMergeIntent
import app.pristinepage.data.library.LibraryObjectTransfer.ObjectId
import app.pristinepage.data.library.LocalLibraryDirectory
import app.pristinepage.data.library.LocalLibraryMergeCommit
import app.pristinepage.data.library.LocalLibraryMergeJournal
import app.pristinepage.data.library.RoomLibrarySavedCatalog.Snapshot
import java.io.File
import java.io.IOException
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalLibraryMergeCommitTest {
    @get:Rule val temporary = TemporaryFolder()
    private val abc = ObjectId("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", 3)
    private val hello = ObjectId("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", 5)
    private val placeholder = ObjectId("471be6558b665e4f6dd49f1184814d1491b0315d466beea768c153cc5500c836", 512)
    private val stageText = "a".repeat(512)
    private val localKey = "a".repeat(64)
    private val incomingKey = "b".repeat(64)
    private val baseline = LibraryGeneration(id(1), id(2), null, 8, placeholder)
    private val source = LibraryGeneration(id(3), id(4), null, 8, placeholder)
    private val next = baseline.copy(id = id(5), predecessorId = baseline.id)
    private val intent = LibraryMergeIntent(localKey, incomingKey, baseline, source, next)
    private val retained = blob(1, abc)
    private val added = blob(2, hello)
    private val candidate = LibraryCatalogMerge.Candidate(Snapshot(emptyList(), emptyList(), listOf(retained, added), emptyList()),
        emptyList(), mapOf(added.sourceBlobId to added.sourceBlobId))

    @Test fun failedDestinationReopenLeavesThePredecessorAndRetryPublishesOnlyOnce() {
        val f = Fixture()
        val incomingBefore = inventory(f.backupRoot)
        val verify: (File) -> Unit = { assertEquals(stageText, it.readText()) }
        var calls = 0
        assertThrows(IOException::class.java) {
            f.commit {
                verify(it)
                if (++calls == 2) throw IOException("Synthetic destination reopen refusal")
            }
        }
        assertEquals(2, calls)
        assertEquals(baseline, f.destination.readTip())
        assertEquals("hello", f.destination.open(hello)?.use { it.readBytes().decodeToString() })
        assertEquals(next, f.commit(verify))
        assertEquals(next, f.commit(verify))
        assertEquals(2, File(f.targetRoot, "generations").list()?.size)
        assertEquals(stageText, f.destination.openCatalog(baseline)?.use { it.readBytes().decodeToString() })
        assertEquals(incomingBefore, inventory(f.backupRoot))
    }

    @Test fun aVisibleButUnacknowledgedChildCanOnlyBeVerifiedNotRepaired() {
        var refuse = false
        val f = Fixture(DirectorySync { directory ->
            !(refuse && File(directory, "${next.id}.complete").isFile)
        })
        refuse = true
        assertThrows(IOException::class.java) { f.commit { } }
        assertEquals(next, f.destination.readTip())
        assertThrows(IOException::class.java) { f.commit { } }
        refuse = false
        assertEquals(next, f.commit { })
        check(File(f.targetRoot, "objects/${hello.sha256}").delete()) // Intentionally damage only this test-owned fixture.
        assertThrows(IOException::class.java) { f.commit { } }
        assertNull(f.destination.open(hello))
        assertEquals(next, f.destination.readTip())
    }

    @Test fun aChangedDestinationOrMissingJournalRefusesBeforeAnyNewObject() {
        val changed = Fixture()
        val unrelated = baseline.copy(id = id(6), predecessorId = baseline.id)
        changed.destination.createMarker(unrelated, unrelated.encode())
        assertThrows(IOException::class.java) { changed.commit { fail("A stale intent must not reach readback") } }
        assertNull(changed.destination.open(hello))
        val absent = Fixture(withIntent = false)
        assertThrows(IOException::class.java) { absent.commit { fail("Unjournaled intent reached readback") } }
        assertNull(absent.destination.open(hello))
    }

    @Test fun corruptedPreexistingCandidateIsRejectedBeforeAnyObjectCopy() {
        val f = Fixture()
        f.destination.createCatalog(next) { it.write("x".repeat(512).encodeToByteArray()) }
        assertThrows(IOException::class.java) { f.commit { fail("Corrupt destination catalog reached readback") } }
        assertNull(f.destination.open(hello))
        assertEquals(baseline, f.destination.readTip())
    }

    @Test fun visibleChildCannotAcknowledgeAReplacedPredecessorMarker() {
        val f = Fixture()
        assertEquals(next, f.commit { })
        val changed = baseline.copy(catalog = baseline.catalog.copy(sha256 = "d".repeat(64)))
        File(f.targetRoot, "generations/${baseline.id}.complete").writeBytes(changed.encode())
        assertEquals(next, f.destination.readTip())
        val tree = inventory(f.targetRoot)
        assertThrows(IOException::class.java) { f.commit { } }
        assertEquals(tree, inventory(f.targetRoot))
    }

    @Test fun visibleChildWithMissingCatalogIsNotReconstructedFromTheStage() {
        val f = Fixture()
        assertEquals(next, f.commit { })
        check(File(f.targetRoot, next.catalogPath).delete()) // Test-owned corruption.
        val tree = inventory(f.targetRoot)
        assertThrows(IOException::class.java) { f.commit { } }
        assertEquals(next, f.destination.readTip())
        assertEquals(tree, inventory(f.targetRoot))
    }

    @Test fun aFullGenerationChainIsRefusedBeforeCopyingAnOriginal() {
        val exhausted = Fixture(predecessors = LibraryGenerationChain.MAX_MARKERS - 1)
        assertEquals(exhausted.baseline, exhausted.destination.readTip())
        val tree = inventory(exhausted.targetRoot)
        assertThrows(IOException::class.java) { exhausted.commit { fail("A full chain reached stage readback") } }
        assertEquals(tree, inventory(exhausted.targetRoot))
        assertNull(exhausted.destination.open(hello))
    }

    @Test fun aCandidateCannotReuseAnOlderAncestorEvenWithAnExactStageAndIntent() {
        val ancestorCollision = Fixture(predecessors = 2, reuseOldAncestor = true)
        assertEquals(ancestorCollision.baseline, ancestorCollision.destination.readTip())
        val tree = inventory(ancestorCollision.targetRoot)
        assertThrows(IOException::class.java) { ancestorCollision.commit { fail("Reused generation reached stage readback") } }
        assertEquals(tree, inventory(ancestorCollision.targetRoot))
    }

    @Test fun aLinkedObjectFromAnInterruptedCopyStillNeedsADirectoryBarrier() {
        var refuse = false
        val f = Fixture(DirectorySync { directory ->
            !(refuse && directory.name == "objects" && File(directory, hello.sha256).isFile)
        })
        refuse = true
        assertThrows(IOException::class.java) { f.commit { } }
        assertNotNull(f.destination.open(hello)?.use { it.readBytes() })
        assertThrows(IOException::class.java) { f.commit { } }
        assertEquals(baseline, f.destination.readTip())
        refuse = false
        assertEquals(next, f.commit { })
    }

    @Test fun aSourceTipChangeDuringTheDestinationPayloadBarrierCannotPublishTheMarker() {
        val successor = source.copy(id = id(7), predecessorId = source.id)
        var changeAtBarrier: ((File) -> Unit)? = null
        val f = Fixture(DirectorySync { dir -> changeAtBarrier?.invoke(dir); true })
        changeAtBarrier = { dir ->
            if (dir == f.targetRoot && File(dir, next.catalogPath).isFile) {
                changeAtBarrier = null
                f.backup.createCatalog(successor) { it.write(stageText.encodeToByteArray()) }
                f.backup.createMarker(successor, successor.encode())
            }
        }
        assertThrows(IOException::class.java) { f.commit { } }
        assertEquals(successor, f.backup.readTip())
        assertEquals(baseline, f.destination.readTip())
        assertFalse(File(f.targetRoot, "generations/${next.id}.complete").exists())
    }

    @Test fun aSourceTipChangeDuringMarkerStagingCannotPublishTheMarker() {
        val successor = source.copy(id = id(7), predecessorId = source.id)
        var changeAtBarrier: ((File) -> Unit)? = null
        val f = Fixture(DirectorySync { dir -> changeAtBarrier?.invoke(dir); true })
        changeAtBarrier = { dir ->
            if (dir == File(f.targetRoot, "generations") && !File(dir, "${next.id}.complete").exists()) {
                changeAtBarrier = null
                f.backup.createCatalog(successor) { it.write(stageText.encodeToByteArray()) }
                f.backup.createMarker(successor, successor.encode())
            }
        }
        assertThrows(IOException::class.java) { f.commit { } }
        assertEquals(successor, f.backup.readTip())
        assertEquals(baseline, f.destination.readTip())
    }

    @Test fun aChangedChildCatalogDuringPayloadSyncCannotBecomeTheTip() {
        var changeAtBarrier: ((File) -> Unit)? = null
        val f = Fixture(DirectorySync { dir -> changeAtBarrier?.invoke(dir); true })
        changeAtBarrier = { dir ->
            if (dir == f.targetRoot && File(dir, next.catalogPath).isFile) {
                changeAtBarrier = null
                File(dir, next.catalogPath).writeText("x".repeat(512))
            }
        }
        assertThrows(IOException::class.java) { f.commit { } }
        assertEquals(baseline, f.destination.readTip())
        assertEquals(stageText, f.destination.openCatalog(baseline)?.use { it.readBytes().decodeToString() })
    }

    @Test fun replayCannotAcknowledgeAChangedOriginalAfterMarkerSync() {
        var changeAtBarrier: ((File) -> Unit)? = null
        val f = Fixture(DirectorySync { dir -> changeAtBarrier?.invoke(dir); true })
        assertEquals(next, f.commit { })
        changeAtBarrier = { dir ->
            if (dir == File(f.targetRoot, "generations")) {
                changeAtBarrier = null
                File(f.targetRoot, "objects/${hello.sha256}").writeText("other")
            }
        }
        assertThrows(IOException::class.java) { f.commit { } }
        assertEquals(next, f.destination.readTip())
        assertEquals("other", f.destination.open(hello)?.use { it.readBytes().decodeToString() })
    }

    private inner class Fixture(sync: DirectorySync = DirectorySync { true }, withIntent: Boolean = true,
        predecessors: Int = 0, reuseOldAncestor: Boolean = false,
    ) {
        val targetRoot = temporary.newFolder().canonicalFile
        val backupRoot = temporary.newFolder().canonicalFile
        private val work = temporary.newFolder().canonicalFile
        val destination = LocalLibraryDirectory(targetRoot, sync)
        val backup = LocalLibraryDirectory(backupRoot, DirectorySync { true })
        private val journal = LocalLibraryMergeJournal(work, checkNotNull(work.parentFile).toPath(), DirectorySync { true })
        private val ancestorIds = List(predecessors) { id(1_000 + it) }
        val baseline = this@LocalLibraryMergeCommitTest.baseline.copy(predecessorId = ancestorIds.lastOrNull())
        private val next = intent.candidate.copy(id = if (reuseOldAncestor) ancestorIds.first() else intent.candidate.id)

        init {
            destination.create(abc) { it.write("abc".encodeToByteArray()) }
            backup.create(hello) { it.write("hello".encodeToByteArray()) }
            for ((directory, generation) in listOf(destination to baseline, backup to source)) {
                directory.createCatalog(generation) { it.write(stageText.encodeToByteArray()) }
                directory.createMarker(generation, generation.encode())
            }
            for ((index, ancestor) in ancestorIds.withIndex()) {
                val marker = baseline.copy(id = ancestor, predecessorId = ancestorIds.getOrNull(index - 1))
                File(targetRoot, "generations/$ancestor.complete").writeBytes(marker.encode())
            }
            val stage = File(work, "merge-stages/${next.id}").apply { check(mkdirs()) }
            File(stage, "catalog.sqlite").writeText(stageText)
            if (withIntent) journal.publish(intent.copy(destination = baseline, candidate = next))
        }

        fun commit(verify: (File) -> Unit) = LocalLibraryMergeCommit(destination, backup, journal)
            .commit(next.id, localKey, incomingKey, 8, candidate, listOf(retained), listOf(added), verify)
    }

    private fun id(value: Int) = UUID(0, value.toLong()).toString()
    private fun blob(value: Int, bytes: ObjectId) = SourceBlobRow(id(value), bytes.sha256, bytes.byteCount,
        "image/png", 2, 2, "sources/${id(value)}/original.bin", "READY", 0, id(value + 100), 1)
    private fun inventory(directory: File) = directory.walkTopDown().filter(File::isFile)
        .associate { it.relativeTo(directory).path to it.readBytes().toList() }
}
