package app.pristinepage

import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.data.model.*
import java.lang.reflect.Proxy
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Control only asynchronous native conversion; actual RenderResult/RGB ownership and scheduler transitions remain real. Native bitmap/sRGB/draw references require their separate Android consumer. */
@OptIn(ExperimentalCoroutinesApi::class)
class PreviewDisplayGenerationTest {
    private val source = SourceBinding("a".repeat(64),PixelSize(2,1),"fixture-srgb-v1")
    private fun request(generation: Long) = RenderRequest("00000000-0000-0000-0000-000000000001",source.sourcePixelHash,"00000000-0000-0000-0000-000000000002",1,Recipe(geometry = GeometryRecipe.None(source)),OutputSpec(PixelSize(2,1),RenderPurpose.PREVIEW),generation,null,FallbackPolicy.FAIL)
    private class Owned(request: RenderRequest) {
        var releases = 0
        val result = RenderResult(request,RenderStatus.OK,RgbImage(2,1,6,byteArrayOf(1,2,3,4,5,6)),emptyList(),emptyList(),AutoCloseable { releases++ })
    }
    private inner class Harness(scope: CoroutineScope) : AutoCloseable {
        val renders = linkedMapOf<Long,Owned>()
        val gates = linkedMapOf<Long,CompletableDeferred<Any>>()
        val displayReleases = mutableMapOf<Long,Int>()
        private val converterType = type("app.pristinepage.image.preview.RenderedPreviewBitmaps")
        private val displayType = type("app.pristinepage.image.preview.RenderedPreviewBitmap")
        private val converter = Proxy.newProxyInstance(converterType.classLoader,arrayOf(converterType)) { _, method, args ->
            check(method.name=="convert"); val render = args!![0] as RenderResult
            val gate = CompletableDeferred<Any>().also { gates[render.request.generation] = it }
            @Suppress("UNCHECKED_CAST")
            val continuation = args[1] as kotlin.coroutines.Continuation<Any?>
            val body: suspend () -> Any? = { withContext(NonCancellable) { gate.await() } }
            body.startCoroutineUninterceptedOrReturn(continuation)
        }
        private val scheduler: Any = try {
            val renderer = object : RenderCoordinator {
                override suspend fun render(request: RenderRequest) = Owned(request).also { renders[request.generation] = it }.result
            }
            type("app.pristinepage.editor.PreviewScheduler").getConstructor(RenderCoordinator::class.java,CoroutineScope::class.java,converterType).newInstance(renderer,scope,converter)
        } catch (_: NoSuchMethodException) { throw AssertionError("Prepublication preview display conversion is absent") }
        fun schedule(generation: Long) { scheduler.javaClass.getMethod("schedule",RenderRequest::class.java).invoke(scheduler,request(generation)) }
        @Suppress("UNCHECKED_CAST") fun <T> field(name: String): T = (scheduler.javaClass.getMethod("getState").invoke(scheduler) as StateFlow<*>).value!!.let { it.javaClass.getMethod("get$name").invoke(it) as T }
        fun finish(generation: Long, returned: RenderRequest = request(generation)) {
            val owner = Proxy.newProxyInstance(displayType.classLoader,arrayOf(displayType)) { _, method, _ ->
                when (method.name) { "getRequest" -> returned; "close" -> { displayReleases[generation] = displayReleases.getOrDefault(generation,0)+1; Unit }; else -> error("No Android pixel getter belongs to this state-only test") }
            }
            gates.getValue(generation).complete(owner)
        }
        override fun close() { (scheduler as AutoCloseable).close() }
    }

    @Test fun conversionFinishingAfterANewerRequestCannotPublishOrCloseTheNewDisplay() = runTest {
        val s = Harness(this); s.schedule(8); runCurrent(); assertEquals(0,s.renders.getValue(8).releases)
        s.schedule(9); runCurrent(); s.finish(9); runCurrent(); s.finish(8); runCurrent()
        assertEquals(request(9),s.field<RenderResult>("Preview").request); assertNotNull(s.field<Any?>("Display")); assertEquals(1,s.displayReleases[8]); assertEquals(1,s.renders.getValue(8).releases)
        assertNull(s.displayReleases[9]); s.close(); assertEquals(1,s.displayReleases[9]); assertEquals(1,s.renders.getValue(9).releases)
    }

    @Test fun conversionResourceFailureKeepsOnlyTheClearlyPreviousCompletedPair() = runTest {
        val s = Harness(this); s.schedule(1); runCurrent(); s.finish(1); runCurrent(); val previous = s.field<Any>("Display")
        s.schedule(2); runCurrent(); s.gates.getValue(2).completeExceptionally(RepositoryFailure(OperationError(OperationErrorCode.RESOURCE_LIMIT,true,"display_budget_unavailable",null))); runCurrent()
        assertSame(previous,s.field<Any>("Display")); assertTrue(s.field("Previous")); assertEquals(RenderStatus.RESOURCE_LIMIT,s.field<RenderStatus>("Failure"))
        assertEquals(1,s.renders.getValue(2).releases); assertNull(s.displayReleases[1]); s.close(); assertEquals(1,s.displayReleases[1])
    }

    @Test fun terminalCloseDuringConversionReleasesBothLateOwnersWithoutPublication() = runTest {
        val s = Harness(this); s.schedule(1); runCurrent(); s.close(); s.finish(1); runCurrent()
        assertNull(s.field<Any?>("Display")); assertNull(s.field<RenderResult?>("Preview")); assertEquals(1,s.renders.getValue(1).releases); assertEquals(1,s.displayReleases[1])
    }

    @Test fun wrongDisplayRequestCannotMasqueradeAsTheCurrentlySelectedRecipe() = runTest {
        val s = Harness(this); s.schedule(1); runCurrent(); s.finish(1,request(2)); runCurrent()
        assertNull(s.field<Any?>("Display")); assertNull(s.field<RenderResult?>("Preview")); assertEquals(RenderStatus.PROCESSING_FAILED,s.field<RenderStatus>("Failure")); assertEquals(1,s.displayReleases[1]); assertEquals(1,s.renders.getValue(1).releases); s.close()
    }

    @Test fun displayAllocationOomKeepsThePreviousPairAndClosesTheNewCompleteResult() = runTest {
        val s = Harness(this); s.schedule(1); runCurrent(); s.finish(1); runCurrent(); val previous = s.field<Any>("Display")
        s.schedule(2); runCurrent(); s.gates.getValue(2).completeExceptionally(OutOfMemoryError("synthetic display allocation")); runCurrent()
        assertEquals(RenderStatus.RESOURCE_LIMIT,s.field<RenderStatus?>("Failure")); assertSame(previous,s.field<Any>("Display")); assertTrue(s.field("Previous"))
        assertEquals(1,s.renders.getValue(2).releases); assertNull(s.displayReleases[1]); s.close(); assertEquals(1,s.displayReleases[1]); assertEquals(1,s.renders.getValue(1).releases)
    }

    private companion object {
        fun type(name: String): Class<*> = try { Class.forName(name) } catch (_: ClassNotFoundException) { throw AssertionError("Prepublication preview display conversion is absent") }
    }
}
