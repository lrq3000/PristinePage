package app.pristinepage

import android.graphics.Bitmap
import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.data.model.*
import app.pristinepage.image.preview.RenderedPreviewBitmap
import app.pristinepage.image.preview.RenderedPreviewBitmaps
import app.pristinepage.library.AtelierThumbnailLoader
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AtelierThumbnailLoaderTest {
    private val album = DocumentSummary(DocumentId(id(1)), "Fixture", 12, 1, false)
    private val tile = DocumentPage(PageId(id(2)), SourceId(id(3)), 4, 0)
    private val source = SourceBinding("a".repeat(64), PixelSize(320, 160), "fixture-v1")
    private val recipe = Recipe(geometry = GeometryRecipe.None(source, outputQuarterTurns = 1))
    private val page = EditablePage(album.documentId, 12, tile.pageId, tile.sourceId, 4, RecipeCodec.hash(recipe), recipe)

    @Test fun acceptedRotationUsesTheExactSavedRecipeAndA192PixelDisplay() = runTest {
        val fixture = Fixture()
        val bitmap = fixture.loader.load(album, tile)
        assertEquals(recipe, bitmap.request.recipe)
        assertEquals(tile.sourceId.value, bitmap.request.sourceId)
        assertEquals(tile.pageId.value, bitmap.request.pageId)
        assertEquals(4L, bitmap.request.recipeRevision)
        assertEquals(OutputSpec(PixelSize(96, 192), RenderPurpose.PREVIEW), bitmap.request.output)
        assertEquals(FallbackPolicy.FAIL, bitmap.request.fallback)
        assertEquals(1, fixture.closedRasters)
        assertEquals(0, fixture.closedDisplays)
        bitmap.close()
        assertEquals(1, fixture.closedDisplays)
    }

    @Test fun aChangedAlbumOrTileRevisionDoesNotRenderUnderTheOldKey() = runTest {
        val fixture = Fixture()
        for (wrong in listOf(page.copy(documentRevision = 13), page.copy(recipeRevision = 5), page.copy(sourceId = SourceId(id(4))))) {
            fixture.saved = wrong
            assertTrue(runCatching { fixture.loader.load(album, tile) }.isFailure)
        }
        assertEquals(0, fixture.renders)
    }

    @Test fun aDegradedOrMismatchedResultClosesWithoutConvertingIt() = runTest {
        val fixture = Fixture()
        fixture.status = RenderStatus.DEGRADED
        assertTrue(runCatching { fixture.loader.load(album, tile) }.isFailure)
        fixture.status = RenderStatus.OK
        fixture.generationDrift = true
        assertTrue(runCatching { fixture.loader.load(album, tile) }.isFailure)
        assertEquals(2, fixture.closedRasters)
        assertEquals(0, fixture.conversions)
    }

    @Test fun aSavedUndoThatCommitsWhileRenderingPreventsPublicationOfTheOldRevision() = runTest {
        val fixture = Fixture()
        fixture.afterRender = {
            val reverted = Recipe(geometry = GeometryRecipe.None(source))
            fixture.saved = page.copy(documentRevision = 13, recipeRevision = 5,
                recipeHash = RecipeCodec.hash(reverted), recipe = reverted)
        }
        val result = runCatching { fixture.loader.load(album, tile) }

        result.getOrNull()?.close()
        assertTrue(result.isFailure)
        assertEquals(1, fixture.closedRasters)
        assertEquals(1, fixture.closedDisplays)
    }

    private inner class Fixture {
        var saved = page
        var status = RenderStatus.OK
        var generationDrift = false
        var renders = 0
        var conversions = 0
        var closedRasters = 0
        var closedDisplays = 0
        var afterRender: () -> Unit = {}
        val loader = AtelierThumbnailLoader({ _, _ -> saved }, object : RenderCoordinator {
            override suspend fun render(request: RenderRequest): RenderResult {
                renders++
                val (width, height) = request.output.size
                return RenderResult(if (generationDrift) request.copy(generation = 7) else request, status,
                    RgbImage(width, height, width * 3, ByteArray(width * height * 3)), emptyList(), emptyList(),
                    AutoCloseable { closedRasters++ }).also { afterRender() }
            }
        }, RenderedPreviewBitmaps { result ->
            conversions++
            object : RenderedPreviewBitmap {
                override val request = result.request
                override val bitmap: Bitmap get() = error("The host test does not draw")
                override fun close() { closedDisplays++ }
            }
        })
    }

    private fun id(value: Long) = UUID(0, value).toString()
}
