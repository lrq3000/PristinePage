package app.pristinepage

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Process
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityService
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.capture.PhotoPickerContract
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.data.model.*
import app.pristinepage.editor.EditorViewModel
import app.pristinepage.library.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual unchanged MainActivity/ActivityResult/system picker/URI grant/Application graph. Selects the complete authenticated two-fixture album, never an arbitrary recent tile; retained synthetic artifacts only, no injected result or replacement content. */
@RunWith(AndroidJUnit4::class)
class P06ImportJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    @Test fun realSelectedReadGrantsBecomePrivateSourcesAndDefaultEditorReopensTheSavedSecondPage() = runBlocking {
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val runId = checkNotNull(arguments.getString("p06_run_id")); require(UUID.fromString(runId).toString() == runId)
        val album = checkNotNull(arguments.getString("p06_album")); require(album.matches(Regex("PristinePage-QA-[a-f0-9-]{36}")))
        val ids = checkNotNull(arguments.getString("p06_media_ids")).split(",").map { it.toLong().also { id -> require(id > 0) } }
        require(ids.size == 2 && ids.distinct().size == 2)
        val report = JSONObject().put("runId",runId).put("album",album).put("selectedSetSize",2).put("completed",false)
        val directory = File(context.filesDir,"p06-real-journey/$runId"); check(directory.mkdirs())
        fun checkpoint(step: String) { report.put("step",step); File(directory,"result.json").writeText(report.toString(2)) }
        val intent = PhotoPickerContract().createIntent(context,Unit)
        @Suppress("DEPRECATION") val picker = checkNotNull(context.packageManager.resolveActivity(intent,PackageManager.MATCH_DEFAULT_ONLY)).activityInfo.packageName
        require(picker == "com.google.android.gms") // Actual inspected installed route, not an OS-version assumption or forced Intent.
        val expectedHashes = setOf("3c27e10c8f798d30402251941669802a65768b3e2415f0bb35b066bc1eecd555","e5ede9acea09e2166c55c0bdfa16bedc312132c2ca5b441c1bb2509ad2efa79a")
        try {
            ids.forEach { id -> assertEquals(PackageManager.PERMISSION_DENIED,context.checkUriPermission(Uri.parse("content://media/external/images/media/$id"),Process.myPid(),Process.myUid(),Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            checkpoint("source-media-read-not-granted")
            val library = compose.runOnUiThread { ViewModelProvider(compose.activity)[ImportLibraryViewModel::class.java].session }
            compose.waitUntil(10_000) { !library.state.value.loading }
            compose.onNodeWithTag("create-document").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { !library.state.value.loading && library.state.value.document?.pages?.isEmpty() == true }
            val document = checkNotNull(library.state.value.selectedId)
            report.put("documentId",document.value)
            compose.onNodeWithTag("import-photos").assertIsEnabled().performClick()
            compose.waitUntil(15_000) { automation.rootInActiveWindow?.packageName?.toString() == picker }
            assertEquals(ImportPhase.WAITING_PICKER,library.state.value.phase)
            clickText("Albums",picker)
            compose.waitUntil(10_000) { nodes(root(picker)).any { it.text?.toString() == album } }
            clickText(album,picker)
            compose.waitUntil(10_000) { nodes(root(picker)).any { it.text?.toString() == album } && photos(root(picker)).size == 2 }
            val first = photos(root(picker)).first()
            val firstBounds = Rect().also { first.getBoundsInScreen(it) }
            assertTrue(first.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            automation.waitForIdle(100,5_000)
            // Both entries belong to the exact unique generated album. Selecting the whole two-element set does not infer which identical timestamp is A or B. Refuse a changed set/layout instead of choosing an arbitrary recent item.
            val afterFirst = root(picker)
            assertTrue(nodes(afterFirst).any { it.text?.toString() == album })
            val remaining = photos(afterFirst).filter { node -> Rect().also { node.getBoundsInScreen(it) } != firstBounds }
            assertEquals(1,remaining.size)
            assertTrue(remaining.single().performAction(AccessibilityNodeInfo.ACTION_CLICK))
            automation.waitForIdle(100,5_000)
            val selection = root(picker)
            report.put("selectionUi",JSONArray(nodes(selection).mapNotNull { it.text?.toString()?.takeIf(String::isNotEmpty) }))
            checkpoint("two-owned-album-entries-selected")
            val add = nodes(selection).filter { it.text?.toString()?.matches(Regex("Ajouter\\s*\\(2\\)")) == true }
            assertEquals("Unmatched Add-two confirmation; do not guess another action",1,add.size)
            assertTrue(actionable(add.single()).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            compose.waitUntil(20_000) { library.state.value.request == null && !library.state.value.loading && library.state.value.document?.pages?.size == 2 }
            assertEquals(ImportPhase.COMPLETE,library.state.value.phase)
            val pages = checkNotNull(library.state.value.document).pages
            val results = library.state.value.results.map { it as IngestResult.Committed }
            assertEquals(pages.map { it.pageId },results.map { it.pageId })
            val graph = (context.applicationContext as PristinePageApplication).services.imports()
            val sources = pages.map { graph.store.committedSources.load(it.sourceId) }
            assertEquals(expectedHashes,sources.map { hash(it.file.readBytes()) }.toSet())
            sources.forEach { assertEquals(746L,it.byteCount); assertEquals(PixelSize(256,192),it.inspection.normalizedSize); assertEquals(hash(it.file.readBytes()),it.sourceByteHash) }
            checkpoint("two-private-normalized-sources-committed")
            // Never discover readable content by parsing a whole grant dump or hashing unknown URIs. Only the two authenticated canonical fixture URIs are candidates; a wrapper-only grant without a verified mapping stops before either open. This checks effective access, not temporary-grant lifetime or independent selection order.
            val permitted = ids.map { "content://media/external/images/media/$it" }
            val grantHashes = P06GrantScope.read(permitted,permitted,{ reference ->
                val uri = Uri.parse(reference)
                context.checkUriPermission(uri,Process.myPid(),Process.myUid(),Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED && context.checkUriPermission(uri,Process.myPid(),Process.myUid(),Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_DENIED
            },{ reference ->
                val uri = Uri.parse(reference)
                context.contentResolver.openInputStream(uri).use { input ->
                    checkNotNull(input); val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) { val count = input.read(buffer); if (count < 0) break; check(output.size()+count <= 256*1024); output.write(buffer,0,count) }
                    hash(output.toByteArray())
                }
            }).toSet()
            assertEquals(expectedHashes,grantHashes)
            report.put("actualReadOnlyGrantHashes",JSONArray(grantHashes.toList()))
            checkpoint("effective-canonical-fixture-read-only-access-authenticated")
            compose.onNodeWithTag("original-page-${pages[1].pageId.value}").performScrollTo().performClick()
            compose.onNodeWithTag("edit-selected-page").performScrollTo().performClick()
            val editor = compose.runOnUiThread { ViewModelProvider(compose.activity)[EditorViewModel::class.java] }
            compose.waitUntil(10_000) { editor.session.value?.preview?.value?.display != null }
            compose.onNodeWithTag("editor-rotate").performScrollTo().performClick()
            compose.onNodeWithTag("editor-apply").performScrollTo().performClick()
            compose.waitUntil(10_000) { editor.session.value?.state?.value?.page?.recipeRevision == 2L && editor.session.value?.state?.value?.dirty == false }
            compose.onNodeWithTag("editor-back").performScrollTo().performClick()
            compose.waitUntil(10_000) { editor.session.value == null && !library.state.value.loading }
            compose.onNodeWithTag("original-page-${pages[1].pageId.value}").performScrollTo().assertIsSelected()
            compose.onNodeWithTag("edit-selected-page").performScrollTo().performClick()
            compose.waitUntil(10_000) { editor.session.value?.preview?.value?.display != null }
            assertEquals(pages[1].pageId,editor.session.value!!.state.value.page!!.pageId)
            assertEquals(1,editor.session.value!!.state.value.draft!!.geometry.outputQuarterTurns)
            assertEquals(1L,graph.store.editablePages.load(document,pages[0].pageId).recipeRevision)
            assertEquals(2L,graph.store.editablePages.load(document,pages[1].pageId).recipeRevision)
            assertEquals(expectedHashes,pages.map { hash(graph.store.committedSources.load(it.sourceId).file.readBytes()) }.toSet())
            compose.onNodeWithTag("editor-back").performScrollTo().performClick()
            compose.waitUntil(10_000) { editor.session.value == null }
            report.put("completed",true).put("pageIds",JSONArray(pages.map { it.pageId.value })).put("orderedPrivateSourceHashes",JSONArray(sources.map { it.sourceByteHash }))
            checkpoint("real-picker-private-import-apply-reopen-passed")
        } finally {
            // Cancel only our expected picker if an assertion stopped there. Never acknowledge a permission/settings/account/other package prompt or discard a draft automatically.
            repeat(3) { if (automation.rootInActiveWindow?.packageName?.toString() == picker) automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
            File(directory,"result.json").writeText(report.toString(2))
        }
        Unit
    }

    private fun root(expected: String): AccessibilityNodeInfo = checkNotNull(automation.rootInActiveWindow).also { assertEquals(expected,it.packageName?.toString()) }
    private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>(); val queue = java.util.ArrayDeque<AccessibilityNodeInfo>(); queue.add(root)
        while (queue.isNotEmpty()) { check(result.size < 512); val node = queue.removeFirst(); result += node; repeat(node.childCount) { node.getChild(it)?.let(queue::addLast) } }
        return result
    }
    private fun photos(root: AccessibilityNodeInfo) = nodes(root).filter { it.isClickable && it.isVisibleToUser && it.contentDescription?.toString()?.contains("Photo prise le ") == true }
    private fun actionable(node: AccessibilityNodeInfo): AccessibilityNodeInfo { var current = node; while (!current.isClickable) current = checkNotNull(current.parent); check(current.isEnabled && current.isVisibleToUser); return current }
    private fun clickText(text: String, picker: String) { val found = nodes(root(picker)).filter { it.text?.toString() == text }; assertEquals(1,found.size); assertTrue(actionable(found.single()).performAction(AccessibilityNodeInfo.ACTION_CLICK)); automation.waitForIdle(100,5_000) }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
