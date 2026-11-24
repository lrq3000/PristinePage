package app.pristinepage.ocr

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.graphics.Rect
import android.os.SystemClock
import android.text.Selection
import android.text.Spannable
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.textclassifier.TextClassifier
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.view.inspector.WindowInspectorCompat
import app.pristinepage.DocumentCatalogIntegrationTest
import app.pristinepage.MainActivity
import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.core.ocr.OcrLanguagePack
import app.pristinepage.core.ocr.OcrLanguagePacks
import app.pristinepage.core.ocr.OcrLayout
import app.pristinepage.core.ocr.OcrText
import app.pristinepage.data.model.IngestResult
import app.pristinepage.data.repository.SavedOcrWork
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrSelectionClipboardTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

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

    @Test fun longPressInSavedOcrDialogCopiesOnlyTheSelectedWord() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val writes = CopyOnWriteArrayList<ClipData>()
        val word = "BLUEBELL"
        val original = "Before $word after"
        DocumentCatalogIntegrationTest.Fixture().use { fixture ->
            val graph = fixture.open()
            val store = graph.store
            val document = store.documents.createDocument("Synthetic selection fixture")
            val batch = store.imports.beginImport(document, 1, null)
            val imported = graph.photoImports.importProviderReferences(listOf(fixture.good), batch.parentId).single() as IngestResult.Committed
            val work = store.savedOcr
            val config = OcrConfiguration("selection-fixture", 1, OcrLayout.SINGLE_BLOCK,
                OcrLanguagePacks(listOf(OcrLanguagePack("eng", "a".repeat(64)))))
            val target = checkNotNull(work.reconcilePage(document, imported.pageId, config))
            assertEquals(SavedOcrWork.Outcome.PUBLISHED, work.execute(target) { _, _, handoff ->
                handoff.retain(object : SavedOcrWork.Prepared {
                    override suspend fun recognize() = OcrText(original)
                    override fun close() = Unit
                })
            })
            val page = store.editablePages.load(document, imported.pageId)
            val reader = SavedPageOcrReader({ work }, MutableStateFlow(false), { _, _ -> SavedOcrScheduling.RerunOutcome.UNAVAILABLE })
            compose.runOnUiThread {
                compose.activity.setContent {
                    MaterialTheme { SavedPageOcrDetails(SavedPageOcrPanel.TEXT, page, reader, false, { writes.add(it) }) {} }
                }
            }
            fun visibleText() = WindowInspectorCompat.getGlobalWindowViews().asSequence().flatMap { it.allViews() }
                .filterIsInstance<ReadOnlyOcrTextView>()
                .singleOrNull { it.isShown && it.hasWindowFocus() && it.text.toString() == original }
            compose.waitUntil(10_000) { compose.runOnUiThread { visibleText() != null } }
            val (x, y) = compose.runOnUiThread {
                val view = checkNotNull(visibleText())
                val layout = checkNotNull(view.layout)
                val origin = IntArray(2).also(view::getLocationOnScreen)
                val index = original.indexOf(word) + 2
                val line = layout.getLineForOffset(index)
                (origin[0] + view.totalPaddingLeft + layout.getPrimaryHorizontal(index)) to
                    (origin[1] + view.totalPaddingTop + (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f)
            }
            try {
                val automation = instrumentation.uiAutomation
                val originalService = automation.serviceInfo
                automation.serviceInfo = automation.serviceInfo.apply {
                    flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                }
                fun touch(down: Long, action: Int, atX: Float, atY: Float) {
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, atX, atY, 0)
                    try {
                        event.source = InputDevice.SOURCE_TOUCHSCREEN
                        assertTrue(automation.injectInputEvent(event, true))
                    } finally { event.recycle() }
                }
                try {
                    val down = SystemClock.uptimeMillis()
                    touch(down, MotionEvent.ACTION_DOWN, x, y)
                    try { delay(ViewConfiguration.getLongPressTimeout().toLong() + 100) }
                    finally { touch(down, MotionEvent.ACTION_UP, x, y) }
                    compose.waitUntil(10_000) {
                        compose.runOnUiThread {
                            visibleText()?.let { original.substring(minOf(it.selectionStart, it.selectionEnd).coerceAtLeast(0),
                                maxOf(it.selectionStart, it.selectionEnd).coerceAtLeast(0)) == word } == true
                        }
                    }
                    assertTrue(writes.isEmpty())
                    val label = instrumentation.targetContext.getString(android.R.string.copy)
                    fun copyItem(): Rect? {
                        val roots = listOfNotNull(automation.rootInActiveWindow) + automation.windows.mapNotNull { it.root }
                        val node = roots.asSequence().flatMap { it.findAccessibilityNodeInfosByText(label).asSequence() }
                            .firstOrNull { (it.text?.toString()?.equals(label, true) == true ||
                                it.contentDescription?.toString()?.equals(label, true) == true) && it.isEnabled && it.isVisibleToUser &&
                                it.packageName?.toString() == instrumentation.targetContext.packageName }
                        if (node != null) return Rect().also(node::getBoundsInScreen).takeUnless { it.isEmpty }
                        return compose.runOnUiThread {
                            WindowInspectorCompat.getGlobalWindowViews().asSequence().flatMap { it.allViews() }
                                .filterIsInstance<TextView>().firstOrNull { it.isShown && it.text.toString().equals(label, true) }
                                ?.let { view ->
                                    val location = IntArray(2).also(view::getLocationOnScreen)
                                    Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
                                }?.takeUnless { it.isEmpty }
                        }
                    }
                    compose.waitUntil(10_000) { copyItem() != null }
                    val copy = checkNotNull(copyItem())
                    val tap = SystemClock.uptimeMillis()
                    touch(tap, MotionEvent.ACTION_DOWN, copy.exactCenterX(), copy.exactCenterY())
                    touch(tap, MotionEvent.ACTION_UP, copy.exactCenterX(), copy.exactCenterY())
                    compose.waitUntil(10_000) { writes.isNotEmpty() }
                    val clip = writes.single()
                    assertEquals(1, clip.itemCount)
                    assertEquals(word, clip.getItemAt(0).text.toString())
                    assertTrue(clip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
                    assertEquals(original, work.readText(document, page.pageId, page.recipeRevision)?.text?.value)
                } finally { automation.serviceInfo = originalService }
            } finally {
                compose.runOnUiThread { compose.activity.setContent {} }
            }
        }
    }

    private fun View.allViews(): Sequence<View> = sequence {
        yield(this@allViews)
        if (this@allViews is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).allViews())
    }
}
