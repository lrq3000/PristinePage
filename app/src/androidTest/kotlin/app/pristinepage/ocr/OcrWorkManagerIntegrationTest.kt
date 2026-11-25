package app.pristinepage.ocr

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.pristinepage.PristinePageApplication
import app.pristinepage.data.library.LibraryActivation
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.PageId
import app.pristinepage.data.repository.SavedOcrWork
import com.google.common.util.concurrent.ListenableFuture
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Android WorkManager transport with unbound synthetic IDs; no scan, OCR result or retained library is changed. */
@RunWith(AndroidJUnit4::class)
class OcrWorkManagerIntegrationTest {
    @Test fun sameActivationKeepsTheLiveWakeButAnotherActivationDoesNotShareIt() = runBlocking {
        val context = standardTarget()
        val manager = WorkManager.getInstance(context)
        val target = newTarget()
        val original = ActivationBoundOcrWake(newActivation(), target)
        val duplicate = OcrPageEnrollment.forWake(original)
        val successor = OcrPageEnrollment.forWake(original.copy(activation = original.activation.copy(number = 2)))
        val held = OneTimeWorkRequest.Builder(SavedOcrPageWorker::class.java)
            .setInputData(original.encode())
            .setInitialDelay(3, TimeUnit.MINUTES) // A real ENQUEUED hold makes the KEEP race deterministic.
            .build()
        try {
            manager.enqueueUniqueWork(duplicate.name, ExistingWorkPolicy.KEEP, held).result.awaitBounded()
            assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoById(held.id).awaitBounded()?.state)
            duplicate.enqueue(manager).result.awaitBounded()
            assertNull(manager.getWorkInfoById(duplicate.request.id).awaitBounded())
            assertEquals(listOf(held.id), manager.getWorkInfosForUniqueWork(duplicate.name).awaitBounded().map { it.id })
            assertNotEquals(duplicate.name, successor.name)
            successor.enqueue(manager).result.awaitBounded()
            requireTerminal(manager, successor.request.id, WorkInfo.State.SUCCEEDED)
            assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoById(held.id).awaitBounded()?.state)
        } finally {
            cancelUnfinished(manager, held.id, duplicate.request.id, successor.request.id)
        }
        assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfoById(held.id).awaitBounded()?.state)
    }

    @Test fun invalidInputFailsBeforeExecutingTheStandardOcrWorker() = runBlocking {
        val context = standardTarget()
        val manager = WorkManager.getInstance(context)
        val target = newTarget()
        val database = context.getDatabasePath("pristinepage.db")
        val existedBefore = database.exists()
        val unwantedField = Data.Builder().putAll(ActivationBoundOcrWake(newActivation(), target).encode()).putBoolean("fixture_extra", true).build()
        val invalid = OneTimeWorkRequest.Builder(SavedOcrPageWorker::class.java).setInputData(unwantedField).build()
        try {
            manager.enqueue(invalid).result.awaitBounded()
            requireTerminal(manager, invalid.id, WorkInfo.State.FAILED)
        } finally {
            cancelUnfinished(manager, invalid.id)
        }
        assertEquals(existedBefore, database.exists())
    }

    @Test fun coldLegacyAndStaleWakesLeaveAnExistingDefaultGraphUnopened() = runBlocking {
        val app = standardTarget() as PristinePageApplication
        val manager = WorkManager.getInstance(app)
        val services = app.services
        val recorded = checkNotNull(services.recordedDefaultActivation())
        assertTrue(app.getDatabasePath("pristinepage.db").isFile)
        val cachedGraph = services.javaClass.getDeclaredField("initialized").apply { isAccessible = true }
        assertNull("No foreground or worker must initialize the graph before this cold fixture", cachedGraph.get(services))
        assertFalse(app.ocrRuntimeReady.value)
        val target = newTarget()
        val legacy = Data.Builder().putString("page", target.page.value).putString("job", target.job.value).build()
        val request = OneTimeWorkRequest.Builder(SavedOcrPageWorker::class.java).setInputData(legacy).build()
        val staleAt = recorded.copy(number = if (recorded.number == 1L) 2L else 1L)
        val stale = OcrPageEnrollment.forWake(ActivationBoundOcrWake(staleAt, target))
        try {
            manager.enqueue(request).result.awaitBounded()
            requireTerminal(manager, request.id, WorkInfo.State.SUCCEEDED)
            stale.enqueue(manager).result.awaitBounded()
            requireTerminal(manager, stale.request.id, WorkInfo.State.SUCCEEDED)
            assertFalse(app.ocrRuntimeReady.value)
            assertNull("Legacy or stale delivery initialized the existing library", cachedGraph.get(services))
        } finally {
            cancelUnfinished(manager, request.id, stale.request.id)
        }
    }

    @Test fun pendingTileUsesOnlyTheLiveWakeForItsSuppliedGeneration() = runBlocking {
        val context = standardTarget()
        val manager = WorkManager.getInstance(context)
        val target = newTarget()
        val activation = newActivation()
        val wake = ActivationBoundOcrWake(activation, target)
        val held = OneTimeWorkRequest.Builder(SavedOcrPageWorker::class.java)
            .setInputData(wake.encode())
            .setInitialDelay(3, TimeUnit.MINUTES)
            .build()
        val observed = MutableStateFlow<SavedOcrWork.Target?>(target)
        try {
            manager.enqueueUniqueWork(wake.uniqueName(), ExistingWorkPolicy.KEEP, held).result.awaitBounded()
            assertEquals(AlbumOcrPending.State.QUEUED, withTimeout(5_000) { AlbumOcrPending.withWorkManager(context, observed, activation).first { it != null } })
        } finally {
            cancelUnfinished(manager, held.id)
        }
        assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfoById(held.id).awaitBounded()?.state)
    }

    private fun standardTarget(): Context {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        assertEquals("app.pristinepage.debug.test", instrumentation.context.packageName)
        assertEquals("app.pristinepage.debug", context.packageName)
        assertTrue(context is PristinePageApplication)
        return context
    }

    private suspend fun requireTerminal(manager: WorkManager, id: UUID, expected: WorkInfo.State) {
        val finished = withTimeout(30_000) {
            var observed: WorkInfo?
            do {
                observed = manager.getWorkInfoById(id).awaitBounded()
                if (observed?.state?.isFinished != true) delay(75)
            } while (observed?.state?.isFinished != true)
            checkNotNull(observed)
        }
        assertEquals(expected, finished.state)
        assertEquals(Data.EMPTY, finished.outputData)
        assertEquals("WorkManager starts, not Room recognition starts", 1, finished.runAttemptCount)
    }

    // Cancel only these synthetic UUIDs, including a wake that may still be queued after an assertion fails.
    private suspend fun cancelUnfinished(manager: WorkManager, vararg ids: UUID) = withContext(NonCancellable) {
        for (id in ids) {
            if (manager.getWorkInfoById(id).awaitBounded()?.state?.isFinished == false) {
                manager.cancelWorkById(id).result.awaitBounded()
            }
        }
    }

    private suspend fun <T> ListenableFuture<T>.awaitBounded(): T =
        withContext(Dispatchers.IO) { get(5, TimeUnit.SECONDS) }

    private fun newTarget() = SavedOcrWork.Target(PageId(UUID.randomUUID().toString()), JobId(UUID.randomUUID().toString()))

    private fun newActivation() = LibraryActivation(UUID.randomUUID().toString(), 1)
}
