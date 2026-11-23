package app.pristinepage.ocr

import androidx.work.Data
import androidx.work.ListenableWorker
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.PageId
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.repository.SavedOcrWork
import app.pristinepage.data.repository.SavedOcrWork.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** WorkManager transports an exact opaque hint. It neither owns the durable attempt count nor resolves engine settings. */
internal class OcrPageDelivery(private val executeTarget: suspend (SavedOcrWork.Target) -> Outcome?) {
    enum class Result {
        DONE, RETRY, REJECT;

        // Deliberately never include a RepositoryFailure, OCR text, query, or saved-page path in output data.
        fun asWorkerResult(): ListenableWorker.Result = when (this) {
            DONE -> ListenableWorker.Result.success()
            RETRY -> ListenableWorker.Result.retry()
            REJECT -> ListenableWorker.Result.failure()
        }
    }

    suspend fun deliver(input: Data): Result {
        val active = currentCoroutineContext()
        active.ensureActive()
        val target = decode(input) ?: return Result.REJECT
        val result = try {
            when (executeTarget(target)) {
                null, Outcome.PUBLISHED, Outcome.OBSOLETE, Outcome.TERMINAL -> Result.DONE
                // A final recorded failure can cause a harmless terminal lookup on the next wake; it cannot grant a fourth start.
                Outcome.BUSY, Outcome.WAITING_FOR_OWNER, Outcome.FAILURE_RECORDED -> Result.RETRY
            }
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (failure: RepositoryFailure) {
            when (failure.error.code) {
                // The configuration/availability change and launch paths can reconcile this existing Room work later.
                OperationErrorCode.PROVIDER_UNAVAILABLE -> Result.DONE
                OperationErrorCode.CANCELED -> throw CancellationException("OCR delivery canceled")
                // Unexpected processing faults can leave a started job RUNNING. Let the next delivery recover it through Room's exact ended-owner proof.
                OperationErrorCode.STORAGE_FAILED, OperationErrorCode.RESOURCE_LIMIT, OperationErrorCode.PROCESSING_FAILED ->
                    if (failure.error.retryable) Result.RETRY else Result.REJECT
                else -> Result.REJECT
            }
        } catch (_: Exception) {
            Result.REJECT
        }
        active.ensureActive()
        return result
    }

    companion object {
        private const val PAGE = "page"
        private const val JOB = "job"

        fun uniqueName(target: SavedOcrWork.Target): String = "saved-ocr-page-v1/${target.job.value}"

        fun encode(target: SavedOcrWork.Target): Data =
            Data.Builder().putString(PAGE, target.page.value).putString(JOB, target.job.value).build()

        fun decode(input: Data): SavedOcrWork.Target? {
            val fields = input.keyValueMap
            if (fields.size != 2) return null
            val page = fields[PAGE] as? String ?: return null
            val job = fields[JOB] as? String ?: return null
            if (page.length != 36 || job.length != 36) return null
            return try {
                SavedOcrWork.Target(PageId(page), JobId(job))
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
