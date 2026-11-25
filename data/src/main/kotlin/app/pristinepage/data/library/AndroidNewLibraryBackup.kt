package app.pristinepage.data.library

import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.system.Os
import android.system.OsConstants
import app.pristinepage.core.io.transferExactBytes
import app.pristinepage.data.library.AndroidLibraryWritableFolder.WritableTree
import app.pristinepage.data.library.LibraryObjectTransfer.ObjectId
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest

/** One newly created backup child, one active staged file, and no append, retry, overwrite or delete authority. */
internal class AndroidNewLibraryBackup private constructor(
    private val provider: WritableTree,
    private val selected: Directory,
    private val root: Directory,
    private val stage: Directory,
    private val objects: Directory,
    private val catalogs: Directory,
    private val generations: Directory,
    private val journal: LocalLibraryMoveAuthorization,
) : LibraryObjectTransfer.Store, LibraryCatalogPublication.Store {
    private val checkpoint = provider.checkpoint
    private data class Directory(val id: String, val descriptor: ParcelFileDescriptor, val identity: Pair<Long, Long>)
    private data class Leaf(val parent: String, val name: String)
    private val acknowledged = mutableMapOf<Leaf, Pair<Long, Long>>()

    override fun open(id: ObjectId) = open(objects, id.sha256)

    override fun create(id: ObjectId, write: (OutputStream) -> Unit) = publish(objects, id.sha256, id, write)

    override fun openCatalog(generation: LibraryGeneration) = open(catalogs, "${generation.id}.sqlite")

    override fun createCatalog(generation: LibraryGeneration, write: (OutputStream) -> Unit) =
        publish(catalogs, "${generation.id}.sqlite", generation.catalog, write)

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

    private fun open(parent: Directory, name: String): ParcelFileDescriptor.AutoCloseInputStream? {
        requireBound(parent)
        val identity = acknowledged[Leaf(parent.id, name)]
        // The folder started empty and this writer never resumes an existing folder. The move independently proves absence in the provider listing before it writes.
        if (identity == null) return null
        val input = ParcelFileDescriptor.AutoCloseInputStream(provider.open("${parent.id}/$name", "r"))
        try {
            if (fileIdentity(input) != identity) throw IOException("The published library file identity changed")
            provider.requireExactAccess()
            return input
        } catch (failure: Throwable) {
            try { input.close() } catch (closeFailure: Exception) { failure.addSuppressed(closeFailure) }
            throw failure
        }
    }

    private fun publish(parent: Directory, name: String, wanted: ObjectId, write: (OutputStream) -> Unit) {
        val leaf = Leaf(parent.id, name)
        if (acknowledged.containsKey(leaf) || find(parent, name) != null || find(stage, name) != null) {
            throw IOException("The new library file would replace an existing name")
        }
        val id = provider.create(stage.id, "application/octet-stream", name)
        if (find(stage, name) != id) throw IOException("The provider staging file is not at the expected name")
        val staged = ParcelFileDescriptor.AutoCloseInputStream(provider.open(id, "r"))
        staged.use { anchor ->
            val identity = fileIdentity(anchor)
            // "w" may truncate before the descriptor can be checked. Never overwrite even a replacement of the isolated staged name.
            ParcelFileDescriptor.AutoCloseOutputStream(provider.open(id, "rw")).use { output ->
                if (regularIdentity(output.fd, requireEmpty = true) != identity) throw IOException("The library staging writer changed files")
                write(output)
                checkpoint()
                output.flush()
                Os.fsync(output.fd)
            }
            val proof = proof(anchor, wanted)
            sync(stage)
            val key = backupSha256("${parent.id.substringAfterLast('/')}/$name".toByteArray(Charsets.US_ASCII))
            val parents = backupSha256(listOf(selected, root, stage, parent).joinToString("\n") { "${it.id}\t${it.identity}" }.toByteArray())
            val publisher = LibraryNamedPublication(object : LibraryNamedPublication.Store {
                override fun readStage() = observe(stage, name, wanted, anchor)
                override fun readFinal() = observe(parent, name, wanted, anchor)
                override fun consumeMoveAuthorization() = journal.consume(key, parents, proof)
                override fun moveOnce() = provider.move(id, stage.id, parent.id)
                override fun syncStageParent() = sync(stage)
                override fun syncFinalParent() = sync(parent)
            }, checkpoint)
            publisher.commit(proof, reconcileOnly = false)
            acknowledged[leaf] = identity
        }
    }

    private fun observe(parent: Directory, name: String, expected: ObjectId, anchor: ParcelFileDescriptor.AutoCloseInputStream): LibraryNamedPublication.FileProof? {
        val id = find(parent, name) ?: return null
        ParcelFileDescriptor.AutoCloseInputStream(provider.open(id, "r")).use {
            if (fileIdentity(anchor) != fileIdentity(it)) throw IOException("The moved library file did not retain its staged identity")
            return proof(it, expected)
        }
    }

    private fun proof(input: ParcelFileDescriptor.AutoCloseInputStream, expected: ObjectId): LibraryNamedPublication.FileProof {
        val before = fileIdentity(input)
        transferExactBytes(input, null, expected.byteCount, expected.sha256, checkpoint)
        if (fileIdentity(input) != before) throw IOException("The library file identity changed during readback")
        provider.requireExactAccess()
        return LibraryNamedPublication.FileProof("${before.first}:${before.second}", expected.sha256, expected.byteCount)
    }

    private fun fileIdentity(input: ParcelFileDescriptor.AutoCloseInputStream) = regularIdentity(input.fd)

    private fun regularIdentity(fd: java.io.FileDescriptor, requireEmpty: Boolean = false): Pair<Long, Long> {
        checkpoint()
        val stat = Os.fstat(fd)
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_ino <= 0 || stat.st_nlink != 1L || stat.st_dev != root.identity.first ||
            (requireEmpty && stat.st_size != 0L)
        ) {
            throw IOException("The library file has no exclusive regular-file identity")
        }
        return stat.st_dev to stat.st_ino
    }

    private fun find(parent: Directory, name: String): String? {
        requireBound(parent)
        return exactChild(provider, parent.id, name, false, namespaceLimit(parent))
    }

    private fun namespaceLimit(parent: Directory) = if (parent == objects) RoomLibrarySavedCatalog.MAX_PAGES else 4

    private fun requireBound(directory: Directory) {
        provider.requireDirectory(selected.id, selected.descriptor)
        if (directory != selected) provider.requireDirectory(root.id, root.descriptor)
        if (directory != selected && directory != root) provider.requireDirectory(directory.id, directory.descriptor)
    }

    private fun sync(directory: Directory) {
        requireBound(directory)
        Os.fsync(directory.descriptor.fileDescriptor)
        provider.requireExactAccess()
    }

    companion object {
        private const val STAGING = "staging"

        /** Nothing can write until capacity and the caller's private durable operation are acknowledged on this held parent. */
        fun <T> create(
            provider: WritableTree, name: String, required: Long, privateIdentities: Set<Pair<Long, Long>>,
            journal: LocalLibraryMoveAuthorization, recordIntent: (Pair<Long, Long>) -> Unit,
            block: (AndroidNewLibraryBackup, Long) -> T,
        ): T {
            val seenIdentities = privateIdentities.toMutableSet()
            var device: Long? = null
            fun <R> directory(id: String, body: (Directory) -> R): R = provider.openDirectory(id).use {
                val identity = provider.directoryIdentity(it)
                if ((device != null && device != identity.first) || !seenIdentities.add(identity)) {
                    throw IOException("The library backup directory topology is invalid")
                }
                device = identity.first
                body(Directory(id, it, identity))
            }
            fun child(parent: Directory, leaf: String, limit: Int): String {
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
                if (available < required) throw IOException("Not enough space for the library backup")
                recordIntent(selected.identity)
                directory(child(selected, name, 2_048)) { root ->
                    directory(child(root, "objects", 4)) { objects ->
                        directory(child(root, "catalogs", 4)) { catalogs ->
                            directory(child(root, "generations", 4)) { markers ->
                                directory(child(root, STAGING, 4)) { stage ->
                                    val backup = AndroidNewLibraryBackup(provider, selected, root, stage, objects, catalogs, markers, journal)
                                    block(backup, available)
                                }
                            }
                        }
                    }
                }
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

internal fun backupSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
