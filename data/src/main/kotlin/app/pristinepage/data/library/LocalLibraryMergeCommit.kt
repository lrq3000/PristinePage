package app.pristinepage.data.library

import app.pristinepage.data.db.SourceBlobRow
import app.pristinepage.data.library.LibraryMergeIntent.Resume
import app.pristinepage.data.library.RoomLibraryCatalogReader.Companion.MAX_PORTABLE_BYTES
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Consumes a private durable intent under exclusive cooperative ownership of the input roots and scratch. Never activates or retires either library. */
internal class LocalLibraryMergeCommit(
    private val destination: LocalLibraryDirectory,
    private val source: LocalLibraryDirectory,
    private val journal: LocalLibraryMergeJournal,
    private val checkpoint: () -> Unit = {},
) {
    /** The caller must admit both exact input catalogs and compare [verifyReopened] against their planned Room projection. */
    fun commit(
        operationId: String,
        destinationKey: String,
        incomingKey: String,
        supportedSchema: Int,
        candidate: LibraryCatalogMerge.Candidate,
        destinationRows: List<SourceBlobRow>,
        incomingRows: List<SourceBlobRow>,
        verifyReopened: (File) -> Unit,
    ): LibraryGeneration {
        val scratch = journal.rootPath
        if (overlaps(destination.rootPath, source.rootPath) || overlaps(destination.rootPath, scratch) || overlaps(source.rootPath, scratch)) {
            throw IOException("Library merge roots overlap")
        }
        val intent = journal.reopen(operationId, checkpoint) ?: throw IOException("Private merge intent is missing")
        val generations = destination.generationInventory(checkpoint)
        val before = generations.tip
        val selected = intent.resume(destinationKey, incomingKey, before, source.readTip(checkpoint), supportedSchema)
        val published = selected == Resume.VERIFY_PUBLISHED
        generations.requireExact(intent.destination)
        if (!published) generations.requireAppendable(intent.candidate)
        verifyCatalog(destination, intent.destination)
        verifyCatalog(source, intent.incoming)
        destination.openCatalog(intent.candidate)?.use { intent.candidate.verifyCatalog(it, checkpoint) }

        journal.openStage(operationId).use {
            LibraryCatalogVerification(scratch, checkpoint).read(it, intent.candidate, verifyReopened)
        }
        val stageSize = intent.candidate.catalog.byteCount
        val overhead = if (published) stageSize else (2 * stageSize + 16L * 1024 * (candidate.catalog.originals.size + 8L))
        val scratchSpace = scratch.toFile().usableSpace
        val capacity = if (published) scratchSpace else minOf(destination.rootPath.toFile().usableSpace, scratchSpace)
        val payload = LibraryMergePayloads.prepare(candidate, destinationRows, incomingRows, destination, source,
            overhead, capacity, published, checkpoint)
        checkTips(intent, before)
        if (!published) payload.copyAndVerify()
        checkTips(intent, before)

        val publishedGeneration = LibraryCatalogPublication(destination, scratch, checkpoint).publish(
            intent.candidate, MAX_PORTABLE_BYTES, { journal.openStage(operationId) }, verifyExistingOnly = published,
            requiresObjects = candidate.catalog.originals.isNotEmpty(),
        ) { reopened ->
            verifyReopened(reopened)
            val objects = candidate.catalog.originals.map {
                LibraryObjectTransfer.Requirement(LibraryObjectTransfer.ObjectId(it.byteSha256, it.byteCount), null)
            }
            LibraryObjectTransfer(destination, checkpoint).prepare(objects, 0, 0)
            checkTips(intent, before)
        }
        checkTips(intent, publishedGeneration)
        return publishedGeneration
    }

    private fun verifyCatalog(store: LocalLibraryDirectory, expected: LibraryGeneration) {
        store.openCatalog(expected)?.use { expected.verifyCatalog(it, checkpoint) } ?: throw IOException("An input catalog disappeared")
    }

    private fun checkTips(intent: LibraryMergeIntent, expectedDestination: LibraryGeneration) {
        val current = destination.generationInventory(checkpoint)
        current.requireExact(intent.destination)
        if (current.tip != expectedDestination || source.readTip(checkpoint) != intent.incoming) {
            throw IOException("Library generation changed during the copy")
        }
    }

    private fun overlaps(a: Path, b: Path) = a.startsWith(b) || b.startsWith(a) || Files.isSameFile(a, b)
}
