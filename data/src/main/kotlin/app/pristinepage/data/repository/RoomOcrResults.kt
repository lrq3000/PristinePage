package app.pristinepage.data.repository

import android.os.SystemClock
import app.pristinepage.core.ocr.OcrText
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Binds the existing completion fence to the owning Room store. Only a live worker with the exact retained inputs may submit its result. */
internal class RoomOcrResults(
    database: PristineDatabase,
    private val owners: LeaseOwnerLiveness,
    auditClock: () -> Long = System::currentTimeMillis,
    leaseClock: () -> Long = SystemClock::elapsedRealtime,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val transactions = JobLeaseTransactions(RoomMetadataAccess(database), database.jobLeases(), auditClock, leaseClock)
    private val attempts = RoomOcrAttempts(database, owners, auditClock, leaseClock, dispatcher)
    private val results = database.ocrResults()
    private val work = database.ocrPageWork()

    suspend fun publish(request: RoomOcrAttempts.Request, lease: JobLease, text: OcrText) {
        // This snapshot belongs only to this completion call and to the enclosing guarded transaction.
        var admitted: RoomOcrCurrentWork.Current? = null
        RoomOcrJobCompletion(
            transactions, owners,
            currentBinding = { id ->
                (if (id == request.jobId) attempts.readPinnedInTransaction(request) else null)
                    .also { admitted = it }?.intent
            },
            boundedJob = { id, expectedBytes ->
                admitted?.job?.takeIf { it.jobId == id.value && it.input.size == expectedBytes }
            },
            persist = { row ->
                val before = admitted ?: badWrite(request)
                val job = before.job
                // The old producer is part of the expected pointer, not inferred from the currently effective engine or preferences.
                if (!results.writeResult(row) || work.publishResult(request.page.value, job.jobId, before.intent.generation,
                        job.canonicalInputSha256, job.input.size, before.resultJobId?.value, before.projectionVersion, PROJECTION_VERSION) != 1
                ) badWrite(request)
            },
            storedResultMatches = { row ->
                val after = admitted
                after != null && after.resultJobId == request.jobId && after.projectionVersion == PROJECTION_VERSION && results.matchesResult(row)
            },
            dispatcher = dispatcher,
        ).complete(request.binding, lease, text)
    }

    private fun badWrite(request: RoomOcrAttempts.Request): Nothing = throw RepositoryFailure(
        OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "ocr_result_write_unverified", request.jobId.value),
    )

    companion object {
        // NFC, selective kana fold and ordered Han/kana/Hangul segmentation. Any incompatible projection change needs an explicit rebuild.
        const val PROJECTION_VERSION = 1
    }
}
