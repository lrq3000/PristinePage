package app.pristinepage.ocr

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.WorkerParameters
import app.pristinepage.data.library.LibraryActivation
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.PageId
import app.pristinepage.data.repository.SavedOcrWork
import java.lang.reflect.Modifier
import org.junit.Assert.*
import org.junit.Test

class OcrPageEnrollmentTest {
    @Test fun requestUsesTheNativeWorkerWithOfflineConstraintsAndOpaqueInputs() {
        val target = SavedOcrWork.Target(PageId("aaaaaaaa-aaaa-4aaa-8aaa-000000000001"), JobId("aaaaaaaa-aaaa-4aaa-8aaa-000000000002"))
        val library = "aaaaaaaa-aaaa-4aaa-8aaa-000000000003"
        val enrollment = OcrPageEnrollment.forWake(ActivationBoundOcrWake(LibraryActivation(library, 2), target))
        val request = enrollment.request
        val spec = request.workSpec
        assertEquals("saved-ocr-page-v2/$library/2/${target.job.value}", enrollment.name)
        assertEquals(ExistingWorkPolicy.KEEP, enrollment.policy)
        assertEquals(SavedOcrPageWorker::class.java.name, spec.workerClassName)
        assertEquals(mapOf("library" to library, "activation" to 2L, "page" to target.page.value, "job" to target.job.value), spec.input.keyValueMap)
        assertEquals(NetworkType.NOT_REQUIRED, spec.constraints.requiredNetworkType)
        assertFalse(spec.expedited)
        assertEquals(30_000L, spec.backoffDelayDuration)
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
    }

    @Test fun workerExposesTheDefaultWorkManagerConstructor() {
        val type = SavedOcrPageWorker::class.java
        assertTrue(CoroutineWorker::class.java.isAssignableFrom(type))
        assertTrue(Modifier.isPublic(type.getConstructor(Context::class.java, WorkerParameters::class.java).modifiers))
    }
}
