package app.pristinepage.ocr

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.DocumentPage
import app.pristinepage.data.repository.SavedOcrWork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Room owns the saved revision and generation; WorkManager tells us only whether an actual wake for that generation is unfinished. */
internal object AlbumOcrPending {
    enum class State { QUEUED, ACTIVE_WAKE }

    fun observe(targets: Flow<SavedOcrWork.Target?>, states: (SavedOcrWork.Target) -> Flow<List<WorkInfo.State>>): Flow<State?> =
        channelFlow<State?> {
            var subscribed: SavedOcrWork.Target? = null
            var observer: Job? = null
            targets.collect { target ->
                // Keep healthy watches across unrelated Room invalidations. A failed watch can reopen on the next revalidation of this same job, without polling.
                if (target == subscribed && observer?.isActive == true) return@collect
                observer?.cancelAndJoin()
                send(null)
                subscribed = target
                observer = target?.let {
                    launch {
                        try {
                            states(it).collect { records ->
                                send(when (records.filterNot { state -> state.isFinished }.singleOrNull()) {
                                    WorkInfo.State.ENQUEUED -> State.QUEUED
                                    WorkInfo.State.RUNNING -> State.ACTIVE_WAKE
                                    else -> null
                                })
                            }
                        } catch (canceled: CancellationException) {
                            throw canceled
                        } catch (_: Exception) {
                            send(null)
                        }
                    }
                }
            }
        }.buffer(0).distinctUntilChanged()

    fun withWorkManager(context: Context, targets: Flow<SavedOcrWork.Target?>): Flow<State?> =
        observe(targets) { target ->
            WorkManager.getInstance(context.applicationContext).getWorkInfosForUniqueWorkFlow(OcrPageDelivery.uniqueName(target))
                .map { records -> records.map { it.state } }
        }

    fun forSavedPage(context: Context, open: suspend () -> SavedOcrWork): (DocumentId, DocumentPage) -> Flow<State?> =
        { document, page ->
            withWorkManager(context, flow {
                emitAll(open().observePendingTarget(document, page.pageId, page.recipeRevision))
            })
        }
}
