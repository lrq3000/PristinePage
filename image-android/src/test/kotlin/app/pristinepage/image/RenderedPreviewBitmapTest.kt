package app.pristinepage.image

import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.image.decode.ImageAllocationBudget
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.*
import org.junit.Test

/** Real result/RGB/budget consumers. These tests stop before native Bitmap allocation; Android pixels, delivered draw references and late delivery cleanup require the parent's native consuming tests. */
@OptIn(ExperimentalCoroutinesApi::class)
class RenderedPreviewBitmapTest {
    @Test fun nonOkResultsCannotBecomeDisplaySuccessOrConsumeTheBorrowedInput() = runBlocking<Unit> {
        for (status in RenderStatus.entries.filter { it != RenderStatus.OK }) {
            Fixture(status = status).use { f ->
                assertEquals(OperationErrorCode.INVALID_INPUT, error { f.convert() }.code)
                f.assertBorrowedInputOpen()
            }
        }
    }

    @Test fun cardDetailAndExportAreNotMislabeledAsSelectedPreview() = runBlocking<Unit> {
        for (purpose in listOf(RenderPurpose.CARD, RenderPurpose.DETAIL_CROP, RenderPurpose.EXPORT)) {
            Fixture(purpose = purpose).use { f ->
                assertEquals(OperationErrorCode.INVALID_INPUT, error { f.convert() }.code)
                f.assertBorrowedInputOpen()
            }
        }
    }

    @Test fun actualWidthMustMatchTheRequestedOutputBeforeAllocation() = runBlocking<Unit> {
        Fixture(actualSize = PixelSize(3, 2)).use { f ->
            assertEquals(OperationErrorCode.INVALID_INPUT, error { f.convert() }.code)
            f.assertBorrowedInputOpen()
        }
    }

    @Test fun actualHeightMustMatchTheRequestedOutputBeforeAllocation() = runBlocking<Unit> {
        Fixture(actualSize = PixelSize(2, 3)).use { f ->
            assertEquals(OperationErrorCode.INVALID_INPUT, error { f.convert() }.code)
            f.assertBorrowedInputOpen()
        }
    }

    @Test fun matchingOverwideRasterRefusesBeforeAllocation() = runBlocking<Unit> {
        Fixture(size = PixelSize(1025, 1)).use { f ->
            assertEquals(OperationErrorCode.INVALID_INPUT, error { f.convert() }.code)
            f.assertBorrowedInputOpen()
        }
    }

    @Test fun matchingOvertallRasterRefusesBeforeAllocation() = runBlocking<Unit> {
        Fixture(size = PixelSize(1, 1025)).use { f ->
            assertEquals(OperationErrorCode.INVALID_INPUT, error { f.convert() }.code)
            f.assertBorrowedInputOpen()
        }
    }

    @Test fun enormousRequestedExtentRefusesWithoutAttemptingAnEnormousRaster() = runBlocking<Unit> {
        Fixture(size = PixelSize(Int.MAX_VALUE, Int.MAX_VALUE), actualSize = PixelSize(2, 2)).use { f ->
            assertEquals(OperationErrorCode.INVALID_INPUT, error { f.convert() }.code)
            f.assertBorrowedInputOpen()
        }
    }

    @Test fun bitmapDrawCopyAndRowAreAdmittedTogetherWhileWholeInputRemainsCharged() = runBlocking<Unit> {
        // Four pixels require bitmap16 + provisional draw16 + row8 =40 bytes. The caller-owned input retains24, leaving39 of63: even this tiny conversion must refuse before native allocation.
        Fixture(capacity = 63).use { f ->
            val failure = error { f.convert() }
            assertEquals(OperationErrorCode.RESOURCE_LIMIT, failure.code)
            assertEquals("image_allocation_limit", failure.safeMessageKey)
            f.assertBorrowedInputOpen()
        }
    }

    @Test fun competingOwnerIsNotReleasedToMakeRoomForDisplayConversion() = runBlocking<Unit> {
        Fixture(capacity = 100).use { f ->
            f.budget.reserve(37).use {
                assertEquals(OperationErrorCode.RESOURCE_LIMIT, error { f.convert() }.code)
                assertEquals(61L, f.budget.usedBytes)
                assertEquals(0, f.inputCloses)
                assertEquals(128, f.result.image!!.getUnsigned(0, 0, 0))
            }
            f.assertBorrowedInputOpen()
        }
    }

