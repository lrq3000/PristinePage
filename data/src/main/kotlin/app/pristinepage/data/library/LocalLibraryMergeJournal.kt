package app.pristinepage.data.library

import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.files.PlatformDirectorySync
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path

/** Binds an app-owned closed stage under private scratch. Callers must retain exclusive ownership and know the ancestor's own directory entry is durable. */
internal class LocalLibraryMergeJournal(root: File, private val durableAncestor: Path, directorySync: DirectorySync = PlatformDirectorySync) {
    private val scratch = LocalLibraryDirectory(root, directorySync)

    fun publish(intent: LibraryMergeIntent, checkpoint: () -> Unit = {}): LibraryMergeIntent {
        val generation = intent.candidate.id
        val existing = reopen(generation, checkpoint)
        if (existing != null) {
            if (existing != intent) conflict()
            return existing
        }
        verifyStage(intent, checkpoint)
        scratch.forceMergeStage(generation, durableAncestor)
        checkpoint()
        try {
            scratch.createMergeIntent(generation, intent.encode())
        } catch (_: FileAlreadyExistsException) {
            // Do not overwrite. A known race can only settle on the exact same closed and durable intent.
        }
        val persisted = reopen(generation, checkpoint)
        if (persisted != intent) conflict()
        return persisted
    }

    fun reopen(generation: String, checkpoint: () -> Unit = {}): LibraryMergeIntent? {
        val intent = scratch.openMergeIntent(generation)?.use(LibraryMergeIntent::read) ?: return null
        if (intent.candidate.id != generation) conflict()
        verifyStage(intent, checkpoint)
        scratch.forceMergeStage(generation, durableAncestor)
        if (scratch.openMergeIntent(generation)?.use(LibraryMergeIntent::read) != intent) conflict()
        checkpoint()
        return intent
    }

    private fun verifyStage(intent: LibraryMergeIntent, checkpoint: () -> Unit) =
        scratch.openMergeStage(intent.candidate.id)?.use { intent.candidate.verifyCatalog(it, checkpoint) }
            ?: throw IOException("Private merge stage disappeared")

    private fun conflict(): Nothing = throw IOException("Private merge intent conflicts with its stage")
}
