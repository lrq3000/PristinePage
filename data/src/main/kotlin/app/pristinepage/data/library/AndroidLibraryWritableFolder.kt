package app.pristinepage.data.library

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Capacity observation for one exact local writable tree. It neither reserves space nor mutates a provider. */
class AndroidLibraryWritableFolder(context: Context) {
    private val app = context.applicationContext

    suspend fun availableBytes(tree: Uri): Long = withContext(Dispatchers.IO) {
        val checkpoint = { coroutineContext.ensureActive() }
        val root = AndroidLibraryTreeRead.localTreeDocumentId(tree) ?: throw IOException("Unsupported writable library folder")
        LibraryProviderAttempt.run {
            withLibraryProviderSignal { signal -> WritableTree(app, tree, root, signal, checkpoint).capacity() }
        }
    }

    private class WritableTree(
        private val app: Context, private val tree: Uri, private val root: String,
        private val signal: CancellationSignal, private val checkpoint: () -> Unit,
    ) {
        private val resolver = app.contentResolver
        private val document = DocumentsContract.buildDocumentUriUsingTree(tree, root)

        fun capacity(): Long = guarded {
            openDirectory().use { anchor ->
                val expected = identity(anchor)
                val volume = Os.fstatvfs(anchor.fileDescriptor)
                val available = try {
                    if (volume.f_frsize <= 0 || volume.f_bavail < 0) throw ArithmeticException()
                    Math.multiplyExact(volume.f_frsize, volume.f_bavail)
                } catch (_: ArithmeticException) { throw IOException("Writable library capacity is unavailable") }
                // Compare simultaneous open descriptors, not a cached path or a returned document ID.
                openDirectory().use { current ->
                    if (identity(current) != expected) throw IOException("The writable library root changed")
                }
                requireExactAccess()
                available
            }
        }

        private fun openDirectory(): ParcelFileDescriptor {
            requireExactAccess()
            val row = resolverCall {
                resolver.query(document, LibraryTreeProviderRows.projection, null, null, null, signal).use {
                    LibraryTreeProviderRows.read(it, 1, checkpoint).singleOrNull()
                }
            }
            if (row == null || row.id != root || !row.directory) throw IOException("The writable library directory could not be identified")
            requireExactAccess()
            return resolverCall { resolver.openFileDescriptor(document, "r", signal) }
                ?: throw IOException("The writable library directory is unavailable")
        }

        private inline fun <T> resolverCall(call: () -> T): T = try { call() }
        catch (_: IllegalArgumentException) {
            checkpoint()
            throw IOException("The writable library directory is unavailable")
        } catch (_: FileNotFoundException) {
            checkpoint()
            throw IOException("The writable library directory is unavailable")
        }

        private fun identity(directory: ParcelFileDescriptor): Pair<Long, Long> {
            checkpoint()
            val stat = Os.fstat(directory.fileDescriptor)
            if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_ino <= 0) throw IOException("The writable library root has no directory identity")
            return stat.st_dev to stat.st_ino
        }

        private fun requireExactAccess() {
            checkpoint()
            if (!AndroidLibraryTreeRead.hasTrustedLocalProvider(app) || resolver.persistedUriPermissions.none {
                    it.isReadPermission && it.isWritePermission && it.uri.toString() == tree.toString()
                }
            ) throw IOException("The exact library folder is not writable")
            checkpoint()
        }

        private inline fun <T> guarded(block: () -> T): T = try { block() }
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
