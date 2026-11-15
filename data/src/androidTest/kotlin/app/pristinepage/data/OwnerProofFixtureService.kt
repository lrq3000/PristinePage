package app.pristinepage.data

import android.app.Service
import android.content.Intent
import android.os.*
import app.pristinepage.data.repository.FileOperationOwners
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*

/** Non-exported synthetic test process only. It owns no real source/user database. HALT kills its own named process without unwinding the registration scope; unbinding also stops this test service and its process. */
class OwnerProofFixtureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var rootId: String? = null
    private var started = false
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            OwnerProofMessages.START -> if (!started) {
                started = true
                val reply = message.replyTo
                scope.launch {
                    try {
                        val root = File(filesDir, "t03-owner-proof-${checkNotNull(rootId)}")
                        FileOperationOwners(root).run { owner ->
                            reply.send(Message.obtain(null, OwnerProofMessages.READY).apply { data = Bundle().apply { putString("owner", owner); putInt("pid", android.os.Process.myPid()) } })
                            awaitCancellation()
                        }
                    } catch (canceled: CancellationException) { throw canceled }
                    catch (_: Exception) { reply.send(Message.obtain(null, OwnerProofMessages.FAILED)) }
                }
            }
            OwnerProofMessages.HALT -> android.os.Process.killProcess(android.os.Process.myPid())
        }
        true
    })

    override fun onBind(intent: Intent): IBinder {
        val id = checkNotNull(intent.getStringExtra("rootId")); UUID.fromString(id)
        check(rootId == null || rootId == id); rootId = id
        return messenger.binder
    }

    override fun onUnbind(intent: Intent): Boolean { stopSelf(); return false }
    override fun onDestroy() {
        scope.cancel(); super.onDestroy()
        // Bound-service disconnection alone is not VM termination. This fixture never leaves an idle process behind after its sole test unbinds, including an automatic post-death service reconnect.
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}

internal object OwnerProofMessages {
    const val START = 1000
    const val READY = 1001
    const val HALT = 1002
    const val FAILED = 1003
}
