package app.pristinepage.data

import android.app.Application
import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import app.pristinepage.core.ocr.OcrText
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.data.files.SourceInspector
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.PageId
import app.pristinepage.data.model.SourceInspection
import app.pristinepage.data.repository.PrivateImportStore
import app.pristinepage.data.repository.SavedOcrWork
import app.pristinepage.data.repository.SourceInput
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** This test-only service can terminate only its own dedicated VM. It accepts no kill target, document bytes or arbitrary path. */
class OcrCrashFixtureService : Service() {
    private enum class Gate { IDLE, ARMED, HALTING, EXPIRED }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val monitor = Any()
    private var gate = Gate.IDLE
    private var armedReply: Messenger? = null
    private var started = false
    private var input: Pair<String, SavedOcrWork.Target>? = null
    private val endpoint = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            OcrCrashMessages.START -> if (!started) {
                started = true
                val reply = checkNotNull(message.replyTo)
                val (token, target) = checkNotNull(input)
                scope.launch {
                    try {
                        OcrCrashFiles(this@OcrCrashFixtureService, token, create = false).open().use { store ->
                            store.savedOcr.execute(target) { _, _, handoff ->
                                handoff.retain(object : SavedOcrWork.Prepared {
                                    override suspend fun recognize(): OcrText = pauseAfterStart(reply)
                                    override fun close() = Unit
                                })
                            }
                        }
                        report(reply, OcrCrashMessages.FAILED)
                    } catch (_: Exception) {
                        report(reply, OcrCrashMessages.FAILED)
                    }
                }
            }
            OcrCrashMessages.HALT -> halt()
        }
        true
    })

    override fun onBind(intent: Intent): IBinder {
        check(inDedicatedProcess())
        val token = checkNotNull(intent.getStringExtra("token"))
        check(UUID.fromString(token).toString() == token)
        val request = token to SavedOcrWork.Target(PageId(checkNotNull(intent.getStringExtra("page"))),
            JobId(checkNotNull(intent.getStringExtra("job"))))
        check(input == null || input == request)
        input = request
        return endpoint.binder
    }

    private fun pauseAfterStart(reply: Messenger): Nothing {
        try {
            synchronized(monitor) {
                check(gate == Gate.IDLE)
                armedReply = reply
                gate = Gate.ARMED
                report(reply, OcrCrashMessages.READY)
            }
            try { CountDownLatch(1).await(45, TimeUnit.SECONDS) } catch (_: InterruptedException) { /* Expire without leaving the attempt. */ }
            synchronized(monitor) {
                if (gate == Gate.ARMED) {
                    gate = Gate.EXPIRED
                    report(reply, OcrCrashMessages.FAILED)
                }
            }
        } finally {
            // Once READY, neither a failed reply, expiry, nor delayed termination can unwind the active worker.
            terminate()
        }
    }

    private fun halt(): Nothing {
        try {
            synchronized(monitor) {
                if (gate == Gate.ARMED) {
                    gate = Gate.HALTING
                    report(checkNotNull(armedReply), OcrCrashMessages.HALT_ACK)
                }
            }
        } finally {
            terminate()
        }
    }

    private fun report(reply: Messenger, what: Int) {
        try {
            reply.send(Message.obtain(null, what).apply { data = Bundle().apply { putInt("pid", Process.myPid()) } })
        } catch (_: RemoteException) { /* A disconnected instrumentation cannot accept this run. */ }
    }

    private fun inDedicatedProcess(): Boolean = Build.VERSION.SDK_INT >= 28 &&
        packageName == OcrCrashFiles.PACKAGE && Application.getProcessName() == "$packageName:ocr_crash_fixture"

    private fun terminate(): Nothing {
        check(inDedicatedProcess())
        Process.killProcess(Process.myPid())
        while (true) try { CountDownLatch(1).await() } catch (_: InterruptedException) { /* Do not unwind. */ }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (inDedicatedProcess()) terminate()
    }
}

internal object OcrCrashMessages {
    const val START = 1200
    const val READY = 1201
    const val HALT = 1202
    const val HALT_ACK = 1203
    const val FAILED = 1204
}

/** Both VMs use the production store composition under a single fresh, test-owned directory. The bound provider route can only return the inherited synthetic input. */
internal class OcrCrashFiles(base: Context, token: String, create: Boolean) {
    private val root: File
    private val files: File
    private val databases: File
    val database: File get() = File(databases, "pristinepage.db")
    val ownerRoot: File get() = File(files, "store")
    private val context: Context

    init {
        check(base.packageName == PACKAGE && UUID.fromString(token).toString() == token)
        val parent = base.filesDir.canonicalFile
        root = directory(parent, "ocr-death-$token", create)
        files = directory(root, "files", create)
        databases = directory(root, "databases", create)
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getDatabasePath(name: String): File {
                check(name == "pristinepage.db")
                return database
            }
        }
    }

    suspend fun open(allowFixtureIngest: Boolean = false): PrivateImportStore {
        if (!allowFixtureIngest) check(database.isFile && database.absoluteFile == database.canonicalFile)
        val source = SourceInput {
            check(allowFixtureIngest && it == IngestCrashSupport.REFERENCE)
            IngestCrashSupport.BYTES.inputStream()
        }
        val inspector = SourceInspector { file, hash ->
            check(allowFixtureIngest && hash == IngestCrashSupport.BYTE_HASH && file.length() == IngestCrashSupport.BYTES.size.toLong())
            check(file.readBytes().contentEquals(IngestCrashSupport.BYTES))
            SourceInspection("image/png", PixelSize(3, 2), PixelSize(3, 2), 1, null, "ocr-death-fixture-v1", "a".repeat(64))
        }
        return PrivateImportStore.open(context, source, inspector)
    }

    private fun directory(parent: File, name: String, create: Boolean): File = File(parent, name).also {
        check(it.absoluteFile == it.canonicalFile)
        if (create) check(it.mkdir()) else check(it.isDirectory)
    }

    companion object { const val PACKAGE = "app.pristinepage.debug.test" }
}
