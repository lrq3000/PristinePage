package app.pristinepage.ocr

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.pristinepage.PristinePageApplication
import app.pristinepage.data.repository.SavedOcrWork

/** Application-owned execution entry. An absent registry/engine must not start Room work or guess a new configuration. */
internal interface SavedOcrWorkerHost {
    suspend fun executeSavedOcr(wake: ActivationBoundOcrWake): SavedOcrWork.Outcome?
}

/** Keep the standard two-argument constructor so the existing WorkManager initializer needs no new factory or manifest. */
internal class SavedOcrPageWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = OcrPageDelivery.deliverBound(inputData) { wake ->
        // No application graph access until all four transport fields match the scoped format.
        val application = applicationContext
        val host = if (application is PristinePageApplication) application.savedOcr else application as? SavedOcrWorkerHost
        host?.executeSavedOcr(wake)
    }.asWorkerResult()
}
