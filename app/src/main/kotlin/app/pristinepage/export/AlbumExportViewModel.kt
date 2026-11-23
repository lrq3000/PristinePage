package app.pristinepage.export

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pristinepage.ApplicationImports
import app.pristinepage.core.geometry.ExportSizing
import app.pristinepage.core.geometry.GeometrySizing
import app.pristinepage.core.pipeline.RenderPurpose
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.NamedAlbumExports
import app.pristinepage.image.export.AlbumArtifactEncoder
import app.pristinepage.image.export.AlbumJpegQuality
import app.pristinepage.library.AlbumExportReview
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class ExportSize(val edge: Int?) { NORMAL(null), COMPACT(1024), SMALL(640) }
internal enum class ExportNotice { SAVED, SENT_WITHOUT_READBACK, NEEDS_VERIFICATION, PARTIAL_OUTPUT, CHOOSER_OPEN_FAILED, STOPPED_SHARING }
internal data class ExportPagePlan(val page: EditablePage, val position: Int)

internal data class AlbumExportState(
    val review: AlbumExportReview? = null,
    val pages: List<ExportPagePlan> = emptyList(),
    val format: ExportFormat? = null,
    val size: ExportSize = ExportSize.COMPACT,
    val quality: AlbumJpegQuality = AlbumJpegQuality.BALANCED,
    val filename: String? = null,
    val busy: Boolean = false,
    val waitingForTarget: Boolean = false,
    val publicationId: String? = null,
    val error: OperationErrorCode? = null,
    val notice: ExportNotice? = null,
    val shared: Boolean = false,
    val launch: Intent? = null,
)

