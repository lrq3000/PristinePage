package app.pristinepage.data.library

import app.pristinepage.core.io.transferExactBytes
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path

/** Publishes a verified immutable catalog generation, not an active-root switch or authority to retire its predecessor. */
internal class LibraryCatalogPublication(private val store: Store, private val privateScratch: Path, private val checkpoint: () -> Unit = {}) {
    /** The destination owner maintains exclusive generation and predecessor ownership throughout publication. Store adapters must not overwrite or conflate IO failure with absence. */
    interface Store {
        fun openCatalog(generation: LibraryGeneration): InputStream?
        fun openMarker(generation: LibraryGeneration): InputStream?

        /** Commit complete, closed catalog bytes exclusively at this generation's previously unused locator. If the writer fails, retain evidence outside the final locator. */
        fun createCatalog(generation: LibraryGeneration, write: (OutputStream) -> Unit)

        /** Publish an immutable marker exclusively. The caller must hold the expected predecessor fence; a throwing operation may have already succeeded. */
        fun createMarker(generation: LibraryGeneration, bytes: ByteArray)

        /** Confirm durability of the catalog and already transferred objects before publishing or accepting a marker, including on retry. */
        fun confirmPayloadDurability(generation: LibraryGeneration, requiresObjects: Boolean = false)

        /** Confirm the durable visibility of the exact marker. Simply reading an unacknowledged write is not sufficient. */
        fun confirmMarkerDurability(generation: LibraryGeneration)
    }

    /**
     * The enclosing transaction has already checked the schema version, reserved space (including one catalog-sized private verification copy), and transferred missing objects. The callback must open the supplied authenticated private copy read-only, prove its schema and its original references at the destination, and close it before returning. Never open unchecked provider bytes as SQLite or let the callback migrate them.
     */
    fun publish(
        generation: LibraryGeneration,
        maximumCatalogBytes: Long,
        openIncoming: () -> InputStream,
        verifyExistingOnly: Boolean = false,
        requiresObjects: Boolean = false,
        beforeNewMarker: ((LibraryGeneration) -> Unit)? = null,
        createOnly: Boolean = false,
        verifyReopen: (File) -> Unit,
    ): LibraryGeneration {
        require(maximumCatalogBytes > 0)
        require(!createOnly || !verifyExistingOnly)
        if (generation.catalog.byteCount > maximumCatalogBytes) throw IOException("Library catalog exceeds admission limit")
        val alreadyMarked = markerExists(generation)
        if (createOnly && alreadyMarked) throw IOException("A fresh publication cannot adopt a visible library marker")
        if (verifyExistingOnly && !alreadyMarked) throw IOException("The expected published library marker is missing")
        checkpoint()
        val catalog = store.openCatalog(generation)
        if (createOnly && catalog != null) {
            catalog.use { throw IOException("A fresh publication cannot adopt an existing catalog") }
        }
        if (catalog == null) {
            if (alreadyMarked) throw IOException("Committed library catalog is missing")
            checkpoint()
            store.createCatalog(generation) { sink ->
                openIncoming().use { transferExactBytes(it, sink, generation.catalog.byteCount, generation.catalog.sha256, checkpoint) }
            }
        }
        (catalog ?: openCatalog(generation)).use { LibraryCatalogVerification(privateScratch, checkpoint).read(it, generation, verifyReopen) }
        // A second read must still identify the catalog we actually reopened, not a provider's replacement at the same locator.
        openCatalog(generation).use { generation.verifyCatalog(it, checkpoint) }
        store.confirmPayloadDurability(generation, requiresObjects)
        checkpoint()
        if (!alreadyMarked) {
            // Observe only a new publication, after the exact payload is verified and its directory barriers have returned.
            if (beforeNewMarker != null) {
                beforeNewMarker(generation)
                checkpoint()
            }
            store.createMarker(generation, generation.encode())
        }
        store.confirmMarkerDurability(generation)
        if (!markerExists(generation)) throw IOException("Library completion marker is missing")
        checkpoint()
        return generation
    }

    private fun openCatalog(generation: LibraryGeneration): InputStream {
        checkpoint()
        return store.openCatalog(generation) ?: throw IOException("Library catalog disappeared")
    }

    private fun markerExists(generation: LibraryGeneration): Boolean {
        checkpoint()
        val marker = store.openMarker(generation) ?: return false
        if (marker.use(LibraryGeneration::read) != generation) throw IOException("Library completion marker conflicts")
        return true
    }
}
