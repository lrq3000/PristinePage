package app.pristinepage.data.library

import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.system.Os
import app.pristinepage.data.library.AndroidLibraryWritableFolder.WritableTree
import app.pristinepage.data.library.LibraryObjectTransfer.ObjectId
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest

/** One newly created backup child, one active staged file, and no append, retry, overwrite or delete authority. */
internal class AndroidNewLibraryBackup private constructor(
    private val provider: WritableTree,
    private val selected: HeldLibraryDirectory,
    private val root: HeldLibraryDirectory,
    private val stage: HeldLibraryDirectory,
    private val objects: HeldLibraryDirectory,
    private val catalogs: HeldLibraryDirectory,
    private val generations: HeldLibraryDirectory,
    journal: LocalLibraryMoveAuthorization,
) : LibraryObjectTransfer.Store, LibraryCatalogPublication.Store {
    private data class Leaf(val parent: String, val name: String)
    private val acknowledged = mutableMapOf<Leaf, Pair<Long, Long>>()
    private val leafWriter = AndroidLibraryLeafWriter(provider, selected, root, stage, journal, ::find, ::sync)

    override fun open(id: ObjectId) = open(objects, id.sha256)

    override fun create(id: ObjectId, write: (OutputStream) -> Unit) = publish(objects, id.sha256, id, write = write)

    override fun openCatalog(generation: LibraryGeneration) = open(catalogs, "${generation.id}.sqlite")

    override fun createCatalog(generation: LibraryGeneration, write: (OutputStream) -> Unit) =
        publish(catalogs, "${generation.id}.sqlite", generation.catalog, write = write)

    override fun openMarker(generation: LibraryGeneration) = open(generations, "${generation.id}.complete")

    override fun createMarker(generation: LibraryGeneration, bytes: ByteArray) {
        if (!bytes.contentEquals(generation.encode())) throw IOException("Library marker changed before publication")
        publish(generations, "${generation.id}.complete", ObjectId(backupSha256(bytes), bytes.size.toLong())) { it.write(bytes) }
    }

    override fun confirmPayloadDurability(generation: LibraryGeneration, requiresObjects: Boolean) {
        requireCompleteLayout()
        sync(objects)
        sync(catalogs)
        sync(root)
        sync(selected)
    }

    override fun confirmMarkerDurability(generation: LibraryGeneration) {
        sync(generations)
        sync(root)
        sync(selected)
    }

    /** A completed new backup has no extra managed files or unacknowledged leaves. Never adopt an uncertain leaf by matching bytes. */
    fun requireCompleteLayout() {
        requireBound(root)
        if (provider.children(root.id, 4).map { it.name }.toSet() != setOf("objects", "catalogs", "generations", STAGING)) {
            throw IOException("The new library backup namespaces changed")
        }
        for (parent in listOf(objects, catalogs, generations, stage)) {
            requireBound(parent)
            val observed = provider.children(parent.id, namespaceLimit(parent)).map {
                if (it.directory || it.id != "${parent.id}/${it.name}") throw IOException("Unexpected library backup entry")
                Leaf(parent.id, it.name)
            }
            if (observed.size != observed.toSet().size || observed.toSet() != acknowledged.keys.filter { it.parent == parent.id }.toSet()) {
                throw IOException("The new library backup contents changed")
            }
        }
        provider.requireExactAccess()
    }

    private fun open(parent: HeldLibraryDirectory, name: String): ParcelFileDescriptor.AutoCloseInputStream? {
        requireBound(parent)
        val identity = acknowledged[Leaf(parent.id, name)]
        // The folder started empty and this writer never resumes an existing folder. The move independently proves absence in the provider listing before it writes.
        if (identity == null) return null
        val input = ParcelFileDescriptor.AutoCloseInputStream(provider.open("${parent.id}/$name", "r"))
        try {
            if (leafWriter.fileIdentity(input) != identity) throw IOException("The published library file identity changed")
            provider.requireExactAccess()
            return input
        } catch (failure: Throwable) {
            try { input.close() } catch (closeFailure: Exception) { failure.addSuppressed(closeFailure) }
            throw failure
        }
    }

    private fun publish(parent: HeldLibraryDirectory, name: String, wanted: ObjectId, write: (OutputStream) -> Unit) {
        val leaf = Leaf(parent.id, name)
        if (acknowledged.containsKey(leaf)) throw IOException("The new library file would replace an existing name")
        acknowledged[leaf] = leafWriter.publish(parent, name, wanted, write = write)
    }

    private fun find(parent: HeldLibraryDirectory, name: String): String? {
        requireBound(parent)
        return exactChild(provider, parent.id, name, false, namespaceLimit(parent))
    }

    private fun namespaceLimit(parent: HeldLibraryDirectory) = if (parent == objects) RoomLibrarySavedCatalog.MAX_PAGES else 4

    private fun requireBound(directory: HeldLibraryDirectory) {
        provider.requireDirectory(selected.id, selected.descriptor)
        if (directory != selected) provider.requireDirectory(root.id, root.descriptor)
        if (directory != selected && directory != root) {
            provider.requireDirectory(directory.id, directory.descriptor)
            if (selected == root) AndroidLibraryDescriptorAncestry.requireDirectChild(directory.descriptor, root.descriptor)
        }
    }

    private fun sync(directory: HeldLibraryDirectory) {
        requireBound(directory)
        Os.fsync(directory.descriptor.fileDescriptor)
        provider.requireExactAccess()
    }

    companion object {
        private const val STAGING = "staging"

        /** Nothing can write until capacity and the caller's private durable operation are acknowledged on this held parent. */
        fun <T> create(
            provider: WritableTree, name: String?, required: Long, privateIdentities: Set<Pair<Long, Long>>,
            journal: LocalLibraryMoveAuthorization, recordIntent: (Pair<Long, Long>) -> Unit,
            requireEmptyRoot: (HeldLibraryDirectory) -> Unit = {},
            block: (AndroidNewLibraryBackup, Long) -> T,
        ): T {
            val seenIdentities = privateIdentities.toMutableSet()
            var device: Long? = null
            fun <R> directory(id: String, body: (HeldLibraryDirectory) -> R): R = provider.openDirectory(id).use {
                val identity = provider.directoryIdentity(it)
                if ((device != null && device != identity.first) || !seenIdentities.add(identity)) {
                    throw IOException("The library backup directory topology is invalid")
                }
                device = identity.first
                body(HeldLibraryDirectory(id, it, identity))
            }
            fun child(parent: HeldLibraryDirectory, leaf: String, limit: Int): String {
                provider.requireDirectory(parent.id, parent.descriptor)
                if (exactChild(provider, parent.id, leaf, true, limit) != null) throw IOException("The backup folder name already exists")
                val created = provider.create(parent.id, Document.MIME_TYPE_DIR, leaf)
                if (exactChild(provider, parent.id, leaf, true, limit) != created) throw IOException("The new backup directory could not be verified")
                Os.fsync(parent.descriptor.fileDescriptor)
                provider.requireExactAccess()
                return created
            }
            return directory(provider.root) { selected ->
                val available = provider.capacity(selected.id, selected.descriptor)
                if (available < required) throw BackupCapacityBeforePublication()
                if (name == null) {
                    requireEmptyRoot(selected)
                    if (provider.children(selected.id, 1).isNotEmpty()) throw IOException("The selected folder is not empty")
                    AndroidLibraryDescriptorAncestry.requirePhysicallyEmpty(selected.descriptor)
                }
                recordIntent(selected.identity)
                fun build(root: HeldLibraryDirectory): T =
                    directory(child(root, "objects", 4)) { objects ->
                        directory(child(root, "catalogs", 4)) { catalogs ->
                            directory(child(root, "generations", 4)) { markers ->
                                directory(child(root, STAGING, 4)) { stage ->
                                    if (name == null) for (directory in listOf(objects, catalogs, markers, stage)) {
                                        AndroidLibraryDescriptorAncestry.requireDirectChild(directory.descriptor, root.descriptor)
                                    }
                                    val backup = AndroidNewLibraryBackup(provider, selected, root, stage, objects, catalogs, markers, journal)
                                    block(backup, available)
                                }
                            }
                        }
                    }
                if (name == null) {
                    requireEmptyRoot(selected)
                    AndroidLibraryDescriptorAncestry.requirePhysicallyEmpty(selected.descriptor)
                    build(selected)
                } else directory(child(selected, name, 2_048), ::build)
            }
        }

        private fun exactChild(provider: WritableTree, parent: String, name: String, directory: Boolean, limit: Int): String? {
            val atName = provider.children(parent, limit).filter { it.name == name || it.id == "$parent/$name" }
            if (atName.isEmpty()) return null
            val entry = atName.singleOrNull()
            if (entry == null || entry.name != name || entry.id != "$parent/$name" || entry.directory != directory) {
                throw IOException("The library destination name is ambiguous")
            }
            return entry.id
        }
    }
}

/** Internal marker emitted only before the backup child and its private intent. */
internal class BackupCapacityBeforePublication : IOException("Not enough space for the library backup")

internal fun backupSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
