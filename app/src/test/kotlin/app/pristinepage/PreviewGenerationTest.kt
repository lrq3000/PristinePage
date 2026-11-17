package app.pristinepage

import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.*
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Only the slow render boundary is controlled. Actual RenderResult/RgbImage lifetimes expose leaked or stale ownership; no scheduler transition is substituted. */
@OptIn(ExperimentalCoroutinesApi::class)
class PreviewGenerationTest {
    private val binding = SourceBinding("a".repeat(64), PixelSize(2,1), "fixture-srgb-v1")
    private fun request(generation: Long) = RenderRequest("00000000-0000-0000-0000-000000000001",binding.sourcePixelHash,"00000000-0000-0000-0000-000000000002",1,Recipe(geometry = GeometryRecipe.None(binding)),OutputSpec(PixelSize(2,1),RenderPurpose.PREVIEW),generation,null,FallbackPolicy.FAIL)
    private class ControlledRenderer : RenderCoordinator {
        val requests = LinkedHashMap<Long,RenderRequest>()
        val gates = LinkedHashMap<Long,CompletableDeferred<RenderResult>>()
        override suspend fun render(request: RenderRequest): RenderResult {
            requests[request.generation] = request
            val gate = CompletableDeferred<RenderResult>().also { gates[request.generation] = it }
            // The provider finishes privately despite canceled work, as some native runtimes do. The real scheduler must independently fence/close its returned result.
            return withContext(NonCancellable) { gate.await() }
        }
        fun finish(generation: Long, result: RenderResult) { checkNotNull(gates[generation]).complete(result) }
    }
    private class Owned(request: RenderRequest, gray: Int = 40, status: RenderStatus = RenderStatus.OK, size: PixelSize = request.output.size) {
        var releases = 0
        val result = RenderResult(request,status,if (status in setOf(RenderStatus.OK,RenderStatus.DEGRADED)) RgbImage(size.width,size.height,size.width*3,ByteArray(size.width*size.height*3) { gray.toByte() }) else null,emptyList(),emptyList(),AutoCloseable { releases++ })
        fun assertClosed() { assertEquals(1,releases); result.image?.let { assertThrows(IllegalStateException::class.java) { it.toPackedByteArray() } } }
    }
    private class Harness(renderer: RenderCoordinator, scope: CoroutineScope) : AutoCloseable {
        val scheduler: Any = try { Class.forName("app.pristinepage.editor.PreviewScheduler").getConstructor(RenderCoordinator::class.java,CoroutineScope::class.java).newInstance(renderer,scope) }
        catch (_: ClassNotFoundException) { throw AssertionError("Generation-fenced preview scheduler is absent") }
        fun schedule(request: RenderRequest) { invoke("schedule",RenderRequest::class.java,request) }
        private fun invoke(name: String, type: Class<*>, argument: Any) { try { scheduler.javaClass.getMethod(name,type).invoke(scheduler,argument) } catch (error: InvocationTargetException) { throw error.targetException } }
        val state get() = (scheduler.javaClass.getMethod("getState").invoke(scheduler) as StateFlow<*>).value!!
        @Suppress("UNCHECKED_CAST") fun <T> field(name: String): T = state.javaClass.getMethod("get$name").invoke(state) as T
        val preview get() = field<RenderResult?>("Preview")
        override fun close() { (scheduler as AutoCloseable).close() }
    }

    @Test fun generationEightFinishingAfterNineCannotReplaceOrLeakTheCurrentOutput() = runTest {
        val renderer = ControlledRenderer(); val scheduler = Harness(renderer,this)
        scheduler.schedule(request(8)); runCurrent(); scheduler.schedule(request(9)); runCurrent()
        val newer = Owned(request(9),90); renderer.finish(9,newer.result); runCurrent()
        val older = Owned(request(8),80); renderer.finish(8,older.result); runCurrent()
        assertSame(newer.result,scheduler.preview); assertEquals(90,scheduler.preview!!.image!!.getUnsigned(0,0,0)); older.assertClosed()
        assertFalse(scheduler.field("Previous")); scheduler.close(); newer.assertClosed()
    }

    @Test fun obsoleteQueuedSelectionDoesNotStartTheRenderer() = runTest {
        val renderer = ControlledRenderer(); val scheduler = Harness(renderer,this)
        scheduler.schedule(request(1)); scheduler.schedule(request(2)); runCurrent()
        assertEquals(listOf(2L),renderer.requests.keys.toList())
        val owned = Owned(request(2)); renderer.finish(2,owned.result); runCurrent(); scheduler.close(); owned.assertClosed()
    }

    @Test fun samePageFailureKeepsTheWholePreviousOwnerButNeverPublishesDegradedPixels() = runTest {
        val renderer = ControlledRenderer(); val scheduler = Harness(renderer,this)
        val first = Owned(request(1)); scheduler.schedule(request(1)); runCurrent(); renderer.finish(1,first.result); runCurrent()
        for ((generation,status) in listOf(2L to RenderStatus.RESOURCE_LIMIT,3L to RenderStatus.DEGRADED,4L to RenderStatus.CANCELED)) {
            scheduler.schedule(request(generation)); assertSame(first.result,scheduler.preview); assertTrue(scheduler.field("Previous")); runCurrent()
            val failed = Owned(request(generation),status = status); renderer.finish(generation,failed.result); runCurrent()
            assertSame(first.result,scheduler.preview); assertEquals(status,scheduler.field<RenderStatus>("Failure")); assertFalse(scheduler.field("Processing")); failed.assertClosed(); assertEquals(0,first.releases)
        }
        scheduler.close(); first.assertClosed()
    }

