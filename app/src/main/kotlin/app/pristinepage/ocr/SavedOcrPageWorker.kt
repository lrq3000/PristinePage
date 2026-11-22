package app.pristinepage.ocr

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.pristinepage.data.repository.SavedOcrWork

/** Application-owned execution entry. An absent registry/engine must not start Room work or guess a new configuration. */
internal interface SavedOcrWorkerHost {
    suspend fun executeSavedOcr(target: SavedOcrWork.Target): SavedOcrWork.Outcome?
}

/** Keep the standard two-argument constructor so the existing WorkManager initializer needs no new factory or manifest. */
internal class SavedOcrPageWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = OcrPageDelivery { target ->
        // No application graph access until the transport has admitted both canonical IDs.
        (applicationContext as? SavedOcrWorkerHost)?.executeSavedOcr(target)
    }.deliver(inputData).asWorkerResult()
}
