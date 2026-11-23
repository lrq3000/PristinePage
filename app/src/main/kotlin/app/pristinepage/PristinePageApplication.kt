package app.pristinepage

import android.app.Application
import app.pristinepage.design.AtelierPreferences
import app.pristinepage.ocr.SavedOcrAppSignals
import app.pristinepage.ocr.SavedOcrApplication
import app.pristinepage.ocr.SavedOcrScheduling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process lifetime owns the service root. No eager camera permission, provider read, database open or background-completion promise is attached to Application.onCreate. */
open class PristinePageApplication : Application() {
    val services: ApplicationServices by lazy { ApplicationServices(this) }
    internal val atelierPreferences by lazy { AtelierPreferences(this) }
    private val runtimeReady = MutableStateFlow(false)
    internal val ocrRuntimeReady = runtimeReady.asStateFlow()

    // Normal builds have no runtime. The local qualification source set is the only override.
    internal open val hasBundledOcr: Boolean get() = false
    internal open suspend fun installedSavedOcrRuntime(): SavedOcrApplication.Runtime? = null

    internal val savedOcr by lazy {
        SavedOcrApplication(
            installed = { installedSavedOcrRuntime().also { runtimeReady.value = it != null } },
            orderedDefaults = { atelierPreferences.settings.value.ocrLanguages.map { it.packCode } },
            graph = { SavedOcrApplication.bind(services.imports()) },
        )
    }
    internal val savedOcrSignals by lazy {
        SavedOcrAppSignals(CoroutineScope(SupervisorJob() + Dispatchers.IO), SavedOcrScheduling.withWorkManager(this, savedOcr::reconciliation))
    }
}
