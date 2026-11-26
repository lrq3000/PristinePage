package app.pristinepage.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.pristinepage.R
import app.pristinepage.data.library.LibraryActivation
import app.pristinepage.capture.PhotoImportKind
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.AlbumSearchQuery
import app.pristinepage.design.AtelierPreferences
import app.pristinepage.design.AtelierStyle
import app.pristinepage.design.HomeThumbnailLayout
import app.pristinepage.image.preview.RenderedPreviewBitmap
import app.pristinepage.ocr.AlbumOcrPending
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable internal fun AtelierLibraryScreen(
     session: LibrarySession,
    state: LibraryState,
    preferences: AtelierPreferences,
    loadOverview: suspend (DocumentId) -> DocumentOverview,
    loadThumbnail: suspend (DocumentSummary, DocumentPage) -> RenderedPreviewBitmap,
    onBatch: (LibraryActivation, DocumentOverview) -> Unit = { _, _ -> },
    onSelectPages: (LibraryActivation, DocumentOverview, PageId) -> Unit = { activation, album, _ -> onBatch(activation, album) },
    onExport: (DocumentOverview) -> Unit = {},
    batchUndo: UndoId? = null,
    undoBatch: () -> Unit = {},
    closeBatchNotice: () -> Unit = {},
    batchBusy: Boolean = false,
    batchError: OperationErrorCode? = null,
    batchUndoUnconfirmed: Boolean = false,
    reconcileBatchUndo: () -> Unit = {},
    thumbnailsCurrent: (DocumentSummary) -> Boolean = { true },
    observeOcrPending: ((DocumentId, DocumentPage) -> Flow<AlbumOcrPending.State?>)? = null,
    openAtelier: ((String, DocumentSummary?) -> Unit)? = null,
    onReconnect: () -> Unit = {},
    onSettings: () -> Unit,
    onEdit: (DocumentId, PageId) -> Unit,
) {
    val preferencesState by preferences.settings.collectAsStateWithLifecycle()
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()
    LaunchedEffect(session, preferencesState.order, locale) {
        session.accept(LibraryEvent.SetAlbumSort(preferencesState.order, locale))
    }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    fun nextTitle() = defaultAlbumTitle(template = preferencesState.albumTitleTemplate)
    val home = state.selectedId == null
    val overview = state.document
    val canOpenPage = !state.reconnect && !state.loading && !state.preparingImport && state.request == null && !state.renaming
    val canAcquire = !state.selectedFolder && canOpenPage
    val canClose = state.request == null && !state.preparingImport && !state.renaming
    fun acquire(kind: PhotoImportKind) {
        sheet = null
        if (home) {
            session.accept(LibraryEvent.NewDocument(nextTitle(), importAfterCreate = true, kind = kind))
        } else session.accept(when (kind) {
            PhotoImportKind.CAMERA -> LibraryEvent.BeginCapture
            PhotoImportKind.GALLERY -> LibraryEvent.BeginImport
            PhotoImportKind.FILES -> LibraryEvent.BeginFiles
        })
    }
    BackHandler(enabled = !home && canClose && sheet == null) { session.accept(LibraryEvent.CloseAlbum) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!home) AtelierIcon(R.drawable.ic_atelier_back, R.string.atelier_back, "atelier-home-back", canClose) { session.accept(LibraryEvent.CloseAlbum) }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                if (home) Text(stringResource(R.string.app_name), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val album = state.document?.document
                if (home || album == null) {
                    Text(stringResource(if (home) R.string.atelier_home else R.string.atelier_album), Modifier.semantics { heading() },
                        style = if (home) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineSmall)
                } else {
                    val renameLabel = stringResource(R.string.atelier_rename)
                    val rename = { session.accept(LibraryEvent.ClearRenameResult); sheet = "rename" }
                    TextButton(
                        onClick = rename, enabled = canAcquire, shape = RectangleShape,
                        modifier = Modifier.fillMaxWidth().heightIn(min = AtelierStyle.touchTarget).testTag("atelier-rename")
                            .semantics { onClick(label = renameLabel, action = null) },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(album.title, Modifier.weight(1f).testTag("atelier-album-title").semantics { heading() },
                            style = MaterialTheme.typography.titleLarge, maxLines = 5, overflow = TextOverflow.Ellipsis)
                        Text("✎", Modifier.padding(start = 4.dp).clearAndSetSemantics { })
                    }
                    Text(stringResource(R.string.saved_page_count, album.pageCount), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (home) AtelierIcon(R.drawable.ic_atelier_sort, R.string.atelier_sort, "atelier-sort") { sheet = "sort" }
        }

        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (home) {
            val searching = state.albumSearch.isNotBlank()
            val query = state.albumSearch
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            LaunchedEffect(session, query, lifecycle, state.activation) {
                if (query.isNotBlank()) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    session.observePageSearch(query, state.activation)
                }
            }
            if (state.canSearchAlbums && !state.reconnect) OutlinedTextField(
                value = state.albumSearch,
                onValueChange = { session.accept(LibraryEvent.SetAlbumSearch(it, ZoneId.systemDefault())) },
                label = { Text(stringResource(R.string.home_search_scans)) },
                supportingText = { Text(stringResource(if (state.canSearchPages) R.string.home_search_with_text else R.string.home_search_available_fields)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                trailingIcon = if (state.albumSearch.isEmpty()) null else {{
                    TextButton(onClick = { session.accept(LibraryEvent.SetAlbumSearch("", ZoneId.systemDefault())) }) {
                        Text(stringResource(R.string.home_search_clear))
                    }
                }},
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("home-album-search"),
            )
            val creation = when (preferencesState.order) {
                AlbumOrder.CREATED -> true
                AlbumOrder.MODIFIED -> false
                else -> preferencesState.showCreationInNameSort
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("atelier-album-list"),
                contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text(stringResource(preferencesState.order.label()), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { LibraryFeedback(state, session) }
                if (state.reconnect) item {
                    Column {
                        Text(stringResource(R.string.library_folder_unavailable))
                        TextButton(onClick = onReconnect, modifier = Modifier.heightIn(min = AtelierStyle.touchTarget)
                            .testTag("library-reconnect")) { Text(stringResource(R.string.library_reconnect)) }
                    }
                }
                if (searching && state.pageSearchUnavailable) item {
                    Text(stringResource(R.string.home_page_search_unavailable), Modifier.padding(vertical = 4.dp))
                }
                if (searching && state.albumSearchUnavailable) item {
                    Text(stringResource(R.string.home_album_search_unavailable), Modifier.padding(vertical = 4.dp))
                }
                if (!state.reconnect && state.documents.isEmpty() && state.pageMatches.isEmpty() && !state.loading && !state.hasMore && state.error == null &&
                    !state.albumSearchUnavailable && !state.pageSearchUnavailable) {
                    item { Text(stringResource(if (!searching) R.string.atelier_empty
                        else if (state.canSearchPages) R.string.home_search_no_page_or_album_matches
                        else R.string.home_search_no_matches), Modifier.padding(vertical = 12.dp)) }
                }
                items(state.documents, key = { it.documentId.value }) { album ->
                    AlbumCard(album, creation, preferencesState.homeThumbnailLayout, loadOverview, loadThumbnail,
                        canClose, thumbnailsCurrent(album)) { session.accept(LibraryEvent.Open(album.documentId)) }
                }
                if (searching && state.pageMatches.isNotEmpty()) {
                    item { Text(stringResource(R.string.home_matching_pages), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium) }
                    items(state.pageMatches, key = { "matching-page-${it.page.value}" }) { hit ->
                        TextButton(onClick = { session.accept(LibraryEvent.OpenPageMatch(hit, onEdit)) }, enabled = canAcquire,
                            modifier = Modifier.fillMaxWidth().heightIn(min = AtelierStyle.touchTarget)) {
                            Text(stringResource(R.string.home_matching_page, hit.albumTitle, hit.pageNumber))
                        }
                    }
                }
                if (state.hasMore) item {
                    TextButton(onClick = { session.accept(LibraryEvent.More) }, enabled = !state.loading) {
                        Text(stringResource(if (!searching) R.string.more_documents
                            else if (state.albumSearchUnavailable || state.pageSearchUnavailable) R.string.home_search_retry_more
                            else R.string.home_search_more))
                    }
                }
            }
            HorizontalDivider(color = AtelierStyle.divider)
            Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.atelier_albums), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                 FilledIconButton(onClick = { if (openAtelier == null) sheet = "sources" else openAtelier(nextTitle(), null) }, enabled = canAcquire,
                     modifier = Modifier.size(58.dp).testTag("atelier-new"), shape = CircleShape) {
                    Icon(painterResource(R.drawable.ic_atelier_plus), stringResource(R.string.atelier_new), Modifier.size(29.dp))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    AtelierIcon(R.drawable.ic_atelier_settings, R.string.atelier_settings, "atelier-settings", onClick = onSettings)
                }
            }
        } else {
            val selectedId = state.selectedId
            val batchActivation = state.activation.takeUnless { state.selectedFolder }
            key(selectedId) {
                LazyVerticalGrid(GridCells.Adaptive(148.dp), Modifier.weight(1f).fillMaxWidth().testTag("document-overview"),
                    contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LibraryFeedback(state, session)
                            if (batchUndo != null && batchError == null) {
                                Text(stringResource(R.string.atelier_batch_saved))
                                TextButton(onClick = undoBatch, enabled = !batchBusy, modifier = Modifier.testTag("atelier-batch-undo-saved")) {
                                    Text(stringResource(R.string.atelier_batch_undo_saved))
                                }
                            }
                            if (batchError != null || batchUndoUnconfirmed) {
                                Text(if (batchUndoUnconfirmed) stringResource(R.string.atelier_batch_undo_unconfirmed) else errorText(checkNotNull(batchError)),
                                    color = MaterialTheme.colorScheme.error)
                                if (batchUndoUnconfirmed) TextButton(onClick = reconcileBatchUndo) { Text(stringResource(R.string.atelier_batch_refresh_album)) }
                                TextButton(onClick = closeBatchNotice) { Text(stringResource(R.string.atelier_batch_dismiss)) }
                            }
                            Text(stringResource(R.string.atelier_all_pages), style = MaterialTheme.typography.titleMedium)
                            overview?.document?.updatedAt?.let { Text(stringResource(R.string.atelier_modified_date, date(it))) }
                            if (overview?.pages?.isEmpty() == true) Text(stringResource(R.string.no_saved_pages))
                        }
                    }
                    items(overview?.pages.orEmpty(), key = { it.pageId.value }) { page ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().testTag("original-page-${page.pageId.value}")
                                .semantics { selected = state.selectedPage == page.pageId }
                                 .combinedClickable(enabled = canOpenPage && overview != null, role = Role.Button,
                                     onLongClickLabel = if (batchActivation != null) stringResource(R.string.carnet_select_page) else null,
                                     onLongClick = if (batchActivation != null && overview != null) {
                                         { onSelectPages(batchActivation, overview, page.pageId) }
                                     } else null) {
                                    overview?.let { onEdit(it.document.documentId, page.pageId) }
                                },
                            shape = RoundedCornerShape(AtelierStyle.surfaceRadius), color = MaterialTheme.colorScheme.surface,
                        ) {
                            Column(Modifier.padding(6.dp)) {
                                 val album = checkNotNull(overview).document
                                 val thumbnailSize = Modifier.fillMaxWidth().aspectRatio(1.18f)
                                  if (thumbnailsCurrent(album)) AlbumPageThumbnail(album, page, loadThumbnail, thumbnailSize, observeOcrPending)
                                 else Box(thumbnailSize.background(AtelierStyle.canvas, RoundedCornerShape(4.dp)))
                                Text(stringResource(R.string.page_number, page.ordinal + 1), Modifier.padding(6.dp))
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = AtelierStyle.divider)
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                if (overview != null && overview.pages.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { batchActivation?.let { onBatch(it, overview) } }, enabled = canAcquire && batchActivation != null,
                        modifier = Modifier.heightIn(min = AtelierStyle.touchTarget).testTag("atelier-batch-select")) {
                        Text(stringResource(R.string.atelier_batch_select))
                    }
                    OutlinedButton(onClick = { onExport(overview) }, enabled = canAcquire,
                        modifier = Modifier.heightIn(min = AtelierStyle.touchTarget).testTag("album-export-all")) {
                        Text(stringResource(R.string.album_export_title))
                    }
                }
                Button(onClick = {
                    val album = overview?.document
                     if (openAtelier != null && album != null) openAtelier(album.title, album) else sheet = "sources"
                }, enabled = canAcquire, modifier = Modifier.fillMaxWidth()
                    .heightIn(min = AtelierStyle.touchTarget).testTag("atelier-add-pages")) {
                    Text(stringResource(R.string.atelier_add_pages))
                }
            }
        }
    }
    val stopSheet = { sheet = null }
    when (sheet) {
        "sort" -> AtelierSheet(R.string.atelier_sort, stopSheet) {
            AlbumOrder.entries.forEach { order ->
                val selected = preferencesState.order == order
                Row(Modifier.fillMaxWidth().heightIn(min = AtelierStyle.touchTarget).testTag("atelier-sort-${order.name}")
                    .selectable(selected, role = Role.RadioButton, onClick = { preferences.setOrder(order); stopSheet() }),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected, onClick = null)
                    Text(stringResource(order.label()), Modifier.padding(start = 12.dp))
                }
            }
        }
        "sources" -> AtelierSheet(if (home) R.string.atelier_new else R.string.atelier_add_pages, stopSheet) {
            AcquisitionAction(R.string.take_photo, "take-photo", canAcquire) { acquire(PhotoImportKind.CAMERA) }
            AcquisitionAction(R.string.import_photos, "import-photos", canAcquire) { acquire(PhotoImportKind.GALLERY) }
            AcquisitionAction(R.string.import_files, "import-files", canAcquire) { acquire(PhotoImportKind.FILES) }
            if (home) AcquisitionAction(R.string.new_document, "create-document", canAcquire) {
                stopSheet()
                session.accept(LibraryEvent.NewDocument(nextTitle()))
            }
        }
        "rename" -> overview?.document?.let { album ->
            RenameSheet(album, state, session) { stopSheet() }
        }
    }
}

