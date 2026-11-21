package app.pristinepage.export

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import app.pristinepage.PristinePageApplication
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Android checks the URI grant; new reads and metadata queries also require a live durable lease for this exact file. */
class FinishedExportProvider : FileProvider() {
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val columns = projection?.filter { it in SHARE_COLUMNS }?.toTypedArray() ?: SHARE_COLUMNS.copyOf()
        val cursor = MatrixCursor(columns, 1)
        // Do not let FileProvider resolve and report an on-disk file after the durable lease has ended.
        // Take size from the same verified descriptor as a normal read and never accept a caller-supplied display alias.
        val size = try { openFile(uri, "r").use { it.statSize } }
        catch (_: IOException) { return cursor }
        if (size < 0) return cursor
        val name = uri.pathSegments[2]
        cursor.addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) name else size })
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException()
        val scope = uri.pathSegments
        if (scope.size != 3 || scope[0] != "finished_exports" || !isShareId(scope[1]) || !safeFilename(scope[2])) throw FileNotFoundException()
        val app = context?.applicationContext as? PristinePageApplication ?: throw FileNotFoundException()
        val root = File(app.filesDir.canonicalFile, "shared-exports")
        val file = File(File(root, scope[1]), scope[2])
        if (file.canonicalFile != file || !Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw FileNotFoundException()
        val opened = super.openFile(uri, mode) ?: throw FileNotFoundException()
        // Check the lease after taking the descriptor too: revocation must not permit a later open to succeed.
        val allowed = try {
            runBlocking(Dispatchers.IO) { app.services.imports().store.albumExports.canOpenShare(scope[1], scope[2], opened.statSize) }
        } catch (_: Exception) { false }
        if (allowed) return opened
        opened.close()
        throw FileNotFoundException()
    }

    companion object {
        private val SHARE_COLUMNS = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)

        internal fun safeFilename(name: String): Boolean = name.isNotBlank() && !name.startsWith('.') &&
            name.encodeToByteArray().size <= 255 && name.none { it.isISOControl() || it in "/\\:" } &&
            name.substringAfterLast('.', "") in setOf("jpg", "pdf", "zip")
        internal fun isShareId(value: String): Boolean =
            value.length == 36 && runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    }
}
