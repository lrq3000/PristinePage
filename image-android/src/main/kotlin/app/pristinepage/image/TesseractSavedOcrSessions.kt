package app.pristinepage.image

import android.graphics.Bitmap
import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.core.ocr.OcrLanguagePacks
import app.pristinepage.core.ocr.OcrLayout
import app.pristinepage.core.ocr.OcrText
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.repository.SavedOcrWork
import app.pristinepage.image.decode.ImageAllocationBudget
import app.pristinepage.image.ocr.SavedOcrPreparer
import com.googlecode.leptonica.android.Pix
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Native recognition for the selected, byte-qualified runtime. */
internal class TesseractSavedOcrSessions(
    private val budget: ImageAllocationBudget,
    private val allocatePix: (Int, Int) -> Pix? = { width, height -> Pix(width, height, 32) },
    private val acquirePacks: (OcrLanguagePacks, () -> Unit) -> PackLease?,
) : SavedOcrPreparer.SessionFactory {
    interface PackLease : AutoCloseable {
        /** Trusted owner supplies a read-only directory containing exactly the claimed pack bytes for the session lifetime. */
        val root: File
    }

    override fun open(configuration: OcrConfiguration, input: Bitmap, checkpoint: () -> Unit): SavedOcrWork.Prepared {
        checkpoint()
        if (configuration.engineId != ENGINE_ID || configuration.engineVersion != CONTRACT_VERSION) unavailable()
        val packs = configuration.languagePacks ?: unavailable()
        val lease = try {
            acquirePacks(packs, checkpoint) ?: unavailable()
        } catch (_: OutOfMemoryError) {
            // Catalog verification can allocate before there is a native session or durable recognition start.
            throw error(OperationErrorCode.RESOURCE_LIMIT, "tesseract_pack_verification_allocation_failed")
        }
        var engine: TessBaseAPI? = null
        var reservation: AutoCloseable? = null
        try {
            checkpoint()
            val root = lease.root
            if (!File(root, "tessdata").isDirectory) unavailable()
            val width = input.width
            val height = input.height
            val pixels = width.toLong() * height
            if (width > Int.MAX_VALUE / 32 || pixels > Int.MAX_VALUE / 4) allocationRefused()
            // Account for a 32-bit Pix, one provisional native raster copy and the Java row. Engine/model scratch and peak RSS are not bounded by this estimate.
            val reserved = budget.reserve(8 * pixels + 4L * width)
            reservation = reserved
            val local = TessBaseAPI()
            engine = local
            if (local.version != NATIVE_VERSION || !initialize(local, root, packs) || !local.setVariable("user_defined_dpi", "300")) unavailable()
            checkpoint()
            val mode = when (configuration.layout) {
                OcrLayout.AUTOMATIC -> TessBaseAPI.PageSegMode.PSM_AUTO
                OcrLayout.SINGLE_BLOCK -> TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
                OcrLayout.SPARSE_TEXT -> TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
            }
            local.setPageSegMode(mode)
            if (local.pageSegMode != mode) unavailable()
            setCheckedImage(local, input, width, height, checkpoint)
            checkpoint()
            return Session(local, lease, reserved)
        } catch (failure: Throwable) {
            val reported = when (failure) {
                is LinkageError -> error(OperationErrorCode.PROVIDER_UNAVAILABLE, "tesseract_runtime_unavailable")
                is OutOfMemoryError -> error(OperationErrorCode.RESOURCE_LIMIT, "tesseract_prepare_allocation_failed")
                else -> failure
            }
            try { release(engine, reservation, lease) } catch (cleanup: Throwable) {
                if (cleanup !== reported) reported.addSuppressed(cleanup)
            }
            throw reported
        }
    }

    private fun initialize(engine: TessBaseAPI, root: File, packs: OcrLanguagePacks): Boolean = try {
        engine.init(root.absolutePath, packs.ordered.joinToString("+") { it.code }, TessBaseAPI.OEM_LSTM_ONLY)
    } catch (_: IllegalArgumentException) {
        // The pinned wrapper can throw if its data root disappears or is unavailable; do not propagate its exception text.
        false
    }

    private fun setCheckedImage(engine: TessBaseAPI, input: Bitmap, width: Int, height: Int, checkpoint: () -> Unit) {
        // The pinned Bitmap JNI path dereferences a failed Pix allocation. Pix(width,height,32) returns the pointer unchanged, so check it before copying or passing it to Tesseract.
        val pix = allocatePix(width, height) ?: allocationRefused()
        if (pix.nativePix == 0L) allocationRefused()
        try {
            val row = IntArray(width)
            for (y in 0 until height) {
                checkpoint()
                input.getPixels(row, 0, width, 0, y, width, 1)
                for (x in row.indices) pix.setPixel(x, y, row[x])
            }
            checkpoint()
            engine.setImage(pix)
        } finally {
            // The qualified JNI SetImage(Pix) path takes its own reference; the engine keeps it until recycle().
            pix.recycle()
        }
    }

    private class Session(
        private val engine: TessBaseAPI,
        private val lease: PackLease,
        private val reservation: AutoCloseable,
    ) : SavedOcrWork.Prepared {
        private var closed = false

        override suspend fun recognize(): OcrText {
            check(!closed)
            val active = currentCoroutineContext()
            active.ensureActive()
            val raw = try { engine.getUTF8Text() } catch (_: OutOfMemoryError) {
                throw error(OperationErrorCode.RESOURCE_LIMIT, "tesseract_recognition_allocation_failed")
            }
            active.ensureActive()
            if (raw == null) throw error(OperationErrorCode.PROCESSING_FAILED, "tesseract_output_unavailable")
            // Preserve the wrapper-returned string, including the empty success; projection is a store responsibility.
            return try { OcrText(raw) } catch (_: IllegalArgumentException) {
                throw error(OperationErrorCode.INVALID_INPUT, "tesseract_output_invalid")
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            release(engine, reservation, lease)
        }
    }

    companion object {
        const val ENGINE_ID = "tesseract-android"
        const val CONTRACT_VERSION = 1
        private const val NATIVE_VERSION = "5.5.1"

        private fun unavailable(): Nothing = throw error(OperationErrorCode.PROVIDER_UNAVAILABLE, "tesseract_runtime_unavailable")
        private fun allocationRefused(): Nothing = throw error(OperationErrorCode.RESOURCE_LIMIT, "tesseract_pix_allocation_failed")

        private fun error(code: OperationErrorCode, key: String): RepositoryFailure =
            RepositoryFailure(OperationError(code, false, key, null))

        private fun release(engine: TessBaseAPI?, reservation: AutoCloseable?, lease: PackLease) {
            try { engine?.recycle() } finally {
                try { reservation?.close() } finally { lease.close() }
            }
        }
    }
}
