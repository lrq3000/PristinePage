package app.pristinepage.ocr

import androidx.work.WorkInfo.State
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.PageId
import app.pristinepage.data.repository.SavedOcrWork
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlbumOcrPendingTest {
    private val page = PageId("00000000-0000-4000-8000-000000000101")
    private val original = SavedOcrWork.Target(page, JobId("00000000-0000-4000-8000-000000000102"))
    private val replacement = SavedOcrWork.Target(page, JobId("00000000-0000-4000-8000-000000000103"))

    @Test fun sameTargetStoreInvalidationCanRestartAFailedWakeObservation() = runTest {
        val targets = MutableSharedFlow<SavedOcrWork.Target?>(replay = 1)
        var subscriptions = 0
        val visible = mutableListOf<AlbumOcrPending.State?>()
        backgroundScope.launch {
            AlbumOcrPending.observe(targets) {
                if (++subscriptions == 1) flow { throw IllegalStateException("synthetic transport read failed") }
                else flowOf(listOf(State.ENQUEUED))
            }.collect { visible.add(it) }
        }
        targets.emit(original)
        runCurrent()
        assertEquals(listOf<AlbumOcrPending.State?>(null), visible)
        targets.emit(original)
        runCurrent()
        assertEquals(2, subscriptions)
        assertEquals(AlbumOcrPending.State.QUEUED, visible.last())
    }

    @Test fun observesOnlyTheCurrentWakeAndClearsBetweenGenerationsAndAtTerminal() = runTest {
        val targets = MutableStateFlow<SavedOcrWork.Target?>(null)
        val first = MutableStateFlow(listOf(State.ENQUEUED))
        val second = MutableStateFlow(emptyList<State>())
        val opened = mutableListOf<SavedOcrWork.Target>()
        val visible = mutableListOf<AlbumOcrPending.State?>()
        backgroundScope.launch {
            AlbumOcrPending.observe(targets) { target ->
                opened.add(target)
                if (target == original) first else second
            }.collect { visible.add(it) }
        }
        runCurrent()
        assertEquals(listOf<AlbumOcrPending.State?>(null), visible)
        assertEquals(emptyList<SavedOcrWork.Target>(), opened)
        targets.value = original
        runCurrent()
        assertEquals(AlbumOcrPending.State.QUEUED, visible.last())
        targets.value = replacement
        runCurrent()
        assertEquals(null, visible.last())
        first.value = listOf(State.RUNNING)
        runCurrent()
        assertEquals(null, visible.last())
        second.value = listOf(State.RUNNING)
        runCurrent()
        assertEquals(AlbumOcrPending.State.ACTIVE_WAKE, visible.last())
        second.value = listOf(State.SUCCEEDED)
        runCurrent()
        assertEquals(null, visible.last())
        assertEquals(listOf(original, replacement), opened)
    }
}