@Composable
private fun AlbumPageThumbnail(
    album: DocumentSummary, page: DocumentPage,
    load: suspend (DocumentSummary, DocumentPage) -> RenderedPreviewBitmap, modifier: Modifier,
    observePending: ((DocumentId, DocumentPage) -> Flow<AlbumOcrPending.State?>)?,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var status by remember(album.documentId, page.pageId, page.recipeRevision, observePending) {
        mutableStateOf<AlbumOcrPending.State?>(null)
    }
    LaunchedEffect(album.documentId, page.pageId, page.recipeRevision, observePending, lifecycle) {
        if (observePending == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try { observePending(album.documentId, page).collect { status = it } }
            catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { /* Optional status cannot prevent displaying the saved page. */ }
            finally { status = null }
        }
    }
    Box(modifier) {
        val pending = status
        AtelierThumbnail(album, page, load, Modifier.matchParentSize().graphicsLayer { alpha = if (pending == null) 1f else 0.45f })
        if (pending != null) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface).padding(4.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(if (pending == AlbumOcrPending.State.ACTIVE_WAKE) R.string.ocr_tile_pending else R.string.ocr_tile_queued),
                style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable private fun AlbumCard(
    album: DocumentSummary, creation: Boolean, layout: HomeThumbnailLayout,
    load: suspend (DocumentId) -> DocumentOverview,
    thumbnail: suspend (DocumentSummary, DocumentPage) -> RenderedPreviewBitmap,
    enabled: Boolean, thumbnailsCurrent: Boolean, onOpen: () -> Unit,
) {
    var pages by remember(album.documentId, album.documentRevision) { mutableStateOf(emptyList<DocumentPage>()) }
    var failed by remember(album.documentId, album.documentRevision) { mutableStateOf(false) }
    val loader by rememberUpdatedState(load)
    LaunchedEffect(album.documentId, album.documentRevision) {
        if (album.pageCount > 0) {
            try {
                val result = loader(album.documentId)
                currentCoroutineContext().ensureActive()
                if (result.document.documentRevision == album.documentRevision && result.document.documentId == album.documentId) {
                    pages = result.pages
                } else failed = true
            } catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { failed = true }
        }
    }
    val displayed = remember(layout, pages) { layout.pages(pages) }
    Surface(onClick = onOpen, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 94.dp)
        .testTag("atelier-album-${album.documentId.value}"), shape = RectangleShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        if (layout == HomeThumbnailLayout.ROW) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AlbumCardDetails(album, creation)
                if (album.pageCount > 0) {
                    // Metadata is bounded by the album contract. Only composed tiles own a bitmap/render.
                    if (thumbnailsCurrent && displayed.isNotEmpty()) {
                        AlbumThumbnailRow(album, displayed, thumbnail)
                    } else ThumbnailPlaceholder(failed && thumbnailsCurrent)
                }
            }
        } else {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (album.pageCount > 0) {
                    Box(Modifier.size(width = 94.dp, height = 66.dp), contentAlignment = Alignment.CenterStart) {
                        if (!thumbnailsCurrent || displayed.isEmpty()) ThumbnailPlaceholder(failed && thumbnailsCurrent)
                        else displayed.forEachIndexed { index, page ->
                            key(page.pageId) {
                                AtelierThumbnail(album, page, thumbnail, Modifier.offset(x = (4 + 19 * index).dp)
                                    .size(44.dp, 58.dp).graphicsLayer { rotationZ = when (index) { 0 -> -8f; 2 -> 8f; else -> 0f } })
                            }
                        }
                    }
                }
                AlbumCardDetails(album, creation, Modifier.weight(1f))
            }
        }
    }
}

