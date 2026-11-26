package app.pristinepage.data.library

import android.content.Context
import app.pristinepage.data.model.requireOpaqueId
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
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
        val storage = files.resolve("library-selected-merge-v1")
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
        val journal = LocalLibraryMergeJournal(root.toFile(), files)
        reader.appendMerge(plan, child, attempt, journal, LocalLibraryMoveAuthorization(attempt.toFile()), ::requireOwner) {
            verifier.verifyStagedProjection(it, plan.candidate.catalog, ::requireOwner)
        }.also {
            requireOwner()
            Files.newDirectoryStream(attempt, "library-catalog-*").use { verification ->
                if (verification.iterator().hasNext()) throw IOException("The selected library readback scratch was not retired")
            }
            if (!Files.exists(closed, NOFOLLOW_LINKS)) throw IOException("The selected library intent lost its closed catalog")
        }
    }
}
