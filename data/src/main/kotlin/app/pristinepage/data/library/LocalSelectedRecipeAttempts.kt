package app.pristinepage.data.library

import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.files.PlatformDirectorySync
import app.pristinepage.data.model.requireOpaqueId
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.BasicFileAttributes

/** Private history only. The caller owns the canonical app-files root and the cooperating selected-write lane. */
internal class LocalSelectedRecipeAttempts(
    private val files: Path,
    private val sync: DirectorySync = PlatformDirectorySync,
    private val checkpoint: () -> Unit = {},
    private val nodeIdentity: (Path, BasicFileAttributes) -> Any,
) {
    private val active = files.resolve("catalog-recipe-attempts-v1")
    private val history = files.resolve("catalog-recipe-acknowledged-v1")

    /** An absent attempt reports inventory only. The caller must still check the separate revision spend. */
    fun find(id: String): Path? {
        requireOpaqueId(id)
        directoryKey(files) ?: unavailable()
        val live = locate(active, id)
        val archived = locate(history, id)
        if (live != null && archived != null) unavailable()
        return live ?: archived
    }

    fun requireNew(id: String): Path {
        requireUnused(id)
        val parents = parentKeys()
        // A rename can become visible before fsync. Never spend a different operation's newly visible slot until it is durable.
        forceParents(parents)
        if (find(id) != null) unavailable()
        requireCapacity(active, 4)
        requireCapacity(history, 128)
        requireParents(parents)
        return active.resolve(id)
    }

    private fun requireUnused(id: String) {
        requireOpaqueId(id)
        LocalLibraryDirectory(files.toFile(), sync).requireUnspentCatalogRevision(id)
        if (find(id) != null) throw IOException("The selected recipe operation cannot be repeated")
    }

    /** Resolve outstanding cleanup for exactly the current, adopted edit before another operation can enter Files. */
    suspend fun prepareFollowingEdit(
        selected: LocalSelectedLibraryAuthority.Selected.Folder, operation: String,
        readPredecessor: () -> LocalSelectedLibraryAuthority.Selected.Folder?,
        verifySpend: (LocalLibraryRecipeEditJournal.Intent) -> Unit,
        readCurrent: () -> LocalSelectedLibraryAuthority.Selected?,
        verifyCurrentChild: suspend (LocalLibraryRecipeEditJournal.Intent) -> Unit,
        readArchivedPredecessor: (Long, String, LibraryGeneration) -> LocalSelectedLibraryAuthority.Selected.Folder? = { _, _, _ -> null },
        requireOwner: () -> Unit,
    ): Path {
        requireOwner()
        if (readCurrent() != selected) unavailable()
        requireOwner()
        requireUnused(operation)
        val generation = selected.expectedGeneration
        if (operation == generation.id || operation == generation.predecessorId) unavailable()
        settleCurrentForPublication(selected, readPredecessor, verifySpend, readCurrent, verifyCurrentChild, readArchivedPredecessor, requireOwner)
        val attempt = requireNew(operation)
        requireOwner()
        if (readCurrent() != selected) unavailable()
        requireOwner()
        return attempt
    }

    /** Check or settle the exact current recipe before another writer replaces its active pointer. This reserves no recipe slot. */
    suspend fun settleCurrentForPublication(
        selected: LocalSelectedLibraryAuthority.Selected.Folder,
        readPredecessor: () -> LocalSelectedLibraryAuthority.Selected.Folder?,
        verifySpend: (LocalLibraryRecipeEditJournal.Intent) -> Unit,
        readCurrent: () -> LocalSelectedLibraryAuthority.Selected?,
        verifyCurrentChild: suspend (LocalLibraryRecipeEditJournal.Intent) -> Unit,
        readArchivedPredecessor: (Long, String, LibraryGeneration) -> LocalSelectedLibraryAuthority.Selected.Folder?,
        requireOwner: () -> Unit,
    ) {
        fun requireSelected() {
            requireOwner()
            if (readCurrent() != selected) unavailable()
            requireOwner()
        }
        requireSelected()
        val generation = selected.expectedGeneration
        val currentAttempt = find(generation.id)
        if (currentAttempt == null) {
            // A matching private spend with neither attempt present is not an empty history.
            LocalLibraryDirectory(files.toFile(), sync).requireUnspentCatalogRevision(generation.id)
        } else if (currentAttempt.parent == active) {
            val prior = readPredecessor() ?: unavailable()
            val proof = open(currentAttempt).observeForRecovery(prior)
            val intent = proof.intent
            if (proof.phase == LocalLibraryRecipeEditJournal.Phase.INTENT_ONLY || intent.adoptedSelection() != selected) unavailable()
            verifySpend(intent)
            requireSelected()
            verifyCurrentChild(intent)
            if (!settleRecordedAdoption(intent, selected, readCurrent, requireOwner)) unavailable()
        } else {
            val intent = open(currentAttempt).readArchivedIntent(readArchivedPredecessor)
            if (intent.successor != generation || intent.adoptedSelection().selection.identity.activation > selected.selection.identity.activation) unavailable()
            verifySpend(intent)
            requireArchiveEvidence(intent, ::requireSelected)
        }
        requireSelected()
    }

    private fun requireArchiveEvidence(intent: LocalLibraryRecipeEditJournal.Intent, requireSelected: () -> Unit) {
        val target = history.resolve(intent.successor.id)
        val parents = parentKeys()
        if (parents[history] == null || parents[active] == null || find(intent.successor.id) != target) unavailable()
        val before = open(target).reclaimedEvidence(intent)
        forceParents(parents)
        if (find(intent.successor.id) != target || open(target).reclaimedEvidence(intent) != before) unavailable()
        requireParents(parents)
        requireSelected()
    }

    /** False is unresolved private cleanup, not a judgment about the already-adopted pointer or the provider write. */
    fun settleAdopted(
        intent: LocalLibraryRecipeEditJournal.Intent,
        adopted: LocalSelectedLibraryAuthority.Selected.Folder,
        requireCurrent: () -> Unit,
    ): Boolean = try {
        if (adopted != intent.adoptedSelection()) unavailable()
        requireCurrent()
        val path = find(intent.successor.id) ?: unavailable()
        if (path.parent == active) {
            val journal = open(path)
            journal.acknowledgeAdopted(intent, adopted, requireCurrent)
            journal.reclaimAdoptedCatalog(intent, adopted, requireCurrent)
        }
        archiveReclaimed(intent, adopted, requireCurrent)
        true
    } catch (_: IOException) { false }
    catch (_: DirectoryIteratorException) { false }

    fun settleRecordedAdoption(
        intent: LocalLibraryRecipeEditJournal.Intent,
        adopted: LocalSelectedLibraryAuthority.Selected.Folder,
        readCurrent: () -> LocalSelectedLibraryAuthority.Selected?,
        requireOwner: () -> Unit,
    ): Boolean {
        fun requireAdopted() {
            requireOwner()
            if (readCurrent() != adopted) unavailable()
            requireOwner()
        }
        val settled = settleAdopted(intent, adopted, ::requireAdopted)
        // A cleanup error may itself have been a pointer/lease refusal. Do not return a stale adoption as merely pending cleanup.
        requireAdopted()
        return settled
    }

    fun archiveReclaimed(
        intent: LocalLibraryRecipeEditJournal.Intent,
        adopted: LocalSelectedLibraryAuthority.Selected.Folder,
        requireCurrent: () -> Unit,
    ): Path {
        val id = intent.successor.id
        if (adopted != intent.adoptedSelection()) unavailable()
        requireCurrent()
        val current = find(id) ?: unavailable()
        val target = history.resolve(id)
        var parents = parentKeys()
        val before = open(current).reclaimedEvidence(intent)
        requireParents(parents)
        if (current != target) {
            requireCapacity(history, 128)
            if (parents[history] == null) {
                requireCurrent()
                Files.createDirectory(history)
                parents = parents + (history to (directoryKey(history) ?: unavailable()))
            }
        }
        if (parents[active] == null || parents[history] == null) unavailable()
        forceParents(parents)
        if (find(id) != current || open(current).reclaimedEvidence(intent) != before) unavailable()
        if (current != target) {
            if (!Files.notExists(target, NOFOLLOW_LINKS)) unavailable()
            requireParents(parents)
            requireCurrent()
            Files.move(current, target, ATOMIC_MOVE)
            forceParents(parents)
            if (find(id) != target || open(target).reclaimedEvidence(intent) != before) unavailable()
        }
        requireParents(parents)
        requireCurrent()
        return target
    }

    private fun open(path: Path) = LocalLibraryRecipeEditJournal(path.toFile(), sync, checkpoint, nodeIdentity)

    private fun parentKeys(): Map<Path, Any?> {
        val owner = directoryKey(files) ?: unavailable()
        return mapOf(history to directoryKey(history), active to directoryKey(active), files to owner)
    }

    private fun requireParents(parents: Map<Path, Any?>) {
        if (parentKeys() != parents) unavailable()
    }

    private fun forceParents(parents: Map<Path, Any?>) {
        requireParents(parents)
        for ((path, key) in parents) {
            if (key != null && !sync.sync(path.toFile())) unavailable()
            requireParents(parents)
        }
    }

    private fun locate(namespace: Path, id: String): Path? {
        if (directoryKey(namespace) == null) return null
        return namespace.resolve(id).takeIf { directoryKey(it) != null }
    }

    private fun requireCapacity(namespace: Path, limit: Int) {
        if (directoryKey(namespace) == null) return
        try {
            Files.newDirectoryStream(namespace).use { entries ->
                for ((index, entry) in entries.withIndex()) {
                    checkpoint()
                    if (index + 1 >= limit || directoryKey(entry) == null) unavailable()
                    try { requireOpaqueId(entry.fileName.toString()) }
                    catch (_: IllegalArgumentException) { unavailable() }
                }
            }
        } catch (_: DirectoryIteratorException) { unavailable() }
    }

    private fun directoryKey(path: Path): Any? {
        val attrs = try { Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
        catch (_: NoSuchFileException) { return null }
        if (!attrs.isDirectory || path.toRealPath() != path) unavailable()
        return nodeIdentity(path, attrs)
    }

    private fun unavailable(): Nothing = throw IOException("Private selected recipe history cannot be verified")
}
