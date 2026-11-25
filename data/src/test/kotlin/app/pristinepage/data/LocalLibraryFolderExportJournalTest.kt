package app.pristinepage.data

import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.library.LocalLibraryBackupAttempts
import app.pristinepage.data.library.LibraryActivation
import app.pristinepage.data.library.LibraryFolderExportRecords
import app.pristinepage.data.library.LibraryFolderGrantRecord
import app.pristinepage.data.library.LibraryGeneration
import app.pristinepage.data.library.LibraryObjectTransfer.ObjectId
import app.pristinepage.data.library.LocalLibraryFolderExportJournal
import app.pristinepage.data.library.LocalLibraryFolderExportJournal.Phase
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class LocalLibraryFolderExportJournalTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun leftoverVerificationCatalogKeepsItsActiveSlotAndAllOfItsBytes() {
        val (root, intent) = staged()
        val journal = LocalLibraryFolderExportJournal(root, DirectorySync { true })
        journal.start(intent).recordCompletion()
        journal.acknowledge(intent)
        val extra = root.resolve("library-catalog-interrupted.sqlite").apply { writeBytes(ByteArray(512) { 53 }) }
        val attempts = LocalLibraryBackupAttempts(checkNotNull(root.parentFile).toPath(), DirectorySync { true })
        assertFailsWith<IOException> { attempts.archiveAcknowledged(intent.operationId, intent.issuedAt) }
        assertEquals(root.toPath(), attempts.find(intent.operationId))
        assertContentEquals(ByteArray(512) { 53 }, extra.readBytes())
        assertTrue(root.resolve("catalog.sqlite").isFile)
        assertEquals(Phase.ACKNOWLEDGED, journal.reopen(intent.operationId)?.phase)
    }

    @Test fun theArchiveCapRefusesANewOperationWithoutLosingTheExistingAcknowledgment() {
        val (root, intent) = staged()
        val files = checkNotNull(root.parentFile).toPath()
        val journal = LocalLibraryFolderExportJournal(root, DirectorySync { true })
        journal.start(intent).recordCompletion()
        journal.acknowledge(intent)
        val archive = files.resolve("library-backup-acknowledged").toFile().apply { assertTrue(mkdir()) }
        repeat(127) { index -> assertTrue(archive.resolve("library-out-${java.util.UUID(0L, index.toLong())}").mkdir()) }
        val attempts = LocalLibraryBackupAttempts(files, DirectorySync { true })
        assertTrue(attempts.archiveAcknowledged(intent.operationId, intent.issuedAt))
        val retained = assertNotNull(attempts.find(intent.operationId))
        assertEquals(Phase.ACKNOWLEDGED, LocalLibraryFolderExportJournal(retained.toFile(), DirectorySync { true }).reopen(intent.operationId)?.phase)
        assertFailsWith<IOException> { attempts.requireNew("10000000-0000-4000-8000-000000000006") }
    }

    @Test fun twoNamesForTheSameAttemptOrARegularFileCannotBeReadAsPositiveAbsence() {
        val (root, intent) = staged()
        val files = checkNotNull(root.parentFile).toPath()
        val archive = files.resolve("library-backup-acknowledged").toFile().apply { assertTrue(mkdir()) }
        val wrong = archive.resolve(root.name).apply { writeText("not a directory") }
        val attempts = LocalLibraryBackupAttempts(files, DirectorySync { true })
        assertFailsWith<IOException> { attempts.requireNew(intent.operationId) }
        assertEquals("not a directory", wrong.readText())
        Files.move(wrong.toPath(), archive.resolve("preserved").toPath())
        assertTrue(wrong.mkdir())
        assertFailsWith<IOException> { attempts.find(intent.operationId) }
        assertTrue(root.isDirectory)
        assertTrue(wrong.isDirectory)
    }

    @Test fun mirroredReceiptDoesNotAuthorizeARepeatAndAnArchivedAttemptDoesNotUseAnUnresolvedSlot() {
        val (root, intent) = staged()
        val files = checkNotNull(root.parentFile).toPath()
        val catalog = root.resolve("catalog.sqlite")
        val journal = LocalLibraryFolderExportJournal(root, DirectorySync { true })
        val attempts = LocalLibraryBackupAttempts(files, DirectorySync { true })
        journal.start(intent).recordCompletion()
        assertFalse(attempts.archiveAcknowledged(intent.operationId, intent.issuedAt))
        assertTrue(catalog.isFile)
        journal.acknowledge(intent)
        assertTrue(attempts.archiveAcknowledged(intent.operationId, intent.issuedAt))
        assertFalse(root.exists())
        val archived = checkNotNull(attempts.find(intent.operationId))
        assertFalse(archived.toFile().resolve("catalog.sqlite").exists())
        assertEquals(Phase.ACKNOWLEDGED, LocalLibraryFolderExportJournal(archived.toFile(), DirectorySync { true }).reopen(intent.operationId)?.phase)
        assertFailsWith<IOException> { attempts.requireNew(intent.operationId) }
        for (number in 7..9) files.resolve("library-out-10000000-0000-4000-8000-00000000000$number").toFile().mkdir()
        assertEquals(files.resolve("library-out-10000000-0000-4000-8000-000000000006"), attempts.requireNew("10000000-0000-4000-8000-000000000006"))
        files.resolve("library-out-10000000-0000-4000-8000-000000000005").toFile().mkdir()
        assertFailsWith<IOException> { attempts.requireNew("10000000-0000-4000-8000-000000000006") }
    }

    @Test fun uncertainArchiveMoveStillBlocksReplayUntilBothParentsCanBeReverified() {
        val (root, intent) = staged()
        val files = checkNotNull(root.parentFile).toPath()
        val journal = LocalLibraryFolderExportJournal(root, DirectorySync { true })
        journal.start(intent).recordCompletion()
        journal.acknowledge(intent)
        val barrier = DirectorySync { dir -> dir.toPath() != files || root.exists() }
        val interrupted = LocalLibraryBackupAttempts(files, barrier)
        assertFailsWith<IOException> { interrupted.archiveAcknowledged(intent.operationId, intent.issuedAt) }
        assertFalse(root.exists())
        assertFailsWith<IOException> { interrupted.requireNew(intent.operationId) }
        val recovered = LocalLibraryBackupAttempts(files, DirectorySync { true })
        assertNotNull(recovered.find(intent.operationId))
        assertTrue(recovered.archiveAcknowledged(intent.operationId, intent.issuedAt))
        assertFailsWith<IOException> { recovered.requireNew(intent.operationId) }
    }

    @Test fun onlyAnAcknowledgedOperationCanDropItsExactPrivateCatalogWithoutBecomingReusable() {
        val (root, intent) = staged()
        val journal = LocalLibraryFolderExportJournal(root, DirectorySync { true })
        val live = journal.start(intent)
        assertFalse(journal.reclaimAcknowledgedCatalog(intent.operationId))
        live.recordCompletion()
        assertFalse(journal.reclaimAcknowledgedCatalog(intent.operationId))
        journal.acknowledge(intent)
        val spend = File(root, "${"c".repeat(64)}.spent").apply { writeText("retained spend evidence") }
        val retained = listOf("export.intent", "export.complete", "export.ack", spend.name).associateWith { File(root, it).readBytes() }
        val unrelated = File(root, "reserved-evidence").apply { writeText("preserve") }
        assertTrue(journal.reclaimAcknowledgedCatalog(intent.operationId))
        assertFalse(File(root, "catalog.sqlite").exists())
        assertFalse(File(root, "export.catalog.owner").exists())
        assertEquals("preserve", unrelated.readText())
        for ((name, bytes) in retained) assertContentEquals(bytes, File(root, name).readBytes())
        assertEquals(Phase.ACKNOWLEDGED, journal.reopen(intent.operationId)?.phase)
        assertTrue(journal.reclaimAcknowledgedCatalog(intent.operationId))
        assertFailsWith<IOException> { journal.start(intent) }
    }

    @Test fun aCatalogWhoseBytesChangedIsNotOwnedForReclamationEvenAfterAcknowledgment() {
        val (root, intent) = staged()
        val journal = LocalLibraryFolderExportJournal(root, DirectorySync { true })
        journal.start(intent).recordCompletion()
        journal.acknowledge(intent)
        val candidate = File(root, "catalog.sqlite")
        val replacement = ByteArray(512) { 99 }
        candidate.writeBytes(replacement)
        assertFailsWith<IOException> { journal.reclaimAcknowledgedCatalog(intent.operationId) }
        assertContentEquals(replacement, candidate.readBytes())
        assertEquals(Phase.ACKNOWLEDGED, journal.reopen(intent.operationId)?.phase)
    }

    @Test fun byteIdenticalReplacementCannotBorrowTheOriginalCatalogsCleanupAuthority() {
        val (root, intent) = staged()
        val catalog = root.resolve("catalog.sqlite").toPath()
        val original = root.resolve("displaced-catalog").toPath()
        val journal = LocalLibraryFolderExportJournal(root, DirectorySync { true })
        journal.start(intent).recordCompletion()
        journal.acknowledge(intent)
        Files.move(catalog, original)
        Files.copy(original, catalog)
        assertFalse(Files.isSameFile(catalog, original))
        assertFailsWith<IOException> { LocalLibraryFolderExportJournal(root, DirectorySync { true }).reclaimAcknowledgedCatalog(intent.operationId) }
        assertContentEquals(Files.readAllBytes(original), Files.readAllBytes(catalog))
        assertEquals(Phase.ACKNOWLEDGED, journal.reopen(intent.operationId)?.phase)
    }

    @Test fun unexpectedJournalEvidenceBlocksReclamationWithoutDeletingEitherFile() {
        val (root, intent) = staged()
        val journal = LocalLibraryFolderExportJournal(root, DirectorySync { true })
        journal.start(intent).recordCompletion()
        journal.acknowledge(intent)
        val artifact = root.resolve(".pristinepage-unresolved.part").apply { writeText("unresolved") }
        assertFailsWith<IOException> { journal.reclaimAcknowledgedCatalog(intent.operationId) }
        assertEquals("unresolved", artifact.readText())
        assertTrue(root.resolve("catalog.sqlite").isFile)
    }

    @Test fun anInterruptedUnlinkOrLegacyReceiptWithOnlyOneCatalogNameIsNotCleanupAuthority() {
        val (root, intent) = staged()
        val journal = LocalLibraryFolderExportJournal(root, DirectorySync { true })
        journal.start(intent).recordCompletion()
        journal.acknowledge(intent)
        val original = root.resolve("catalog.sqlite")
        val bytes = original.readBytes()
        Files.delete(root.resolve("export.catalog.owner").toPath())
        assertFailsWith<IOException> { LocalLibraryFolderExportJournal(root, DirectorySync { true }).reclaimAcknowledgedCatalog(intent.operationId) }
        assertContentEquals(bytes, original.readBytes())
        assertEquals(Phase.ACKNOWLEDGED, journal.reopen(intent.operationId)?.phase)
        assertFailsWith<IOException> { journal.start(intent) }
    }

    @Test fun lossOfThePostReclamationBarrierKeepsTheSpentOperationAndCanReconcile() {
        val (root, intent) = staged()
        var permitSync = true
        val candidate = File(root, "catalog.sqlite")
        val journal = LocalLibraryFolderExportJournal(root, DirectorySync { permitSync || candidate.exists() })
        journal.start(intent).recordCompletion()
        journal.acknowledge(intent)
        permitSync = false
        assertFailsWith<IOException> { journal.reclaimAcknowledgedCatalog(intent.operationId) }
        assertFalse(candidate.exists())
        assertFailsWith<IOException> { journal.reopen(intent.operationId) }
        permitSync = true
        assertTrue(journal.reclaimAcknowledgedCatalog(intent.operationId))
        assertEquals(Phase.ACKNOWLEDGED, journal.reopen(intent.operationId)?.phase)
        assertFailsWith<IOException> { journal.start(intent) }
    }

    @Test fun lostDeliveryCanReopenAndAcknowledgeTheSamePrivateReceiptWithoutAnotherWriteIntent() {
        val (root, intent) = staged()
        val readback = { LocalLibraryFolderExportJournal(root, DirectorySync { true }) }
        assertNull(readback().reopen(intent.operationId))
        val writer = readback().start(intent)
        assertEquals(Phase.INTENT_ONLY, readback().reopen(intent.operationId)?.phase)
        assertFailsWith<IOException> { readback().acknowledge(intent) }
        assertFailsWith<IOException> { readback().start(intent) }

        writer.recordCompletion()
        assertEquals(Phase.COMPLETED, readback().reopen(intent.operationId)?.phase)
        assertFailsWith<IOException> { writer.recordCompletion() }
        val complete = File(root, "export.complete").readBytes()
        assertFailsWith<IOException> { readback().acknowledge(intent.copy(issuedAt = intent.issuedAt.copy(number = 2))) }
        assertFalse(File(root, "export.ack").exists())
        readback().acknowledge(intent)
        readback().acknowledge(intent)
        assertEquals(Phase.ACKNOWLEDGED, readback().reopen(intent.operationId)?.phase)
        assertContentEquals(complete, File(root, "export.complete").readBytes())
        assertFailsWith<IOException> { readback().start(intent) }
    }

    @Test fun failedDirectoryAcknowledgmentRequiresPositiveSyncBeforeAnObservedReceiptCanBeReported() {
        val (root, intent) = staged()
        var permitSync = true
        val journal = LocalLibraryFolderExportJournal(root, DirectorySync {
            permitSync || !File(root, "export.complete").isFile
        })
        val writer = journal.start(intent)
        permitSync = false
        assertFailsWith<IOException> { writer.recordCompletion() }
        val observed = File(root, "export.complete").readBytes()
        assertFailsWith<IOException> { journal.reopen(intent.operationId) }
        assertFailsWith<IOException> { writer.recordCompletion() }
        assertContentEquals(observed, File(root, "export.complete").readBytes())
        permitSync = true
        assertEquals(Phase.COMPLETED, journal.reopen(intent.operationId)?.phase)
    }

    @Test fun cancellationWhileForcingAnAcknowledgmentStageDoesNotHideTheCompletedBackup() {
        val (root, intent) = staged()
        LocalLibraryFolderExportJournal(root, DirectorySync { true }).start(intent).recordCompletion()
        val job = Job()
        var publishing = false
        val journal = LocalLibraryFolderExportJournal(root, DirectorySync {
            if (root.resolve("export.ack").isFile) publishing = true
            true
        }) {
            if (publishing || root.list().orEmpty().any { it.endsWith(".part") }) job.cancel()
            job.ensureActive()
        }

        assertFailsWith<CancellationException> { journal.acknowledge(intent) }
        val reopened = LocalLibraryFolderExportJournal(root, DirectorySync { true })
        assertEquals(Phase.ACKNOWLEDGED, reopened.reopen(intent.operationId)?.phase)
        assertContentEquals(intent.completion(), root.resolve("export.complete").readBytes())
    }

    @Test fun corruptOrOutOfOrderReceiptNeverLooksLikeAnUnspentOperation() {
        val (root, intent) = staged()
        val journal = LocalLibraryFolderExportJournal(root, DirectorySync { true })
        File(root, "export.ack").writeBytes(intent.acknowledgment())
        assertFailsWith<IOException> { journal.reopen(intent.operationId) }
        assertFailsWith<IOException> { journal.start(intent) }
        assertFalse(File(root, "export.intent").exists())

        val (different, stable) = staged()
        val next = LocalLibraryFolderExportJournal(different, DirectorySync { true })
        next.start(stable)
        File(different, "export.complete").writeBytes(stable.completion().copyOf(12))
        assertFailsWith<IOException> { next.reopen(stable.operationId) }
        assertFailsWith<IOException> { next.acknowledge(stable) }
        assertFalse(File(different, "export.ack").exists())
    }

    @Test fun aWrongClosedCatalogDoesNotSpendAPrivateIntent() {
        val (root, intent) = staged()
        val candidate = File(root, "catalog.sqlite")
        candidate.writeBytes(ByteArray(512) { 81 })
        val journal = LocalLibraryFolderExportJournal(root, DirectorySync { true })
        assertFailsWith<IOException> { journal.start(intent) }
        assertFalse(File(root, "export.intent").exists())
        assertContentEquals(ByteArray(512) { 81 }, candidate.readBytes())
    }

    @Test fun leftoverPrivatePublicationEvidencePreventsStartingTheSameOperationAgain() {
        val (root, intent) = staged()
        val scratch = File(root, ".pristinepage-interrupted.part")
        scratch.writeBytes(byteArrayOf(28, 9))
        val journal = LocalLibraryFolderExportJournal(root, DirectorySync { true })
        assertFailsWith<IOException> { journal.reopen(intent.operationId) }
        assertFailsWith<IOException> { journal.start(intent) }
        assertFalse(File(root, "export.intent").exists())
        assertContentEquals(byteArrayOf(28, 9), scratch.readBytes())
    }

    @Test fun readbackMustRefuseACatalogChangedDuringItsPrepublicationBarrier() {
        val (root, intent) = staged()
        val candidate = File(root, "catalog.sqlite")
        val altered = ByteArray(512) { 16 }
        val journal = LocalLibraryFolderExportJournal(root, DirectorySync {
            candidate.writeBytes(altered)
            true
        })
        assertFailsWith<IOException> { journal.start(intent) }
        assertFalse(File(root, "export.intent").exists())
        assertContentEquals(altered, candidate.readBytes())
    }

    @Test fun anUnlinkedTerminalStageIsNotAnOrdinaryCompletedReceipt() {
        val (root, intent) = staged()
        val journal = LocalLibraryFolderExportJournal(root, DirectorySync { true })
        val writer = journal.start(intent)
        val stage = File(root, ".pristinepage-interrupted.part")
        stage.writeBytes(intent.completion())
        assertFailsWith<IOException> { journal.reopen(intent.operationId) }
        assertFailsWith<IOException> { writer.recordCompletion() }
        assertFalse(File(root, "export.complete").exists())
        assertContentEquals(intent.completion(), stage.readBytes())
    }

    @Test fun anExactFinalRecordAndItsOwnSurvivingHardLinkCanBeReconciledWithoutRewritingEither() {
        val (root, intent) = staged()
        val journal = LocalLibraryFolderExportJournal(root, DirectorySync { true })
        journal.start(intent).recordCompletion()
        val stage = root.resolve(".pristinepage-interrupted.part").toPath()
        val complete = root.resolve("export.complete").toPath()
        Files.createLink(stage, complete)
        journal.acknowledge(intent)
        assertEquals(Phase.ACKNOWLEDGED, journal.reopen(intent.operationId)?.phase)
        assertEquals(true, Files.isSameFile(stage, complete))
        assertContentEquals(intent.completion(), Files.readAllBytes(complete))
    }

    @Test fun mutationDuringTheIntentWriterBarrierStillRefusesBeforeExternalWorkCanStart() {
        val (root, intent) = staged()
        val candidate = File(root, "catalog.sqlite")
        val changed = ByteArray(512) { 7 }
        var privateBarriers = 0
        val journal = LocalLibraryFolderExportJournal(root, DirectorySync {
            // This is after the early catalog check and while the closed journal stage is being linked/forced.
            if (root.list().orEmpty().any { name -> name.endsWith(".part") }) {
                privateBarriers++
                candidate.writeBytes(changed)
            }
            true
        })
        assertFailsWith<IOException> { journal.start(intent) }
        assertEquals(1, privateBarriers)
        assertContentEquals(changed, candidate.readBytes())
    }

    private fun staged(): Pair<File, LibraryFolderExportRecords.Intent> {
        val id = "10000000-0000-4000-8000-000000000001"
        val root = temporary.newFolder().resolve("library-out-$id").canonicalFile.also { check(it.mkdir()) }
        val catalog = ByteArray(512) { 73 }
        File(root, "catalog.sqlite").writeBytes(catalog)
        val digest = MessageDigest.getInstance("SHA-256").digest(catalog).joinToString("") { "%02x".format(it) }
        val generation = LibraryGeneration(
            "10000000-0000-4000-8000-000000000003", "10000000-0000-4000-8000-000000000004", null, 8, ObjectId(digest, 512),
        )
        return root to LibraryFolderExportRecords.Intent(
            id, LibraryActivation("10000000-0000-4000-8000-000000000002", 1),
            LibraryFolderGrantRecord(id, "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FB", 3),
            1, 42, generation,
        )
    }
}
