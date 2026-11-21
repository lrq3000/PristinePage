package app.pristinepage

import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.*
import app.pristinepage.data.model.*
import app.pristinepage.editor.*
import app.pristinepage.image.decode.ImageAllocationBudget
import app.pristinepage.image.render.AppearanceEngine
import app.pristinepage.image.render.GeometryEngine
import java.security.MessageDigest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorShapeTest {
    @Test fun draggingAnAnchorKeepsItsAdjacentBezierEdgesJoinedAndTheOtherKnotsIntact() = checkShape {
        editor.selectBezier()
        editor.insertBezier(ContourSide.TOP, 0, .35)
        val corner = BezierControl(ContourSide.TOP, 0, BezierPart.START)
        val drag = checkNotNull(editor.beginBezierDrag(corner))
        val moved = PointPx(.08, .06)
        editor.moveBezier(corner, moved, drag)
        editor.endBezierDrag(drag)
        val contour = (editor.state.value.draft!!.geometry as GeometryRecipe.Bezier).contour
        assertEquals(moved, contour.quad.tl)
        assertEquals(moved, contour.top.segments.first().start)
        assertEquals(moved, contour.left.segments.first().start)
        assertEquals(.35, contour.top.segments.first().to, 0.0)
        assertTrue(contour.straight)
        advanceUntilIdle()
        assertEquals(RenderStatus.OK, preview().status)
    }

    @Test fun manualMidpointMovesDirectlyOnTheCanvasWithCancelableUndo() = checkShape {
        editor.selectManualCurve()
        val before = editor.state.value.draft
        val drag = checkNotNull(editor.beginFrameDrag(ShapePoint.TOP_MIDPOINT))
        editor.moveFrameDrag(drag, PointPx(.5, .035))
        assertEquals(PointPx(.5, .035), (editor.state.value.draft!!.geometry as GeometryRecipe.ManualCurve).topMidpoint)
        editor.endFrameDrag(drag, cancel = true)
        assertEquals(before, editor.state.value.draft)
    }

    @Test fun manualSideMidpointsChangeTheRenderedLeftAndRightEdgesAndSurviveRecipeReopening() = checkShape {
        editor.selectManualCurve()
        val legacy = checkNotNull(editor.state.value.draft)
        advanceUntilIdle()
        val leftBefore = sample(preview(), 40, 0)
        val rightBefore = sample(preview(), 40, 100)
        val left = ShapePoint.valueOf("LEFT_MIDPOINT")
        assertEquals(PointPx(0.0, .5), EditorShapes.points(legacy.geometry)[left])
        val leftDrag = checkNotNull(editor.beginFrameDrag(left))
        editor.moveFrameDrag(leftDrag, PointPx(.08, .5))
        editor.endFrameDrag(leftDrag)
        val right = ShapePoint.valueOf("RIGHT_MIDPOINT")
        val rightDrag = checkNotNull(editor.beginFrameDrag(right))
        editor.moveFrameDrag(rightDrag, PointPx(.9, .5))
        editor.endFrameDrag(rightDrag)
        advanceUntilIdle()
        val edited = checkNotNull(editor.state.value.draft)
        assertEquals(RenderStatus.OK, preview().status)
        assertEquals(leftBefore + 8, sample(preview(), 40, 0))
        assertEquals(rightBefore - 10, sample(preview(), 40, 100))
        assertEquals(edited, RecipeCodec.decode(RecipeCodec.encode(edited)))
        assertEquals(legacy.geometry.source, edited.geometry.source)
        editor.undo()
        advanceUntilIdle()
        assertEquals(rightBefore, sample(preview(), 40, 100))
        editor.undo()
        assertEquals(legacy, editor.state.value.draft)
        editor.redo()
        editor.redo()
        advanceUntilIdle()
        val valid = preview()
        editor.setShapeCoordinate(left, horizontal = true, value = .95)
        advanceUntilIdle()
        assertSame(valid, preview())
        assertEquals(RenderStatus.INVALID_MAP, editor.preview.value.failure)
        assertBlocked()
        editor.keepEditing()
        editor.undo()
        advanceUntilIdle()
        editor.apply()
        advanceUntilIdle()
        assertEquals(listOf(edited), saves)
    }

    @Test fun returningALateralDragToItsStartKeepsTheExactLegacyCurve() = checkShape {
        val legacy = base.copy(geometry = GeometryRecipe.ManualCurve(binding, full, PointPx(.37, .14), outputQuarterTurns = 1))
        editor.edit(legacy)
        val left = ShapePoint.valueOf("LEFT_MIDPOINT")
        val drag = checkNotNull(editor.beginFrameDrag(left))
        editor.moveFrameDrag(drag, PointPx(.08, .5))
        assertNotEquals(legacy, editor.state.value.draft)
        editor.moveFrameDrag(drag, PointPx(0.0, .5))
        editor.endFrameDrag(drag)
        assertEquals(legacy, editor.state.value.draft)
        editor.undo()
        assertEquals(base, editor.state.value.draft)
    }

    @Test fun undoFencesALateCanvasDeltaAndPreservesRedo() = checkShape {
        editor.selectManualCurve()
        val initial = editor.state.value.draft
        val left = ShapePoint.valueOf("LEFT_MIDPOINT")
        val drag = checkNotNull(editor.beginFrameDrag(left))
        editor.moveFrameDrag(drag, PointPx(.08, .5))
        val edited = editor.state.value.draft
        editor.undo()
        assertEquals(initial, editor.state.value.draft)
        editor.moveFrameDrag(drag, PointPx(.12, .5))
        editor.endFrameDrag(drag)
        assertEquals(initial, editor.state.value.draft)
        assertTrue(editor.state.value.canRedo)
        editor.redo()
        assertEquals(edited, editor.state.value.draft)
    }

    @Test fun cubicGestureIsCancelableAndOneUndoRestoresTheWholeChain() = checkShape {
        editor.selectBezier()
        val before = editor.state.value.draft
        val handle = BezierControl(ContourSide.TOP, 0, BezierPart.OUTGOING)
        val canceled = checkNotNull(editor.beginBezierDrag(handle))
        editor.moveBezier(handle, PointPx(1.0 / 3, .04), canceled)
        editor.endBezierDrag(canceled, cancel = true)
        assertEquals(before, editor.state.value.draft)
        val drag = checkNotNull(editor.beginBezierDrag(handle))
        editor.moveBezier(handle, PointPx(1.0 / 3, .05), drag)
        editor.moveBezier(handle, PointPx(1.0 / 3, .07), drag)
        editor.endBezierDrag(drag)
        val edited = editor.state.value.draft
        assertNotEquals(before, edited)
        editor.undo()
        assertEquals(before, editor.state.value.draft)
        editor.redo()
        advanceUntilIdle()
        editor.apply()
        advanceUntilIdle()
        assertEquals(listOf(edited), saves)
    }

    @Test fun wizardCheckpointOnlyAllowsNavigationAfterTheExactDirtyFrameSaves() = checkShape {
        val proposed = base.copy(geometry = perspective.copy(quad = full.copy(tl = PointPx(.2, .1))))
        editor.edit(proposed)
        assertFalse(editor.checkpointForNavigation())
        assertTrue(saves.isEmpty())
        advanceUntilIdle()
        assertTrue(editor.checkpointForNavigation())
        assertEquals(listOf(proposed), saves)
        assertFalse(editor.state.value.exitReady)
        assertFalse(editor.state.value.dirty)
        assertTrue(editor.checkpointForNavigation())
        assertEquals(listOf(proposed), saves)
    }

    @Test fun canvasDragChangesTwoAxesTogetherAndRejectsCrossedCorners() = checkShape {
        editor.selectPerspective()
        advanceUntilIdle()
        val before = editor.state.value.draft
        val drag = checkNotNull(editor.beginFrameDrag(ShapePoint.TOP_LEFT))
        editor.moveFrameDrag(drag, PointPx(.15, .13))
        editor.moveFrameDrag(drag, PointPx(.16, .14))
        advanceUntilIdle()
        val quad = (editor.state.value.draft!!.geometry as GeometryRecipe.Perspective).quad
        assertEquals(.16, quad.tl.x, 0.0)
        assertEquals(.14, quad.tl.y, 0.0)
        editor.moveFrameDrag(drag, PointPx(.95, .95))
        assertEquals(quad, (editor.state.value.draft!!.geometry as GeometryRecipe.Perspective).quad)
        editor.endFrameDrag(drag)
        editor.undo()
        assertEquals(before, editor.state.value.draft)
        val canceled = checkNotNull(editor.beginFrameDrag(ShapePoint.TOP_LEFT))
        editor.moveFrameDrag(canceled, PointPx(.15, .13))
        editor.endFrameDrag(canceled, cancel = true)
        assertEquals(before, editor.state.value.draft)
        editor.redo()
        assertEquals(quad, (editor.state.value.draft!!.geometry as GeometryRecipe.Perspective).quad)
    }

    @Test fun labeledCornerAndWholeImageResetRenderOnTheSelectedPageAndUndoRestoresTheShape() = checkShape {
        assertEquals(0, sample(preview(), 80, 0))
        editor.selectPerspective()
        editor.setShapeCoordinate(ShapePoint.TOP_LEFT, horizontal = true, value = .2)
        editor.setShapeCoordinate(ShapePoint.TOP_LEFT, horizontal = false, value = .1)
        advanceUntilIdle()
        val corrected = preview()
        val expected = base.copy(geometry = perspective.copy(quad = full.copy(tl = PointPx(.2, .1))))
        assertEquals(expected, corrected.request.recipe)
        assertEquals(page.value, corrected.request.pageId)
        assertEquals(source.value, corrected.request.sourceId)
        assertEquals(binding.sourcePixelHash, corrected.request.sourcePixelHash)
        assertEquals(OutputSpec(PixelSize(81, 101), RenderPurpose.PREVIEW), corrected.request.output)
        // After one clockwise turn the top-left source corner appears at the top right of the output.
        assertEquals(28, sample(corrected, 80, 0))
        assertEquals(80, sample(corrected, 0, 0))
        editor.resetShape()
        advanceUntilIdle()
        assertEquals(base, preview().request.recipe)
        assertEquals(0, sample(preview(), 80, 0))
        editor.undo()
        advanceUntilIdle()
        assertEquals(expected, preview().request.recipe)
        assertEquals(28, sample(preview(), 80, 0))
        editor.apply()
        advanceUntilIdle()
        assertEquals(listOf(expected), saves)
        assertEquals(expected, editor.state.value.page!!.recipe)
    }

    @Test fun pendingNewShapeCannotApplyOrExitAndAnExactSuccessfulResultCan() = checkShape {
        val proposed = base.copy(geometry = perspective.copy(quad = full.copy(tl = PointPx(.2, .1))))
        editor.edit(proposed)
        assertTrue(editor.preview.value.processing)
        assertBlocked()
        advanceUntilIdle()
        assertEquals(proposed, preview().request.recipe)
        editor.applyAndBack()
        advanceUntilIdle()
        assertEquals(listOf(proposed), saves)
        assertTrue(editor.state.value.exitReady)
    }

    @Test fun longDragEndingInAnInvalidQuadDoesNotEvictItsStartingRecipe() = checkShape {
        editor.selectPerspective()
        editor.chooseLook("paper")
        editor.rotateClockwise()
        advanceUntilIdle()
        val start = checkNotNull(editor.state.value.draft)
        val drag = checkNotNull(editor.beginShapeDrag(ShapePoint.TOP_LEFT, horizontal = true))
        // One slider drag from the left end to the right end, with 100 distinct callback values.
        for (step in 1..99) {
            editor.setShapeCoordinate(ShapePoint.TOP_LEFT, horizontal = true, value = step / 100.0, drag = drag)
            if (step == 20) {
                assertTrue(editor.preview.value.processing)
                editor.apply()
                editor.applyAndBack()
                assertFalse(editor.state.value.saving)
                advanceUntilIdle()
                assertEquals(editor.state.value.draft, preview().request.recipe)
            }
        }
        val previous = preview()
        editor.setShapeCoordinate(ShapePoint.TOP_LEFT, horizontal = true, value = 1.0, drag = drag)
        editor.endShapeDrag(drag)
        val final = checkNotNull(editor.state.value.draft)
        assertEquals(PointPx(1.0, 0.0), (final.geometry as GeometryRecipe.Perspective).quad.tl)
        assertBlocked()
        assertEquals(RenderStatus.INVALID_INPUT, editor.preview.value.failure)
        assertSame(previous, preview())

        editor.undo()
        advanceUntilIdle()
        assertEquals("One Undo must restore the full pre-gesture draft", start, editor.state.value.draft)
        assertEquals(start, preview().request.recipe)
        editor.redo()
        assertEquals(final, editor.state.value.draft)
        assertBlocked()
    }

    @Test fun distinctDragsDiscreteEditsAndOtherControlsStaySeparate() = checkShape {
        editor.selectPerspective()
        val start = checkNotNull(editor.state.value.draft)
        val one = checkNotNull(editor.beginShapeDrag(ShapePoint.TOP_LEFT, true))
        editor.setShapeCoordinate(ShapePoint.TOP_LEFT, true, .1, one)
        editor.setShapeCoordinate(ShapePoint.TOP_LEFT, true, .2, one)
        editor.endShapeDrag(one)
        val afterOne = checkNotNull(editor.state.value.draft)
        val two = checkNotNull(editor.beginShapeDrag(ShapePoint.TOP_LEFT, true))
        editor.endShapeDrag(one) // Stale termination must not close a distinct drag.
        editor.setShapeCoordinate(ShapePoint.TOP_LEFT, true, .3, two)
        editor.setShapeCoordinate(ShapePoint.TOP_LEFT, true, .4, two)
        val afterTwo = checkNotNull(editor.state.value.draft)
        editor.setShapeCoordinate(ShapePoint.TOP_LEFT, false, .1)
        val otherAxis = checkNotNull(editor.state.value.draft)
        editor.setShapeCoordinate(ShapePoint.TOP_LEFT, true, .8, two)
        assertEquals(otherAxis, editor.state.value.draft)
        editor.setShapeCoordinate(ShapePoint.TOP_RIGHT, true, .9)
        val otherPoint = checkNotNull(editor.state.value.draft)
        editor.setShapeCoordinate(ShapePoint.TOP_RIGHT, true, .8)
        for (expected in listOf(otherPoint, otherAxis, afterTwo, afterOne, start)) {
            editor.undo()
            assertEquals(expected, editor.state.value.draft)
        }
    }

    @Test fun undoBackAndPauseEndAGestureAndFenceItsLateDeltas() = checkShape {
        editor.selectPerspective()
        val start = checkNotNull(editor.state.value.draft)
        for (end in listOf<() -> Unit>(editor::undo, editor::requestBack, { editor.setActive(false) })) {
            val drag = checkNotNull(editor.beginShapeDrag(ShapePoint.TOP_LEFT, true))
            editor.setShapeCoordinate(ShapePoint.TOP_LEFT, true, .2, drag)
            end()
            val expected = editor.state.value.draft
            editor.setShapeCoordinate(ShapePoint.TOP_LEFT, true, .4, drag)
            assertEquals(expected, editor.state.value.draft)
            editor.keepEditing()
            editor.setActive(true)
            if (expected != start) editor.undo()
            assertEquals(start, editor.state.value.draft)
        }
    }

    @Test fun draggingBackToItsStartPreservesRedoAndDoesNotMakeAnUndoEntry() = checkShape {
        editor.selectPerspective()
        val start = checkNotNull(editor.state.value.draft)
        editor.setShapeCoordinate(ShapePoint.TOP_LEFT, true, .2)
        val prior = checkNotNull(editor.state.value.draft)
        editor.undo()
        assertEquals(start, editor.state.value.draft)
        val drag = checkNotNull(editor.beginShapeDrag(ShapePoint.TOP_LEFT, true))
        editor.setShapeCoordinate(ShapePoint.TOP_LEFT, true, .3, drag)
        assertFalse(editor.state.value.canRedo)
        editor.setShapeCoordinate(ShapePoint.TOP_LEFT, true, 0.0, drag)
        editor.endShapeDrag(drag)
        assertTrue(editor.state.value.canRedo)
        editor.redo()
        assertEquals(prior, editor.state.value.draft)
        editor.undo()
        assertEquals(start, editor.state.value.draft)
        editor.undo()
        assertEquals(base, editor.state.value.draft)
    }

    @Test fun crossedCornersRetainPreviousPixelsAndCannotApplyOrExit() = checkShape {
        val previous = preview()
        editor.edit(base.copy(geometry = perspective.copy(quad = full.copy(tl = PointPx(1.0, 1.0)))))
        assertBlocked()
        assertEquals(RenderStatus.INVALID_INPUT, editor.preview.value.failure)
        assertTrue(editor.preview.value.previous)
        assertSame(previous, preview())
        assertEquals(0, sample(preview(), 80, 0))
        editor.undo()
        advanceUntilIdle()
        assertEquals(base, preview().request.recipe)
        assertFalse(editor.state.value.dirty)
    }

    @Test fun staleSameRecipeResultAndIdenticalComparisonRecipeCannotAuthorizeSave() = checkShape {
        // With finishing off, Input requests a byte-for-byte identical Recipe. It still is not proof from Result.
        val proposed = Recipe(geometry = perspective)
        staleNext = true
        editor.edit(proposed)
        advanceUntilIdle()
        assertBlocked()
        assertEquals(RenderStatus.PROCESSING_FAILED, editor.preview.value.failure)
        assertTrue(editor.preview.value.previous)
        editor.keepEditing()
        editor.showView("input")
        advanceUntilIdle()
        assertEquals(proposed, preview().request.recipe)
        assertBlocked()
        editor.keepEditing()
        editor.showView("result")
        assertBlocked()
        editor.keepEditing()
        advanceUntilIdle()
        assertEquals(proposed, preview().request.recipe)
        editor.chooseLook("gray")
        assertBlocked()
        editor.keepEditing()
        advanceUntilIdle()
        editor.apply()
        advanceUntilIdle()
        assertEquals(listOf(proposed.copy(binary = BinaryRecipe.GrayscaleV1)), saves)
    }

    @Test fun curveMidpointsUseTheRealMapAndAnInBoundsFoldCannotApplyOrExit() = checkShape {
        editor.selectPerspective()
        editor.selectManualCurve()
        advanceUntilIdle()
        val straight = preview()
        val started = straight.request.recipe.geometry as GeometryRecipe.ManualCurve
        assertEquals(PointPx(.5, 0.0), started.topMidpoint)
        assertEquals(PointPx(.5, 1.0), started.bottomMidpoint)
        assertEquals(50, sample(straight, 80, 50))
        editor.setShapeCoordinate(ShapePoint.TOP_MIDPOINT, horizontal = false, value = .1)
        editor.setShapeCoordinate(ShapePoint.BOTTOM_MIDPOINT, horizontal = false, value = .9)
        advanceUntilIdle()
        val curved = preview()
        val expected = base.copy(geometry = GeometryRecipe.ManualCurve(binding, full, PointPx(.5, .1), PointPx(.5, .9), outputQuarterTurns = 1))
        assertEquals(expected, curved.request.recipe)
        assertEquals(122, sample(curved, 0, 50))
        assertEquals(58, sample(curved, 80, 50))
        editor.setShapeCoordinate(ShapePoint.TOP_MIDPOINT, horizontal = false, value = .95)
        advanceUntilIdle()
        assertBlocked()
        assertEquals(RenderStatus.INVALID_MAP, editor.preview.value.failure)
        assertTrue(editor.preview.value.previous)
        assertSame(curved, preview())
        editor.undo()
        advanceUntilIdle()
        assertEquals(expected, preview().request.recipe)
        editor.apply()
        advanceUntilIdle()
        assertEquals(listOf(expected), saves)
    }

    private fun checkShape(check: suspend Fixture.() -> Unit) = runTest {
        val f = Fixture(this)
        try {
            advanceUntilIdle()
            f.check()
        } finally {
            f.editor.close()
            assertArrayEquals(f.pixels, f.image.toPackedByteArray())
            f.image.close()
        }
        assertEquals(0L, f.budget.usedBytes)
    }

    private class Fixture(private val scope: TestScope) : EditorActions, RenderCoordinator {
        val pixels = ByteArray(101 * 81 * 3) { (it / 3 % 101 + it / 3 / 101).toByte() }
        val image = RgbImage(101, 81, 303, pixels)
        val binding = SourceBinding(MessageDigest.getInstance("SHA-256").digest(pixels).toHexString(), PixelSize(101, 81), "host-synthetic-rgb8-v1")
        val full = QuadPx(PointPx(0.0, 0.0), PointPx(1.0, 0.0), PointPx(1.0, 1.0), PointPx(0.0, 1.0))
        val base = Recipe(geometry = GeometryRecipe.None(binding, outputQuarterTurns = 1), binary = BinaryRecipe.GrayscaleV1)
        val perspective = GeometryRecipe.Perspective(binding, full, outputQuarterTurns = 1)
        private val document = DocumentId("00000000-0000-0000-0000-000000000001")
        val page = PageId("00000000-0000-0000-0000-000000000002")
        val source = SourceId("00000000-0000-0000-0000-000000000003")
        val budget = ImageAllocationBudget(32L * 1024 * 1024)
        val saves = mutableListOf<Recipe>()
        var staleNext = false
        val editor = EditorSession(document.value, page.value, this, this, scope)

        fun advanceUntilIdle() = scope.advanceUntilIdle()
        fun preview() = checkNotNull(editor.preview.value.preview)
        fun assertBlocked() {
            editor.apply()
            // Capture any wrongly enqueued write without completing the pending renderer first.
            assertFalse("Apply must not begin an unverified shape save", editor.state.value.saving)
            assertTrue(saves.isEmpty())
            editor.requestBack()
            assertTrue(editor.state.value.confirmExit)
            editor.applyAndBack()
            assertFalse("Apply-and-exit must not begin an unverified shape save", editor.state.value.saving)
            assertFalse(editor.state.value.exitReady)
            assertTrue(saves.isEmpty())
        }

        override suspend fun load(documentId: DocumentId, pageId: PageId): EditablePage {
            assertEquals(document, documentId)
            assertEquals(page, pageId)
            return EditablePage(document, 5, page, source, 7, RecipeCodec.hash(base), base)
        }

        override suspend fun save(pageId: PageId, expectedRevision: Long, recipe: Recipe): SaveRecipeResult {
            assertEquals(page, pageId)
            assertEquals(7L, expectedRevision)
            saves += recipe
            return SaveRecipeResult.Saved(page, 8, 6, RecipeCodec.hash(recipe))
        }

        override suspend fun render(request: RenderRequest): RenderResult {
            assertEquals(binding, request.recipe.geometry.source)
            assertEquals(source.value, request.sourceId)
            val raster = try {
                GeometryEngine(budget).render(image, binding, request.recipe.geometry, request.output, null, null) {}.use { geometry ->
                    AppearanceEngine(budget).render(geometry.image, request.recipe, geometry.protection) {}
                }
            } catch (failure: RepositoryFailure) {
                // This test adapter only handles the failure deliberately exercised by the curved fixture.
                assertEquals(OperationErrorCode.INVALID_MAP, failure.error.code)
                return RenderResult(request, RenderStatus.INVALID_MAP, null, emptyList(), emptyList())
            }
            val response = if (staleNext) request.copy(generation = request.generation - 1) else request
            staleNext = false
            return RenderResult(response, RenderStatus.OK, raster.image, raster.stages, emptyList(), raster)
        }
    }

    companion object {
        private fun sample(result: RenderResult, x: Int, y: Int): Int {
            val image = checkNotNull(result.image)
            return image.toPackedByteArray()[(y * image.width + x) * 3].toInt() and 255
        }
    }
}
