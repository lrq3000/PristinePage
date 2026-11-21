package app.pristinepage.image.provider

import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.PointPx
import app.pristinepage.image.decode.ImageAllocationBudget
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DocQuadDetectorTest {
    @get:Rule val fixture = TemporaryFolder()
    private val bytes = "hello".encodeToByteArray()
    private val spec = ModelAssetSpec("synthetic-docquad", 1, 5, "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", peakInferenceBytes = 8_000_000)
    private val sample get() = RgbImage(256, 256, 768, ByteArray(3 * 256 * 256))

    @Test fun absentAssetAndUnqualifiedRuntimeDoNotRunInference() {
        val store = modelStore()
        val budget = ImageAllocationBudget(16_000_000)
        val mustNotRun = DocQuadInference { _, _, _ -> error("No inference may start") }
        sample.use { rgb ->
            val unavailable = DocQuadDetector(store, spec, budget, mustNotRun).detect(rgb)
            assertEquals("DETECTOR_UNAVAILABLE", unavailable.reason)
            assertFalse(Files.exists(fixture.root.toPath().resolve("models")))
            install(store)
            val unqualified = DocQuadDetector(store, spec.copy(peakInferenceBytes = null), budget, mustNotRun).detect(rgb)
            assertEquals("DETECTOR_UNAVAILABLE", unqualified.reason)
            assertNull(unqualified.quad)
            assertEquals(0, budget.usedBytes)
        }
    }

    @Test fun admittedModelIsHeldUntilRealProposalExtractionFinishes() {
        val store = modelStore().also { install(it) }
        val budget = ImageAllocationBudget(16_000_000)
        val detector = DocQuadDetector(store, spec, budget, DocQuadInference { asset, chw, _ ->
            assertTrue(Files.readAllBytes(asset).contentEquals(bytes))
            assertEquals(256 * 256 * 3, chw.size)
            assertEquals(8_000_000, budget.usedBytes)
            assertEquals(ModelAssetFailure.IN_USE, assertThrows(ModelAssetException::class.java) { store.remove(spec) }.reason)
            heads()
        })
        sample.use { rgb ->
            val result = detector.detect(rgb)
            assertTrue(result.detected)
            assertEquals(PointPx(42.0, 42.0), result.quad?.tl)
        }
        assertEquals(0, budget.usedBytes)
        store.remove(spec)
    }

    @Test fun contentionAndCancellationDoNotReturnAQuadOrRetainTheModel() {
        val store = modelStore().also { install(it) }
        sample.use { rgb ->
            val noCapacity = ImageAllocationBudget(7_999_999)
            val detect = DocQuadDetector(store, spec, noCapacity, DocQuadInference { _, _, _ -> error("Reservation must happen before inference") })
            assertEquals("RESOURCE_LIMIT", detect.detect(rgb).reason)
            assertEquals(0, noCapacity.usedBytes)

            val budget = ImageAllocationBudget(16_000_000)
            var cancelled = false
            val cancellable = DocQuadDetector(store, spec, budget, DocQuadInference { _, _, _ ->
                cancelled = true
                heads()
            })
            assertThrows(CancellationException::class.java) {
                cancellable.detect(rgb) { if (cancelled) throw CancellationException() }
            }
            assertEquals(0, budget.usedBytes)
            store.remove(spec)
        }
    }

    @Test fun cancellationWinsOverALateRuntimeFailureAndReleasesOwners() {
        val store = modelStore().also { install(it) }
        val budget = ImageAllocationBudget(16_000_000)
        var cancelled = false
        val detector = DocQuadDetector(store, spec, budget, DocQuadInference { _, _, _ ->
            cancelled = true
            throw DocQuadInferenceFailure(DocQuadInferenceFailure.Reason.INFERENCE_FAILED)
        })
        sample.use { rgb ->
            assertThrows(CancellationException::class.java) {
                detector.detect(rgb) { if (cancelled) throw CancellationException() }
            }
        }
        assertEquals(0, budget.usedBytes)
        store.remove(spec)
    }

    @Test fun outputSnapshotDoesNotAliasReusedRuntimeScratch() {
        val original = FloatArray(4096) { i -> if (i % 64 in 10..50 && i / 64 in 10..50) 8f else -8f }
        val store = modelStore().also { install(it) }
        val detector = DocQuadDetector(store, spec, ImageAllocationBudget(16_000_000), DocQuadInference { _, _, _ ->
            heads(original).also { original.fill(Float.NaN) }
        })
        sample.use { assertTrue(detector.detect(it).detected) }
    }

    private fun modelStore() = ModelAssetStore(fixture.root.toPath().resolve("models"))
    private fun install(store: ModelAssetStore) = store.install(spec) { ByteArrayInputStream(bytes) }

    private fun heads(mask: FloatArray = FloatArray(4096) { i -> if (i % 64 in 10..50 && i / 64 in 10..50) 8f else -8f }): DocQuadHeads {
        val points = listOf(10 to 10, 50 to 10, 50 to 50, 10 to 50)
        val heat = FloatArray(4 * 4096) { -8f }
        points.forEachIndexed { i, (x, y) -> heat[i * 4096 + y * 64 + x] = 8f }
        return DocQuadHeads(mask, heat)
    }
}
