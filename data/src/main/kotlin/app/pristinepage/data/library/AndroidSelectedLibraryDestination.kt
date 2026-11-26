package app.pristinepage.data.library

import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.system.Os
import app.pristinepage.data.library.AndroidLibraryDescriptorAncestry.Observation
import app.pristinepage.data.library.AndroidLibraryWritableFolder.WritableTree
import app.pristinepage.data.library.LibraryObjectTransfer.ObjectId
import app.pristinepage.data.library.LibraryTreeReadSource.Companion.MAX_GENERATION_ENTRIES
import app.pristinepage.data.library.LibraryTreeReadSource.Companion.MAX_OBJECT_ENTRIES
import app.pristinepage.data.library.LibraryTreeReadSource.Companion.MAX_ROOT_ENTRIES
import app.pristinepage.data.model.requireOpaqueId
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Path

/** A cooperating selected-root attempt. It may add immutable leaves, never replace or remove existing ones. */
internal class AndroidSelectedLibraryDestination private constructor(
    private val provider: WritableTree,
    private val root: HeldLibraryDirectory,
    private val objects: HeldLibraryDirectory,
    private val catalogs: HeldLibraryDirectory,
    private val generations: HeldLibraryDirectory,
    private val scratch: Path,
    private val spend: LocalLibraryMoveAuthorization,
    private val requireRoots: () -> Unit,
    private val successor: LibraryGeneration,
) : LibraryMergePublication.Destination {
    private val rootObservation = AndroidLibraryDescriptorAncestry.observe(root.descriptor)
    private var stagingObservation: Observation? = null
    private val originalNames = listing(objects, MAX_OBJECT_ENTRIES, requireFiles = false).associateByTo(HashMap()) { it.name }
    private val acknowledged = HashMap<String, Pair<Long, Long>>()

    override fun requireIndependentScratch(scratch: Path) {
        if (scratch != this.scratch) throw IOException("Selected library publication needs its independent private scratch")
        requireRoots()
    }

    override fun generationInventory(checkpoint: () -> Unit): LibraryGenerationInventory {
        val items = listing(generations, MAX_GENERATION_ENTRIES, requireFiles = false)
        val markers = items.filter { it.name.endsWith(".complete") }.map { entry ->
            checkpoint()
            val id = entry.name.removeSuffix(".complete")
            try { requireOpaqueId(id) } catch (_: IllegalArgumentException) { throw IOException("The selected library marker name is invalid") }
            val marker = open(generations, entry).use { LibraryGeneration.read(it, checkpoint) }
            if (marker.id != id) throw IOException("The selected library marker name changed")
            marker
        }
        return LibraryGenerationInventory(markers, items.size)
    }

    override fun open(id: ObjectId) = originalNames[id.sha256]?.let { open(objects, it) }

    fun requireReady(originals: Set<String>) {
        val rootRows = listing(root, MAX_ROOT_ENTRIES, requireFiles = false)
        val catalogRows = listing(catalogs, MAX_GENERATION_ENTRIES, requireFiles = false)
        val objectRows = listing(objects, MAX_OBJECT_ENTRIES, requireFiles = false)
        if (objectRows.associateBy { it.name } != originalNames) throw IOException("The selected library objects changed during admission")
        LibrarySelectedAppendAdmission.requireSpaceForAppend(rootRows.any { it.name == STAGING },
            catalogRows.any { it.name == "${successor.id}.sqlite" }, rootRows.size, catalogRows.size, objectRows.size,
            originals.count { it !in originalNames })
        generationInventory(provider.checkpoint).requireAppendable(successor)
    }

    override fun openCatalog(generation: LibraryGeneration) = find(catalogs, "${generation.id}.sqlite")?.let {
        if (generation.id == successor.id && it.id !in acknowledged) throw IOException("The selected child catalog is not owned by this attempt")
        open(catalogs, it)
    }

    override fun openMarker(generation: LibraryGeneration) = find(generations, "${generation.id}.complete")?.let { open(generations, it) }

    override fun create(id: ObjectId, requireCurrent: () -> Unit, write: (OutputStream) -> Unit) {
        val entry = publish(objects, id.sha256, id, requireCurrent, requireCurrent, write)
        originalNames[id.sha256] = entry
    }

    override fun createCatalog(generation: LibraryGeneration, write: (OutputStream) -> Unit) {
        publish(catalogs, "${generation.id}.sqlite", generation.catalog, requireRoots, requireRoots, write)
    }

    override fun createMarker(generation: LibraryGeneration, bytes: ByteArray): Nothing =
        throw IOException("Selected library markers require an admitted merge guard")

    override fun createMergeMarker(generation: LibraryGeneration, bytes: ByteArray, requireStage: () -> Unit, requireCurrent: () -> Unit) {
        require(bytes.contentEquals(generation.encode()))
        publish(generations, "${generation.id}.complete", ObjectId(backupSha256(bytes), bytes.size.toLong()),
            requireStage, requireCurrent) { it.write(bytes) }
    }

    override fun confirmPayloadDurability(generation: LibraryGeneration, requiresObjects: Boolean) {
        sync(objects)
        sync(catalogs)
        sync(root)
    }

    override fun confirmMarkerDurability(generation: LibraryGeneration) {
        sync(generations)
        sync(root)
    }

    private fun publish(parent: HeldLibraryDirectory, name: String, id: ObjectId, requireStage: () -> Unit,
        requireCurrent: () -> Unit, write: (OutputStream) -> Unit,
    ): LibraryTreeReadSource.Entry {
        if (find(parent, name) != null) throw IOException("The selected library final name already exists")
        requireStage()
        val staged = find(root, STAGING, isDirectory = true)
        if ((staged == null) != (stagingObservation == null)) throw IOException("The selected library stage was not created by this attempt")
        val stageId = staged?.id ?: provider.create(root.id, Document.MIME_TYPE_DIR, STAGING).also {
            if (find(root, STAGING, isDirectory = true)?.id != it) throw IOException("The selected library stage was not created exactly")
            sync(root)
        }
        return provider.openDirectory(stageId).use { descriptor ->
            val seen = AndroidLibraryDescriptorAncestry.observe(descriptor)
            val stage = HeldLibraryDirectory(stageId, descriptor, provider.directoryIdentity(descriptor))
            if (seen.mount != rootObservation.mount || stage.identity.first != root.identity.first ||
                stage.identity in setOf(root.identity, objects.identity, catalogs.identity, generations.identity) ||
                (stagingObservation != null && stagingObservation != seen)
            ) throw IOException("The selected library stage identity changed")
            stagingObservation = seen
            if (listing(stage, 1, requireFiles = true).isNotEmpty()) throw IOException("The selected library has unresolved staging files")
            val writer = AndroidLibraryLeafWriter(provider, root, root, stage, spend,
                { directory, leaf -> find(directory, leaf)?.id }, ::sync)
            val finalIdentity = writer.publish(parent, name, id,
                requireStage = { requireStage(); requireBound(stage); requireBound(parent) },
                requireVisibility = { requireCurrent(); requireBound(stage); requireBound(parent) }, write = write)
            val entry = find(parent, name) ?: throw IOException("The published selected library file disappeared")
            acknowledged[entry.id] = finalIdentity
            entry
        }
    }

    private fun open(parent: HeldLibraryDirectory, entry: LibraryTreeReadSource.Entry): ParcelFileDescriptor.AutoCloseInputStream {
        requireBound(parent)
        if (entry.directory || entry.id != "${parent.id}/${entry.name}") throw IOException("The selected library leaf is invalid")
        val input = ParcelFileDescriptor.AutoCloseInputStream(provider.open(entry.id, "r"))
        try {
            val seen = AndroidLibraryLeafWriter.fileIdentity(input.fd, root.identity.first, checkpoint = provider.checkpoint)
            if (acknowledged[entry.id]?.let { it != seen } == true) throw IOException("The published selected library file changed")
            requireBound(parent)
            return input
        } catch (failure: Throwable) {
            try { input.close() } catch (closeFailure: Exception) { failure.addSuppressed(closeFailure) }
            throw failure
        }
    }

    private fun find(parent: HeldLibraryDirectory, name: String, isDirectory: Boolean = false): LibraryTreeReadSource.Entry? {
        val exact = listing(parent, when (parent) { root -> MAX_ROOT_ENTRIES; objects -> MAX_OBJECT_ENTRIES; catalogs, generations -> MAX_GENERATION_ENTRIES; else -> 1 },
            requireFiles = false).singleOrNull { it.name == name } ?: return null
        if (exact.directory != isDirectory) throw IOException("The selected library name has an unexpected type")
        return exact
    }

    private fun listing(parent: HeldLibraryDirectory, maximum: Int, requireFiles: Boolean): List<LibraryTreeReadSource.Entry> {
        requireBound(parent)
        return checkedChildren(provider, parent, maximum).also { rows ->
            if (requireFiles && rows.any { it.directory }) throw IOException("The selected library stage contains a directory")
            requireBound(parent)
        }
    }

    private fun requireBound(directory: HeldLibraryDirectory) {
        requireRoots()
        provider.requireDirectory(root.id, root.descriptor)
        if (directory != root) provider.openDirectory(directory.id).use {
            if (AndroidLibraryDescriptorAncestry.observe(it) != AndroidLibraryDescriptorAncestry.observe(directory.descriptor)) {
                throw IOException("The selected library namespace changed")
            }
            AndroidLibraryDescriptorAncestry.requireDirectChild(it, root.descriptor)
        }
    }

    private fun sync(directory: HeldLibraryDirectory) {
        requireBound(directory)
        Os.fsync(directory.descriptor.fileDescriptor)
        requireBound(directory)
    }

    companion object {
        // Leave an exported backup's staging namespace untouched. This qualification expects a fresh merge namespace.
        private const val STAGING = "merge-staging"
        fun <T> use(provider: WritableTree, expectedRoot: Observation, scratch: Path, spend: LocalLibraryMoveAuthorization, successor: LibraryGeneration,
            requireRoots: () -> Unit, block: (AndroidSelectedLibraryDestination) -> T,
        ): T {
            val seen = HashSet<Pair<Long, Long>>()
            fun <R> directory(id: String, body: (HeldLibraryDirectory) -> R): R = provider.openDirectory(id).use {
                val current = AndroidLibraryDescriptorAncestry.observe(it)
                val identity = provider.directoryIdentity(it)
                if ((id == provider.root && current != expectedRoot) || current.mount != expectedRoot.mount ||
                    current.identity.device != expectedRoot.identity.device || !seen.add(identity)
                ) throw IOException("The selected writable library topology is invalid")
                body(HeldLibraryDirectory(id, it, identity))
            }
            return directory(provider.root) { root ->
                requireRoots()
                val children = checkedChildren(provider, root, MAX_ROOT_ENTRIES).associateBy { it.name }
                fun required(name: String) = children[name]?.takeIf { it.directory }?.id
                    ?: throw IOException("The selected library is missing a writable namespace")
                directory(required("objects")) { objects ->
                    directory(required("catalogs")) { catalogs ->
                        directory(required("generations")) { generations ->
                            val destination = AndroidSelectedLibraryDestination(provider, root, objects, catalogs, generations,
                                scratch, spend, requireRoots, successor)
                            for (namespace in listOf(objects, catalogs, generations)) destination.requireBound(namespace)
                            block(destination)
                        }
                    }
                }
            }
        }

        private fun checkedChildren(provider: WritableTree, parent: HeldLibraryDirectory, maximum: Int): List<LibraryTreeReadSource.Entry> {
            val rows = provider.children(parent.id, maximum)
            if (rows.any { it.id != "${parent.id}/${it.name}" } || rows.map { it.name }.toSet().size != rows.size) {
                throw IOException("The selected library names are ambiguous")
            }
            return rows
        }
    }
}
