package app.pristinepage

import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.accessibilityservice.AccessibilityService
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.capture.PhotoPickerContract
import app.pristinepage.library.ImportLibraryViewModel
import app.pristinepage.library.ImportPhase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileNotFoundException

/** Real launcher/default Application graph and external system UI; no content replacement or injected Activity Result. The test-only document authority is separate from production and must deny source reads before the picker grants access. */
@RunWith(AndroidJUnit4::class)
class SystemPickerJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun testDocumentProviderIsRestrictedBeforeAnyUserSelection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION") val provider = context.packageManager.resolveContentProvider(AUTHORITY, 0)
        assertNotNull("System picker fixture provider is absent", provider)
        assertEquals("android.permission.MANAGE_DOCUMENTS", provider!!.readPermission)
        assertEquals("android.permission.MANAGE_DOCUMENTS", provider.writePermission)
        assertTrue(provider.exported); assertTrue(provider.grantUriPermissions)
        val uri = DocumentsContract.buildDocumentUri(AUTHORITY, "original-a")
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION))
        try { context.contentResolver.openInputStream(uri)?.close(); fail("Unselected fixture was readable without an actual system grant") }
        catch (_: SecurityException) { /* Expected platform access refusal, not a synthetic provider callback. */ }
    }

    @Test fun fixtureProviderLoadsInItsOwnPackageWithoutTargetApplicationDependencies() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // A provider is started in the test APK's own process by DocumentsUI, not in the instrumented target's joined classloader. Merely resolving ProviderInfo/denying an unauthorized read cannot prove that its implementation can start there.
        val fixtureContext = context.createPackageContext("app.pristinepage.debug.test", Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
        val type = Class.forName("app.pristinepage.testing.SyntheticDocumentsProvider", true, fixtureContext.classLoader)
        val provider = type.getConstructor().newInstance() as DocumentsProvider
        assertTrue(provider.onCreate())
        provider.queryRoots(null).use { roots ->
            assertEquals(1, roots.count); assertTrue(roots.moveToFirst())
            assertEquals("PristinePage QA", roots.getString(roots.getColumnIndexOrThrow(DocumentsContract.Root.COLUMN_TITLE)))
            assertEquals("generated-fixtures-v1", roots.getString(roots.getColumnIndexOrThrow(DocumentsContract.Root.COLUMN_DOCUMENT_ID)))
        }
        provider.queryChildDocuments("generated-fixtures-v1", null, null as String?).use { documents ->
            val ids = mutableListOf<String>()
            while (documents.moveToNext()) {
                ids += documents.getString(documents.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                assertTrue(documents.getLong(documents.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)) in 1..4096)
            }
            assertEquals(listOf("original-a", "original-b", "corrupt"), ids)
        }
        assertThrows(FileNotFoundException::class.java) { provider.openDocument("original-a", "w", null) }
        assertThrows(FileNotFoundException::class.java) { provider.queryDocument("unrelated", null) }
    }

    @Test fun actualSystemPickerCancellationRetainsTheOriginalDocumentAndClosesItsReservation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent = PhotoPickerContract().createIntent(context, Unit)
        // AndroidX can select an installed OEM fallback photo picker even on API28. Follow that real capability-selected action, not an OS-version assumption or a forced document Intent.
        assertNotNull(intent.action)
        @Suppress("DEPRECATION") val expectedPackage = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        assertNotNull("No installed picker resolves the app's own intent", expectedPackage)
        val session = compose.runOnUiThread { ViewModelProvider(compose.activity)[ImportLibraryViewModel::class.java].session }
        compose.waitUntil(10_000) { !session.state.value.loading }
        compose.onNodeWithTag("create-document").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { !session.state.value.loading && session.state.value.document != null }
        val document = session.state.value.selectedId!!
        assertTrue(session.state.value.document!!.pages.isEmpty())
        val persistedBefore = context.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet()
        compose.onNodeWithTag("import-photos").assertIsEnabled().performClick()
        compose.waitUntil(15_000) { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == expectedPackage }
        assertEquals(ImportPhase.WAITING_PICKER, session.state.value.phase); assertNotNull(session.state.value.request)
        // Back is sent only after the exact resolved external picker owns the active window. No permission/system-settings dialog is acknowledged and no provider/file is selected.
        assertEquals(expectedPackage, instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
        assertTrue("The resolved picker refused the scoped accessibility Back action", instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        compose.waitUntil(15_000) { session.state.value.request == null && session.state.value.phase == ImportPhase.CANCELED && !session.state.value.loading }
        assertEquals(document, session.state.value.selectedId); assertTrue(session.state.value.document!!.pages.isEmpty())
        compose.onNodeWithTag("import-photos").assertIsEnabled()
        assertEquals(persistedBefore, context.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet())
    }

    private companion object { const val AUTHORITY = "app.pristinepage.debug.test.documents" }
}
