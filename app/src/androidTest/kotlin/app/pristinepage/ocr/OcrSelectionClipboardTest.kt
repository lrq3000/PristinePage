package app.pristinepage.ocr

import android.content.ClipData
import android.text.Selection
import android.text.Spannable
import android.view.textclassifier.TextClassifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrSelectionClipboardTest {
    @Test fun onlyExplicitCopyTransfersTheSelectedRangeAndClassificationIsOff() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val original = "Unselected\nSelected\n OCR  text\nUnselected"
            val writes = mutableListOf<ClipData>()
            val view = ReadOnlyOcrTextView(instrumentation.targetContext) { writes.add(it) }
            view.text = original
            assertTrue(view.isTextSelectable)
            assertNull(view.keyListener)
            assertSame(TextClassifier.NO_OP, view.textClassifier)
            val range = view.text as Spannable
            Selection.setSelection(range, 30, 11)
            assertTrue(writes.isEmpty())
            assertFalse(view.onTextContextMenuItem(android.R.id.cut))
            assertFalse(view.onTextContextMenuItem(android.R.id.paste))
            assertTrue(writes.isEmpty())
            assertTrue(view.onTextContextMenuItem(android.R.id.copy))
            val clip = writes.single()
            assertEquals(1, clip.itemCount)
            assertEquals("Selected\n OCR  text", clip.getItemAt(0).text.toString())
            assertEquals("", clip.description.label.toString())
            assertTrue(clip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
            assertEquals(original, view.text.toString())
        }
    }
}
