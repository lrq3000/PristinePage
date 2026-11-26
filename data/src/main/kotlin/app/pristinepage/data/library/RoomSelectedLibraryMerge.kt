package app.pristinepage.data.library

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import app.pristinepage.data.model.requireOpaqueId
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Detached selected-folder append qualification. No activation switch, retired source, or new write following an uncertain result. */
internal class RoomSelectedLibraryMerge(context: Context, private val scratchDirectory: File) {
    private val app = context.applicationContext
    private val maximum = RoomLibraryCatalogReader.MAX_PORTABLE_BYTES

    suspend fun append(operationId: String, destination: AndroidLibraryTreeRead.PairInput, incoming: AndroidLibraryTreeRead.PairInput,
        requireAuthority: () -> Unit,
    ): LibraryGeneration = withContext(Dispatchers.IO) {
        requireOpaqueId(operationId)
        if (destination.expected == null || incoming.expected == null) throw IOException("The selected write needs both exact input generations")
        val checkpoint = { coroutineContext.ensureActive() }
        val reader = AndroidLibraryTreeRead(app, scratchDirectory)
        val plan = reader.planMerge(operationId, destination, incoming, requireAuthority)
        val files = app.filesDir.toPath().toRealPath()
        val storage = files.resolve(STORAGE)
        if (files.toFile().usableSpace < 3 * maximum) throw IOException("Not enough private space to stage the merged library")
        requireAuthority()
        checkpoint()
        // A fresh directory is the one-shot private owner. This qualification deliberately refuses any later append or replay until the owner can be settled.
        Files.createDirectory(storage)
        val root = canonicalLibraryDirectory(storage.toFile(), files)
        val key = localLibraryRootKey(root)
        val attempt = Files.createDirectory(Files.createDirectory(root.resolve("merge-stages")).resolve(operationId))
        val attemptKey = localLibraryRootKey(attempt)
        fun requireOwner() {
            checkpoint()
            requireAuthority()
            if (localLibraryRootKey(root) != key || localLibraryRootKey(attempt) != attemptKey) {
                throw IOException("The private selected library attempt changed")
            }
            checkpoint()
        }
        val closed = attempt.resolve("catalog.sqlite")
        val file = closed.toFile()
        requireOwner()
        val schema = RoomLibraryCatalogStage(app).write(plan.candidate.catalog, file)
        val verifier = RoomLibraryCatalogReader(app, maximum)
        val proof = fingerprintClosedLibraryCatalog(file, ::requireOwner)
        verifier.verifyStagedProjection(file, plan.candidate.catalog, ::requireOwner)
        if (fingerprintClosedLibraryCatalog(file, ::requireOwner) != proof) throw IOException("The private selected library catalog changed")
        val child = LibraryGeneration(destination.expected.libraryId, operationId, destination.expected.id, schema, proof)
        val journal = journal(root, files)
        val published = reader.appendMerge(plan, child, attempt, journal, LocalLibraryMoveAuthorization(attempt.toFile()), ::requireOwner) {
            verifier.verifyStagedProjection(it, plan.candidate.catalog, ::requireOwner)
        }
        // The paired reader, writer, and scratch owners have all unwound. A late I/O failure or canceled result before this boundary leaves only intent.
        requireOwner()
        Files.newDirectoryStream(attempt, "library-catalog-*").use { verification ->
            if (verification.iterator().hasNext()) throw IOException("The selected library readback scratch was not retired")
        }
        published.completion.recordCompletion(published.generation, checkpoint, ::requireOwner)
        published.generation
    }

    companion object {
        private const val STORAGE = "library-selected-merge-v1"

        private fun journal(root: Path, files: Path) = LocalLibraryMergeJournal(root.toFile(), files, nodeIdentity = { path, _ ->
            try { Os.lstat(path.toString()).let { it.st_dev to it.st_ino } }
            catch (failure: ErrnoException) { throw IOException("The private selected merge evidence cannot be identified", failure) }
        })

        /** Inspect one retained private attempt. A completion is historical, not proof the external folder is still accessible. */
        suspend fun inspect(context: Context, operationId: String): LocalLibraryMergeJournal.SelectedState? = withContext(Dispatchers.IO) {
            requireOpaqueId(operationId)
            val files = context.applicationContext.filesDir.toPath().toRealPath()
            val storage = files.resolve(STORAGE)
            try {
                Files.readAttributes(storage, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                return@withContext null
            }
            val root = canonicalLibraryDirectory(storage.toFile(), files)
            val key = localLibraryRootKey(root)
            val state = journal(root, files).inspectSelected(operationId) { coroutineContext.ensureActive() }
            if (localLibraryRootKey(root) != key) throw IOException("The private selected merge journal changed")
            state
        }
    }
}
