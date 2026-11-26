package app.pristinepage.data

import app.pristinepage.data.library.LibraryCatalogPublication
import app.pristinepage.data.library.LibraryGenerationInventory
import app.pristinepage.data.library.LibraryCatalogRevisionPublication
import app.pristinepage.data.library.CatalogRevisionSpendUnconfirmed
import app.pristinepage.data.library.LibraryGeneration
import app.pristinepage.data.library.LibraryMergePublication
import app.pristinepage.data.library.LocalLibraryDirectory
import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.library.LibraryObjectTransfer.ObjectId
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryCatalogPublicationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val predecessor = "01284418-aa30-4cb7-8840-e5d8fa893e26"
    private val generation = LibraryGeneration(
        "16085bae-ae25-4fa4-8c69-9a8982ef6c13", "96b461d1-bc41-4bca-ad7e-53415db3f1a2", predecessor, 8,
        ObjectId("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", 3),
    )

    @Test fun catalogOnlyRevisionKeepsTheExactOriginalAndPredecessorWithoutObjectWrites() {
        val destination = revisionFixture()
        val published = publishRevision(destination) { reopened ->
            assertEquals("abc", reopened.readText())
            assertFalse(destination.folder.marker.exists())
        }
        assertEquals(generation, published)
        assertEquals("prior original", destination.original.readText())
        assertEquals("old catalog", destination.folder.previous.readText())
        assertEquals(generation, destination.folder.marker.inputStream().use(LibraryGeneration::read))
        assertThrows(IOException::class.java) { publishRevision(destination) { fail("An existing generation cannot be replayed") } }
    }

    @Test fun catalogOnlyRevisionRefusesChangedOriginalInventoryOrInsufficientSpaceBeforeStaging() {
        val destination = revisionFixture()
        assertThrows(IOException::class.java) { publishRevision(destination, incoming = emptyList()) { fail("Unchanged originals required") } }
        assertThrows(IOException::class.java) { publishRevision(destination, available = 1024) { fail("Space check must include verification space") } }
        assertThrows(IOException::class.java) { publishRevision(destination, budget = 4) { fail("Space check must cover the exact spend and marker records") } }
        assertFalse(destination.folder.catalog.exists())
        assertFalse(destination.folder.marker.exists())
    }

    @Test fun originalChangedAfterCatalogReadbackCannotCrossTheMarkerBarrier() {
        val destination = revisionFixture()
        assertThrows(IOException::class.java) {
            publishRevision(destination) { destination.original.writeText("unverified replacement") }
        }
        assertFalse(destination.folder.marker.exists())
        assertEquals("old catalog", destination.folder.previous.readText())
        assertTrue(destination.folder.catalog.exists())
        destination.original.writeText("prior original")
        assertThrows(IOException::class.java) { publishRevision(destination) { fail("An unmarked catalog cannot authorize reuse") } }
        assertFalse(destination.folder.marker.exists())
    }

    @Test fun localCatalogLinkRechecksAuthorityAndInterruptedStagingIsNotRetryable() {
        val local = localRevisionFixture()
        var allowed = true
        val writer = local.writer { if (!allowed) throw IOException("Closed stage lost authority") }
        val failure = assertThrows(IOException::class.java) {
            local.publish(writer, openStage = {
                object : ByteArrayInputStream("abc".encodeToByteArray()) {
                    override fun close() { allowed = false }
                }
            })
        }
        assertEquals("Closed stage lost authority", failure.message)
        assertFalse(local.catalog.exists())
        assertTrue(local.catalog.parentFile.list().orEmpty().any { it.endsWith(".part") })
        allowed = true
        val replay = assertThrows(IOException::class.java) {
            local.publish(local.writer {}, openStage = { error("Spent authority cannot read the catalog again") })
        }
        assertTrue(replay.message.orEmpty().contains("spent"))
        assertFalse(local.marker.exists())
    }

    @Test fun localPublicationRefusesRelativeScratchInsideTheDestination() {
        val local = localRevisionFixture()
        val inside = File(local.store.rootPath.toFile(), "overlap").apply { check(mkdir()) }
        val relative = Path.of("").toAbsolutePath().normalize().relativize(inside.toPath())
        val writer = LibraryCatalogRevisionPublication(local.store, relative, local.journal, requireOwner = {})
        assertThrows(IOException::class.java) { local.publish(writer, openStage = { error("Overlapping scratch reached a write") }) }
        assertFalse(local.catalog.exists())
        assertEquals(emptyList<String>(), inside.list().orEmpty().toList())
    }

    @Test fun localMarkerCannotAcknowledgeAReplacementOriginalOrAuthorizeReplay() {
        var afterBarrier: (() -> Unit)? = null
        val local = localRevisionFixture(DirectorySync { afterBarrier?.invoke(); true })
        afterBarrier = { if (local.marker.exists()) local.original.writeText("unverified replacement") }
        assertThrows(IOException::class.java) { local.publish(local.writer {}) }
        assertTrue(local.marker.exists())
        assertEquals("old catalog", local.previous.readText())
        assertThrows(IOException::class.java) { local.publish(local.writer {}, openStage = { error("A visible marker can only be inspected") }) }
    }

    @Test fun publicationOwnerReceivesTheExactSpentSuccessorBeforeOpeningTheDestinationCatalog() {
        val local = localRevisionFixture()
        val delivered = mutableListOf<LibraryGeneration>()
        val writer = LibraryCatalogRevisionPublication(local.store, temporary.newFolder().canonicalFile.toPath(),
            local.journal, requireOwner = {}, onPublicationStarted = { next ->
                assertFalse(local.catalog.exists())
                assertFalse(local.marker.exists())
                assertThrows(IOException::class.java) { local.journal.requireUnspentCatalogRevision(next.id) }
                delivered += next
            })
        assertThrows(IOException::class.java) {
            local.publish(writer, openStage = {
                assertEquals(listOf(generation), delivered)
                throw IOException("Synthetic failure while transferring the closed catalog")
            })
        }
        assertFalse(local.marker.exists())
        assertThrows(IOException::class.java) { local.publish(writer) }
        assertEquals(listOf(generation), delivered)
    }

    @Test fun preflightCapacityRefusalDoesNotReportPublicationStarted() {
        val local = localRevisionFixture()
        val writer = LibraryCatalogRevisionPublication(local.store, temporary.newFolder().canonicalFile.toPath(),
            local.journal, requireOwner = {}, onPublicationStarted = { error("A refused candidate cannot reach publication") })
        assertThrows(IOException::class.java) {
            writer.publish(local.prior, generation, listOf(local.objectId), listOf(local.objectId), 0, 1024,
                { error("Capacity refusal cannot read the staged catalog") }, { error("Capacity refusal cannot read back") })
        }
        local.journal.requireUnspentCatalogRevision(generation.id)
        assertFalse(local.catalog.exists())
        assertFalse(local.marker.exists())
    }

    @Test fun privateSpendBarrierFailureIsDistinctFromCapacityRefusalAndCannotWriteTheDestination() {
        val journalSync = DirectorySync { directory ->
            !directory.resolve("${generation.id}.spent").isFile
        }
        val local = localRevisionFixture(journalSync = journalSync)
        val writer = LibraryCatalogRevisionPublication(local.store, temporary.newFolder().canonicalFile.toPath(),
            local.journal, requireOwner = {}, onPublicationStarted = { error("An uncertain spend does not authorize a destination write") })
        assertThrows(CatalogRevisionSpendUnconfirmed::class.java) {
            local.publish(writer, openStage = { error("The writable destination must not open") })
        }
        assertTrue(local.journal.rootPath.resolve("catalog-revision-spends/${generation.id}.spent").toFile().isFile)
        assertFalse(local.catalog.exists())
        assertFalse(local.marker.exists())
    }

    @Test fun revisionSpendReadbackRequiresBothExactMarkersAndNeverCreatesEvidence() {
        val local = localRevisionFixture()
        val leaf = local.journal.rootPath.resolve("catalog-revision-spends/${generation.id}.spent")
        assertThrows(IOException::class.java) { requireRevisionSpend(local.journal, local.prior, generation) }
        assertFalse(leaf.toFile().exists())
        local.journal.spendCatalogRevision(local.prior, generation) {}
        requireRevisionSpend(local.journal, local.prior, generation)
        val otherPrior = local.prior.copy(catalog = local.prior.catalog.copy(sha256 = "d".repeat(64)))
        assertThrows(IOException::class.java) { requireRevisionSpend(local.journal, otherPrior, generation) }
        val otherChild = generation.copy(catalog = generation.catalog.copy(sha256 = "e".repeat(64)))
        assertThrows(IOException::class.java) { requireRevisionSpend(local.journal, local.prior, otherChild) }
    }

    @Test fun revisionSpendReadbackRefusesWhenItsDirectoryBarrierLosesTheExactRecord() {
        var replace = false
        var record: File? = null
        val barrier = DirectorySync {
            if (replace && it.name == "catalog-revision-spends") {
                checkNotNull(record).appendText("unexpected")
                replace = false
            }
            true
        }
        val local = localRevisionFixture(journalSync = barrier)
        record = local.journal.rootPath.resolve("catalog-revision-spends/${generation.id}.spent").toFile()
        local.journal.spendCatalogRevision(local.prior, generation) {}
        replace = true
        assertThrows(IOException::class.java) { requireRevisionSpend(local.journal, local.prior, generation, barrier) }
        assertFalse(replace)
        assertTrue(record.readText().endsWith("unexpected"))
    }

    @Test fun revisionSpendReadbackRefusesAnIdenticalReplacementDuringTheBarrier() {
        var replacement: Path? = null
        lateinit var leaf: Path
        val barrier = DirectorySync {
            if (it.name == "catalog-revision-spends") replacement?.let { staged ->
                Files.move(staged, leaf, REPLACE_EXISTING)
                replacement = null
            }
            true
        }
        val local = localRevisionFixture(journalSync = barrier)
        leaf = local.journal.rootPath.resolve("catalog-revision-spends/${generation.id}.spent")
        local.journal.spendCatalogRevision(local.prior, generation) {}
        val expected = Files.readAllBytes(leaf)
        replacement = Files.createTempFile(local.journal.rootPath, "replacement-", ".spent").also { Files.write(it, expected) }
        assertThrows(IOException::class.java) {
            requireRevisionSpend(local.journal, local.prior, generation, barrier)
        }
        assertNull(replacement)
        assertArrayEquals(expected, Files.readAllBytes(leaf))
    }

    private fun requireRevisionSpend(journal: LocalLibraryDirectory, prior: LibraryGeneration, next: LibraryGeneration,
        barrier: DirectorySync = DirectorySync { true },
    ) = journal.requireRecordedCatalogRevision(prior, next) { leaf, read ->
        // Windows JVM lacks directory fsync; a test hard link keeps the original leaf's identity observable through the synthetic barrier.
        val anchor = Files.createLink(temporary.newFolder().toPath().resolve("anchor"), leaf)
        fun requireLeaf() {
            if (!Files.isSameFile(anchor, leaf)) throw IOException("The private spend node was replaced")
        }
        fun syncParents() {
            if (!barrier.sync(leaf.parent.toFile()) || !barrier.sync(journal.rootPath.toFile())) throw IOException("The test barrier failed")
        }
        read(::requireLeaf, ::syncParents) { Files.newInputStream(anchor) }
    }

    @Test fun localRevisionRequiresTheExactTipAndExcludesACompetingWriteLane() {
        val local = localRevisionFixture()
        val sibling = generation.copy(id = "19f959f4-1ab6-489f-beed-fbfb09acde8c")
        local.publish(local.writer {}, openStage = {
            val competing = assertThrows(IOException::class.java) {
                local.publish(local.writer {}, next = sibling, openStage = { error("A second writer must not stage bytes") })
            }
            assertTrue(competing.message.orEmpty().contains("write lane"))
            "abc".byteInputStream()
        })
        assertThrows(IOException::class.java) {
            local.publish(local.writer {}, next = sibling, openStage = { error("An old tip cannot publish a sibling") })
        }
        assertFalse(local.store.rootPath.resolve(sibling.catalogPath).toFile().exists())
    }

    @Test fun createOnlyPublicationNeverAdoptsAnExistingUnmarkedCatalog() {
        val target = fixture()
        target.catalog.writeText("abc")
        assertThrows(IOException::class.java) {
            publisher(target).publish(generation, 100, { error("An already occupied leaf cannot be used") }, createOnly = true) {
                fail("An existing catalog cannot reach readback on the fresh path")
            }
        }
        assertEquals("abc", target.catalog.readText())
        assertFalse(target.marker.exists())
    }

    @Test fun reopensNewCatalogBeforePublishingAndKeepsPreviousGeneration() {
        val directory = fixture()
        val published = publish(directory) { reopened ->
            assertFalse(directory.marker.exists())
            assertEquals("abc", reopened.readText())
            assertEquals("old catalog", directory.previous.readText())
        }
        assertEquals(generation, published)
        assertEquals(generation, directory.marker.inputStream().use(LibraryGeneration::read))
        assertEquals("old catalog", directory.previous.readText())
    }

    @Test fun doesNotPublishWhenActualCandidateCannotBeReopened() {
        val directory = fixture()
        assertThrows(IOException::class.java) { publish(directory) { throw IOException("SQLite reopen refused") } }
        assertFalse(directory.marker.exists())
        assertEquals("abc", directory.catalog.readText())
        assertEquals("old catalog", directory.previous.readText())
    }

    @Test fun interruptionAfterThePayloadBarrierLeavesNoCompletionMarker() {
        val directory = fixture()
        val events = mutableListOf<String>()
        val store = object : LibraryCatalogPublication.Store by directory {
            override fun confirmPayloadDurability(generation: LibraryGeneration, requiresObjects: Boolean) {
                assertEquals(listOf("reopened"), events)
                events += "payload durable"
            }
        }
        val failure = assertThrows(IOException::class.java) {
            publisher(store).publish(generation, 100, { "abc".byteInputStream() }, beforeNewMarker = { observed ->
                assertEquals(generation, observed)
                assertEquals(listOf("reopened", "payload durable"), events)
                assertFalse(directory.marker.exists())
                throw IOException("Interrupted before the completion marker")
            }) { reopened ->
                assertEquals("abc", reopened.readText())
                events += "reopened"
            }
        }
        assertEquals("Interrupted before the completion marker", failure.message)
        assertFalse(directory.marker.exists())
        assertEquals("abc", directory.catalog.readText())
        assertEquals("old catalog", directory.previous.readText())
    }

    @Test fun verificationOfAnExistingMarkerDoesNotReachTheNewMarkerBoundary() {
        val directory = fixture()
        publish(directory) {}
        val verified = publisher(directory).publish(generation, 100, { error("Must not recopy") },
            verifyExistingOnly = true, beforeNewMarker = { fail("Historical verification must not announce a new marker") }) {}
        assertEquals(generation, verified)
    }

    @Test fun ordinaryPublicationHasNoObserverOnlyCancellationCheckpoint() {
        val directory = fixture()
        val events = mutableListOf<String>()
        val store = object : LibraryCatalogPublication.Store by directory {
            override fun confirmPayloadDurability(generation: LibraryGeneration, requiresObjects: Boolean) {
                events.clear()
            }

            override fun createMarker(generation: LibraryGeneration, bytes: ByteArray) {
                assertEquals(listOf("checkpoint"), events)
                directory.createMarker(generation, bytes)
            }
        }
        val published = LibraryCatalogPublication(store, temporary.newFolder().toPath()) { events += "checkpoint" }
            .publish(generation, 100, { "abc".byteInputStream() }) {}
        assertEquals(generation, published)
    }

    @Test fun cancellationRequestedByTheObserverPreventsMarkerCreation() {
        val directory = fixture()
        var canceled = false
        val publisher = LibraryCatalogPublication(directory, temporary.newFolder().toPath()) {
            if (canceled) throw java.util.concurrent.CancellationException("Canceled after the observation")
        }
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            publisher.publish(generation, 100, { "abc".byteInputStream() },
                beforeNewMarker = { canceled = true }) {}
        }
        assertFalse(directory.marker.exists())
        assertEquals("abc", directory.catalog.readText())
    }

    @Test fun verifiesDestinationBytesBeforeHandingThemToCatalogReader() {
        val directory = fixture()
        val store = object : LibraryCatalogPublication.Store by directory {
            override fun createCatalog(generation: LibraryGeneration, write: (OutputStream) -> Unit) {
                directory.createCatalog(generation, write)
                directory.catalog.writeText("xyz")
            }
        }
        assertThrows(IOException::class.java) {
            publisher(store).publish(generation, 100, { "abc".byteInputStream() }) { fail("Corrupt candidate reached reopen") }
        }
        assertFalse(directory.marker.exists())
    }

    @Test fun retryAfterMarkerWriteLostItsAcknowledgmentReusesTheCompleteGeneration() {
        val directory = fixture()
        val store = object : LibraryCatalogPublication.Store by directory {
            override fun createMarker(generation: LibraryGeneration, bytes: ByteArray) {
                directory.createMarker(generation, bytes)
                throw IOException("Lost provider acknowledgment")
            }
        }
        assertThrows(IOException::class.java) {
            publisher(store).publish(generation, 100, { "abc".byteInputStream() }) {}
        }
        assertEquals(generation, publisher(directory).publish(generation, 100, { error("Must not recopy") }) {})
        assertEquals("old catalog", directory.previous.readText())
    }

    @Test fun aVisibleMarkerIsNotSuccessfulWhileItsDurabilityBarrierStillFails() {
        val directory = fixture()
        val store = object : LibraryCatalogPublication.Store by directory {
            override fun createMarker(generation: LibraryGeneration, bytes: ByteArray) {
                directory.createMarker(generation, bytes)
                throw IOException("Lost acknowledgement after exposing marker")
            }

            override fun confirmMarkerDurability(generation: LibraryGeneration) {
                throw IOException("Directory durability could not be confirmed")
            }
        }
        val publisher = publisher(store)
        assertThrows(IOException::class.java) { publisher.publish(generation, 100, { "abc".byteInputStream() }) {} }
        assertTrue(directory.marker.exists())
        assertThrows(IOException::class.java) { publisher.publish(generation, 100, { error("Must not recopy") }) {} }
        assertEquals("old catalog", directory.previous.readText())
    }

    @Test fun secondOpenCannotSubstituteTheCatalogAfterByteProof() {
        val directory = fixture()
        directory.catalog.writeText("abc")
        var opens = 0
        val store = object : LibraryCatalogPublication.Store by directory {
            override fun openCatalog(generation: LibraryGeneration): InputStream? =
                if (++opens == 1) directory.openCatalog(generation) else "xyz".byteInputStream()
        }
        assertThrows(IOException::class.java) {
            publisher(store).publish(generation, 100, { error("Must not recopy") }) { reopened ->
                assertEquals("abc", reopened.readText())
            }
        }
        assertFalse(directory.marker.exists())
    }

    @Test fun sizeAdmissionPrecedesCatalogOrDestinationAccess() {
        val directory = fixture()
        val store = object : LibraryCatalogPublication.Store by directory {
            override fun openCatalog(generation: LibraryGeneration): InputStream? = error("Must not access catalog")
        }
        assertThrows(IOException::class.java) {
            publisher(store).publish(generation, 2, { error("Must not access source") }) { fail("Must not reopen") }
        }
        assertFalse(directory.catalog.exists())
        assertFalse(directory.marker.exists())
    }

    private fun fixture() = Folder(temporary.newFolder(), generation, predecessor)
    private fun id(file: File) = ObjectId(MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }, file.length())
    private fun revisionFixture(): RevisionFolder {
        val folder = fixture()
        val original = File(temporary.newFolder(), "original").apply { writeText("prior original") }
        val prior = generation.copy(id = predecessor, predecessorId = null, catalog = id(folder.previous))
        return RevisionFolder(folder, original, prior, id(original), temporary.newFolder().canonicalFile)
    }
    private fun publishRevision(destination: RevisionFolder, incoming: List<ObjectId> = listOf(destination.objectId),
        available: Long = 4096, budget: Long = 1024, verify: (File) -> Unit,
    ): LibraryGeneration = LibraryCatalogRevisionPublication(destination, temporary.newFolder().toPath(),
        LocalLibraryDirectory(destination.journal, DirectorySync { true }), requireOwner = {})
        .publish(destination.prior, generation, listOf(destination.objectId), incoming, available, budget, { "abc".byteInputStream() }, verify)

    private inner class LocalRevision(val store: LocalLibraryDirectory, val journal: LocalLibraryDirectory,
        val prior: LibraryGeneration, val objectId: ObjectId,
    ) {
        val original = store.rootPath.resolve("objects/${objectId.sha256}").toFile()
        val catalog = store.rootPath.resolve(generation.catalogPath).toFile()
        val marker = store.rootPath.resolve("generations/${generation.id}.complete").toFile()
        val previous = store.rootPath.resolve(prior.catalogPath).toFile()
        fun writer(requireOwner: () -> Unit) =
            LibraryCatalogRevisionPublication(store, temporary.newFolder().canonicalFile.toPath(), journal, requireOwner)
        fun publish(writer: LibraryCatalogRevisionPublication, next: LibraryGeneration = generation,
            openStage: () -> InputStream = { "abc".byteInputStream() },
        ) = writer.publish(prior, next, listOf(objectId), listOf(objectId), 4096, 1024, openStage) {
            assertEquals("abc", it.readText())
        }
    }

    private fun localRevisionFixture(sync: DirectorySync = DirectorySync { true }, journalSync: DirectorySync = DirectorySync { true }): LocalRevision {
        val store = LocalLibraryDirectory(temporary.newFolder().canonicalFile, sync)
        val journal = LocalLibraryDirectory(temporary.newFolder().canonicalFile, journalSync)
        val synthetic = fixture()
        val prior = generation.copy(id = predecessor, predecessorId = null, catalog = id(synthetic.previous))
        val original = File(temporary.newFolder(), "original").apply { writeText("prior original") }
        val objectId = id(original)
        store.createCatalog(prior) { it.write("old catalog".encodeToByteArray()) }
        store.createMarker(prior, prior.encode())
        store.create(objectId) { it.write(original.readBytes()) }
        return LocalRevision(store, journal, prior, objectId)
    }

    private class RevisionFolder(val folder: Folder, val original: File, val prior: LibraryGeneration, val objectId: ObjectId,
        val journal: File,
    ) :
        LibraryMergePublication.Destination, LibraryCatalogPublication.Store by folder {
        override fun requireIndependentScratch(scratch: Path) = Unit
        override fun requireIndependentRevisionJournal(path: Path) = Unit
        override fun generationInventory(checkpoint: () -> Unit) = LibraryGenerationInventory(
            listOfNotNull(prior, folder.marker.takeIf(File::isFile)?.inputStream()?.use(LibraryGeneration::read)),
            if (folder.marker.isFile) 2 else 1,
        )
        override fun openCatalog(generation: LibraryGeneration) =
            if (generation == prior) folder.previous.inputStream() else folder.openCatalog(generation)
        override fun open(id: ObjectId) = original.takeIf { it.isFile && id == objectId }?.inputStream()
        override fun create(id: ObjectId, requireCurrent: () -> Unit, write: (OutputStream) -> Unit): Nothing =
            error("A recipe edit cannot write an original")
        override fun createGuardedCatalog(generation: LibraryGeneration, requireCurrent: () -> Unit, write: (OutputStream) -> Unit) {
            requireCurrent()
            folder.createCatalog(generation, write)
            requireCurrent()
        }
        override fun createMarker(generation: LibraryGeneration, bytes: ByteArray): Nothing = error("Marker requires guarded ownership")
        override fun createMergeMarker(generation: LibraryGeneration, bytes: ByteArray, requireStage: () -> Unit, requireCurrent: () -> Unit) {
            requireStage()
            requireCurrent()
            folder.createMarker(generation, bytes)
        }
    }
    private fun publisher(store: LibraryCatalogPublication.Store) =
        LibraryCatalogPublication(store, temporary.newFolder().toPath())
    private fun publish(directory: Folder, verify: (File) -> Unit) =
        publisher(directory).publish(generation, 100, { "abc".byteInputStream() }, verifyReopen = verify)

    private class Folder(root: File, generation: LibraryGeneration, predecessor: String) : LibraryCatalogPublication.Store {
        val catalog = File(root, generation.catalogPath).apply { checkNotNull(parentFile).mkdirs() }
        val previous = File(catalog.parentFile, "$predecessor.sqlite").apply { writeText("old catalog") }
        val marker = File(root, "${generation.id}.complete")
        override fun openCatalog(generation: LibraryGeneration) = catalog.takeIf(File::isFile)?.inputStream()
        override fun openMarker(generation: LibraryGeneration) = marker.takeIf(File::isFile)?.inputStream()
        override fun createCatalog(generation: LibraryGeneration, write: (OutputStream) -> Unit) {
            Files.newOutputStream(catalog.toPath(), CREATE_NEW, WRITE).use(write)
        }
        override fun createMarker(generation: LibraryGeneration, bytes: ByteArray) {
            Files.newOutputStream(marker.toPath(), CREATE_NEW, WRITE).use { it.write(bytes) }
        }
        override fun confirmPayloadDurability(generation: LibraryGeneration, requiresObjects: Boolean) = Unit
        override fun confirmMarkerDurability(generation: LibraryGeneration) = Unit
    }
}
