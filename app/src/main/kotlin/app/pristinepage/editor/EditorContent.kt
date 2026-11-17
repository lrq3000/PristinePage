package app.pristinepage.editor

import androidx.compose.runtime.Composable

/** The Activity supplies its retained session to the screen; the screen never constructs another store or owns the editor's draft history. */
interface EditorContent {
    @Composable fun Content(session: EditorSession, onExit: () -> Unit)
}
