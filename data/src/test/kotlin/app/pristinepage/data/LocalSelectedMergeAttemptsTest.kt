package app.pristinepage.data

import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.library.LibraryGeneration
import app.pristinepage.data.library.LibraryMergeIntent
import app.pristinepage.data.library.LibraryObjectTransfer.ObjectId
import app.pristinepage.data.library.LocalLibraryMergeJournal
import app.pristinepage.data.library.LocalSelectedMergeAttempts
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalSelectedMergeAttemptsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val payload = ObjectId("471be6558b665e4f6dd49f1184814d1491b0315d466beea768c153cc5500c836", 512)
    private val destination = LibraryGeneration(id(100), id(101), null, 8, payload)
    private val incoming = LibraryGeneration(id(200), id(201), null, 8, payload)

    @Test fun verifiedCompletionAdmitsANewIdButNeverReissuesThePriorId() {
        val (root, attempts) = fixture()
        val first = complete(attempts, 1)
        val firstStage = File(root, "merge-stages/${id(1)}")
        val before = File(firstStage, "selected-merge.complete").readBytes()
        assertThrows(IOException::class.java) { attempts.allocate(id(1)) }
        val second = complete(attempts, 2)
        assertEquals(id(2), second.candidate.id)
        assertEquals(first, firstReceipt(root, 1))
        assertArrayEquals(before, File(firstStage, "selected-merge.complete").readBytes())
    }

    @Test fun anUnresolvedPriorAttemptBlocksAnotherWriteWithoutChangingEvidence() {
        val (root, attempts) = fixture()
        val allocated = attempts.allocate(id(1))
        val catalog = allocated.stage.resolve("catalog.sqlite").toFile().apply { writeText("a".repeat(512)) }
        assertThrows(IOException::class.java) { attempts.allocate(id(2)) }
        val first = intent(1)
        allocated.journal.startSelected(first)
        val recorded = allocated.stage.resolve("merge.intent").toFile().readBytes()
        assertThrows(IOException::class.java) { attempts.allocate(id(2)) }
        assertEquals(listOf(id(1)), File(root, "merge-stages").list()?.toList())
        assertEquals("a".repeat(512), catalog.readText())
        assertArrayEquals(recorded, allocated.stage.resolve("merge.intent").toFile().readBytes())
    }

    @Test fun unknownPrivateEntriesRefuseAdmissionAndStayUntouched() {
        val (root, attempts) = fixture()
        complete(attempts, 1)
        val unknown = File(root, "unexpected").apply { writeText("retain") }
        assertThrows(IOException::class.java) { attempts.allocate(id(2)) }
        assertEquals("retain", unknown.readText())
    }

    @Test fun aCompletedReceiptDoesNotHideUnrelatedRetainedStageBytes() {
        val (root, attempts) = fixture()
        complete(attempts, 1)
        val firstStage = File(root, "merge-stages/${id(1)}")
        val remainder = File(firstStage, ".pristinepage-interrupted.part").apply { writeText("retain") }
        val receipt = File(firstStage, "selected-merge.complete")
        val before = receipt.readBytes()
        assertThrows(IOException::class.java) { attempts.allocate(id(2)) }
        assertEquals(listOf(id(1)), File(root, "merge-stages").list()?.toList())
        assertEquals("retain", remainder.readText())
        assertArrayEquals(before, receipt.readBytes())
    }

    @Test fun theExactHardLinkedJournalTemporaryNameDoesNotBlockAnotherAttempt() {
        val (root, attempts) = fixture()
        complete(attempts, 1)
        val firstStage = File(root, "merge-stages/${id(1)}")
        val temporary = firstStage.resolve(".pristinepage-proved.part")
        val receipt = firstStage.resolve("selected-merge.complete")
        Files.createLink(temporary.toPath(), receipt.toPath())
        assertEquals(intent(2), complete(attempts, 2))
        assertTrue(Files.isSameFile(temporary.toPath(), receipt.toPath()))
    }

    @Test fun lostAuthorityDuringPriorReceiptSyncDoesNotConsumeAFreshSlot() {
        var revokeOnSync = false
        var authorized = true
        val (root, attempts) = fixture(DirectorySync { directory ->
            if (revokeOnSync && directory.name == id(1)) authorized = false
            true
        })
        complete(attempts, 1)
        revokeOnSync = true
        fun requireAuthority() {
            if (!authorized) throw IOException("No current append authority")
        }
        assertThrows(IOException::class.java) { attempts.allocate(id(2), requireAuthority = ::requireAuthority) }
        assertFalse(authorized)
        assertEquals(listOf(id(1)), File(root, "merge-stages").list()?.toList())
        revokeOnSync = false
        authorized = true
        assertEquals(id(2), attempts.allocate(id(2), requireAuthority = ::requireAuthority).stage.fileName.toString())
    }

    @Test fun completedAttemptsStayBoundedUntilAnExplicitRetentionPolicyExists() {
        val (root, attempts) = fixture()
        for (n in 1..4) complete(attempts, n)
        assertThrows(IOException::class.java) { attempts.allocate(id(5)) }
        assertEquals((1..4).map(::id), File(root, "merge-stages").list()?.sorted())
        assertEquals(intent(4), firstReceipt(root, 4))
    }

    private fun fixture(sync: DirectorySync = DirectorySync { true }): Pair<File, LocalSelectedMergeAttempts> {
        val root = temporary.newFolder().canonicalFile.resolve("selected")
        return root to LocalSelectedMergeAttempts(root.toPath()) { journal(it, sync) }
    }

    private fun journal(root: Path, sync: DirectorySync = DirectorySync { true }) =
        LocalLibraryMergeJournal(root.toFile(), root.parent, sync, nodeIdentity = { _, attrs -> attrs.fileKey() ?: attrs.creationTime() })

    private fun complete(attempts: LocalSelectedMergeAttempts, n: Int): LibraryMergeIntent {
        val allocated = attempts.allocate(id(n))
        allocated.stage.resolve("catalog.sqlite").toFile().writeText("a".repeat(512))
        return intent(n).also { allocated.journal.startSelected(it).recordCompletion(it.candidate) }
    }

    private fun firstReceipt(root: File, n: Int): LibraryMergeIntent? =
        journal(root.toPath()).inspectSelected(id(n))?.takeIf { it.completed }?.intent

    private fun intent(n: Int) =
        LibraryMergeIntent("a".repeat(64), "b".repeat(64), destination, incoming, destination.copy(id = id(n), predecessorId = destination.id))

    private fun id(n: Int) = UUID(0, n.toLong()).toString()
}
