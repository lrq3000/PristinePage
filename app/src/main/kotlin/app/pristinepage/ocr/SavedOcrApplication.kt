package app.pristinepage.ocr

import app.pristinepage.ApplicationServices
import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.core.ocr.OcrLayout
import app.pristinepage.data.library.LibraryActivation
import app.pristinepage.data.repository.SavedOcrWork
import app.pristinepage.image.ocr.SavedOcrPreparer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One application entry for both event enrollment and validated WorkManager delivery. */
internal class SavedOcrApplication(
    private val installed: suspend () -> Runtime?,
    private val defaults: () -> Defaults,
    private val graph: suspend () -> Graph,
    private val currentActivation: suspend () -> LibraryActivation? = { null },
    private val graphForActivation: suspend (LibraryActivation) -> Graph? = {
        if (currentActivation() == it) graph() else null
    },
) : SavedOcrWorkerHost {
    class Defaults(val orderedCodes: List<String>, val layout: OcrLayout)

    class Runtime(
        val sessions: SavedOcrPreparer.SessionFactory,
        /** Resolve only the exact verified packs in the requested order. No download, text or file URI crosses this interface. */
        val configurationFor: suspend (List<String>, OcrLayout) -> OcrConfiguration?,
    )

    interface Graph {
        val activation: LibraryActivation? get() = null
        fun reconcile(defaults: OcrConfiguration?): SavedOcrScheduling.QualifiedSession
        suspend fun execute(target: SavedOcrWork.Target, sessions: SavedOcrPreparer.SessionFactory): SavedOcrWork.Outcome
    }

    suspend fun reconciliation(issuedAt: LibraryActivation? = null): SavedOcrScheduling.QualifiedSession? {
        if (issuedAt != null && currentActivation() != issuedAt) return null
        val runtime = activeRuntime() ?: return null
        val requested = defaults()
        val configuration = resolveOrdered(runtime, requested.orderedCodes, requested.layout)
        // Unresolved defaults must not hide work whose original packs are still available.
        val opened = if (issuedAt == null) graph() else graphForActivation(issuedAt)?.takeIf { it.activation == issuedAt } ?: return null
        return opened.reconcile(configuration)
    }

    suspend fun resolveRerunConfiguration(saved: OcrConfiguration, useDefaults: Boolean): OcrConfiguration? {
        val runtime = activeRuntime() ?: return null
        if (useDefaults) {
            val requested = defaults()
            return resolveOrdered(runtime, requested.orderedCodes, requested.layout)
        }
        val packs = saved.languagePacks ?: return null
        val verified = resolveOrdered(runtime, packs.ordered.map { it.code }, saved.layout) ?: return null
        // Saved reruns must also keep the exact engine contract and pack digests.
        return saved.takeIf {
            verified.engineId == saved.engineId && verified.engineVersion == saved.engineVersion && verified.languagePacks == packs
        }
    }

    override suspend fun executeSavedOcr(wake: ActivationBoundOcrWake): SavedOcrWork.Outcome? {
        if (currentActivation() != wake.activation) return null
        val runtime = activeRuntime() ?: return null
        val opened = graphForActivation(wake.activation) ?: return null
        if (opened.activation != wake.activation || currentActivation() != wake.activation) return null
        currentCoroutineContext().ensureActive()
        return opened.execute(wake.target, runtime.sessions)
    }

    private suspend fun activeRuntime(): Runtime? {
        val active = currentCoroutineContext()
        active.ensureActive()
        return installed().also { active.ensureActive() }
    }

    private suspend fun resolveOrdered(runtime: Runtime, requested: List<String>, layout: OcrLayout): OcrConfiguration? {
        val expected = requested.toList()
        // Validate against a snapshot the resolver cannot sort or otherwise mutate.
        val resolved = runtime.configurationFor(ArrayList(expected), layout)
        currentCoroutineContext().ensureActive()
        return resolved?.takeIf { it.layout == layout && it.languagePacks?.ordered?.map { pack -> pack.code } == expected }
    }

    companion object {
        fun bind(services: ApplicationServices, issuedAt: LibraryActivation): Graph = object : Graph {
            override val activation = issuedAt

            override fun reconcile(defaults: OcrConfiguration?): SavedOcrScheduling.QualifiedSession =
                SavedOcrScheduling.bind(services, defaults, issuedAt)

            override suspend fun execute(target: SavedOcrWork.Target, sessions: SavedOcrPreparer.SessionFactory): SavedOcrWork.Outcome =
                services.recognizeDefaultOcrPage(issuedAt, target, sessions)
        }
    }
}
