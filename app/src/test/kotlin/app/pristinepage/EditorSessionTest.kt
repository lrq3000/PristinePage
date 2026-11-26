package app.pristinepage

import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.*
import app.pristinepage.data.model.*
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Actual editor history/scheduler/result lifetimes; only suspend repository and expensive rendering IO are controlled. The consuming native screen must separately prove Application/Room/bitmap/Apply wiring. */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorSessionTest {
    private val document = DocumentId("00000000-0000-0000-0000-000000000001")
    private val pageId = PageId("00000000-0000-0000-0000-000000000002")
    private val sourceId = SourceId("00000000-0000-0000-0000-000000000003")
    private val binding = SourceBinding("a".repeat(64),PixelSize(3,2),"fixture-srgb-v1")
    private val original = Recipe(geometry = GeometryRecipe.None(binding))
    private val white = original.copy(appearance = AppearanceRecipe.PaperGainV1(strength = .5))
    private val rotated = white.copy(geometry = white.geometry.withOutputQuarterTurns(1))
    private fun page(recipe: Recipe = original) = EditablePage(document,5,pageId,sourceId,7,RecipeCodec.hash(recipe),recipe)
    private fun saved(recipe: Recipe) = SaveRecipeResult.Saved(pageId,8,6,RecipeCodec.hash(recipe))

    private class Renderer : RenderCoordinator {
        val requests = mutableListOf<RenderRequest>()
        val outputs = mutableListOf<RenderResult>()
        var releases = 0
        override suspend fun render(request: RenderRequest): RenderResult {
            requests += request
            val size = request.output.size
            return RenderResult(request,RenderStatus.OK,RgbImage(size.width,size.height,size.width*3,ByteArray(size.width*size.height*3) { 40 }),emptyList(),emptyList(),AutoCloseable { releases++ }).also { outputs += it }
        }
    }

    private inner class Fixture {
        val renderer = Renderer()
        val loads = mutableListOf<Pair<String,String>>()
        val saves = mutableListOf<Triple<String,Long,Recipe>>()
        var loading: suspend () -> EditablePage = { page() }
        var saving: suspend (Recipe) -> SaveRecipeResult = { saved(it) }
        fun session(scope: CoroutineScope): Harness {
            val actionsType = type("app.pristinepage.editor.EditorActions")
            val actions = Proxy.newProxyInstance(actionsType.classLoader,arrayOf(actionsType)) { _, method, args ->
                checkNotNull(args)
                val body: suspend () -> Any? = when {
                    method.name.startsWith("load") -> { { loads += (args[0] as String) to (args[1] as String); loading() } }
                    method.name.startsWith("save") -> { { val recipe = args[2] as Recipe; saves += Triple(args[0] as String,args[1] as Long,recipe); saving(recipe) } }
                    else -> error("Unexpected editor IO: ${method.name}")
                }
                @Suppress("UNCHECKED_CAST")
                body.startCoroutineUninterceptedOrReturn(args.last() as Continuation<Any?>)
            }
            val session = type("app.pristinepage.editor.EditorSession").getConstructor(String::class.java,String::class.java,actionsType,RenderCoordinator::class.java,CoroutineScope::class.java).newInstance(document.value,pageId.value,actions,renderer,scope)
            return Harness(session)
        }
    }

    private class Harness(private val session: Any) : AutoCloseable {
        private val value get() = (session.javaClass.getMethod("getState").invoke(session) as StateFlow<*>).value!!
        @Suppress("UNCHECKED_CAST") fun <T> field(name: String): T = value.javaClass.getMethod("get$name").invoke(value) as T
        val page get() = field<EditablePage?>("Page")
        val draft get() = field<Recipe?>("Draft")
        val error get() = field<OperationError?>("Error")
        val preview get() = (session.javaClass.getMethod("getPreview").invoke(session) as StateFlow<*>).value!!
        fun command(name: String) { invoke { session.javaClass.getMethod(name).invoke(session) } }
        fun edit(recipe: Recipe) { invoke { session.javaClass.getMethod("edit",Recipe::class.java).invoke(session,recipe) } }
        fun active(value: Boolean) { invoke { session.javaClass.getMethod("setActive",Boolean::class.javaPrimitiveType).invoke(session,value) } }
        private fun invoke(action: () -> Any?) { try { action() } catch (error: InvocationTargetException) { throw error.targetException } }
        override fun close() { (session as AutoCloseable).close() }
    }

    @Test fun openingLoadsOneExactRetainedPageAndRequestsItsRecipeWithoutSaving() = runTest {
        val f = Fixture(); val s = f.session(this); advanceUntilIdle()
        assertEquals(listOf(document.value to pageId.value),f.loads); assertEquals(page(),s.page); assertEquals(original,s.draft)
        assertFalse(s.field("Dirty")); assertFalse(s.field("Loading")); assertTrue(f.saves.isEmpty())
        val request = f.renderer.requests.single()
        assertEquals(sourceId.value,request.sourceId); assertEquals(pageId.value,request.pageId); assertEquals(7L,request.recipeRevision)
        assertEquals(original,request.recipe); assertEquals(OutputSpec(PixelSize(3,2),RenderPurpose.PREVIEW),request.output); assertEquals(FallbackPolicy.FAIL,request.fallback)
        s.close(); assertEquals(f.renderer.outputs.size,f.renderer.releases)
    }

    @Test fun draftsUndoRedoAndDiscardNeverChangeThePersistedAcceptedRecipe() = runTest {
        val f = Fixture(); val s = f.session(this); advanceUntilIdle(); s.edit(white); s.edit(rotated); advanceUntilIdle()
        assertEquals(original,s.page!!.recipe); assertEquals(rotated,s.draft); assertTrue(s.field("Dirty")); assertTrue(f.saves.isEmpty())
        s.command("undo"); assertEquals(white,s.draft); s.command("undo"); assertEquals(original,s.draft); assertFalse(s.field("Dirty"))
        s.command("redo"); assertEquals(white,s.draft); s.command("discard"); assertEquals(original,s.draft)
        assertFalse(s.field("CanUndo")); assertFalse(s.field("CanRedo")); advanceUntilIdle(); s.close(); assertEquals(f.renderer.outputs.size,f.renderer.releases)
    }

    @Test fun rotationPreservesSourceAndChoosesPurposeSizedRotatedAspect() = runTest {
        val wideBinding = binding.copy(sourceSize = PixelSize(2048,512)); val wide = Recipe(geometry = GeometryRecipe.None(wideBinding))
        val f = Fixture().apply { loading = { page(wide) } }; val s = f.session(this); advanceUntilIdle()
        assertEquals(PixelSize(1024,256),f.renderer.requests.last().output.size)
        s.command("rotateClockwise"); advanceUntilIdle(); val request = f.renderer.requests.last()
        assertEquals(1,request.recipe.geometry.outputQuarterTurns); assertEquals(wideBinding,request.recipe.geometry.source); assertEquals(PixelSize(256,1024),request.output.size)
        assertEquals(wide,s.page!!.recipe); assertTrue(f.saves.isEmpty()); s.close(); assertEquals(f.renderer.outputs.size,f.renderer.releases)
    }

    @Test fun counterclockwiseRotationIsAnUndoableOutputTurnAndDoesNotWriteTheSource() = runTest {
        val f = Fixture(); val s = f.session(this); advanceUntilIdle()
        s.command("rotateCounterclockwise"); advanceUntilIdle()
        val turned = f.renderer.requests.last().recipe
        assertEquals(3, turned.geometry.outputQuarterTurns)
        assertEquals(original.geometry.source, turned.geometry.source)
        s.command("undo"); advanceUntilIdle()
        assertEquals(original, f.renderer.requests.last().recipe)
        s.close()
    }

    @Test fun rapidEditsReplaceQueuedWorkInsteadOfRenderingEveryIntermediateDraft() = runTest {
        val f = Fixture(); val s = f.session(this); advanceUntilIdle(); val first = f.renderer.requests.single()
        s.edit(white); s.edit(rotated); advanceUntilIdle()
        assertEquals(2,f.renderer.requests.size); assertEquals(rotated,f.renderer.requests.last().recipe); assertTrue(f.renderer.requests.last().generation > first.generation)
        assertTrue(f.saves.isEmpty()); s.close()
    }

    @Test fun applyUsesCapturedExpectedRevisionAndChangesBaselineOnlyAfterMatchingSaved() = runTest {
        val gate = CompletableDeferred<SaveRecipeResult>(); val f = Fixture().apply { saving = { gate.await() } }; val s = f.session(this); advanceUntilIdle()
        s.edit(white); s.command("apply"); runCurrent(); assertTrue(s.field("Saving")); assertEquals(original,s.page!!.recipe)
        s.edit(rotated); s.command("apply"); s.command("undo"); assertEquals(white,s.draft); assertEquals(1,f.saves.size)
        assertEquals(Triple(pageId.value,7L,white),f.saves.single()); gate.complete(saved(white)); advanceUntilIdle()
        assertEquals(8L,s.page!!.recipeRevision); assertEquals(6L,s.page!!.documentRevision); assertEquals(white,s.page!!.recipe)
        assertFalse(s.field("Dirty")); assertFalse(s.field("Saving")); assertEquals(8L,f.renderer.requests.last().recipeRevision)
        s.command("undo"); assertEquals(original,s.draft); assertTrue(s.field("Dirty")); assertEquals(white,s.page!!.recipe); advanceUntilIdle(); s.close()
    }

    @Test fun conflictsAndStructuredFailuresKeepTheDraftWithoutPretendingItWasSaved() = runTest {
        for (failure in listOf(SaveRecipeResult.Conflict(9),SaveRecipeResult.Rejected(OperationError(OperationErrorCode.STORAGE_FAILED,true,"fixture_write_failed",pageId.value)))) {
            val f = Fixture().apply { saving = { failure } }; val s = f.session(this); advanceUntilIdle(); s.edit(white); s.command("apply"); advanceUntilIdle()
            assertEquals(7L,s.page!!.recipeRevision); assertEquals(original,s.page!!.recipe); assertEquals(white,s.draft); assertTrue(s.field("Dirty")); assertFalse(s.field("Saving"))
            assertEquals(if (failure is SaveRecipeResult.Conflict) OperationErrorCode.REVISION_CONFLICT else OperationErrorCode.STORAGE_FAILED,s.error!!.code)
            assertFalse(s.field("ExitReady")); s.close()
        }
    }

    @Test fun unconfirmedSaveCannotWriteAgainFromTheSameEditorEvenAfterAnotherEdit() = runTest {
        val issue = OperationError(OperationErrorCode.STORAGE_FAILED, false, "editor_save_delivery_unconfirmed", pageId.value)
        val f = Fixture().apply { saving = { throw RepositoryFailure(issue) } }
        val s = f.session(this)
        advanceUntilIdle()
        s.edit(white)
        s.command("apply")
        advanceUntilIdle()
        assertEquals(issue, s.error)
        assertEquals(7L, s.page!!.recipeRevision)
        assertTrue(s.field("Dirty"))
        assertEquals(f.renderer.outputs.size, f.renderer.releases)
        val before = f.renderer.requests.size
        s.edit(rotated)
        s.command("apply")
        s.command("applyAndBack")
        advanceUntilIdle()
        assertEquals(before, f.renderer.requests.size)
        assertEquals(1, f.saves.size)
        assertEquals(rotated, s.draft)
        assertFalse(s.field("ExitReady"))
        s.command("requestBack")
        s.command("discardAndBack")
        assertTrue(s.field("ExitReady"))
        s.close()
    }

    @Test fun malformedSuccessCannotAuthorizeAnotherPageRecipeOrNonmonotonicRevision() = runTest {
        for (response in listOf(saved(white).copy(pageId = PageId("00000000-0000-0000-0000-000000000004")),saved(white).copy(recipeHash = "b".repeat(64)),saved(white).copy(recipeRevision = 7),saved(white).copy(documentRevision = 5))) {
            val f = Fixture().apply { saving = { response } }; val s = f.session(this); advanceUntilIdle(); s.edit(white); s.command("apply"); advanceUntilIdle()
            assertEquals(original,s.page!!.recipe); assertEquals(white,s.draft); assertTrue(s.field("Dirty")); assertEquals(OperationErrorCode.INTEGRITY_FAILED,s.error!!.code); s.close()
        }
    }

    @Test fun backWithADraftOffersAChoiceAndDiscardExitsWithoutAnyRepositoryWrite() = runTest {
        val f = Fixture(); val s = f.session(this); advanceUntilIdle(); s.edit(white); s.command("requestBack")
        assertTrue(s.field("ConfirmExit")); assertFalse(s.field("ExitReady")); assertTrue(f.saves.isEmpty())
        s.command("keepEditing"); assertFalse(s.field("ConfirmExit")); assertEquals(white,s.draft)
        s.command("requestBack"); s.command("discardAndBack"); advanceUntilIdle()
        assertTrue(s.field("ExitReady")); assertEquals(original,s.draft); assertTrue(f.saves.isEmpty()); assertEquals(f.renderer.outputs.size,f.renderer.releases); s.close()
    }

    @Test fun applyAndBackWaitsForPersistenceNotMerelyACompletedPreview() = runTest {
        val gate = CompletableDeferred<SaveRecipeResult>(); val f = Fixture().apply { saving = { gate.await() } }; val s = f.session(this); advanceUntilIdle(); s.edit(white); advanceUntilIdle()
        s.command("applyAndBack"); runCurrent(); assertFalse(s.field("ExitReady")); assertEquals(original,s.page!!.recipe)
        gate.complete(saved(white)); advanceUntilIdle(); assertTrue(s.field("ExitReady")); assertEquals(white,s.page!!.recipe); assertEquals(f.renderer.outputs.size,f.renderer.releases); s.close()
    }

    @Test fun closingBeforeANoncooperativeLoadReturnsCannotPublishOrStartPreviewWork() = runTest {
        val gate = CompletableDeferred<EditablePage>(); val f = Fixture().apply { loading = { withContext(NonCancellable) { gate.await() } } }; val s = f.session(this); runCurrent()
        s.close(); gate.complete(page()); advanceUntilIdle(); assertNull(s.page); assertTrue(f.renderer.requests.isEmpty()); assertTrue(f.saves.isEmpty())
    }

    @Test fun closingBeforeANoncooperativeSaveReturnsCannotPublishAFalseLocalAcceptance() = runTest {
        val gate = CompletableDeferred<SaveRecipeResult>(); val f = Fixture().apply { saving = { withContext(NonCancellable) { gate.await() } } }; val s = f.session(this); advanceUntilIdle(); s.edit(white); s.command("apply"); runCurrent()
        s.close(); gate.complete(saved(white)); advanceUntilIdle(); assertEquals(original,s.page!!.recipe); assertEquals(7L,s.page!!.recipeRevision); assertEquals(f.renderer.outputs.size,f.renderer.releases)
    }

    @Test fun failedLoadIsSafeAndRetryCannotDiscardAnAlreadyLoadedDraft() = runTest {
        val f = Fixture().apply { loading = { throw IllegalStateException("private/path/must-not-publish") } }; val s = f.session(this); advanceUntilIdle()
        assertNull(s.page); assertEquals(OperationErrorCode.STORAGE_FAILED,s.error!!.code); assertFalse(s.error.toString().contains("private/path"))
        f.loading = { page() }; s.command("retryLoad"); advanceUntilIdle(); s.edit(white); s.command("retryLoad"); advanceUntilIdle()
        assertEquals(2,f.loads.size); assertEquals(white,s.draft); assertEquals(original,s.page!!.recipe); assertTrue(f.saves.isEmpty()); s.close()
    }

    @Test fun unchangedOrForeignSourceEditCannotConsumeHistoryOrMakeAnUnrelatedPreviewCurrent() = runTest {
        val f = Fixture(); val s = f.session(this); advanceUntilIdle(); s.edit(original); s.command("apply"); advanceUntilIdle()
        assertFalse(s.field("CanUndo")); assertEquals(1,f.renderer.requests.size); assertTrue(f.saves.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { s.edit(original.copy(geometry = GeometryRecipe.None(binding.copy(sourcePixelHash = "b".repeat(64))))) }
        assertEquals(original,s.draft); assertFalse(s.field("Dirty")); assertEquals(1,f.renderer.requests.size); s.close()
    }

    @Test fun backgroundPauseReleasesPreviewButKeepsDraftAndResumeDoesNotReloadOrSave() = runTest {
        val f = Fixture(); val s = f.session(this); advanceUntilIdle(); s.edit(white); advanceUntilIdle(); val previousGeneration = f.renderer.requests.last().generation
        s.active(false); advanceUntilIdle(); assertEquals(f.renderer.outputs.size,f.renderer.releases); assertEquals(white,s.draft); assertTrue(s.field("Dirty"))
        val renderCount = f.renderer.requests.size; s.edit(rotated); advanceUntilIdle(); assertEquals(renderCount,f.renderer.requests.size)
        s.active(true); advanceUntilIdle(); assertEquals(rotated,f.renderer.requests.last().recipe); assertTrue(f.renderer.requests.last().generation > previousGeneration)
        assertEquals(1,f.loads.size); assertTrue(f.saves.isEmpty()); s.close(); assertEquals(f.renderer.outputs.size,f.renderer.releases)
    }

    private companion object {
        fun type(name: String): Class<*> = try { Class.forName(name) } catch (_: ClassNotFoundException) { throw AssertionError("Consuming editor session is absent") }
    }
}
