package app.pristinepage.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.data.repository.FileOperationOwners
import app.pristinepage.data.repository.LeaseOwnerState
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Prepared actual Android separate-process lock case. Only the installed non-exported test service is interrupted; no personal phone, host process, application data clear or original deletion. This is not a Room/WAL/full-ingest crash test. */
@RunWith(AndroidJUnit4::class)
class ProcessOwnerProofIntegrationTest {
    @Test fun actualForeignAndroidProcessExitPermitsProofButLiveProcessIsNotALocalRequester() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val rootId = UUID.randomUUID().toString()
        val owners = FileOperationOwners(File(context.filesDir, "t03-owner-proof-$rootId"))
        val connected = CompletableDeferred<Messenger>()
        val ready = CompletableDeferred<Bundle>()
        val dead = CompletableDeferred<Unit>()
        val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
            when (message.what) {
                OwnerProofMessages.READY -> ready.complete(message.data)
                OwnerProofMessages.FAILED -> ready.completeExceptionally(AssertionError("Synthetic Android owner registration failed"))
            }
            true
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                service.linkToDeath({ dead.complete(Unit) }, 0)
                connected.complete(Messenger(service))
            }
            override fun onServiceDisconnected(name: ComponentName) { dead.complete(Unit) }
            override fun onBindingDied(name: ComponentName) { dead.complete(Unit) }
        }
        var remote: Messenger? = null
        val bound = context.bindService(Intent(context, OwnerProofFixtureService::class.java).putExtra("rootId", rootId), connection, Context.BIND_AUTO_CREATE)
        assertTrue("The operation-owned test service did not bind", bound)
        try {
            remote = withTimeout(15000) { connected.await() }
            remote.send(Message.obtain(null, OwnerProofMessages.START).apply { replyTo = reply })
            val registration = withTimeout(15000) { ready.await() }
            val owner = checkNotNull(registration.getString("owner")); UUID.fromString(owner)
            assertNotEquals(android.os.Process.myPid(), registration.getInt("pid"))
            assertEquals(LeaseOwnerState.UNKNOWN, owners.state(owner))
            assertEquals(LeaseOwnerState.ALIVE, owners.refresh(owner))
            assertEquals("A live foreign process cannot become a local scoped requester", LeaseOwnerState.UNKNOWN, owners.state(owner))
            remote.send(Message.obtain(null, OwnerProofMessages.HALT))
            withTimeout(15000) { dead.await() }
            // Binder death is not itself accepted as file-ownership proof. Wait only for the real nonblocking native probe; exceeding the bound fails, never converts a timeout into ENDED.
            withTimeout(15000) { while (owners.refresh(owner) != LeaseOwnerState.ENDED) delay(25) }
            assertEquals(LeaseOwnerState.ENDED, owners.state(owner))
        } finally {
            try { remote?.send(Message.obtain(null, OwnerProofMessages.HALT)) } catch (_: RemoteException) { /* Already-dead fixture, not a new kill target. */ }
            context.unbindService(connection)
        }
    }
}
