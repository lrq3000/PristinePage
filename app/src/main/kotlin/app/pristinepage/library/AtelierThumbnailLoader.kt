package app.pristinepage.library

import app.pristinepage.core.geometry.GeometrySizing
import app.pristinepage.core.pipeline.*
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.DocumentPage
import app.pristinepage.data.model.DocumentSummary
import app.pristinepage.data.model.EditablePage
import app.pristinepage.data.model.PageId
import app.pristinepage.image.preview.RenderedPreviewBitmap
import app.pristinepage.image.preview.RenderedPreviewBitmaps
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Small saved-recipe renders share the editor's source proof and converter; the tile may only consume its exact catalog revision. */
internal class AtelierThumbnailLoader(
    private val loadPage: suspend (DocumentId, PageId) -> EditablePage,
    private val renderer: RenderCoordinator,
    private val converter: RenderedPreviewBitmaps,
) {
    suspend fun load(album: DocumentSummary, tile: DocumentPage): RenderedPreviewBitmap {
        val page = loadPage(album.documentId, tile.pageId)
        check(page.documentId == album.documentId && page.documentRevision == album.documentRevision &&
            page.pageId == tile.pageId && page.sourceId == tile.sourceId && page.recipeRevision == tile.recipeRevision)
        currentCoroutineContext().ensureActive()
        val recipe = page.recipe
        val output = GeometrySizing.output(recipe.geometry, RenderPurpose.PREVIEW, 192)
        val request = RenderRequest(page.sourceId.value, recipe.geometry.source.sourcePixelHash, page.pageId.value,
            page.recipeRevision, recipe, output, 1, null, FallbackPolicy.FAIL)
        var display: RenderedPreviewBitmap? = null
        try {
            renderer.render(request).use { result ->
                currentCoroutineContext().ensureActive()
                check(result.request == request && result.status == RenderStatus.OK &&
                    result.image?.width == output.size.width && result.image?.height == output.size.height)
                display = converter.convert(result)
            }
            // Batch Undo can advance the accepted revision while render or bitmap conversion is suspended.
            check(loadPage(album.documentId, tile.pageId) == page)
            currentCoroutineContext().ensureActive()
            return checkNotNull(display).also { check(it.request == request) }
        } catch (failure: Throwable) {
            display?.close()
            throw failure
        }
    }
}
