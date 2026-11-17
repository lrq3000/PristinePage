package app.pristinepage

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pristinepage.capture.LibraryPickerBinding
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.ImportSessionId
import app.pristinepage.data.model.PageId
import app.pristinepage.editor.EditorIdentity
import app.pristinepage.editor.EditorViewModel
import app.pristinepage.editor.PageEditorScreen
import app.pristinepage.library.LibraryEvent
import app.pristinepage.library.errorText
import app.pristinepage.library.ApplicationLibraryActions
import app.pristinepage.library.ImportLibraryViewModel
import app.pristinepage.library.LibraryIdentity
import app.pristinepage.library.LibraryScreen
import app.pristinepage.library.LibrarySession
import app.pristinepage.image.preview.OriginalPreviewBitmap
import app.pristinepage.image.preview.OriginalPreviewBitmapLoader
import app.pristinepage.image.preview.OriginalPreviewLoader
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var picker: LibraryPickerBinding? = null
    private var observing: Job? = null
    private var session: LibrarySession? = null
    private var editor: EditorViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildPolicy.supportsApi(Build.VERSION.SDK_INT)) { finish(); return }
        enableEdgeToEdge()
        val restored = try { restore(savedInstanceState) to restoreEditor(savedInstanceState) } catch (_: RuntimeException) {
            // Invalid saved identity cannot invent a replacement request/destination. The explicit library action still discovers durable pending insertions without reopening temporary provider references.
            setContent { AppTheme { Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
                Text(stringResource(R.string.error_session_expired))
                Button(onClick = { start(LibraryIdentity()) }) { Text(stringResource(R.string.open_library)) }
            } } }
            return
        }
        start(restored.first, restored.second)
    }

    private fun start(restored: LibraryIdentity, restoredEditor: EditorIdentity? = null) {
        val services = (application as PristinePageApplication).services
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == ImportLibraryViewModel::class.java)
                return ImportLibraryViewModel(ApplicationLibraryActions(services::imports), restored) as T
            }
        }
        val retained = ViewModelProvider(this, factory)[ImportLibraryViewModel::class.java].session
        val editorFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == EditorViewModel::class.java)
                return EditorViewModel(services::imports) as T
            }
        }
        val editorOwner = ViewModelProvider(this, editorFactory)[EditorViewModel::class.java]
        editor = editorOwner
        if (editorOwner.state.value.identity == null) restoredEditor?.let { editorOwner.open(it.documentId.value, it.pageId.value) }
        session = retained
        observing?.cancel(); picker?.close()
        val binding = LibraryPickerBinding(activityResultRegistry, retained)
        picker = binding
        binding.synchronize()
        observing = lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { retained.state.collect { binding.synchronize() } } }
        val loadOriginal: suspend (String) -> OriginalPreviewBitmap = { source ->
            val graph = services.imports()
            // Reuse the process-owned private store, decoder and shared allowance. Screen lifetime owns only the delivered display lease, not another import graph or provider reference.
            OriginalPreviewBitmapLoader(OriginalPreviewLoader(graph.store.committedSources, graph.decoder, graph.budget), graph.budget).load(source)
        }
        setContent { AppTheme {
            val host by editorOwner.state.collectAsStateWithLifecycle()
            val editing by editorOwner.session.collectAsStateWithLifecycle()
            fun leaveEditor() { editorOwner.closeEditor(); retained.accept(LibraryEvent.Refresh) }
            if (host.identity == null) LibraryScreen(retained, loadOriginal) { document, page -> editorOwner.open(document.value, page.value) }
            else {
                val selected = editing
                if (selected != null) PageEditorScreen().Content(selected, ::leaveEditor)
                else {
                    BackHandler { leaveEditor() }
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
                        Text(stringResource(if (host.loading) R.string.editor_opening else R.string.editor_heading))
                        host.error?.let { Text(errorText(it.code)) }
                        Button(onClick = ::leaveEditor) { Text(stringResource(R.string.editor_back)) }
                        if (host.error != null) Button(onClick = { host.identity?.let { editorOwner.open(it.documentId.value, it.pageId.value) } }) { Text(stringResource(R.string.editor_retry_load)) }
                    }
                }
            }
        } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        session?.identity()?.let { identity ->
            outState.putBundle(SAVED, Bundle().apply {
                identity.selected?.let { putString(SELECTED, it.value) }
                identity.pending?.let { putString(PENDING, it.value) }
                identity.selectedPage?.let { putString(PAGE, it.value) }
            })
        }
        editor?.state?.value?.identity?.let { identity ->
            outState.putBundle(EDITOR, Bundle().apply { putString(SELECTED, identity.documentId.value); putString(PAGE, identity.pageId.value) })
        }
    }

    override fun onDestroy() { picker?.close(); picker = null; super.onDestroy() }

    private companion object {
        const val SAVED = "pristinepage.library.identity.v1"
        const val SELECTED = "document"
        const val PENDING = "batch"
        const val EDITOR = "pristinepage.editor.identity.v1"
        const val PAGE = "page"
        @Suppress("DEPRECATION") fun restoreEditor(saved: Bundle?): EditorIdentity? {
            val raw = saved?.get(EDITOR) ?: return null
            require(raw is Bundle)
            val document = raw.get(SELECTED) as? String ?: throw IllegalArgumentException("Invalid saved editor identity")
            val page = raw.get(PAGE) as? String ?: throw IllegalArgumentException("Invalid saved editor identity")
            return EditorIdentity(DocumentId(document), PageId(page))
        }
        @Suppress("DEPRECATION") fun restore(saved: Bundle?): LibraryIdentity {
            val raw = saved?.get(SAVED) ?: return LibraryIdentity()
            require(raw is Bundle)
            fun id(key: String): String? = if (raw.containsKey(key)) (raw.get(key) as? String ?: throw IllegalArgumentException("Invalid saved identity")) else null
            return LibraryIdentity(id(SELECTED)?.let(::DocumentId), id(PENDING)?.let(::ImportSessionId), id(PAGE)?.let(::PageId))
        }
    }
}

@Composable private fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize(), content = content)
    }
}
