package app.pristinepage.data.repository

import app.pristinepage.data.db.OcrTextPublicationRow
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.requireOpaqueId
import app.pristinepage.data.repository.SavedOcrWork.TextSaveOutcome
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Durable authorization for one foreground attempt; never opens a provider or reacquires a lost grant. */
internal class RoomOcrTextPublication(
    private val store: PrivateImportStore.OcrComposition,
    private val exports: RoomOcrTextExport,
) {
    private val dao = store.database.ocrTextPublication()
    private val metadata = RoomMetadataAccess(store.database)

    class Recovery(val target: String?, val retry: RoomOcrTextExport.Receipt?)

    suspend fun publish(
        receipt: RoomOcrTextExport.Receipt,
        choose: suspend (String) -> String?,
        transfer: suspend (String, ByteArray) -> TextSaveOutcome,
    ): TextSaveOutcome = store.owners.run { owner ->
        val id = UUID.randomUUID().toString()
        val intent = receipt.intent
        val tx = intent.transaction.value
        val active = currentCoroutineContext()
        fun verify(phase: String, uri: String?) {
            active.ensureActive()
            check(store.owners.state(owner) == LeaseOwnerState.ALIVE)
            exports.verifyCommitted(receipt, active::ensureActive)
            if (!dao.matches(id, tx, owner, intent.textSha256, intent.byteCount, phase, uri)) mismatch()
            active.ensureActive()
        }
        fun change(previous: String, next: String, before: String?, after: String?): Boolean {
            verify(previous, before)
            if (dao.advance(id, tx, owner, intent.textSha256, intent.byteCount, previous, next, before, after,
                    System.currentTimeMillis()) != 1) return false
            verify(next, after)
            return true
        }
        suspend fun advance(previous: String, next: String, before: String?, after: String?) = withContext(Dispatchers.IO) {
            metadata.transaction { if (!change(previous, next, before, after)) mismatch() }
        }
        try {
            withContext(Dispatchers.IO) {
                metadata.transaction {
                    exports.verifyCommitted(receipt, active::ensureActive)
                    // Only failed/canceled attempts may take new consent on the same frozen artifact. Keep their prior target history.
                    if (!dao.canStart(tx)) mismatch()
                    dao.insert(OcrTextPublicationRow(id, tx, owner, intent.textSha256, intent.byteCount,
                        "WAITING_DESTINATION", null, System.currentTimeMillis()))
                    verify("WAITING_DESTINATION", null)
                }
            }
            val target = choose(id)
            if (target == null) {
                advance("WAITING_DESTINATION", "CANCELED", null, null)
                return@run TextSaveOutcome.CANCELED
            }
            require(target.startsWith("content://") && target.length in 11..4096 &&
                target.none(Char::isISOControl) && target.encodeToByteArray().size <= 4096)
            // Bind the selected document immediately. It may already exist even if private verification now fails.
            val admitted = withContext(Dispatchers.IO) {
                metadata.transaction {
                    // A provider can return the same empty document under a new grant. Never consume it again for these frozen bytes.
                    val prior = dao.recordedTarget(tx, target, id)
                    if (prior != null) {
                        if (!change("WAITING_DESTINATION", "CANCELED", null, null) ||
                            dao.recordedTarget(tx, target, id) != prior) mismatch()
                        false
                    } else {
                        if (!change("WAITING_DESTINATION", "WRITING", null, target) ||
                            dao.hasRecordedTarget(tx, target, id)) mismatch()
                        true
                    }
                }
            }
            if (!admitted) return@run TextSaveOutcome.DESTINATION_REFUSED
            val bytes = try {
                exports.read(receipt)
            } catch (canceled: kotlinx.coroutines.CancellationException) {
                throw canceled
            } catch (failure: Exception) {
                advance("WRITING", TextSaveOutcome.DESTINATION_REFUSED.name, target, target)
                throw failure
            }
            try {
                active.ensureActive()
                val measured = transfer(target, bytes)
                check(measured != TextSaveOutcome.CANCELED)
                advance("WRITING", measured.name, target, target)
                measured
            } finally {
                bytes.fill(0)
            }
        } catch (failure: Exception) {
            // A canceled dispatcher hop may have already committed WRITING. If an external write is ambiguous, never repeat it.
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    val now = System.currentTimeMillis()
                    dao.endAbandoned(id, owner, "WAITING_DESTINATION", now)
                    dao.endAbandoned(id, owner, "WRITING", now)
                }
            }
            throw failure
        }
    }

    suspend fun recover(): Recovery? = withContext(Dispatchers.IO) {
        for (phase in listOf("WAITING_DESTINATION", "WRITING")) {
            var after = ""
            do {
                val pending = dao.pending(phase, after)
                for (item in pending) {
                    ensureActive()
                    if (!isId(item.consentId) || !isId(item.ownerId) || store.owners.refresh(item.ownerId) != LeaseOwnerState.ENDED) continue
                    dao.endAbandoned(item.consentId, item.ownerId, item.state, System.currentTimeMillis())
                }
                pending.lastOrNull()?.let { after = it.consentId }
            } while (pending.isNotEmpty())
        }
        val row = dao.latestUncertain() ?: return@withContext null
        val active = currentCoroutineContext()
        val retry = metadata.transaction {
            active.ensureActive()
            val frozen = dao.frozenReceipt(row.transactionId, row.byteSha256, row.byteCount) ?: return@transaction null
            val intent = try { OcrTextExportIntent.decode(frozen.payload) } catch (_: IllegalArgumentException) { return@transaction null }
            if (intent.transaction.value != row.transactionId || intent.textSha256 != row.byteSha256 || intent.byteCount != row.byteCount ||
                !dao.canStart(row.transactionId)) return@transaction null
            val receipt = RoomOcrTextExport.Receipt(intent, frozen.sealSha256)
            exports.verifyCommitted(receipt, active::ensureActive)
            receipt
        }
        Recovery(row.targetUri, retry)
    }

    private fun isId(value: String): Boolean = runCatching { requireOpaqueId(value) }.isSuccess

    private fun mismatch(): Nothing =
        throw RepositoryFailure(OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "ocr_text_publication_changed", null))
}
