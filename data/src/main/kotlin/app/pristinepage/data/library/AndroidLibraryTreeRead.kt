package app.pristinepage.data.library

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.Os
import app.pristinepage.core.io.transferExactBytes
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.data.library.LibraryObjectTransfer.ObjectId
import app.pristinepage.data.library.RoomLibraryCatalogReader.Companion.MAX_PORTABLE_BYTES
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.SourceBlobId
import app.pristinepage.data.model.SourceInspection
import app.pristinepage.data.repository.CommittedSourceBinding
import app.pristinepage.data.repository.DocumentCatalog
import app.pristinepage.data.repository.ScopedCommittedOriginals
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** Detached verification of one persisted local Android tree. No path conversion, provider writes, activation or grant acquisition. */
class AndroidLibraryTreeRead(context: Context, private val scratchDirectory: File) {
    private val app = context.applicationContext
    private val reader = RoomLibraryCatalogReader(app, MAX_PORTABLE_BYTES)

    sealed interface Verified {
        val albums: Int
        val scans: Int
        val libraryId: String
        val generationId: String
    }

    private class VerifiedTree(
        val tree: String, val generation: LibraryGeneration, override val albums: Int, override val scans: Int,
        private val originals: Map<String, ObjectId>,
    ) : Verified {
        override val libraryId: String get() = generation.libraryId
        override val generationId: String get() = generation.id

        fun original(blob: SourceBlobId): ObjectId =
            originals[blob.value] ?: throw IOException("Original is not in the verified library view")
    }

    suspend fun verify(tree: Uri, expected: Verified? = null): Verified {
        val previous = expected?.let(::requireVerifiedTree)
        if (previous != null && previous.tree != tree.toString()) throw IOException("The verified library belongs to a different selected folder")
        return verifyExact(tree, previous?.generation)
    }

    /** A durable private selection supplies the full expected marker; reject a different tip before projecting rows or opening originals. */
    private suspend fun verifyExact(tree: Uri, expected: LibraryGeneration?, scratch: File = scratchDirectory): Verified =
        readTree(tree, scratch) { read, checkpoint ->
            readCatalog(read, expected, checkpoint) { generation, catalog ->
                val originals = catalog.originals.associate { it.sourceBlobId to ObjectId(it.byteSha256, it.byteCount) }
                VerifiedTree(tree.toString(), generation, catalog.albums.size, catalog.albums.sumOf { it.pages.size }, originals)
            }.value
        }

    internal data class PairInput(val tree: Uri, val expected: LibraryGeneration? = null)
    internal data class PairMember(val tree: Uri, val generation: LibraryGeneration, val snapshot: RoomLibrarySavedCatalog.Snapshot)
    internal data class MergePlan(val destination: PairInput, val incoming: PairInput, val candidate: LibraryCatalogMerge.Candidate)

    private class PairedOriginals(val destination: LibraryTreeReadSource, val incoming: LibraryTreeReadSource,
        val requireCurrent: () -> Unit, val checkpoint: () -> Unit, val availableDestinationBytes: () -> Long)

    /** Produce only a detached plan. Check byte equivalence and missing objects in the final admitted pair after private staging. */
    internal suspend fun planMerge(operationId: String, destination: PairInput, incoming: PairInput,
        requireAuthority: () -> Unit,
    ): MergePlan = withVerifiedPairOriginals(destination, incoming, requireAuthority) { left, right, originals ->
        val candidate = LibraryCatalogMerge.plan(operationId, left.snapshot, right.snapshot, originals.checkpoint)
        LibraryBackupRead.admitOriginals(candidate.catalog.originals.map { ObjectId(it.byteSha256, it.byteCount) },
            MAX_TREE_ORIGINAL_BYTES, originals.checkpoint)
        MergePlan(PairInput(left.tree, left.generation), PairInput(right.tree, right.generation), candidate)
    }

