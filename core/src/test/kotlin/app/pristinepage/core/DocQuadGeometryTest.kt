package app.pristinepage.core

import app.pristinepage.core.geometry.DocQuadGeometry
import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.PointPx
import app.pristinepage.core.pipeline.QuadPx
import java.util.concurrent.CancellationException
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocQuadGeometryTest {
    private val landscape = PixelSize(512, 256)
    private val corners = listOf(10 to 20, 50 to 20, 50 to 40, 10 to 40)

    @Test fun portableLetterboxKeepsRgbPlaneOrderAndFloatsWithoutQuantizing() {
        val row = byteArrayOf(10, 65, 144.toByte(), 90, 75, 164.toByte(), 120, 0, 8, 2, 3, 4)
        RgbImage(4, 2, 12, row + row).use { image ->
            val input = DocQuadGeometry.preprocess(image)
            val plane = 256 * 256
            assertEquals(3 * plane, input.size)
            for (channel in 0..2) {
                assertEquals(0f, input[channel * plane + 63 * 256])
                assertEquals(0f, input[channel * plane + 192 * 256])
            }
            assertEquals(10f / 255, input[64 * 256])
            assertEquals(65f / 255, input[plane + 64 * 256])
            assertEquals(144f / 255, input[2 * plane + 64 * 256])
            assertEquals(10.625f / 255, input[64 * 256 + 32])
        }
    }

    @Test fun labeledHeadCoordinatesUseTheNominalInverseWithoutAPixelCenterAdjustment() {
        val result = DocQuadGeometry.propose(landscape, mask(), heatmaps())
        assertTrue(result.detected)
        assertEquals(QuadPx(PointPx(84.0, 36.0), PointPx(404.0, 36.0), PointPx(404.0, 196.0), PointPx(84.0, 196.0)), result.quad)
        assertEquals(1 / (1 + exp(-8.0)), checkNotNull(result.score), 1e-12)
    }

    @Test fun negativeConcavityAllowsAnIndependentSubCellAdjustment() {
        val logits = heatmaps()
        logits[20 * 64 + 9] = 4f
        logits[20 * 64 + 11] = 6f
        val result = DocQuadGeometry.propose(landscape, mask(), logits)
        assertTrue(result.detected)
        // With L=4,C=8,R=6, the offset is 1/6 of a heatmap cell, or 4/3 source pixels.
        val topLeft = checkNotNull(result.quad).tl
        assertEquals(84.0 + 4.0 / 3, topLeft.x, 1e-10)
        assertEquals(36.0, topLeft.y)
    }

    @Test fun invalidTensorsAndLowMaskAgreementNeverReturnAFallbackQuad() {
        val corrupt = heatmaps().apply { this[lastIndex] = Float.NaN }
        val malformed = DocQuadGeometry.propose(landscape, mask(), corrupt)
        assertFalse(malformed.detected)
        assertNull(malformed.quad)
        assertEquals("INVALID_TENSOR", malformed.reason)
        assertEquals("INVALID_TENSOR", DocQuadGeometry.propose(landscape, FloatArray(63 * 64), heatmaps()).reason)
        val low = DocQuadGeometry.propose(landscape, FloatArray(64 * 64) { -1f }, heatmaps())
        assertEquals("LOW_CONFIDENCE", low.reason)
        assertEquals(0.0, low.score)
        assertNull(low.quad)
    }

    @Test fun crossedLabelsAndPredictionsInsidePaddingAreRefusedInsteadOfRepaired() {
        val crossed = listOf(corners[0], corners[2], corners[1], corners[3])
        val padded = listOf(10 to 8, 50 to 8, corners[2], corners[3])
        for (order in listOf(crossed, padded)) {
            val result = DocQuadGeometry.propose(landscape, mask(), heatmaps(order))
            assertEquals("INVALID_GEOMETRY", result.reason)
            assertNull(result.quad)
        }
    }

    @Test fun longHostStagesPropagateCancellation() {
        assertFailsWith<CancellationException> {
            DocQuadGeometry.propose(landscape, mask(), heatmaps()) { throw CancellationException() }
        }
    }

    private fun heatmaps(points: List<Pair<Int, Int>> = corners) =
        FloatArray(4 * 64 * 64) { -8f }.apply {
            points.forEachIndexed { channel, (x, y) -> this[channel * 64 * 64 + y * 64 + x] = 8f }
        }

    private fun mask() = FloatArray(64 * 64) { index ->
        if (index % 64 in 10..50 && index / 64 in 20..40) 8f else -8f
    }
}
