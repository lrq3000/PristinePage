package app.pristinepage.testing

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/** Debug source set only. The actual Application resolver/import path can consume tiny generated fixtures without a test-APK URI grant, another graph or replacement Activity content. No arbitrary path or private original is addressable here. */
class PrivateEditorFixtureProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri): String { fixture(uri); return "image/png" }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Synthetic fixture is read-only")
        return ParcelFileDescriptor.open(fixture(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun fixture(uri: Uri): File {
        val app = checkNotNull(context)
        if (Binder.getCallingUid() != Process.myUid()) throw SecurityException("Synthetic fixture is private")
        val name = uri.pathSegments.singleOrNull()
        val validId = name != null && name.length == 36 && runCatching { UUID.fromString(name).toString() == name }.getOrDefault(false)
        if (uri.scheme != "content" || uri.authority != "${app.packageName}.editor-fixtures" || uri.query != null || uri.fragment != null || !validId) throw FileNotFoundException("Synthetic fixture unavailable")
        val root = File(app.cacheDir,"editor-journey-fixtures").canonicalFile
        val file = File(root,"$name.png").canonicalFile
        if (file.parentFile != root || !file.isFile || file.length() !in 1..1_048_576L) throw FileNotFoundException("Synthetic fixture unavailable")
        return file
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = throw UnsupportedOperationException("No fixture enumeration")
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("No fixture mutation")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("No fixture mutation")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("No fixture mutation")
}
