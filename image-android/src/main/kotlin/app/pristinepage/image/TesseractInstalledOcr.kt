package app.pristinepage.image

import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.core.ocr.OcrLayout
import app.pristinepage.image.decode.ImageAllocationBudget
import app.pristinepage.image.ocr.PrivateOcrPackOwner
import app.pristinepage.image.ocr.SavedOcrPreparer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Read-only binding to a published private catalog of bundled packs. */
internal class TesseractInstalledOcr(private val packs: PrivateOcrPackOwner, budget: ImageAllocationBudget) {
    suspend fun configurationFor(codes: List<String>, layout: OcrLayout = OcrLayout.AUTOMATIC): OcrConfiguration? = withContext(Dispatchers.IO) {
        val ordered = packs.resolveOrdered(codes) { coroutineContext.ensureActive() } ?: return@withContext null
        OcrConfiguration(TesseractSavedOcrSessions.ENGINE_ID, TesseractSavedOcrSessions.CONTRACT_VERSION, layout, ordered)
    }

    val sessions: SavedOcrPreparer.SessionFactory = TesseractSavedOcrSessions(budget) { requested, checkpoint ->
        packs.acquire(requested, checkpoint)?.toFile()?.let(::PublishedCatalogBorrow)
    }

    /** The owner has no API to replace or delete this published catalog. The session retains the owner-backed path through native teardown; no lock or file handle escapes. */
    private class PublishedCatalogBorrow(override val root: File) : TesseractSavedOcrSessions.PackLease {
        override fun close() = Unit
    }
}
