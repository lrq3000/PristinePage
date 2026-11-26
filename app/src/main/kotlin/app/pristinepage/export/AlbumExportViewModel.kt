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
import app.pristinepage.ApplicationServices
import app.pristinepage.core.geometry.ExportSizing
import app.pristinepage.core.geometry.GeometrySizing
import app.pristinepage.core.pipeline.RenderPurpose
import app.pristinepage.data.library.LibraryActivation
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

internal class AlbumExportViewModel(application: Application, private val services: ApplicationServices) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()
    private val current = MutableStateFlow(AlbumExportState())
    val state = current.asStateFlow()
    private data class Completed(val issuedAt: LibraryActivation, val ready: NamedAlbumExports.Ready, val name: String)
    private data class Share(val id: String, val uri: Uri, val file: File, val expires: Long)
    private data class Selection(val code: Int, val data: Intent?)
    private class Picker(val id: String, val result: CompletableDeferred<Selection?> = CompletableDeferred())
    private var completed: Completed? = null
    private var picker: Picker? = null
    private var share: Share? = null
    private var worker: Job? = null
    private var activation: LibraryActivation? = null

    fun show(issuedAt: LibraryActivation?) {
        if (activation == issuedAt) return
        activation = issuedAt
        completed = null
        picker?.result?.complete(null) // Let the issuing lease finish its own journal, without binding a target.
        share = null
        current.value = AlbumExportState(busy = current.value.busy)
    }

    fun review(issuedAt: LibraryActivation, overview: DocumentOverview, selected: Set<PageId>?) {
        if (activation != issuedAt || current.value.busy || current.value.review != null || selected?.isEmpty() == true) return
        val review = AlbumExportReview.prepare(overview, selected) ?: return
        val format = if (review.pages.size == 1) ExportFormat.JPEG else ExportFormat.IMAGE_ZIP
        current.value = AlbumExportState(review = review, format = format)
        run(issuedAt) {
            val pages = services.loadDefaultAlbumExport(issuedAt, review)
            if (activation == issuedAt) current.value = current.value.copy(pages = pages)
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
        val issuedAt = activation ?: return
        val state = current.value
        val names = state.review ?: return
        val format = state.format ?: return
        if (!mayEdit() || state.pages.size != names.pages.size) return
        val filename = name(names, format)
        run(issuedAt) {
            val dimensions = exportOutputs(state.pages, state.size)
            val settings = ExportSettings(format, dimensions, AlbumArtifactEncoder.PDF.takeIf { format == ExportFormat.PDF },
                state.quality.settings.takeIf { format == ExportFormat.JPEG || format == ExportFormat.IMAGE_ZIP }, emptyList(),
                AlbumExportScope(names.albumTitle, state.pages.map { it.position }))
            val result = services.generateDefaultAlbumExport(issuedAt, names, settings)
            check(result.filename == filename)
            if (activation == issuedAt) {
                completed = Completed(issuedAt, result, filename)
                current.value = current.value.copy(filename = filename)
            }
        }
    }

    fun saveAs() {
        val result = completed ?: return
        if (current.value.waitingForTarget) return
        run(result.issuedAt) {
            val notice = services.useDefaultAlbumExport(result.issuedAt) { store, confirm ->
                // Capture the committed attempt inside the noncancellable scope; cleanup can settle it even if delivery is canceled.
                var attempt: NamedAlbumExports.Publication? = null
                try {
                    withContext(NonCancellable) { attempt = store.prepareSaveAs(result.ready) }
                    val pending = checkNotNull(attempt)
                    confirm()
                    if (activation != result.issuedAt) return@useDefaultAlbumExport PublicationNotice(null)
                    val owner = Picker(pending.id)
                    picker = owner
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(result.ready.mime).putExtra(Intent.EXTRA_TITLE, result.name)
                    current.value = current.value.copy(waitingForTarget = true, publicationId = pending.id, launch = intent)
                    val chosen = try { owner.result.await() }
                    finally {
                        if (picker === owner) {
                            picker = null
                            current.value = current.value.copy(waitingForTarget = false, publicationId = null, launch = null)
                        }
                    }
                    if (chosen == null || activation != result.issuedAt) return@useDefaultAlbumExport PublicationNotice(null)
                    confirm()
                    val uri = chosen.data?.data
                    val flags = chosen.data?.flags ?: 0
                    if (!eligible(uri, chosen.code, flags)) PublicationNotice(null, OperationErrorCode.PERMISSION_LOST.takeIf { chosen.code == Activity.RESULT_OK })
                    else PublicationNotice(publish(store, result, pending, checkNotNull(uri), flags, confirm))
                } finally {
                    // Only WAITING_DESTINATION may be canceled here. An admitted or uncertain write is settled in publish, never retried.
                    attempt?.let { pending ->
                        withContext(NonCancellable) { runCatching { store.updateSaveAs(pending, "WAITING_DESTINATION", "CANCELED") } }
                    }
                }
            }
            if (activation == result.issuedAt) current.value = current.value.copy(notice = notice.notice ?: current.value.notice, error = notice.error)
        }
    }

    fun result(publicationId: String, code: Int, data: Intent?) {
        val owner = picker?.takeIf { it.id == publicationId && current.value.launch == null } ?: return
        owner.result.complete(Selection(code, data)) // Only the first result can settle this exact suspended owner.
    }

    private fun eligible(uri: Uri?, code: Int, flags: Int): Boolean =
        code == Activity.RESULT_OK && uri != null && uri.scheme == "content" && uri.authority != null &&
            !uri.authority.orEmpty().startsWith("${context.packageName}.") && uri.toString().length <= 4096 &&
            flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0 && DocumentsContract.isDocumentUri(context, uri) &&
            hasPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

    private fun hasPermission(uri: Uri, mode: Int) =
        context.checkUriPermission(uri, Process.myPid(), Process.myUid(), mode) == PackageManager.PERMISSION_GRANTED

    private data class PublicationNotice(val notice: ExportNotice?, val error: OperationErrorCode? = null)

    private suspend fun publish(store: NamedAlbumExports, result: Completed, pending: NamedAlbumExports.Publication,
        uri: Uri, flags: Int, confirm: suspend () -> Unit): ExportNotice {
        val reference = uri.toString()
        val writable = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val readable = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val expectRead = flags and readable != 0
        fun requirePermission(mode: Int) { if (!hasPermission(uri, mode)) throw SecurityException("Export permission ended") }
        var sent = 0L
        var closed = false
        var writingMayHaveStarted = false
        try {
            confirm()
            requirePermission(writable)
            val resolver = context.contentResolver
            writingMayHaveStarted = true
            store.updateSaveAs(pending, "WAITING_DESTINATION", "WRITING", reference)
            withContext(Dispatchers.IO) {
                store.copy(result.ready) { source, active ->
                    active()
                    services.requireDefaultAlbumWriteOnIo(result.issuedAt)
                    requirePermission(writable)
                    // The existing copy port authenticated the whole private artifact before calling this handoff.
                    val descriptor = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("Destination unavailable")
                    ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { sink ->
                        if (descriptor.statSize != 0L) throw IOException("Destination is not a new empty file")
                        services.requireDefaultAlbumWriteOnIo(result.issuedAt)
                        requirePermission(writable)
                        transferExactExport(source, sink, result.ready.byteCount, result.ready.sha256, active) { sent += it }
                        sink.flush()
                    }
                    closed = true
                }
            }
            if (expectRead) withContext(Dispatchers.IO) {
                confirm()
                requirePermission(readable)
                val scope = currentCoroutineContext()
                val stream = resolver.openInputStream(uri) ?: throw IOException("Destination readback is unavailable")
                stream.use { transferExactExport(it, null, result.ready.byteCount, result.ready.sha256, { scope.ensureActive() }) }
            }
            confirm()
            requirePermission(writable)
            if (expectRead) requirePermission(readable)
            store.updateSaveAs(pending, "WRITING", if (expectRead) "READBACK_VERIFIED" else "OUTPUT_COMPLETED", reference, sent, expectRead)
            // The original receipt may already be terminal; a changed activation can still refuse its UI delivery.
            confirm()
            return if (expectRead) ExportNotice.SAVED else ExportNotice.SENT_WITHOUT_READBACK
        } catch (failure: Exception) {
            val code = when (failure) {
                is CancellationException -> OperationErrorCode.CANCELED
                is RepositoryFailure -> failure.error.code
                is SecurityException -> OperationErrorCode.PERMISSION_LOST
                else -> OperationErrorCode.STORAGE_FAILED
            }
            // Never delete/overwrite a provider file on an ambiguous result. Keep the verified private artifact.
            if (writingMayHaveStarted) withContext(NonCancellable) {
                val next = if (closed && sent == result.ready.byteCount) "NEEDS_VERIFICATION" else "PARTIAL_EXTERNAL"
                runCatching { store.updateSaveAs(pending, "WRITING", next, reference, sent, error = code) }
            }
            if (failure is CancellationException) throw failure
            return if (closed) ExportNotice.NEEDS_VERIFICATION else ExportNotice.PARTIAL_OUTPUT
        }
    }

    fun share() {
        val result = completed ?: return
        if (current.value.waitingForTarget) return
        run(result.issuedAt) {
            if (result.issuedAt.number != 1L) throw RepositoryFailure(OperationError(OperationErrorCode.SOURCE_UNAVAILABLE, false, "library_share_activation_unqualified", null))
            val granted = services.useDefaultAlbumExport(result.issuedAt) { store, confirm ->
                share?.takeIf { System.currentTimeMillis() < it.expires && store.canOpenShare(it.id, result.name, it.file.length()) }
                    ?.also { confirm() } ?: createShare(store, result, confirm)
            }
            if (activation != result.issuedAt) return@run
            share = granted
            val intent = Intent(Intent.ACTION_SEND).setType(result.ready.mime).putExtra(Intent.EXTRA_STREAM, granted.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newUri(context.contentResolver, result.name, granted.uri)
            current.value = current.value.copy(shared = true, launch = Intent.createChooser(intent, null))
        }
    }

    private suspend fun createShare(store: NamedAlbumExports, result: Completed, confirm: suspend () -> Unit): Share {
        require(FinishedExportProvider.safeFilename(result.name))
        val id = UUID.randomUUID().toString()
        val root = File(context.filesDir.canonicalFile, "shared-exports")
        val directory = File(root, id)
        val file = File(directory, result.name)
        var ownsDirectory = false
        var ownsFile = false
        var recorded = false
        try {
            store.copy(result.ready) { source, active ->
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
            confirm()
            val expires = store.recordShare(result.ready, id, file.name)
            recorded = true
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.finished-exports", file)
            confirm()
            return Share(id, uri, file, expires)
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                if (recorded) runCatching { store.revokeShare(result.ready, id) }
                if (ownsFile) runCatching { file.delete() }
                if (ownsDirectory) runCatching { directory.delete() }
            }
            throw failure
        }
    }

    fun revokeShare() {
        val result = completed ?: return
        val granted = share ?: return
        run(result.issuedAt) {
            services.useDefaultAlbumExport(result.issuedAt) { store, _ ->
                store.revokeShare(result.ready, granted.id)
                // Room is already revoked. A failed platform/file cleanup must not let this UI offer the URI again.
                if (activation == result.issuedAt) {
                    share = null
                    current.value = current.value.copy(shared = false, notice = ExportNotice.STOPPED_SHARING)
                }
                context.revokeUriPermission(granted.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                withContext(Dispatchers.IO) {
                    if (granted.file.exists() && !granted.file.delete()) throw IOException("Share copy cleanup failed")
                    granted.file.parentFile?.delete()
                    Unit
                }
            }
        }
    }

    suspend fun consumeLaunch(intent: Intent): Boolean {
        val issuedAt = activation ?: return false
        if (current.value.launch !== intent) return false
        val admitted = try { services.recordedDefaultActivation() == issuedAt }
        catch (canceled: CancellationException) { throw canceled }
        catch (_: Exception) { false }
        if (activation != issuedAt || current.value.launch !== intent) return false
        current.value = current.value.copy(launch = null)
        if (!admitted) launchFailed()
        return admitted
    }
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
        share = null // Handed-off copies retain their own expiring read grant.
        current.value = AlbumExportState()
    }

    private fun mayEdit() = !current.value.busy && !current.value.waitingForTarget && completed == null && current.value.review != null

    private fun run(issuedAt: LibraryActivation, work: suspend () -> Unit) {
        if (current.value.busy || activation != issuedAt) return
        current.value = current.value.copy(busy = true, error = null, notice = null)
        worker = viewModelScope.launch {
            try { work() }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (activation == issuedAt) current.value = current.value.copy(error = when (failure) {
                    is RepositoryFailure -> failure.error.code
                    is SecurityException -> OperationErrorCode.PERMISSION_LOST
                    is IllegalArgumentException -> OperationErrorCode.INVALID_INPUT
                    else -> OperationErrorCode.STORAGE_FAILED
                })
            }
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
