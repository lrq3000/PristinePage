package app.pristinepage.data.library

import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.files.PlatformDirectorySync
import app.pristinepage.data.files.PrivateStorePaths
import app.pristinepage.data.model.requireOpaqueId
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.DirectoryIteratorException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes

/** Local filesystem only. The caller owns a stable, exclusively controlled app-managed directory; provider URIs cannot be translated into this capability. */
internal class LocalLibraryDirectory(root: File, private val directorySync: DirectorySync = PlatformDirectorySync) :
    LibraryObjectTransfer.Store, LibraryCatalogPublication.Store {
    private val paths = PrivateStorePaths(root)

    init {
        require(root.absoluteFile == paths.root && Files.isDirectory(root.toPath(), NOFOLLOW_LINKS))
    }

    val rootPath: Path get() = paths.root.toPath()

    override fun open(id: LibraryObjectTransfer.ObjectId) = open(objectPath(id))
    override fun create(id: LibraryObjectTransfer.ObjectId, write: (OutputStream) -> Unit) = create(objectPath(id), write)
    override fun openCatalog(generation: LibraryGeneration) = open(generation.catalogPath)
    override fun createCatalog(generation: LibraryGeneration, write: (OutputStream) -> Unit) = create(generation.catalogPath, write)
    override fun openMarker(generation: LibraryGeneration) = open(markerPath(generation))

    /** Private merge-journal use only. An operation ID is never supplied by SQLite rows or a display label. */
    fun openMergeIntent(generation: String) = open(mergeStagePath(generation) + "/merge.intent")

    fun createMergeIntent(generation: String, bytes: ByteArray) =
        create(mergeStagePath(generation) + "/merge.intent") { it.write(bytes) }

    fun openMergeStage(generation: String) = open(mergeStagePath(generation) + "/catalog.sqlite")

    fun forceMergeStage(generation: String, durableAncestor: Path) {
        val directory = mergeStagePath(generation)
        val stage = readPath("$directory/catalog.sqlite")
        if (!rootPath.startsWith(durableAncestor) || !Files.isDirectory(durableAncestor, NOFOLLOW_LINKS) ||
            durableAncestor.toRealPath() != durableAncestor
        ) throw IOException("Private merge stage has no admitted durable ancestor")
        if (!presentRegularFile(stage)) throw IOException("Private merge stage is missing")
        FileChannel.open(stage, WRITE, NOFOLLOW_LINKS).use { it.force(true) }
        var containing = checkNotNull(stage.parent)
        while (true) {
            forceDirectory(containing.toFile())
            if (containing == durableAncestor) break
            containing = checkNotNull(containing.parent)
        }
    }

    /** Only for a fresh private restore: opaque blob IDs, never source-row paths or logical filenames. */
    fun openPrivateOriginal(blob: String): InputStream? = open(privateOriginalPath(blob))

    fun createPrivateOriginal(blob: String, write: (OutputStream) -> Unit) = create(privateOriginalPath(blob), write)

    fun publishRestoreReceipt(expected: ByteArray) {
        val path = "catalog.ready"
        create(path) { it.write(expected) }
        val observed = open(path) ?: throw IOException("Restore completion receipt is missing")
        observed.use { input ->
            for (byte in expected) if (input.read() != byte.toInt().and(255)) throw IOException("Restore completion receipt changed")
            if (input.read() != -1) throw IOException("Restore completion receipt has trailing data")
        }
    }

    /** Duplicate destination locators can share their already verified immutable inode; never link back to the backup. */
    fun linkPrivateOriginal(existing: String, duplicate: String) {
        val source = readPath(privateOriginalPath(existing))
        if (!presentRegularFile(source)) throw IOException("Primary restored original is missing")
        val target = paths.beneath(privateOriginalPath(duplicate))
        val parent = checkNotNull(target.parentFile)
        paths.createDirectories(parent, ::forceDirectory)
        Files.createLink(target.toPath(), source)
        forceDirectory(parent)
    }

    override fun createMarker(generation: LibraryGeneration, bytes: ByteArray) {
        require(bytes.contentEquals(generation.encode()))
        create(markerPath(generation)) { it.write(bytes) }
    }

    /** Iterates a bounded inventory and refuses uncertainty rather than silently opening an older generation. Does not publish, remove or repair anything. */
    fun readTip(checkpoint: () -> Unit = {}): LibraryGeneration = generationInventory(checkpoint).tip

    class GenerationInventory(private val markers: List<LibraryGeneration>, private val entryCount: Int) {
        val tip = LibraryGenerationChain.tip(markers)

        fun requireExact(generation: LibraryGeneration) {
            if (generation !in markers) throw IOException("The exact retained library generation is missing or changed")
        }

        fun requireAppendable(candidate: LibraryGeneration) {
            // Budget both the marker and its staging leaf, even if cleanup of the latter is refused.
            if (entryCount > 2 * LibraryGenerationChain.MAX_MARKERS - 2 || LibraryGenerationChain.tip(markers + candidate) != candidate) {
                throw IOException("The destination cannot admit the new library generation")
            }
        }
    }

    fun generationInventory(checkpoint: () -> Unit = {}): GenerationInventory {
        val root = readPath("generations")
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) throw IOException("Library generation folder is unavailable")
        val markers = ArrayList<LibraryGeneration>()
        var entries = 0
        try {
            Files.newDirectoryStream(root).use {
                for (entry in it) {
                    checkpoint()
                    if (++entries > 2 * LibraryGenerationChain.MAX_MARKERS) throw IOException("Library generation inventory exceeds the limit")
                    val leaf = entry.fileName.toString()
                    if (!leaf.endsWith(".complete")) continue
                    if (markers.size == LibraryGenerationChain.MAX_MARKERS) throw IOException("Library generation inventory exceeds the limit")
                    val marker = open("generations/$leaf")?.use(LibraryGeneration::read)
                        ?: throw IOException("Library marker disappeared")
                    if (leaf != "${marker.id}.complete") throw IOException("Library marker name does not match the generation")
                    markers += marker
                }
            }
        } catch (_: DirectoryIteratorException) {
            throw IOException("Library generation inventory could not be read")
        }
        checkpoint()
        return GenerationInventory(markers, entries)
    }

    override fun confirmPayloadDurability(generation: LibraryGeneration, requiresObjects: Boolean) {
        forceParentOf(generation.catalogPath)
        val objects = readPath("objects")
        val attributes = try { Files.readAttributes(objects, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
        catch (missing: NoSuchFileException) {
            if (requiresObjects) throw missing
            null
        }
        if (attributes != null) {
            if (!attributes.isDirectory) throw IOException("Library objects namespace is not a directory")
            forceDirectory(objects.toFile())
        }
        // The root may have acquired the catalogs/objects directory links since the last acknowledged generation.
        forceDirectory(paths.root)
        forceDirectory(checkNotNull(paths.root.parentFile))
    }

    override fun confirmMarkerDurability(generation: LibraryGeneration) {
        forceParentOf(markerPath(generation))
        forceDirectory(paths.root)
        forceDirectory(checkNotNull(paths.root.parentFile))
    }

    private fun forceParentOf(relativePath: String) =
        forceDirectory(checkNotNull(paths.beneath(relativePath).parentFile))

    private fun open(relativePath: String): InputStream? {
        val path = readPath(relativePath)
        if (!presentRegularFile(path)) return null
        // An open failure after positive observation is uncertain, not evidence that a new object can be created here.
        return Files.newInputStream(path, READ, NOFOLLOW_LINKS)
    }

    private fun readPath(relativePath: String): Path = try {
        paths.beneath(relativePath).toPath()
    } catch (_: IllegalArgumentException) {
        throw IOException("Library path redirects outside its owned location")
    }

    private fun create(relativePath: String, write: (OutputStream) -> Unit) {
        val file = paths.beneath(relativePath)
        val destination = file.toPath()
        if (presentRegularFile(destination)) throw FileAlreadyExistsException(file.name)
        val parent = checkNotNull(file.parentFile)
        paths.createDirectories(parent, ::forceDirectory)
        forceDirectory(parent)
        val stage = Files.createTempFile(parent.toPath(), ".pristinepage-", ".part")
        FileChannel.open(stage, WRITE, NOFOLLOW_LINKS).use { channel ->
            write(Channels.newOutputStream(channel))
            channel.force(true)
        }
        // The exclusive link makes the closed stage visible without replacing any existing leaf. A backend without this primitive refuses rather than copying into the final name.
        Files.createLink(destination, stage)
        forceDirectory(parent)
        // A failed write, close or sync keeps its exact stage as recovery evidence. Once durable, unlink only this attempt's temporary name; never remove the final name.
        try { Files.deleteIfExists(stage) } catch (_: IOException) { /* The verified object remains usable. */ }
    }

    private fun presentRegularFile(path: Path): Boolean {
        val attributes = try { Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
        catch (_: NoSuchFileException) { return false }
        if (!attributes.isRegularFile) throw IOException("Library leaf is not a regular file")
        return true
    }

    private fun forceDirectory(directory: File) {
        if (!directorySync.sync(directory)) throw IOException("Library directory durability is unavailable")
    }

    private fun objectPath(id: LibraryObjectTransfer.ObjectId) = "objects/${id.sha256}"
    private fun markerPath(generation: LibraryGeneration) = "generations/${generation.id}.complete"
    private fun mergeStagePath(generation: String): String {
        requireOpaqueId(generation)
        return "merge-stages/$generation"
    }

    private fun privateOriginalPath(blob: String): String {
        requireOpaqueId(blob)
        return "sources/$blob/original.bin"
    }
}
