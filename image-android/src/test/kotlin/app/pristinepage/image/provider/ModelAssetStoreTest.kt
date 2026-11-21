package app.pristinepage.image.provider

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelAssetStoreTest {
    @get:Rule val fixture = TemporaryFolder()

    private val hello = "hello".encodeToByteArray()
    private val spec = ModelAssetSpec(
        "demo-look", 1, 5,
        "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
        peakInferenceBytes = null,
    )

    @Test
    fun verifiedInstallIsAvailableAfterReopenWithoutFetchingAgain() {
        val root = fixture.root.toPath().resolve("models")
        val store = ModelAssetStore(root)
        assertEquals(ModelAssetStatus.ABSENT, store.status(spec))
        assertFalse(Files.exists(root))
        store.install(spec) { ByteArrayInputStream(hello) }
        val reopened = ModelAssetStore(root)
        reopened.install(spec) { error("Already installed bytes must not be downloaded again") }
        reopened.acquire(spec).use { assertArrayEquals(hello, Files.readAllBytes(it.path)) }
    }

    @Test
    fun wrongLengthOrHashNeverPublishesAndDoesNotLeaveStagingBytes() {
        val root = fixture.root.toPath().resolve("models")
        val store = ModelAssetStore(root)
        for (invalid in listOf("hell", "hello!", "HELLO")) {
            assertThrows(IOException::class.java) { store.install(spec) { invalid.byteInputStream() } }
            assertEquals(ModelAssetStatus.ABSENT, store.status(spec))
            assertEquals(0L, Files.list(root).use { it.count() })
        }
    }

    @Test
    fun interruptedTransferClosesInputAndNeverBecomesInstalled() {
        val root = fixture.root.toPath().resolve("models")
        var closed = false
        val store = ModelAssetStore(root)
        assertThrows(IOException::class.java) {
            store.install(spec) {
                object : InputStream() {
                    private var first = true
                    override fun read(): Int {
                        if (!first) throw IOException("Interrupted fixture")
                        first = false
                        return 'h'.code
                    }

                    override fun close() { closed = true }
                }
            }
        }
        assertEquals(true, closed)
        assertEquals(ModelAssetStatus.ABSENT, ModelAssetStore(root).status(spec))
        assertEquals(0L, Files.list(root).use { it.count() })
    }

    @Test
    fun aPausedTransferDoesNotBlockAnotherModelsLeaseRelease() {
        val store = ModelAssetStore(fixture.root.toPath().resolve("models"))
        val next = spec.copy(version = 2)
        store.install(spec) { ByteArrayInputStream(hello) }
        val lease = store.acquire(spec)
        val fromSource = CountDownLatch(1)
        val releaseSource = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(2) { task -> Thread(task).apply { isDaemon = true } }
        try {
            val installing = threads.submit {
                store.install(next) {
                    fromSource.countDown()
                    check(releaseSource.await(8, SECONDS))
                    ByteArrayInputStream(hello)
                }
            }
            assertTrue(fromSource.await(5, SECONDS))
            threads.submit {
                lease.close()
                store.remove(spec)
            }.get(2, SECONDS)
            releaseSource.countDown()
            installing.get(5, SECONDS)
            assertEquals(ModelAssetStatus.ABSENT, store.status(spec))
            assertEquals(ModelAssetStatus.VERIFIED, store.status(next))
        } finally {
            releaseSource.countDown()
            threads.shutdownNow()
            assertTrue(threads.awaitTermination(10, SECONDS))
            lease.close()
        }
    }

    @Test
    fun cancellationAfterACompleteTransferStillPreventsPublication() {
        val root = fixture.root.toPath().resolve("models")
        val store = ModelAssetStore(root)
        val cancelled = AtomicBoolean(false)
        val checkpoint = { if (cancelled.get()) throw CancellationException() }
        assertThrows(CancellationException::class.java) {
            store.install(spec, checkpoint) {
                object : ByteArrayInputStream(hello) {
                    override fun close() {
                        cancelled.set(true)
                        super.close()
                    }
                }
            }
        }
        assertEquals(ModelAssetStatus.ABSENT, store.status(spec))
        assertEquals(0L, Files.list(root).use { it.count() })
    }

    @Test
    fun recoveredStagingFileNeverLooksInstalledAndOnlyOwnedStagesAreRemoved() {
        val root = fixture.newFolder("models").toPath()
        val abandoned = Files.write(root.resolve(".stage-a001c820-19b1-4f40-a8a9-24cbd660bb17"), hello)
        val unknown = Files.write(root.resolve(".stage-other"), hello)
        val store = ModelAssetStore(root)
        store.removeInterruptedInstalls()
        assertFalse(Files.exists(abandoned))
        assertArrayEquals(hello, Files.readAllBytes(unknown))
        assertEquals(ModelAssetStatus.ABSENT, store.status(spec))
    }

    @Test
    fun deletingModelWaitsForAllLeasesAndLeavesOtherVersionsAndRenditionsIntact() {
        val root = fixture.root.toPath()
        val saved = Files.write(root.resolve("accepted-render.png"), byteArrayOf(1, 4, 9))
        val store = ModelAssetStore(root.resolve("models"))
        val next = spec.copy(version = 2)
        store.install(spec) { ByteArrayInputStream(hello) }
        store.install(next) { ByteArrayInputStream(hello) }
        val first = store.acquire(spec)
        val second = store.acquire(spec)
        first.close()
        first.close()
        assertThrows(IOException::class.java) { store.remove(spec) }
        assertEquals(ModelAssetStatus.VERIFIED, store.status(spec))
        second.close()
        store.remove(spec)
        assertEquals(ModelAssetStatus.ABSENT, store.status(spec))
        assertEquals(ModelAssetStatus.VERIFIED, store.status(next))
        assertArrayEquals(byteArrayOf(1, 4, 9), Files.readAllBytes(saved))
    }

    @Test
    fun sameLengthCorruptionIsNotLeasedOrSilentlyOverwritten() {
        val store = ModelAssetStore(fixture.root.toPath().resolve("models"))
        store.install(spec) { ByteArrayInputStream(hello) }
        val installed: Path = store.acquire(spec).use { it.path }
        Files.write(installed, "HELLO".encodeToByteArray())
        assertEquals(ModelAssetStatus.INVALID, store.status(spec))
        assertThrows(IOException::class.java) { store.acquire(spec) }
        assertThrows(IOException::class.java) { store.install(spec) { error("Do not overwrite corrupt assets") } }
        assertArrayEquals("HELLO".encodeToByteArray(), Files.readAllBytes(installed))
    }

    @Test
    fun untrustedAssetMetadataCannotChoosePathsOrAnUnboundedTransfer() {
        assertThrows(IllegalArgumentException::class.java) { spec.copy(id = "../outside") }
        assertThrows(IllegalArgumentException::class.java) { spec.copy(byteCount = Long.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { spec.copy(sha256 = "not-a-hash") }
        assertThrows(IllegalArgumentException::class.java) { spec.copy(peakInferenceBytes = 0) }
    }
}
