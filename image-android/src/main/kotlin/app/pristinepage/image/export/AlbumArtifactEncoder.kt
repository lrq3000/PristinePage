package app.pristinepage.image.export

import android.os.Build
import app.pristinepage.core.pipeline.RenderCoordinator
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.CommittedSources
import app.pristinepage.data.repository.NamedAlbumExports
import app.pristinepage.image.decode.ImageAllocationBudget
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Consume only the already frozen pages through the shared renderer. The data owner journals and publishes the complete artifact. */
class AlbumArtifactEncoder(
    private val scratch: File, sources: CommittedSources, renderer: RenderCoordinator, private val budget: ImageAllocationBudget,
) : NamedAlbumExports.Encoder {
    private val render = FrozenPageRenderer(sources, renderer, PROFILE.rendererVersion, PROFILE.encodingVersion, PROFILE.backend, PROFILE.precision)
    private val jpeg = AlbumJpeg(budget)
    private val pdf = AlbumPdf(budget)

    override suspend fun check(snapshot: ExportSnapshot): OperationError? = try {
        currentCoroutineContext().ensureActive()
        require(snapshot.settings.albumScope != null)
        when (snapshot.settings.format) {
            ExportFormat.JPEG, ExportFormat.IMAGE_ZIP -> {
                require(AlbumJpegQuality.supports(snapshot.settings.jpeg))
                if (Build.VERSION.SDK_INT < 28) refuse(OperationErrorCode.UNSUPPORTED_VERSION, "export_jpeg_readback_api")
                snapshot.pages.forEach {
                    val size = it.output.size
                    if (pngNativeBytes(size.width, size.height) + 3L * size.width * size.height > budget.capacityBytes - budget.usedBytes) {
                        refuse(OperationErrorCode.RESOURCE_LIMIT, "export_jpeg_budget")
                    }
                }
            }
            ExportFormat.PDF -> pdf.check(snapshot.pages.map { it.output.size }, checkNotNull(snapshot.settings.pdf))
            ExportFormat.PNG -> refuse(OperationErrorCode.UNSUPPORTED_FORMAT, "named_png_unsupported")
        }
        null
    } catch (failure: RepositoryFailure) { failure.error }
    catch (failure: IllegalArgumentException) { OperationError(OperationErrorCode.INVALID_INPUT, false, "named_export_encoder_mismatch", snapshot.documentId.value) }

    override suspend fun prepare(snapshot: ExportSnapshot): NamedAlbumExports.PreparedArtifact {
        check(snapshot)?.let { throw RepositoryFailure(it) }
        val context = currentCoroutineContext()
        val checkpoint = { context.ensureActive() }
        Files.createDirectories(scratch.toPath())
        require(scratch.canonicalFile == scratch.absoluteFile && !Files.isSymbolicLink(scratch.toPath()))
        if (snapshot.settings.format == ExportFormat.PDF) {
            return pdf.prepare(snapshot.pages.map { it.output.size }, checkNotNull(snapshot.settings.pdf), checkpoint) { index ->
                render.render(snapshot.pages[index]).use { albumBitmap(checkNotNull(it.image), checkpoint) }
            }
        }
        val jpegSettings = checkNotNull(snapshot.settings.jpeg)
        // Keep verified JPEGs on private scratch storage, not simultaneously in RAM. These random files are not share targets.
        val temporary = ArrayList<Part>(snapshot.pages.size)
        var remaining = NamedAlbumExports.MAX_ARTIFACT_BYTES
        val scratchFiles = ArrayList<File>()
        try {
            for (page in snapshot.pages) {
                checkpoint()
                val file = File.createTempFile("album-jpeg-", ".part", scratch).also(scratchFiles::add)
                render.render(page).use { result ->
                    val image = checkNotNull(result.image)
                    file.outputStream().use { raw ->
                        val sink = object : OutputStream() {
                            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                            override fun write(bytes: ByteArray, off: Int, len: Int) {
                                checkpoint()
                                if (len > remaining) refuse(OperationErrorCode.RESOURCE_LIMIT, "export_jpeg_byte_limit")
                                raw.write(bytes, off, len); remaining -= len
                            }
                        }
                        jpeg.write(image, sink, jpegSettings, checkpoint)
                    }
                    jpeg.verify(file, image, jpegSettings, checkpoint)
                }
                val length = file.length()
                temporary += Part(file, length, file.inputStream().use { it.hashExactly(length, checkpoint) })
            }
            return JpegParts(temporary, AlbumExportNames(checkNotNull(snapshot.settings.albumScope)).jpegs, snapshot.settings.format)
        } catch (failure: Throwable) {
            scratchFiles.forEach { runCatching { it.delete() } }
            throw failure
        }
    }

    private data class Part(val file: File, val size: Long, val sha256: String)
    private class JpegParts(private val parts: List<Part>, private val names: List<String>, private val format: ExportFormat) :
        NamedAlbumExports.PreparedArtifact {
        private val proof = parts.mapIndexed { index, part -> AlbumArchive.Entry(names[index], part.size, part.sha256) }

        override fun write(sink: OutputStream, checkpoint: () -> Unit) {
            val buffer = ByteArray(65_536)
            fun writePart(index: Int, output: OutputStream) {
                val part = parts[index]
                part.file.inputStream().use { input ->
                    var left = part.size
                    while (left > 0) {
                        checkpoint()
                        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                        require(n > 0)
                        output.write(buffer, 0, n)
                        left -= n
                    }
                    require(input.read() == -1)
                }
            }
            if (format == ExportFormat.JPEG) writePart(0, sink)
            else require(AlbumArchive.write(sink, names, checkpoint, ::writePart) == proof)
        }

        override fun verify(closed: File, checkpoint: () -> Unit) {
            if (format == ExportFormat.JPEG) {
                val part = parts.single()
                require(closed.length() == part.size && closed.inputStream().use { it.hashExactly(part.size, checkpoint) } == part.sha256)
            } else AlbumArchive.verify(closed, proof, checkpoint)
        }

        override fun close() { parts.forEach { runCatching { it.file.delete() } } }
    }

    companion object {
        val PROFILE = OrdinaryExportProfile("classical-v1", "rgb8-v1", "android-cpu", "binary64")
        val PDF = AlbumPdf.DEFAULT
    }
}
