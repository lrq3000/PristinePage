package app.pristinepage.data

import app.pristinepage.data.files.DerivedFileTarget
import app.pristinepage.data.files.DerivedFiles
import app.pristinepage.data.files.DerivedKind
import app.pristinepage.data.files.DirectorySync
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** File-only crash-state fixtures; the journal is represented by the expected seal hash input, not by a Room test double. */
class DerivedFilesRecoveryTest {
    @Test fun recoversStageOnlyExportWhenJournalHasNoSealHash() {
        val attempt = SealedAttempt(export = true)
        val reopened = attempt.openStore()
        assertFalse(attempt.final.exists())

        val recovered = checkNotNull(attempt.recover(reopened, expectedSealSha256 = null))
        assertEquals(attempt.sealSha256, recovered.call("getSealSha256"))
        assertArrayEquals(attempt.metadata, recovered.call("getMetadata") as ByteArray)
        assertArrayEquals(attempt.payload, attempt.stage.readBytes())
        assertFalse(attempt.final.exists())

        assertEquals(attempt.final, reopened.call("move", recovered))
        assertArrayEquals(attempt.payload, attempt.final.readBytes())
        assertFalse(attempt.stage.exists())
        attempt.assertSealUnchanged()
    }

    @Test fun matchingStageAndFinalAreBothPreservedWhenReplayed() {
        val attempt = SealedAttempt()
        attempt.createMatchingFinal()
        // Different timestamps witness ordinary byte-identical rewrites; file keys also detect replacement where the host exposes them.
        val stageBefore = mark(attempt.stage, "2000-01-01T00:00:00Z")
        val finalBefore = mark(attempt.final, "2000-01-02T00:00:00Z")
        assertNotEquals(stageBefore.lastModifiedTime(), finalBefore.lastModifiedTime())
        val reopened = attempt.openStore()

        val recovered = checkNotNull(attempt.recover(reopened))
        assertUnchanged(attempt.stage, attempt.payload, stageBefore)
        assertUnchanged(attempt.final, attempt.payload, finalBefore)

        assertEquals(attempt.final, reopened.call("move", recovered))
        assertUnchanged(attempt.stage, attempt.payload, stageBefore)
        assertUnchanged(attempt.final, attempt.payload, finalBefore)
        attempt.assertSealUnchanged()
    }

    @Test fun matchingFinalDoesNotHideSameLengthStageDrift() {
        assertDriftRejected(driftStage = true)
    }

    @Test fun matchingStageDoesNotHideSameLengthFinalDrift() {
        assertDriftRejected(driftStage = false)
    }

    @Test fun sealWithoutEitherRecordedPayloadIsNotRecoverable() {
        val attempt = SealedAttempt()
        assertNotNull(attempt.recover(attempt.openStore()))
        // Retain the original bytes at a test-owned path outside the two recorded locations, without deleting a fixture.
        val retained = File(attempt.root, "detached-fixture.bin")
        Files.move(attempt.stage.toPath(), retained.toPath())
        assertFalse(attempt.stage.exists())
        assertFalse(attempt.final.exists())
        val reopened = attempt.openStore()

        assertThrows(Exception::class.java) { attempt.recover(reopened) }
        assertFalse(attempt.stage.exists())
        assertFalse(attempt.final.exists())
        assertArrayEquals(attempt.payload, retained.readBytes())
        attempt.assertSealUnchanged()
    }

    @Test fun writerCannotSealAValidPrefixAfterSwallowingAnOverLimitWrite() {
        val root = newRoot()
        val transactionId = UUID.randomUUID().toString()
        val store = openStore(root)
        val writer: (OutputStream) -> Unit = { output ->
            output.write(1)
            assertThrows(IOException::class.java) { output.write(byteArrayOf(2, 3, 4, 5)) }
        }
        val checkpoint: () -> Unit = {}

        assertThrows(IOException::class.java) { store.call("stage", transactionId, target(), 4L, writer, checkpoint) }
        assertArrayEquals(byteArrayOf(1), File(root, "staging/$transactionId/derived.part").readBytes())
        assertEquals(null, store.call("recover", transactionId, null))
        assertFalse(File(root, "renders").exists())
    }

