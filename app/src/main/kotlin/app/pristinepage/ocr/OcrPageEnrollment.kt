package app.pristinepage.ocr

import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkManager
import app.pristinepage.data.repository.SavedOcrWork
import java.util.concurrent.TimeUnit

/** The durable job ID, rather than a page or Activity, is the WorkManager uniqueness boundary. */
internal class OcrPageEnrollment private constructor(val name: String, val request: OneTimeWorkRequest) {
    val policy = ExistingWorkPolicy.KEEP

    fun enqueue(manager: WorkManager): Operation = manager.enqueueUniqueWork(name, policy, request)

    companion object {
        /** Default local constraints: no network, no foreground service, no expedited wake. Room still caps actual recognition starts. */
        fun forTarget(target: SavedOcrWork.Target): OcrPageEnrollment = OcrPageEnrollment(
            OcrPageDelivery.uniqueName(target),
            OneTimeWorkRequest.Builder(SavedOcrPageWorker::class.java)
                .setInputData(OcrPageDelivery.encode(target))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }
}
