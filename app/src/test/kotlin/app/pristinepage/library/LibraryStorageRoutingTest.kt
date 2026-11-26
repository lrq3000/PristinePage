package app.pristinepage.library

import app.pristinepage.data.library.AndroidLibraryStartup
import app.pristinepage.data.library.LibraryActivation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryStorageRoutingTest {
    private val active = LibraryActivation("aaaaaaaa-aaaa-4aaa-8aaa-000000000001", 2)

    @Test fun anExactRootOrTipRefusalRequiresReconnectEvenIfTheRecordedModesAreStillRetained() {
        val location = AndroidLibraryStartup.Location(active, "Synthetic folder", false)
        assertTrue(location.requiresLibraryReconnect(active))
        assertFalse(location.requiresLibraryReconnect(active.copy(number = 1)))
        assertFalse(location.requiresLibraryReconnect(null))
    }

    @Test fun missingRecordedAccessStillRequiresReconnectAndDoesNotApplyToDefault() {
        val folder = AndroidLibraryStartup.Location(active, "Synthetic folder", true)
        assertTrue(folder.requiresLibraryReconnect(null))
        assertFalse(AndroidLibraryStartup.Location(active, null, false).requiresLibraryReconnect(active))
    }

    @Test fun aLateSettingsReadCannotReplaceTheLocationOfANewerCutover() = runTest {
        val original = AndroidLibraryStartup.Location(active, null, false)
        val successor = AndroidLibraryStartup.Location(active.copy(number = 3), "Synthetic folder", false)
        val state = MutableStateFlow(LibraryStorageViewModel.State(location = original))
        val held = CompletableDeferred<AndroidLibraryStartup.Location>()
        val refresh = launch(start = CoroutineStart.UNDISPATCHED) { refreshStorageLocation(state) { held.await() } }
        state.value = state.value.copy(location = successor)
        held.complete(original)
        refresh.join()
        assertEquals(successor, state.value.location)
    }
}
