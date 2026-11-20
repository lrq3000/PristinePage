package app.pristinepage.capture

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import app.pristinepage.data.model.ImportSessionId
import java.io.File
import java.io.FileNotFoundException

/** A cache handoff for one durable BATCH, distinct from the SourceStore payload. This URI never enters an external Intent or grants access to an original. */
internal class CaptureStage(context: Context, batchId: String) {
    private val id = ImportSessionId(batchId).value
    private val root = File(context.cacheDir.canonicalFile, "capture-staging")
    val uri: Uri = Uri.Builder().scheme("content").authority(authority(context)).appendPath(id).build()

    private fun file(): File {
        val result = File(root, "$id.jpg")
        if (root.canonicalFile != root.absoluteFile || result.canonicalFile != result.absoluteFile) throw FileNotFoundException("Capture stage is not private")
        return result
    }

    fun create(): File {
        if (!root.isDirectory && !root.mkdirs()) throw FileNotFoundException("Capture staging unavailable")
        return file().also { if (!it.createNewFile()) throw FileNotFoundException("Capture already started") }
    }

    fun discard() { runCatching { file().delete() } }

    fun open(): ParcelFileDescriptor {
        val jpeg = file()
        if (!jpeg.isFile || jpeg.length() !in 1..104_857_600L) throw FileNotFoundException("Capture not available for ingest")
        return ParcelFileDescriptor.open(jpeg, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    companion object {
        fun authority(context: Context) = "${context.packageName}.capture-staging"
    }
}

/** Read-only internal bridge so the existing content-URI ingest can copy the exact closed JPEG. The manifest forbids grants; the UID restriction is defense in depth. */
class CaptureStageProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Capture handoff is read-only")
        return stage(uri).open()
    }

    override fun getType(uri: Uri): String { stage(uri); return "image/jpeg" }

    private fun stage(uri: Uri): CaptureStage {
        val app = checkNotNull(context)
        if (Binder.getCallingUid() != Process.myUid()) throw SecurityException("Capture handoff is private")
        val id = uri.pathSegments.singleOrNull() ?: throw FileNotFoundException("Unknown capture")
        val stage = try { CaptureStage(app, id) } catch (_: IllegalArgumentException) { throw FileNotFoundException("Unknown capture") }
        if (uri != stage.uri) throw FileNotFoundException("Unknown capture")
        return stage
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = throw UnsupportedOperationException()
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
