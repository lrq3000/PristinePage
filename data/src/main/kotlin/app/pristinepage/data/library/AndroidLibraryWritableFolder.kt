package app.pristinepage.data.library

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Capacity observation for one exact local writable tree. It neither reserves space nor mutates a provider. */
class AndroidLibraryWritableFolder(context: Context) {
    private val app = context.applicationContext

    internal enum class Access { PERSISTED, LIVE_FILES_RESULT }

    suspend fun availableBytes(tree: Uri): Long = availableBytes(tree, Access.PERSISTED)

    /** Advisory probe only for the exact URI of a live Files result. No permission is persisted or released. */
    suspend fun availableFromFilesResult(tree: Uri): Long = availableBytes(tree, Access.LIVE_FILES_RESULT)

    internal suspend fun availableBytes(tree: Uri, access: Access): Long = useTree(tree, access) { it.capacity() }

    /** Keep provider calls and every returned descriptor in the same bounded, non-escaping I/O scope. */
    internal suspend fun <T> useTree(tree: Uri, access: Access, block: (WritableTree) -> T): T = withContext(Dispatchers.IO) {
        val checkpoint = { coroutineContext.ensureActive() }
        val root = AndroidLibraryTreeRead.localTreeDocumentId(tree) ?: throw IOException("Unsupported writable library folder")
        LibraryProviderAttempt.run {
            withLibraryProviderSignal { signal -> WritableTree(app, tree, root, signal, checkpoint, access).guarded(block) }
        }
    }

    internal class WritableTree(
        private val app: Context, val tree: Uri, val root: String,
        private val signal: CancellationSignal, val checkpoint: () -> Unit, private val access: Access,
    ) {
        private val resolver = app.contentResolver

        fun capacity(): Long = openDirectory(root).use { capacity(root, it) }

        fun capacity(id: String, anchor: ParcelFileDescriptor): Long {
            val volume = Os.fstatvfs(anchor.fileDescriptor)
            val available = try {
                if (volume.f_frsize <= 0 || volume.f_bavail < 0) throw ArithmeticException()
                Math.multiplyExact(volume.f_frsize, volume.f_bavail)
            } catch (_: ArithmeticException) { throw IOException("Writable library capacity is unavailable") }
            requireDirectory(id, anchor)
            return available
        }

        fun openDirectory(id: String): ParcelFileDescriptor {
            val row = query(document(id), 1).singleOrNull()
            if (row == null || row.id != id || !row.directory) throw IOException("The writable library directory could not be identified")
            val descriptor = open(id, "r")
            try {
                directoryIdentity(descriptor)
                return descriptor
            } catch (failure: Throwable) {
                try { descriptor.close() } catch (closeFailure: Exception) { failure.addSuppressed(closeFailure) }
                throw failure
            }
        }

        fun requireDirectory(id: String, anchor: ParcelFileDescriptor) = openDirectory(id).use { current ->
            if (directoryIdentity(anchor) != directoryIdentity(current)) throw IOException("The writable library directory changed")
        }

        fun children(parent: String, limit: Int): List<LibraryTreeReadSource.Entry> =
            query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent), limit)

        fun create(parent: String, mime: String, name: String): String {
            requireExactAccess()
            val expected = "$parent/$name"
            val returned = resolverCall { DocumentsContract.createDocument(resolver, document(parent), mime, name) }
            requireExactAccess()
            if (returned != document(expected)) throw IOException("The provider did not create the exact requested library name")
            return expected
        }

        fun move(id: String, stage: String, destination: String) {
            requireExactAccess()
            // The response is not a publication receipt, including a returned null. The caller always proves names and bytes.
            resolverCall { DocumentsContract.moveDocument(resolver, document(id), document(stage), document(destination)) }
        }

        fun open(id: String, mode: String): ParcelFileDescriptor {
            requireExactAccess()
            val descriptor = resolverCall { resolver.openFileDescriptor(document(id), mode, signal) }
                ?: throw IOException("The writable library document is unavailable")
            try {
                requireExactAccess()
                return descriptor
            } catch (failure: Throwable) {
                try { descriptor.close() } catch (closeFailure: Exception) { failure.addSuppressed(closeFailure) }
                throw failure
            }
        }

        private fun document(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

        private fun query(uri: Uri, limit: Int): List<LibraryTreeReadSource.Entry> {
            requireExactAccess()
            val rows = resolverCall {
                resolver.query(uri, LibraryTreeProviderRows.projection, null, null, null, signal).use {
                    LibraryTreeProviderRows.read(it, limit, checkpoint)
                }
            }
            requireExactAccess()
            return rows
        }

        private inline fun <T> resolverCall(call: () -> T): T = try { call() }
        catch (_: IllegalArgumentException) {
            checkpoint()
            throw IOException("The writable library directory is unavailable")
        } catch (_: FileNotFoundException) {
            checkpoint()
            throw IOException("The writable library directory is unavailable")
        }

        fun directoryIdentity(directory: ParcelFileDescriptor): Pair<Long, Long> {
            checkpoint()
            val stat = Os.fstat(directory.fileDescriptor)
            if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_ino <= 0) throw IOException("The writable library root has no directory identity")
            return stat.st_dev to stat.st_ino
        }

        fun requirePrivateJournal(path: Path) {
            if (app.filesDir.toPath().toRealPath() != path) throw IOException("The selected library revision requires its exact private journal")
        }

        fun requireExactAccess() {
            checkpoint()
            if (!AndroidLibraryTreeRead.hasTrustedLocalProvider(app)) throw IOException("The exact library folder is not writable")
            val granted = when (access) {
                Access.PERSISTED -> resolver.persistedUriPermissions.any {
                    it.isReadPermission && it.isWritePermission && it.uri.toString() == tree.toString()
                }
                // The app admitted the exact URI and flags from its one-shot result. Android alone decides whether its grant is still usable.
                Access.LIVE_FILES_RESULT -> app.checkUriPermission(tree, Process.myPid(), Process.myUid(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
            }
            if (!granted) throw IOException("The exact library folder is not writable")
            checkpoint()
        }

        fun <T> guarded(block: (WritableTree) -> T): T = try { block(this) }
        catch (_: SecurityException) {
            checkpoint()
            throw IOException("The writable library permission is unavailable")
        } catch (_: OperationCanceledException) {
            checkpoint()
            throw IOException("The writable library provider canceled its response")
        } catch (_: ErrnoException) {
            checkpoint()
            throw IOException("The writable library descriptor failed")
        }
    }
}
