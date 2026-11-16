package app.pristinepage

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.capture.PhotoImportController
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.BoundSourceIngest
import app.pristinepage.data.repository.ImportSelections
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android result/Uri adapters, not provider byte/grant availability, picker UI interaction, API26 or recreation acceptance. No external activity is launched by these tests. */
@RunWith(AndroidJUnit4::class)
class PhotoPickerContractTest {
    private val a = Uri.parse("content://fixture.documents/one")
    private val b = Uri.parse("content://fixture.documents/two")
    @Suppress("UNCHECKED_CAST")
    private fun contract(): ActivityResultContract<Unit, Any> {
        val type = try { Class.forName("app.pristinepage.capture.PhotoPickerContract") }
        catch (_: ClassNotFoundException) { throw AssertionError("Bounded Android photo-picker contract is absent") }
        return type.getConstructor().newInstance() as ActivityResultContract<Unit, Any>
    }
    private fun property(result: Any, name: String): Any = result.javaClass.getMethod("get$name").invoke(result)!!
    private fun clip(vararg uris: Uri): Intent {
        val data = ClipData("Synthetic selection", arrayOf("image/png"), ClipData.Item(uris[0]))
        uris.drop(1).forEach { data.addItem(ClipData.Item(it)) }
        return Intent().apply { clipData = data }
    }

    @Test fun imageIntentUsesInstalledAndroidXAvailabilityAndOpenableFallbackWithoutBroadPermissions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = contract().createIntent(context, Unit)
        assertFalse(intent.action.isNullOrBlank())
        if (intent.action == Intent.ACTION_OPEN_DOCUMENT) {
            assertEquals("image/*", intent.type); assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
            assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
            assertEquals(listOf("image/jpeg", "image/png", "image/webp"), intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)!!.toList())
        } else {
            assertTrue(intent.action!!.contains("PICK_IMAGES"))
        }
        assertFalse(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
    }

    @Test fun returnedClipDataRetainsOrdinalDuplicatesAndOnlyOfferedReadGrantFlags() {
        val intent = clip(b, a, b).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        val result = contract().parseResult(Activity.RESULT_OK, intent)
        assertEquals("Selected", result.javaClass.simpleName)
        assertEquals(listOf(b, a, b), property(result, "Uris"))
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION, property(result, "OfferedReadFlags"))
    }

    @Test fun singleDataUriIsAcceptedWithoutInventingAPersistableReadGrant() {
        val result = contract().parseResult(Activity.RESULT_OK, Intent().setData(a))
        assertEquals("Selected", result.javaClass.simpleName); assertEquals(listOf(a), property(result, "Uris")); assertEquals(0, property(result, "OfferedReadFlags"))
    }

    @Test fun canceledResultIsDistinctFromMalformedSuccess() {
        assertEquals("Canceled", contract().parseResult(Activity.RESULT_CANCELED, null).javaClass.simpleName)
        for (intent in listOf(null, Intent())) {
            val result = contract().parseResult(Activity.RESULT_OK, intent)
            assertEquals("Rejected", result.javaClass.simpleName); assertEquals(OperationErrorCode.INVALID_INPUT, (property(result, "Error") as OperationError).code)
        }
    }

    @Test fun fallbackOverSelectionCannotSilentlyImportFirstHundredItems() {
        val result = contract().parseResult(Activity.RESULT_OK, clip(*Array(101) { a }))
        assertEquals("Rejected", result.javaClass.simpleName); assertEquals(OperationErrorCode.RESOURCE_LIMIT, (property(result, "Error") as OperationError).code)
    }

    @Test fun textOnlyClipAndNonContentReferencesCannotBecomePrivateFileImports() {
        val textOnly = Intent().apply { clipData = ClipData.newPlainText("Synthetic", "file:///private/source") }
        for (intent in listOf(textOnly, Intent().setData(Uri.parse("file:///private/source")), Intent().setData(Uri.parse("https://outside.invalid/source")))) {
            val result = contract().parseResult(Activity.RESULT_OK, intent)
            assertEquals("Rejected", result.javaClass.simpleName); assertEquals(OperationErrorCode.INVALID_INPUT, (property(result, "Error") as OperationError).code)
        }
    }

    @Test fun clipDataIsAuthoritativeWhenIntentAlsoHasAnUnrelatedDataUri() {
        val result = contract().parseResult(Activity.RESULT_OK, clip(b, a).setData(Uri.parse("content://fixture.documents/not-selected")))
        assertEquals(listOf(b, a), property(result, "Uris"))
    }

    @Test fun actualAndroidUriControllerAdapterRetainsOrderAndChildBindings() = runBlocking {
        val parent = ImportSessionId(UUID.randomUUID().toString()); val children = List(3) { ImportSessionId(UUID.randomUUID().toString()) }
        val seen = mutableListOf<Pair<String, ImportSessionId>>()
        val selection = ImportSelections { id, refs -> assertEquals(parent, id); assertEquals(listOf(b.toString(), a.toString(), b.toString()), refs); ImportBatch(parent, children) }
        val ingest = object : BoundSourceIngest {
            override suspend fun ingestBoundProvider(boundReference: String, session: ImportSessionId): IngestResult { seen += boundReference to session; return IngestResult.Pending(session, FileTransactionState.INTENT) }
        }
        val results = PhotoImportController(selection, ingest).import(listOf(b, a, b), parent)
        assertEquals(listOf(b.toString(), a.toString(), b.toString()).zip(children), seen)
        assertEquals(children.map { IngestResult.Pending(it, FileTransactionState.INTENT) }, results)
    }
}
