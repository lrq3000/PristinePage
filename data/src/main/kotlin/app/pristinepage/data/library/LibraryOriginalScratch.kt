package app.pristinepage.data.library

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE

/** One attempt under a stable cooperating private root. The caller serializes admission and checks capacity. */
internal class LibraryOriginalScratch(
    private val root: Path,
    private val openOutput: (Path) -> OutputStream = { Files.newOutputStream(it, CREATE_NEW, WRITE, NOFOLLOW_LINKS) },
) {
    suspend fun use(checkpoint: () -> Unit, write: suspend (OutputStream) -> Unit, useClosed: suspend (File) -> Unit) {
        try {
            Files.newDirectoryStream(root, "library-original-*").use {
                if (it.asSequence().take(4).count() >= 4) throw IOException("Retained library original copies need attention")
            }
        } catch (_: DirectoryIteratorException) { throw IOException("Private library original scratch is unavailable") }
        checkpoint()
        val attempt = Files.createTempDirectory(root, "library-original-")
        val path = attempt.resolve("original.bin")
        // Before a handle is returned, an empty attempt may safely retire; a visible unexpected file blocks that directory deletion.
        var retireEmptyOnly = true
        var closed = false
        var primary: Throwable? = null
        try {
            val output = openOutput(path)
            retireEmptyOnly = false
            var copying: Throwable? = null
            try { write(output) }
            catch (failure: Throwable) { copying = failure; throw failure }
            finally {
                try { output.close(); closed = true }
                catch (_: IOException) {
                    val refused = IOException("The private library original copy could not be closed")
                    if (copying == null) throw refused else copying.addSuppressed(refused)
                }
            }
            checkpoint()
            useClosed(path.toFile())
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            if (closed || retireEmptyOnly) try {
                if (closed) Files.delete(path)
                Files.delete(attempt)
            } catch (_: IOException) {
                if (primary == null) throw IOException("The private library original copy could not be retired")
            }
        }
    }
}