@Composable internal fun AlbumThumbnailRow(
    album: DocumentSummary, pages: List<DocumentPage>,
    thumbnail: suspend (DocumentSummary, DocumentPage) -> RenderedPreviewBitmap,
) {
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().semantics { isTraversalGroup = true }, verticalAlignment = Alignment.CenterVertically) {
        ThumbnailScrollButton(previous = true, enabled = scroll.canScrollBackward) {
            scope.launch { scroll.scrollToItem((scroll.firstVisibleItemIndex - 1).coerceAtLeast(0)) }
        }
        LazyRow(Modifier.weight(1f).testTag("home-thumbnail-row-${album.documentId.value}"), state = scroll,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(pages, key = { it.pageId.value }) { page ->
                AtelierThumbnail(album, page, thumbnail, Modifier.size(48.dp, 60.dp)
                    .testTag("carnet-thumbnail-${page.ordinal}"))
            }
        }
        ThumbnailScrollButton(previous = false, enabled = scroll.canScrollForward) {
            scope.launch { scroll.scrollToItem((scroll.firstVisibleItemIndex + 1).coerceAtMost(pages.lastIndex)) }
        }
    }
}

@Composable private fun ThumbnailScrollButton(previous: Boolean, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick, enabled = enabled, modifier = Modifier.size(AtelierStyle.touchTarget)
        .testTag(if (previous) "carnet-thumbnails-previous" else "carnet-thumbnails-next")) {
        Icon(painterResource(R.drawable.ic_atelier_back),
            stringResource(if (previous) R.string.carnet_thumbnails_previous else R.string.carnet_thumbnails_next),
            modifier = Modifier.graphicsLayer { scaleX = if (previous) 1f else -1f })
    }
}

