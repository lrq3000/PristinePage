package app.pristinepage.data.library

import app.pristinepage.data.library.LibraryObjectTransfer.ObjectId
import app.pristinepage.data.model.requireOpaqueId
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path

/** One read attempt over an already authorized document tree. The backend keeps document IDs opaque and proves their tree membership and availability; this class does not obtain a grant or write to it. */
internal class LibraryTreeReadSource(private val rootId: String, private val backend: Backend) : LibraryBackupRead.Source {
    data class Entry(val id: String, val name: String, val directory: Boolean)

    interface Backend {
        fun requireIndependentScratch(scratch: Path)

        /** Return a complete listing or throw; bound both row count and total text before retention. A null cursor, incomplete results or provider error is not an empty directory. */
        fun list(parentId: String, maximum: Int, checkpoint: () -> Unit): List<Entry>

        /** Open the already enumerated ID within the exact tree. An inaccessible or disappeared child must throw. */
        fun open(id: String): InputStream
    }

    private data class Layout(val generations: String, val catalogs: String, val objects: String?)
    private data class Binding(val parent: String, val name: String, val directory: Boolean)

    private var layout: Layout? = null
    private var boundMarkers: Map<String, Entry>? = null
    private val bindings = HashMap<String, Binding>()
    private var bindingTextLength = 0L
    private val catalogIds = mutableMapOf<String, String>()
    private var objectEntries: Map<String, Entry>? = null
    private var checkActive: () -> Unit = {}

    override fun requireIndependentScratch(scratch: Path) {
        backend.requireIndependentScratch(scratch)
        layout = null
        boundMarkers = null
        bindings.clear()
        bindingTextLength = 0L
        catalogIds.clear()
        objectEntries = null
        checkActive = {}
    }

    override fun generationInventory(checkpoint: () -> Unit): LibraryGenerationInventory {
        checkActive = checkpoint
        val root = indexed(rootId, MAX_ROOT_ENTRIES) { it == "generations" || it == "catalogs" || it == "objects" }
        val current = Layout(
            root["generations"]?.directoryId() ?: throw IOException("Library generation directory is missing"),
            root["catalogs"]?.directoryId() ?: throw IOException("Library catalog directory is missing"),
            root["objects"]?.directoryId(),
        )
        if (layout != null && layout != current) throw IOException("Library tree directories changed")
        layout = current
        val entries = children(current.generations, MAX_GENERATION_ENTRIES)
        val markers = index(entries) { it.endsWith(".complete") }
        if (markers.size > LibraryGenerationChain.MAX_MARKERS) throw IOException("Library generation inventory exceeds the limit")
        if (boundMarkers != null && boundMarkers != markers) throw IOException("Library tree generation entries changed")
        val declared = markers.values.map { entry ->
            val generationId = entry.name.removeSuffix(".complete")
            try { requireOpaqueId(generationId) } catch (_: IllegalArgumentException) { throw IOException("Library marker name is invalid") }
            val marker = openEntry(entry, current).use { LibraryGeneration.read(it, checkActive) }
            if (marker.id != generationId) throw IOException("Library marker name does not match the generation")
            marker
        }
        return LibraryGenerationInventory(declared, entries.size).also { boundMarkers = markers }
    }

    override fun openCatalog(generation: LibraryGeneration): InputStream? {
        val current = requireLayout()
        val leaf = "${generation.id}.sqlite"
        val entry = indexed(current.catalogs, MAX_GENERATION_ENTRIES) { it == leaf }[leaf] ?: return null
        val prior = catalogIds.putIfAbsent(leaf, entry.id)
        if (prior != null && prior != entry.id) throw IOException("Library catalog identity changed")
        return openEntry(entry, current)
    }

    override fun open(id: ObjectId): InputStream? {
        val current = requireLayout()
        val parent = current.objects ?: return null
        val entries = objectEntries ?: indexed(parent, MAX_OBJECT_ENTRIES) { OBJECT_NAME.matches(it) }.also { objectEntries = it }
        val entry = entries[id.sha256] ?: return null
        return openEntry(entry, current)
    }

    private fun requireLayout() = layout ?: throw IOException("Library tree has no verified namespace inventory")

    private fun Entry.directoryId(): String = if (directory) id else throw IOException("Library namespace is not a directory")

    private fun openEntry(entry: Entry, current: Layout): InputStream {
        if (entry.directory || entry.id == rootId || entry.id == current.generations || entry.id == current.catalogs || entry.id == current.objects) {
            throw IOException("Library tree file identity is invalid")
        }
        checkActive()
        return backend.open(entry.id)
    }

    private fun indexed(parent: String, maximum: Int, managed: (String) -> Boolean): Map<String, Entry> =
        index(children(parent, maximum), managed)

    private fun children(parent: String, maximum: Int): List<Entry> {
        checkActive()
        val listed = backend.list(parent, maximum, checkActive)
        checkActive()
        if (listed.size > maximum) throw IOException("Library tree directory exceeds the limit")
        val ids = HashSet<String>()
        for (entry in listed) {
            checkActive()
            if (entry.id.isEmpty() || entry.id.length > 8_192 || entry.name.length !in 1..255 ||
                entry.id == rootId || entry.id == parent || !ids.add(entry.id)
            ) throw IOException("Library tree children have ambiguous identities")
            val binding = Binding(parent, entry.name, entry.directory)
            val previous = bindings[entry.id]
            if (previous == null) {
                // Bound retained identities across the entire attempt, including a changed directory during reread.
                bindingTextLength += entry.id.length + entry.name.length
                if (bindingTextLength > MAX_BINDING_TEXT) throw IOException("Library tree identity inventory exceeds the limit")
                bindings[entry.id] = binding
            } else if (previous != binding) throw IOException("Library tree child identity is ambiguous")
        }
        return listed
    }

    private fun index(entries: List<Entry>, managed: (String) -> Boolean): Map<String, Entry> {
        val result = LinkedHashMap<String, Entry>()
        for (entry in entries) {
            checkActive()
            if (managed(entry.name) && result.put(entry.name, entry) != null) {
                throw IOException("Library tree children have duplicate managed names")
            }
        }
        return result
    }

    internal companion object {
        const val MAX_ROOT_ENTRIES = 2_048
        const val MAX_GENERATION_ENTRIES = 2 * LibraryGenerationChain.MAX_MARKERS
        // Includes retained objects from preceding catalogs; excess entries require an explicitly qualified maintenance policy.
        const val MAX_OBJECT_ENTRIES = 16_384
        private const val MAX_BINDING_TEXT = 4L * 1024 * 1024
        private val OBJECT_NAME = Regex("[0-9a-f]{64}")
    }
}
