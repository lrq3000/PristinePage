package app.pristinepage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.DocumentPage
import app.pristinepage.data.model.DocumentSummary
import app.pristinepage.data.model.PageId
import app.pristinepage.data.model.SourceId
import app.pristinepage.design.CarnetTheme
import app.pristinepage.library.AlbumThumbnailRow
import java.util.UUID
import kotlinx.coroutines.awaitCancellation
import org.junit.Rule
import org.junit.Test

class CarnetThumbnailRowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun independentScrollActionsReachTheLastPageAndReturnWithoutAGesture() {
        val album = DocumentSummary(DocumentId(UUID.randomUUID().toString()), "Synthetic album", 1, 5, false)
        val source = SourceId(UUID.randomUUID().toString())
        val pages = List(5) { ordinal -> DocumentPage(PageId(UUID.randomUUID().toString()), source, 1, ordinal) }
        compose.setContent {
            CarnetTheme {
                Box(Modifier.width(155.dp)) {
                    // Keep this test on navigation; the existing renderer tests cover actual saved pixels.
                    AlbumThumbnailRow(album, pages) { _, _ -> awaitCancellation() }
                }
            }
        }
        val previous = compose.onNodeWithTag("carnet-thumbnails-previous")
        val next = compose.onNodeWithTag("carnet-thumbnails-next")
        previous.assertIsNotEnabled()
        compose.onNodeWithTag("carnet-thumbnail-0").assertIsDisplayed()
        repeat(4) { next.performClick() }
        compose.onNodeWithTag("carnet-thumbnail-4").assertIsDisplayed()
        next.assertIsNotEnabled()
        repeat(4) { previous.performClick() }
        compose.onNodeWithTag("carnet-thumbnail-0").assertIsDisplayed()
        previous.assertIsNotEnabled()
    }
}
