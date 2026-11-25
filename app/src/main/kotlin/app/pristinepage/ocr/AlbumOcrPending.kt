package app.pristinepage.ocr

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.pristinepage.ApplicationServices
import app.pristinepage.data.library.LibraryActivation
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Room owns the saved revision and generation; WorkManager tells us only whether an actual wake for that generation is unfinished. */
internal object AlbumOcrPending {
    enum class State { QUEUED, ACTIVE_WAKE }

    fun observe(targets: Flow<SavedOcrWork.Target?>, isCurrent: suspend () -> Boolean = { true },
        states: (SavedOcrWork.Target) -> Flow<List<WorkInfo.State>>,
    ): Flow<State?> =
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
                            if (!isCurrent()) return@launch
                            states(it).collect { records ->
                                // The Room target may not change while a WorkManager delivery runs. Check the issuing activation again before updating the tile.
                                send(when (if (isCurrent()) records.filterNot { state -> state.isFinished }.singleOrNull() else null) {
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

    fun withWorkManager(context: Context, targets: Flow<SavedOcrWork.Target?>, activation: LibraryActivation,
        isCurrent: suspend () -> Boolean = { true },
    ): Flow<State?> =
        observe(targets, isCurrent) { target ->
            val name = ActivationBoundOcrWake(activation, target).uniqueName()
            WorkManager.getInstance(context.applicationContext).getWorkInfosForUniqueWorkFlow(name)
                .map { records -> records.map { it.state } }
        }

    fun forSavedPage(context: Context, issuedAt: LibraryActivation, services: ApplicationServices): (DocumentId, DocumentPage) -> Flow<State?> =
        { document, page ->
            withWorkManager(context, services.observeDefaultAlbumOcrTarget(issuedAt, document, page), issuedAt) {
                services.recordedDefaultActivation() == issuedAt
            }
        }
}