    @Test fun everyRequestIdentityAndActualSizeMustMatchBeforePublication() = runTest {
        val variants: List<(RenderRequest) -> RenderRequest> = listOf(
            { it.copy(pageId = "00000000-0000-0000-0000-000000000003") }, { it.copy(sourceId = "00000000-0000-0000-0000-000000000004") },
            { it.copy(sourcePixelHash = "b".repeat(64)) }, { it.copy(recipeRevision = 2) }, { it.copy(generation = 99) },
            { it.copy(recipe = it.recipe.copy(appearance = AppearanceRecipe.PaperGainV1())) },
            { it.copy(output = it.output.copy(purpose = RenderPurpose.CARD)) }, { it.copy(output = it.output.copy(size = PixelSize(1,1))) },
            { it.copy(protectionMaskId = "00000000-0000-0000-0000-000000000005") }, { it.copy(fallback = FallbackPolicy.KEEP_STAGE_INPUT) },
        )
        for (variant in variants) {
            val renderer = ControlledRenderer(); val scheduler = Harness(renderer,this); val selected = request(1)
            scheduler.schedule(selected); runCurrent(); val wrong = Owned(variant(selected)); renderer.finish(1,wrong.result); runCurrent()
            assertNull(scheduler.preview); assertEquals(RenderStatus.PROCESSING_FAILED,scheduler.field<RenderStatus>("Failure")); wrong.assertClosed(); scheduler.close()
        }
        val renderer = ControlledRenderer(); val scheduler = Harness(renderer,this); scheduler.schedule(request(1)); runCurrent()
        val wrongSize = Owned(request(1),size = PixelSize(1,1)); renderer.finish(1,wrongSize.result); runCurrent(); assertNull(scheduler.preview); wrongSize.assertClosed(); scheduler.close()
    }

    @Test fun changingPageOrSourceImmediatelyDetachesTheUnrelatedPreview() = runTest {
        for (other in listOf(request(2).copy(pageId = "00000000-0000-0000-0000-000000000003"),request(2).copy(sourceId = "00000000-0000-0000-0000-000000000004"))) {
            val renderer = ControlledRenderer(); val scheduler = Harness(renderer,this); val first = Owned(request(1))
            scheduler.schedule(request(1)); runCurrent(); renderer.finish(1,first.result); runCurrent()
            scheduler.schedule(other); assertNull(scheduler.preview); first.assertClosed(); runCurrent()
            val next = Owned(other); renderer.finish(2,next.result); runCurrent(); scheduler.close(); next.assertClosed()
        }
    }

    @Test fun replacementAndTerminalCloseReleaseWholeOwnersEvenWhenOldWorkFinishesLate() = runTest {
        val renderer = ControlledRenderer(); val scheduler = Harness(renderer,this); val first = Owned(request(1)); val second = Owned(request(2))
        scheduler.schedule(request(1)); runCurrent(); renderer.finish(1,first.result); runCurrent()
        scheduler.schedule(request(2)); runCurrent(); renderer.finish(2,second.result); runCurrent(); first.assertClosed()
        scheduler.schedule(request(3)); runCurrent(); scheduler.close(); scheduler.close(); assertNull(scheduler.preview); second.assertClosed()
        val late = Owned(request(3)); renderer.finish(3,late.result); runCurrent(); late.assertClosed(); assertNull(scheduler.preview)
        assertThrows(IllegalStateException::class.java) { scheduler.schedule(request(4)) }
    }

    @Test fun reusedOrNonpositiveGenerationCannotMakeAnOldRequestCurrentAgain() = runTest {
        val renderer = ControlledRenderer(); val scheduler = Harness(renderer,this)
        assertThrows(IllegalArgumentException::class.java) { scheduler.schedule(request(0)) }
        scheduler.schedule(request(8)); runCurrent()
        assertThrows(IllegalArgumentException::class.java) { scheduler.schedule(request(8).copy(recipeRevision = 2)) }
        assertThrows(IllegalArgumentException::class.java) { scheduler.schedule(request(7)) }
        assertEquals(request(8),scheduler.field<RenderRequest>("Request")); scheduler.close()
        val late = Owned(request(8)); renderer.finish(8,late.result); runCurrent(); late.assertClosed()
    }

    @Test fun unexpectedRenderExceptionCannotEraseThePreviousPreviewOrExposeItsMessage() = runTest {
        val renderer = ControlledRenderer(); val scheduler = Harness(renderer,this); val first = Owned(request(1))
        scheduler.schedule(request(1)); runCurrent(); renderer.finish(1,first.result); runCurrent()
        scheduler.schedule(request(2)); runCurrent(); renderer.gates[2]!!.completeExceptionally(IllegalStateException("private/path/never-publish")); runCurrent()
        assertSame(first.result,scheduler.preview); assertEquals(RenderStatus.PROCESSING_FAILED,scheduler.field<RenderStatus>("Failure")); assertFalse(scheduler.state.toString().contains("private/path")); scheduler.close(); first.assertClosed()
    }
}
