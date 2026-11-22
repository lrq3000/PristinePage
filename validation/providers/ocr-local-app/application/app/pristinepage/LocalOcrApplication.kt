package app.pristinepage

import app.pristinepage.image.LocalOcrRuntime
import app.pristinepage.ocr.SavedOcrApplication
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The checked debug build uses the same library, renderer and Worker as the default app. No native work is started by Application.onCreate. */
class LocalOcrApplication : PristinePageApplication() {
    private val lock = Mutex()
    private var ready: SavedOcrApplication.Runtime? = null

    internal override val hasBundledOcr: Boolean get() = true

    internal override suspend fun installedSavedOcrRuntime(): SavedOcrApplication.Runtime? = lock.withLock {
        ready ?: LocalOcrRuntime.open(this, services.budget)?.let { installed ->
            SavedOcrApplication.Runtime(installed.sessions, installed::configurationFor).also { ready = it }
        }
    }
}
