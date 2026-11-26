package app.pristinepage.data

import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.library.LibraryGeneration
import app.pristinepage.data.library.LibraryMergeIntent
import app.pristinepage.data.library.LibraryObjectTransfer.ObjectId
import app.pristinepage.data.library.LocalLibraryMergeJournal
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.FileTime
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalLibraryMergeJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private val stageText = "a".repeat(512)
    private val payload = ObjectId("471be6558b665e4f6dd49f1184814d1491b0315d466beea768c153cc5500c836", 512)
    private val baseline = LibraryGeneration(id(1), id(2), null, 8, payload)
    private val incoming = LibraryGeneration(id(3), id(4), null, 8, payload)
    private val child = baseline.copy(id = id(5), predecessorId = baseline.id)
    private val intent = LibraryMergeIntent("a".repeat(64), "b".repeat(64), baseline, incoming, child)

    @Test fun opensAnExactDurableIntentOnlyWithItsClosedStageAndDoesNotTouchSiblingFiles() {
        val root = temporary.newFolder().canonicalFile
        val (journal, stage) = stage(root)
        val sibling = File(stage.parentFile, "owner-note").apply { writeText("keep") }
        assertNull(journal.reopen(child.id))
        assertEquals(intent, journal.publish(intent))
        assertEquals(intent, journal.reopen(child.id))
        assertEquals(intent, journal.publish(intent))
        assertEquals("keep", sibling.readText())
        assertEquals(stageText, stage.readText())

        stage.writeText("xyz")
        assertThrows(IOException::class.java) { journal.reopen(child.id) }
        assertEquals("xyz", stage.readText())
    }

    @Test fun existingIntentCannotBeOverwrittenOrReboundToAnotherSourceGeneration() {
        val (journal, _) = stage(temporary.newFolder().canonicalFile)
        journal.publish(intent)
        val wrong = intent.copy(incoming = incoming.copy(id = id(6)))
        assertThrows(IOException::class.java) { journal.publish(wrong) }
        assertEquals(intent, journal.reopen(child.id))
    }

    @Test fun aVisibleIntentAfterSyncFailureCannotBeReadmittedUntilReverifiedAndDurable() {
        val root = temporary.newFolder().canonicalFile
        var failIntentSync = true
        val sync = DirectorySync { directory ->
            !(failIntentSync && File(directory, "merge.intent").isFile)
        }
        val (journal, _) = stage(root, sync)
        assertThrows(IOException::class.java) { journal.publish(intent) }
        assertTrue(File(root, "merge-stages/${child.id}/merge.intent").isFile)
        assertThrows(IOException::class.java) { journal.reopen(child.id) }
        failIntentSync = false
        assertEquals(intent, LocalLibraryMergeJournal(root, checkNotNull(root.parentFile).toPath(), sync).reopen(child.id))
    }

    @Test fun aFailedOwnedAncestorSyncCannotExposeAnIntent() {
        val parent = temporary.newFolder().canonicalFile
        val root = File(parent, "a/b/scratch").apply { check(mkdirs()) }
        val (journal, stage) = stage(root, DirectorySync { it != parent }, parent)
        assertThrows(IOException::class.java) { journal.publish(intent) }
        assertFalse(File(stage.parentFile, "merge.intent").exists())
        assertEquals(stageText, stage.readText())
    }

    @Test fun onlyTheLiveSelectedAttemptCanRecordCompletionAndReopenItWithoutReplay() {
        val root = temporary.newFolder().canonicalFile
        val (writer, stage) = stage(root)
        val cold = selectedJournal(root)
        val live = writer.startSelected(intent)
        assertEquals(LocalLibraryMergeJournal.SelectedState(intent, completed = false), cold.inspectSelected(child.id))
        assertThrows(IOException::class.java) { cold.startSelected(intent) }
        live.recordCompletion(child)
        assertEquals(LocalLibraryMergeJournal.SelectedState(intent, completed = true), cold.inspectSelected(child.id))
        val receipt = File(stage.parentFile, "selected-merge.complete")
        val bytes = receipt.readBytes()
        assertThrows(IOException::class.java) { live.recordCompletion(child) }
        assertArrayEquals(bytes, receipt.readBytes())
        assertEquals(stageText, stage.readText())
    }

    @Test fun aSelectedCompletionWithTheWrongGenerationCannotPublishOrRenewItsToken() {
        val (journal, stage) = stage(temporary.newFolder().canonicalFile)
        val live = journal.startSelected(intent)
        assertThrows(IOException::class.java) { live.recordCompletion(baseline) }
        assertThrows(IOException::class.java) { live.recordCompletion(child) }
        assertFalse(File(stage.parentFile, "selected-merge.complete").exists())
        assertEquals(LocalLibraryMergeJournal.SelectedState(intent, completed = false), journal.inspectSelected(child.id))
    }

    @Test fun aVisibleSelectedCompletionMayOnlyBeReadAfterItsDirectoryBarrierSucceeds() {
        var fail = true
        val (journal, stage) = stage(temporary.newFolder().canonicalFile,
            DirectorySync { parent -> !(fail && File(parent, "selected-merge.complete").isFile) })
        val live = journal.startSelected(intent)
        assertThrows(IOException::class.java) { live.recordCompletion(child) }
        val receipt = File(stage.parentFile, "selected-merge.complete")
        assertTrue(receipt.isFile)
        assertThrows(IOException::class.java) { journal.inspectSelected(child.id) }
        fail = false
        assertEquals(LocalLibraryMergeJournal.SelectedState(intent, completed = true), journal.inspectSelected(child.id))
        receipt.writeBytes(byteArrayOf(1))
        assertThrows(IOException::class.java) { journal.inspectSelected(child.id) }
        assertArrayEquals(byteArrayOf(1), receipt.readBytes())
    }

    @Test fun aSelectedCompletionWithoutItsIntentCanNeitherBeAdoptedNorReissued() {
        val (journal, stage) = stage(temporary.newFolder().canonicalFile)
        journal.startSelected(intent).recordCompletion(child)
        val receipt = File(stage.parentFile, "selected-merge.complete")
        val bytes = receipt.readBytes()
        check(File(stage.parentFile, "merge.intent").delete())
        assertThrows(IOException::class.java) { journal.inspectSelected(child.id) }
        assertThrows(IOException::class.java) { journal.startSelected(intent) }
        assertFalse(File(stage.parentFile, "merge.intent").exists())
        assertArrayEquals(bytes, receipt.readBytes())
    }

    @Test fun selectedColdInspectionRejectsCatalogBytesChangedDuringTheDirectoryBarrier() {
        var corrupt = false
        val (journal, _) = stage(temporary.newFolder().canonicalFile, DirectorySync { parent ->
            val receipt = File(parent, "selected-merge.complete")
            if (corrupt && receipt.isFile) File(parent, "catalog.sqlite").writeText("b".repeat(512))
            true
        })
        journal.startSelected(intent).recordCompletion(child)
        corrupt = true
        assertThrows(IOException::class.java) { journal.inspectSelected(child.id) }
    }

    @Test fun selectedInspectionDistinguishesAnAbsentAttemptFromRetainedPreIntentEvidence() {
        val (journal, stage) = stage(temporary.newFolder().canonicalFile)
        assertNull(journal.inspectSelected(id(6)))
        assertThrows(IOException::class.java) { journal.inspectSelected(child.id) }
        val spill = File(stage.parentFile, ".pristinepage-interrupted.part").apply { writeText("retain") }
        check(stage.delete())
        assertThrows(IOException::class.java) { journal.inspectSelected(child.id) }
        assertEquals("retain", spill.readText())
    }

    @Test fun aSelectedReceiptCannotConfirmACatalogWithAnUnexpectedSidecar() {
        val (journal, stage) = stage(temporary.newFolder().canonicalFile)
        journal.startSelected(intent).recordCompletion(child)
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            val sidecar = File(stage.parentFile, stage.name + suffix).apply { writeText("retain") }
            assertThrows(IOException::class.java) { journal.inspectSelected(child.id) }
            assertEquals("retain", sidecar.readText())
            check(sidecar.delete())
        }
    }

    @Test fun coldInspectionRejectsAnExactByteReceiptReplacedAfterItsDirectoryBarrier() {
        var replace = false
        val (journal, stage) = stage(temporary.newFolder().canonicalFile, DirectorySync { parent ->
            if (replace && parent.name == "merge-stages") {
                replace = false
                val receipt = File(parent, "${child.id}/selected-merge.complete")
                val original = File(receipt.parentFile, "retained-original")
                Files.move(receipt.toPath(), original.toPath())
                Files.copy(original.toPath(), receipt.toPath())
                // The retained original prevents inode reuse. Distinct times also identify the test nodes on Windows JVMs without fileKey().
                Files.getFileAttributeView(receipt.toPath(), BasicFileAttributeView::class.java)
                    .setTimes(null, null, FileTime.fromMillis(946_684_800_000))
            }
            true
        })
        journal.startSelected(intent).recordCompletion(child)
        replace = true
        assertThrows(IOException::class.java) { journal.inspectSelected(child.id) }
        val retained = File(stage.parentFile, "retained-original")
        assertArrayEquals(retained.readBytes(), File(stage.parentFile, "selected-merge.complete").readBytes())
    }

    @Test fun coldInspectionAdmitsCatalogExtentBeforeItsBarrier() {
        var syncs = 0
        val (journal, stage) = stage(temporary.newFolder().canonicalFile, DirectorySync { ++syncs; true })
        journal.startSelected(intent).recordCompletion(child)
        stage.appendText("extra")
        syncs = 0
        assertThrows(IOException::class.java) { journal.inspectSelected(child.id) }
        assertEquals(0, syncs)
    }

    @Test fun lossOfAuthorityAfterStagingButBeforeLinkCannotLeaveACompletion() {
        val (journal, stage) = stage(temporary.newFolder().canonicalFile)
        val live = journal.startSelected(intent)
        assertThrows(IOException::class.java) {
            live.recordCompletion(child, requireOwner = {
                if (stage.parentFile.list().orEmpty().any { it.endsWith(".part") }) throw IOException("Issuing authority changed")
            })
        }
        assertFalse(File(stage.parentFile, "selected-merge.complete").exists())
        assertEquals(LocalLibraryMergeJournal.SelectedState(intent, completed = false), journal.inspectSelected(child.id))
    }

    private fun stage(root: File, sync: DirectorySync = DirectorySync { true }, ancestor: File = checkNotNull(root.parentFile)): Pair<LocalLibraryMergeJournal, File> {
        val stageDirectory = File(root, "merge-stages/${child.id}").apply { check(mkdirs()) }
        val stage = File(stageDirectory, "catalog.sqlite").apply { writeText(stageText) }
        return selectedJournal(root, sync, ancestor) to stage
    }

    private fun selectedJournal(root: File, sync: DirectorySync = DirectorySync { true }, ancestor: File = checkNotNull(root.parentFile)) =
        // Test-only fallback for Windows providers with no file key. The synthetic replacement fixture sets a known distinct creation time.
        LocalLibraryMergeJournal(root, ancestor.toPath(), sync, nodeIdentity = { _, attrs -> attrs.fileKey() ?: attrs.creationTime() })

    private fun id(value: Int) = UUID(0, value.toLong()).toString()
}
