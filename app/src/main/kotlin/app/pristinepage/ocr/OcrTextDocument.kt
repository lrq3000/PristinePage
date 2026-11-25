package app.pristinepage.ocr

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import app.pristinepage.data.repository.SavedOcrWork.TextSaveOutcome
import app.pristinepage.export.transferExactExport
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Nothing becomes verified until the nontruncating write is closed and a separate read matches the frozen bytes. */
internal suspend fun writeOcrTextDocument(
    bytes: ByteArray,
    sha256: String,
    open: () -> ParcelFileDescriptor?,
    reopen: (() -> InputStream?)?,
    admit: suspend () -> Boolean = { true },
): TextSaveOutcome = withContext(Dispatchers.IO) {
    val active = currentCoroutineContext()
    if (!admit()) return@withContext TextSaveOutcome.DESTINATION_REFUSED
    val descriptor = try {
        active.ensureActive()
        open() ?: return@withContext TextSaveOutcome.DESTINATION_REFUSED
    } catch (canceled: CancellationException) {
        throw canceled
    } catch (_: SecurityException) {
        return@withContext TextSaveOutcome.PERMISSION_LOST
    } catch (_: Exception) {
        return@withContext TextSaveOutcome.DESTINATION_REFUSED
    }
    val empty = try {
        descriptor.statSize == 0L && Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_CUR) == 0L
    } catch (_: Exception) { false }
    if (!empty) {
        runCatching { descriptor.close() }
        return@withContext TextSaveOutcome.DESTINATION_REFUSED
    }
    try {
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { sink ->
            if (!admit()) return@withContext TextSaveOutcome.DESTINATION_REFUSED
            transferExactExport(bytes.inputStream(), sink, bytes.size.toLong(), sha256, active::ensureActive)
            sink.flush()
        }
    } catch (canceled: CancellationException) {
        throw canceled
    } catch (_: Exception) {
        return@withContext TextSaveOutcome.PARTIAL_EXTERNAL
    }
    if (reopen == null) return@withContext TextSaveOutcome.OUTPUT_COMPLETED
    try {
        active.ensureActive()
        if (!admit()) return@withContext TextSaveOutcome.NEEDS_VERIFICATION
        val source = reopen() ?: return@withContext TextSaveOutcome.NEEDS_VERIFICATION
        source.use { transferExactExport(it, null, bytes.size.toLong(), sha256, active::ensureActive) }
        TextSaveOutcome.READBACK_VERIFIED
    } catch (canceled: CancellationException) {
        throw canceled
    } catch (_: Exception) {
        TextSaveOutcome.NEEDS_VERIFICATION
    }
}
