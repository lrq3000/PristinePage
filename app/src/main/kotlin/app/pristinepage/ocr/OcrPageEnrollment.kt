package app.pristinepage.ocr

import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Uniqueness uses the issuing activation and durable job, not just a page or Activity. */
internal class OcrPageEnrollment private constructor(val name: String, val request: OneTimeWorkRequest) {
    val policy = ExistingWorkPolicy.KEEP

    fun enqueue(manager: WorkManager): Operation = manager.enqueueUniqueWork(name, policy, request)

    companion object {
        /** Default local constraints: no network, no foreground service, no expedited wake. Room still caps actual recognition starts. */
        fun forWake(wake: ActivationBoundOcrWake): OcrPageEnrollment = OcrPageEnrollment(
            wake.uniqueName(),
            OneTimeWorkRequest.Builder(SavedOcrPageWorker::class.java)
                .setInputData(wake.encode())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }
}