    /** Compare both current inventories and measure capacity on the held destination. This neither reserves space nor grants a write. */
    internal suspend fun estimateMerge(plan: MergePlan, metadataBytes: Long, requireAuthority: () -> Unit): LibraryMergePayloads.Estimate =
        withVerifiedPairOriginals(plan.destination, plan.incoming, requireAuthority) { left, right, originals ->
            // Each input already used its own ordinary verification budget. Charge comparison reopens separately.
            var remaining = 2 * MAX_TREE_ORIGINAL_BYTES
            fun open(source: LibraryTreeReadSource, id: ObjectId): InputStream? {
                if (id.byteCount > remaining) throw IOException("The library merge exceeds the original comparison budget")
                remaining -= id.byteCount
                return source.open(id)
            }
            val estimate = LibraryMergePayloads.estimate(plan.candidate, left.snapshot.originals, right.snapshot.originals,
                { open(originals.destination, it) }, { open(originals.incoming, it) },
                originals.requireCurrent, metadataBytes, originals.availableDestinationBytes(), originals.checkpoint)
            if (originals.availableDestinationBytes() < estimate.requiredBytes) {
                throw IOException("Not enough space for the library transfer")
            }
            estimate
        }

    /** Prepare a detached, resource-free result without delivering it or causing visible side effects from the projection. Not publication or activation authority. */
    internal suspend fun <T : Any> withVerifiedPair(left: PairInput, right: PairInput, requireAuthority: () -> Unit,
        project: (PairMember, PairMember) -> T,
    ): T = withVerifiedPairOriginals(left, right, requireAuthority) { a, b, _ -> project(a, b) }

    private suspend fun <T : Any> withVerifiedPairOriginals(left: PairInput, right: PairInput, requireAuthority: () -> Unit,
        project: (PairMember, PairMember, PairedOriginals) -> T,
    ): T = withContext(Dispatchers.IO) {
        LibraryProviderAttempt.runOwned { owner ->
            val checkpoint = owner::requireCurrent
            fun requireIssuingAuthority() {
                checkpoint()
                requireAuthority()
                checkpoint()
            }
            requireIssuingAuthority()
            val a = localTreeDocumentId(left.tree) ?: throw LibraryTreeUnavailable("This folder provider is not supported")
            val b = localTreeDocumentId(right.tree) ?: throw LibraryTreeUnavailable("This folder provider is not supported")
            val hints = LibraryTreeAncestryHint.parentHops(a, b)
            val scratchRoot = canonicalLibraryDirectory(scratchDirectory, app.filesDir.toPath().toRealPath())
            val detached = withLibraryProviderSignal { signal ->
                LibraryPairScratch.use(scratchRoot, bind = ::bindScratch) { scratch ->
                    val first = Backend(app, left.tree, a, scratch, signal, checkpoint)
                    val second = Backend(app, right.tree, b, scratch, signal, checkpoint)
                    first.openRootDirectory().use { heldLeft ->
                        second.openRootDirectory().use { heldRight ->
                            val leftIdentity = AndroidLibraryDescriptorAncestry.observe(heldLeft)
                            val rightIdentity = AndroidLibraryDescriptorAncestry.observe(heldRight)
                            AndroidLibraryDescriptorAncestry.withDerivedAnchor(heldLeft, heldRight, hints, checkpoint) { requireAncestry ->
                                fun requireCurrent() {
                                    requireIssuingAuthority()
                                    first.rebindRoot(leftIdentity)
                                    second.rebindRoot(rightIdentity)
                                    requireAncestry()
                                    requireIssuingAuthority()
                                }
                                val leftObjects = LibraryTreeReadSource(a, first)
                                val rightObjects = LibraryTreeReadSource(b, second)
                                val leftRead = backupRead(leftObjects, scratch, checkpoint)
                                val rightRead = backupRead(rightObjects, scratch, checkpoint)
                                requireCurrent()
                                val verifiedLeft = readCatalog(leftRead, left.expected, checkpoint, first::member).value
                                requireCurrent()
                                val verifiedRight = readCatalog(rightRead, right.expected, checkpoint, second::member).value
                                fun requireBothTips() {
                                    requireCurrent()
                                    leftRead.requireCurrent(verifiedLeft.generation)
                                    rightRead.requireCurrent(verifiedRight.generation)
                                    requireCurrent()
                                }
                                requireBothTips()
                                val projected = project(verifiedLeft, verifiedRight, PairedOriginals(leftObjects, rightObjects, ::requireCurrent,
                                    checkpoint, { availableBytes(heldLeft) }))
                                requireBothTips()
                                projected
                            }
                        }
                    }
                }
            }
            requireIssuingAuthority()
            detached
        }
    }

    private fun bindScratch(path: Path): () -> Unit {
        val key = localLibraryRootKey(path)
        return { if (localLibraryRootKey(path) != key) throw IOException("The library verification attempt changed") }
    }