    @Test fun moveRejectsStageDriftEvenAfterSuccessfulRecovery() {
        val attempt = SealedAttempt()
        attempt.createMatchingFinal()
        val store = attempt.openStore()
        val recovered = checkNotNull(attempt.recover(store))
        val drift = byteArrayOf(11, 29, 48, 71)
        Files.write(attempt.stage.toPath(), drift, WRITE)

        assertThrows(Exception::class.java) { store.call("move", recovered) }
        assertArrayEquals(drift, attempt.stage.readBytes())
        assertArrayEquals(attempt.payload, attempt.final.readBytes())
        attempt.assertSealUnchanged()
    }

    @Test fun recoveryRefusesNonCanonicalUnknownAndOverExtentSealsEvenWithoutAJournalHash() {
        for (tamper in listOf<(String) -> String>(
            { "$it " },
            { it.replace("\"version\":1", "\"version\":2") },
            { it.replace("\"version\":1", "\"version\":1,\"version\":1") },
            { "{}" },
            { " ".repeat(1_048_577) },
        )) {
            val attempt = SealedAttempt()
            val original = attempt.seal.readText()
            val changed = tamper(original)
            assertNotEquals(original, changed)
            attempt.seal.writeText(changed)

            assertThrows(Exception::class.java) { attempt.recover(attempt.openStore(), expectedSealSha256 = null) }
            assertEquals(changed, attempt.seal.readText())
            assertArrayEquals(attempt.payload, attempt.stage.readBytes())
            assertFalse(attempt.final.exists())
        }
    }

    @Test fun failedPostMoveDirectorySyncLeavesFinalOnlyReplayable() {
        val attempt = SealedAttempt()
        val refusingStore = openStore(attempt.root, DirectorySync { directory ->
            if (directory == attempt.final.parentFile) throw IOException("synthetic post-move directory sync failure")
            true
        })
        val recovered = checkNotNull(attempt.recover(refusingStore))

        assertThrows(IOException::class.java) { refusingStore.call("move", recovered) }
        assertFalse(attempt.stage.exists())
        assertArrayEquals(attempt.payload, attempt.final.readBytes())
        val before = mark(attempt.final, "2000-01-03T00:00:00Z")
        val restarted = attempt.openStore()
        val replay = checkNotNull(attempt.recover(restarted))
        assertEquals(attempt.final, restarted.call("move", replay))
        assertUnchanged(attempt.final, attempt.payload, before)
        attempt.assertSealUnchanged()
    }

