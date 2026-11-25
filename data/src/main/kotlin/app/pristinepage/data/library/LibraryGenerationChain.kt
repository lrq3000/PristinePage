package app.pristinepage.data.library

import java.io.IOException

/** A detached observation of one bounded inventory. Appendability is a check, not permission to create a generation. */
internal class LibraryGenerationInventory(markers: List<LibraryGeneration>, private val entryCount: Int) {
    init {
        if (entryCount !in markers.size..2 * LibraryGenerationChain.MAX_MARKERS) {
            throw IOException("Library generation inventory exceeds the limit")
        }
    }

    private val markers = markers.toList()
    val tip = LibraryGenerationChain.tip(this.markers)

    fun requireExact(generation: LibraryGeneration) {
        if (generation !in markers) throw IOException("The exact retained library generation is missing or changed")
    }

    fun requireAppendable(candidate: LibraryGeneration) {
        requireVacantChildId(candidate.id)
        if (candidate.libraryId != tip.libraryId || candidate.predecessorId != tip.id) {
            throw IOException("The destination cannot admit the new library generation")
        }
    }

    fun requireVacantChildId(id: String) {
        // Budget both the marker and its staging leaf, even if cleanup of the latter is refused.
        if (entryCount > 2 * LibraryGenerationChain.MAX_MARKERS - 2 || markers.size == LibraryGenerationChain.MAX_MARKERS ||
            markers.any { it.id == id }
        ) {
            throw IOException("The destination cannot admit the new library generation")
        }
    }
}

/** Resolve an already bounded inventory of verified marker records. No timestamp, directory order, or newest visible partial write is selection authority. */
internal object LibraryGenerationChain {
    const val MAX_MARKERS = 1_024

    fun tip(markers: List<LibraryGeneration>): LibraryGeneration {
        if (markers.size !in 1..MAX_MARKERS) invalid()
        val byId = markers.associateBy(LibraryGeneration::id)
        if (byId.size != markers.size) invalid()
        val initial = markers.singleOrNull { it.predecessorId == null } ?: invalid()
        val childByParent = HashMap<String, LibraryGeneration>()
        for (marker in markers) {
            if (marker.libraryId != initial.libraryId) invalid()
            val parent = marker.predecessorId ?: continue
            if (parent !in byId || childByParent.put(parent, marker) != null) invalid()
        }
        var current = initial
        var visited = 1
        while (true) {
            current = childByParent[current.id] ?: break
            if (++visited > markers.size) invalid()
        }
        if (visited != markers.size) invalid()
        return current
    }

    private fun invalid(): Nothing = throw IOException("Library generation chain is incomplete or ambiguous")
}
