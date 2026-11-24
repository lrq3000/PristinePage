package app.pristinepage.data.library

import android.content.Context
import app.pristinepage.core.io.transferExactBytes
import app.pristinepage.data.library.LibraryObjectTransfer.ObjectId
import app.pristinepage.data.library.RoomLibraryCatalogReader.Companion.MAX_PORTABLE_BYTES
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Reads one exact detached app-private library in place. The caller must retain cooperative ownership throughout admission and each read; these identity observations do not provide a lock, grant, activation or mutation. */
internal class RoomLibraryPortableView(context: Context, private val scratchDirectory: File) {
    private val app = context.applicationContext
    private val reader = RoomLibraryCatalogReader(app, MAX_PORTABLE_BYTES)

    class Proposal internal constructor(internal val root: Path, internal val rootKey: String, val generation: LibraryGeneration)

    /** Inspect the marker and bind the actual root for later confirmation; not full catalog or payload verification. */
    suspend fun prepare(root: File): Proposal = withContext(Dispatchers.IO) {
        val checkpoint = { coroutineContext.ensureActive() }
        val canonical = canonicalLibraryDirectory(root, app.filesDir.toPath().toRealPath())
        val key = localLibraryRootKey(canonical)
        val tip = LocalLibraryDirectory(canonical.toFile()).readTip(checkpoint)
        if (localLibraryRootKey(canonical) != key) throw IOException("The proposed library root changed")
        Proposal(canonical, key, tip)
    }

    suspend fun open(proposal: Proposal, exact: LibraryGeneration): Opened = withContext(Dispatchers.IO) {
        val checkpoint = { coroutineContext.ensureActive() }
        if (proposal.generation != exact) throw IOException("The confirmed library generation changed")
        val privateFiles = app.filesDir.toPath().toRealPath()
        val canonical = canonicalLibraryDirectory(proposal.root.toFile(), privateFiles)
        val scratch = canonicalLibraryDirectory(scratchDirectory, privateFiles)
        val key = proposal.rootKey
        if (localLibraryRootKey(canonical) != key) throw IOException("The confirmed library root changed")
        val store = LocalLibraryDirectory(canonical.toFile())
        val verified = LibraryBackupRead(store, scratch, MAX_PORTABLE_BYTES, checkpoint).read(reader.schemaVersion, exact, exact) { _, file ->
            reader.read(file) { db ->
                val catalog = LibraryPortableCatalogProjection(db, checkpoint = checkpoint).snapshot()
                LibraryBackupRead.Projection(catalog, catalog.originals.map { ObjectId(it.byteSha256, it.byteCount) })
            }
        }
        if (localLibraryRootKey(canonical) != key) throw IOException("The verified library root changed")
        VerifiedView(exact, verified.value, key, store)
    }

    internal sealed interface Opened {
        val generation: LibraryGeneration
        val catalog: RoomLibrarySavedCatalog.Snapshot

        /** The caller owns temporary output and must discard it if this operation fails. The source remains in the original library. */
        suspend fun copyOriginal(blobId: String, output: OutputStream)
    }

    private class VerifiedView(
        override val generation: LibraryGeneration,
        override val catalog: RoomLibrarySavedCatalog.Snapshot,
        private val rootKey: String,
        private val store: LocalLibraryDirectory,
    ) : Opened {
        private val originals = catalog.originals.associate { it.sourceBlobId to ObjectId(it.byteSha256, it.byteCount) }

        override suspend fun copyOriginal(blobId: String, output: OutputStream) = withContext(Dispatchers.IO) {
            val checkpoint = { coroutineContext.ensureActive() }
            val id = originals[blobId] ?: throw IOException("Original is not in the verified library view")
            checkSelection(checkpoint)
            val input = store.open(id) ?: throw IOException("Library original is no longer available")
            input.use { transferExactBytes(it, output, id.byteCount, id.sha256, checkpoint) }
            checkSelection(checkpoint)
        }

        private fun checkSelection(checkpoint: () -> Unit) {
            checkTip(checkpoint)
            val catalog = store.openCatalog(generation) ?: throw IOException("The verified catalog is no longer available")
            catalog.use { generation.verifyCatalog(it, checkpoint) }
            checkTip(checkpoint)
        }

        private fun checkTip(checkpoint: () -> Unit) {
            if (localLibraryRootKey(store.rootPath) != rootKey || store.readTip(checkpoint) != generation) {
                throw IOException("The verified library view is no longer current")
            }
        }
    }
}
