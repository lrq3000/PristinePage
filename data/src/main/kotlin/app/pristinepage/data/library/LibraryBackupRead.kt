package app.pristinepage.data.library

import app.pristinepage.core.io.transferExactBytes
import app.pristinepage.data.library.LibraryObjectTransfer.ObjectId
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path

/** Read-only proof for a stable cooperating backup, not authority to import its rows, activate it, or retire anything. */
internal class LibraryBackupRead(
    private val store: Source,
    private val privateScratch: Path,
    private val maximumCatalogBytes: Long,
    private val checkpoint: () -> Unit = {},
) {
    interface Source {
        /** The adapter must establish separation for its actual storage identity, not just compare display names. */
        fun requireIndependentScratch(scratch: Path)
        fun generationInventory(checkpoint: () -> Unit = {}): LibraryGenerationInventory
        /** Null means positive absence; inability to open or read the selected location propagates as failure. */
        fun openCatalog(generation: LibraryGeneration): InputStream?
        /** Null means positive absence; losing access to an open library is a failure, not an empty directory. */
        fun open(id: ObjectId): InputStream?
    }

    data class Projection<T>(val value: T, val objects: Collection<ObjectId>)
    data class Verified<T>(val generation: LibraryGeneration, val value: T)

    /** The current tip can nominate an operation to examine; it is never a completion or activation proof. */
    fun observeTip(retained: LibraryGeneration): LibraryGeneration {
        verifyScratch()
        val inventory = store.generationInventory(checkpoint)
        inventory.requireExact(retained)
        checkpoint()
        return inventory.tip
    }

    /** Recheck identity of an already admitted tip and catalog without reopening every original. */
    fun requireCurrent(generation: LibraryGeneration, retainedPredecessor: LibraryGeneration? = null, verifyRetainedCatalog: Boolean = false) {
        verifyScratch()
        if (generation.catalog.byteCount > maximumCatalogBytes ||
            (verifyRetainedCatalog && (retainedPredecessor == null || retainedPredecessor.catalog.byteCount > maximumCatalogBytes))
        ) throw IOException("The library catalog exceeds the verification budget")
        checkTip(generation, retainedPredecessor)
        openCatalog(generation).use { generation.verifyCatalog(it, checkpoint) }
        if (verifyRetainedCatalog) {
            val previous = checkNotNull(retainedPredecessor)
            openCatalog(previous).use { previous.verifyCatalog(it, checkpoint) }
        }
        checkTip(generation, retainedPredecessor)
        checkpoint()
    }

    /** The caller derives [objectId] from an already admitted snapshot and owns disposable output. Failure can leave output bytes but never authorizes their use. */
    fun copyExactOriginal(generation: LibraryGeneration, objectId: ObjectId, output: OutputStream) {
        verifyScratch()
        fun requireCatalog() {
            checkpoint()
            openCatalog(generation).use { generation.verifyCatalog(it, checkpoint) }
        }
        checkTip(generation)
        requireCatalog()
        checkpoint()
        val input = store.open(objectId) ?: throw IOException("The verified library original is missing")
        input.use { transferExactBytes(it, output, objectId.byteCount, objectId.sha256, checkpoint) }
        requireCatalog()
        checkTip(generation)
        checkpoint()
    }

    private fun checkTip(generation: LibraryGeneration, retainedPredecessor: LibraryGeneration? = null) {
        checkpoint()
        val inventory = store.generationInventory(checkpoint)
        inventory.requireExact(generation)
        retainedPredecessor?.let(inventory::requireExact)
        if (inventory.tip != generation) throw UnexpectedLibraryTip()
    }

    fun <T> read(expectedSchema: Int, generation: LibraryGeneration? = null, expectedTip: LibraryGeneration? = null,
        maximumOriginalBytes: Long = Long.MAX_VALUE,
        project: (LibraryGeneration, File) -> Projection<T>,
    ): Verified<T> {
        require(maximumOriginalBytes >= 0)
        val scratch = verifyScratch()
        val inventory = store.generationInventory(checkpoint)
        val tip = inventory.tip
        if (expectedTip != null && tip != expectedTip) throw UnexpectedLibraryTip()
        val selected = generation ?: tip
        inventory.requireExact(selected)
        if (selected.catalogSchema != expectedSchema || selected.catalog.byteCount > maximumCatalogBytes) {
            throw IOException("Unsupported or oversized library catalog")
        }
        val projection = openCatalog(selected).use { input ->
            LibraryCatalogVerification(scratch, checkpoint).read(input, selected) { project(selected, it) }
        }
        verifyOriginals(projection.objects, maximumOriginalBytes)
        // Closing the last original (or projecting an empty catalog) can observe cancellation before another provider open.
        checkpoint()
        openCatalog(selected).use { selected.verifyCatalog(it, checkpoint) }
        val reopened = store.generationInventory(checkpoint)
        reopened.requireExact(selected)
        if (reopened.tip != tip) throw IOException("Library generation changed during verification")
        checkpoint()
        return Verified(selected, projection.value)
    }

    private fun verifyScratch(): Path {
        val scratch = privateScratch.toRealPath()
        if (scratch != privateScratch.toAbsolutePath()) throw IOException("Invalid private verification directory")
        store.requireIndependentScratch(scratch)
        return scratch
    }

    private fun verifyOriginals(objects: Collection<ObjectId>, maximumBytes: Long) {
        for (id in admitOriginals(objects, maximumBytes, checkpoint)) {
            checkpoint()
            val input = store.open(id) ?: throw IOException("Missing required library object")
            input.use { transferExactBytes(it, null, id.byteCount, id.sha256, checkpoint) }
        }
    }

    private fun openCatalog(tip: LibraryGeneration) = store.openCatalog(tip) ?: throw IOException("Library catalog is missing")

    companion object {
        /** Admit the complete distinct payload union before opening any original, or before promising a readable merge. */
        fun admitOriginals(objects: Collection<ObjectId>, maximumBytes: Long, checkpoint: () -> Unit = {}): Collection<ObjectId> {
            require(maximumBytes >= 0)
            val unique = LinkedHashMap<String, ObjectId>()
            var remaining = maximumBytes
            for (id in objects) {
                checkpoint()
                val retained = unique.putIfAbsent(id.sha256, id)
                if (retained != null) {
                    if (retained != id) throw IOException("Conflicting library object lengths")
                } else {
                    if (id.byteCount > remaining) throw IOException("The library exceeds the original verification budget")
                    remaining -= id.byteCount
                }
            }
            return unique.values
        }
    }
}

internal class UnexpectedLibraryTip : IOException("The verified library generation is no longer current")
