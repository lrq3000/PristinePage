package app.pristinepage.data.library

import app.pristinepage.data.library.LibraryTreeReadSource.Companion.MAX_GENERATION_ENTRIES
import app.pristinepage.data.library.LibraryTreeReadSource.Companion.MAX_OBJECT_ENTRIES
import app.pristinepage.data.library.LibraryTreeReadSource.Companion.MAX_ROOT_ENTRIES
import app.pristinepage.data.model.requireOpaqueId
import java.io.IOException

/** A fresh attempt may not adopt unresolved leaves or publish a generation the ordinary reader cannot enumerate. */
internal object LibrarySelectedAppendAdmission {
    private const val LEGACY_STAGE = "merge-staging"
    private const val STAGE_PREFIX = "$LEGACY_STAGE-"

    fun stagingName(operation: String): String {
        requireOpaqueId(operation)
        return STAGE_PREFIX + operation
    }

    /** Only report names for read-only inspection. A current-operation collision and any unexplained reserved name refuse. */
    fun retainedStagingNames(names: Iterable<String>, operation: String, committedIds: Set<String>): Set<String> {
        val fresh = stagingName(operation)
        return buildSet {
            for (name in names) {
                if (name.equals(LEGACY_STAGE, ignoreCase = true)) unresolved()
                if (!name.startsWith(STAGE_PREFIX, ignoreCase = true)) continue
                if (name == fresh || !name.startsWith(STAGE_PREFIX) ||
                    name.removePrefix(STAGE_PREFIX) !in committedIds || !add(name)
                ) unresolved()
            }
        }
    }

    fun requireSpaceForAppend(stagingPresent: Boolean, catalogPresent: Boolean, rootEntries: Int, catalogEntries: Int,
        objectEntries: Int, missingObjects: Int,
    ) {
        if (stagingPresent || catalogPresent) throw IOException("The selected library has an unresolved staging or catalog name")
        if (rootEntries !in 0 until MAX_ROOT_ENTRIES || catalogEntries !in 0 until MAX_GENERATION_ENTRIES ||
            objectEntries !in 0..MAX_OBJECT_ENTRIES || missingObjects !in 0..(MAX_OBJECT_ENTRIES - objectEntries)
        ) throw IOException("The selected library cannot admit the projected directory entries")
    }

    private fun unresolved(): Nothing = throw IOException("The selected library retains an unresolved staging namespace")
}
