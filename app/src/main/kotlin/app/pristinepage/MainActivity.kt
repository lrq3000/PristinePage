package app.pristinepage

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pristinepage.capture.LibraryPickerBinding
import app.pristinepage.capture.CaptureScreen
import app.pristinepage.capture.PhotoImportKind
import app.pristinepage.capture.AtelierCaptureViewModel
import app.pristinepage.capture.AtelierCaptureScreen
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.DocumentPage
import app.pristinepage.data.model.DocumentSummary
import app.pristinepage.data.model.ImportSessionId
import app.pristinepage.data.model.PageId
import app.pristinepage.design.CarnetTheme
import app.pristinepage.editor.EditorIdentity
import app.pristinepage.editor.EditorViewModel
import app.pristinepage.editor.PageEditorScreen
import app.pristinepage.library.LibraryEvent
import app.pristinepage.library.errorText
import app.pristinepage.editor.BatchEditScreen
import app.pristinepage.editor.BatchEditViewModel
import app.pristinepage.export.AlbumExportDialog
import app.pristinepage.export.AlbumExportViewModel
import androidx.compose.runtime.LaunchedEffect
import app.pristinepage.library.ApplicationLibraryActions
import app.pristinepage.library.ImportLibraryViewModel
import app.pristinepage.library.LibraryIdentity
import app.pristinepage.library.AtelierLibraryScreen
import app.pristinepage.library.AtelierThumbnailLoader
import app.pristinepage.library.OriginalPagePreview
import app.pristinepage.library.LibrarySession
import app.pristinepage.library.ImportPhase
import app.pristinepage.image.preview.OriginalPreviewBitmap
import app.pristinepage.image.preview.RenderedPreviewBitmap
import app.pristinepage.image.preview.RenderedPreviewBitmapLoader
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class MainActivity : ComponentActivity() {
    private var picker: LibraryPickerBinding? = null
    private var observing: Job? = null
    private var session: LibrarySession? = null
    private var editor: EditorViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildPolicy.supportsApi(Build.VERSION.SDK_INT)) { finish(); return }
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
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
        val app = application as PristinePageApplication
        val services = app.services
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == ImportLibraryViewModel::class.java)
                return ImportLibraryViewModel(ApplicationLibraryActions(applicationContext, services::imports), restored) as T
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
        val batchOwner = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == BatchEditViewModel::class.java)
                return BatchEditViewModel(services::imports) as T
            }
        })[BatchEditViewModel::class.java]
        val exportOwner = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == AlbumExportViewModel::class.java)
                return AlbumExportViewModel(app, services::imports) as T
            }
        })[AlbumExportViewModel::class.java]
        val acquisition = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == AtelierCaptureViewModel::class.java)
                return AtelierCaptureViewModel(applicationContext, services::imports) as T
            }
        })[AtelierCaptureViewModel::class.java]
        editor = editorOwner
        if (editorOwner.state.value.identity == null) restoredEditor?.let { editorOwner.open(it.documentId.value, it.pageId.value) }
        session = retained
        observing?.cancel(); picker?.close()
        val binding = LibraryPickerBinding(activityResultRegistry, retained)
        picker = binding
        binding.synchronize()
        observing = lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { retained.state.collect { binding.synchronize() } } }
        val loadOriginal: suspend (String) -> OriginalPreviewBitmap = { source ->
            services.imports().originalPreview(source)
        }
        setContent { AppTheme {
            val libraryState = rememberSaveableStateHolder()
            val thumbnailPermit = remember { Semaphore(1) }
            val settings by app.atelierPreferences.settings.collectAsStateWithLifecycle()
            val host by editorOwner.state.collectAsStateWithLifecycle()
            val editing by editorOwner.session.collectAsStateWithLifecycle()
            val library by retained.state.collectAsStateWithLifecycle()
            val batch by batchOwner.state.collectAsStateWithLifecycle()
            val acquiring by acquisition.state.collectAsStateWithLifecycle()
            val camera = library.request?.takeIf { it.kind == PhotoImportKind.CAMERA && library.phase == ImportPhase.WAITING_PICKER }
            fun leaveEditor() { editorOwner.closeEditor(); retained.accept(LibraryEvent.RefreshAlbum) }
            LaunchedEffect(batch.savedDocument, batch.savedRevision, batch.undo) {
                if (batch.savedDocument != null) retained.accept(LibraryEvent.RefreshAlbum)
            }
            LaunchedEffect(acquiring.saved) {
                acquiring.saved?.let { album ->
                    retained.accept(if (library.selectedId == album) LibraryEvent.RefreshAlbum else LibraryEvent.Open(album))
                    acquisition.acknowledgeSave()
                }
            }
            if (acquiring.open) AtelierCaptureScreen(acquiring, acquisition, activityResultRegistry, settings.showCameraGrid, window, loadOriginal,
                loadQueueTile = { source -> services.imports().originalPreview(source, longEdge = 224) })
            else if (batch.album != null) BatchEditScreen(batch, batchOwner, services::imports) { album, pages -> exportOwner.review(album, pages) }
            else if (host.identity == null && camera != null) key(camera.batchId) {
                CaptureScreen(camera, { retained.accept(LibraryEvent.CancelImport) }, settings.showCameraGrid) { still ->
                    retained.submitCapture(LibraryEvent.PhotoResult(camera, still))
                }
            }
            else if (host.identity == null) libraryState.SaveableStateProvider("atelier-library") {
                val loadThumbnail: suspend (DocumentSummary, DocumentPage) -> RenderedPreviewBitmap = { album, tile ->
                    // Lazy tiles retain only their small displays. Serialize expensive full-source decoding; the existing process-owned budget still governs every allocation.
                    thumbnailPermit.withPermit {
                        val graph = services.imports()
                        AtelierThumbnailLoader(graph.store.editablePages::load, graph.renderer, RenderedPreviewBitmapLoader(graph.budget))
                            .load(album, tile)
                    }
                }
                AtelierLibraryScreen(retained, app.atelierPreferences, { services.imports().store.catalog.load(it) }, loadThumbnail,
                     onBatch = { batchOwner.open(it) }, onSelectPages = batchOwner::open,
                     onExport = { exportOwner.review(it, null) },
                     batchUndo = batch.undo?.takeIf { batch.savedDocument == library.selectedId },
                     undoBatch = batchOwner::undoSaved, closeBatchNotice = batchOwner::clearNotice,
                     batchBusy = batch.saving, batchError = batch.error?.takeIf { batch.savedDocument == library.selectedId },
                     thumbnailsCurrent = { album ->
                         batch.savedDocument != album.documentId ||
                             (!batch.saving && album.documentRevision >= (batch.savedRevision ?: 0L))
                     },
                     openAtelier = acquisition::open) { document, page ->
                    editorOwner.open(document.value, page.value)
                }
            }
            else {
                val selected = editing
                val identity = host.identity
                val album = library.document?.takeIf { it.document.documentId == identity?.documentId }
                val orderedPages = album?.pages.orEmpty()
                val index = orderedPages.indexOfFirst { it.pageId == identity?.pageId }
                val fallback = orderedPages.getOrNull(index)
                fun destination(offset: Int): (() -> Unit)? {
                    val document = identity?.documentId ?: return null
                    if (index < 0) return null
                    val target = orderedPages.getOrNull(index + offset) ?: return null
                    return {
                        val current = editorOwner.session.value
                        if (current === selected && current != null && !current.state.value.let { it.dirty || it.saving || it.confirmExit || it.exitReady }) {
                            retained.accept(LibraryEvent.SelectPage(target.pageId))
                            editorOwner.open(document.value, target.pageId.value)
                        }
                    }
                }
                if (selected != null) key(identity) { PageEditorScreen(leaveAfterApply = true, loadOriginal = loadOriginal,
                    pageNumber = fallback?.ordinal?.plus(1), albumPageCount = orderedPages.size,
                    previousPage = destination(-1), nextPage = destination(1), originalOnLoadFailure = {
                    if (fallback != null) key(fallback) {
                        OriginalPagePreview(fallback, loadOriginal)
                    }
                }).Content(selected, ::leaveEditor) }
                else {
                    BackHandler { leaveEditor() }
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
                        Text(stringResource(if (host.loading) R.string.editor_opening else R.string.editor_heading))
                        host.error?.let { Text(errorText(it.code)) }
                        if (host.error != null && fallback != null) key(fallback) {
                            OriginalPagePreview(fallback, loadOriginal)
                        }
                        Button(onClick = ::leaveEditor) { Text(stringResource(R.string.editor_back)) }
                        if (host.error != null) Button(onClick = { host.identity?.let { editorOwner.open(it.documentId.value, it.pageId.value) } }) { Text(stringResource(R.string.editor_retry_load)) }
                    }
                }
            }
            AlbumExportDialog(exportOwner)
        } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        session?.identity()?.let { identity ->
            outState.putBundle(SAVED, Bundle().apply {
                identity.selected?.let { putString(SELECTED, it.value) }
                identity.pending?.let { putString(PENDING, it.value) }
                if (identity.pending != null) putString(ACQUISITION, identity.kind.name)
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
        const val ACQUISITION = "acquisition"
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
            return LibraryIdentity(id(SELECTED)?.let(::DocumentId), id(PENDING)?.let(::ImportSessionId), id(PAGE)?.let(::PageId), id(ACQUISITION)?.let(PhotoImportKind::valueOf) ?: PhotoImportKind.GALLERY)
        }
    }
}

@Composable private fun AppTheme(content: @Composable () -> Unit) {
    CarnetTheme {
        Surface(Modifier.fillMaxSize(), content = content)
    }
}
