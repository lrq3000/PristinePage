package app.pristinepage.data

import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.library.ActiveLibraryFence.Identity
import app.pristinepage.data.library.ActiveLibraryFence.Location
import app.pristinepage.data.library.ActiveLibraryFence.Selection
import app.pristinepage.data.library.LibraryFolderGrantRecord
import app.pristinepage.data.library.LibraryGeneration
import app.pristinepage.data.library.LibraryObjectTransfer.ObjectId
import app.pristinepage.data.library.LocalLibraryRecipeEditJournal
import app.pristinepage.data.library.LocalSelectedLibraryAuthority
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class LocalLibraryRecipeEditJournalTest {
    @get:Rule val roots = TemporaryFolder()
    private val sync = DirectorySync { true }
    private val library = "1c45277c-3f1d-42a2-9ad2-c3996a22ba7a"
    private val prior = LibraryGeneration(library, "25c49d9b-02d8-418f-b982-154980d89cfb", null, 8, ObjectId("a".repeat(64), 512))
    private val grant = LibraryFolderGrantRecord("34adcfe8-fc9e-46d0-bc74-da5355276c24",
        "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FScans", 3)
    private val staged = ByteArray(512) { (it % 7).toByte() }
    private val digest = MessageDigest.getInstance("SHA-256").digest(staged).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val next = LibraryGeneration(library, "e6cbf4e1-0560-4e04-82eb-1d77fd44d961", prior.id, 8, ObjectId(digest, 512))

    @Test fun onlyTheLiveWriterCanCompleteAndColdInspectionDoesNotRenewIt() {
        val (attempt, intent) = fixture()
        val writer = journal(attempt)
        val live = writer.start(intent) {}
        val cold = journal(attempt)
        assertEquals(LocalLibraryRecipeEditJournal.Phase.INTENT_ONLY, cold.inspect(intent))
        assertFailsWith<IOException> { cold.start(intent) {} }
        val receipt = live.recordAfterProviderClosure(next)
        assertEquals(intent, receipt.intent)
        assertEquals(LocalLibraryRecipeEditJournal.Phase.COMPLETED, cold.inspect(intent))
        assertFailsWith<IOException> { live.recordAfterProviderClosure(next) }
        assertFailsWith<IOException> { cold.inspect(intent.copy(rootSha256 = "b".repeat(64))) }
        assertFailsWith<IOException> { cold.inspect(intent.copy(binding = binding(4))) }
    }

    @Test fun revokedOwnerCannotMintCompletionAndItsAttemptCannotBeReplayed() {
        val (attempt, intent) = fixture()
        val writer = journal(attempt)
        var allowed = true
        val live = writer.start(intent) { if (!allowed) throw IOException("Selection changed") }
        allowed = false
        assertFailsWith<IOException> { live.recordAfterProviderClosure(next) }
        assertEquals(LocalLibraryRecipeEditJournal.Phase.INTENT_ONLY, writer.inspect(intent))
        allowed = true
        assertFailsWith<IOException> { live.recordAfterProviderClosure(next) }
        assertFailsWith<IOException> { writer.start(intent) {} }
    }

    @Test fun replacedStageAndWrongGenerationCannotMintCompletion() {
        val (attempt, intent) = fixture()
        val writer = journal(attempt)
        val live = writer.start(intent) {}
        attempt.resolve("catalog.sqlite").writeBytes(ByteArray(512))
        assertFailsWith<IOException> { live.recordAfterProviderClosure(next) }
        assertFalse(attempt.resolve("recipe-edit.complete").exists())
        assertFailsWith<IOException> { writer.inspect(intent) }

        val (other, same) = fixture()
        val otherLive = journal(other).start(same) {}
        assertFailsWith<IOException> { otherLive.recordAfterProviderClosure(prior) }
        assertFalse(other.resolve("recipe-edit.complete").exists())
    }

    @Test fun completionBarrierFailureDoesNotDeliverAReceiptEvenIfTheRecordIsVisible() {
        val (attempt, intent) = fixture()
        val completion = attempt.resolve("recipe-edit.complete")
        val writer = journal(attempt, DirectorySync { !completion.exists() })
        val live = writer.start(intent) {}
        assertFailsWith<IOException> { live.recordAfterProviderClosure(next) }
        assertFailsWith<IOException> { writer.inspect(intent) }
        val cold = journal(attempt)
        assertEquals(LocalLibraryRecipeEditJournal.Phase.COMPLETED, cold.inspect(intent))
        assertFailsWith<IOException> { cold.start(intent) {} }
    }

    @Test fun forceOfOneRecordCannotAcknowledgeAByteIdenticalReplacement() {
        val (attempt, intent) = fixture()
        val leaf = attempt.resolve("recipe-edit.complete").toPath()
        var replaced = false
        val writer = journal(attempt, DirectorySync {
            if (!replaced && Files.exists(leaf)) {
                replaced = true
                val substitute = Files.createTempFile(attempt.parentFile.toPath(), "substitute-", ".record")
                Files.write(substitute, Files.readAllBytes(leaf))
                Files.move(substitute, leaf, REPLACE_EXISTING)
            }
            true
        })
        val live = writer.start(intent) {}
        assertFailsWith<IOException> { live.recordAfterProviderClosure(next) }
        assertEquals(true, replaced)
        assertFailsWith<IOException> { journal(attempt).inspect(intent) }
    }

    @Test fun anUnlinkedPrivateSpillIsNeitherCleanPendingNorAuthorityToComplete() {
        val (attempt, intent) = fixture()
        val writer = journal(attempt)
        val live = writer.start(intent) {}
        val spill = attempt.resolve(".pristinepage-uncertain.part")
        spill.writeBytes(intent.completion())
        assertFailsWith<IOException> { writer.inspect(intent) }
        assertFailsWith<IOException> { live.recordAfterProviderClosure(next) }
        assertEquals(intent.completion().toList(), spill.readBytes().toList())
        assertFalse(attempt.resolve("recipe-edit.complete").exists())
    }

    private fun binding(number: Long): LocalSelectedLibraryAuthority.Selected.Folder {
        val selection = Selection(Location.Folder(grant.grantId, prior.id), Identity(library, number))
        return LocalSelectedLibraryAuthority.Selected.Folder(
            LocalSelectedLibraryAuthority.boundSelection(selection, grant, prior), grant, prior,
        )
    }

    private fun journal(attempt: File, barrier: DirectorySync = sync) =
        LocalLibraryRecipeEditJournal(attempt, barrier, nodeIdentity = { _, attrs -> attrs.fileKey() ?: attrs.creationTime() })

    private fun fixture(): Pair<File, LocalLibraryRecipeEditJournal.Intent> {
        val attempt = roots.newFolder().canonicalFile.resolve(next.id).apply { check(mkdir()) }
        attempt.resolve("catalog.sqlite").writeBytes(staged)
        return attempt to LocalLibraryRecipeEditJournal.Intent(binding(3), "c".repeat(64), next)
    }
}
