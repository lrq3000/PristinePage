package app.pristinepage

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.*
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.SourceInput
import app.pristinepage.image.render.AppearanceEngine
import app.pristinepage.image.render.DefaultRenderCoordinator
import app.pristinepage.image.render.GeometryEngine
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room/private source/strict Android codec/geometry/appearance composition. Only external provider delivery is synthetic; roots/originals/journals are retained, not deleted. No picker, grants, UI, saved edit or export claim. */
@RunWith(AndroidJUnit4::class)
class RenderCoordinatorIntegrationTest {
    @Test fun packagedPurposePolicyRejectsBeforeSourceReadAndPreservesExplicitFullContext() = runBlocking {
        Fixture(1025, 1, IntArray(1025) { gray(80) }).use { f ->
            f.ingest(); f.forbidProvider = true
            val recipe = Recipe(geometry = GeometryRecipe.None(f.binding))
            val request = f.request(recipe, PixelSize(1025,1)); val coordinator = f.coordinator()
            // A nonexistent source makes validation ordering observable: known-invalid purpose must win over a later source-storage failure.
            val absent = request.copy(sourceId = UUID.randomUUID().toString())
            val rotated = Recipe(geometry = GeometryRecipe.None(f.binding, outputQuarterTurns = 1))
            for (invalid in listOf(absent.copy(output = OutputSpec(PixelSize(225,1), RenderPurpose.CARD)), absent, absent.copy(recipe = rotated, output = OutputSpec(PixelSize(1,1025), RenderPurpose.PREVIEW)))) {
                coordinator.render(invalid).use { assertEquals(RenderStatus.INVALID_INPUT, it.status); assertNull(it.image) }
                assertEquals(0L, f.budget.usedBytes)
            }
            for ((size, purpose) in listOf(PixelSize(224,1) to RenderPurpose.CARD, PixelSize(1024,1) to RenderPurpose.PREVIEW, PixelSize(1025,1) to RenderPurpose.DETAIL_CROP, PixelSize(1025,1) to RenderPurpose.EXPORT)) {
                coordinator.render(request.copy(output = OutputSpec(size, purpose))).use {
                    assertEquals(RenderStatus.OK, it.status)
                    val image = checkNotNull(it.image)
                    assertEquals(size.width, image.width); assertEquals(size.height, image.height); assertEquals(80, image.getUnsigned(0,0,0))
                }
                assertEquals(0L, f.budget.usedBytes)
            }
            assertEquals(1, f.providerReads); assertArrayEquals(f.png, f.original())
        }
    }

    @Test fun realPrivateSourceRendersThenReopensWithoutProviderAccessOrSourceMutation() = runBlocking {
        Fixture(4, 1, intArrayOf(gray(200), gray(200), gray(200), gray(195))).use { f ->
            f.ingest()
            val recipe = Recipe(geometry = GeometryRecipe.None(f.binding), appearance = AppearanceRecipe.PaperGainV1(strength = .5))
            val request = f.request(recipe, PixelSize(4, 1))
            f.forbidProvider = true
            f.coordinator().render(request).use { result ->
                assertEquals(RenderStatus.OK, result.status)
                assertArrayEquals(neutrals(223,223,223,217), result.image!!.toPackedByteArray())
                assertEquals(listOf("source_decode_v1", "geometry_none_v1", "paper_gain_v1"), result.stages.map { it.stageId })
                assertTrue(f.budget.usedBytes > 0)
            }
            assertEquals(0L, f.budget.usedBytes); assertArrayEquals(f.png, f.original())
            f.reopen()
            f.coordinator().render(request.copy(generation = 2)).use { assertEquals(RenderStatus.OK, it.status); assertArrayEquals(neutrals(223,223,223,217), it.image!!.toPackedByteArray()) }
            assertEquals(1, f.providerReads); assertArrayEquals(f.png, f.original()); assertEquals(0L, f.budget.usedBytes)
        }
    }

