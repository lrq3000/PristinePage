package app.pristinepage.data.library

import app.pristinepage.data.library.LibraryObjectTransfer.ObjectId
import app.pristinepage.data.library.RoomLibraryCatalogReader.Companion.MAX_PORTABLE_BYTES
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** A metadata-only successor of one held library. The caller owns the durable attempt and the exact writable scope. */
internal class LibraryCatalogRevisionPublication(
    private val destination: LibraryMergePublication.Destination,
    private val scratch: Path,
    private val journal: LocalLibraryDirectory,
    private val requireOwner: () -> Unit,
    private val checkpoint: () -> Unit = {},
    private val onPublicationStarted: (LibraryGeneration) -> Unit = {},
) {
    fun publish(
        predecessor: LibraryGeneration, successor: LibraryGeneration,
        originalObjects: Collection<ObjectId>, editedObjects: Collection<ObjectId>,
        availableBytes: Long, catalogAndJournalBytes: Long,
        openStage: () -> InputStream, verifyReopened: (File) -> Unit,
    ): LibraryGeneration = withSelectedLibraryWriteLane(journal.rootPath) { lease ->
        publishUnderLease(lease, predecessor, successor, originalObjects, editedObjects, availableBytes,
            catalogAndJournalBytes, openStage, verifyReopened)
    }

    /** A provider adapter already holds the private lane before borrowing any provider handles. Do not reacquire it. */
    fun publishUnderLease(
        lease: SelectedLibraryWriteLease, predecessor: LibraryGeneration, successor: LibraryGeneration,
        originalObjects: Collection<ObjectId>, editedObjects: Collection<ObjectId>,
        availableBytes: Long, catalogAndJournalBytes: Long,
        openStage: () -> InputStream, verifyReopened: (File) -> Unit,
    ): LibraryGeneration {
        lease.requireCurrent(journal.rootPath)
        if (successor.libraryId != predecessor.libraryId || successor.predecessorId != predecessor.id ||
            successor.catalogSchema != predecessor.catalogSchema || predecessor.catalog.byteCount > MAX_PORTABLE_BYTES ||
            successor.catalog.byteCount > MAX_PORTABLE_BYTES
        ) throw IOException("The edited catalog is not an exact successor")
        val bytes = successor.catalog.byteCount
        val records = journal.catalogRevisionSpendByteCount(predecessor, successor) + successor.encode().size
        // Charge both catalogs in case they share a volume. The adapter's overhead estimate must also cover the exact records.
        if (catalogAndJournalBytes < bytes + records || availableBytes < bytes || catalogAndJournalBytes > availableBytes - bytes) {
            throw IOException("Not enough space for the edited catalog")
        }
        if (!scratch.isAbsolute || !Files.isDirectory(scratch, NOFOLLOW_LINKS) || scratch.toRealPath() != scratch) {
            throw IOException("Catalog revision scratch must be an absolute canonical directory")
        }
        // Private scratch and journal can also share a volume; do not let both spend the same free bytes.
        if (scratch.toFile().usableSpace < catalogAndJournalBytes || journal.rootPath.toFile().usableSpace < catalogAndJournalBytes) {
            throw IOException("Not enough private space to verify the edited catalog")
        }
        val originals = LibraryBackupRead.admitOriginals(originalObjects, Long.MAX_VALUE, checkpoint).toSet()
        if (LibraryBackupRead.admitOriginals(editedObjects, Long.MAX_VALUE, checkpoint).toSet() != originals) {
            throw IOException("A catalog-only edit cannot change original objects")
        }

        requireOwner()
        destination.requireIndependentScratch(scratch)
        destination.requireIndependentRevisionJournal(journal.rootPath)
        return publishOwned(predecessor, successor, originals, openStage, verifyReopened) {
            lease.requireCurrent(journal.rootPath)
            requireOwner()
        }
    }

    private fun publishOwned(
        predecessor: LibraryGeneration, successor: LibraryGeneration, originals: Set<ObjectId>,
        openStage: () -> InputStream, verifyReopened: (File) -> Unit, requireLiveOwner: () -> Unit,
    ): LibraryGeneration {
        fun requireTip(expected: LibraryGeneration) {
            checkpoint()
            requireLiveOwner()
            val inventory = destination.generationInventory(checkpoint)
            inventory.requireExact(predecessor)
            if (inventory.tip != expected) throw UnexpectedLibraryTip()
            if (expected == predecessor) inventory.requireAppendable(successor)
            requireLiveOwner()
        }
        fun verifyCatalog(generation: LibraryGeneration) {
            requireLiveOwner()
            destination.openCatalog(generation)?.use { generation.verifyCatalog(it, checkpoint) }
                ?: throw IOException("A catalog needed for the edit disappeared")
            requireLiveOwner()
        }
        val readOnly = object : LibraryObjectTransfer.Store {
            override fun open(id: ObjectId): InputStream? {
                requireLiveOwner()
                return destination.open(id)
            }
            override fun create(id: ObjectId, write: (OutputStream) -> Unit): Nothing =
                throw IOException("A catalog-only edit cannot write an original")
        }
        val requirements = originals.map { LibraryObjectTransfer.Requirement(it, null) }
        val transfer = LibraryObjectTransfer(readOnly, checkpoint)
        fun verifyOriginals() {
            transfer.prepare(requirements, 0, 0, requiredExisting = originals)
        }

        requireTip(predecessor)
        journal.requireUnspentCatalogRevision(successor.id)
        if (destination.openCatalog(successor)?.use { true } == true) {
            throw IOException("The edited catalog is already present; do not replay the write")
        }
        verifyCatalog(predecessor)
        verifyOriginals()
        requireTip(predecessor)
        journal.spendCatalogRevision(predecessor, successor) { requireTip(predecessor) }
        // This attempt has spent its write authority. The observer receives an obligation, not proof that any destination bytes are visible.
        onPublicationStarted(successor)
        val guarded = object : LibraryCatalogPublication.Store by destination {
            override fun createCatalog(generation: LibraryGeneration, write: (OutputStream) -> Unit) {
                requireTip(predecessor)
                destination.createGuardedCatalog(generation, { requireTip(predecessor) }, write)
            }

            override fun createMarker(generation: LibraryGeneration, bytes: ByteArray) {
                destination.createMergeMarker(generation, bytes, requireStage = { requireTip(predecessor) }) {
                    verifyCatalog(predecessor)
                    verifyCatalog(successor)
                    verifyOriginals()
                    requireTip(predecessor)
                }
            }
        }
        val published = LibraryCatalogPublication(guarded, scratch, checkpoint).publish(
            successor, MAX_PORTABLE_BYTES, openStage, requiresObjects = originals.isNotEmpty(), createOnly = true,
        ) { file ->
            verifyReopened(file)
            requireTip(predecessor)
        }
        requireTip(published)
        verifyCatalog(published)
        verifyCatalog(predecessor)
        verifyOriginals()
        requireTip(published)
        return published
    }
}
