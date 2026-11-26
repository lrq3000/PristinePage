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
    LibraryObjectTransfer.Store, LibraryMergePublication.Destination {
    private val paths = PrivateStorePaths(root)

    init {
        require(root.absoluteFile == paths.root && Files.isDirectory(root.toPath(), NOFOLLOW_LINKS))
    }

    val rootPath: Path get() = paths.root.toPath()

    override fun requireIndependentScratch(scratch: Path) {
        val root = rootPath
        if (scratch.startsWith(root) || root.startsWith(scratch) || Files.isSameFile(root, scratch)) {
            throw IOException("Backup and private verification directory overlap or are aliased")
        }
    }

    override fun open(id: LibraryObjectTransfer.ObjectId) = open(objectPath(id))
    override fun create(id: LibraryObjectTransfer.ObjectId, write: (OutputStream) -> Unit) = create(objectPath(id), write = write)
    override fun create(id: LibraryObjectTransfer.ObjectId, requireCurrent: () -> Unit, write: (OutputStream) -> Unit) =
        create(objectPath(id), beforeStage = requireCurrent, beforeLink = requireCurrent, write = write)
    override fun openCatalog(generation: LibraryGeneration) = open(generation.catalogPath)
    override fun createCatalog(generation: LibraryGeneration, write: (OutputStream) -> Unit) = create(generation.catalogPath, write = write)
    override fun openMarker(generation: LibraryGeneration) = open(markerPath(generation))

    /** Private merge-journal use only. An operation ID is never supplied by SQLite rows or a display label. */
    fun openMergeIntent(generation: String) = open(mergeStagePath(generation) + "/merge.intent")

    fun createMergeIntent(generation: String, bytes: ByteArray, beforeLink: () -> Unit = {}) =
        create(mergeStagePath(generation) + "/merge.intent", beforeLink = beforeLink) { it.write(bytes) }

    fun openMergeStage(generation: String) = open(mergeStagePath(generation) + "/catalog.sqlite")

    fun requireFreshSelectedMergeStage(generation: String) {
        Files.newDirectoryStream(readPath(mergeStagePath(generation))).use { entries ->
            val names = entries.iterator()
            if (!names.hasNext() || names.next().fileName.toString() != "catalog.sqlite" || names.hasNext()) {
                throw IOException("The private selected merge stage is not fresh")
            }
        }
    }

    fun openSelectedMergeCompletion(generation: String) = open(mergeStagePath(generation) + "/selected-merge.complete")

    fun createSelectedMergeCompletion(generation: String, bytes: ByteArray, beforeLink: () -> Unit) =
        create(mergeStagePath(generation) + "/selected-merge.complete", beforeLink = beforeLink) { it.write(bytes) }

    data class MergeNode(val identity: Any, val byteCount: Long)
    data class SelectedMergeEvidence(val attempt: Any, val catalog: MergeNode?, val intent: MergeNode?, val completion: MergeNode?)

    /** Pin the exact observed nodes across the directory barrier; an unexplained SQLite sidecar invalidates the closed-stage proof. */
    fun selectedMergeEvidence(generation: String, identity: (Path, BasicFileAttributes) -> Any): SelectedMergeEvidence? {
        val relative = mergeStagePath(generation)
        fun attributes(path: Path): BasicFileAttributes? = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) { null }
        val attempt = readPath(relative)
        val parent = attributes(attempt) ?: return null
        if (!parent.isDirectory) throw IOException("The private selected merge attempt is not a directory")
        fun file(name: String): MergeNode? {
            val path = readPath("$relative/$name")
            val attrs = attributes(path) ?: return null
            if (!attrs.isRegularFile) throw IOException("The private selected merge evidence is not a regular file")
            return MergeNode(identity(path, attrs), attrs.size())
        }
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            if (attributes(readPath("$relative/catalog.sqlite$suffix")) != null) throw IOException("The selected merge catalog is not closed")
        }
        return SelectedMergeEvidence(identity(attempt, parent), file("catalog.sqlite"), file("merge.intent"), file("selected-merge.complete"))
    }

    enum class ExportRecord(val nameOnDisk: String) {
        INTENT("export.intent"), COMPLETE("export.complete"), ACKNOWLEDGED("export.ack"),
    }

    fun openExportRecord(record: ExportRecord) = open(record.nameOnDisk)

    fun createExportRecord(record: ExportRecord, bytes: ByteArray, beforeLink: () -> Unit) =
        create(record.nameOnDisk, beforeLink = beforeLink) { it.write(bytes) }

    fun requireAndForceExportStage(generation: LibraryGeneration, checkpoint: () -> Unit) {
        val stage = readPath("catalog.sqlite")
        if (!presentRegularFile(stage)) throw IOException("Private backup catalog is missing")
        FileChannel.open(stage, READ, WRITE, NOFOLLOW_LINKS).use {
            generation.verifyCatalog(Channels.newInputStream(it), checkpoint)
            it.force(true)
        }
        forceExportJournal()
        verifyExportStage(generation, checkpoint)
    }

    fun verifyExportStage(generation: LibraryGeneration, checkpoint: () -> Unit) =
        (open("catalog.sqlite") ?: throw IOException("Private backup catalog is missing"))
            .use { generation.verifyCatalog(it, checkpoint) }

    /** The private owner name starts as an exclusive hard link to the actual staged inode, not a second catalog copy. */
    fun anchorExportStage() {
        val stage = readPath("catalog.sqlite")
        val owner = readPath("export.catalog.owner")
        if (!presentRegularFile(stage)) throw IOException("Private backup catalog is missing")
        Files.createLink(owner, stage)
        forceExportJournal()
        requireExportStageOwner()
    }

    fun requireExportStageOwner() {
        val stage = readPath("catalog.sqlite")
        val owner = readPath("export.catalog.owner")
        if (!presentRegularFile(stage) || !presentRegularFile(owner) || !Files.isSameFile(stage, owner)) {
            throw IOException("The private backup catalog has no exact attempt owner")
        }
    }

    fun requireOnlyAnchoredExportStage() {
        requireExportStageOwner()
        try {
            Files.newDirectoryStream(rootPath).use { entries ->
                val names = entries.asSequence().take(3).map { it.fileName.toString() }.toSet()
                if (names != setOf("catalog.sqlite", "export.catalog.owner")) throw IOException("The backup stage retains other evidence")
            }
        } catch (_: DirectoryIteratorException) { throw IOException("The retained backup stage could not be read") }
    }

    /** No recovery caller may unlink an unstarted-looking attempt after process death. A live prepublication refusal is required. */
    fun discardUnpublishedExportStage(generation: LibraryGeneration, checkpoint: () -> Unit,
        requireOriginalAttempt: () -> Unit, requireOriginalStage: () -> Unit,
    ) {
        requireOriginalAttempt()
        requireOriginalStage()
        requireOnlyAnchoredExportStage()
        reclaimExportStage(generation, checkpoint) {
            requireOriginalAttempt()
            requireOriginalStage()
        }
        // Nonrecursive; a new or unexplained entry makes removal fail. Never delete a pathname reported by SQLite or by Files.
        requireOriginalAttempt()
        if (rootPath.toRealPath() != rootPath) throw IOException("The private backup stage changed")
        Files.delete(rootPath)
        forceDirectory(checkNotNull(paths.root.parentFile))
    }

    /** The caller has verified the acknowledgment and holds the private writer lane. Remove only the original closed stage. */
    fun reclaimExportStage(generation: LibraryGeneration, checkpoint: () -> Unit, beforeUnlink: () -> Unit = {}) {
        val stage = readPath("catalog.sqlite")
        val owner = readPath("export.catalog.owner")
        if (presentRegularFile(stage) || presentRegularFile(owner)) {
            requireExportStageOwner()
            verifyExportStage(generation, checkpoint)
            checkpoint()
            beforeUnlink()
            requireExportStageOwner()
            // If interruption leaves just one name, do not infer its identity from its bytes alone on the next attempt.
            Files.delete(owner)
            Files.delete(stage)
        }
        // An absent name can be a prior delivered unlink whose directory barrier failed.
        forceExportJournal()
        if (presentRegularFile(stage) || presentRegularFile(owner)) throw IOException("The acknowledged backup stage is still present")
    }

    /** A prior closed stage is explained only by an independently verified final record still linked to the same file. */
    fun requireResolvedExportStages(records: Set<ExportRecord>) {
        try {
            var count = 0
            Files.newDirectoryStream(rootPath, ".pristinepage-*.part").use { entries ->
                for (entry in entries) {
                    if (++count > ExportRecord.entries.size || !presentRegularFile(entry) ||
                        records.none { Files.isSameFile(entry, readPath(it.nameOnDisk)) }) {
                        throw IOException("The private backup retains an unacknowledged journal stage")
                    }
                }
            }
        } catch (_: DirectoryIteratorException) { throw IOException("The private backup stages could not be read") }
    }

    /** A journal may be positively absent in a fresh attempt; prior staged leaves or other evidence are not absence. */
    fun requireUnstartedExport() {
        try {
            Files.newDirectoryStream(rootPath).use { entries ->
                for (entry in entries) {
                    if (entry.fileName.toString() != "catalog.sqlite" || !presentRegularFile(readPath("catalog.sqlite"))) {
                        throw IOException("The private backup attempt retains unacknowledged evidence")
                    }
                }
            }
        } catch (_: DirectoryIteratorException) { throw IOException("The private backup attempt could not be read") }
    }

    fun forceExportJournal() {
        forceDirectory(paths.root)
        forceDirectory(checkNotNull(paths.root.parentFile))
    }

    /** Private grant records never cross a library export or accept a destination path from a provider. */
    fun createFolderGrant(record: LibraryFolderGrantRecord) =
        create(folderGrantPath(record.grantId), privateSupport = true) { it.write(record.encode()) }

    fun readFolderGrant(grantId: String): LibraryFolderGrantRecord? =
        open(folderGrantPath(grantId))?.use { input ->
            val bytes = ByteArray(LibraryFolderGrantRecord.MAX_BYTES)
            var count = 0
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                if (read == 0) throw IOException("The private folder binding could not be read")
                count += read
            }
            if (input.read() != -1) throw IOException("The private folder binding is oversized")
            LibraryFolderGrantRecord.decode(bytes.copyOf(count), grantId)
        }

    fun forceFolderGrants(grantId: String) {
        forceParentOf(folderGrantPath(grantId))
        forceDirectory(paths.root)
    }

    fun createFolderVerification(grantId: String, generation: LibraryGeneration) =
        create(folderVerificationPath(grantId, generation.id), privateSupport = true) { it.write(generation.encode()) }

    fun readFolderVerification(grantId: String, generationId: String): LibraryGeneration? =
        open(folderVerificationPath(grantId, generationId))?.use(LibraryGeneration::read)

    fun forceFolderVerification(grantId: String, generationId: String) {
        forceParentOf(folderVerificationPath(grantId, generationId))
        forceDirectory(paths.root)
    }

    fun forceMergeStage(generation: String, durableAncestor: Path, exactCatalogBytes: Long? = null) {
        val directory = mergeStagePath(generation)
        val stage = readPath("$directory/catalog.sqlite")
        if (!rootPath.startsWith(durableAncestor) || !Files.isDirectory(durableAncestor, NOFOLLOW_LINKS) ||
            durableAncestor.toRealPath() != durableAncestor
        ) throw IOException("Private merge stage has no admitted durable ancestor")
        if (!presentRegularFile(stage)) throw IOException("Private merge stage is missing")
        FileChannel.open(stage, WRITE, NOFOLLOW_LINKS).use {
            if (exactCatalogBytes != null && it.size() != exactCatalogBytes) throw IOException("The private merge stage extent changed")
            it.force(true)
        }
        var containing = checkNotNull(stage.parent)
        while (true) {
            forceDirectory(containing.toFile())
            if (containing == durableAncestor) break
            containing = checkNotNull(containing.parent)
        }
    }

    /** Only for a fresh private restore: opaque blob IDs, never source-row paths or logical filenames. */
    fun openPrivateOriginal(blob: String): InputStream? = open(privateOriginalPath(blob))

    fun createPrivateOriginal(blob: String, write: (OutputStream) -> Unit) = create(privateOriginalPath(blob), write = write)

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

    override fun createMergeMarker(generation: LibraryGeneration, bytes: ByteArray, requireStage: () -> Unit, requireCurrent: () -> Unit) {
        require(bytes.contentEquals(generation.encode()))
        create(markerPath(generation), beforeLink = requireCurrent, beforeStage = requireStage) { it.write(bytes) }
    }

    /** Iterates a bounded inventory and refuses uncertainty rather than silently opening an older generation. Does not publish, remove or repair anything. */
    fun readTip(checkpoint: () -> Unit = {}): LibraryGeneration = generationInventory(checkpoint).tip

    override fun generationInventory(checkpoint: () -> Unit): LibraryGenerationInventory {
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
        return LibraryGenerationInventory(markers, entries)
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

    private fun create(relativePath: String, privateSupport: Boolean = false, beforeLink: () -> Unit = {},
        beforeStage: () -> Unit = {}, write: (OutputStream) -> Unit,
    ) {
        val file = paths.beneath(relativePath)
        val destination = file.toPath()
        if (presentRegularFile(destination)) throw FileAlreadyExistsException(file.name)
        val parent = checkNotNull(file.parentFile)
        paths.createDirectories(parent, ::forceDirectory)
        if (privateSupport) admitPrivateSupportStage(parent.toPath())
        forceDirectory(parent)
        beforeStage()
        val stage = Files.createTempFile(parent.toPath(), ".pristinepage-", ".part")
        FileChannel.open(stage, WRITE, NOFOLLOW_LINKS).use { channel ->
            write(Channels.newOutputStream(channel))
            channel.force(true)
        }
        // The exclusive link makes the closed stage visible without replacing any existing leaf. A backend without this primitive refuses rather than copying into the final name.
        beforeLink()
        Files.createLink(destination, stage)
        forceDirectory(parent)
        // A failed write, close or sync keeps its exact stage as recovery evidence. Once durable, unlink only this attempt's temporary name; never remove the final name.
        try { Files.deleteIfExists(stage) } catch (_: IOException) { /* The verified object remains usable. */ }
    }

    private fun admitPrivateSupportStage(directory: Path) {
        // The settings lane serializes these writes. Refusal does not prune a locator or prevent reopening an exact existing record.
        try {
            Files.newDirectoryStream(directory).use { entries ->
                var retained = 0
                var stages = 0
                for (entry in entries) {
                    val name = entry.fileName.toString()
                    if (name.startsWith(".pristinepage-") && name.endsWith(".part") && ++stages >= 4) {
                        throw IOException("Retained library folder stages need attention")
                    }
                    // The closed stage and its exclusive publication link can add two directory entries.
                    if (++retained > 4_094) throw IOException("Retained library folder records need attention")
                }
            }
        } catch (_: DirectoryIteratorException) {
            throw IOException("Private library folder records could not be read")
        }
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
    private fun folderGrantPath(grantId: String): String {
        requireOpaqueId(grantId)
        return "folder-grants/$grantId"
    }

    private fun folderVerificationPath(grantId: String, generationId: String): String {
        requireOpaqueId(grantId)
        requireOpaqueId(generationId)
        return "folder-verifications/$grantId-$generationId"
    }
    private fun mergeStagePath(generation: String): String {
        requireOpaqueId(generation)
        return "merge-stages/$generation"
    }

    private fun privateOriginalPath(blob: String): String {
        requireOpaqueId(blob)
        return "sources/$blob/original.bin"
    }
}
