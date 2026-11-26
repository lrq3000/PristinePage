package app.pristinepage.data.library

import app.pristinepage.data.library.LibraryTreeReadSource.Companion.MAX_GENERATION_ENTRIES
import app.pristinepage.data.library.LibraryTreeReadSource.Companion.MAX_OBJECT_ENTRIES
import app.pristinepage.data.library.LibraryTreeReadSource.Companion.MAX_ROOT_ENTRIES
import java.io.IOException

/** A fresh attempt may not adopt unresolved leaves or publish a generation the ordinary reader cannot enumerate. */
internal object LibrarySelectedAppendAdmission {
    fun requireSpaceForAppend(stagingPresent: Boolean, catalogPresent: Boolean, rootEntries: Int, catalogEntries: Int,
        objectEntries: Int, missingObjects: Int,
    ) {
        if (stagingPresent || catalogPresent) throw IOException("The selected library has an unresolved staging or catalog name")
        if (rootEntries !in 0 until MAX_ROOT_ENTRIES || catalogEntries !in 0 until MAX_GENERATION_ENTRIES ||
            objectEntries !in 0..MAX_OBJECT_ENTRIES || missingObjects !in 0..(MAX_OBJECT_ENTRIES - objectEntries)
        ) throw IOException("The selected library cannot admit the projected directory entries")
    }
}
