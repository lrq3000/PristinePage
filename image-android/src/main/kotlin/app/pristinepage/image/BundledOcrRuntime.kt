package app.pristinepage.image

import android.app.Application
import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.core.ocr.OcrLayout
import app.pristinepage.image.decode.ImageAllocationBudget
import app.pristinepage.image.ocr.PrivateOcrPackOwner
import app.pristinepage.image.ocr.SavedOcrPreparer
import com.googlecode.leptonica.android.Pix
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Copy from APK assets to the private verified pack owner; never consult a network or user path. */
class BundledOcrRuntime private constructor(private val installed: TesseractInstalledOcr) {
    val sessions: SavedOcrPreparer.SessionFactory get() = installed.sessions
    suspend fun configurationFor(codes: List<String>, layout: OcrLayout): OcrConfiguration? = try {
        installed.configurationFor(codes, layout)
    } catch (_: OutOfMemoryError) {
        // Defaults are not a started job. Do not substitute a different installed list or prevent recovery of previously bound jobs.
        null
    }

    companion object {
        /** Initialize the pinned wrapper classes, which load and register their native libraries, without opening an engine or image. */
        internal fun nativeLibrariesAvailable(): Boolean = try {
            for (type in listOf(TessBaseAPI::class.java, Pix::class.java)) {
                Class.forName(type.name, true, type.classLoader)
            }
            true
        } catch (_: LinkageError) {
            // A missing library or failed JNI initializer is not a recognition attempt.
            false
        }

        suspend fun open(app: Application, budget: ImageAllocationBudget): BundledOcrRuntime? = withContext(Dispatchers.IO) {
            val context = coroutineContext
            context.ensureActive()
            val packs = try {
                if (!nativeLibrariesAvailable()) return@withContext null
                val owner = PrivateOcrPackOwner.forApplication(app, LocalOcrCatalog.pins) ?: return@withContext null
                if (!owner.install(checkpoint = { context.ensureActive() }) { code ->
                        app.assets.open("ocr-local/tessdata/$code.traineddata")
                    }) return@withContext null
                owner
            } catch (_: OutOfMemoryError) {
                return@withContext null
            }
            context.ensureActive()
            BundledOcrRuntime(TesseractInstalledOcr(packs, budget))
        }
    }
}
