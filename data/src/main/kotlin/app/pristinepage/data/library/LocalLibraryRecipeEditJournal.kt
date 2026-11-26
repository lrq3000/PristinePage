package app.pristinepage.data.library

import app.pristinepage.core.pipeline.requireSha256
import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.files.PlatformDirectorySync
import app.pristinepage.data.library.ActiveLibraryFence.Location
import app.pristinepage.data.library.LocalLibraryDirectory.RecipeEditRecord
import app.pristinepage.data.library.RoomLibraryCatalogReader.Companion.MAX_PORTABLE_BYTES
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicBoolean

/** Private proof for one active selected edit. The live caller owns the canonical attempt and independently closes all provider scopes before completing. */
internal class LocalLibraryRecipeEditJournal(
    attempt: File, sync: DirectorySync = PlatformDirectorySync, private val checkpoint: () -> Unit = {},
    private val nodeIdentity: (Path, BasicFileAttributes) -> Any,
) {
    private val directory = LocalLibraryDirectory(attempt, sync)
    private val root = directory.rootPath

    data class Intent(val binding: LocalSelectedLibraryAuthority.Selected.Folder, val rootSha256: String, val successor: LibraryGeneration) {
        private val predecessor get() = binding.expectedGeneration
        init {
            requireSha256(rootSha256)
            val selection = binding.selection
            val location = requireNotNull(selection.location as? Location.Folder)
            require(binding.grant.returnedModes == 3 && location.grantId == binding.grant.grantId &&
                location.admittedGenerationId == predecessor.id && selection.identity.libraryId == predecessor.libraryId &&
                selection.identity.activation < Long.MAX_VALUE)
            require(selection == LocalSelectedLibraryAuthority.boundSelection(selection, binding.grant, predecessor))
            require(successor.libraryId == predecessor.libraryId && successor.predecessorId == predecessor.id &&
                successor.id != predecessor.predecessorId && successor.catalogSchema == predecessor.catalogSchema)
            require(listOf(predecessor, successor).all { it.catalog.byteCount in 512..MAX_PORTABLE_BYTES && it.catalog.byteCount % 512L == 0L })
        }

        fun encode(): ByteArray {
            val location = binding.selection.location as Location.Folder
            return "pristinepage-recipe-edit-intent-v1\n${binding.selection.identity.activation}\n${location.grantId}\n${location.verificationSha256}\n$rootSha256\n"
                .toByteArray(Charsets.US_ASCII) + predecessor.encode() + successor.encode()
        }

        fun completion(): ByteArray =
            "pristinepage-recipe-edit-complete-v1\n${backupSha256(encode())}\n".toByteArray(Charsets.US_ASCII)

        fun adoptedSelection(): LocalSelectedLibraryAuthority.Selected.Folder {
            val identity = binding.selection.identity.copy(activation = binding.selection.identity.activation + 1)
            val unbound = ActiveLibraryFence.Selection(Location.Folder(binding.grant.grantId, successor.id), identity)
            return LocalSelectedLibraryAuthority.Selected.Folder(
                LocalSelectedLibraryAuthority.boundSelection(unbound, binding.grant, successor), binding.grant, successor)
        }

        fun acknowledgment(): ByteArray {
            val adopted = adoptedSelection().selection
            val proof = (adopted.location as Location.Folder).verificationSha256
            return "pristinepage-recipe-edit-adopted-v1\n${backupSha256(completion())}\n${adopted.identity.activation}\n$proof\n"
                .toByteArray(Charsets.US_ASCII)
        }
    }

    enum class Phase { INTENT_ONLY, COMPLETED, ACKNOWLEDGED }

    sealed interface Receipt {
        val intent: Intent
        fun requireCurrent()
    }

    private inner class Completed(override val intent: Intent, private val proof: Evidence) : Receipt {
        override fun requireCurrent() {
            if (inspectOwned(intent, null) != proof) invalid()
        }
    }

    sealed interface LiveCompletion {
        fun recordAfterProviderClosure(published: LibraryGeneration): Receipt
    }

    fun start(intent: Intent, requireOwner: () -> Unit): LiveCompletion {
        requireAttempt(intent)
        requireOwner()
        Files.newDirectoryStream(root).use { entries ->
            if (entries.asSequence().take(2).map { it.fileName.toString() }.toList() != listOf("catalog.sqlite")) {
                throw IOException("The recipe edit attempt is not fresh")
            }
        }
        directory.requireAndForceClosedCatalog(intent.successor, checkpoint)
        requireSidecarsAbsent()
        requireOwner()
        directory.anchorClosedCatalog(CATALOG_OWNER)
        fun requirePrepared(ownedStage: Path?) {
            requireStage(intent)
            evidence(intent, Phase.INTENT_ONLY, includeIntent = false, ownedStage)
            requireOwner()
        }
        directory.createRecipeEditRecord(RecipeEditRecord.INTENT, intent.encode(), ::identity, ::requirePrepared)
        val initial = inspectOwned(intent, null)?.takeIf { it.phase == Phase.INTENT_ONLY } ?: invalid()
        requireOwner()
        return Live(intent, initial, requireOwner)
    }

    /** A known exact attempt may be inspected without creating a receipt, touching the provider, or reviving its writer. */
    fun inspect(intent: Intent): Phase? = inspectOwned(intent, null)?.phase

    /** Private terminal evidence only. The caller holds the selected-write lane and reopens the exact adopted binding. */
    fun acknowledgeAdopted(intent: Intent, adopted: LocalSelectedLibraryAuthority.Selected.Folder, requireCurrent: () -> Unit) {
        if (adopted != intent.adoptedSelection()) invalid()
        requireCurrent()
        val before = inspectOwned(intent, null) ?: invalid()
        requireCurrent()
        if (before.phase == Phase.ACKNOWLEDGED) return
        if (before.phase != Phase.COMPLETED) invalid()
        val published = directory.createRecipeEditRecord(RecipeEditRecord.ADOPTED, intent.acknowledgment(), ::identity) { ownedStage ->
            if (inspectOwned(intent, ownedStage) != before) invalid()
            requireCurrent()
        }
        val after = inspectOwned(intent, null)?.takeIf { it.phase == Phase.ACKNOWLEDGED } ?: invalid()
        if (after.nodes[RecipeEditRecord.ADOPTED.leaf] != published || after.nodes - RecipeEditRecord.ADOPTED.leaf != before.nodes) invalid()
        requireCurrent()
    }

    /** Reconstruct a private observation for the exact issuing selection. This does not return a live receipt or prove current provider bytes. */
    fun observeForRecovery(binding: LocalSelectedLibraryAuthority.Selected.Folder): Observation {
        requireAttemptDirectory()
        val intent = directory.openRecipeEditRecord(RecipeEditRecord.INTENT)?.use { readIntent(it, binding) } ?: invalid()
        return Observation(intent, inspect(intent) ?: invalid())
    }

    data class Observation(val intent: Intent, val phase: Phase)

    private data class Evidence(val phase: Phase, val nodes: Map<String, Any>)

    private fun inspectOwned(intent: Intent, ownedStage: Path?): Evidence? {
        requireAttempt(intent)
        val observed = readPhase(intent) ?: return null
        val exact = evidence(intent, observed, includeIntent = true, ownedStage)
        requireStage(intent)
        directory.forceExportJournal()
        requireStage(intent)
        if (readPhase(intent) != observed || evidence(intent, observed, includeIntent = true, ownedStage) != exact) invalid()
        return exact
    }

    private inner class Live(private val intent: Intent, private val initial: Evidence, private val requireOwner: () -> Unit) : LiveCompletion {
        private val used = AtomicBoolean()

        override fun recordAfterProviderClosure(published: LibraryGeneration): Receipt {
            if (!used.compareAndSet(false, true) || published != intent.successor) invalid()
            val pending = inspectOwned(intent, null)?.takeIf { it.phase == Phase.INTENT_ONLY } ?: invalid()
            if (pending.nodes.filterKeys { !it.endsWith(".spent") } != initial.nodes) invalid()
            fun requirePending(ownedStage: Path?) {
                if (inspectOwned(intent, ownedStage) != pending) invalid()
                requireOwner()
            }
            requirePending(null)
            directory.createRecipeEditRecord(RecipeEditRecord.COMPLETED, intent.completion(), ::identity, ::requirePending)
            val complete = inspectOwned(intent, null)?.takeIf { it.phase == Phase.COMPLETED } ?: invalid()
            if (complete.nodes - RecipeEditRecord.COMPLETED.leaf != pending.nodes) invalid()
            requireOwner()
            return Completed(intent, complete)
        }
    }

    /** Never adopt an unlinked spill. The exclusive file writer supplies only its own pending inode before link. */
    private fun evidence(intent: Intent, phase: Phase, includeIntent: Boolean, ownedStage: Path?): Evidence {
        val records = buildList {
            if (includeIntent) add(RecipeEditRecord.INTENT.leaf)
            if (phase != Phase.INTENT_ONLY) add(RecipeEditRecord.COMPLETED.leaf)
            if (phase == Phase.ACKNOWLEDGED) add(RecipeEditRecord.ADOPTED.leaf)
        }
        val known = (setOf("catalog.sqlite", CATALOG_OWNER) + records).toMutableSet()
        val allowedSpends = if (includeIntent) listOf(intent.successor.catalogPath, "generations/${intent.successor.id}.complete")
            .map { "${backupSha256(it.toByteArray(Charsets.US_ASCII))}.spent" }.toSet() else emptySet()
        var count = 0
        Files.newDirectoryStream(root).use { stream ->
            for (entry in stream) {
                checkpoint()
                if (++count > 12 || !Files.isRegularFile(entry, NOFOLLOW_LINKS)) invalid()
                val name = entry.fileName.toString()
                if (name in known) continue
                if (name in allowedSpends) {
                    known.add(name)
                    continue
                }
                if (name.startsWith(".pristinepage-") && name.endsWith(".part") &&
                    (entry == ownedStage || records.any { Files.isSameFile(entry, root.resolve(it)) })) continue
                invalid()
            }
        }
        return Evidence(phase, known.associateWith { identity(root.resolve(it)) } + ("." to identity(root)))
    }

    private fun identity(path: Path): Any {
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (if (path == root) !attrs.isDirectory else !attrs.isRegularFile) invalid()
        return nodeIdentity(path, attrs)
    }
    private fun readPhase(intent: Intent): Phase? {
        val known = directory.openRecipeEditRecord(RecipeEditRecord.INTENT)?.use { requireExact(it, intent.encode()) }
        val complete = directory.openRecipeEditRecord(RecipeEditRecord.COMPLETED)?.use { requireExact(it, intent.completion()) }
        val adopted = directory.openRecipeEditRecord(RecipeEditRecord.ADOPTED)?.use {
            if (known == null || complete == null) invalid()
            requireExact(it, intent.acknowledgment())
        }
        if (known == null) {
            Files.newDirectoryStream(root).use {
                if (complete != null || it.asSequence().take(2).any { node -> node.fileName.toString() != "catalog.sqlite" }) invalid()
            }
            return null
        }
        return when {
            adopted != null -> Phase.ACKNOWLEDGED
            complete != null -> Phase.COMPLETED
            else -> Phase.INTENT_ONLY
        }
    }

    private fun requireStage(intent: Intent) {
        requireAttempt(intent)
        requireSidecarsAbsent()
        directory.requireClosedCatalogOwner(CATALOG_OWNER)
        directory.verifyExportStage(intent.successor, checkpoint)
        directory.requireClosedCatalogOwner(CATALOG_OWNER)
        requireSidecarsAbsent()
    }

    private fun requireAttempt(intent: Intent) {
        requireAttemptDirectory()
        if (root.fileName.toString() != intent.successor.id) invalid()
    }

    private fun requireAttemptDirectory() {
        checkpoint()
        if (!Files.isDirectory(root, NOFOLLOW_LINKS) || root.toRealPath() != root) invalid()
    }

    private fun readIntent(input: InputStream, binding: LocalSelectedLibraryAuthority.Selected.Folder): Intent {
        val bytes = ByteArray(MAX_INTENT_BYTES)
        var count = 0
        while (true) {
            checkpoint()
            val byte = input.read()
            if (byte == -1) break
            if (count == bytes.size || (byte != 10 && byte !in 32..126)) invalid()
            bytes[count++] = byte.toByte()
        }
        val raw = String(bytes, 0, count, Charsets.US_ASCII)
        if (!raw.endsWith('\n')) invalid()
        val fields = raw.dropLast(1).split('\n', limit = 20)
        // Five envelope fields precede the two seven-field markers. The predecessor comes from the caller's durable binding, not from this record.
        if (fields.size != 19) invalid()
        val successor = fields.subList(12, 19).joinToString("\n", postfix = "\n")
            .byteInputStream(Charsets.US_ASCII).use(LibraryGeneration::read)
        val intent = try { Intent(binding, fields[4], successor) }
        catch (_: IllegalArgumentException) { invalid() }
        if (intent.encode().toString(Charsets.US_ASCII) != raw) invalid()
        return intent
    }

    private fun requireSidecarsAbsent() {
        if (listOf("-wal", "-shm", "-journal").any { !Files.notExists(root.resolve("catalog.sqlite$it"), NOFOLLOW_LINKS) }) invalid()
    }

    private fun requireExact(input: InputStream, expected: ByteArray) {
        for (byte in expected) {
            checkpoint()
            if (input.read() != (byte.toInt() and 255)) invalid()
        }
        if (input.read() != -1) invalid()
    }

    private fun invalid(): Nothing = throw IOException("The selected recipe edit evidence is not exact")

    private companion object {
        const val CATALOG_OWNER = "recipe-edit.catalog-owner"
        const val MAX_INTENT_BYTES = 1024
    }
}
