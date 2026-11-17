package app.pristinepage.image

import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.*
import app.pristinepage.data.db.ExportPageRow
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Snapshot values, canonical recipes and ownership are real; slow source IO/rendering are controlled only at their existing ports. No current-page repository is supplied or consulted. */
@OptIn(ExperimentalCoroutinesApi::class)
class FrozenPageRendererTest {
    private class Fixture {
        val source = SourceId(UUID.randomUUID().toString()); val blob = SourceBlobId(UUID.randomUUID().toString()); val page = PageId(UUID.randomUUID().toString())
        val binding = SourceBinding("b".repeat(64),PixelSize(3,2),"fixture-srgb-v1")
        val recipe = Recipe(geometry = GeometryRecipe.None(binding,outputQuarterTurns = 1))
        val providers = "{\"backend\":\"cpu-reference\",\"encodingVersion\":\"lossless-rgb-v1\",\"precision\":\"RGB8\",\"providers\":[],\"version\":1}".toByteArray()
        val emptyAssets = MessageDigest.getInstance("SHA-256").digest("pristinepage-export-assets-v1\u0000\u0000\u0000\u0000\u0000".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        val row = ExportPageRow(UUID.randomUUID().toString(),0,page.value,source.value,blob.value,"a".repeat(64),binding.sourcePixelHash,3,2,binding.orientationColorConversionVersion,7,1,RecipeCodec.encode(recipe),RecipeCodec.hash(recipe),"[]".toByteArray(),providers,emptyAssets,"classical-v1",2,3,2,3,"FAIL",null,null)
        var committed = CommittedSource(source,blob,"a".repeat(64),746,SourceInspection("image/png",PixelSize(3,2),PixelSize(3,2),1,null,binding.orientationColorConversionVersion,binding.sourcePixelHash),File("unused-private-source.bin"))
        var loads = 0; var renders = 0; var closes = 0
        var beforeLoad: () -> Unit = {}
        var request: RenderRequest? = null
        var output: (RenderRequest) -> RenderResult = { r -> RenderResult(r,RenderStatus.OK,RgbImage(2,3,6,ByteArray(18) { it.toByte() }),emptyList(),emptyList(),AutoCloseable { closes++ }) }
        fun snapshot(row: ExportPageRow = this.row): ExportPageSnapshot = ExportPageSnapshot::class.java.getConstructor(ExportPageRow::class.java).newInstance(row)
        fun consumer(dispatcher: CoroutineDispatcher): Any {
            val kind = try { Class.forName("app.pristinepage.image.export.FrozenPageRenderer") } catch (_: ClassNotFoundException) { throw AssertionError("Frozen export page consumer is absent") }
            val sources = CommittedSources { id -> assertEquals(source,id); loads++; beforeLoad(); committed }
            val renderer = object : RenderCoordinator { override suspend fun render(request: RenderRequest): RenderResult { renders++; this@Fixture.request = request; return output(request) } }
            return kind.getConstructor(CommittedSources::class.java,RenderCoordinator::class.java,String::class.java,String::class.java,String::class.java,String::class.java,CoroutineDispatcher::class.java).newInstance(sources,renderer,"classical-v1","lossless-rgb-v1","cpu-reference","RGB8",dispatcher)
        }
    }
    private suspend fun render(consumer: Any, page: ExportPageSnapshot): RenderResult = suspendCoroutineUninterceptedOrReturn { continuation ->
        try { consumer.javaClass.getMethod("render",ExportPageSnapshot::class.java,Continuation::class.java).invoke(consumer,page,continuation) }
        catch (error: InvocationTargetException) { throw error.targetException }
    }
    private suspend fun rejected(code: OperationErrorCode, action: suspend () -> Unit) {
        try { action(); fail("Expected controlled export refusal") } catch (failure: RepositoryFailure) { assertEquals(code,failure.error.code) }
    }

    @Test fun frozenRevisionRecipeAndSizeDriveTheSameRendererAndTheWholeOwnerTransfers() = runTest {
        val f = Fixture(); val consumer = f.consumer(UnconfinedTestDispatcher(testScheduler)); val page = f.snapshot()
        // A later mutable row with a different revision/rotation never enters this immutable page value.
        f.row.recipe.fill(0)
        val result = render(consumer,page)
        assertEquals(RenderRequest(f.source.value,"b".repeat(64),f.page.value,7,f.recipe,OutputSpec(PixelSize(2,3),RenderPurpose.EXPORT),0,null,FallbackPolicy.FAIL),f.request)
        assertArrayEquals(ByteArray(18) { it.toByte() },result.image!!.toPackedByteArray()); assertEquals(0,f.closes)
        result.close(); result.close(); assertEquals(1,f.closes); assertEquals(1,f.loads); assertEquals(1,f.renders)
    }

    @Test fun changedEncodedSourceIdentityRefusesBeforeRenderEvenWhenNormalizedPixelsMatch() = runTest {
        val f = Fixture(); val old = f.committed
        f.committed = CommittedSource(old.sourceId,old.sourceBlobId,"c".repeat(64),old.byteCount,old.inspection,old.file)
        rejected(OperationErrorCode.INTEGRITY_FAILED) { render(f.consumer(UnconfinedTestDispatcher(testScheduler)),f.snapshot()) }
        assertEquals(0,f.renders)
    }

