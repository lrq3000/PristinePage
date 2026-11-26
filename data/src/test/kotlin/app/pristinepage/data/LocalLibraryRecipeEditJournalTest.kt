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
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class LocalLibraryRecipeEditJournalTest {
    @get:Rule val roots = TemporaryFolder.builder().assureDeletion().build()
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

    @Test fun coldDiscoveryReadsOnlyTheExactIssuingBindingAndDoesNotCreateCompletion() {
        val (attempt, intent) = fixture()
        val live = journal(attempt).start(intent) {}
        assertEquals(LocalLibraryRecipeEditJournal.Observation(intent, LocalLibraryRecipeEditJournal.Phase.INTENT_ONLY),
            journal(attempt).observeForRecovery(binding(3)))
        assertFalse(attempt.resolve("recipe-edit.complete").exists())
        assertFailsWith<IOException> { journal(attempt).observeForRecovery(binding(4)) }
        assertFailsWith<IOException> {
            journal(attempt).observeForRecovery(binding(3, owner = grant.copy(exactUri = grant.exactUri.replace("%3A", "%3a"))))
        }
        assertFailsWith<IOException> {
            journal(attempt).observeForRecovery(binding(3, predecessor = prior.copy(catalog = ObjectId("d".repeat(64), 512))))
        }
        live.recordAfterProviderClosure(next)
        assertEquals(LocalLibraryRecipeEditJournal.Observation(intent, LocalLibraryRecipeEditJournal.Phase.COMPLETED),
            journal(attempt).observeForRecovery(binding(3)))
    }

    @Test fun coldDiscoveryPreservesUnresolvedAndNoncanonicalJournalBytes() {
        val (attempt, intent) = fixture()
        val record = attempt.resolve("recipe-edit.intent")
        assertFailsWith<IOException> { journal(attempt).observeForRecovery(intent.binding) }
        assertFalse(record.exists())
        journal(attempt).start(intent) {}
        val text = intent.encode().toString(Charsets.US_ASCII)
        for (broken in listOf(text.replaceFirst("\n3\n", "\n03\n"), text + "extra\n", text + " ".repeat(1024))) {
            record.writeText(broken, Charsets.US_ASCII)
            assertFailsWith<IOException> { journal(attempt).observeForRecovery(intent.binding) }
            assertEquals(broken, record.readText(Charsets.US_ASCII))
        }
        assertFalse(attempt.resolve("recipe-edit.complete").exists())
    }

    @Test fun coldDiscoveryRefusesUnconfirmedParentBarrierAndPreservesPrivateEvidence() {
        val (attempt, intent) = fixture()
        journal(attempt).start(intent) {}.recordAfterProviderClosure(next)
        fun inventory() = attempt.listFiles()!!.associate { it.name to it.readBytes().toList() }
        val before = inventory()
        assertFailsWith<IOException> {
            journal(attempt, DirectorySync { it != attempt.parentFile }).observeForRecovery(intent.binding)
        }
        assertEquals(before, inventory())
    }

    @Test fun liveReceiptCannotAuthorizeAdoptionAfterByteIdenticalCompletionReplacement() {
        val (attempt, intent) = fixture()
        val writer = LocalLibraryRecipeEditJournal(attempt, sync, nodeIdentity = { _, attrs ->
            // This fixture gives the substitute an explicit synthetic node identity on Windows, where fileKey can be null.
            attrs.fileKey() ?: (attrs.creationTime() to attrs.lastModifiedTime().takeIf { attrs.isRegularFile })
        })
        val receipt = writer.start(intent) {}.recordAfterProviderClosure(next)
        receipt.requireCurrent()

        val leaf = attempt.resolve("recipe-edit.complete").toPath()
        val replacement = Files.createTempFile(checkNotNull(attempt.parentFile).toPath(), "replacement-", ".record")
        Files.write(replacement, intent.completion())
        Files.setLastModifiedTime(replacement, FileTime.fromMillis(1_600_000_000_000))
        Files.move(replacement, leaf, REPLACE_EXISTING)

        assertEquals(LocalLibraryRecipeEditJournal.Phase.COMPLETED, writer.inspect(intent))
        assertFailsWith<IOException> { receipt.requireCurrent() }
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

    @Test fun onlyExactCompletedAdoptionSettlesWithoutRemovingItsCatalogOrReceipt() {
        val (attempt, intent) = fixture()
        val writer = journal(attempt)
        val live = writer.start(intent) {}
        val adopted = binding(4, predecessor = next)
        assertFailsWith<IOException> { writer.acknowledgeAdopted(intent, adopted) {} }
        live.recordAfterProviderClosure(next)
        for (wrong in listOf(binding(3), binding(5, predecessor = next),
            binding(4, owner = grant.copy(exactUri = grant.exactUri.replace("Scans", "Other")), predecessor = next))) {
            assertFailsWith<IOException> { writer.acknowledgeAdopted(intent, wrong) {} }
        }
        val before = attempt.listFiles()!!.associate { it.name to it.readBytes().toList() }
        writer.acknowledgeAdopted(intent, adopted) {}
        assertEquals(LocalLibraryRecipeEditJournal.Phase.ACKNOWLEDGED, journal(attempt).inspect(intent))
        journal(attempt).acknowledgeAdopted(intent, adopted) {}
        val after = attempt.listFiles()!!.associate { it.name to it.readBytes().toList() }
        assertEquals(before, after - "recipe-edit.adopted")
        assertEquals(before.keys + "recipe-edit.adopted", after.keys)
    }

    @Test fun settlementRefusesLostBindingAndUnacknowledgedBarrier() {
        val (attempt, intent) = fixture()
        journal(attempt).start(intent) {}.recordAfterProviderClosure(next)
        val adopted = binding(4, predecessor = next)
        assertFailsWith<IOException> {
            journal(attempt).acknowledgeAdopted(intent, adopted) { throw IOException("The advanced pointer changed") }
        }
        val leaf = attempt.resolve("recipe-edit.adopted")
        assertFalse(leaf.exists())
        val failed = journal(attempt, DirectorySync { !leaf.exists() })
        assertFailsWith<IOException> { failed.acknowledgeAdopted(intent, adopted) {} }
        assertFailsWith<IOException> { failed.inspect(intent) }
        assertEquals(staged.toList(), attempt.resolve("catalog.sqlite").readBytes().toList())
    }

    @Test fun definiteAdoptionRefusalAfterStagingDoesNotObscureTheCompletedEdit() {
        val (attempt, intent) = fixture()
        val writer = journal(attempt)
        writer.start(intent) {}.recordAfterProviderClosure(next)
        val names = attempt.list()!!.toSet()
        assertFailsWith<IOException> {
            writer.acknowledgeAdopted(intent, binding(4, predecessor = next)) {
                if (attempt.list()!!.toSet() != names) throw IOException("Selection changed before linking")
            }
        }
        assertEquals(names, attempt.list()!!.toSet())
        assertEquals(LocalLibraryRecipeEditJournal.Phase.COMPLETED, writer.inspect(intent))
    }

    @Test fun aByteIdenticalSubstituteAfterLinkCannotSatisfyTheLiveAcknowledgment() {
        val (attempt, intent) = fixture()
        val leaf = attempt.resolve("recipe-edit.adopted").toPath()
        var replaced = false
        val writer = LocalLibraryRecipeEditJournal(attempt, sync, checkpoint = {
            if (!replaced && Files.exists(leaf) && attempt.list().orEmpty().none { it.endsWith(".part") }) {
                val substitute = Files.createTempFile(checkNotNull(attempt.parentFile).toPath(), "adopted-", ".record")
                Files.write(substitute, Files.readAllBytes(leaf))
                Files.setLastModifiedTime(substitute, FileTime.fromMillis(1_600_000_000_000))
                Files.move(substitute, leaf, REPLACE_EXISTING)
                replaced = true
            }
        }, nodeIdentity = { _, attrs -> attrs.fileKey() ?: (attrs.creationTime() to attrs.lastModifiedTime().takeIf { attrs.isRegularFile }) })
        writer.start(intent) {}.recordAfterProviderClosure(next)
        assertFailsWith<IOException> { writer.acknowledgeAdopted(intent, binding(4, predecessor = next)) {} }
        assertEquals(true, replaced)
    }

    private fun binding(number: Long, owner: LibraryFolderGrantRecord = grant,
        predecessor: LibraryGeneration = prior): LocalSelectedLibraryAuthority.Selected.Folder {
        val selection = Selection(Location.Folder(owner.grantId, predecessor.id), Identity(library, number))
        return LocalSelectedLibraryAuthority.Selected.Folder(
            LocalSelectedLibraryAuthority.boundSelection(selection, owner, predecessor), owner, predecessor,
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
