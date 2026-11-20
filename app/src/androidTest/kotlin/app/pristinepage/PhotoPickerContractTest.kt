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

    @Test fun cameraStageIsPrivateReadOnlyAndPreservesEveryByte() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val provider = context.packageManager.resolveContentProvider("${context.packageName}.capture-staging", 0)!!
        assertFalse(provider.exported)
        assertFalse(provider.grantUriPermissions)
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 7, 0xFF.toByte(), 0xD9.toByte())
        val stage = app.pristinepage.capture.CaptureStage(context, id)
        try {
            stage.create().writeBytes(bytes)
            assertArrayEquals(bytes, context.contentResolver.openInputStream(stage.uri)!!.use { it.readBytes() })
            assertThrows(java.io.FileNotFoundException::class.java) { context.contentResolver.openFileDescriptor(stage.uri, "w")?.close() }
            assertThrows(java.io.FileNotFoundException::class.java) { context.contentResolver.openInputStream(stage.uri.buildUpon().appendPath("extra").build())?.close() }
        } finally { stage.discard() }
        assertThrows(java.io.FileNotFoundException::class.java) { context.contentResolver.openInputStream(stage.uri)?.close() }
    }

    @Test fun galleryNeverSilentlyOpensDocumentsAndFilesAlwaysDoes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val gallery = app.pristinepage.capture.PhotoPickerContract().createIntent(context, Unit)
        if (androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context)) {
            assertTrue(gallery.action!!.contains("PICK_IMAGES"))
        } else {
            assertEquals(Intent.ACTION_PICK, gallery.action)
            assertEquals("image/*", gallery.type)
        }
        val files = app.pristinepage.capture.PhotoPickerContract(app.pristinepage.capture.PhotoImportKind.FILES).createIntent(context, Unit)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, files.action)
        assertTrue(files.hasCategory(Intent.CATEGORY_OPENABLE))
        assertTrue(files.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
    }
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

    @Test fun imageIntentUsesInstalledAndroidXAvailabilityAndGalleryFallbackWithoutBroadPermissions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = contract().createIntent(context, Unit)
        assertFalse(intent.action.isNullOrBlank())
        if (intent.action == Intent.ACTION_PICK) {
            assertEquals("image/*", intent.type)
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
