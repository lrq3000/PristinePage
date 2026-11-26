package app.pristinepage.data.library

import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Holds one app-private pair's temporary catalogs. Never sweep unknown contents or return success on uncertain cleanup. */
internal object LibraryPairScratch {
    private const val PREFIX = "library-pair-"
    private const val MAX_ATTEMPTS = 4

    fun <T> use(root: Path, bind: (Path) -> (() -> Unit) = { { } }, block: (Path) -> T): T {
        fun requireDirectory(path: Path) {
            if (!Files.isDirectory(path, NOFOLLOW_LINKS) || path.toRealPath() != path) {
                throw IOException("The library verification scratch directory changed")
            }
        }
        val attempt = synchronized(this) {
            requireDirectory(root)
            try {
                Files.newDirectoryStream(root, "$PREFIX*").use {
                    if (it.asSequence().take(MAX_ATTEMPTS).count() == MAX_ATTEMPTS) {
                        throw IOException("Retained library pair verification needs attention")
                    }
                }
            } catch (_: DirectoryIteratorException) { throw IOException("Library verification scratch could not be read") }
            Files.createTempDirectory(root, PREFIX)
        }
        val requireOriginal = bind(attempt)
        return AutoCloseable {
            requireDirectory(root)
            requireDirectory(attempt)
            requireOriginal()
            // A retained file keeps its entire attempt charged to the bounded budget.
            Files.delete(attempt)
        }.use { block(attempt) }
    }
}
