package app.pristinepage.core

import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.SourceBinding
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** A rendered image cannot outlive its allocation accounting merely because a coordinator returned it. */
class RenderOwnershipTest {
    private fun request(): RenderRequest {
        val binding = SourceBinding("a".repeat(64), PixelSize(1, 1), "test-srgb-v1")
        return RenderRequest("source", binding.sourcePixelHash, "page", 1, Recipe(geometry = GeometryRecipe.None(binding)), OutputSpec(PixelSize(1, 1), RenderPurpose.PREVIEW), 1, null, FallbackPolicy.FAIL)
    }

    private fun owned(image: RgbImage?, status: RenderStatus, release: AutoCloseable): AutoCloseable {
        val type = Class.forName("app.pristinepage.core.pipeline.RenderResult")
        val constructor = type.constructors.firstOrNull { it.parameterCount == 6 && it.parameterTypes.last() == AutoCloseable::class.java }
        assertNotNull("RenderResult has no allocation-owning lifetime", constructor)
        return try { constructor!!.newInstance(request(), status, image, emptyList<StageRecord>(), emptyList<String>(), release) as AutoCloseable }
        catch (error: InvocationTargetException) { throw error.targetException }
    }

    @Test fun resultCloseEndsImageAndReservationExactlyOnce() {
        val releases = AtomicInteger()
        val image = RgbImage(1, 1, 3, byteArrayOf(1, 2, 3))
        val result = owned(image, RenderStatus.OK, AutoCloseable { releases.incrementAndGet() })
        assertEquals(2, image.getUnsigned(0, 0, 1))
        assertEquals(0, releases.get())
        result.close(); result.close()
        assertEquals(1, releases.get())
        assertThrows(IllegalStateException::class.java) { image.getUnsigned(0, 0, 0) }
    }

    @Test fun exceptionalReservationReleaseStillClosesPixelsAndDoesNotRepeatRelease() {
        val releases = AtomicInteger()
        val image = RgbImage(1, 1, 3, byteArrayOf(1, 2, 3))
        val result = owned(image, RenderStatus.DEGRADED, AutoCloseable { releases.incrementAndGet(); throw IllegalStateException("test release failure") })
        assertThrows(IllegalStateException::class.java) { result.close() }
        result.close()
        assertEquals(1, releases.get())
        assertThrows(IllegalStateException::class.java) { image.getUnsigned(0, 0, 0) }
    }

    @Test fun failureResultCannotPublishAnImage() {
        for (status in RenderStatus.entries.filter { it !in listOf(RenderStatus.OK, RenderStatus.DEGRADED) }) {
            RgbImage(1, 1, 3, byteArrayOf(1, 2, 3)).use { image ->
                assertThrows(IllegalArgumentException::class.java) { owned(image, status, AutoCloseable {}) }
            }
        }
    }

    @Test fun successfulResultRequiresACompleteRaster() {
        for (status in listOf(RenderStatus.OK, RenderStatus.DEGRADED)) {
            assertThrows(IllegalArgumentException::class.java) { owned(null, status, AutoCloseable {}) }
        }
    }

    @Test fun publishedStageWarningsDoNotAliasCallerOwnedNestedLists() {
        val inputWarnings = mutableListOf("FIRST", "SECOND")
        val inputStages = mutableListOf(StageRecord("test", 1, null, "OK", PixelSize(1, 1), PixelSize(1, 1), inputWarnings))
        RgbImage(1, 1, 3, byteArrayOf(1, 2, 3)).use { image ->
            RenderResult(request(), RenderStatus.OK, image, inputStages, emptyList()).use { result ->
                inputWarnings[0] = "CHANGED_AFTER_PUBLICATION"
                inputStages.clear()
                assertEquals(listOf("FIRST", "SECOND"), result.stages.single().warningCodes)
            }
        }
    }

    @Test fun publishedProvenanceCannotBeRewrittenThroughMutableListViews() {
        val stage = StageRecord("test", 1, null, "OK", PixelSize(1, 1), PixelSize(1, 1), listOf("FIRST", "SECOND"))
        RgbImage(1, 1, 3, byteArrayOf(1, 2, 3)).use { image ->
            RenderResult(request(), RenderStatus.OK, image, listOf(stage, stage.copy(stageId = "next")), listOf("TOP_FIRST", "TOP_SECOND")).use { result ->
                // Kotlin's read-only List type alone does not stop a Java consumer/cast mutating its backing ArrayList. Either refuse that mutation or return an independent view; the published record must remain intact.
                try { (result.stages.single { it.stageId == "test" }.warningCodes as MutableList<String>).clear() } catch (_: UnsupportedOperationException) { }
                try { (result.stages as MutableList<StageRecord>).clear() } catch (_: UnsupportedOperationException) { }
                try { (result.warnings as MutableList<String>).clear() } catch (_: UnsupportedOperationException) { }
                assertEquals(listOf("test", "next"), result.stages.map { it.stageId })
                assertEquals(listOf("FIRST", "SECOND"), result.stages.first().warningCodes)
                assertEquals(listOf("TOP_FIRST", "TOP_SECOND"), result.warnings)
            }
        }
    }
}
