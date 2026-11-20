package app.pristinepage

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.core.pipeline.PointPx
import app.pristinepage.core.pipeline.RenderStatus
import app.pristinepage.core.recipe.BinaryRecipe
import app.pristinepage.core.recipe.ContourSide
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.data.model.IngestResult
import app.pristinepage.editor.EditorSession
import app.pristinepage.editor.EditorViewModel
import app.pristinepage.library.ImportLibraryViewModel
import app.pristinepage.library.LibraryEvent
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Default Activity, actual Room/source decoder/renderer/bitmap path; private synthetic fixture, not real-picker acceptance. */
@RunWith(AndroidJUnit4::class)
class EditorShapeScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun secondPageRejectsCrossedCornersAndFoldedCurveThenSavesAndReopensTheRenderedShape() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val authority = "${context.packageName}.editor-fixtures"
        val nonce = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "editor-journey-fixtures")
        check(root.isDirectory || root.mkdirs())
        val file = File(root, "$nonce.png")
        check(file.createNewFile())
        val ramp = IntArray(101 * 81) { i -> 0xff000000.toInt() or ((i % 101 + i / 101) * 0x010101) }
        val bitmap = Bitmap.createBitmap(ramp, 101, 81, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        val png = file.readBytes()
        val graph = (context.applicationContext as PristinePageApplication).services.imports()
        val doc = graph.store.documents.createDocument("Manual shape $nonce")
        val batch = graph.store.imports.beginImport(doc, 1, null)
        val pages = graph.photoImports.importProviderReferences(
            List(2) { "content://$authority/$nonce" }, batch.parentId,
        ).map { it as IngestResult.Committed }
        val second = pages[1]
        val library = compose.runOnUiThread { ViewModelProvider(compose.activity)[ImportLibraryViewModel::class.java].session }
        compose.waitUntil(10_000) { !library.state.value.loading }
        compose.runOnUiThread { library.accept(LibraryEvent.Open(doc)) }
        compose.waitUntil(10_000) { library.state.value.document?.pages?.size == 2 && !library.state.value.loading }
        compose.onNodeWithTag("original-page-${second.pageId.value}").performClick()
        val host = compose.runOnUiThread { ViewModelProvider(compose.activity)[EditorViewModel::class.java] }
        compose.waitUntil(10_000) { host.session.value?.preview?.value?.display != null }
        compose.onNodeWithTag("editor-viewport").performScrollTo().performTouchInput { swipeRight() }
        compose.waitUntil(10_000) { host.session.value?.state?.value?.page?.pageId == pages[0].pageId }
        compose.onNodeWithTag("carnet-next-page").performScrollTo().performClick()
        compose.waitUntil(10_000) { host.session.value?.state?.value?.page?.pageId == second.pageId &&
            host.session.value?.preview?.value?.display != null }
        val session = checkNotNull(host.session.value)
        val original = checkNotNull(session.state.value.draft)
        assertEquals(second.pageId, session.state.value.page!!.pageId)
        compose.onNodeWithTag("editor-tool-looks").performClick()
        click("editor-look-gray")
        compose.onNodeWithTag("editor-tool-rotate").performClick()
        click("editor-rotate")
        compose.onNodeWithTag("editor-tool-shape").performClick()
        click("editor-shape-perspective")
        coordinate("x", .2f)
        coordinate("y", .1f)
        waitForResult(session, "perspective_v1")
        assertEquals(PointPx(.2, .1), (session.state.value.draft!!.geometry as GeometryRecipe.Perspective).quad.tl)
        assertEquals(28, displayedSample(session, 80, 0))

        val beforeDrag = session.state.value.draft
        compose.onNodeWithTag("editor-shape-x").performScrollTo().performTouchInput {
            fun point(fraction: Float) = Offset(width * fraction, center.y)
            down(point(.2f))
            for (step in 1..40) moveTo(point(.2f + step * .01f), delayMillis = 16)
            up()
        }
        waitForResult(session, "perspective_v1")
        val afterDrag = session.state.value.draft
        assertNotEquals(beforeDrag, afterDrag)
        click("editor-undo")
        waitForResult(session, "perspective_v1")
        assertEquals(beforeDrag, session.state.value.draft)
        click("editor-redo")
        waitForResult(session, "perspective_v1")
        assertEquals(afterDrag, session.state.value.draft)
        click("editor-undo")
        waitForResult(session, "perspective_v1")
        assertEquals(beforeDrag, session.state.value.draft)

        // Moving TL onto TR collapses the top edge. The visible bitmap must still be the earlier result.
        coordinate("y", 0f)
        waitForResult(session, "perspective_v1")
        val previous = displayedSample(session, 80, 0)
        coordinate("x", 1f)
        assertRefused(session, RenderStatus.INVALID_INPUT)
        assertEquals(previous, displayedSample(session, 80, 0))
        assertEquals(original, graph.store.editablePages.load(doc, second.pageId).recipe)

        click("editor-shape-reset")
        waitForResult(session, "geometry_none_v1")
        click("editor-shape-curve")
        heldMidpointDragCannotEditReplacementCorner(session)
        click("editor-shape-curve")
        selectPoint("TOP_MIDPOINT")
        coordinate("y", .1f)
        selectPoint("BOTTOM_MIDPOINT")
        coordinate("y", .9f)
        waitForResult(session, "manual_ruled_v1")
        val valid = checkNotNull(session.state.value.draft)
        assertEquals(58, displayedSample(session, 80, 50))
        assertEquals(122, displayedSample(session, 0, 50))
        assertEquals(1, valid.geometry.outputQuarterTurns)
        assertEquals(BinaryRecipe.GrayscaleV1, valid.binary)
        selectPoint("TOP_MIDPOINT")
        coordinate("y", .95f)
        assertRefused(session, RenderStatus.INVALID_MAP)
        assertEquals(58, displayedSample(session, 80, 50))
        assertEquals(1L, graph.store.editablePages.load(doc, second.pageId).recipeRevision)

        click("editor-undo")
        waitForResult(session, "manual_ruled_v1")
        compose.onNodeWithTag("editor-back").performClick()
        compose.onNodeWithTag("editor-apply-exit").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { host.session.value == null && !library.state.value.loading }
        compose.onNodeWithTag("original-page-${second.pageId.value}").performClick()
        compose.waitUntil(10_000) { host.session.value?.state?.value?.page?.recipeRevision == 2L }
        val reopened = checkNotNull(host.session.value)
        waitForResult(reopened, "manual_ruled_v1")
        assertEquals(second.pageId, reopened.state.value.page!!.pageId)
        assertEquals(valid, reopened.state.value.draft)
        assertEquals(original.geometry.source, reopened.state.value.draft!!.geometry.source)
        assertEquals(1L, graph.store.editablePages.load(doc, pages[0].pageId).recipeRevision)
        assertArrayEquals(png, graph.store.committedSources.load(second.sourceId).file.readBytes())

        compose.onNodeWithTag("editor-tool-shape").performClick()
        click("editor-shape-reset")
        waitForResult(reopened, "geometry_none_v1")
        val flat = displayedSample(reopened, 80, 50)
        click("editor-shape-bezier")
        click("atelier-bezier-insert")
        click("atelier-bezier-remove")
        waitForResult(reopened, "atelier_bezier_v1")
        repeat(4) { click("atelier-bezier-down") }
        waitForResult(reopened, "atelier_bezier_v1")
        val curvedValue = displayedSample(reopened, 80, 50)
        assertTrue(curvedValue > flat)
        val cubic = checkNotNull(reopened.state.value.draft)
        assertEquals(.05, (cubic.geometry as GeometryRecipe.Bezier).contour.edge(ContourSide.TOP).segments.first().outgoing.y, 1e-12)
        compose.onNodeWithTag("editor-apply").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { host.session.value == null && !library.state.value.loading }
        compose.onNodeWithTag("original-page-${second.pageId.value}").performClick()
        compose.waitUntil(10_000) { host.session.value?.state?.value?.page?.recipeRevision == 3L }
        val afterReopen = checkNotNull(host.session.value)
        waitForResult(afterReopen, "atelier_bezier_v1")
        assertEquals(cubic, afterReopen.state.value.draft)
        assertEquals(curvedValue, displayedSample(afterReopen, 80, 50))
        assertArrayEquals(png, graph.store.committedSources.load(second.sourceId).file.readBytes())
    }

    private fun click(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick() }

    private fun selectPoint(label: String) {
        click("editor-shape-point")
        compose.onNodeWithTag("editor-shape-point-$label").performClick()
    }

    private fun coordinate(axis: String, value: Float) {
        compose.onNodeWithTag("editor-shape-$axis").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) {
            assertTrue(it(value))
        }
    }

    private fun heldMidpointDragCannotEditReplacementCorner(session: EditorSession) {
        selectPoint("TOP_MIDPOINT")
        val slider = compose.onNodeWithTag("editor-shape-x").performScrollTo()
        val before = (session.state.value.draft!!.geometry as GeometryRecipe.ManualCurve).topMidpoint
        try {
            slider.performTouchInput {
                down(center)
                moveTo(Offset(width * .65f, center.y))
                moveTo(Offset(width * .72f, center.y))
            }
            compose.runOnIdle {
                val during = session.state.value.draft!!.geometry as GeometryRecipe.ManualCurve
                assertNotEquals("The held gesture must actually move the midpoint before rebinding", before, during.topMidpoint)
            }
            // Invoke the real button action without a new touch or scroll that could cancel pointer zero.
            compose.onNodeWithTag("editor-shape-perspective").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
            slider.assertContentDescriptionEquals("Top left corner, horizontal position")
            val replacement = compose.runOnIdle { session.state.value.draft!! }
            val quad = (replacement.geometry as GeometryRecipe.Perspective).quad
            assertEquals(PointPx(0.0, 0.0), quad.tl)
            compose.onRoot().performTouchInput {
                assertNotNull("The original pointer must still be down after rebinding", currentPosition())
                moveBy(Offset(width * .1f, 0f))
            }
            compose.runOnIdle {
                assertEquals("A held midpoint drag must not edit the replacement corner", replacement, session.state.value.draft)
            }
        } finally {
            compose.onRoot().performTouchInput { if (currentPosition() != null) up() }
        }
    }

    private fun waitForResult(session: EditorSession, stage: String) {
        compose.waitUntil(10_000) {
            val p = session.preview.value
            val display = p.display
            display != null && !p.processing && !p.previous && display.request == p.request &&
                p.request?.recipe == session.state.value.draft && p.preview?.stages?.any { it.stageId == stage } == true
        }
    }

    private fun assertRefused(session: EditorSession, failure: RenderStatus) {
        compose.waitUntil(10_000) { session.preview.value.failure == failure && session.preview.value.previous }
        compose.onNodeWithTag("editor-previous").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("editor-apply").assertIsNotEnabled()
        click("editor-back")
        compose.onNodeWithTag("editor-apply-exit").assertIsNotEnabled()
        compose.onNodeWithTag("editor-keep-editing").performClick()
    }

    private fun displayedSample(session: EditorSession, x: Int, y: Int): Int = compose.runOnUiThread {
        checkNotNull(session.preview.value.display).bitmap.getPixel(x, y) and 0xff
    }
}
