package app.pristinepage.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

/** Activity recreation retains this owner and its foreground copy scope. Clearing it cancels structured work; durable rows/originals remain for discovery/recovery, not an automatic temporary-URI retry. */
class ImportLibraryViewModel(actions: LibraryActions, restored: LibraryIdentity) : ViewModel() {
    val session = ImportLibrarySession(actions, restored, viewModelScope)
}
