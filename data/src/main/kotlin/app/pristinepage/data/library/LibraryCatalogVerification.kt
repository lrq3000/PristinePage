package app.pristinepage.data.library

import app.pristinepage.core.io.transferExactBytes
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE

/** Opens no database itself. The synchronous consumer may read only this closed, exact, attempt-owned copy. */
internal class LibraryCatalogVerification(private val privateScratch: Path, private val checkpoint: () -> Unit) {
    fun <T> read(input: InputStream, generation: LibraryGeneration, verify: (File) -> T): T {
        val directory = privateScratch.toAbsolutePath()
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS) || directory.toRealPath() != directory) throw IOException("Invalid private verification directory")
        if (directory.toFile().usableSpace < generation.catalog.byteCount) throw IOException("Not enough space to verify library catalog")
        checkpoint()
        val scratch = Files.createTempFile(directory, "library-catalog-", ".sqlite")
        try {
            Files.newOutputStream(scratch, WRITE, NOFOLLOW_LINKS).use {
                transferExactBytes(input, it, generation.catalog.byteCount, generation.catalog.sha256, checkpoint)
            }
            Files.newInputStream(scratch, READ, NOFOLLOW_LINKS).use { generation.verifyCatalog(it, checkpoint) }
            return verify(scratch.toFile())
        } finally {
            // The selected folder is never a cleanup target.
            try { Files.deleteIfExists(scratch) } catch (_: IOException) { /* Do not obscure the primary result. */ }
        }
    }
}
