package app.pristinepage.data.library

import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.files.PlatformDirectorySync
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicBoolean

/** Binds an app-owned closed stage under private scratch. Callers must retain exclusive ownership and know the ancestor's own directory entry is durable. */
internal class LocalLibraryMergeJournal(
    root: File, private val durableAncestor: Path, directorySync: DirectorySync = PlatformDirectorySync,
    private val nodeIdentity: (Path, BasicFileAttributes) -> Any = { _, attrs ->
        attrs.fileKey() ?: throw IOException("The private merge node cannot be identified")
    },
) {
    private val scratch = LocalLibraryDirectory(root, directorySync)
    val rootPath: Path get() = scratch.rootPath

    data class SelectedState(val intent: LibraryMergeIntent, val completed: Boolean)

    sealed interface SelectedCompletion {
        /** Only the issuing live writer may call this, after exact destination readback and provider scope closure. */
        fun recordCompletion(published: LibraryGeneration, checkpoint: () -> Unit = {}, requireOwner: () -> Unit = {})
    }

    /** Unlike the replayable local journal, this can only start in a freshly allocated selected attempt. */
    fun startSelected(intent: LibraryMergeIntent, checkpoint: () -> Unit = {}, requireOwner: () -> Unit = {}): SelectedCompletion {
        val id = intent.candidate.id
        requireOwner()
        scratch.requireFreshSelectedMergeStage(id)
        verifyStage(intent, checkpoint)
        scratch.forceMergeStage(id, durableAncestor, intent.candidate.catalog.byteCount)
        checkpoint()
        scratch.createMergeIntent(id, intent.encode()) {
            verifyStage(intent, checkpoint)
            requireOwner()
            checkpoint()
        }
        if (inspectSelected(id, checkpoint) != SelectedState(intent, false)) conflict()
        requireOwner()
        return LiveSelectedCompletion(intent)
    }

    /** Revalidate known private records without authorizing provider access, delivery, or another write. */
    fun inspectSelected(generation: String, checkpoint: () -> Unit = {}): SelectedState? {
        checkpoint()
        val evidence = scratch.selectedMergeEvidence(generation, nodeIdentity) ?: return null
        val state = readSelected(generation) ?: throw IOException("The selected merge retains evidence without a durable intent")
        val exactBytes = state.intent.candidate.catalog.byteCount
        if (evidence.intent == null || (evidence.completion != null) != state.completed || evidence.catalog?.byteCount != exactBytes) conflict()
        fun requireEvidence() {
            if (scratch.selectedMergeEvidence(generation, nodeIdentity) != evidence) conflict()
        }
        requireEvidence()
        scratch.forceMergeStage(generation, durableAncestor, exactBytes)
        verifyStage(state.intent, checkpoint)
        if (readSelected(generation) != state) conflict()
        requireEvidence()
        checkpoint()
        return state
    }

    private inner class LiveSelectedCompletion(private val intent: LibraryMergeIntent) : SelectedCompletion {
        private val spent = AtomicBoolean()

        override fun recordCompletion(published: LibraryGeneration, checkpoint: () -> Unit, requireOwner: () -> Unit) {
            if (!spent.compareAndSet(false, true) || published != intent.candidate) {
                throw IOException("The selected merge completion is spent or does not match the candidate")
            }
            val id = published.id
            fun requirePending() {
                requireOwner()
                if (inspectSelected(id, checkpoint) != SelectedState(intent, false)) conflict()
                requireOwner()
            }
            requirePending()
            scratch.createSelectedMergeCompletion(id, completionBytes(intent), ::requirePending)
            if (inspectSelected(id, checkpoint) != SelectedState(intent, true)) conflict()
            requireOwner()
        }
    }

    private fun readSelected(generation: String): SelectedState? {
        val intent = scratch.openMergeIntent(generation)?.use(LibraryMergeIntent::read)
        if (intent != null && intent.candidate.id != generation) conflict()
        val completed = scratch.openSelectedMergeCompletion(generation)?.use { receipt ->
            for (byte in completionBytes(intent ?: conflict())) {
                if (receipt.read() != (byte.toInt() and 255)) conflict()
            }
            if (receipt.read() != -1) conflict()
            true
        } ?: false
        return intent?.let { SelectedState(it, completed) }
    }

    private fun completionBytes(intent: LibraryMergeIntent): ByteArray =
        "pristinepage-selected-merge-complete-v1\n".toByteArray(Charsets.US_ASCII) + intent.encode()

    /** Only for the admitted journal consumer: verify the returned stream against the persisted exact candidate on each use. */
    fun openStage(generation: String) = scratch.openMergeStage(generation) ?: throw IOException("Private merge stage disappeared")

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
        openStage(intent.candidate.id).use { intent.candidate.verifyCatalog(it, checkpoint) }

    private fun conflict(): Nothing = throw IOException("Private merge intent conflicts with its stage")
}
