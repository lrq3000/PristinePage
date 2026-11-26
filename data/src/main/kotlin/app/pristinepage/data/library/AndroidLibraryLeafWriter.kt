package app.pristinepage.data.library

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import app.pristinepage.core.io.transferExactBytes
import app.pristinepage.data.library.AndroidLibraryWritableFolder.WritableTree
import app.pristinepage.data.library.LibraryObjectTransfer.ObjectId
import java.io.FileDescriptor
import java.io.IOException
import java.io.OutputStream

internal data class HeldLibraryDirectory(val id: String, val descriptor: ParcelFileDescriptor, val identity: Pair<Long, Long>)

/** One same-basename move of a closed staged leaf under held provider directories. Its caller owns the operation, not just the grant. */
internal class AndroidLibraryLeafWriter(
    private val provider: WritableTree,
    private val selected: HeldLibraryDirectory,
    private val root: HeldLibraryDirectory,
    private val stage: HeldLibraryDirectory,
    private val journal: LocalLibraryMoveAuthorization,
    private val find: (HeldLibraryDirectory, String) -> String?,
    private val sync: (HeldLibraryDirectory) -> Unit,
) {
    private val checkpoint = provider.checkpoint

    fun publish(parent: HeldLibraryDirectory, name: String, wanted: ObjectId, requireStage: () -> Unit = {},
        requireVisibility: (() -> Unit)? = null, write: (OutputStream) -> Unit,
    ): Pair<Long, Long> {
        if (find(parent, name) != null || find(stage, name) != null) {
            throw IOException("The new library file would replace an existing name")
        }
        requireStage()
        val id = provider.create(stage.id, "application/octet-stream", name)
        if (find(stage, name) != id) throw IOException("The provider staging file is not at the expected name")
        return ParcelFileDescriptor.AutoCloseInputStream(provider.open(id, "r")).use { anchor ->
            val identity = fileIdentity(anchor)
            // A truncating open can mutate before the descriptor is checked. Start writing only to the anchored empty file.
            ParcelFileDescriptor.AutoCloseOutputStream(provider.open(id, "rw")).use { output ->
                if (fileIdentity(output.fd, root.identity.first, requireEmpty = true, checkpoint = checkpoint) != identity) {
                    throw IOException("The library staging writer changed files")
                }
                requireStage()
                write(output)
                checkpoint()
                output.flush()
                Os.fsync(output.fd)
            }
            val proof = proof(anchor, wanted)
            sync(stage)
            val key = backupSha256("${parent.id.substringAfterLast('/')}/$name".toByteArray(Charsets.US_ASCII))
            val parents = backupSha256(listOf(selected, root, stage, parent).joinToString("\n") { "${it.id}\t${it.identity}" }.toByteArray())
            val publisher = LibraryNamedPublication(object : LibraryNamedPublication.Store {
                override fun readStage() = observe(stage, name, wanted, anchor)
                override fun readFinal() = observe(parent, name, wanted, anchor)
                override fun consumeMoveAuthorization() = journal.consume(key, parents, proof)
                override fun moveOnce() = provider.move(id, stage.id, parent.id)
                override fun syncStageParent() = sync(stage)
                override fun syncFinalParent() = sync(parent)
            }, checkpoint)
            publisher.commit(proof, reconcileOnly = false, requireVisibility = requireVisibility)
            identity
        }
    }

    fun fileIdentity(input: ParcelFileDescriptor.AutoCloseInputStream): Pair<Long, Long> =
        fileIdentity(input.fd, root.identity.first, checkpoint = checkpoint)

    private fun observe(parent: HeldLibraryDirectory, name: String, expected: ObjectId,
        anchor: ParcelFileDescriptor.AutoCloseInputStream,
    ): LibraryNamedPublication.FileProof? {
        val id = find(parent, name) ?: return null
        ParcelFileDescriptor.AutoCloseInputStream(provider.open(id, "r")).use {
            if (fileIdentity(anchor) != fileIdentity(it)) throw IOException("The moved library file did not retain its staged identity")
            return proof(it, expected)
        }
    }

    private fun proof(input: ParcelFileDescriptor.AutoCloseInputStream, expected: ObjectId): LibraryNamedPublication.FileProof {
        val before = fileIdentity(input)
        transferExactBytes(input, null, expected.byteCount, expected.sha256, checkpoint)
        if (fileIdentity(input) != before) throw IOException("The library file identity changed during readback")
        provider.requireExactAccess()
        return LibraryNamedPublication.FileProof("${before.first}:${before.second}", expected.sha256, expected.byteCount)
    }

    companion object {
        fun fileIdentity(fd: FileDescriptor, device: Long, requireEmpty: Boolean = false, checkpoint: () -> Unit): Pair<Long, Long> {
            checkpoint()
            val stat = Os.fstat(fd)
            if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_ino <= 0 || stat.st_nlink != 1L || stat.st_dev != device ||
                (requireEmpty && stat.st_size != 0L)
            ) throw IOException("The library file has no exclusive regular-file identity")
            return stat.st_dev to stat.st_ino
        }
    }
}