    @Test fun decodedAsymmetricManualMapUsesActualArcLutsThroughTheWholePipeline() = runBlocking {
        Fixture(101, 101, IntArray(10201) { n -> (0xff shl 24) or ((n % 101) shl 16) or ((n / 101) shl 8) or 37 }).use { f ->
            f.ingest(); f.forbidProvider = true
            val full = QuadPx(PointPx(0.0,0.0), PointPx(1.0,0.0), PointPx(1.0,1.0), PointPx(0.0,1.0))
            val manual = GeometryRecipe.ManualCurve(f.binding, full, PointPx(.3,.2), PointPx(.6,.85), .4, outputQuarterTurns = 1)
            f.coordinator().render(f.request(Recipe(geometry = manual), PixelSize(21,21))).use { result ->
                assertEquals(RenderStatus.OK, result.status)
                // Independent128-chord oracle: base(.25,.4)≈(21.86016271,54.52450904). Raw quadratic parameter u would give(21.16,51.58), a different byte result.
                val image = checkNotNull(result.image)
                assertEquals(22, image.getUnsigned(12, 5, 0)); assertEquals(55, image.getUnsigned(12, 5, 1)); assertEquals(37, image.getUnsigned(12, 5, 2))
                assertTrue(result.stages.single { it.stageId == "manual_ruled_v1" }.warningCodes.contains("MANUAL_COMPOSITE_V1"))
            }
            assertEquals(0L, f.budget.usedBytes); assertEquals(1, f.providerReads); assertArrayEquals(f.png, f.original())
        }
    }

    @Test fun realDecodeSharesFinalOwnerCapacityAndSuccessfulKeepPolicyRemainsOk() = runBlocking {
        Fixture(3, 1, intArrayOf(gray(0),gray(10),gray(0))).use { f ->
            f.ingest(); f.forbidProvider = true
            val recipe = Recipe(geometry = GeometryRecipe.None(f.binding), denoise = DenoiseRecipe.BilateralLumaV1(), binary = BinaryRecipe.SauvolaV1())
            val request = f.request(recipe, PixelSize(3,1)); val coordinator = f.coordinator()
            coordinator.render(request).use { first ->
                val image = checkNotNull(first.image)
                assertEquals(RenderStatus.OK, first.status); assertArrayEquals(neutrals(255,255,255), image.toPackedByteArray())
                val owned = f.budget.usedBytes
                f.budget.reserve(f.budget.capacityBytes - owned).use {
                    coordinator.render(request.copy(generation = 2)).use { assertEquals(RenderStatus.RESOURCE_LIMIT, it.status); assertNull(it.image) }
                    assertEquals(f.budget.capacityBytes, f.budget.usedBytes); assertArrayEquals(neutrals(255,255,255), image.toPackedByteArray())
                }
                assertEquals(owned, f.budget.usedBytes)
            }
            coordinator.render(request.copy(fallback = FallbackPolicy.KEEP_STAGE_INPUT)).use { assertEquals(RenderStatus.OK, it.status); assertArrayEquals(neutrals(255,255,255), it.image!!.toPackedByteArray()) }
            assertEquals(0L, f.budget.usedBytes); assertArrayEquals(f.png, f.original())
        }
    }

    @Test fun realManualFailureKeepsUnrotatedFullDecodedSourceAndOmittedStageMetadata() = runBlocking {
        Fixture(101,101,IntArray(10201) { n -> (0xff shl 24) or ((n%101) shl 16) or ((n/101) shl 8) or 37 }).use { f ->
            f.ingest(); f.forbidProvider = true
            val full = QuadPx(PointPx(0.0,0.0),PointPx(1.0,0.0),PointPx(1.0,1.0),PointPx(0.0,1.0))
            val manual = GeometryRecipe.ManualCurve(f.binding,full,PointPx(.5,.9),PointPx(.5,.1),outputQuarterTurns = 1)
            val request = f.request(Recipe(geometry = manual, appearance = AppearanceRecipe.PaperGainV1(), binary = BinaryRecipe.SauvolaV1()),PixelSize(21,21)).copy(fallback = FallbackPolicy.KEEP_STAGE_INPUT)
            f.coordinator().render(request).use {
                assertEquals(RenderStatus.DEGRADED,it.status); val image = checkNotNull(it.image)
                assertEquals(101,image.width); assertEquals(101,image.height)
                assertEquals(12,image.getUnsigned(12,27,0)); assertEquals(27,image.getUnsigned(12,27,1)); assertEquals(37,image.getUnsigned(12,27,2))
                assertTrue(it.warnings.contains("OUTPUT_ROTATION_OMITTED_1")); assertTrue(f.budget.usedBytes > 0)
                assertEquals(listOf("OK","FAILED_INVALID_MAP","SKIPPED_AFTER_STAGE_FAILURE","SKIPPED_AFTER_STAGE_FAILURE"),it.stages.map { s -> s.outcome })
            }
            assertEquals(0L,f.budget.usedBytes); assertArrayEquals(f.png,f.original())
        }
    }

