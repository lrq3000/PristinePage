package app.pristinepage

import android.graphics.Bitmap
import android.graphics.ColorSpace
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.data.model.*
import app.pristinepage.image.decode.ImageAllocationBudget
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android bitmap conversion of a complete owned result, not rendering/source proof. Application/editor consumers separately own those boundaries; the RGB fixture is tiny and independent of production packing. */
@RunWith(AndroidJUnit4::class)
class RenderedPreviewBitmapIntegrationTest {
    private val size = PixelSize(3,2)
    private val binding = SourceBinding("a".repeat(64),size,"fixture-srgb-v1")
    private val request = RenderRequest("00000000-0000-0000-0000-000000000001",binding.sourcePixelHash,"00000000-0000-0000-0000-000000000002",7,Recipe(geometry = GeometryRecipe.None(binding)),OutputSpec(size,RenderPurpose.PREVIEW),9,null,FallbackPolicy.FAIL)

    @Test fun renderedRgbBecomesOpaqueSrgbWithExactOrderAndADrawSafeClosedOwner() = runBlocking {
        val budget = ImageAllocationBudget(1024); var sourceReleases = 0
        val bytes = byteArrayOf(-1,0,0,0,-1,0,0,0,-1,99,99,-1,-1,-1,0,0,0,1,2,3,99,99)
        val sourcePermit = budget.reserve(22)
        val result = RenderResult(request,RenderStatus.OK,RgbImage(3,2,11,bytes),emptyList(),emptyList(),AutoCloseable { sourcePermit.close(); sourceReleases++ })
        try {
            val owner = convert(budget,result)
            assertEquals(request,displayType().getMethod("getRequest").invoke(owner))
            val pixels = bitmap(owner); assertEquals(3,pixels.width); assertEquals(2,pixels.height); assertEquals(Bitmap.Config.ARGB_8888,pixels.config)
            assertEquals(ColorSpace.get(ColorSpace.Named.SRGB),pixels.colorSpace); assertEquals(Bitmap.DENSITY_NONE,pixels.density)
            val actual = IntArray(6); pixels.getPixels(actual,0,3,0,0,3,2)
            assertArrayEquals(intArrayOf(0xffff0000.toInt(),0xff00ff00.toInt(),0xff0000ff.toInt(),0xffffffff.toInt(),0xff000000.toInt(),0xff010203.toInt()),actual)
            assertEquals(82L,budget.usedBytes); assertEquals(0,sourceReleases)
            (owner as AutoCloseable).close(); owner.close(); assertEquals(22L,budget.usedBytes); assertFalse(pixels.isRecycled)
            assertEquals(0xff010203.toInt(),pixels.getPixel(2,1)); assertEquals(255,result.image!!.getUnsigned(0,0,0))
            try { bitmap(owner); fail("Closed display still lends new draw references") } catch (error: InvocationTargetException) { assertTrue(error.targetException is IllegalStateException) }
        } finally { result.close() }
        assertEquals(0L,budget.usedBytes); assertEquals(1,sourceReleases)
    }

    @Test fun insufficientDisplayBudgetRefusesWithoutTakingOrClosingTheInputResult() = runBlocking {
        val budget = ImageAllocationBudget(59); var sourceReleases = 0
        val result = RenderResult(request,RenderStatus.OK,RgbImage(3,2,9,ByteArray(18)),emptyList(),emptyList(),AutoCloseable { sourceReleases++ })
        try {
            try { convert(budget,result); fail("Display silently bypassed its60byte reservation") } catch (error: RepositoryFailure) { assertEquals(OperationErrorCode.RESOURCE_LIMIT,error.error.code) }
            assertEquals(0L,budget.usedBytes); assertEquals(0,sourceReleases); assertEquals(0,result.image!!.getUnsigned(0,0,0))
        } finally { result.close() }
        assertEquals(1,sourceReleases)
    }

    private suspend fun convert(budget: ImageAllocationBudget, result: RenderResult): Any {
        val loader = try { Class.forName("app.pristinepage.image.preview.RenderedPreviewBitmapLoader").getConstructor(ImageAllocationBudget::class.java).newInstance(budget) } catch (_: ClassNotFoundException) { throw AssertionError("Rendered preview bitmap conversion is absent") }
        return suspendCoroutineUninterceptedOrReturn { continuation ->
            try { loader.javaClass.getMethod("convert",RenderResult::class.java,Continuation::class.java).invoke(loader,result,continuation) } catch (error: InvocationTargetException) { throw error.targetException }
        }
    }
    private fun displayType() = Class.forName("app.pristinepage.image.preview.RenderedPreviewBitmap")
    private fun bitmap(owner: Any) = displayType().getMethod("getBitmap").invoke(owner) as Bitmap
}
