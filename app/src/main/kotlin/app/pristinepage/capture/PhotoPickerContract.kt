package app.pristinepage.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode

/** Result values are process-private handoffs, not durable source ownership or permission receipts. No URI enters a Bundle, error, log or WorkManager input. Only the consuming bound session may copy/store the selected references. */
sealed interface PhotoSelection {
    class Selected(val uris: List<Uri>, val offeredReadFlags: Int) : PhotoSelection
    data object Canceled : PhotoSelection
    class Rejected(val error: OperationError) : PhotoSelection
}

/** Keep the gallery and document browsing choices separate. Parsing preserves ordinal duplicates and consumes only selected, content-backed results. */
class PhotoPickerContract @JvmOverloads constructor(private val kind: PhotoImportKind = PhotoImportKind.GALLERY) : ActivityResultContract<Unit, PhotoSelection>() {
    private val delegate = ActivityResultContracts.PickMultipleVisualMedia(100)

    override fun createIntent(context: Context, input: Unit): Intent = when (kind) {
        PhotoImportKind.FILES -> Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/jpeg", "image/png", "image/webp"))
        }
        PhotoImportKind.GALLERY -> {
            if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context)) {
                val picker = delegate.createIntent(context, PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                if (picker.action != Intent.ACTION_OPEN_DOCUMENT) picker else galleryIntent()
            } else galleryIntent()
        }
        PhotoImportKind.CAMERA -> error("Camera uses its own preview, not a picker")
    }

    // On older devices this may be single-select; never silently replace the user's gallery choice by DocumentsUI.
    private fun galleryIntent() = Intent(Intent.ACTION_PICK).setType("image/*")

    override fun parseResult(resultCode: Int, intent: Intent?): PhotoSelection {
        if (resultCode != Activity.RESULT_OK) return PhotoSelection.Canceled
        if (intent == null) return rejected(OperationErrorCode.INVALID_INPUT, "invalid_photo_selection")
        val clip = intent.clipData
        val count = if (clip != null) clip.itemCount else if (intent.data != null) 1 else 0
        // Fallback providers may ignore the requested maximum. Refuse the whole result before copying its URI list; never silently import a prefix.
        if (count > 100) return rejected(OperationErrorCode.RESOURCE_LIMIT, "document_page_limit")
        if (count == 0) return rejected(OperationErrorCode.INVALID_INPUT, "invalid_photo_selection")
        val selected = ArrayList<Uri>(count)
        repeat(count) { ordinal ->
            val uri = if (clip != null) clip.getItemAt(ordinal).uri else intent.data
            if (uri == null || uri.scheme != "content" || uri.authority.isNullOrBlank() || uri.toString().length > 8192) return rejected(OperationErrorCode.INVALID_INPUT, "invalid_photo_selection")
            // Do not coerce text/HTML/virtual items, open a provider, deduplicate or reorder. Data-owned binding performs complete reference validation before persistence/IO.
            selected += uri
        }
        return PhotoSelection.Selected(selected.toList(), intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
    }

    private fun rejected(code: OperationErrorCode, key: String) = PhotoSelection.Rejected(OperationError(code, false, key, null))
}
