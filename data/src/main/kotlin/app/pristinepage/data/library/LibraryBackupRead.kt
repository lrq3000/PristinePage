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

    /** The caller derives [objectId] from an already admitted snapshot and owns disposable output. Failure can leave output bytes but never authorizes their use. */
    fun copyExactOriginal(generation: LibraryGeneration, objectId: ObjectId, output: OutputStream) {
        val scratch = privateScratch.toRealPath()
        if (scratch != privateScratch.toAbsolutePath()) throw IOException("Invalid private verification directory")
        store.requireIndependentScratch(scratch)
        fun requireTip() {
            checkpoint()
            val inventory = store.generationInventory(checkpoint)
            inventory.requireExact(generation)
            if (inventory.tip != generation) throw IOException("The verified library generation is no longer current")
        }
        fun requireCatalog() {
            checkpoint()
            openCatalog(generation).use { generation.verifyCatalog(it, checkpoint) }
        }
        requireTip()
        requireCatalog()
        checkpoint()
        val input = store.open(objectId) ?: throw IOException("The verified library original is missing")
        input.use { transferExactBytes(it, output, objectId.byteCount, objectId.sha256, checkpoint) }
        requireCatalog()
        requireTip()
        checkpoint()
    }

    fun <T> read(expectedSchema: Int, generation: LibraryGeneration? = null, expectedTip: LibraryGeneration? = null,
        maximumOriginalBytes: Long = Long.MAX_VALUE,
        project: (LibraryGeneration, File) -> Projection<T>,
    ): Verified<T> {
        require(maximumOriginalBytes >= 0)
        val scratch = privateScratch.toRealPath()
        if (scratch != privateScratch.toAbsolutePath()) throw IOException("Invalid private verification directory")
        store.requireIndependentScratch(scratch)
        val inventory = store.generationInventory(checkpoint)
        val tip = inventory.tip
        if (expectedTip != null && tip != expectedTip) throw IOException("Unexpected library tip during catalog admission")
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

    private fun verifyOriginals(objects: Collection<ObjectId>, maximumBytes: Long) {
        // Reject contradictory declarations before opening any payload; identical references need only one read.
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
        for (id in unique.values) {
            checkpoint()
            val input = store.open(id) ?: throw IOException("Missing required library object")
            input.use { transferExactBytes(it, null, id.byteCount, id.sha256, checkpoint) }
        }
    }

    private fun openCatalog(tip: LibraryGeneration) = store.openCatalog(tip) ?: throw IOException("Library catalog is missing")
}
