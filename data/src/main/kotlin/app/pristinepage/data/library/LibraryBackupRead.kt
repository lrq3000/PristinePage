package app.pristinepage.data.library

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Read-only proof for a stable cooperating local backup, not authority to import its rows, activate it, or retire anything. */
internal class LibraryBackupRead(
    private val store: LocalLibraryDirectory,
    private val privateScratch: Path,
    private val maximumCatalogBytes: Long,
    private val checkpoint: () -> Unit = {},
) {
    data class Projection<T>(val value: T, val objects: Collection<LibraryObjectTransfer.ObjectId>)
    data class Verified<T>(val generation: LibraryGeneration, val value: T)

    fun <T> read(expectedSchema: Int, project: (LibraryGeneration, File) -> Projection<T>): Verified<T> =
        read(expectedSchema, generation = null, expectedTip = null, project)

    fun <T> read(expectedSchema: Int, generation: LibraryGeneration?, expectedTip: LibraryGeneration?,
        project: (LibraryGeneration, File) -> Projection<T>,
    ): Verified<T> {
        val scratch = privateScratch.toRealPath()
        val root = store.rootPath
        if (scratch != privateScratch.toAbsolutePath() || scratch.startsWith(root) || root.startsWith(scratch) ||
            Files.isSameFile(root, scratch)
        ) throw IOException("Backup and private verification directory overlap or are aliased")
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
        // No incoming stream means every declared object must already exist. Preparing validates bytes and performs no publication.
        LibraryObjectTransfer(store, checkpoint).prepare(
            projection.objects.map { LibraryObjectTransfer.Requirement(it, null) }, 0, 0,
        )
        openCatalog(selected).use { selected.verifyCatalog(it, checkpoint) }
        val reopened = store.generationInventory(checkpoint)
        reopened.requireExact(selected)
        if (reopened.tip != tip) throw IOException("Library generation changed during verification")
        checkpoint()
        return Verified(selected, projection.value)
    }

    private fun openCatalog(tip: LibraryGeneration) = store.openCatalog(tip) ?: throw IOException("Library catalog is missing")
}
