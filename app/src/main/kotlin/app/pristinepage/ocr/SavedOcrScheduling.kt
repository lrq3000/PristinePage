package app.pristinepage.ocr

import android.content.Context
import androidx.work.WorkManager
import androidx.work.await
import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.data.library.LibraryActivation
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.PageId
import app.pristinepage.data.repository.SavedOcrWork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.yield

/** Save and launch hints are transport only. A missing qualified runtime resolves no session and must not open the library or WorkManager. */
internal class SavedOcrScheduling(
    private val resolve: suspend (LibraryActivation?) -> QualifiedSession?,
    private val enqueue: suspend (ActivationBoundOcrWake) -> Unit,
) {
    interface QualifiedSession {
        val canCreateJobs: Boolean
        val activation: LibraryActivation
        suspend fun reconcilePage(document: DocumentId, page: PageId): SavedOcrWork.Target?
        fun pendingPages(): Flow<SavedOcrWork.Target>
    }

    enum class RerunOutcome { UNAVAILABLE, ENROLLED, WAKE_DEFERRED, BUSY }

    /** Explicit command only. A wake failure cannot undo a committed rerun or turn it into a recognition error. */
    suspend fun requestRerun(
        qualify: suspend () -> OcrConfiguration?,
        activation: LibraryActivation,
        commit: suspend (OcrConfiguration) -> SavedOcrWork.Target,
    ): RerunOutcome {
        val active = currentCoroutineContext()
        active.ensureActive()
        val configuration = qualify()
        active.ensureActive()
        if (configuration == null) return RerunOutcome.UNAVAILABLE
        val target = commit(configuration)
        active.ensureActive()
        val outcome = try {
            enqueue(ActivationBoundOcrWake(activation, target))
            RerunOutcome.ENROLLED
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (_: Exception) {
            // Launch reconciliation can rediscover the exact Room job; never create a second generation here.
            RerunOutcome.WAKE_DEFERRED
        }
        active.ensureActive()
        return outcome
    }

    /** One defaults snapshot per committed notification. A partial notification leaves the next foreground backfill eligible. */
    suspend fun onPagesSaved(issuedAt: LibraryActivation, document: DocumentId, pages: List<PageId>): Boolean {
        val active = currentCoroutineContext()
        active.ensureActive()
        if (pages.isEmpty()) return true
        val qualified = resolve(issuedAt) ?: return false
        if (qualified.activation != issuedAt) return false
        var complete = qualified.canCreateJobs
        for (page in pages) {
            active.ensureActive()
            try {
                val target = qualified.reconcilePage(document, page) ?: continue
                active.ensureActive()
                enqueue(ActivationBoundOcrWake(qualified.activation, target))
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (_: Exception) {
                // The accepted page and any committed job remain discoverable. A single failure must not drop the rest of this notification.
                complete = false
            }
        }
        active.ensureActive()
        return complete
    }

    /** Keep offering later pages after a failed wake. An incomplete scan remains eligible for the next launch. */
    suspend fun onStartup(): Boolean {
        val active = currentCoroutineContext()
        active.ensureActive()
        val qualified = resolve(null) ?: return false
        var firstFailure: Exception? = null
        qualified.pendingPages().collect { target ->
            active.ensureActive()
            try {
                enqueue(ActivationBoundOcrWake(qualified.activation, target))
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (failure: Exception) {
                // Keep only the first exception: the app signal uses it to leave startup eligible, not to log or count a recognition failure.
                if (firstFailure == null) firstFailure = failure
            }
        }
        active.ensureActive()
        firstFailure?.let { throw it }
        // A read-only pass can deliver previously committed jobs but could not backfill pages that still need a job.
        return qualified.canCreateJobs
    }

    companion object {
        private const val PAGE_BATCH = 20

        fun withWorkManager(context: Context, resolve: suspend (LibraryActivation?) -> QualifiedSession?): SavedOcrScheduling {
            val application = context.applicationContext
            return SavedOcrScheduling(resolve) { wake ->
                // Wait for the request transaction, not for execution of the worker.
                OcrPageEnrollment.forWake(wake).enqueue(WorkManager.getInstance(application)).await()
            }
        }

        /** A verified runtime may have no resolvable default pack list. Null defaults preserve rediscovery without allocating new jobs. */
        fun bind(work: SavedOcrWork, defaults: OcrConfiguration?, issuedAt: LibraryActivation): QualifiedSession = object : QualifiedSession {
            override val activation = issuedAt
            override val canCreateJobs = defaults != null

            override suspend fun reconcilePage(document: DocumentId, page: PageId): SavedOcrWork.Target? =
                work.reconcilePage(document, page, defaults)

            override fun pendingPages(): Flow<SavedOcrWork.Target> = discoverTargets<SavedOcrWork.Continuation>(defaults) { config, after, limit ->
                val batch = work.reconcile(config, after, limit)
                batch.targets to batch.nextAfter
            }
        }

        /** Only the issuing store can interpret a cursor; enrollment does not derive one from the targets. */
        internal fun <Cursor : Any> discoverTargets(
            defaults: OcrConfiguration?,
            read: suspend (OcrConfiguration?, Cursor?, Int) -> Pair<List<SavedOcrWork.Target>, Cursor?>,
        ): Flow<SavedOcrWork.Target> = flow {
            var after: Cursor? = null
            do {
                val (targets, next) = read(defaults, after, PAGE_BATCH)
                for (target in targets) emit(target)
                after = next
                // A batch of terminal or refused pages still advances the scan position.
                if (after != null) yield()
            } while (after != null)
        }
    }
}
