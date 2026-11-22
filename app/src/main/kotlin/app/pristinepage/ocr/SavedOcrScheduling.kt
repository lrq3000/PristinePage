package app.pristinepage.ocr

import android.content.Context
import androidx.work.WorkManager
import androidx.work.await
import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.PageId
import app.pristinepage.data.repository.SavedOcrWork
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.yield

/** Save and launch hints are transport only. A missing qualified runtime resolves no session and must not open the library or WorkManager. */
internal class SavedOcrScheduling(
    private val resolve: suspend () -> QualifiedSession?,
    private val enqueue: suspend (SavedOcrWork.Target) -> Unit,
) {
    interface QualifiedSession {
        suspend fun reconcilePage(document: DocumentId, page: PageId): SavedOcrWork.Target?
        fun pendingPages(): Flow<SavedOcrWork.Target>
    }

    /** Call outside the save transaction and its coroutine lifetime; interruption is recovered by a later startup scan. */
    suspend fun onPageSaved(document: DocumentId, page: PageId) {
        currentCoroutineContext().ensureActive()
        val target = resolve()?.reconcilePage(document, page) ?: return
        currentCoroutineContext().ensureActive()
        enqueue(target)
    }

    /** Serial enrollment keeps each scan bounded in memory and exposes failures without misreporting a recognition start. */
    suspend fun onStartup() {
        currentCoroutineContext().ensureActive()
        resolve()?.pendingPages()?.collect { target ->
            currentCoroutineContext().ensureActive()
            enqueue(target)
        }
    }

    companion object {
        private const val PAGE_BATCH = 20

        fun withWorkManager(context: Context, resolve: suspend () -> QualifiedSession?): SavedOcrScheduling {
            val application = context.applicationContext
            return SavedOcrScheduling(resolve) { target ->
                // Wait for the request transaction, not for execution of the worker.
                OcrPageEnrollment.forTarget(target).enqueue(WorkManager.getInstance(application)).await()
            }
        }

        /** A verified runtime may have no resolvable default pack list. Null defaults preserve rediscovery without allocating new jobs. */
        fun bind(work: SavedOcrWork, defaults: OcrConfiguration?): QualifiedSession = object : QualifiedSession {
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