    private fun availableBytes(directory: ParcelFileDescriptor): Long = try {
        val volume = Os.fstatvfs(directory.fileDescriptor)
        if (volume.f_frsize <= 0L || volume.f_bavail < 0L) throw ArithmeticException()
        Math.multiplyExact(volume.f_frsize, volume.f_bavail)
    } catch (_: ErrnoException) {
        throw IOException("The selected library capacity is unavailable")
    } catch (_: ArithmeticException) {
        throw IOException("The selected library capacity is unavailable")
    }

    private fun <T : Any> readCatalog(read: LibraryBackupRead, expected: LibraryGeneration?, checkpoint: () -> Unit,
        project: (LibraryGeneration, RoomLibrarySavedCatalog.Snapshot) -> T,
    ): LibraryBackupRead.Verified<T> =
        try { read.read(reader.schemaVersion, expected, expected, MAX_TREE_ORIGINAL_BYTES) { generation, file ->
            reader.read(file) { sql ->
                val catalog = LibraryPortableCatalogProjection(sql, checkpoint = checkpoint).snapshot()
                LibraryBackupRead.Projection(project(generation, catalog), catalog.originals.map { ObjectId(it.byteSha256, it.byteCount) })
            }
        } } catch (_: UnexpectedLibraryTip) {
            throw LibraryTreeUnavailable("Unexpected library tip during catalog admission")
        }

    /** Stream one original into caller-owned disposable output. The caller must discard that output on any failure and verify closure before using it. */
    suspend fun copyOriginal(verified: Verified, blob: SourceBlobId, output: OutputStream) {
        val selected = requireVerifiedTree(verified)
        val id = selected.original(blob)
        readTree(Uri.parse(selected.tree)) { read, _ -> read.copyExactOriginal(selected.generation, id, output) }
    }

    /** Borrow the closed file read-only; the caller must finish all file-backed and native work and close every borrowed handle before the callback exits. Discard provisional decoder output on any failed revalidation, cleanup or canceled result delivery. */
    suspend fun withVerifiedOriginal(
        verified: Verified, blob: SourceBlobId, block: suspend CoroutineScope.(File) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        val selected = requireVerifiedTree(verified)
        val objectId = selected.original(blob)
        if (!ORIGINAL.tryLock()) throw IOException("A library original is already being read")
        try {
            val checkpoint = { coroutineContext.ensureActive() }
            val scratch = canonicalLibraryDirectory(scratchDirectory, app.filesDir.toPath().toRealPath())
            if (scratch.toFile().usableSpace < objectId.byteCount + 16L * 1024) {
                throw IOException("Not enough private space to read the selected original")
            }
            LibraryOriginalScratch(scratch).use(checkpoint, { copyOriginal(selected, blob, it) }) { file ->
                Files.newInputStream(file.toPath(), READ, NOFOLLOW_LINKS).use {
                    transferExactBytes(it, null, objectId.byteCount, objectId.sha256, checkpoint)
                }
                checkpoint()
                coroutineScope { block(file) }
                checkpoint()
                readTree(Uri.parse(selected.tree)) { read, _ -> read.requireCurrent(selected.generation) }
            }
        } finally { ORIGINAL.unlock() }
    }

    private fun requireVerifiedTree(verified: Verified): VerifiedTree =
        verified as? VerifiedTree ?: throw IOException("The library tree was not verified by this reader")

    /** Rebuild a private Room catalog without importing original bytes. This detached read cannot activate a library or commit an edit. */
    suspend fun <T : Any> openWorkingCatalog(
        verified: Verified, block: suspend CoroutineScope.(DocumentCatalog) -> T,
    ): T = useWorkingCatalog({ verified }, {}) { catalog, _ -> block(catalog) }

    /** A detached read-only original port under the same verified catalog lifetime; it cannot change the selected library. */
    suspend fun openWorkingOriginals(
        verified: Verified, block: suspend CoroutineScope.(ScopedCommittedOriginals) -> Unit,
    ) = useWorkingCatalog({ verified }, {}) { _, originals -> block(originals) }

    internal suspend fun <T : Any> useSelectedWorkingLibrary(
        tree: Uri, generation: LibraryGeneration, confirmSelection: suspend () -> Unit,
        block: suspend CoroutineScope.(DocumentCatalog, ScopedCommittedOriginals) -> T,
    ): T = useWorkingCatalog({ attempt -> verifyExact(tree, generation, attempt) }, confirmSelection, block)

