package app.pristinepage.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.core.ocr.OcrLanguagePack
import app.pristinepage.core.ocr.OcrLanguagePacks
import app.pristinepage.core.ocr.OcrLayout
import app.pristinepage.core.ocr.OcrText
import app.pristinepage.data.model.IngestResult
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.repository.FileOperationOwners
import app.pristinepage.data.repository.LeaseOwnerState
import app.pristinepage.data.repository.SavedOcrWork
import app.pristinepage.data.repository.SavedOcrWork.Outcome
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android VM interruption, native owner proof and durable Room recovery; recognition itself is fixture-supplied. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28)
class OcrProcessDeathIntegrationTest {
    @Test fun liveForeignOcrOwnerDefersThenActualDeathResumesOriginalJob() = runBlocking { exercise(1L) }

    @Test fun actualThirdOcrProcessDeathKeepsTerminalInterruptionAndRejectsFourthStart() = runBlocking { exercise(3L) }

    private suspend fun exercise(ordinal: Long) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals(OcrCrashFiles.PACKAGE, instrumentation.context.packageName)
        assertEquals(instrumentation.context.packageName, context.packageName)
        val token = UUID.randomUUID().toString()
        val files = OcrCrashFiles(context, token, create = true)
        val proof = FileOperationOwners(files.ownerRoot)
        lateinit var running: WorkState
        val (document, target) = files.open(allowFixtureIngest = true).use { store ->
            val document = store.documents.createDocument("Synthetic OCR process-death witness")
            val batch = store.imports.beginImport(document, store.catalog.load(document).document.documentRevision, null)
            val ref = IngestCrashSupport.REFERENCE
            val selected = store.selections.prepareSelection(batch.parentId, listOf(ref)).childIds.single()
            val saved = store.sources.ingestBoundProvider(ref, selected) as IngestResult.Committed
            val ocr = store.savedOcr
            val target = ocr.reconcile(original, null, 20).targets.single()
            assertEquals(saved.pageId, target.page)
            repeat((ordinal - 1).toInt()) { index ->
                assertEquals(Outcome.FAILURE_RECORDED, ocr.execute(target) { _, config, handoff ->
                    assertEquals(original, config)
                    handoff.retain(prepared {
                        throw RepositoryFailure(OperationError(OperationErrorCode.PROCESSING_FAILED, true, "ocr_fixture_failure", null))
                    })
                })
                assertEquals(WorkState("ADMITTED", index + 1L, index + 1L, "ENGINE_FAILED", null, null, null, 0),
                    state(files, target))
            }
            armAndStopChild(context, token, target) {
                val before = state(files, target)
                assertEquals("RUNNING", before.phase)
                assertEquals(ordinal, before.starts)
                assertEquals(ordinal, before.generation)
                assertNull(before.error)
                assertNull(before.producer)
                assertEquals(0L, before.resultCount)
                assertTrue(checkNotNull(before.deadline) > SystemClock.elapsedRealtime())
                val owner = checkNotNull(before.owner)
                assertEquals(LeaseOwnerState.UNKNOWN, proof.state(owner))
                assertEquals(LeaseOwnerState.ALIVE, proof.refresh(owner))
                assertEquals(Outcome.WAITING_FOR_OWNER, ocr.execute(target, neverPrepare))
                assertEquals(before, state(files, target))
                assertTrue(ocr.find(QUERY, null, 20).hits.isEmpty())
                running = before
            }
            document to target
        }
        files.open().use { store ->
            val ocr = store.savedOcr
            val ended = checkNotNull(running.owner)
            assertEquals(running, state(files, target))
            assertTrue(checkNotNull(running.deadline) > SystemClock.elapsedRealtime())
            assertEquals(LeaseOwnerState.UNKNOWN, proof.state(ended))
            // A changed default is not permission to replace this saved job or substitute pack order after death.
            assertEquals(target, ocr.reconcile(changedDefault, null, 20).targets.single())
            var preparedCount = 0
            val preparer = if (ordinal == 3L) neverPrepare else SavedOcrWork.Preparer { page, config, handoff ->
                assertEquals(target.page, page.pageId)
                assertEquals(original, config)
                preparedCount++
                val readmitted = state(files, target)
                assertEquals(WorkState("ADMITTED", 1L, 1L, "INTERRUPTED", null, null, null, 0), readmitted)
                handoff.retain(prepared {
                    val restarted = state(files, target)
                    assertEquals(2L, restarted.starts)
                    assertEquals(2L, restarted.generation)
                    assertEquals("RUNNING", restarted.phase)
                    assertNotEquals(ended, checkNotNull(restarted.owner))
                    OcrText("Synthetic recovery receipt")
                })
            }
            val outcome = withTimeout(15_000) {
                var decision: Outcome
                do {
                    // Binder death alone authorizes nothing. Wait only for the actual dispatch's positive native lock probe.
                    decision = ocr.execute(target, preparer)
                    if (decision == Outcome.WAITING_FOR_OWNER) delay(25)
                } while (decision == Outcome.WAITING_FOR_OWNER)
                decision
            }
            assertEquals(LeaseOwnerState.ENDED, proof.state(ended))
            val expected = if (ordinal == 3L) {
                assertEquals(Outcome.TERMINAL, outcome)
                assertEquals(0, preparedCount)
                assertTrue(ocr.find(QUERY, null, 20).hits.isEmpty())
                WorkState("FAILED", 3L, 3L, "INTERRUPTED", null, null, null, 0)
            } else {
                assertEquals(Outcome.PUBLISHED, outcome)
                assertEquals(1, preparedCount)
                val hit = ocr.find(QUERY, null, 20).hits.single()
                assertEquals(document, hit.document)
                assertEquals(target.page, hit.page)
                assertTrue(ocr.canOpen(hit))
                WorkState("SUCCEEDED", 2L, 2L, null, null, null, target.job.value, 1)
            }
            assertEquals(expected, state(files, target))
            assertTrue(ocr.reconcile(changedDefault, null, 20).targets.isEmpty())
            assertEquals(Outcome.TERMINAL, ocr.execute(target, neverPrepare))
            assertEquals(expected, state(files, target))
        }
    }

    private suspend fun armAndStopChild(context: Context, token: String, target: SavedOcrWork.Target, whileLive: suspend () -> Unit) {
        val online = CompletableDeferred<Messenger>()
        val ready = CompletableDeferred<Int>()
        val ack = CompletableDeferred<Int>()
        val dead = CompletableDeferred<Unit>()
        val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
            when (message.what) {
                OcrCrashMessages.READY -> ready.complete(message.data.getInt("pid"))
                OcrCrashMessages.HALT_ACK -> ack.complete(message.data.getInt("pid"))
                OcrCrashMessages.FAILED -> {
                    val failure = AssertionError("OCR fixture did not remain armed at its real recognition boundary")
                    ready.completeExceptionally(failure)
                    ack.completeExceptionally(failure)
                }
            }
            true
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                try {
                    binder.linkToDeath({ dead.complete(Unit) }, 0)
                    online.complete(Messenger(binder))
                } catch (_: RemoteException) {
                    online.completeExceptionally(AssertionError("OCR fixture died before it could be armed"))
                }
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit // Only this binder's death recipient satisfies the witness.
        }
        val intent = Intent(context, OcrCrashFixtureService::class.java)
            .putExtra("token", token).putExtra("page", target.page.value).putExtra("job", target.job.value)
        val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        var child: Messenger? = null
        try {
            assertTrue("Non-exported OCR fixture did not bind", bound)
            val connected = withTimeout(15_000) { online.await() }
            child = connected
            connected.send(Message.obtain(null, OcrCrashMessages.START).apply { replyTo = reply })
            val pid = withTimeout(20_000) { ready.await() }
            assertTrue(pid > 0 && pid != Process.myPid())
            whileLive()
            connected.send(Message.obtain(null, OcrCrashMessages.HALT))
            assertEquals(pid, withTimeout(15_000) { ack.await() })
            withTimeout(15_000) { dead.await() }
        } finally {
            if (!dead.isCompleted) try { child?.send(Message.obtain(null, OcrCrashMessages.HALT)) } catch (_: RemoteException) { }
            if (bound) context.unbindService(connection)
        }
    }

    private data class WorkState(val phase: String, val starts: Long, val generation: Long, val error: String?,
        val owner: String?, val deadline: Long?, val producer: String?, val resultCount: Long)

    private fun state(files: OcrCrashFiles, target: SavedOcrWork.Target): WorkState =
        SQLiteDatabase.openDatabase(files.database.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("""SELECT j.state,j.attempt,j.leaseGeneration,j.errorCode,j.leaseOwner,j.leaseUntil,w.resultJobId,
                (SELECT COUNT(*) FROM OcrResult r WHERE r.pageId=w.pageId)
                FROM OcrPageWork w JOIN Job j ON j.jobId=w.jobId
                WHERE w.pageId=? AND w.jobId=? AND j.kind='PAGE_OCR'""", arrayOf(target.page.value, target.job.value)).use {
                assertTrue(it.moveToFirst())
                WorkState(it.getString(0), it.getLong(1), it.getLong(2), it.getString(3), it.getString(4),
                    if (it.isNull(5)) null else it.getLong(5), it.getString(6), it.getLong(7)).also { _ -> assertFalse(it.moveToNext()) }
            }
        }

    private fun prepared(recognition: () -> OcrText): SavedOcrWork.Prepared = object : SavedOcrWork.Prepared {
        override suspend fun recognize(): OcrText = recognition()
        override fun close() = Unit
    }

    private companion object {
        const val QUERY = "recovery receipt"
        val english = OcrLanguagePack("eng", "a".repeat(64))
        val french = OcrLanguagePack("fra", "b".repeat(64))
        val original = OcrConfiguration("fixture-engine", 1, OcrLayout.SINGLE_BLOCK, OcrLanguagePacks(listOf(french, english)))
        val changedDefault = OcrConfiguration("fixture-engine", 1, OcrLayout.AUTOMATIC, OcrLanguagePacks(listOf(english, french)))
        val neverPrepare = SavedOcrWork.Preparer { _, _, _ -> throw AssertionError("An unauthorized native start reached preparation") }
    }
}