    @Test fun readableSealCannotAuthorizeRecoveryOrMoveUntilItsBarriersSucceed() {
        val root = newRoot()
        val tx = UUID.randomUUID().toString()
        val target = DerivedFileTarget(DerivedKind.RENDER, UUID.randomUUID().toString())
        val stage = File(root, "staging/$tx/derived.part")
        val file = File(checkNotNull(stage.parentFile), "derived-seal.json")
        val payload = byteArrayOf(11, 29, 47, 71)
        val metadata = byteArrayOf(13, 5)
        val forceError = IOException("injected seal file force failure")
        val syncError = IOException("injected seal directory sync failure")
        val barriers = mutableListOf<String>()
        var failForce = true
        var failDirectory = false
        fun store() = DerivedFiles(root, DirectorySync { directory ->
            if (directory == file.parentFile) {
                barriers += "directory"
                if (failDirectory) throw syncError
            }
            true
        }) { channel ->
            barriers += "file"
            assertEquals(file.length(), channel.size())
            assertTrue(channel.size() > 0)
            if (failForce) throw forceError
            channel.force(true)
        }
        val first = store()
        val staged = first.stage(tx, target, 4L, { it.write(payload) })
        val final = first.finalFile(target)
        barriers.clear()
        assertSame(forceError, assertThrows(IOException::class.java) { first.seal(staged, metadata) })
        assertEquals(listOf("file"), barriers)
        val completeSeal = file.readBytes()
        val sealBefore = mark(file, "2000-01-04T00:00:00Z")
        assertArrayEquals(payload, stage.readBytes())
        assertFalse(final.exists())

        val reopened = store()
        barriers.clear()
        assertSame(forceError, assertThrows("Readable is not durable", IOException::class.java) { reopened.recover(tx, null) })
        assertEquals(listOf("file"), barriers)
        barriers.clear()
        val observed = checkNotNull(reopened.inspectSeal(tx, null))
        assertArrayEquals(metadata, observed.metadata)
        assertEquals(emptyList<String>(), barriers)

        failForce = false
        failDirectory = true
        assertSame(syncError, assertThrows(IOException::class.java) { reopened.recover(tx, null) })
        assertEquals(listOf("file", "directory"), barriers)
        assertUnchanged(file, completeSeal, sealBefore)
        assertFalse(final.exists())

        failDirectory = false
        barriers.clear()
        val replay = checkNotNull(reopened.recover(tx, null))
        assertEquals(listOf("file", "directory"), barriers)
        assertEquals(observed.sealSha256, replay.sealSha256)
        failForce = true
        barriers.clear()
        assertSame(forceError, assertThrows(IOException::class.java) { reopened.move(replay) })
        assertEquals(listOf("file"), barriers)
        assertFalse(final.exists())
        assertArrayEquals(payload, stage.readBytes())

        failForce = false
        assertEquals(final, reopened.move(replay))
        assertArrayEquals(payload, final.readBytes())
        assertUnchanged(file, completeSeal, sealBefore)
    }

    @Test fun reentrantCheckpointWriteCannotExceedLimitOrReturnAPrefixReceipt() {
        val root = newRoot()
        val tx = UUID.randomUUID().toString()
        val store = DerivedFiles(root, DirectorySync { false })
        val target = DerivedFileTarget(DerivedKind.RENDER, UUID.randomUUID().toString())
        var borrowed: OutputStream? = null
        var entered = false
        var nestedFailure: IOException? = null
        val writer: (OutputStream) -> Unit = { output ->
            borrowed = output
            try { output.write(byteArrayOf(2, 3)) } catch (_: IOException) { /* A codec can swallow the outer error too. */ }
        }
        val result = runCatching {
            store.stage(tx, target, 2L, writer) {
                val output = borrowed
                if (output != null && !entered) {
                    entered = true
                    try { output.write(1) } catch (error: IOException) { nestedFailure = error }
                }
            }
        }

        assertTrue("The in-write checkpoint must attempt the nested write", entered)
        val stage = File(root, "staging/$tx/derived.part")
        assertEquals("Neither active write may produce bytes after the reentry", 0L, stage.length())
        assertNotNull("The nested write itself must refuse", nestedFailure)
        assertSame("Swallowing the errors must not yield a receipt", nestedFailure, result.exceptionOrNull())
        assertEquals(null, store.recover(tx, null))
        assertFalse(store.finalFile(target).exists())
    }

    private fun assertDriftRejected(driftStage: Boolean) {
        val attempt = SealedAttempt()
        attempt.createMatchingFinal()
        assertNotNull(attempt.recover(attempt.openStore()))
        val changed = if (driftStage) attempt.stage else attempt.final
        val matching = if (driftStage) attempt.final else attempt.stage
        // Keep both lengths and one copy valid so neither trusting the other copy nor a length-only check suffices.
        val drift = byteArrayOf(11, 29, 48, 71)
        Files.write(changed.toPath(), drift, WRITE)
        val reopened = attempt.openStore()

        assertThrows(Exception::class.java) { attempt.recover(reopened) }
        assertArrayEquals(drift, changed.readBytes())
        assertArrayEquals(attempt.payload, matching.readBytes())
        attempt.assertSealUnchanged()
    }

