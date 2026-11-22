package app.pristinepage

import android.app.Application
import app.pristinepage.design.AtelierPreferences
import app.pristinepage.ocr.SavedOcrAppSignals
import app.pristinepage.ocr.SavedOcrScheduling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process lifetime owns the service root. No eager camera permission, provider read, database open or background-completion promise is attached to Application.onCreate. */
class PristinePageApplication : Application() {
    val services: ApplicationServices by lazy { ApplicationServices(this) }
    internal val atelierPreferences by lazy { AtelierPreferences(this) }
    internal val savedOcrSignals by lazy {
        // Ordinary builds do not supply an OCR runtime. Do not open the store or scheduler for these signals unless a qualified session resolves.
        SavedOcrAppSignals(CoroutineScope(SupervisorJob() + Dispatchers.IO), SavedOcrScheduling.withWorkManager(this) { null })
    }
}
