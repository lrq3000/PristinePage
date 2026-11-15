package app.pristinepage.data

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.*
import androidx.room.Room
import androidx.room.RoomDatabase
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.db.RecipeRevisionRow
import app.pristinepage.data.files.SealedSource
import app.pristinepage.data.files.SourceFiles
import app.pristinepage.data.files.SourceInspector
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*

/** Non-exported, token-owned synthetic Room/WAL fixture only. HALT kills this service's own PID without unwinding active file/Room/owner scopes; it never accepts a caller-selected path/database, arbitrary kill PID or real document input. */
class IngestCrashFixtureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var input: Triple<String, ImportSessionId, IngestCrashPoint>? = null
    private var started = false
    private enum class GateState { IDLE, ARMED, HALTING, EXPIRED }
    private val gateLock = Any()
    private var gateState = GateState.IDLE
    private var gateReply: Messenger? = null
    private var gatePoint: IngestCrashPoint? = null
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            IngestCrashMessages.START -> if (!started) {
                started = true; val reply = message.replyTo
                scope.launch {
                    try { execute(reply) }
                    catch (canceled: CancellationException) { throw canceled }
                    catch (_: Exception) { reportFailure(reply, "ingest_crash_fixture_failed") }
                }
            }
            IngestCrashMessages.HALT -> halt()
        }
        true
    })

    private suspend fun execute(reply: Messenger) {
        val (token, session, selected) = checkNotNull(input)
        val database = IngestCrashSupport.open(this, token)
        val native = FileOperationOwners(IngestCrashSupport.root(this, token)); val active = AtomicReference<String>()
        val owners = object : OperationOwners by native {
            override suspend fun <T> run(block: suspend CoroutineScope.(String) -> T): T = native.run { owner -> active.set(owner); block(owner) }
        }
        val journalMode = IngestCrashSupport.journalMode(database)
        check(journalMode == "wal")
        fun gate(point: IngestCrashPoint) {
            if (point != selected) return
            synchronized(gateLock) {
                check(gateState == GateState.IDLE)
                gateState = GateState.ARMED; gateReply = reply; gatePoint = point
                reply.send(Message.obtain(null, IngestCrashMessages.READY).apply { data = Bundle().apply {
                    putString("point", point.name); putString("owner", checkNotNull(active.get())); putInt("pid", android.os.Process.myPid())
                    putBoolean("inTransaction", database.inTransaction()); putString("journalMode", journalMode)
                } })
            }
            // READY is only a snapshot. Expiry and HALT compete under the same lock; once HALT_ACK is sent, this gate can never throw/return/unwind before self-termination.
            val reason = try { CountDownLatch(1).await(60, TimeUnit.SECONDS); "ingest_crash_gate_expired" }
            catch (_: InterruptedException) { "ingest_crash_gate_interrupted" }
            synchronized(gateLock) {
                if (gateState == GateState.ARMED) { gateState = GateState.EXPIRED; reportFailure(reply, reason) }
            }
            terminateWithoutUnwind()
        }
        val base = RoomMetadataAccess(database)
        val metadata = object : MetadataAccess by base {
            override val queries = object : CatalogQueries by base.queries {
                override fun insertRecipe(row: RecipeRevisionRow) { base.queries.insertRecipe(row); gate(IngestCrashPoint.IN_METADATA) }
            }
        }
        val originalPhases = RoomIngestPhases(database)
        val phases = object : IngestPhases by originalPhases {
            override fun copying(intent: IngestJournalIntent, lease: JobLease): Boolean { gate(IngestCrashPoint.OWNED_INTENT); return originalPhases.copying(intent, lease) }
            override fun sealed(intent: IngestJournalIntent, lease: JobLease, sealed: SealedSource): Boolean { gate(IngestCrashPoint.SEALED); return originalPhases.sealed(intent, lease, sealed) }
            override fun moved(intent: IngestJournalIntent, lease: JobLease): Boolean { gate(IngestCrashPoint.MOVED); return originalPhases.moved(intent, lease) }
        }
        val provider = SourceInput { reference ->
            check(reference == IngestCrashSupport.REFERENCE)
            object : ByteArrayInputStream(IngestCrashSupport.BYTES) {
                private var emitted = false
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    if (length == 0) return 0
                    if (selected == IngestCrashPoint.PARTIAL && emitted) gate(IngestCrashPoint.PARTIAL)
                    val count = super.read(bytes, offset, if (selected == IngestCrashPoint.PARTIAL) 1 else length)
                    if (count > 0) emitted = true
                    return count
                }
            }
        }
        val store = IngestCrashSupport.store(database, SourceFiles(IngestCrashSupport.root(this, token)), owners, provider, phases, metadata) { gate(IngestCrashPoint.COMMITTED) }
        store.ingestBoundProvider(IngestCrashSupport.REFERENCE, session)
        error("Requested actual ingest crash boundary was not reached")
    }

    override fun onBind(intent: Intent): IBinder {
        val token = checkNotNull(intent.getStringExtra("token")); UUID.fromString(token)
        val current = Triple(token, ImportSessionId(checkNotNull(intent.getStringExtra("session"))), IngestCrashPoint.valueOf(checkNotNull(intent.getStringExtra("point"))))
        check(input == null || input == current); input = current
        return messenger.binder
    }
    private fun halt(): Nothing {
        try {
            synchronized(gateLock) {
                val reply = gateReply
                if (gateState == GateState.ARMED && reply != null) {
                    gateState = GateState.HALTING
                    reply.send(Message.obtain(null, IngestCrashMessages.HALT_ACK).apply { data = Bundle().apply {
                        putString("point", checkNotNull(gatePoint).name); putInt("pid", android.os.Process.myPid())
                    } })
                } else if (reply != null) reportFailure(reply, "ingest_crash_gate_unarmed")
            }
        } finally { terminateWithoutUnwind() }
    }
    private fun reportFailure(reply: Messenger, reason: String) {
        try { reply.send(Message.obtain(null, IngestCrashMessages.FAILED).apply { data = Bundle().apply { putString("reason", reason) } }) }
        catch (_: RemoteException) { /* A disconnected parent cannot accept this run. Do not unwind an armed gate because reporting failed. */ }
    }
    private fun terminateWithoutUnwind(): Nothing {
        android.os.Process.killProcess(android.os.Process.myPid())
        // killProcess requests termination. If delivery is delayed, keep every file/Room/owner scope at its actual boundary instead of accidentally completing through a finally block.
        while (true) try { CountDownLatch(1).await() } catch (_: InterruptedException) { /* Only actual VM termination exits this gate. */ }
    }
    override fun onUnbind(intent: Intent): Boolean { stopSelf(); return false }
    override fun onDestroy() { scope.cancel(); super.onDestroy(); terminateWithoutUnwind() }
}

