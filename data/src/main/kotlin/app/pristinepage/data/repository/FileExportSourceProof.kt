package app.pristinepage.data.repository

import app.pristinepage.data.files.SourceFiles
import app.pristinepage.data.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Hash closed app-owned original bytes without provider access, decode or a metadata writer lock. A successful byte proof does not replace the recorded normalized-pixel/decoder acceptance or a later render's own file check. */
class FileExportSourceProof @JvmOverloads constructor(private val files: SourceFiles, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) : ExportSourceProof {
    override suspend fun verify(binding: ExportSourceBinding): Unit = withContext(dispatcher) {
        val source = binding.source; val blob = binding.blob
        try {
            coroutineContext.ensureActive()
            requireOpaqueId(source.sourceId); requireOpaqueId(blob.sourceBlobId)
            require(source.state == "READY" && blob.state == "READY" && source.sourceBlobId == blob.sourceBlobId)
            require(blob.relativePath == "sources/${blob.sourceBlobId}/original.bin")
            files.verifyOriginal(blob.sourceBlobId, blob.byteCount, blob.byteSha256) { coroutineContext.ensureActive() }
        } catch (canceled: CancellationException) { throw canceled }
        catch (_: IllegalArgumentException) { throw RepositoryFailure(OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "export_source_invalid", source.sourceId.takeIf { it.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) })) }
        catch (_: Exception) { throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "export_source_read_failed", source.sourceId)) }
    }
}
