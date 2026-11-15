package app.pristinepage.data.files

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.READ

/** Filesystem-specific directory durability: false means a recognized unsupported primitive, while an operational storage failure throws. Kept separate from journal visibility and payload ownership. */
fun interface DirectorySync {
    @Throws(IOException::class)
    fun sync(directory: File): Boolean
}

internal object PlatformDirectorySync : DirectorySync {
    override fun sync(directory: File): Boolean {
        // This JVM adapter deliberately does not implement Windows directory descriptors. That declared backend limitation is not an arbitrary access/IO error caught and relabeled as success.
        if (System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)) return false
        try { FileChannel.open(directory.toPath(), READ).use { it.force(true) } }
        catch (_: UnsupportedOperationException) { return false }
        return true
    }
}