internal class AlbumExportViewModel(application: Application, private val imports: suspend () -> ApplicationImports) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()
    private val current = MutableStateFlow(AlbumExportState())
    val state = current.asStateFlow()
    private data class Completed(val store: NamedAlbumExports, val ready: NamedAlbumExports.Ready, val name: String)
    private data class Share(val id: String, val uri: Uri, val file: File, val expires: Long)
    private var completed: Completed? = null
    private var publication: NamedAlbumExports.Publication? = null
    private var share: Share? = null
    private var worker: Job? = null

    fun review(overview: DocumentOverview, selected: Set<PageId>?) {
        if (current.value.review != null || selected?.isEmpty() == true) return
        val review = AlbumExportReview.prepare(overview, selected) ?: return
        val format = if (review.pages.size == 1) ExportFormat.JPEG else ExportFormat.IMAGE_ZIP
        current.value = AlbumExportState(review = review, format = format)
        run {
            val graph = imports()
            val pages = review.pages.map {
                val page = graph.store.editablePages.load(review.documentId, it.page.pageId)
                if (page.documentRevision != review.expectedDocumentRevision || page.recipeRevision != it.page.recipeRevision ||
                    page.sourceId != it.page.sourceId || page.pageId != it.page.pageId) {
                    throw RepositoryFailure(OperationError(OperationErrorCode.REVISION_CONFLICT, false, "named_export_review_changed", null))
                }
                ExportPagePlan(page, it.page.ordinal + 1)
            }
            current.value = current.value.copy(pages = pages)
        }
    }

    fun format(value: ExportFormat) {
        val review = current.value.review ?: return
        if (!mayEdit() || value !in listOf(ExportFormat.PDF, if (review.pages.size == 1) ExportFormat.JPEG else ExportFormat.IMAGE_ZIP)) return
        current.value = current.value.copy(format = value, error = null)
    }

    fun size(value: ExportSize) {
        if (mayEdit()) current.value = current.value.copy(size = value, error = null)
    }

    fun quality(value: AlbumJpegQuality) {
        if (mayEdit() && current.value.format != ExportFormat.PDF) current.value = current.value.copy(quality = value, error = null)
    }

    fun generate() {
        val state = current.value
        val names = state.review ?: return
        val format = state.format ?: return
        if (!mayEdit() || state.pages.size != names.pages.size) return
        val filename = name(names, format)
        run {
            val dimensions = exportOutputs(state.pages, state.size)
            val settings = ExportSettings(format, dimensions, AlbumArtifactEncoder.PDF.takeIf { format == ExportFormat.PDF },
                state.quality.settings.takeIf { format == ExportFormat.JPEG || format == ExportFormat.IMAGE_ZIP }, emptyList(),
                AlbumExportScope(names.albumTitle, state.pages.map { it.position }))
            val graph = imports()
            val port = graph.store.albumExports
            // The renderer and its budget belong to the same graph as the editor; no preview pixels are exported.
            val encoder = AlbumArtifactEncoder(File(context.cacheDir.canonicalFile, "album-export-scratch"), graph.store.committedSources,
                graph.renderer, graph.budget)
            val result = port.export(names.documentId, names.expectedDocumentRevision, settings, AlbumArtifactEncoder.PROFILE, encoder)
            check(result.filename == filename)
            completed = Completed(port, result, filename)
            current.value = current.value.copy(filename = filename)
        }
    }

    fun saveAs() {
        val result = completed ?: return
        if (current.value.waitingForTarget) return
        run {
            publication = result.store.prepareSaveAs(result.ready)
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(result.ready.mime).putExtra(Intent.EXTRA_TITLE, result.name)
            current.value = current.value.copy(waitingForTarget = true, publicationId = checkNotNull(publication).id, launch = intent)
        }
    }

    fun result(publicationId: String, code: Int, data: Intent?) {
        val pending = publication?.takeIf { it.id == publicationId } ?: return
        val ready = completed ?: return
        publication = null // A stale or duplicate Activity Result cannot write any URI.
        current.value = current.value.copy(waitingForTarget = false, publicationId = null)
        val uri = data?.data
        val flags = data?.flags ?: 0
        val eligible = code == Activity.RESULT_OK && uri != null && uri.scheme == "content" && uri.authority != null &&
            !uri.authority.orEmpty().startsWith("${context.packageName}.") && uri.toString().length <= 4096 &&
            (flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0 &&
            DocumentsContract.isDocumentUri(context, uri) &&
            context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
        run {
            if (!eligible) {
                ready.store.updateSaveAs(pending, "WAITING_DESTINATION", "CANCELED")
                if (code == Activity.RESULT_OK) current.value = current.value.copy(error = OperationErrorCode.PERMISSION_LOST)
            } else publish(ready, pending, checkNotNull(uri), flags)
        }
    }

    private suspend fun publish(result: Completed, pending: NamedAlbumExports.Publication, uri: Uri, flags: Int) {
        val reference = uri.toString()
        val resolver = context.contentResolver
        result.store.updateSaveAs(pending, "WAITING_DESTINATION", "WRITING", reference)
        var sent = 0L
        var closed = false
        try {
            result.store.copy(result.ready) { source, active ->
                // Do not request truncation. An unseekable or nonempty document is not a safe new Save as destination.
                val descriptor = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("Destination unavailable")
                if (descriptor.statSize != 0L) {
                    descriptor.close()
                    throw IOException("Destination is not a new empty file")
                }
                ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { sink ->
                    transferExactExport(source, sink, result.ready.byteCount, result.ready.sha256, active) { sent += it }
                    sink.flush()
                }
                closed = true
            }
            val readback = if (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) false else withContext(Dispatchers.IO) {
                val scope = currentCoroutineContext()
                val stream = try { resolver.openInputStream(uri) } catch (_: SecurityException) { null }
                stream?.use { transferExactExport(it, null, result.ready.byteCount, result.ready.sha256, { scope.ensureActive() }) }
                stream != null
            }
            result.store.updateSaveAs(pending, "WRITING", if (readback) "READBACK_VERIFIED" else "OUTPUT_COMPLETED", reference, sent, readback)
            current.value = current.value.copy(notice = if (readback) ExportNotice.SAVED else ExportNotice.SENT_WITHOUT_READBACK)
        } catch (failure: Exception) {
            val code = when (failure) {
                is CancellationException -> OperationErrorCode.CANCELED
                is RepositoryFailure -> failure.error.code
                is SecurityException -> OperationErrorCode.PERMISSION_LOST
                else -> OperationErrorCode.STORAGE_FAILED
            }
            // Never delete/overwrite a provider file on an ambiguous result. Keep the verified private artifact.
            withContext(NonCancellable) {
                val next = if (closed && sent == result.ready.byteCount) "NEEDS_VERIFICATION" else "PARTIAL_EXTERNAL"
                runCatching { result.store.updateSaveAs(pending, "WRITING", next, reference, sent, error = code) }
            }
            if (failure is CancellationException) throw failure
            current.value = current.value.copy(notice = if (closed) ExportNotice.NEEDS_VERIFICATION else ExportNotice.PARTIAL_OUTPUT)
        }
    }

    fun share() {
        val result = completed ?: return
        if (current.value.waitingForTarget) return
        run {
            val granted = share?.takeIf { System.currentTimeMillis() < it.expires } ?: createShare(result).also { share = it }
            val intent = Intent(Intent.ACTION_SEND).setType(result.ready.mime).putExtra(Intent.EXTRA_STREAM, granted.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newUri(context.contentResolver, result.name, granted.uri)
            current.value = current.value.copy(shared = true, launch = Intent.createChooser(intent, null))
        }
    }

    private suspend fun createShare(result: Completed): Share {
        require(FinishedExportProvider.safeFilename(result.name))
        val id = UUID.randomUUID().toString()
        val root = File(context.filesDir.canonicalFile, "shared-exports")
        val directory = File(root, id)
        val file = File(directory, result.name)
        var ownsDirectory = false
        var ownsFile = false
        var recorded = false
        try {
            result.store.copy(result.ready) { source, active ->
                require(root.absoluteFile == root.canonicalFile)
                Files.createDirectories(root.toPath())
                Files.createDirectory(directory.toPath())
                ownsDirectory = true
                check(file.createNewFile())
                ownsFile = true
                file.outputStream().use { sink ->
                    transferExactExport(source, sink, result.ready.byteCount, result.ready.sha256, active)
                    sink.fd.sync()
                }
                file.inputStream().use { transferExactExport(it, null, result.ready.byteCount, result.ready.sha256, active) }
            }
            val expires = result.store.recordShare(result.ready, id, file.name)
            recorded = true
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.finished-exports", file)
            return Share(id, uri, file, expires)
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                if (recorded) runCatching { result.store.revokeShare(result.ready, id) }
                if (ownsFile) runCatching { file.delete() }
                if (ownsDirectory) runCatching { directory.delete() }
            }
            throw failure
        }
    }

    fun revokeShare() {
        val result = completed ?: return
        val granted = share ?: return
        run {
            result.store.revokeShare(result.ready, granted.id)
            share = null
            current.value = current.value.copy(shared = false, notice = ExportNotice.STOPPED_SHARING)
            context.revokeUriPermission(granted.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            withContext(Dispatchers.IO) {
                if (granted.file.exists() && !granted.file.delete()) throw IOException("Share copy cleanup failed")
                granted.file.parentFile?.delete()
            }
        }
    }

    fun consumeLaunch() { current.value = current.value.copy(launch = null) }
    fun launchFailed() {
        current.value.publicationId?.let { result(it, Activity.RESULT_CANCELED, null) }
        current.value = current.value.copy(notice = ExportNotice.CHOOSER_OPEN_FAILED)
    }

    fun cancel() {
        if (!current.value.waitingForTarget && completed == null) worker?.cancel()
    }

    fun dismiss() {
        if (current.value.busy || current.value.waitingForTarget) return
        completed = null
        publication = null
        share = null // Handed-off copies retain their own expiring read grant.
        current.value = AlbumExportState()
    }

    private fun mayEdit() = !current.value.busy && !current.value.waitingForTarget && completed == null && current.value.review != null

    private fun run(work: suspend () -> Unit) {
        if (current.value.busy) return
        current.value = current.value.copy(busy = true, error = null, notice = null)
        worker = viewModelScope.launch {
            try { work() }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: RepositoryFailure) { current.value = current.value.copy(error = failure.error.code) }
            catch (_: SecurityException) { current.value = current.value.copy(error = OperationErrorCode.PERMISSION_LOST) }
            catch (_: IllegalArgumentException) { current.value = current.value.copy(error = OperationErrorCode.INVALID_INPUT) }
            catch (_: Exception) { current.value = current.value.copy(error = OperationErrorCode.STORAGE_FAILED) }
            finally { current.value = current.value.copy(busy = false) }
        }
    }

    private fun name(review: AlbumExportReview, format: ExportFormat): String = when (format) {
        ExportFormat.PDF -> review.pdfName
        ExportFormat.IMAGE_ZIP -> review.zipName
        ExportFormat.JPEG -> checkNotNull(review.singleJpegName)
        ExportFormat.PNG -> error("Unsupported named format")
    }
}

internal fun exportOutputs(pages: List<ExportPagePlan>, size: ExportSize): List<PageOutputSettings> =
    pages.map { page ->
        val geometry = page.page.recipe.geometry
        PageOutputSettings(page.page.pageId, size.edge?.let { GeometrySizing.output(geometry, RenderPurpose.EXPORT, it) }
            ?: ExportSizing.normal(geometry))
    }
