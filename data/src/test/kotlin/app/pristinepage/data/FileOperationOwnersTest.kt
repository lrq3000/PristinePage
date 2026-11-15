package app.pristinepage.data

import app.pristinepage.data.repository.*
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Actual private marker/OS-lock ownership and a bounded owned child JVM, not mocked process liveness. Host proof is not Android process-death acceptance. No fixture files are deleted. */
class FileOperationOwnersTest {
    private fun root(): File {
        val parent = File(System.getProperty("user.dir"), "build/test-owner-proof").apply { mkdirs() }
        return File(Files.createTempDirectory(parent.toPath(), "ownership-").toFile(), "store")
    }

    @Test fun sharedInstancesKeepLocalOwnerAliveUntilStructuredChildrenFinish() = runTest {
        val root = root(); val owners = fileOwners(root); val mirror = fileOwners(root)
        var identity: String? = null
        val ready = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val task = launch {
            owners.run { owner ->
                identity = owner
                assertEquals(LeaseOwnerState.ALIVE, mirror.state(owner))
                assertEquals(LeaseOwnerState.ALIVE, (mirror as OperationOwnerProof).refresh(owner))
                launch { ready.complete(Unit); finish.await(); assertEquals(LeaseOwnerState.ALIVE, owners.state(owner)) }
            }
        }
        ready.await(); assertEquals(LeaseOwnerState.ALIVE, mirror.state(identity!!))
        finish.complete(Unit); task.join()
        assertEquals(LeaseOwnerState.ENDED, owners.state(identity)); assertEquals(LeaseOwnerState.ENDED, mirror.state(identity))
    }

    @Test fun missingOrOtherStoreOwnershipNeverBecomesEndedOrCreatesProofFiles() = runTest {
        val absent = root(); val owners = fileOwners(absent); val unknown = UUID.randomUUID().toString()
        assertEquals(LeaseOwnerState.UNKNOWN, owners.state(unknown))
        assertEquals(LeaseOwnerState.UNKNOWN, (owners as OperationOwnerProof).refresh(unknown))
        assertFalse("A missing proof lookup created an ownership store", absent.exists())
        val actualRoot = root(); val actual = fileOwners(actualRoot)
        actual.run { owner ->
            assertEquals(LeaseOwnerState.UNKNOWN, owners.state(owner))
            assertEquals(LeaseOwnerState.UNKNOWN, owners.refresh(owner))
        }
        assertFalse(absent.exists())
    }

    @Test fun foreignLiveProcessBlocksUntilActualAbruptJvmExitThenCachesPositiveProof() = runTest {
        val root = root(); val owners = fileOwners(root) as OperationOwnerProof
        OwnerChild(root).use { child ->
            val id = child.owner()
            assertEquals(LeaseOwnerState.UNKNOWN, (owners as OperationOwners).state(id))
            assertEquals(LeaseOwnerState.ALIVE, owners.refresh(id))
            assertEquals("Foreign process evidence must not authorize a local lease requester", LeaseOwnerState.UNKNOWN, owners.state(id))
            assertTrue(child.process.isAlive)
            child.stopAbruptly()
            assertEquals(LeaseOwnerState.ENDED, owners.refresh(id))
            assertEquals(LeaseOwnerState.ENDED, owners.state(id))
            assertEquals(LeaseOwnerState.ENDED, owners.refresh(id))
        }
    }

    @Test fun malformedRegistrationCannotUseAnUnlockedProcessAsTerminationProof() = runTest {
        val root = root(); val owners = fileOwners(root) as OperationOwnerProof
        OwnerChild(root).use { child ->
            val id = child.owner(); child.stopAbruptly()
            val record = File(root, "ownership/operations/$id.owner")
            assertTrue(record.isFile)
            record.writeText("unsupported private synthetic owner metadata") // This unique test-owned fault fixture only.
            assertEquals(LeaseOwnerState.UNKNOWN, owners.refresh(id))
            assertEquals(LeaseOwnerState.UNKNOWN, (owners as OperationOwners).state(id))
            assertTrue(record.isFile)
        }
    }

    @Test fun malformedProcessMarkerCannotCertifyARecordedOwnerAfterExit() = runTest {
        val root = root(); val owners = fileOwners(root) as OperationOwnerProof
        OwnerChild(root).use { child ->
            val id = child.owner(); child.stopAbruptly()
            val process = File(root, "ownership/processes").listFiles()!!.single()
            process.writeText("unsupported synthetic process marker")
            assertEquals(LeaseOwnerState.UNKNOWN, owners.refresh(id))
            assertEquals(LeaseOwnerState.UNKNOWN, (owners as OperationOwners).state(id))
            assertTrue(process.isFile)
        }
    }

    @Test fun unknownLookupThroughMetadataDoesNotWaitForTheNativeRegistrationMonitor() {
        assertMetadataLookupIndependentOfRegistration(fileOwners(root()), UUID.randomUUID().toString())
    }

