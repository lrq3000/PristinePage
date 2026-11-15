package app.pristinepage.data.repository

import android.net.Uri
import app.pristinepage.data.files.SourceFiles
import app.pristinepage.data.files.SourceInspector
import app.pristinepage.data.journal.IngestJournalCodec
import app.pristinepage.data.model.*
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One bounded provider copy at a time across store instances in this process. This accounts for the 64KiB copy buffer, not decoder/native allocation: the injected inspector must separately admit full normalization before allocating. Unknown old owners and interrupted files require recovery, never blind CREATE_NEW retries. */
class RoomSourceStore @JvmOverloads constructor(private val files: SourceFiles, private val provider: SourceInput, private val inspector: SourceInspector, private val preparation: IngestPreparation, private val lookup: IngestOperationLookup, private val replay: IngestReplay, private val completion: IngestCompletion, private val phases: IngestPhases, private val leases: JobLeaseCommands, private val owners: OperationOwners, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) : SourceStore, BoundSourceIngest {
    override suspend fun ingest(uri: Uri, session: ImportSessionId): IngestResult = ingestBoundProvider(uri.toString(), session)

    override suspend fun ingestBoundProvider(boundReference: String, session: ImportSessionId): IngestResult = withContext(dispatcher) {
        try {
            coroutineContext.ensureActive()
            // Repeated committed callbacks use private bytes, not today's provider grant. Active callbacks must still pass preparation's exact URI/session checks before executing anything.
            val recorded = lookup.find(session)
            if (recorded != null) {
                val result = replay.replay(recorded)
                if (result !is IngestResult.Pending) return@withContext result
                if (result.recoveryState != FileTransactionState.INTENT) return@withContext result
            }
            val intent = preparation.prepare(session, boundReference)
            copyAdmission.withLock {
                coroutineContext.ensureActive()
                val result = replay.replay(intent)
                if (result !is IngestResult.Pending || result.recoveryState != FileTransactionState.INTENT) return@withLock result
                owners.run { owner ->
                    if (!phases.admit(intent)) return@run replay.replay(intent)
                    val acquired = leases.acquireAdmitted(intent.jobId, owner, 300_000L)
                    when (acquired) {
                        is AcquireLeaseResult.Busy -> replay.replay(intent)
                        is AcquireLeaseResult.Rejected -> IngestResult.Rejected(session, acquired.error)
                        is AcquireLeaseResult.Granted -> execute(intent, acquired.lease, boundReference)
                    }
                }
            }
        } catch (canceled: CancellationException) { throw canceled }
        catch (failure: RepositoryFailure) { IngestResult.Rejected(session, failure.error) }
    }

    private suspend fun execute(intent: IngestJournalIntent, lease: JobLease, boundReference: String): IngestResult {
        try {
            val context = kotlinx.coroutines.currentCoroutineContext()
            context.ensureActive()
            if (!phases.copying(intent, lease)) return replay.replay(intent)
            // No suspension between opening the provider and transferring close ownership to SourceFiles. Checks wrap reads, not just coroutine dispatch boundaries; blocked providers may remain uninterruptible until their read returns.
            val input = providerCall(intent.sessionId) { provider.open(boundReference) }
            val stream = CheckedInput(input, context, intent.sessionId)
            val staged = files.stage(intent.transactionId.value, intent.sourceBlobId.value, stream, MAX_BYTES)
            context.ensureActive()
            val inspection = try { inspector.inspect(staged.stagingFile, staged.byteSha256) }
            catch (canceled: CancellationException) { throw canceled }
            catch (failure: RepositoryFailure) { throw failure }
            catch (_: Exception) { throw RepositoryFailure(OperationError(OperationErrorCode.PROCESSING_FAILED, false, "source_inspection_failed", intent.sessionId.value)) }
            context.ensureActive()
            val sealed = files.seal(staged, IngestJournalCodec.encodeSeal(IngestJournalSeal(intent, inspection, staged.byteSha256, staged.byteCount)))
            if (!phases.sealed(intent, lease, sealed)) return replay.replay(intent)
            context.ensureActive()
            files.move(sealed)
            context.ensureActive()
            if (!phases.moved(intent, lease)) return replay.replay(intent)
            return completion.commit(intent, lease)
        } catch (canceled: CancellationException) {
            // Stream/inspection scopes have already unwound. This synchronous, fenced, metadata-only write can run in a canceled coroutine; it cannot manufacture ownership after lease loss.
            recordFailure(intent, lease, OperationError(OperationErrorCode.CANCELED, false, "ingest_canceled", intent.sessionId.value))
            throw canceled
        } catch (failure: RepositoryFailure) { return fail(intent, lease, failure.error) }
        catch (_: SecurityException) { return fail(intent, lease, OperationError(OperationErrorCode.PERMISSION_LOST, false, "import_permission_lost", intent.sessionId.value)) }
        catch (_: IllegalArgumentException) { return fail(intent, lease, OperationError(OperationErrorCode.INVALID_INPUT, false, "invalid_source_bytes", intent.sessionId.value)) }
        catch (_: IOException) { return fail(intent, lease, OperationError(OperationErrorCode.STORAGE_FAILED, true, "ingest_io_failed", intent.sessionId.value)) }
    }

    private suspend fun fail(intent: IngestJournalIntent, lease: JobLease, error: OperationError): IngestResult {
        // The transaction may retain a prior independent cancellation instead of this later IO error. Return the persisted outcome, not the superseded exception that prompted unwinding.
        if (recordFailure(intent, lease, error)) return replay.replay(intent)
        return if (error.retryable) replay.replay(intent) else IngestResult.Rejected(intent.sessionId, error)
    }

    private fun recordFailure(intent: IngestJournalIntent, lease: JobLease, error: OperationError): Boolean = try { phases.failed(intent, lease, error) }
    catch (_: RepositoryFailure) { false /* Keep the last durable state and payload if even failure journaling cannot commit; recovery owns the discrepancy. */ }

    private class CheckedInput(input: InputStream, private val context: CoroutineContext, private val session: ImportSessionId) : FilterInputStream(input) {
        private var count = 0L
        private var closed = false
        override fun read(): Int { context.ensureActive(); val value = providerCall(session) { `in`.read() }; if (value >= 0) account(1); context.ensureActive(); return value }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int { context.ensureActive(); val size = providerCall(session) { `in`.read(buffer, offset, length) }; if (size > 0) account(size); context.ensureActive(); return size }
        private fun account(size: Int) {
            if (size.toLong() > MAX_BYTES - count) throw RepositoryFailure(OperationError(OperationErrorCode.RESOURCE_LIMIT, false, "encoded_source_limit", session.value))
            count += size
        }
        override fun close() { if (!closed) { closed = true; providerCall(session) { `in`.close() } } }
    }

    private companion object {
        const val MAX_BYTES = 104857600L
        val copyAdmission = Mutex()

        /** Normalize only provider-origin failures, before private file IO can see them. Missing/revoked/failed provider content needs a new readable selection, not a blind retry into an existing partial attempt. Fatal VM errors and coroutine cancellation are never swallowed. */
        inline fun <T> providerCall(session: ImportSessionId, action: () -> T): T = try { action() }
        catch (canceled: CancellationException) { throw canceled }
        catch (failure: RepositoryFailure) { throw failure }
        catch (_: SecurityException) { throw RepositoryFailure(OperationError(OperationErrorCode.PERMISSION_LOST, false, "import_permission_lost", session.value)) }
        catch (_: Exception) { throw RepositoryFailure(OperationError(OperationErrorCode.SOURCE_UNAVAILABLE, false, "import_source_unavailable", session.value)) }
    }
}
