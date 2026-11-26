package app.pristinepage.data.library

import app.pristinepage.data.model.requireOpaqueId
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Requires the selected writer lane and a stable, app-private parent. It never reopens a provider or removes evidence. */
internal class LocalSelectedMergeAttempts(private val root: Path, private val openJournal: (Path) -> LocalLibraryMergeJournal) {
    data class Fresh(val journal: LocalLibraryMergeJournal, val stage: Path)

    /** Exact-ID refusal only. Absence neither admits a new write nor clears the rest of the namespace. */
    fun requireUnused(operation: String) {
        requireOpaqueId(operation)
        if (!directoryPresent(root)) return
        requireKnownNamespace()
        val stages = root.resolve("merge-stages")
        if (directoryPresent(stages) && directoryPresent(stages.resolve(operation))) {
            throw IOException("The selected merge operation cannot be repeated")
        }
    }

    fun allocate(operation: String, checkpoint: () -> Unit = {}, requireAuthority: () -> Unit = {}): Fresh {
        requireUnused(operation)
        val parent = checkNotNull(root.parent)
        if (!directoryPresent(parent)) throw IOException("Private library files are unavailable")
        fun createFresh(path: Path): Path {
            requireAuthority()
            checkpoint()
            return Files.createDirectory(path)
        }
        if (!directoryPresent(root)) createFresh(root)
        val stages = root.resolve("merge-stages")
        requireKnownNamespace()
        if (!directoryPresent(stages)) createFresh(stages)
        val journal = openJournal(root)
        if (journal.rootPath != root) throw IOException("The selected merge journal does not match the attempt root")
        val stage = stages.resolve(operation)
        if (directoryPresent(stage)) throw IOException("The selected merge operation cannot be repeated")
        try {
            Files.newDirectoryStream(stages).use { entries ->
                var retained = 0
                for (entry in entries) {
                    checkpoint()
                    if (++retained >= MAX_RETAINED) throw IOException("Retained selected merge attempts need attention")
                    val id = entry.fileName.toString()
                    try { requireOpaqueId(id) } catch (_: IllegalArgumentException) { throw unknownEvidence() }
                    if (id == operation || !directoryPresent(entry) || journal.inspectSelected(id, checkpoint)?.completed != true) {
                        throw IOException("An earlier selected merge is unresolved")
                    }
                    requireSettledInventory(entry, checkpoint)
                }
            }
        } catch (_: DirectoryIteratorException) { throw unknownEvidence() }
        return Fresh(journal, createFresh(stage))
    }

    private fun requireSettledInventory(attempt: Path, checkpoint: () -> Unit) {
        val knownRecords = HashSet<String>()
        val linkedRecords = HashSet<String>()
        var spends = 0
        Files.newDirectoryStream(attempt).use { entries ->
            for (entry in entries) {
                checkpoint()
                val attrs = Files.readAttributes(entry, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (!attrs.isRegularFile) throw unknownEvidence()
                val name = entry.fileName.toString()
                when {
                    name in RECORDS -> if (!knownRecords.add(name)) throw unknownEvidence()
                    SPEND.matches(name) -> if (++spends > RoomLibrarySavedCatalog.MAX_PAGES + 2 || attrs.size() !in 1..512) {
                        throw unknownEvidence()
                    }
                    name.startsWith(".pristinepage-") && name.endsWith(".part") -> {
                        // A previous force may have reached the final hard link without removing its own temporary name.
                        val matched = JOURNAL_RECORDS.singleOrNull { leaf ->
                            val final = attempt.resolve(leaf)
                            Files.isRegularFile(final, NOFOLLOW_LINKS) && Files.isSameFile(entry, final)
                        }
                        if (matched == null || !linkedRecords.add(matched)) throw unknownEvidence()
                    }
                    else -> throw unknownEvidence()
                }
            }
        }
        if (knownRecords != RECORDS) throw unknownEvidence()
    }

    private fun requireKnownNamespace() {
        try {
            Files.newDirectoryStream(root).use { entries ->
                val remaining = entries.iterator()
                if (remaining.hasNext() && (remaining.next().fileName.toString() != "merge-stages" || remaining.hasNext())) {
                    throw unknownEvidence()
                }
            }
        } catch (_: DirectoryIteratorException) { throw unknownEvidence() }
    }

    private fun directoryPresent(path: Path): Boolean {
        val attributes = try { Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
        catch (_: NoSuchFileException) { return false }
        if (!attributes.isDirectory || path.toRealPath() != path) throw unknownEvidence()
        return true
    }

    private fun unknownEvidence() = IOException("Private selected merge storage retains unexplained evidence")

    private companion object {
        const val MAX_RETAINED = 4
        val JOURNAL_RECORDS = setOf("merge.intent", "selected-merge.complete")
        val RECORDS = JOURNAL_RECORDS + "catalog.sqlite"
        val SPEND = Regex("[a-f0-9]{64}\\.spent")
    }
}
