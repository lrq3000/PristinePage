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

/** One canonical app-private root, under the same cooperative writer/history lane for the entire operation. */
internal class LocalLibraryBackupAttempts(
    private val files: Path,
    private val sync: DirectorySync = PlatformDirectorySync,
    private val checkpoint: () -> Unit = {},
) {
    private val archive = files.resolve("library-backup-acknowledged")

    fun find(id: String): Path? {
        requireRoot()
        val name = name(id)
        val archiveDirectory = existingArchive()
        if (archiveDirectory != null) {
            forceParents()
            if (!directoryPresent(archiveDirectory)) throw IOException("The archived backup root changed")
        }
        val active = files.resolve(name).takeIf(::directoryPresent)
        val archived = archiveDirectory?.resolve(name)?.takeIf(::directoryPresent)
        if (active != null && archived != null) throw IOException("The private backup attempt has two locations")
        return active ?: archived
    }

    fun requireNew(id: String): Path {
        if (find(id) != null) throw IOException("This backup operation cannot be repeated")
        if (atLimit(files, "library-out-*", MAX_ACTIVE)) throw IOException("Retained library export attempts need attention")
        if (existingArchive() != null && atLimit(archive, "*", MAX_ACKNOWLEDGED)) {
            throw IOException("The retained backup history needs attention")
        }
        return files.resolve(name(id))
    }

    /** A refusal here is private retention only, not a failed or retryable backup. */
    fun archiveAcknowledged(id: String, issuedAt: LibraryActivation): Boolean {
        val current = find(id) ?: return false
        val journal = LocalLibraryFolderExportJournal(current.toFile(), sync, checkpoint)
        val observed = journal.reopen(id) ?: return false
        if (observed.intent.issuedAt != issuedAt) throw IOException("The backup belongs to another activation")
        if (observed.phase != LocalLibraryFolderExportJournal.Phase.ACKNOWLEDGED) return false
        if (current.parent == archive) {
            requireArchiveInventory(current, beforeReclamation = false)
            return true
        }
        requireArchiveInventory(current, beforeReclamation = true)
        if (existingArchive() == null) Files.createDirectory(archive)
        requireRoot()
        if (!directoryPresent(archive) || atLimit(archive, "*", MAX_ACKNOWLEDGED)) throw IOException("The retained backup history needs attention")
        forceParents()
        if (!journal.reclaimAcknowledgedCatalog(id)) throw IOException("The backup acknowledgment is no longer available")
        requireArchiveInventory(current, beforeReclamation = false)
        val target = archive.resolve(name(id))
        checkpoint()
        // The lane owns and rechecks both namespaces. Never copy/delete to simulate a move or overwrite a prior terminal fence.
        if (directoryPresent(target) || !directoryPresent(current)) throw IOException("The private backup attempt changed before archival")
        Files.move(current, target, ATOMIC_MOVE)
        if (find(id) != target || LocalLibraryFolderExportJournal(target.toFile(), sync, checkpoint).reopen(id) != observed) {
            throw IOException("The archived backup acknowledgment could not be verified")
        }
        requireArchiveInventory(target, beforeReclamation = false)
        return true
    }

    private fun requireRoot() {
        if (!directoryPresent(files)) throw IOException("Private backup storage is unavailable")
    }

    private fun existingArchive(): Path? = archive.takeIf(::directoryPresent)

    private fun directoryPresent(path: Path): Boolean {
        val attrs = try { Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
        catch (_: NoSuchFileException) { return false }
        if (!attrs.isDirectory || path.toRealPath() != path) throw IOException("A private backup path is not an owned directory")
        return true
    }

    private fun forceParents() {
        if (!sync.sync(archive.toFile()) || !sync.sync(files.toFile())) throw IOException("Private backup history durability is unavailable")
    }

    /** A completed operation may still have private spill from refused cleanup. Preserve it and its active slot; do not archive it by name. */
    private fun requireArchiveInventory(attempt: Path, beforeReclamation: Boolean) {
        try {
            var count = 0
            Files.newDirectoryStream(attempt).use { entries ->
                for (entry in entries) {
                    checkpoint()
                    if (++count > RoomLibrarySavedCatalog.MAX_PAGES + 10) throw IOException("The backup attempt exceeds private retention limits")
                    val attrs = Files.readAttributes(entry, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    val name = entry.fileName.toString()
                    val known = when {
                        name in catalogNames -> beforeReclamation && attrs.size() in 1..RoomLibraryCatalogReader.MAX_PORTABLE_BYTES
                        SPEND_NAME.matches(name) -> attrs.size() in 1..512
                        name in journalNames || name.startsWith(".pristinepage-") && name.endsWith(".part") ->
                            attrs.size() in 1..5_000 // Journal reopen separately proves each surviving hard-link stage.
                        else -> false
                    }
                    if (!attrs.isRegularFile || !known) throw IOException("The backup attempt retains unexplained private files")
                }
            }
        } catch (_: DirectoryIteratorException) { throw IOException("The retained backup attempt could not be read") }
    }

    private fun atLimit(path: Path, pattern: String, limit: Int): Boolean = try {
        Files.newDirectoryStream(path, pattern).use { it.asSequence().take(limit).count() == limit }
    } catch (_: DirectoryIteratorException) { throw IOException("The retained backup attempts could not be read") }

    private fun name(id: String): String {
        requireOpaqueId(id)
        return "library-out-$id"
    }

    private companion object {
        const val MAX_ACTIVE = 4
        const val MAX_ACKNOWLEDGED = 128
        val SPEND_NAME = Regex("[a-f0-9]{64}\\.spent")
        val catalogNames = setOf("catalog.sqlite", "export.catalog.owner")
        val journalNames = LocalLibraryDirectory.ExportRecord.entries.map { it.nameOnDisk }.toSet()
    }
}
