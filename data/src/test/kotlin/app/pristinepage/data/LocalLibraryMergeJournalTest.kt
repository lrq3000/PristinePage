package app.pristinepage.data

import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.library.LibraryGeneration
import app.pristinepage.data.library.LibraryMergeIntent
import app.pristinepage.data.library.LibraryObjectTransfer.ObjectId
import app.pristinepage.data.library.LocalLibraryMergeJournal
import java.io.File
import java.io.IOException
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
        assertEquals(intent, LocalLibraryMergeJournal(root, root.parentFile.toPath(), sync).reopen(child.id))
    }

    @Test fun aFailedOwnedAncestorSyncCannotExposeAnIntent() {
        val parent = temporary.newFolder().canonicalFile
        val root = File(parent, "a/b/scratch").apply { check(mkdirs()) }
        val (journal, stage) = stage(root, DirectorySync { it != parent }, parent)
        assertThrows(IOException::class.java) { journal.publish(intent) }
        assertFalse(File(stage.parentFile, "merge.intent").exists())
        assertEquals(stageText, stage.readText())
    }

    private fun stage(root: File, sync: DirectorySync = DirectorySync { true }, ancestor: File = root.parentFile): Pair<LocalLibraryMergeJournal, File> {
        val stageDirectory = File(root, "merge-stages/${child.id}").apply { check(mkdirs()) }
        val stage = File(stageDirectory, "catalog.sqlite").apply { writeText(stageText) }
        return LocalLibraryMergeJournal(root, ancestor.toPath(), sync) to stage
    }

    private fun id(value: Int) = UUID(0, value.toLong()).toString()
}
