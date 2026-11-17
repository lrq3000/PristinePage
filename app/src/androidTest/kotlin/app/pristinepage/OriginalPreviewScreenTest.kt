package app.pristinepage

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import app.pristinepage.data.model.*
import app.pristinepage.image.preview.OriginalPreviewBitmap
import app.pristinepage.image.preview.OriginalPreviewBitmapLoader
import app.pristinepage.image.preview.OriginalPreviewLoader
import app.pristinepage.library.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose content in MainActivity with an explicit private synthetic graph/session. This tests visible original selection, not the production picker's grants, its external UI or the app's default database import journey. No source deletion or OS settings are used. */
@RunWith(AndroidJUnit4::class)
class OriginalPreviewScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun visibleOriginalUsesTheVerifiedImageAspectAndReleasesItsOwnerWhenTheContentLeaves() = runBlocking {
        withFixture { f, graph, session, pages, loader ->
            compose.runOnUiThread { compose.activity.setContent { MaterialTheme { LibraryScreen(session, { source -> loader.load(source) }) } } }
            requirePreviewUi("original-preview")
            compose.onNodeWithTag("original-preview").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("original-preview").assertContentDescriptionEquals("Original photograph preview, page 1")
            val image = compose.onNodeWithTag("original-preview").captureToImage()
            assertTrue(image.width > 0 && image.height > 0)
            assertEquals(2.0, image.width.toDouble() / image.height, 0.03)
            val pixel = image.toPixelMap()[(image.width * .375).toInt(), (image.height * .25).toInt()].toArgb()
            assertEquals(100.0, ((pixel ushr 16) and 255).toDouble(), 3.0)
            assertEquals(20.0, ((pixel ushr 8) and 255).toDouble(), 3.0)
            assertEquals(40.0, (pixel and 255).toDouble(), 3.0)
            compose.onNodeWithTag("original-page-${pages[1].pageId.value}").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Original photograph preview, page 2").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("original-preview").performScrollTo().assertIsDisplayed()
            assertTrue(graph.budget.usedBytes > 0)
            compose.runOnUiThread { compose.activity.setContent {} }
            compose.waitForIdle(); compose.waitUntil(10_000) { graph.budget.usedBytes == 0L }
            assertEquals(2, f.providerOpens)
        }
    }

    @Test fun lateUninterruptibleOriginalCannotReplaceANewerPageOrRetainAnUndisplayedOwner() = runBlocking {
        withFixture { _, graph, session, pages, loader ->
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val oldClosed = AtomicBoolean(false)
            val load: suspend (String) -> OriginalPreviewBitmap = { source ->
                if (source == pages[0].sourceId.value) {
                    started.complete(Unit)
                    withContext(NonCancellable) {
                        release.await()
                        val original = loader.load(source)
                        object : OriginalPreviewBitmap by original { override fun close() { original.close(); oldClosed.set(true) } }
                    }
                } else loader.load(source)
            }
            compose.runOnUiThread { compose.activity.setContent { MaterialTheme { LibraryScreen(session, load) } } }
            requirePreviewUi("original-preview-loading")
            try {
                withTimeout(10_000) { started.await() }
                compose.onNodeWithTag("original-page-${pages[1].pageId.value}").performScrollTo().performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Original photograph preview, page 2").fetchSemanticsNodes().isNotEmpty() }
                release.complete(Unit)
                compose.waitUntil(10_000) { oldClosed.get() }
                compose.onNodeWithTag("original-preview").assertContentDescriptionEquals("Original photograph preview, page 2")
            } finally { release.complete(Unit); compose.runOnUiThread { compose.activity.setContent {} }; compose.waitForIdle() }
            compose.waitUntil(10_000) { graph.budget.usedBytes == 0L }
        }
    }

    @Test fun sharedResourceRefusalShowsNoImageAndExplicitRetryUsesTheSamePrivateOriginal() = runBlocking {
        withFixture { f, graph, session, _, loader ->
            val pressure = graph.budget.reserve(graph.budget.capacityBytes - 1)
            try {
                compose.runOnUiThread { compose.activity.setContent { MaterialTheme { LibraryScreen(session, { source -> loader.load(source) }) } } }
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("retry-original-preview").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("original-preview").assertDoesNotExist()
                compose.onNodeWithText(compose.activity.getString(R.string.error_resource_limit)).performScrollTo().assertIsDisplayed()
                assertEquals(graph.budget.capacityBytes - 1, graph.budget.usedBytes)
                pressure.close()
                compose.onNodeWithTag("retry-original-preview").performScrollTo().performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Original photograph preview, page 1").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("original-preview").performScrollTo().assertIsDisplayed()
                assertEquals(2, f.providerOpens)
            } finally { pressure.close() }
        }
    }

    @Test fun backgroundingReleasesTheDisplayAndResumeReloadsWithoutReopeningTheProvider() = runBlocking {
        withFixture { f, graph, session, _, loader ->
            compose.runOnUiThread { compose.activity.setContent { MaterialTheme { LibraryScreen(session, { source -> loader.load(source) }) } } }
            requirePreviewUi("original-preview")
            assertTrue(graph.budget.usedBytes > 0)
            try {
                compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                compose.waitUntil(10_000) { graph.budget.usedBytes == 0L }
            } finally { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Original photograph preview, page 1").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("original-preview").performScrollTo().assertIsDisplayed()
            assertTrue(graph.budget.usedBytes > 0); assertEquals(2, f.providerOpens)
        }
    }

    @Test fun admittedTallAndWideOriginalsStayInsideAFiniteViewportWithReachablePageControls() = runBlocking {
        for ((width, height) in listOf(2 to 1024, 1024 to 2)) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val png = try { ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray() } finally { bitmap.recycle() }
            withFixture(png) { f, graph, session, pages, loader ->
                compose.runOnUiThread { compose.activity.setContent { MaterialTheme { LibraryScreen(session, { source -> loader.load(source) }) } } }
                requirePreviewUi("original-preview")
                compose.onNodeWithTag("original-preview").performScrollTo().assertIsDisplayed()
                val bounds = compose.onNodeWithTag("original-preview").getUnclippedBoundsInRoot()
                val displayHeight = (bounds.bottom - bounds.top).value
                val displayWidth = (bounds.right - bounds.left).value
                assertTrue("Preview must not derive an unbounded layout height from a tiny valid raster", displayHeight > 0 && displayHeight <= 512)
                assertTrue(displayWidth > 0 && displayWidth <= compose.activity.resources.configuration.screenWidthDp)
                compose.onNodeWithTag("original-page-${pages[1].pageId.value}").performScrollTo().assertIsDisplayed().performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Original photograph preview, page 2").fetchSemanticsNodes().isNotEmpty() }
                assertEquals(2, f.providerOpens); assertTrue(graph.budget.usedBytes > 0)
            }
        }
    }

    @Test fun selectedPageRemainsIdentifiedWhileItsOwnPreviewLoadsFailsAndRetries() = runBlocking {
        withFixture { f, _, session, pages, loader ->
            val release = CompletableDeferred<Unit>(); var refuse = true
            val load: suspend (String) -> OriginalPreviewBitmap = { source ->
                if (source == pages[1].sourceId.value && refuse) {
                    release.await()
                    throw RepositoryFailure(OperationError(OperationErrorCode.RESOURCE_LIMIT, true, "synthetic_preview_pressure", source))
                }
                loader.load(source)
            }
            compose.runOnUiThread { compose.activity.setContent { MaterialTheme { LibraryScreen(session, load) } } }
            requirePreviewUi("original-preview")
            try {
                compose.onNodeWithTag("original-page-${pages[1].pageId.value}").performScrollTo().performClick()
                compose.onNodeWithTag("original-page-${pages[1].pageId.value}").assertIsSelected()
                compose.onNodeWithTag("original-page-${pages[0].pageId.value}").assertIsNotSelected()
                compose.onNodeWithText("Page 2 — Selected").assertExists()
                compose.onNodeWithText("Original photograph preview, page 2").assertExists()
                compose.onNodeWithTag("original-preview-loading").assertExists()
                release.complete(Unit)
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("retry-original-preview").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("original-preview").assertDoesNotExist()
                compose.onNodeWithText("Original photograph preview, page 2").assertExists()
                compose.onNodeWithTag("original-page-${pages[1].pageId.value}").assertIsSelected()
                refuse = false
                compose.onNodeWithTag("retry-original-preview").performScrollTo().performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Original photograph preview, page 2").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("original-page-${pages[0].pageId.value}").assertIsNotSelected()
                assertEquals(2, f.providerOpens)
            } finally { release.complete(Unit) }
        }
    }

    private fun requirePreviewUi(tag: String) {
        // Both missing-feature cases must stop before injecting further UI actions; native RED is not a timeout or a framework setup error.
        try { compose.onNodeWithTag(if (tag == "original-preview") "original-preview-region" else tag).assertExists() }
        catch (failure: AssertionError) { throw AssertionError("Visible original preview is absent", failure) }
        if (tag == "original-preview") compose.waitUntil(10_000) { compose.onAllNodesWithTag("original-preview").fetchSemanticsNodes().isNotEmpty() }
    }

    private suspend fun withFixture(encoded: ByteArray = OriginalPreviewIntegrationTest.asymmetricPng(), body: suspend (DocumentCatalogIntegrationTest.Fixture, ApplicationImports, LibrarySession, List<DocumentPage>, OriginalPreviewBitmapLoader) -> Unit) {
        DocumentCatalogIntegrationTest.Fixture(encoded).use { f ->
            val graph = f.open(); val actions = ApplicationLibraryActions { graph }; val doc = actions.create("Visible original fixture")
            val batch = graph.store.imports.beginImport(doc,1,null)
            graph.photoImports.importProviderReferences(listOf(f.good,f.good),batch.parentId)
            val pages = graph.store.catalog.load(doc).pages
            f.forbid = true
            val owner = SupervisorJob(); val scope = CoroutineScope(owner + Dispatchers.Main.immediate)
            try {
                val session = withContext(Dispatchers.Main) { ImportLibrarySession(actions,LibraryIdentity(doc),scope) }
                withTimeout(10_000) { session.state.first { !it.loading && it.document != null } }
                val loader = OriginalPreviewBitmapLoader(OriginalPreviewLoader(graph.store.committedSources,graph.decoder,graph.budget),graph.budget)
                body(f,graph,session,pages,loader)
            } finally { compose.runOnUiThread { compose.activity.setContent {} }; compose.waitForIdle(); owner.cancelAndJoin() }
        }
    }
}