@Composable private fun ThumbnailPlaceholder(failed: Boolean) {
    if (failed) Text(stringResource(R.string.atelier_thumbnail_failed), style = MaterialTheme.typography.labelSmall)
    else Box(Modifier.size(48.dp, 60.dp).background(AtelierStyle.canvas, RoundedCornerShape(4.dp)))
}

@Composable private fun AlbumCardDetails(album: DocumentSummary, creation: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(album.title, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.saved_page_count, album.pageCount), style = MaterialTheme.typography.bodySmall)
        val timestamp = if (creation) album.createdAt else album.updatedAt
        if (timestamp != null) {
            Text(stringResource(if (creation) R.string.atelier_created_date else R.string.atelier_modified_date, date(timestamp)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun AtelierThumbnail(album: DocumentSummary, page: DocumentPage,
    loader: suspend (DocumentSummary, DocumentPage) -> RenderedPreviewBitmap, modifier: Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val load by rememberUpdatedState(loader)
    var ready by remember(album.documentId, album.documentRevision, page) { mutableStateOf<RenderedPreviewBitmap?>(null) }
    var failed by remember(album.documentId, album.documentRevision, page) { mutableStateOf(false) }
    LaunchedEffect(lifecycle, album.documentId, album.documentRevision, page) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var resource: RenderedPreviewBitmap? = null
            try {
                failed = false
                resource = load(album, page)
                currentCoroutineContext().ensureActive()
                ready = resource
                awaitCancellation()
            } catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { failed = true }
            finally { ready = null; resource?.close() }
        }
    }
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(AtelierStyle.canvas), contentAlignment = Alignment.Center) {
        ready?.let { Image(it.bitmap.asImageBitmap(), stringResource(R.string.atelier_saved_thumbnail_description, page.ordinal + 1),
            Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (failed) Text(stringResource(R.string.atelier_thumbnail_failed), Modifier.padding(3.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable private fun LibraryFeedback(state: LibraryState, session: LibrarySession) {
    Column {
        state.error?.let {
            Text(acquisitionError(it), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { session.accept(LibraryEvent.Refresh) }, enabled = !state.loading) { Text(stringResource(R.string.refresh_library)) }
        }
        if ((state.recovery?.blocked ?: 0) > 0) Text(stringResource(R.string.recovery_attention))
        if (state.request != null) {
            Text(stringResource(when (state.phase) {
                ImportPhase.COPYING -> R.string.import_copying
                ImportPhase.CANCELING -> R.string.import_canceling
                ImportPhase.INTERRUPTED -> R.string.import_interrupted
                else -> R.string.import_waiting
            }))
            TextButton(onClick = { session.accept(LibraryEvent.CancelImport) }, enabled = state.phase != ImportPhase.CANCELING) {
                Text(stringResource(R.string.cancel_import))
            }
        }
        if (state.phase == ImportPhase.CANCELED) Text(stringResource(R.string.import_canceled))
        state.results.forEachIndexed { i, result ->
            when (result) {
                is IngestResult.Committed -> Text(stringResource(R.string.item_saved, i + 1))
                is IngestResult.Pending -> Text(stringResource(R.string.item_pending, i + 1))
                is IngestResult.Rejected -> Text(stringResource(R.string.item_rejected, i + 1, acquisitionError(result.error)))
            }
        }
    }
}

@Composable private fun RenameSheet(album: DocumentSummary, state: LibraryState, session: LibrarySession, onClose: () -> Unit) {
    var text by rememberSaveable(album.documentId.value) { mutableStateOf(album.title) }
    var revision by rememberSaveable(album.documentId.value) { mutableLongStateOf(album.documentRevision) }
    LaunchedEffect(state.renameResult) {
        if (state.renameResult is DocumentMutationResult.Applied) {
            session.accept(LibraryEvent.ClearRenameResult)
            onClose()
        }
    }
    AtelierSheet(R.string.atelier_rename, { if (!state.renaming) { session.accept(LibraryEvent.ClearRenameResult); onClose() } }, canDismiss = !state.renaming) {
        // Reject an over-limit edit before saving it in a Bundle; rejecting the whole edit cannot split a surrogate pair.
        OutlinedTextField(text, { if (it.length <= 160) text = it }, enabled = !state.renaming, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("atelier-title-field"), label = { Text(stringResource(R.string.atelier_title)) })
        val failure = state.renameResult
        when (failure) {
            is DocumentMutationResult.Conflict -> {
                Text(errorText(OperationErrorCode.REVISION_CONFLICT), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { revision = album.documentRevision; session.accept(LibraryEvent.ClearRenameResult) },
                    enabled = !state.loading && album.documentRevision >= failure.actualRevision) {
                    Text(stringResource(R.string.atelier_use_current_revision))
                }
                if (album.documentRevision < failure.actualRevision) {
                    TextButton(onClick = { session.accept(LibraryEvent.RefreshAlbum) }, enabled = !state.loading) {
                        Text(stringResource(R.string.refresh_library))
                    }
                }
            }
            is DocumentMutationResult.Rejected -> Text(acquisitionError(failure.error), color = MaterialTheme.colorScheme.error)
            else -> Unit
        }
        Button(
            enabled = !state.renaming && !state.loading && text.trim().length in 1..160 && failure !is DocumentMutationResult.Conflict,
            onClick = { session.accept(LibraryEvent.RenameAlbum(album.documentId, revision, text)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = AtelierStyle.touchTarget).testTag("atelier-save-title"),
        ) { Text(stringResource(R.string.atelier_save_title)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AtelierSheet(title: Int, onClose: () -> Unit, canDismiss: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val dismissible by rememberUpdatedState(canDismiss)
    val sheet = rememberModalBottomSheetState(confirmValueChange = { it != SheetValue.Hidden || dismissible })
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            content()
            TextButton(onClick = onClose, enabled = canDismiss, modifier = Modifier.heightIn(min = AtelierStyle.touchTarget)) { Text(stringResource(R.string.atelier_close)) }
        }
    }
}

@Composable private fun AcquisitionAction(label: Int, tag: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = AtelierStyle.touchTarget).testTag(tag)) {
        Text(stringResource(label))
    }
}

@Composable private fun AtelierIcon(icon: Int, label: Int, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(AtelierStyle.touchTarget).testTag(tag)) {
        Icon(painterResource(icon), stringResource(label))
    }
}

internal fun AlbumOrder.label() = when (this) {
    AlbumOrder.MODIFIED -> R.string.atelier_sort_modified
    AlbumOrder.CREATED -> R.string.atelier_sort_created
    AlbumOrder.TITLE_AZ -> R.string.atelier_sort_az
    AlbumOrder.TITLE_ZA -> R.string.atelier_sort_za
}

// Keep the user’s local time zone, but do not mix non-English month names into app-owned text.
private fun date(epoch: Long): String = AlbumSearchQuery.displayDate(epoch, ZoneId.systemDefault())
