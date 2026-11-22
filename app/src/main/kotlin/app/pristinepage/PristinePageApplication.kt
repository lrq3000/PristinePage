package app.pristinepage

import android.app.Application
import app.pristinepage.design.AtelierPreferences
import app.pristinepage.ocr.SavedOcrAppSignals
import app.pristinepage.ocr.SavedOcrApplication
import app.pristinepage.ocr.SavedOcrScheduling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process lifetime owns the service root. No eager camera permission, provider read, database open or background-completion promise is attached to Application.onCreate. */
class PristinePageApplication : Application() {
    val services: ApplicationServices by lazy { ApplicationServices(this) }
    internal val atelierPreferences by lazy { AtelierPreferences(this) }
    internal val savedOcr by lazy {
        SavedOcrApplication(
            // The private qualifications do not authorize packaging the runtime or weights in ordinary APKs.
            installed = { null },
            orderedDefaults = { atelierPreferences.settings.value.ocrLanguages.map { it.packCode } },
            graph = { SavedOcrApplication.bind(services.imports()) },
        )
    }
    internal val savedOcrSignals by lazy {
        SavedOcrAppSignals(CoroutineScope(SupervisorJob() + Dispatchers.IO), SavedOcrScheduling.withWorkManager(this, savedOcr::reconciliation))
    }
}
