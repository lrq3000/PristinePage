package app.pristinepage.image

import android.app.Application
import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.image.decode.ImageAllocationBudget
import app.pristinepage.image.ocr.PrivateOcrPackOwner
import app.pristinepage.image.ocr.SavedOcrPreparer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Available only in an opt-in S-01 candidate. Copy from APK assets to the normal private verified pack owner; never consult a network or user path. */
class LocalOcrRuntime private constructor(private val installed: TesseractInstalledOcr) {
    val sessions: SavedOcrPreparer.SessionFactory get() = installed.sessions
    suspend fun configurationFor(codes: List<String>): OcrConfiguration? = try {
        installed.configurationFor(codes)
    } catch (_: OutOfMemoryError) {
        // Defaults are not a started job. Do not substitute a different installed list or prevent recovery of previously bound jobs.
        null
    }

    companion object {
        suspend fun open(app: Application, budget: ImageAllocationBudget): LocalOcrRuntime? = withContext(Dispatchers.IO) {
            val context = coroutineContext
            context.ensureActive()
            val packs = try {
                val owner = PrivateOcrPackOwner.forApplication(app, LocalOcrCatalog.pins) ?: return@withContext null
                if (!owner.install(checkpoint = { context.ensureActive() }) { code ->
                        app.assets.open("ocr-local/tessdata/$code.traineddata")
                    }) return@withContext null
                owner
            } catch (_: OutOfMemoryError) {
                return@withContext null
            }
            context.ensureActive()
            LocalOcrRuntime(TesseractInstalledOcr(packs, budget))
        }
    }
}
