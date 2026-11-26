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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
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
import app.pristinepage.capture.PhotoImportRequest.Companion.restoreActivation
import app.pristinepage.capture.PhotoImportRequest.Companion.saveActivation
import app.pristinepage.capture.AtelierCaptureViewModel
import app.pristinepage.capture.AtelierCaptureScreen
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.library.LibraryActivation
import app.pristinepage.data.model.DocumentPage
import app.pristinepage.data.model.DocumentSummary
import app.pristinepage.data.model.ImportSessionId
import app.pristinepage.data.model.PageId
import app.pristinepage.design.CarnetTheme
import app.pristinepage.design.AtelierSettingsScreen
import app.pristinepage.design.SettingsPage
import app.pristinepage.editor.EditorIdentity
import app.pristinepage.editor.EditorViewModel
import app.pristinepage.editor.PageEditorScreen
import app.pristinepage.library.LibraryEvent
import app.pristinepage.library.LibraryBackupPickerHost
import app.pristinepage.library.LibraryStoragePicker
import app.pristinepage.library.LibraryStorageSettings
import app.pristinepage.library.LibraryStorageViewModel
import app.pristinepage.library.LibraryBackupViewModel
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
import app.pristinepage.library.OriginalPagePreview
import app.pristinepage.library.LibrarySession
import app.pristinepage.library.ImportPhase
import app.pristinepage.ocr.AlbumOcrPending
import app.pristinepage.ocr.SavedPageOcrReader
import app.pristinepage.ocr.OcrTextSaveHost
import app.pristinepage.ocr.OcrTextSaveViewModel
import app.pristinepage.image.preview.OriginalPreviewBitmap
import app.pristinepage.image.preview.RenderedPreviewBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class MainActivity : ComponentActivity() {
    private var picker: LibraryPickerBinding? = null
    private var observing: Job? = null
    private var session: LibrarySession? = null
    private var editor: EditorViewModel? = null
    private var selectedApplyQualification by mutableStateOf<EditorIdentity?>(null)

    /** Same-process instrumentation only. No intent, restored state or preference may bypass the selected Save settlement gate. */
    internal fun qualifySelectedApplyForTest(identity: EditorIdentity) {
        check(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
        check(selectedApplyQualification == null && editor?.state?.value?.identity == identity)
        selectedApplyQualification = identity
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildPolicy.supportsApi(Build.VERSION.SDK_INT)) { finish(); return }
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        val restored = try { restoreLibraryIdentity(savedInstanceState) to restoreEditorIdentity(savedInstanceState) } catch (_: RuntimeException) {
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
                return ImportLibraryViewModel(ApplicationLibraryActions(applicationContext, app.savedOcrSignals::onPagesSaved, services), restored) as T
            }
        }
        val retained = ViewModelProvider(this, factory)[ImportLibraryViewModel::class.java].session
        val editorFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == EditorViewModel::class.java)
                 return EditorViewModel(services, app.savedOcrSignals::onPagesSaved) { before, after ->
                     retained.reloadAfterLibraryChange(before.issuedAt,
                         LibraryIdentity(selected = after.documentId, selectedPage = after.pageId, selectedAt = after.issuedAt))
                 } as T
            }
        }
        val editorOwner = ViewModelProvider(this, editorFactory)[EditorViewModel::class.java]
        val batchOwner = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == BatchEditViewModel::class.java)
                return BatchEditViewModel(services, app.savedOcrSignals::onPagesSaved, app.savedOcrSignals::requireStartupReconciliation) as T
            }
        })[BatchEditViewModel::class.java]
        val exportOwner = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == AlbumExportViewModel::class.java)
                 return AlbumExportViewModel(app, services) as T
            }
        })[AlbumExportViewModel::class.java]
        val textSave = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == OcrTextSaveViewModel::class.java)
                 return OcrTextSaveViewModel(app, services) as T
            }
        })[OcrTextSaveViewModel::class.java]
        val backup = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == LibraryBackupViewModel::class.java)
                return LibraryBackupViewModel(services) as T
            }
        })[LibraryBackupViewModel::class.java].session
        val storage = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == LibraryStorageViewModel::class.java)
                return LibraryStorageViewModel(applicationContext, services) as T
            }
        })[LibraryStorageViewModel::class.java]
        val acquisition = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == AtelierCaptureViewModel::class.java)
                return AtelierCaptureViewModel(applicationContext, services, app.savedOcrSignals::onPagesSaved) as T
            }
        })[AtelierCaptureViewModel::class.java]
        editor = editorOwner
        if (editorOwner.state.value.identity == null) restoredEditor?.let { editorOwner.open(it) }
        session = retained
        observing?.cancel(); picker?.close()
        val binding = LibraryPickerBinding(activityResultRegistry, retained)
        picker = binding
        binding.synchronize()
        observing = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // A failed or unavailable earlier reconciliation may recover on the next foreground visit.
                launch {
                    try {
                        // Only the library owner initiates Room startup. The foreground OCR hint observes its verified default selection afterward.
                        retained.state.first { it.recovery != null }.activation?.let(app.savedOcrSignals::onStartup)
                    }
                    catch (canceled: CancellationException) { throw canceled }
                    catch (_: Exception) { /* Library startup owns the unavailable state. */ }
                }
                retained.state.collect { binding.synchronize() }
            }
        }
        setContent { AppTheme {
            val libraryState = rememberSaveableStateHolder()
            var settingsPage by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
            val thumbnailPermit = remember { Semaphore(1) }
            val settings by app.atelierPreferences.settings.collectAsStateWithLifecycle()
            val host by editorOwner.state.collectAsStateWithLifecycle()
            val editing by editorOwner.session.collectAsStateWithLifecycle()
             val library by retained.state.collectAsStateWithLifecycle()
             val storageState by storage.state.collectAsStateWithLifecycle()
             LaunchedEffect(storageState.change) {
                 storageState.change?.let { change ->
                     editorOwner.closeEditor()
                     retained.reloadAfterLibraryChange(change.old)
                     storage.acknowledge(change)
                 }
             }
               LaunchedEffect(library.activation, library.selectedFolder) {
                   backup.show(library.activation.takeUnless { library.selectedFolder })
                  textSave.show(library.activation)
                  exportOwner.show(library.activation)
              }
            val batch by batchOwner.state.collectAsStateWithLifecycle()
            val acquiring by acquisition.state.collectAsStateWithLifecycle()
             val camera = library.request?.takeIf { it.kind == PhotoImportKind.CAMERA && library.phase == ImportPhase.WAITING_PICKER }
             val atHome = !acquiring.open && batch.album == null && host.identity == null && camera == null && library.selectedId == null
             BackHandler(enabled = atHome && settingsPage == null && library.restoringSelection) {
                 retained.accept(LibraryEvent.LeaveHomeSearch)
             }
             LaunchedEffect(atHome) {
                 if (!atHome) {
                     backup.leaveSettings()
                     settingsPage = null
                 }
             }
            fun leaveEditor() { editorOwner.closeEditor(); retained.accept(LibraryEvent.RefreshAlbum) }
            fun refreshBatchAlbum(issuing: LibraryActivation, document: DocumentId) {
                retained.refreshIssuedAlbum(issuing, document) { services.loadDefaultAlbum(issuing, document) }
            }
            LaunchedEffect(batch.savedDocument, batch.savedRevision, batch.issuingActivation) {
                val issuing = batch.issuingActivation
                val document = batch.savedDocument
                if (issuing != null && document != null && batch.savedRevision != null) refreshBatchAlbum(issuing, document)
            }
            LaunchedEffect(acquiring.saved, acquiring.activation, library.activation) {
                acquiring.saved?.let { album ->
                    if (library.activation == acquiring.activation) {
                        retained.accept(if (library.selectedId == album) LibraryEvent.RefreshAlbum else LibraryEvent.Open(album))
                    }
                    acquisition.acknowledgeSave()
                }
            }
            if (acquiring.open) {
                val draft = acquiring.draft
                val issuing = checkNotNull(acquiring.activation)
                AtelierCaptureScreen(acquiring, acquisition, activityResultRegistry, settings.showCameraGrid, window,
                    loadOriginal = { source -> services.originalDefaultAtelier(issuing, checkNotNull(draft), source, 1024) },
                    loadQueueTile = { source -> services.originalDefaultAtelier(issuing, checkNotNull(draft), source, 224) })
            }
            else if (batch.album != null) BatchEditScreen(batch, batchOwner, services,
                 onReconcile = { issuing, album -> refreshBatchAlbum(issuing, album.document.documentId) }) { issuing, album, pages ->
                 if (issuing == library.activation) exportOwner.review(issuing, album, pages)
            }
            else if (host.identity == null && camera != null) key(camera.batchId) {
                CaptureScreen(camera, { retained.accept(LibraryEvent.CancelImport) }, settings.showCameraGrid) { still ->
                    retained.submitCapture(LibraryEvent.PhotoResult(camera, still))
                }
            }
            else if (atHome && settingsPage != null) {
                 val ocrReady by app.ocrRuntimeReady.collectAsStateWithLifecycle()
                 AtelierSettingsScreen(checkNotNull(settingsPage), app.atelierPreferences, ocrReady,
                      libraryStorage = {
                           LibraryStorageSettings(storage, backup, library.activation, library.activation.takeIf { library.reconnect })
                      }) {
                      if (storageState.busy) return@AtelierSettingsScreen
                      if (settingsPage == SettingsPage.LIBRARY_STORAGE && it != SettingsPage.LIBRARY_STORAGE) storage.cancelReview()
                     if (settingsPage == SettingsPage.LIBRARY_STORAGE && it != SettingsPage.LIBRARY_STORAGE) backup.leaveSettings()
                     settingsPage = it
                 }
            }
             else if (host.identity == null) libraryState.SaveableStateProvider("atelier-library") {
                 val issuing = library.activation
                 val observeOcrPending = remember(app, issuing) { issuing?.let { AlbumOcrPending.forSavedPage(app, it, services) } }
                 val loadThumbnail: suspend (DocumentSummary, DocumentPage) -> RenderedPreviewBitmap = { album, tile ->
                     // Lazy tiles retain only their small displays. Serialize expensive full-source decoding; the existing process-owned budget still governs every allocation.
                     thumbnailPermit.withPermit {
                          services.renderLibraryThumbnail(checkNotNull(issuing), album, tile)
                     }
                 }
                 // Identical album/page IDs and revisions in a successor are not keys for an old display or in-flight effect.
                 key(issuing) {
                     AtelierLibraryScreen(
                          retained, library, app.atelierPreferences, { services.loadLibraryAlbum(checkNotNull(issuing), it) }, loadThumbnail,
                         onBatch = { activation, album -> batchOwner.open(activation, album) }, onSelectPages = batchOwner::open,
                         onExport = { album -> issuing?.let { exportOwner.review(it, album, null) } },
                         batchUndo = batch.undo?.takeIf { batch.savedDocument == library.selectedId && batch.issuingActivation == library.activation },
                         undoBatch = batchOwner::undoSaved, closeBatchNotice = batchOwner::clearNotice,
                         reconcileBatchUndo = {
                             val activation = batch.issuingActivation
                             val document = batch.savedDocument
                             if (activation != null && document != null) refreshBatchAlbum(activation, document)
                         },
                         batchBusy = batch.saving && batch.issuingActivation == library.activation,
                         batchError = batch.error?.takeIf { batch.savedDocument == library.selectedId && batch.issuingActivation == library.activation },
                         batchUndoUnconfirmed = batch.undoUnconfirmed && batch.savedDocument == library.selectedId && batch.issuingActivation == library.activation,
                         observeOcrPending = observeOcrPending,
                         thumbnailsCurrent = { album ->
                             batch.issuingActivation != library.activation || batch.savedDocument != album.documentId ||
                                 (!batch.saving && album.documentRevision >= (batch.savedRevision ?: 0L))
                         },
                         openAtelier = { title, target ->
                             retained.accept(LibraryEvent.LeaveHomeSearch)
                             issuing?.let { acquisition.open(it, title, target) }
                          }, onReconnect = {
                              settingsPage = SettingsPage.LIBRARY_STORAGE
                          }, onSettings = {
                             retained.accept(LibraryEvent.LeaveHomeSearch)
                             settingsPage = SettingsPage.ROOT
                         },
                     ) { document, page ->
                         issuing?.let {
                             editorOwner.open(EditorIdentity(document, page, it)) {
                                 retained.accept(LibraryEvent.SelectPage(page))
                             }
                         }
                     }
                 }
            }
            else {
                val selected = editing
                 val identity = host.identity
                 val album = library.document?.takeIf { identity != null && it.document.documentId == identity.documentId && library.activation == identity.issuedAt }
                 val loadEditorOriginal: suspend (String) -> OriginalPreviewBitmap = { source ->
                      services.originalLibraryEditor(checkNotNull(identity), source)
                 }
                val orderedPages = album?.pages.orEmpty()
                val index = orderedPages.indexOfFirst { it.pageId == identity?.pageId }
                val fallback = orderedPages.getOrNull(index)
                fun destination(offset: Int): (() -> Unit)? {
                     val issuing = identity ?: return null
                    if (index < 0) return null
                    val target = orderedPages.getOrNull(index + offset) ?: return null
                    return {
                        val current = editorOwner.session.value
                        if (current === selected && current != null && !current.state.value.let { it.dirty || it.saving || it.confirmExit || it.exitReady }) {
                             editorOwner.open(issuing.copy(pageId = target.pageId)) {
                                 retained.accept(LibraryEvent.SelectPage(target.pageId))
                             }
                        }
                    }
                }
                if (selected != null) key(identity) {
                      val issuing = checkNotNull(identity)
                       val editorActivationCurrent = library.activation == issuing.issuedAt && !library.reconnect
                       val defaultEditor = editorActivationCurrent && !library.selectedFolder
                      val savedOcr = remember(app, issuing, defaultEditor) {
                          if (!defaultEditor) null else SavedPageOcrReader(
                             readStatus = { page -> services.readDefaultEditorOcrStatus(issuing, page.recipeRevision) },
                             readText = { page -> services.readDefaultEditorOcrText(issuing, page.recipeRevision) },
                            rerunInFlight = app.savedOcrSignals.rerunInFlight,
                            requestRerun = { observed, defaults ->
                                 app.savedOcrSignals.onRerun(
                                     qualify = { app.savedOcr.resolveRerunConfiguration(observed.configuration, defaults) },
                                     activation = issuing.issuedAt,
                                     commit = { services.rerunDefaultEditorOcr(issuing, observed, it) },
                                )
                            },
                             requestSave = { textSave.save(issuing.issuedAt, it) },
                        )
                    }
                       PageEditorScreen(loadOriginal = loadEditorOriginal, savedOcr = savedOcr, showOcrDiagnostics = settings.ocrDiagnostics,
                          allowSave = defaultEditor || editorActivationCurrent && selectedApplyQualification == issuing,
                        pageNumber = fallback?.ordinal?.plus(1), albumPageCount = orderedPages.size,
                        previousPage = destination(-1), nextPage = destination(1), originalOnLoadFailure = {
                            if (fallback != null) key(fallback) {
                                 OriginalPagePreview(fallback, loadEditorOriginal)
                            }
                        }).Content(selected, ::leaveEditor)
                }
                else {
                    BackHandler { leaveEditor() }
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
                        Text(stringResource(if (host.loading) R.string.editor_opening else R.string.editor_heading))
                        host.error?.let { Text(errorText(it.code)) }
                        if (host.error != null && fallback != null) key(fallback) {
                             OriginalPagePreview(fallback, loadEditorOriginal)
                        }
                        Button(onClick = ::leaveEditor) { Text(stringResource(R.string.editor_back)) }
                         if (host.error != null) Button(onClick = { host.identity?.let { editorOwner.open(it) } }) { Text(stringResource(R.string.editor_retry_load)) }
                    }
                }
            }
            AlbumExportDialog(exportOwner)
             OcrTextSaveHost(textSave, activityResultRegistry)
             LibraryBackupPickerHost(backup, activityResultRegistry, allowLaunch = atHome && settingsPage == SettingsPage.LIBRARY_STORAGE)
             LibraryStoragePicker(storage, activityResultRegistry, allowLaunch = atHome && settingsPage == SettingsPage.LIBRARY_STORAGE)
        } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        session?.let { owner ->
            saveLibraryIdentity(outState, owner.identity(), owner.state.value.request?.canDeliver == true)
        }
        editor?.state?.value?.identity?.let { saveEditorIdentity(outState, it) }
    }

    override fun onDestroy() { picker?.close(); picker = null; super.onDestroy() }

    internal companion object {
        private const val SAVED = "pristinepage.library.identity.v1"
        private const val NAVIGATION = "pristinepage.library.navigation.v2"
        private const val SELECTED = "document"
        private const val PENDING = "batch"
        private const val ACQUISITION = "acquisition"
        private const val EDITOR = "pristinepage.editor.identity.v2"
        private const val PAGE = "page"
        fun saveEditorIdentity(out: Bundle, identity: EditorIdentity) {
            out.putBundle(EDITOR, Bundle().apply {
                putString(SELECTED, identity.documentId.value)
                putString(PAGE, identity.pageId.value)
                saveActivation(identity.issuedAt)
            })
        }
        @Suppress("DEPRECATION") fun restoreEditorIdentity(saved: Bundle?): EditorIdentity? {
            val raw = saved?.get(EDITOR) ?: return null
            require(raw is Bundle)
            val document = raw.get(SELECTED) as? String ?: throw IllegalArgumentException("Invalid saved editor identity")
            val page = raw.get(PAGE) as? String ?: throw IllegalArgumentException("Invalid saved editor identity")
            return EditorIdentity(DocumentId(document), PageId(page), requireNotNull(raw.restoreActivation()))
        }
        fun saveLibraryIdentity(out: Bundle, identity: LibraryIdentity, allowPendingResult: Boolean) {
            val at = identity.selectedAt
            val album = identity.selected
            if (album != null && at != null) out.putBundle(NAVIGATION, Bundle().apply {
                putString(SELECTED, album.value)
                identity.selectedPage?.let { putString(PAGE, it.value) }
                saveActivation(at)
            })
            out.putBundle(SAVED, Bundle().apply {
                val pending = identity.pending
                val issuedAt = identity.issuedAt
                if (allowPendingResult && pending != null && issuedAt != null) {
                    putString(PENDING, pending.value)
                    putString(ACQUISITION, identity.kind.name)
                    saveActivation(issuedAt)
                }
            })
        }

        @Suppress("DEPRECATION") fun restoreLibraryIdentity(saved: Bundle?): LibraryIdentity {
            fun record(key: String): Bundle? {
                val raw = saved?.get(key) ?: return null
                require(raw is Bundle)
                return raw
            }
            fun Bundle.id(key: String): String? =
                if (containsKey(key)) (get(key) as? String ?: throw IllegalArgumentException("Invalid saved identity")) else null
            val pendingState = record(SAVED)
            val pending = pendingState?.id(PENDING)?.let(::ImportSessionId)
            val issuedAt = pendingState?.restoreActivation()
            val route = pendingState?.id(ACQUISITION)?.let(PhotoImportKind::valueOf)
            require(issuedAt == null || (pending != null && route != null))
            // Legacy unscoped result registrations are not restored. Room may still discover the exact pending batch for explicit abandonment.
            val chooser = LibraryIdentity(pending = pending.takeIf { issuedAt != null },
                kind = route ?: PhotoImportKind.GALLERY, issuedAt = issuedAt)
            // Album navigation is optional. Its corruption cannot consume a valid independent Files registration.
            return try {
                val navigation = record(NAVIGATION) ?: return chooser
                chooser.copy(selected = DocumentId(requireNotNull(navigation.id(SELECTED))),
                    selectedPage = navigation.id(PAGE)?.let(::PageId), selectedAt = requireNotNull(navigation.restoreActivation()))
            } catch (_: RuntimeException) { chooser }
        }
    }
}

@Composable private fun AppTheme(content: @Composable () -> Unit) {
    CarnetTheme {
        Surface(Modifier.fillMaxSize(), content = content)
    }
}