    @Test fun frozenSourceBlobConversionSizeAndPixelBindingsAreIndependentlyChecked() = runTest {
        for (field in 0..3) {
            val f = Fixture(); val old = f.committed
            val info = when (field) { 1 -> old.inspection.copy(conversionFingerprint = "changed"); 2 -> old.inspection.copy(encodedSize = PixelSize(2,3),normalizedSize = PixelSize(2,3)); 3 -> old.inspection.copy(sourcePixelHash = "c".repeat(64)); else -> old.inspection }
            f.committed = CommittedSource(old.sourceId,if (field == 0) SourceBlobId(UUID.randomUUID().toString()) else old.sourceBlobId,old.sourceByteHash,old.byteCount,info,old.file)
            rejected(OperationErrorCode.INTEGRITY_FAILED) { render(f.consumer(UnconfinedTestDispatcher(testScheduler)),f.snapshot()) }
            assertEquals(0,f.renders)
        }
    }

    @Test fun unsupportedMasksProvidersVersionsAndFallbacksNeverReachSourceOrRenderer() = runTest {
        val f = Fixture(); val consumer = f.consumer(UnconfinedTestDispatcher(testScheduler))
        for (row in listOf(f.row.copy(masks = "[{}]".toByteArray()),f.row.copy(providers = "{}".toByteArray()),f.row.copy(rendererVersion = "future"),f.row.copy(fallbackPolicy = "KEEP_STAGE_INPUT"))) {
            rejected(OperationErrorCode.PROVIDER_UNAVAILABLE) { render(consumer,f.snapshot(row)) }
        }
        assertEquals(0,f.loads); assertEquals(0,f.renders)
    }

    @Test fun contradictoryCanonicalRecipeAndOutputRefuseBeforeSourceIO() = runTest {
        val f = Fixture(); val consumer = f.consumer(UnconfinedTestDispatcher(testScheduler))
        for (row in listOf(f.row.copy(recipeSha256 = "c".repeat(64)),f.row.copy(pixelSha256 = "c".repeat(64)),f.row.copy(outputWidth = 3,outputHeight = 2))) {
            rejected(OperationErrorCode.INTEGRITY_FAILED) { render(consumer,f.snapshot(row)) }
        }
        assertEquals(0,f.loads); assertEquals(0,f.renders)
    }

    @Test fun wrongRequestOrWrongSizedSuccessClosesTheEntireResultInsteadOfEncodingIt() = runTest {
        for (wrongSize in listOf(false,true)) {
            val f = Fixture()
            f.output = { r -> RenderResult(if (wrongSize) r else r.copy(recipeRevision = 8),RenderStatus.OK,RgbImage(3,2,9,ByteArray(18)),emptyList(),emptyList(),AutoCloseable { f.closes++ }) }
            rejected(OperationErrorCode.INTEGRITY_FAILED) { render(f.consumer(UnconfinedTestDispatcher(testScheduler)),f.snapshot()) }
            assertEquals(1,f.closes)
        }
    }

    @Test fun degradedAndFailedRendersNeverBecomeOrdinaryExportSuccessAndAlwaysClose() = runTest {
        for ((status,code) in listOf(RenderStatus.DEGRADED to OperationErrorCode.PROCESSING_FAILED,RenderStatus.RESOURCE_LIMIT to OperationErrorCode.RESOURCE_LIMIT,RenderStatus.CANCELED to OperationErrorCode.CANCELED,RenderStatus.STORAGE_FAILED to OperationErrorCode.STORAGE_FAILED,RenderStatus.INVALID_MAP to OperationErrorCode.INVALID_MAP)) {
            val f = Fixture(); f.output = { r -> RenderResult(r,status,if (status == RenderStatus.DEGRADED) RgbImage(2,3,6,ByteArray(18)) else null,emptyList(),emptyList(),AutoCloseable { f.closes++ }) }
            rejected(code) { render(f.consumer(UnconfinedTestDispatcher(testScheduler)),f.snapshot()) }; assertEquals(1,f.closes)
        }
    }

    @Test fun cancellationAfterPrivateRenderClosesUndeliveredOutput() = runTest {
        val f = Fixture(); val parent = Job(); val scope = CoroutineScope(parent + UnconfinedTestDispatcher(testScheduler))
        f.output = { r -> RenderResult(r,RenderStatus.OK,RgbImage(2,3,6,ByteArray(18)),emptyList(),emptyList(),AutoCloseable { f.closes++ }).also { parent.cancel() } }
        val task = scope.async { render(f.consumer(UnconfinedTestDispatcher(testScheduler)),f.snapshot()) }
        try { task.await(); fail("Canceled export must not deliver output") } catch (_: CancellationException) { }
        assertEquals(1,f.closes)
    }

    @Test fun cleanupFailureDoesNotReplaceTheSafeOriginalIdentityRefusal() = runTest {
        val f = Fixture(); f.output = { r -> RenderResult(r.copy(recipeRevision = 8),RenderStatus.OK,RgbImage(2,3,6,ByteArray(18)),emptyList(),emptyList(),AutoCloseable { f.closes++; throw IllegalStateException("private-path-must-not-escape") }) }
        rejected(OperationErrorCode.INTEGRITY_FAILED) { render(f.consumer(UnconfinedTestDispatcher(testScheduler)),f.snapshot()) }
        assertEquals(1,f.closes)
    }

    @Test fun unclassifiedSourceReadFailureIsStorageNotAnAlgorithmFailure() = runTest {
        val f = Fixture(); f.beforeLoad = { throw java.io.IOException("private-path-must-not-escape") }
        rejected(OperationErrorCode.STORAGE_FAILED) { render(f.consumer(UnconfinedTestDispatcher(testScheduler)),f.snapshot()) }
        assertEquals(0,f.renders)
    }
}
