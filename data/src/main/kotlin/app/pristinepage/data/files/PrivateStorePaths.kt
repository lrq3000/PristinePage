package app.pristinepage.data.files

import java.io.File
import java.nio.file.Files

/** Fixed relative paths under a stable app-owned root. These checks do not fence malicious concurrent changes by the same UID. */
internal class PrivateStorePaths(root: File) {
    val root = root.canonicalFile

    fun beneath(relative: String): File {
        val path = root.toPath().resolve(relative).normalize()
        require(path.startsWith(root.toPath()) && path != root.toPath()) { "Unsafe private path" }
        var current = root.toPath()
        for (part in root.toPath().relativize(path)) {
            current = current.resolve(part)
            require(!Files.isSymbolicLink(current)) { "Private path is a symbolic link" }
            if (Files.exists(current)) require(current.toFile().canonicalFile.toPath() == current) { "Private path redirects outside its owned location" }
        }
        return path.toFile()
    }

    fun createDirectories(directory: File, syncDirectory: (File) -> Unit) {
        require(directory.toPath().startsWith(root.toPath()))
        Files.createDirectories(directory.toPath())
        require(directory.canonicalFile == directory && directory.isDirectory) { "Invalid private directory" }
        // Persist the links, not just eventual leaf contents. Revisit the bounded ancestry even on replay: a previous operation may have created a directory and failed before its parent was forced. The store's containing directory is app-owned by the constructor contract.
        var child = directory
        while (child.toPath().startsWith(root.toPath())) {
            val parent = checkNotNull(child.parentFile)
            syncDirectory(parent)
            if (child == root) break
            child = parent
        }
    }
}
