package app.pristinepage

import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.*
import app.pristinepage.data.model.*
import app.pristinepage.editor.*
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Actual session/history/scheduler/request ownership. Only repository IO and expensive rendering are controlled; native literal comparison pixels and visible labels remain separate consuming tests. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EditorComparisonTest {
    @Test fun inputKeepsDraftGeometryWhileOriginalBypassesUserTurnsAndAllPhotometricStages() = runTest {
        val f = Fixture(); val s = f.session(this)
        try {
            advanceUntilIdle(); show(s,"input"); advanceUntilIdle()
            assertEquals(Recipe(geometry = f.accepted.geometry),f.requests.last().recipe); assertEquals(PixelSize(2,6),f.requests.last().output.size)
            assertEquals(f.accepted,s.state.value.draft); assertFalse(s.state.value.dirty)
            show(s,"original"); advanceUntilIdle()
            assertEquals(Recipe(geometry = GeometryRecipe.None(f.binding)),f.requests.last().recipe); assertEquals(PixelSize(6,2),f.requests.last().output.size)
            show(s,"result"); advanceUntilIdle(); assertEquals(f.accepted,f.requests.last().recipe); assertEquals(PixelSize(2,6),f.requests.last().output.size)
            assertEquals(listOf(1L,2L,3L,4L),f.requests.map { it.generation }); assertTrue(f.saves.isEmpty())
        } finally { s.close() }
        assertEquals(f.requests.size,f.releases)
    }

    @Test fun viewSwitchesNeverConsumeHistoryOrSaveAndRepeatedViewIsANoOp() = runTest {
        val f = Fixture(); val s = f.session(this)
        try {
            advanceUntilIdle(); show(s,"input"); advanceUntilIdle(); show(s,"input"); s.apply(); advanceUntilIdle()
            assertEquals(2,f.requests.size); assertFalse(s.state.value.canUndo); assertFalse(s.state.value.canRedo); assertFalse(s.state.value.dirty); assertTrue(f.saves.isEmpty())
            assertEquals(f.accepted,s.state.value.page!!.recipe)
        } finally { s.close() }
    }

    @Test fun applyWhileViewingOriginalSavesTheCompleteDraftNotTheBypassComparisonRecipe() = runTest {
        val f = Fixture(); val s = f.session(this)
        try {
            advanceUntilIdle(); val draft = f.accepted.copy(appearance = AppearanceRecipe.NaturalLV1(),binary = null)
            s.edit(draft); show(s,"original"); advanceUntilIdle(); s.apply(); advanceUntilIdle()
            assertEquals(listOf(draft),f.saves); assertEquals(draft,s.state.value.page!!.recipe); assertFalse(s.state.value.dirty)
            assertEquals(draft,f.requests.last().recipe); assertEquals("RESULT",viewName(s)); assertEquals(8L,f.requests.last().recipeRevision)
        } finally { s.close() }
    }

    @Test fun editingARecipeWhileComparingOriginalReturnsToResultWithoutChangingTheSourceBinding() = runTest {
        val f = Fixture(); val s = f.session(this)
        try {
            advanceUntilIdle(); show(s,"original"); advanceUntilIdle(); s.rotateClockwise(); advanceUntilIdle()
            assertEquals("RESULT",viewName(s)); assertEquals(2,s.state.value.draft!!.geometry.outputQuarterTurns); assertEquals(f.binding,f.requests.last().recipe.geometry.source)
            assertEquals(s.state.value.draft,f.requests.last().recipe); assertTrue(s.state.value.dirty); assertTrue(f.saves.isEmpty())
        } finally { s.close() }
    }

    @Test fun onlyTheExactlyAcknowledgedEditorRevisionNotifiesOcrAndKeepsTheSaveSuccessful() = runTest {
        val f = Fixture()
        val commits = mutableListOf<Pair<DocumentId, List<PageId>>>()
        val s = f.session(this) { document, pages ->
            commits += document to pages
            throw IllegalStateException("Synthetic notification refusal")
        }
        try {
            advanceUntilIdle()
            val page = checkNotNull(s.state.value.page)
            val draft = f.accepted.copy(appearance = AppearanceRecipe.NaturalLV1(), binary = null)
            f.savedResponse = { SaveRecipeResult.Saved(PageId("00000000-0000-0000-0000-000000000007"), 8, 6, RecipeCodec.hash(it)) }
            s.edit(draft); s.apply(); advanceUntilIdle()
            assertTrue(commits.isEmpty())
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, s.state.value.error?.code)
            f.savedResponse = null
            s.apply(); advanceUntilIdle()
            assertEquals(listOf(page.documentId to listOf(page.pageId)), commits)
            assertEquals(draft, s.state.value.page?.recipe)
            assertNull(s.state.value.error)
        } finally { s.close() }
    }

    @Test fun pauseResumeKeepsTheChosenComparisonAndCannotReloadOrSaveADifferentRecipe() = runTest {
        val f = Fixture(); val s = f.session(this)
        try {
            advanceUntilIdle(); show(s,"input"); advanceUntilIdle(); val before = f.requests.last().generation
            s.setActive(false); advanceUntilIdle(); assertEquals(f.requests.size,f.releases)
            s.setActive(true); advanceUntilIdle(); assertEquals("GEOMETRY_INPUT",viewName(s)); assertEquals(Recipe(geometry = f.accepted.geometry),f.requests.last().recipe)
            assertTrue(f.requests.last().generation > before); assertEquals(1,f.loads); assertTrue(f.saves.isEmpty())
        } finally { s.close() }
    }

    @Test fun anUnknownComparisonCannotConsumeGenerationOrSubstituteAnotherView() = runTest {
        val f = Fixture(); val s = f.session(this)
        try {
            advanceUntilIdle(); assertThrows(IllegalArgumentException::class.java) { show(s,"full-resolution-detail-placeholder") }
            assertEquals(1,f.requests.size); assertEquals("RESULT",viewName(s)); assertEquals(f.accepted,s.state.value.draft)
        } finally { s.close() }
    }

    private fun show(session: EditorSession, id: String) {
        val method = try { session.javaClass.getMethod("showView",String::class.java) } catch (_: NoSuchMethodException) { throw AssertionError("Editor source comparison is absent") }
        try { method.invoke(session,id) } catch (error: InvocationTargetException) { throw error.targetException }
    }
    private fun viewName(session: EditorSession): String = (session.state.value.javaClass.getMethod("getView").invoke(session.state.value) as Enum<*>).name

    private class Fixture : EditorActions, RenderCoordinator {
        val binding = SourceBinding("a".repeat(64),PixelSize(6,2),"fixture-srgb-v1")
        val accepted = Recipe(geometry = GeometryRecipe.None(binding,outputQuarterTurns = 1),appearance = AppearanceRecipe.PaperGainV1(strength = .5),denoise = DenoiseRecipe.BilateralLumaV1(),detail = DetailRecipe.LumaDetail7V1(),binary = BinaryRecipe.GrayscaleV1)
        private val document = DocumentId("00000000-0000-0000-0000-000000000001"); private val page = PageId("00000000-0000-0000-0000-000000000002"); private val source = SourceId("00000000-0000-0000-0000-000000000003")
        val requests = mutableListOf<RenderRequest>(); val saves = mutableListOf<Recipe>(); var releases = 0; var loads = 0
        var savedResponse: ((Recipe) -> SaveRecipeResult)? = null
        fun session(scope: CoroutineScope, onPagesSaved: (DocumentId, List<PageId>) -> Unit = { _, _ -> }) =
            EditorSession(document.value, page.value, this, this, scope, onPagesSaved = onPagesSaved)
        override suspend fun load(documentId: DocumentId, pageId: PageId): EditablePage { assertEquals(document,documentId); assertEquals(page,pageId); loads++; return EditablePage(document,5,page,source,7,RecipeCodec.hash(accepted),accepted) }
        override suspend fun save(pageId: PageId, expectedRevision: Long, recipe: Recipe): SaveRecipeResult { assertEquals(page,pageId); assertEquals(7L,expectedRevision); saves += recipe; return savedResponse?.invoke(recipe) ?: SaveRecipeResult.Saved(page,8,6,RecipeCodec.hash(recipe)) }
        override suspend fun render(request: RenderRequest): RenderResult { requests += request; val size = request.output.size; return RenderResult(request,RenderStatus.OK,RgbImage(size.width,size.height,size.width*3,ByteArray(size.width*size.height*3)),emptyList(),emptyList(),AutoCloseable { releases++ }) }
    }
}
