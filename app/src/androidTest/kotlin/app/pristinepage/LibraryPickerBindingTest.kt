package app.pristinepage

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.capture.PhotoImportRequest
import app.pristinepage.capture.PhotoImportKind
import app.pristinepage.capture.PhotoPickerRegistration
import app.pristinepage.data.library.LibraryActivation
import app.pristinepage.data.model.ImportSessionId
import app.pristinepage.library.*
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Tests our Activity-owned binding against actual AndroidX queued raw results and Android Parcel. This is not an actual Activity recreation, external picker or platform URI-grant experiment. */
@RunWith(AndroidJUnit4::class)
class LibraryPickerBindingTest {
    @Test fun libraryBackupRegistryDeliversOnlyTheLaunchedWritableSelection() {
        val activation = LibraryActivation("aaaaaaaa-aaaa-4aaa-8aaa-000000000021", 2)
        val owner = app.pristinepage.library.LibraryBackupConfirmation()
        val ticket = checkNotNull(owner.confirm(activation))
        val registry = RecordingRegistry()
        val tree = android.provider.DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Documents/SyntheticBackup")
        val delivered = mutableListOf<app.pristinepage.library.LibraryBackupPickerRegistration.Delivery>()
        main {
            app.pristinepage.library.LibraryBackupPickerRegistration(registry, owner, ticket, delivered::add).use { registration ->
                assertFalse(registration.launch(activation.copy(number = 1)))
                assertTrue(registration.launch(activation))
                assertFalse(registration.launch(activation))
                val result = Intent().setData(tree).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                assertTrue(registry.dispatchResult(registry.codes.single(), Activity.RESULT_OK, result))
                val exact = delivered.single()
                assertSame(ticket, exact.ticket)
                assertEquals(tree, (exact.selection as app.pristinepage.library.LibraryFolderPickerContract.Picked).uri)
                assertTrue(exact.consume(activation))
                assertFalse(exact.consume(activation))
            }
        }
    }


    @Test fun savedEditorNeedsItsIssuingActivationAndLegacyIdsCannotReopen() {
        val identity = app.pristinepage.editor.EditorIdentity(
            app.pristinepage.data.model.DocumentId("aaaaaaaa-aaaa-4aaa-8aaa-000000000011"),
            app.pristinepage.data.model.PageId("aaaaaaaa-aaaa-4aaa-8aaa-000000000012"),
            LibraryActivation("aaaaaaaa-aaaa-4aaa-8aaa-000000000010", 2))
        val stored = Bundle()
        MainActivity.saveEditorIdentity(stored, identity)
        assertEquals(identity, MainActivity.restoreEditorIdentity(parcel(stored)))
        val key = stored.keySet().single()
        checkNotNull(stored.getBundle(key)).remove("activation")
        assertTrue(runCatching { MainActivity.restoreEditorIdentity(parcel(stored)) }.isFailure)
        val legacy = Bundle().apply {
            putBundle("pristinepage.editor.identity.v1", Bundle().apply {
                putString("document", identity.documentId.value)
                putString("page", identity.pageId.value)
            })
        }
        assertNull(MainActivity.restoreEditorIdentity(parcel(legacy)))
    }
    @Test fun parceledQueuedRawResultIsConsumedSynchronouslyDuringRestoredRegistrationWithoutRelaunch() {
        val batch = ImportSessionId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        val request = PhotoImportRequest(batch.value)
        val old = RecordingRegistry(); val saved = Bundle()
        main {
            val registration = PhotoPickerRegistration(old, request) { _, _ -> throw AssertionError("Detached owner consumed pending result") }
            registration.launch(); val code = old.codes.single(); registration.close()
            assertTrue(old.dispatchResult(code, Activity.RESULT_OK, Intent().setData(Uri.parse("content://fixture.documents/queued"))))
            old.onSaveInstanceState(saved)
        }
        val restoredState = parcel(saved)
        val restoredRequest = PhotoImportRequest.restore(parcel(request.save()))!!
        val registry = RecordingRegistry(); val session = Session(restoredRequest, false)
        main { registry.onRestoreInstanceState(restoredState) }
        val binding = binding(registry, session)
        try {
            main { synchronize(binding) }
            val result = session.events.single() as LibraryEvent.PhotoResult
            assertEquals(batch.value, result.request.batchId)
            assertEquals(listOf("content://fixture.documents/queued"), (result.selection as LibraryPhotoResult.Selected).references)
            assertTrue(registry.codes.isEmpty()); assertNull(session.identity().pending)
            assertEquals(1, session.events.size)
        } finally { main { binding.close() } }
    }

