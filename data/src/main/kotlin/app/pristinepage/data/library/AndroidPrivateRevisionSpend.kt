package app.pristinepage.data.library

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.FileDescriptor
import java.io.IOException
import java.nio.file.Path

/** Android readback of one already-recorded private spend. The caller owns the selected storage lane. */
internal fun LocalLibraryDirectory.requireAndroidRevisionSpend(predecessor: LibraryGeneration, successor: LibraryGeneration,
    requireOwner: () -> Unit, checkpoint: () -> Unit,
) = requireRecordedCatalogRevision(predecessor, successor, checkpoint) { leaf, check ->
    val parent = checkNotNull(leaf.parent)
    try {
        withPrivateNode(rootPath, directory = true) { files, requireFiles ->
            withPrivateNode(parent, directory = true) { directory, requireDirectory ->
                withPrivateNode(leaf, directory = false) { record, requireLeaf ->
                    fun requireNodes() {
                        requireOwner()
                        requireFiles()
                        requireDirectory()
                        requireLeaf()
                    }
                    fun syncParents() {
                        // Synchronize the admitted directories, not whatever their paths happen to resolve to during fsync.
                        Os.fsync(directory)
                        Os.fsync(files)
                    }
                    check(::requireNodes, ::syncParents) {
                        Os.lseek(record, 0, OsConstants.SEEK_SET)
                        ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(record))
                    }
                }
            }
        }
    } catch (failure: ErrnoException) {
        throw IOException("The private catalog revision spend could not be verified", failure)
    }
}

private fun <T> withPrivateNode(path: Path, directory: Boolean, block: (FileDescriptor, () -> Unit) -> T): T {
    val initial = Os.lstat(path.toString())
    fun sameType(mode: Int) = if (directory) OsConstants.S_ISDIR(mode) else OsConstants.S_ISREG(mode)
    if (!sameType(initial.st_mode) || path.toRealPath() != path) throw IOException("A private spend node is not canonical")
    val held = Os.open(path.toString(), OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or
        OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
    return Closeable { Os.close(held) }.use {
        val identity = Os.fstat(held)
        if (!sameType(identity.st_mode) || identity.st_ino <= 0L || identity.st_dev != initial.st_dev || identity.st_ino != initial.st_ino) {
            throw IOException("A private spend node changed while opening")
        }
        block(held) {
            val current = Os.lstat(path.toString())
            if (!sameType(current.st_mode) || current.st_dev != identity.st_dev || current.st_ino != identity.st_ino ||
                path.toRealPath() != path
            ) throw IOException("A held private spend node changed")
        }
    }
}
