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

    /** Historical private receipts may be shown only after proving continuity of the same default library. */
    fun verifiedDefaultOrigin(current: Selected.Default): LibraryActivation? {
        if (read() != current) throw IOException("The default library is no longer active")
        val origin = if (current.selection.identity.activation == 1L) null else {
            pointer.readDefaultOrigin()?.takeIf { it.identity.libraryId == current.selection.identity.libraryId }
                ?: throw IOException("The original default library identity is unavailable")
        }
        if (read() != current) throw IOException("The default library changed during history admission")
        return origin?.identity?.let { LibraryActivation(it.libraryId, it.activation) }
    }

    /** Explicit return only. A missing or inconsistent private origin can never mint a new default library. */
    fun returnToDefault(expected: Selection, requireCurrent: () -> Unit): Selected.Default {
        if (expected.location !is Location.Folder || read()?.selection != expected) throw IOException("The folder is no longer selected")
        val origin = pointer.readDefaultOrigin() ?: throw IOException("The original default library identity is unavailable")
        val next = origin.copy(identity = origin.identity.copy(activation = Math.addExact(expected.identity.activation, 1L)))
        val confirmed = Selected.Default(next)
        try {
            if (!pointer.replace(expected, next, requireCurrent)) throw IOException("The default library was not selected")
        } catch (failure: IOException) {
            if (read() != confirmed) throw failure
        }
        if (read() != confirmed || pointer.readDefaultOrigin() != origin) throw IOException("The default selection could not be acknowledged")
        return confirmed
    }

    /** Advance the exact current writable binding, without switching folders. The settings owner must hold its lane and supply a verified live publication; this private swap does not authenticate or replay a provider write. */
    fun advanceVerifiedFolderGeneration(
        expected: Selected.Folder, successor: LibraryGeneration, beforePointerReplace: () -> Unit = {}, requireCurrent: () -> Unit,
    ): Selected.Folder {
        val next = advancedFolderSelection(expected, successor)
            ?: throw IOException("The edited generation is not a successor to the active writable folder")
        if (read() != expected) throw IOException("The expected folder generation is no longer active")
        val bound = chooseFolder(expected.selection, next, expected.grant, successor, beforePointerReplace) {
            if (read() != expected) throw IOException("The active folder binding changed before publication")
            // A disk barrier may outlast provider access; keep the live check last, immediately before the pointer move.
            requireCurrent()
        }
        val admitted = Selected.Folder(bound, expected.grant, successor)
        if (read() != admitted) throw IOException("The advanced folder binding could not be acknowledged")
        return admitted
    }

    /** Observe a possibly acknowledged atomically published pointer. This never advances the pointer or inspects the provider. */
    fun readAdvancedFolderGeneration(expected: Selected.Folder, successor: LibraryGeneration): Selected.Folder? {
        val next = advancedFolderSelection(expected, successor) ?: return null
        val bound = boundSelection(next, expected.grant, successor)
        return (read() as? Selected.Folder)?.takeIf { it == Selected.Folder(bound, expected.grant, successor) }
    }

    fun advanceOrReadVerifiedFolderGeneration(
        expected: Selected.Folder, successor: LibraryGeneration, beforePointerReplace: () -> Unit = {}, requireCurrent: () -> Unit,
    ): Selected.Folder = try {
        advanceVerifiedFolderGeneration(expected, successor, beforePointerReplace, requireCurrent)
    } catch (failure: IOException) {
        // The atomic replacement may have occurred before its final force/readback failed. Never repeat the swap or the provider write.
        readAdvancedFolderGeneration(expected, successor) ?: throw failure
    }

    private fun advancedFolderSelection(expected: Selected.Folder, successor: LibraryGeneration): Selection? {
        val previous = expected.expectedGeneration
        val identity = expected.selection.identity
        if (expected.grant.returnedModes != 3 || successor.libraryId != previous.libraryId ||
            successor.predecessorId != previous.id || successor.id == previous.predecessorId ||
            successor.catalogSchema != previous.catalogSchema || identity.activation == Long.MAX_VALUE
        ) return null
        return Selection(Location.Folder(expected.grant.grantId, successor.id), identity.copy(activation = identity.activation + 1))
    }

    /** Called only after verifying the exact next folder and candidate graph. Stage records before publishing the pointer; never delete an old binding on refusal. */
    fun chooseFolder(expected: Selection, next: Selection, grant: LibraryFolderGrantRecord, generation: LibraryGeneration,
        beforePointerReplace: () -> Unit = {},
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
        if (expected.location == Location.Default) {
            val origin = expected.copy(identity = expected.identity.copy(activation = 1L))
            if (pointer.readDefaultOrigin() == null && expected.identity.activation == 1L) {
                try { verifications.createDefaultOrigin(pointer.encodeDefaultOrigin(origin)) }
                catch (_: FileAlreadyExistsException) { /* Require exact durable readback below. */ }
            }
            if (pointer.readDefaultOrigin() != origin) throw IOException("The default library origin cannot be retained")
        }
        grants.writeVerified(grant)
        if (readVerifiedGeneration(location) == null) {
            try { verifications.createFolderVerification(grant.grantId, generation) }
            catch (_: FileAlreadyExistsException) { /* Require an exact durable immutable match below. */ }
        }
        if (readVerifiedGeneration(location) != generation) throw IOException("The folder generation verification conflicts")
        if (!pointer.replace(expected, bound) {
                checkpoint()
                // No provider work or cancellation point may separate the grant handoff from attempting the atomic pointer move.
                beforePointerReplace()
            }) throw IOException("The selected folder was not published")
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