    @Test fun alreadyCanceledCallerPropagatesCancellationWithoutClosingWholeInput() = runBlocking<Unit> {
        Fixture().use { f ->
            val loader = f.loader()
            var canceled = false
            launch {
                coroutineContext.cancel()
                try { f.convert(loader).close(); fail("Canceled conversion was delivered") }
                catch (_: CancellationException) { canceled = true }
            }.join()
            assertTrue(canceled)
            f.assertBorrowedInputOpen()
        }
    }

    @Test fun cancellationWhileQueuedOnProcessingDispatcherCannotAdmitOrCloseInput() = runBlocking<Unit> {
        Fixture().use { f ->
            val scheduler = TestCoroutineScheduler()
            val loader = f.loader(StandardTestDispatcher(scheduler))
            var canceled = false
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                try { f.convert(loader).close(); fail("Queued canceled conversion was delivered") }
                catch (_: CancellationException) { canceled = true }
            }
            assertTrue(job.isActive)
            job.cancel()
            scheduler.runCurrent()
            job.join()
            assertTrue(canceled)
            f.assertBorrowedInputOpen()
        }
    }

    private class Fixture(
        size: PixelSize = PixelSize(2, 2),
        actualSize: PixelSize = size,
        purpose: RenderPurpose = RenderPurpose.PREVIEW,
        status: RenderStatus = RenderStatus.OK,
        capacity: Long = 1024 * 1024,
    ) : AutoCloseable {
        val budget = ImageAllocationBudget(capacity)
        var inputCloses = 0
        private val inputPermit = budget.reserve(24)
        private val request = RenderRequest("source", "b".repeat(64), "page", 7,
            Recipe(geometry = GeometryRecipe.None(SourceBinding("b".repeat(64), PixelSize(2, 2), "fixture-v1"))),
            OutputSpec(size, purpose), 9, null, FallbackPolicy.FAIL)
        // Literal unsigned color vectors plus padding sentinels, reusable for the parent's Android pixel checks. The final row has no trailing padding.
        private val bytes = if (actualSize == PixelSize(2, 2)) intArrayOf(128, 1, 255, 2, 254, 3, 85, 102, 4, 5, 6, 255, 0, 128).map(Int::toByte).toByteArray() else ByteArray(actualSize.width * actualSize.height * 3)
        val result = RenderResult(request, status,
            if (status in listOf(RenderStatus.OK, RenderStatus.DEGRADED)) RgbImage(actualSize.width, actualSize.height, if (actualSize == PixelSize(2, 2)) 8 else actualSize.width * 3, bytes) else null,
            emptyList(), emptyList(), AutoCloseable { inputCloses++; inputPermit.close() })

        fun loader(dispatcher: CoroutineDispatcher? = null): Any {
            val type = try { Class.forName("app.pristinepage.image.preview.RenderedPreviewBitmapLoader") }
            catch (_: ClassNotFoundException) { throw AssertionError("Budgeted rendered-preview bitmap bridge is absent") }
            return if (dispatcher == null) type.getConstructor(ImageAllocationBudget::class.java).newInstance(budget)
            else type.getConstructor(ImageAllocationBudget::class.java, CoroutineDispatcher::class.java).newInstance(budget, dispatcher)
        }

        suspend fun convert(loader: Any = loader()): AutoCloseable = suspendCoroutineUninterceptedOrReturn { continuation: Continuation<AutoCloseable> ->
            try { loader.javaClass.getMethod("convert", RenderResult::class.java, Continuation::class.java).invoke(loader, result, continuation) }
            catch (failure: InvocationTargetException) { throw failure.targetException }
        }

        fun assertBorrowedInputOpen() {
            assertEquals(0, inputCloses)
            assertEquals(24L, budget.usedBytes)
            result.image?.let { image ->
                if (image.width == 2 && image.height == 2) assertArrayEquals(intArrayOf(128, 1, 255, 2, 254, 3, 4, 5, 6, 255, 0, 128).map(Int::toByte).toByteArray(), image.toPackedByteArray())
                else assertEquals(0, image.getUnsigned(0, 0, 0))
            }
        }

        override fun close() {
            result.close(); result.close()
            assertEquals(1, inputCloses)
            assertEquals(0L, budget.usedBytes)
        }
    }

    private companion object {
        suspend fun error(action: suspend () -> AutoCloseable): OperationError {
            try { action().close(); throw AssertionError("Unsafe rendered preview was accepted") }
            catch (failure: RepositoryFailure) { return failure.error }
        }
    }
}
