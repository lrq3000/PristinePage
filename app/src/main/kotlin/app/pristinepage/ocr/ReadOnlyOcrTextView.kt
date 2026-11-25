package app.pristinepage.ocr

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.textclassifier.TextClassifier
import android.widget.TextView

/** Use native selection handles and Copy without sending the result to Android's smart selection classifier. Compose constructs this view directly under the platform theme; the folder adapter's AppCompat dependency does not provide its inflater or selection controller. */
@SuppressLint("AppCompatCustomView")
internal class ReadOnlyOcrTextView @JvmOverloads constructor(
    context: Context,
    private val writeClipboard: (ClipData) -> Unit = { context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(it) },
) : TextView(context) {
    init {
        setTextIsSelectable(true)
        setTextClassifier(TextClassifier.NO_OP)
        isSaveEnabled = false
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
        if (Build.VERSION.SDK_INT >= 30) importantForContentCapture = IMPORTANT_FOR_CONTENT_CAPTURE_NO
        textDirection = TEXT_DIRECTION_FIRST_STRONG
        customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                keepSelectionCommands(menu)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = keepSelectionCommands(menu)

            // Consume any unexpected OEM action rather than forwarding private text to its destination.
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (item.itemId == android.R.id.selectAll) return false
                if (item.itemId == android.R.id.copy && onTextContextMenuItem(item.itemId)) mode.finish()
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) = Unit
        }
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id == android.R.id.selectAll) return super.onTextContextMenuItem(id)
        if (id != android.R.id.copy) return false
        val start = minOf(selectionStart, selectionEnd)
        val end = maxOf(selectionStart, selectionEnd)
        if (start < 0 || end > text.length || start == end) return false
        writeClipboard(ClipData.newPlainText("", text.subSequence(start, end).toString()).apply {
            // This suppresses previews where supported; it does not make the system clipboard private.
            description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        })
        return true
    }

    private fun keepSelectionCommands(menu: Menu): Boolean {
        var changed = false
        for (index in menu.size() - 1 downTo 0) {
            val item = menu.getItem(index)
            // PROCESS_TEXT items may share their item ID; hide each individually.
            if (item.isVisible && item.itemId != android.R.id.copy && item.itemId != android.R.id.selectAll) {
                item.isVisible = false
                changed = true
            }
        }
        return changed
    }
}