internal enum class IngestCrashPoint { OWNED_INTENT, PARTIAL, SEALED, MOVED, IN_METADATA, COMMITTED }
internal object IngestCrashMessages { const val START = 1100; const val READY = 1101; const val HALT = 1102; const val FAILED = 1103; const val HALT_ACK = 1104 }

/** Shared test-only composition and fixed paths, not production hooks or provider/decoder implementations. */
internal object IngestCrashSupport {
    val BYTES = byteArrayOf(1, 2, 3, 4)
    const val BYTE_HASH = "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a"
    const val REFERENCE = "content://fixture.documents/synthetic-process-death"
    fun root(context: Context, token: String): File { UUID.fromString(token); return File(context.filesDir, "t03-ingest-crash-$token") }
    fun open(context: Context, token: String): PristineDatabase {
        UUID.fromString(token)
        return Room.databaseBuilder(context, PristineDatabase::class.java, "t03-ingest-crash-$token.db").setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
    }
    fun journalMode(database: PristineDatabase): String = database.openHelper.readableDatabase.query("PRAGMA journal_mode").use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) }
    fun store(database: PristineDatabase, files: SourceFiles, owners: OperationOwners, provider: SourceInput, phases: IngestPhases = RoomIngestPhases(database), metadata: MetadataAccess = RoomMetadataAccess(database), afterCommit: (() -> Unit)? = null): RoomSourceStore {
        val completion = RoomIngestCompletion(metadata, database.importSessions(), database.ingestPreparation(), database.ingestCompletion(), database.jobLeases(), files)
        val committing = object : IngestCompletion {
            override suspend fun commit(intent: IngestJournalIntent, lease: JobLease): IngestResult = completion.commit(intent, lease).also { if (it is IngestResult.Committed) afterCommit?.invoke() }
        }
        val inspector = SourceInspector { file, hash ->
            check(!database.inTransaction()); check(hash == BYTE_HASH && file.readBytes().contentEquals(BYTES))
            SourceInspection("image/png", PixelSize(3, 2), PixelSize(3, 2), 1, null, "synthetic-process-death-v1", "a".repeat(64))
        }
        return RoomSourceStore(files, provider, inspector, RoomIngestPreparation(database), RoomIngestLookup(database), completion, committing, phases, RoomJobLeases(database, owners), owners, restarts = RoomIngestRestarts(database, files, owners))
    }
}
