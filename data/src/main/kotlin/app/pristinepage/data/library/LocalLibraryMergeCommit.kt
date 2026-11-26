package app.pristinepage.data.library

import app.pristinepage.data.db.SourceBlobRow
import app.pristinepage.data.library.LibraryMergeIntent.Resume
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
    private val requireLocations: () -> Unit = {},
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
        val before = destination.readTip(checkpoint)
        val selected = intent.resume(destinationKey, incomingKey, before, source.readTip(checkpoint), supportedSchema)
        val published = selected == Resume.VERIFY_PUBLISHED
        val stageSize = intent.candidate.catalog.byteCount
        val overhead = if (published) stageSize else (2 * stageSize + 16L * 1024 * (candidate.catalog.originals.size + 8L))
        val scratchSpace = scratch.toFile().usableSpace
        val capacity = if (published) scratchSpace else minOf(destination.rootPath.toFile().usableSpace, scratchSpace)
        return LibraryMergePublication(destination, source, scratch,
            requireRoots = {
                requireLocations()
                destination.requireIndependentScratch(source.rootPath)
                destination.requireIndependentScratch(scratch)
                source.requireIndependentScratch(scratch)
            }, checkpoint).publish(intent, selected, candidate, destinationRows, incomingRows,
            capacity, overhead, { journal.openStage(operationId) }, verifyReopened)
    }

    private fun overlaps(a: Path, b: Path) = a.startsWith(b) || b.startsWith(a) || Files.isSameFile(a, b)
}
