package app.pristinepage.library

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContract
import app.pristinepage.data.library.AndroidLibraryTreeRead
import com.anggrayudi.storage.extension.fromTreeUri
import java.io.IOException

/** Direct Android tree result: SimpleStorage resolves the admitted root but never launches the picker or cleans other grants. */
internal class LibraryFolderPickerContract(private val writable: Boolean = false) : ActivityResultContract<Unit, LibraryFolderPickerContract.Selection>() {
    sealed interface Selection
    data object Canceled : Selection
    enum class Rejected : Selection { MALFORMED_RESULT, UNSUPPORTED_TREE, MISSING_PERSISTENT_READ, MISSING_WRITE }
    class Picked internal constructor(val uri: Uri, val grant: LibraryTreeGrant) : Selection

    override fun createIntent(context: Context, input: Unit): Intent {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        if (writable) picker.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        for (pkg in listOf("com.android.documentsui", "com.google.android.documentsui")) {
            val info = context.packageManager.resolveActivity(picker.setPackage(pkg), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo ?: continue
            if (info.packageName == pkg && info.enabled && info.exported && info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) {
                return picker.setComponent(ComponentName(pkg, info.name))
            }
        }
        throw ActivityNotFoundException("System Files is not available")
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Selection {
        if (resultCode == Activity.RESULT_CANCELED) return Canceled
        if (resultCode != Activity.RESULT_OK || intent == null) return Rejected.MALFORMED_RESULT
        val uri = intent.data ?: return Rejected.MALFORMED_RESULT
        intent.clipData?.let { clip ->
            if (clip.itemCount != 1 || clip.getItemAt(0).uri?.toString() != uri.toString()) return Rejected.MALFORMED_RESULT
        }
        if (AndroidLibraryTreeRead.localTreeDocumentId(uri) == null) return Rejected.UNSUPPORTED_TREE
        fun returned(flag: Int) = intent.flags and flag != 0
        val grant = try {
            LibraryTreeGrant.inspect(uri.toString(), returned(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                returned(Intent.FLAG_GRANT_WRITE_URI_PERMISSION), returned(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
        } catch (_: IOException) { return Rejected.MISSING_PERSISTENT_READ }
        if (writable && !grant.returnedWrite) return Rejected.MISSING_WRITE
        return Picked(uri, grant)
    }
}

internal class LibraryFolderGrantHandoff(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver

    /** On an actual picker callback, persist only the read capability needed here; never remove or retake another grant. */
    fun persistRead(picked: LibraryFolderPickerContract.Picked): Uri = persist(picked, write = false, checkpoint = {})

    /** Only for an explicit approved writable destination. This acquires a grant, not readiness to publish or delete files. */
    fun persistWrite(picked: LibraryFolderPickerContract.Picked, checkpoint: () -> Unit = {}): Uri =
        persist(picked, write = true, checkpoint)

    /** One-shot export uses only the live picker capability. The provider writer verifies actual platform access at each boundary. */
    fun resolveLiveWrite(picked: LibraryFolderPickerContract.Picked): Uri {
        if (!picked.grant.returnedWrite || picked.uri.toString() != picked.grant.exactUri) {
            throw IOException("The selected folder did not return writable access")
        }
        return picked.uri.also(::resolveRoot)
    }

    private fun persist(picked: LibraryFolderPickerContract.Picked, write: Boolean, checkpoint: () -> Unit): Uri {
        val tree = picked.uri
        if (tree.toString() != picked.grant.exactUri) throw IOException("The selected folder capability changed")
        checkpoint()
        resolveRoot(tree)
        try {
            val current = held()
            val missing = if (write) picked.grant.missingWriteModes(current)
                else if (picked.grant.needsReadGrant(current)) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0
            picked.grant.persistMissing(missing, checkpoint) { resolver.takePersistableUriPermission(tree, it) }
        } catch (_: SecurityException) {
            throw IOException("The selected folder could not retain the requested permission")
        }
        checkpoint()
        if (write) picked.grant.requirePersistedWrite(held()) else picked.grant.requirePersistedRead(held())
        return tree
    }

    private fun resolveRoot(tree: Uri) {
        val id = AndroidLibraryTreeRead.localTreeDocumentId(tree) ?: throw IOException("The selected folder is not supported")
        if (!AndroidLibraryTreeRead.hasTrustedLocalProvider(app)) throw IOException("The selected folder provider is not available")
        val root = app.fromTreeUri(tree)?.uri
        if (root?.toString() != DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()) {
            throw IOException("The selected folder identity could not be resolved")
        }
    }

    private fun held() = resolver.persistedUriPermissions.map { LibraryTreeGrant.Held(it.uri.toString(), it.isReadPermission, it.isWritePermission) }
}
