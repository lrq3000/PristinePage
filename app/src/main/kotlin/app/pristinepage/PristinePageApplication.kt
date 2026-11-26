package app.pristinepage

import android.app.Application
import app.pristinepage.design.AtelierPreferences
import app.pristinepage.image.BundledOcrRuntime
import app.pristinepage.ocr.SavedOcrAppSignals
import app.pristinepage.ocr.SavedOcrApplication
import app.pristinepage.ocr.SavedOcrScheduling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process lifetime owns the service root. No eager camera permission, provider read, database open or background-completion promise is attached to Application.onCreate. */
class PristinePageApplication : Application() {
    val services: ApplicationServices by lazy { ApplicationServices(this) }
    internal val atelierPreferences by lazy { AtelierPreferences(this) }
    private val runtimeReady = MutableStateFlow(false)
    internal val ocrRuntimeReady = runtimeReady.asStateFlow()

    private val ocrInitialization = Mutex()
    private var verifiedRuntime: SavedOcrApplication.Runtime? = null

    private suspend fun installedSavedOcrRuntime(): SavedOcrApplication.Runtime? = ocrInitialization.withLock {
        verifiedRuntime ?: BundledOcrRuntime.open(this, services.budget)?.let { installed ->
            SavedOcrApplication.Runtime(installed.sessions, installed::configurationFor).also { verifiedRuntime = it }
        }
    }

    internal val savedOcr by lazy {
        SavedOcrApplication(
            installed = { installedSavedOcrRuntime().also { runtimeReady.value = it != null } },
            defaults = {
                val settings = atelierPreferences.settings.value
                SavedOcrApplication.Defaults(settings.ocrLanguages.map { it.packCode }, settings.ocrLayout)
            },
            graph = { SavedOcrApplication.bind(services, services.imports().activation) },
            currentActivation = services::recordedDefaultActivation,
            graphForActivation = { SavedOcrApplication.bind(services, it) },
        )
    }
    internal val savedOcrSignals by lazy {
        SavedOcrAppSignals(CoroutineScope(SupervisorJob() + Dispatchers.IO), SavedOcrScheduling.withWorkManager(this, savedOcr::reconciliation))
    }
}