    @Test fun cachedForeignEndedLookupThroughMetadataDoesNotWaitForRegistration() = runTest {
        val root = root(); val owners = fileOwners(root)
        OwnerChild(root).use { child ->
            val owner = child.owner(); child.stopAbruptly()
            assertEquals(LeaseOwnerState.ENDED, (owners as OperationOwnerProof).refresh(owner))
            assertMetadataLookupIndependentOfRegistration(owners, owner)
        }
    }

    /** Hold the exact native-registration monitor to model a stalled fsync without depending on an unreliable real-disk stall. The liveness method and consuming metadata transaction remain production code; no production test hook or global filesystem setting is introduced. */
    private fun assertMetadataLookupIndependentOfRegistration(owners: OperationOwners, owner: String) {
        val db = IngestIntentTest.Fixture(); val intent = db.commands.prepare(db.child, db.uri)
        val admitted = db.jobs.values.single().copy(state = "ADMITTED"); db.jobs[admitted.jobId] = admitted
        val pool = owners.javaClass.getDeclaredField("pool").apply { isAccessible = true }.get(owners)
        val held = CountDownLatch(1); val release = CountDownLatch(1); val enteredLookup = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(2)
        val registration = threads.submit {
            synchronized(pool) { held.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
        }
        try {
            assertTrue(held.await(5, TimeUnit.SECONDS))
            val liveness = LeaseOwnerLiveness { requested ->
                assertTrue(db.catalog.inTransaction); enteredLookup.countDown(); owners.state(requested)
            }
            val leases = RoomJobLeases(db.metadata, db, liveness, { db.now }, { db.now }, Dispatchers.Unconfined)
            val lookup = threads.submit<AcquireLeaseResult> { runBlocking { leases.acquireAdmitted(intent.jobId, owner, 100) } }
            assertTrue(enteredLookup.await(5, TimeUnit.SECONDS))
            try { assertTrue(lookup.get(1, TimeUnit.SECONDS) is AcquireLeaseResult.Rejected) }
            catch (blocked: java.util.concurrent.TimeoutException) { throw AssertionError("Room liveness lookup waited on the native registration IO monitor", blocked) }
            assertSame(admitted, db.jobs.values.single())
        } finally {
            release.countDown(); registration.get(5, TimeUnit.SECONDS)
            threads.shutdown(); assertTrue(threads.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    /** Only this test's newly spawned JVM is signaled; EXIT closes its own scope, HALT intentionally exits without finally. No PID enumeration, broad kill, timeout-as-proof or unrelated process is involved. */
    internal class OwnerChild(root: File) : AutoCloseable {
        val process: Process
        private val identity: CompletableFuture<String?>
        init {
            val paths = linkedSetOf<String>()
            System.getProperty("java.class.path").orEmpty().split(File.pathSeparator).filter { it.isNotEmpty() }.forEach(paths::add)
            var loader: ClassLoader? = FileOperationOwnersTest::class.java.classLoader
            while (loader != null) {
                if (loader is URLClassLoader) loader.urLs.filter { it.protocol == "file" }.forEach { paths += File(it.toURI()).absolutePath }
                loader = loader.parent
            }
            val java = File(System.getProperty("java.home"), "bin/java.exe")
            val executable = if (java.isFile) java else File(System.getProperty("java.home"), "bin/java")
            process = ProcessBuilder(executable.absolutePath, "-Xmx128m", "-cp", paths.joinToString(File.pathSeparator), OwnerProofChild::class.java.name, root.absolutePath).redirectError(File(root.parentFile, "child-stderr.log")).start()
            identity = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readLine() }
        }
        fun owner(): String = checkNotNull(identity.get(15, TimeUnit.SECONDS)) { "Owned proof fixture exited before registration; inspect retained child-stderr.log" }.also { UUID.fromString(it) }
        fun stopAbruptly() {
            check(process.isAlive); process.outputStream.write("HALT\n".toByteArray()); process.outputStream.flush()
            check(process.waitFor(15, TimeUnit.SECONDS)) { "Owned proof fixture did not exit; no termination evidence inferred" }
            check(process.exitValue() == 0)
        }
        override fun close() {
            if (process.isAlive) {
                process.outputStream.write("EXIT\n".toByteArray()); process.outputStream.flush(); process.outputStream.close()
                check(process.waitFor(15, TimeUnit.SECONDS)) { "Owned fixture cleanup is unconfirmed; do not treat timeout as exit" }
            }
            check(!process.isAlive)
        }
    }
}

internal fun fileOwners(root: File): OperationOwners {
    val type = try { Class.forName("app.pristinepage.data.repository.FileOperationOwners") }
    catch (failure: ClassNotFoundException) { throw AssertionError("Positive native process ownership proof is absent", failure) }
    return try { type.constructors.single { it.parameterCount == 3 }.newInstance(root, 1024, Dispatchers.Unconfined) as OperationOwners }
    catch (failure: InvocationTargetException) { throw failure.targetException }
}

/** Dedicated host fixture: a new JVM genuinely owns the registration/lock, and HALT demonstrates native release on actual VM termination rather than a forged registry flag. */
object OwnerProofChild {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        fileOwners(File(args.single())).run { owner ->
            println(owner); System.out.flush()
            if (System.`in`.bufferedReader().readLine() == "HALT") Runtime.getRuntime().halt(0)
        }
    }
}
