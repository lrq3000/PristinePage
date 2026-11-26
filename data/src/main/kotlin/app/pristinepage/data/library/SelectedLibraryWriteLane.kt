package app.pristinepage.data.library

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import kotlinx.coroutines.sync.Mutex

private const val SELECTED_LIBRARY_WRITE_LOCK = "library-selected-merge-v1.lock"
private val selectedLibraryProcessLane = Mutex()

internal class SelectedLibraryWriteLease(private val owner: Path, private val check: () -> Unit) {
    fun requireCurrent(files: Path) {
        if (files != owner) throw IOException("The selected library write lease belongs to another private owner")
        check()
    }
}

/** Both selected append and edit use the exact canonical app-files owner. Refuse same-process contention before opening another lock descriptor. */
internal inline fun <T> withSelectedLibraryWriteLane(files: Path, block: (SelectedLibraryWriteLease) -> T): T {
    if (!selectedLibraryProcessLane.tryLock()) throw IOException("Another selected library publication owns the write lane")
    try {
        requireSelectedPrivateWriteRoot(files)
        FileChannel.open(files.resolve(SELECTED_LIBRARY_WRITE_LOCK), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                ?: throw IOException("Another process owns the selected library write lane")
            return lock.use {
                block(SelectedLibraryWriteLease(files) {
                    requireSelectedPrivateWriteRoot(files)
                    if (!lock.isValid) throw IOException("The selected library lost its write lane")
                })
            }
        }
    } finally {
        selectedLibraryProcessLane.unlock()
    }
}

private fun requireSelectedPrivateWriteRoot(files: Path) {
    if (!files.isAbsolute || !Files.isDirectory(files, NOFOLLOW_LINKS) || files.toRealPath() != files) {
        throw IOException("The private selected library write lane is unavailable")
    }
}