    @Test fun realUnavailableAppearanceFallbackKeepsRotatedGeometryInsteadOfRawOrBinaryPixels() = runBlocking {
        Fixture(3,2,intArrayOf(gray(5),gray(15),gray(25),gray(35),gray(45),gray(55))).use { f ->
            f.ingest(); f.forbidProvider = true
            val missing = AppearanceRecipe.ProviderAppearanceV1("missing",1,"d".repeat(64),"test","e".repeat(64),0.0)
            val recipe = Recipe(geometry = GeometryRecipe.None(f.binding,outputQuarterTurns = 1),appearance = missing,binary = BinaryRecipe.SauvolaV1())
            f.coordinator().render(f.request(recipe,PixelSize(2,3)).copy(fallback = FallbackPolicy.KEEP_STAGE_INPUT)).use {
                assertEquals(RenderStatus.DEGRADED,it.status); assertArrayEquals(neutrals(35,5,45,15,55,25),it.image!!.toPackedByteArray())
                assertEquals(listOf("OK","EXACT_PERMUTATION","FAILED_PROVIDER_UNAVAILABLE","SKIPPED_AFTER_STAGE_FAILURE"),it.stages.map { s -> s.outcome })
            }
            assertEquals(0L,f.budget.usedBytes); assertArrayEquals(f.png,f.original())
        }
    }

    private class Fixture(width: Int, height: Int, colors: IntArray) : AutoCloseable {
        private val base = InstrumentationRegistry.getInstrumentation().targetContext
        private val root = File(base.filesDir, "render-coordinator-${UUID.randomUUID()}")
        private val files = File(root, "files").apply { check(mkdirs()) }
        private val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = files
            override fun getDatabasePath(name: String) = File(root, "databases/$name").also { it.parentFile!!.mkdirs() }
        }
        val budget = ApplicationImageBudget.create(64)
        val png = Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888).let { bitmap -> try { ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray() } finally { bitmap.recycle() } }
        var forbidProvider = false; var providerReads = 0
        private val provider = SourceInput { reference -> check(!forbidProvider); check(reference == "content://fixture.documents/render"); providerReads++; ByteArrayInputStream(png) }
        private var graph: ApplicationImports? = null
        private lateinit var imported: IngestResult.Committed
        lateinit var binding: SourceBinding
            private set
        suspend fun ingest() {
            val value = ApplicationServices(context, budget, provider).imports().also { graph = it }
            val document = value.store.documents.createDocument("Synthetic render composition")
            val session = value.store.imports.beginImport(document, 1, null)
            imported = value.photoImports.importProviderReferences(listOf("content://fixture.documents/render"), session.parentId).single() as IngestResult.Committed
            val info = value.store.committedSources.load(imported.sourceId).inspection
            binding = SourceBinding(info.sourcePixelHash, info.normalizedSize, info.conversionFingerprint)
        }
        fun request(recipe: Recipe, output: PixelSize) = RenderRequest(imported.sourceId.value, binding.sourcePixelHash, imported.pageId.value, 1, recipe, OutputSpec(output, RenderPurpose.PREVIEW), 1, null, FallbackPolicy.FAIL)
        fun coordinator() = checkNotNull(graph).let { DefaultRenderCoordinator(it.decoder, GeometryEngine(budget), AppearanceEngine(budget)) }
        suspend fun original() = checkNotNull(graph).store.committedSources.load(imported.sourceId).file.readBytes()
        suspend fun reopen() { close(); graph = ApplicationServices(context, budget, provider).imports() }
        override fun close() { graph?.store?.close(); graph = null }
    }

    private companion object {
        fun gray(v: Int) = (0xff shl 24) or (v shl 16) or (v shl 8) or v
        fun neutrals(vararg v: Int) = ByteArray(v.size * 3) { v[it / 3].toByte() }
    }
}