    private class SealedAttempt(export: Boolean = false) {
        val payload = byteArrayOf(11, 29, 47, 71)
        val metadata = byteArrayOf(13, 5)
        val root = newRoot()
        private val transactionId = UUID.randomUUID().toString()
        private val objectId = UUID.randomUUID().toString()
        private val snapshotId = if (export) UUID.randomUUID().toString() else null
        val stage = File(root, "staging/$transactionId/derived.part")
        val final = File(root, if (export) "exports/$snapshotId/$objectId.bin" else "renders/$objectId/page.bin")
        val seal = File(root, "staging/$transactionId/derived-seal.json")
        private val sealBytes: ByteArray
        val sealSha256: String

        init {
            val store = openStore()
            val kindName = if (export) "EXPORT" else "RENDER"
            val target = target(kindName, objectId, snapshotId)
            val writer: (OutputStream) -> Unit = { it.write(payload) }
            val checkpoint: () -> Unit = {}
            val staged = checkNotNull(store.call("stage", transactionId, target, payload.size.toLong(), writer, checkpoint))
            store.call("seal", staged, metadata.copyOf())
            sealBytes = seal.readBytes()
            sealSha256 = MessageDigest.getInstance("SHA-256").digest(sealBytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        fun openStore(): Any = openStore(root)

        fun recover(store: Any, expectedSealSha256: String? = sealSha256): Any? =
            store.call("recover", transactionId, expectedSealSha256)

        fun createMatchingFinal() {
            Files.createDirectories(checkNotNull(final.parentFile).toPath())
            Files.write(final.toPath(), payload, CREATE_NEW, WRITE)
        }

        fun assertSealUnchanged() = assertArrayEquals(sealBytes, seal.readBytes())
    }

    private companion object {
        fun openStore(root: File, sync: DirectorySync = DirectorySync { false }): Any =
            type("DerivedFiles").getConstructor(File::class.java, DirectorySync::class.java).newInstance(root, sync)

        fun target(kindName: String = "RENDER", id: String = UUID.randomUUID().toString(), snapshotId: String? = null): Any {
            val kindType = type("DerivedKind")
            val kind = checkNotNull(kindType.enumConstants).single { it.toString() == kindName }
            return type("DerivedFileTarget").getConstructor(kindType, String::class.java, String::class.java)
                .newInstance(kind, id, snapshotId)
        }

        fun newRoot(): File {
            val parent = File(System.getProperty("user.dir"), "build/test-derived-recovery").toPath()
            return Files.createTempDirectory(Files.createDirectories(parent), "case-").toFile().canonicalFile
        }

        fun type(name: String): Class<*> = try {
            Class.forName("app.pristinepage.data.files.$name")
        } catch (_: ClassNotFoundException) {
            throw AssertionError("Derived checkpoint file protocol is absent")
        }

        fun Any.call(name: String, vararg args: Any?): Any? = try {
            val method = javaClass.methods.singleOrNull { it.name == name && it.parameterCount == args.size }
                ?: throw AssertionError("Missing or ambiguous derived checkpoint method: $name/${args.size}")
            method.invoke(this, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        } catch (e: Exception) {
            // A broken reflection adapter must not pass a behavioral refusal assertion.
            throw AssertionError("Could not invoke derived checkpoint method: $name", e)
        }

        fun mark(file: File, time: String): BasicFileAttributes {
            Files.setLastModifiedTime(file.toPath(), FileTime.from(Instant.parse(time)))
            return attributes(file)
        }

        fun assertUnchanged(file: File, payload: ByteArray, before: BasicFileAttributes) {
            assertArrayEquals(payload, file.readBytes())
            val after = attributes(file)
            assertEquals(before.lastModifiedTime(), after.lastModifiedTime())
            before.fileKey()?.let { assertEquals(it, after.fileKey()) }
        }

        fun attributes(file: File): BasicFileAttributes =
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
    }
}
