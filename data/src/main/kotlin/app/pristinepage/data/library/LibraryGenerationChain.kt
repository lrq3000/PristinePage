package app.pristinepage.data.library

import java.io.IOException

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