    private suspend fun <T : Any> useWorkingCatalog(
        verify: suspend (File) -> Verified, confirmSelection: suspend () -> Unit,
        block: suspend CoroutineScope.(DocumentCatalog, ScopedCommittedOriginals) -> T,
    ): T = withContext(Dispatchers.IO) {
        if (!WORKING.tryLock()) throw IOException("A library working catalog is already open")
        try {
            val checkpoint = { coroutineContext.ensureActive() }
            val scratch = canonicalLibraryDirectory(scratchDirectory, app.filesDir.toPath().toRealPath())
            if (scratch.toFile().usableSpace < 3 * MAX_PORTABLE_BYTES) throw IOException("Not enough private space for a library working catalog")
            Files.newDirectoryStream(scratch, "library-working-*").use {
                if (it.asSequence().take(4).count() >= 4) throw IOException("Retained working catalogs need attention")
            }
            checkpoint()
            val attempt = Files.createTempDirectory(scratch, "library-working-")
            val file = attempt.resolve("working.sqlite").toFile()
            val stage = RoomLibraryCatalogStage(app)
            var mayRetire = true
            try {
                confirmSelection()
                val selected = requireVerifiedTree(verify(attempt.toFile()))
                val tree = Uri.parse(selected.tree)
                val snapshot = readTree(tree, attempt.toFile()) { read, _ ->
                    try { read.read(reader.schemaVersion, selected.generation, selected.generation) { _, catalog ->
                        reader.read(catalog) { sql ->
                            // The exact proof already admitted membership. This graph owns no payload; each original read authenticates bytes anew.
                            LibraryBackupRead.Projection(LibraryPortableCatalogProjection(sql, checkpoint = checkpoint).snapshot(), emptyList())
                        }
                    }.value } catch (_: UnexpectedLibraryTip) {
                        throw LibraryTreeUnavailable("The selected library generation changed")
                    }
                }
                mayRetire = false
                stage.write(snapshot, file, acknowledgeClose = { mayRetire = true })
                reader.verifyStagedProjection(file, snapshot, checkpoint)
                val access = LibraryWorkingCatalogAccess {
                    readTree(tree, attempt.toFile()) { read, _ ->
                        try { read.requireCurrent(selected.generation) }
                        catch (_: IOException) { throw LibraryTreeUnavailable("The selected library catalog changed or is unavailable") }
                    }
                    confirmSelection()
                }
                val originals = workingOriginals(selected, snapshot, access, checkpoint)
                mayRetire = false
                val result = stage.useCatalog(file, access, acknowledgeClose = { mayRetire = true }) { catalog ->
                    block(catalog, originals)
                }
                access.requireCurrent()
                result
            } finally {
                // Only close-acknowledged, attempt-owned working files are retired; uncertain attempts consume the bounded private budget.
                if (mayRetire) try {
                    for (name in listOf("working.sqlite", "working.sqlite-journal", "working.sqlite-wal", "working.sqlite-shm")) {
                        Files.deleteIfExists(attempt.resolve(name))
                    }
                    Files.delete(attempt)
                } catch (_: IOException) { /* Retain the attempt on uncertain cleanup; no other directory is a target. */ }
            }
        } finally {
            WORKING.unlock()
        }
    }