    @Test fun launchIsClaimedOnlyOnceAndARealLaunchFailureRemainsBoundToItsExactRequest() {
        val request = PhotoImportRequest("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
        val registry = RecordingRegistry().apply { failLaunch = true }; val session = Session(request, true)
        val binding = binding(registry, session)
        try {
            main { synchronize(binding); synchronize(binding) }
            val failed = session.events.single() as LibraryEvent.PickerFailed
            assertEquals(request.batchId, failed.request.batchId); assertEquals(1, registry.codes.size)
            assertFalse(session.state.value.launchPending)
        } finally { main { binding.close() } }
    }

    @Test fun restoredScopedFilesRegistrationClaimsOnlyItsQueuedResult() {
        val id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val issuedAt = LibraryActivation("aaaaaaaa-aaaa-4aaa-8aaa-000000000011", 2)
        val request = PhotoImportRequest(id, PhotoImportKind.FILES, issuedAt)
        val registry = RecordingRegistry()
        val saved = Bundle()
        main {
            listOf(PhotoImportRequest(id), PhotoImportRequest(id, PhotoImportKind.FILES, issuedAt.copy(number = 1)),
                PhotoImportRequest(id, PhotoImportKind.GALLERY, issuedAt), request).forEachIndexed { index, issued ->
                val registration = PhotoPickerRegistration(registry, issued) { _, _ -> fail("Detached registry consumed a queued result") }
                registration.launch()
                registration.close()
                assertTrue(registry.dispatchResult(registry.codes.last(), Activity.RESULT_OK,
                    Intent().setData(Uri.parse("content://fixture.documents/picked-$index"))))
            }
            assertEquals(4, registry.codes.distinct().size)
            registry.onSaveInstanceState(saved)
        }
        val appState = Bundle()
        MainActivity.saveLibraryIdentity(appState, LibraryIdentity(pending = ImportSessionId(id), kind = request.kind, issuedAt = issuedAt),
            allowPendingResult = true)
        val restoredIdentity = MainActivity.restoreLibraryIdentity(parcel(appState))
        val state = Session(PhotoImportRequest(checkNotNull(restoredIdentity.pending).value, restoredIdentity.kind, restoredIdentity.issuedAt), false)
        val reopened = RecordingRegistry()
        main { reopened.onRestoreInstanceState(parcel(saved)) }
        val binding = binding(reopened, state)
        try {
            main { synchronize(binding); synchronize(binding) }
            val delivered = state.events.single() as LibraryEvent.PhotoResult
            assertTrue(request.matches(delivered.request))
            assertEquals(listOf("content://fixture.documents/picked-3"), (delivered.selection as LibraryPhotoResult.Selected).references)
            assertTrue(reopened.codes.isEmpty())
        } finally { main { binding.close() } }
        checkNotNull(appState.getBundle("pristinepage.library.identity.v1")).remove("acquisition")
        assertTrue(runCatching { MainActivity.restoreLibraryIdentity(parcel(appState)) }.exceptionOrNull() is IllegalArgumentException)
    }

    private class Session(request: PhotoImportRequest, launch: Boolean) : LibrarySession {
        override val state = MutableStateFlow(LibraryState(loading = false, request = request, launchPending = launch, phase = if (launch) ImportPhase.WAITING_PICKER else ImportPhase.INTERRUPTED))
        val events = mutableListOf<LibraryEvent>()
        override fun identity() = LibraryIdentity(pending = state.value.request?.let { ImportSessionId(it.batchId) })
        override fun claimPickerLaunch(batchId: ImportSessionId): Boolean {
            if (state.value.request?.batchId != batchId.value || !state.value.launchPending) return false
            state.value = state.value.copy(launchPending = false); return true
        }
        override fun accept(event: LibraryEvent) { events += event; state.value = state.value.copy(request = null, launchPending = false) }
    }
    private class RecordingRegistry : ActivityResultRegistry() {
        val codes = mutableListOf<Int>(); var failLaunch = false
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            codes += requestCode
            if (failLaunch) throw ActivityNotFoundException("Synthetic picker unavailable")
        }
    }
    private companion object {
        fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
        fun parcel(bundle: Bundle): Bundle = Parcel.obtain().let { p ->
            try { p.writeBundle(bundle); p.setDataPosition(0); p.readBundle(LibraryPickerBindingTest::class.java.classLoader)!! }
            finally { p.recycle() }
        }
        fun binding(registry: ActivityResultRegistry, session: LibrarySession): AutoCloseable {
            val type = try { Class.forName("app.pristinepage.capture.LibraryPickerBinding") }
            catch (_: ClassNotFoundException) { throw AssertionError("Activity-owned library picker binding is absent") }
            return try { type.getConstructor(ActivityResultRegistry::class.java, LibrarySession::class.java).newInstance(registry, session) as AutoCloseable }
            catch (failure: InvocationTargetException) { throw failure.targetException }
        }
        fun synchronize(binding: AutoCloseable) {
            try { binding.javaClass.getMethod("synchronize").invoke(binding) }
            catch (failure: InvocationTargetException) { throw failure.targetException }
        }
    }
}
