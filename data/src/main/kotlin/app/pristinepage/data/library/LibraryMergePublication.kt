package app.pristinepage.data.library

import app.pristinepage.data.db.SourceBlobRow
import app.pristinepage.data.library.RoomLibraryCatalogReader.Companion.MAX_PORTABLE_BYTES
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path

/** Common immutable-generation append. The adapter must hold the exact roots, their grants, and durable per-attempt authority throughout. */
internal class LibraryMergePublication(
    private val destination: Destination,
    private val incoming: LibraryBackupRead.Source,
    private val scratch: Path,
    private val requireRoots: () -> Unit,
    private val checkpoint: () -> Unit = {},
) {
    interface Destination : LibraryMergePayloads.Destination, LibraryBackupRead.Source, LibraryCatalogPublication.Store {
        /** Complete staging first; call [requireCurrent] immediately before the externally visible move or link. */
        fun createMergeMarker(generation: LibraryGeneration, bytes: ByteArray, requireStage: () -> Unit, requireCurrent: () -> Unit)
    }

    fun publish(
        intent: LibraryMergeIntent,
        mode: LibraryMergeIntent.Resume,
        candidate: LibraryCatalogMerge.Candidate,
        destinationRows: List<SourceBlobRow>,
        incomingRows: List<SourceBlobRow>,
        availableBytes: Long,
        budgetedMetadataBytes: Long,
        openStage: () -> InputStream,
        verifyReopened: (File) -> Unit,
    ): LibraryGeneration {
        val verifyOnly = mode == LibraryMergeIntent.Resume.VERIFY_PUBLISHED
        val before = if (verifyOnly) intent.candidate else intent.destination
        fun checkTips(expected: LibraryGeneration) {
            checkpoint()
            requireRoots()
            val current = destination.generationInventory(checkpoint)
            current.requireExact(intent.destination)
            if (current.tip != expected || incoming.generationInventory(checkpoint).tip != intent.incoming) {
                throw IOException("Library generation changed during the copy")
            }
            requireRoots()
        }
        fun verifyInput(store: LibraryBackupRead.Source, expected: LibraryGeneration) {
            requireRoots()
            store.openCatalog(expected)?.use { expected.verifyCatalog(it, checkpoint) }
                ?: throw IOException("An input catalog disappeared")
            requireRoots()
        }
        val objects = candidate.catalog.originals.map {
            LibraryObjectTransfer.Requirement(LibraryObjectTransfer.ObjectId(it.byteSha256, it.byteCount), null)
        }
        val readOnly = object : LibraryObjectTransfer.Store {
            override fun open(id: LibraryObjectTransfer.ObjectId): InputStream? {
                requireRoots()
                return destination.open(id)
            }
            override fun create(id: LibraryObjectTransfer.ObjectId, write: (OutputStream) -> Unit): Nothing =
                throw IOException("The final library readback cannot create originals")
        }
        fun verifyDestinationPayload(expectedTip: LibraryGeneration) {
            verifyInput(destination, intent.candidate)
            LibraryObjectTransfer(readOnly, checkpoint).prepare(objects, 0, 0)
            checkTips(expectedTip)
        }
        checkTips(before)
        if (!verifyOnly) destination.generationInventory(checkpoint).requireAppendable(intent.candidate)
        verifyInput(destination, intent.destination)
        verifyInput(incoming, intent.incoming)
        destination.openCatalog(intent.candidate)?.use { intent.candidate.verifyCatalog(it, checkpoint) }
        openStage().use { LibraryCatalogVerification(scratch, checkpoint).read(it, intent.candidate, verifyReopened) }

        val payload = LibraryMergePayloads.prepare(candidate, destinationRows, incomingRows, destination, incoming::open,
            requireRoots, budgetedMetadataBytes, availableBytes, verifyOnly, checkpoint)
        checkTips(before)
        if (!verifyOnly) payload.copyAndVerify()
        checkTips(before)
        val guardedStore = object : LibraryCatalogPublication.Store by destination {
            override fun createMarker(generation: LibraryGeneration, bytes: ByteArray) {
                destination.createMergeMarker(generation, bytes, requireStage = { checkTips(before) }) {
                    verifyInput(destination, intent.destination)
                    verifyInput(incoming, intent.incoming)
                    verifyDestinationPayload(before)
                    checkpoint()
                }
            }
        }
        val published = LibraryCatalogPublication(guardedStore, scratch, checkpoint).publish(
            intent.candidate, MAX_PORTABLE_BYTES, openStage, verifyExistingOnly = verifyOnly,
            requiresObjects = objects.isNotEmpty(),
        ) { reopened ->
            verifyReopened(reopened)
            LibraryObjectTransfer(readOnly, checkpoint).prepare(objects, 0, 0)
            checkTips(before)
        }
        // Return no acknowledgment if a marker barrier changed the payload, including on read-only replay.
        verifyDestinationPayload(published)
        return published
    }
}