    private fun workingOriginals(
        selected: VerifiedTree, snapshot: RoomLibrarySavedCatalog.Snapshot, access: LibraryWorkingCatalogAccess, checkpoint: () -> Unit,
    ): ScopedCommittedOriginals {
        val sources = snapshot.sources.associateBy { checkpoint(); it.sourceId }
        val blobs = snapshot.originals.associateBy { checkpoint(); it.sourceBlobId }
        return ScopedCommittedOriginals { id, expected, read ->
            access.read {
                val source = sources[id.value] ?: throw RepositoryFailure(OperationError(
                    OperationErrorCode.SOURCE_UNAVAILABLE, false, "source_not_found", id.value,
                ))
                val blob = blobs.getValue(source.sourceBlobId)
                if (expected != null && (source != expected.source.copy(refCount = source.refCount) ||
                        blob != expected.blob.copy(sourceCount = blob.sourceCount))) {
                    throw RepositoryFailure(OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "committed_source_binding_mismatch", id.value))
                }
                val inspection = SourceInspection(blob.mime, PixelSize(blob.encodedWidth, blob.encodedHeight),
                    PixelSize(source.width, source.height), source.exifOrientation, source.profileSha256,
                    source.conversionFingerprint, source.pixelSha256)
                val binding = CommittedSourceBinding(id, SourceBlobId(blob.sourceBlobId), blob.byteSha256, blob.byteCount, inspection)
                try {
                    withVerifiedOriginal(selected, binding.sourceBlobId) { file ->
                        access.requireLoanDelivery()
                        read(binding, file)
                    }
                } catch (_: UnexpectedLibraryTip) {
                    throw LibraryTreeUnavailable("The selected library generation changed during decode")
                }
            }
        }
    }

    private suspend fun <T> readTree(
        tree: Uri, scratchLocation: File = scratchDirectory, block: (LibraryBackupRead, () -> Unit) -> T,
    ): T = withContext(Dispatchers.IO) {
        val checkpoint = { coroutineContext.ensureActive() }
        LibraryProviderAttempt.run {
            withLibraryProviderSignal { signal ->
                val root = localTreeDocumentId(tree) ?: throw LibraryTreeUnavailable("This folder provider is not supported")
                val scratch = canonicalLibraryDirectory(scratchLocation, app.filesDir.toPath().toRealPath())
                val backend = Backend(app, tree, root, scratch, signal, checkpoint)
                backend.requireRoot()
                val result = block(backupRead(LibraryTreeReadSource(root, backend), scratch, checkpoint), checkpoint)
                backend.requireRoot()
                result
            }
        }
    }

    private fun backupRead(source: LibraryTreeReadSource, scratch: Path, checkpoint: () -> Unit) =
        LibraryBackupRead(source, scratch, MAX_PORTABLE_BYTES, checkpoint)

    private class Backend(
        private val app: Context, private val tree: Uri, private val rootId: String,
        private val privateScratch: Path, private val signal: CancellationSignal, private val checkpoint: () -> Unit,
    ) : LibraryTreeReadSource.Backend {
        private val resolver = app.contentResolver
        private val admitted = HashMap<String, Binding>()
        private data class Binding(val parent: String, val entry: LibraryTreeReadSource.Entry)

        fun member(generation: LibraryGeneration, catalog: RoomLibrarySavedCatalog.Snapshot) = PairMember(tree, generation, catalog)

        fun requireRoot() {
            checkAccess()
            val rows = query(document(rootId), 1, checkpoint)
            if (rows.singleOrNull()?.let { it.id == rootId && it.directory } != true) {
                throw LibraryTreeUnavailable("The selected library root could not be verified")
            }
        }

        fun openRootDirectory(): ParcelFileDescriptor {
            requireRoot()
            val descriptor = try {
                resolver.openFileDescriptor(document(rootId), "r", signal)
            } catch (_: SecurityException) {
                throw LibraryTreeUnavailable("The selected library directory is unavailable")
            } catch (_: OperationCanceledException) {
                checkpoint()
                throw LibraryTreeUnavailable("The selected library directory open was canceled")
            } ?: throw LibraryTreeUnavailable("The selected library directory is unavailable")
            try {
                checkAccess()
                return descriptor
            } catch (failure: Throwable) {
                try { descriptor.close() } catch (closeFailure: Exception) { failure.addSuppressed(closeFailure) }
                throw failure
            }
        }

        fun rebindRoot(held: AndroidLibraryDescriptorAncestry.Observation) {
            openRootDirectory().use {
                if (AndroidLibraryDescriptorAncestry.observe(it) != held) throw LibraryTreeUnavailable("The selected library directory changed")
            }
        }

        override fun requireIndependentScratch(scratch: Path) {
            // The admitted AOSP provider's primary tree addresses shared storage only. Scratch is a verified app-private files directory.
            if (scratch != privateScratch) throw IOException("The library verifier needs private scratch storage")
        }

        override fun list(parentId: String, maximum: Int, checkpoint: () -> Unit): List<LibraryTreeReadSource.Entry> {
            checkAccess()
            if (parentId != rootId && admitted[parentId]?.entry?.directory != true) {
                throw IOException("The library directory was not enumerated in the selected tree")
            }
            val before = admitted.filterValues { it.parent == parentId }
            val items = query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId), maximum, checkpoint)
            for (entry in items) {
                if (entry.id == rootId || entry.id == parentId) throw IOException("The selected library contains a directory alias")
                val old = admitted[entry.id]
                if (old != null && old != Binding(parentId, entry)) throw IOException("A document identity changed in the selected tree")
            }
            before.keys.forEach(admitted::remove)
            items.forEach { admitted[it.id] = Binding(parentId, it) }
            return items
        }

        override fun open(id: String): InputStream {
            checkAccess()
            val item = admitted[id] ?: throw IOException("The library file was not enumerated in the selected tree")
            if (item.entry.directory) throw IOException("The enumerated library entry is not a file")
            val descriptor = try { resolver.openAssetFileDescriptor(document(id), "r", signal) } catch (_: SecurityException) {
                throw LibraryTreeUnavailable("The selected library file is unavailable")
            } catch (_: OperationCanceledException) {
                checkpoint()
                throw IOException("The selected library file was canceled")
            } ?: throw LibraryTreeUnavailable("The selected library file is unavailable")
            try {
                checkAccess()
                return descriptor.createInputStream()
            } catch (failure: Throwable) {
                try { descriptor.close() } catch (closeFailure: Exception) { failure.addSuppressed(closeFailure) }
                throw failure
            }
        }

        private fun document(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

        private fun query(uri: Uri, maximum: Int, checkpoint: () -> Unit): List<LibraryTreeReadSource.Entry> {
            checkpoint()
            val entries = try {
                resolver.query(uri, LibraryTreeProviderRows.projection, null, null, null, signal).use {
                    LibraryTreeProviderRows.read(it, maximum, checkpoint)
                }
            } catch (_: SecurityException) {
                throw LibraryTreeUnavailable("The selected library directory is unavailable")
            } catch (_: IllegalArgumentException) {
                throw LibraryTreeUnavailable("The selected library directory is unavailable")
            } catch (_: OperationCanceledException) {
                checkpoint()
                throw IOException("The selected library directory query was canceled")
            }
            checkAccess()
            return entries
        }

        private fun checkAccess() {
            checkpoint()
            if (!hasTrustedLocalProvider(app) || !hasExactReadGrant(app, tree)) {
                throw LibraryTreeUnavailable("Reconnect the selected library folder")
            }
        }
    }

    companion object {
        private const val AUTHORITY = "com.android.externalstorage.documents"
        private const val PACKAGE = "com.android.externalstorage"
        // This caps one detached admission's cumulative provider I/O, not storage capacity or single-scan size.
        internal const val MAX_TREE_ORIGINAL_BYTES = 8L * 1024 * 1024 * 1024
        private val WORKING = Mutex()
        private val ORIGINAL = Mutex()

        private fun hasExactReadGrant(app: Context, tree: Uri): Boolean =
            app.contentResolver.persistedUriPermissions.any { it.isReadPermission && it.uri.toString() == tree.toString() }

        /** Exact primary shared-storage or AOSP Documents-subtree grant. Never a volume root, single document URI, or translated filesystem path. */
        fun localTreeDocumentId(uri: Uri): String? {
            if (uri.scheme != "content" || uri.encodedAuthority != AUTHORITY || uri.encodedQuery != null || uri.encodedFragment != null ||
                uri.toString().length > 4096 || !DocumentsContract.isTreeUri(uri)
            ) return null
            val id = DocumentsContract.getTreeDocumentId(uri)
            // AOSP may identify the same Documents child by its more specific home root. Keep that identifier verbatim for grants and provider calls.
            if (id.length > 2048 || (!id.startsWith("primary:") && !id.startsWith("home:")) ||
                id.substringAfter(':').split('/').any { it.isBlank() || it == "." || it == ".." || it.any(Char::isISOControl) } ||
                DocumentsContract.buildTreeDocumentUri(AUTHORITY, id).toString() != uri.toString()
            ) return null
            return id
        }

        fun hasTrustedLocalProvider(context: Context): Boolean {
            val resolved = context.packageManager.resolveContentProvider(AUTHORITY, 0) ?: return false
            return resolved.enabled && resolved.packageName == PACKAGE && resolved.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
        }
    }
}

/** Known loss of provider identity or access; private scratch and callback exceptions are not this state. */
internal class LibraryTreeUnavailable(message: String) : IOException(message)

/** Register before calling the synchronous resolver. The caller owns any returned descriptor while checking cancellation. */
internal suspend fun <T> withLibraryProviderSignal(block: (CancellationSignal) -> T): T =
    suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        if (continuation.isActive) continuation.resumeWith(runCatching { block(signal) })
    }
