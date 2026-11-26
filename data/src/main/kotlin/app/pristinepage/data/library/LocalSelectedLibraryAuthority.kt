package app.pristinepage.data.library

import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.files.PlatformDirectorySync
import app.pristinepage.data.library.ActiveLibraryFence.Location
import app.pristinepage.data.library.ActiveLibraryFence.Selection
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.security.MessageDigest

/** Verify the private pointer and its supporting immutable records. The caller owns the cooperative settings lane and must admit the real graph independently. */
internal class LocalSelectedLibraryAuthority(root: File, sync: DirectorySync = PlatformDirectorySync) {
    private val pointer = LocalActiveLibrarySelection(root, sync)
    private val grants = LocalLibraryFolderGrants(root, sync)
    private val verifications = LocalLibraryDirectory(root, sync)

    sealed interface Selected {
        val selection: Selection
        data class Default(override val selection: Selection) : Selected
        data class Reconnect(override val selection: Selection) : Selected
        data class Folder(
            override val selection: Selection, val grant: LibraryFolderGrantRecord, val expectedGeneration: LibraryGeneration,
        ) : Selected {
            fun requireExact(tree: String, generation: LibraryGeneration) {
                if (grant.exactUri != tree || generation != expectedGeneration) throw IOException("The selected library verification changed")
            }
        }
    }

    /** Positive absence is only meaningful to the first-use policy. A corrupt pointer is never an absent pointer. */
    fun read(): Selected? {
        val current = pointer.readVerified() ?: return null
        val location = current.location as? Location.Folder ?: return Selected.Default(current)
        val selected = try {
            val grant = grants.readVerified(location.grantId)
            val generation = readVerifiedGeneration(location)
            if (grant != null && generation != null && generation.libraryId == current.identity.libraryId &&
                location.verificationSha256 == digest(grant, generation)
            ) {
                Selected.Folder(current, grant, generation)
            } else Selected.Reconnect(current)
        } catch (_: IOException) {
            Selected.Reconnect(current)
        }
        if (pointer.readVerified() != current) throw IOException("The active library selection changed")
        return selected
    }

    fun initializeDefault(next: Selection): Boolean = pointer.replace(null, next)

    /** Advance the exact current writable binding, without switching folders. The settings owner must hold its lane and supply a verified live publication; this private swap does not authenticate or replay a provider write. */
    fun advanceVerifiedFolderGeneration(
        expected: Selected.Folder, successor: LibraryGeneration, requireCurrent: () -> Unit,
    ): Selected.Folder {
        val previous = expected.expectedGeneration
        val identity = expected.selection.identity
        if (expected.grant.returnedModes != 3 || successor.libraryId != previous.libraryId ||
            successor.predecessorId != previous.id || successor.catalogSchema != previous.catalogSchema ||
            identity.activation == Long.MAX_VALUE || read() != expected
        ) throw IOException("The edited generation is not a successor to the active writable folder")
        val next = Selection(Location.Folder(expected.grant.grantId, successor.id), identity.copy(activation = identity.activation + 1))
        val bound = chooseFolder(expected.selection, next, expected.grant, successor) {
            if (read() != expected) throw IOException("The active folder binding changed before publication")
            // A disk barrier may outlast provider access; keep the live check last, immediately before the pointer move.
            requireCurrent()
        }
        val admitted = Selected.Folder(bound, expected.grant, successor)
        if (read() != admitted) throw IOException("The advanced folder binding could not be acknowledged")
        return admitted
    }

    /** Called only after verifying the exact next folder and candidate graph. Stage records before publishing the pointer; never delete an old binding on refusal. */
    fun chooseFolder(expected: Selection, next: Selection, grant: LibraryFolderGrantRecord, generation: LibraryGeneration,
        checkpoint: () -> Unit = {},
    ): Selection {
        val location = next.location as? Location.Folder ?: throw IOException("Expected a verified folder selection")
        if (location.grantId != grant.grantId || location.admittedGenerationId != generation.id ||
            next.identity.libraryId != generation.libraryId || expected.identity.activation == Long.MAX_VALUE ||
            next.identity.activation != expected.identity.activation + 1
        ) throw IOException("The grant, generation and selection disagree")
        val bound = boundSelection(next, grant, generation)
        if (pointer.readVerified() != expected) throw IOException("The expected selection is no longer active")
        checkpoint()
        grants.writeVerified(grant)
        if (readVerifiedGeneration(location) == null) {
            try { verifications.createFolderVerification(grant.grantId, generation) }
            catch (_: FileAlreadyExistsException) { /* Require an exact durable immutable match below. */ }
        }
        if (readVerifiedGeneration(location) != generation) throw IOException("The folder generation verification conflicts")
        if (!pointer.replace(expected, bound, checkpoint)) throw IOException("The selected folder was not published")
        return bound
    }

    private fun readVerifiedGeneration(location: Location.Folder): LibraryGeneration? {
        val (grantId, generationId) = location
        val first = verifications.readFolderVerification(grantId, generationId) ?: return null
        verifications.forceFolderVerification(grantId, generationId)
        if (first.id != generationId || verifications.readFolderVerification(grantId, generationId) != first) {
            throw IOException("The folder generation verification changed")
        }
        return first
    }

    companion object {
        fun boundSelection(selection: Selection, grant: LibraryFolderGrantRecord, generation: LibraryGeneration): Selection {
            val location = selection.location as? Location.Folder ?: throw IOException("Expected a folder selection")
            val digest = digest(grant, generation)
            if (location.verificationSha256?.let { it != digest } == true) throw IOException("The folder verification binding disagrees")
            return selection.copy(location = location.copy(verificationSha256 = digest))
        }

        private fun digest(grant: LibraryFolderGrantRecord, generation: LibraryGeneration): String =
            MessageDigest.getInstance("SHA-256").digest(grant.encode() + generation.encode())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
