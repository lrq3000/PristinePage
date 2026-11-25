package app.pristinepage.data.library

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.DocumentsContract
import app.pristinepage.data.library.LibraryObjectTransfer.ObjectId
import app.pristinepage.data.library.RoomLibraryCatalogReader.Companion.MAX_PORTABLE_BYTES
import app.pristinepage.data.model.SourceBlobId
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
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
        return readTree(tree) { read, checkpoint ->
            read.read(reader.schemaVersion, previous?.generation, previous?.generation, MAX_TREE_ORIGINAL_BYTES) { generation, file ->
                reader.read(file) { sql ->
                    val catalog = LibraryPortableCatalogProjection(sql, checkpoint = checkpoint).snapshot()
                    val originals = catalog.originals.associate { it.sourceBlobId to ObjectId(it.byteSha256, it.byteCount) }
                    LibraryBackupRead.Projection(
                        VerifiedTree(tree.toString(), generation, catalog.albums.size, catalog.albums.sumOf { it.pages.size }, originals),
                        originals.values,
                    )
                }
            }.value
        }
    }

    /** Stream one original into caller-owned disposable output. The caller must discard that output on any failure and verify closure before using it. */
    suspend fun copyOriginal(verified: Verified, blob: SourceBlobId, output: OutputStream) {
        val selected = requireVerifiedTree(verified)
        val id = selected.original(blob)
        readTree(Uri.parse(selected.tree)) { read, _ -> read.copyExactOriginal(selected.generation, id, output) }
    }

    private fun requireVerifiedTree(verified: Verified): VerifiedTree =
        verified as? VerifiedTree ?: throw IOException("The library tree was not verified by this reader")

    private suspend fun <T> readTree(tree: Uri, block: (LibraryBackupRead, () -> Unit) -> T): T = withContext(Dispatchers.IO) {
        val checkpoint = { coroutineContext.ensureActive() }
        // An uncooperative provider can delay unwinding. Do not let repeated taps accumulate further blocked opens.
        if (!ATTEMPT.tryLock()) throw IOException("A library folder verification is already in progress")
        try {
            withLibraryProviderSignal { signal ->
                val root = localTreeDocumentId(tree) ?: throw IOException("This folder provider is not supported")
                val scratch = canonicalLibraryDirectory(scratchDirectory, app.filesDir.toPath().toRealPath())
                val backend = Backend(app, tree, root, scratch, signal, checkpoint)
                backend.requireRoot()
                val source = LibraryTreeReadSource(root, backend)
                val result = block(LibraryBackupRead(source, scratch, MAX_PORTABLE_BYTES, checkpoint), checkpoint)
                backend.requireRoot()
                result
            }
        } finally {
            ATTEMPT.unlock()
        }
    }

    private class Backend(
        private val app: Context, private val tree: Uri, private val rootId: String,
        private val privateScratch: Path, private val signal: CancellationSignal, private val checkpoint: () -> Unit,
    ) : LibraryTreeReadSource.Backend {
        private val resolver = app.contentResolver
        private val admitted = HashMap<String, Binding>()
        private data class Binding(val parent: String, val entry: LibraryTreeReadSource.Entry)

        fun requireRoot() {
            checkAccess()
            val rows = query(document(rootId), 1, checkpoint)
            if (rows.singleOrNull()?.let { it.id == rootId && it.directory } != true) {
                throw IOException("The selected library root could not be verified")
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
                throw IOException("The selected library file is unavailable")
            } catch (_: OperationCanceledException) {
                checkpoint()
                throw IOException("The selected library file was canceled")
            } ?: throw IOException("The selected library file is unavailable")
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
                throw IOException("The selected library directory is unavailable")
            } catch (_: IllegalArgumentException) {
                throw IOException("The selected library directory is unavailable")
            } catch (_: OperationCanceledException) {
                checkpoint()
                throw IOException("The selected library directory query was canceled")
            }
            checkAccess()
            return entries
        }

        private fun checkAccess() {
            checkpoint()
            if (!hasTrustedLocalProvider(app) || resolver.persistedUriPermissions.none { it.isReadPermission && it.uri.toString() == tree.toString() }) {
                throw IOException("Reconnect the selected library folder")
            }
        }
    }

    companion object {
        private const val AUTHORITY = "com.android.externalstorage.documents"
        private const val PACKAGE = "com.android.externalstorage"
        // This caps one detached admission's cumulative provider I/O, not storage capacity or single-scan size.
        private const val MAX_TREE_ORIGINAL_BYTES = 8L * 1024 * 1024 * 1024
        private val ATTEMPT = Mutex()

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

/** Register before calling the synchronous resolver. The caller owns any returned descriptor while checking cancellation. */
internal suspend fun <T> withLibraryProviderSignal(block: (CancellationSignal) -> T): T =
    suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        if (continuation.isActive) continuation.resumeWith(runCatching { block(signal) })
    }
