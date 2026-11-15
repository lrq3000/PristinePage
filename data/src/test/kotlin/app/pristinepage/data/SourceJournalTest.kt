package app.pristinepage.data

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** Real filesystem tests retain their isolated directories for manual cleanup. Later Room tests bind the same protocol to the database journal; these tests alone are not crash/Room acceptance. */
class SourceJournalTest {
    private val tx = "9b58d249-0641-4cb7-bbe7-ff805098b21b"
    private val blob = "180aab39-4036-4979-922e-e91a1ded30c4"
    private val bytes = byteArrayOf(1, 2, 3, 4)

    private fun store(root: File): Any = try {
        Class.forName("app.pristinepage.data.files.SourceFiles").getConstructor(File::class.java).newInstance(root)
    } catch (error: ClassNotFoundException) { throw AssertionError("Source journal file protocol is absent", error) }

    private fun call(target: Any, name: String, vararg args: Any?): Any? = try {
        target.javaClass.methods.single { it.name == name && it.parameterCount == args.size }.invoke(target, *args)
    } catch (error: InvocationTargetException) { throw error.targetException }

    private fun root(): File {
        val approved = File(System.getProperty("user.dir"), "build/test-source-files").apply { mkdirs() }
        return Files.createTempDirectory(approved.toPath(), "case-").toFile()
    }

    private fun stage(store: Any, input: InputStream = ByteArrayInputStream(bytes), limit: Long = 4) = call(store, "stage", tx, blob, input, limit)!!

    @Test fun sealedMoveRecoversIdempotentlyWithoutChangingOriginalBytes() {
        val root = root(); val store = store(root)
        val staged = stage(store)
        assertEquals("9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a", call(staged, "getByteSha256"))
        assertEquals(4L, call(staged, "getByteCount"))
        val sealed = call(store, "seal", staged, byteArrayOf(10, 11))!!
        val sealHash = call(sealed, "getSealSha256")!!
        val final = call(store, "move", sealed) as File
        assertArrayEquals(bytes, final.readBytes())
        val restarted = this.store(root)
        repeat(2) {
            val recovered = call(restarted, "recover", tx, sealHash)!!
            assertEquals(final, call(restarted, "move", recovered))
            assertArrayEquals(byteArrayOf(10, 11), call(recovered, "getMetadata") as ByteArray)
        }
        assertArrayEquals(bytes, final.readBytes())
        assertFalse(File(root, "staging/$tx/original.part").exists())
    }

    @Test fun oversizedEmptyOrRevokedReadsNeverCreateASealedSource() {
        for (input in listOf(ByteArrayInputStream(byteArrayOf()), ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)), object : InputStream() {
            private var count = 0
            override fun read(): Int = if (count++ < 2) 7 else throw SecurityException("synthetic provider revocation")
        })) {
            val root = root(); val store = store(root)
            assertThrows(Exception::class.java) { stage(store, input) }
            assertNull(call(store, "recover", tx, null))
            assertFalse(File(root, "sources/$blob/original.bin").exists())
        }
    }

    @Test fun unsafeIdsCannotWriteOutsideStore() {
        val root = root(); val store = store(root)
        for (bad in listOf("../escape", "x", "", "/absolute", "9b58d249-0641-4cb7-bbe7-ff805098b21b/other")) {
            assertThrows(IllegalArgumentException::class.java) { call(store, "stage", bad, blob, ByteArrayInputStream(bytes), 4L) }
            assertThrows(IllegalArgumentException::class.java) { call(store, "stage", tx, bad, ByteArrayInputStream(bytes), 4L) }
        }
        assertEquals(emptyList<String>(), root.walkTopDown().filter { it.isFile }.map { it.name }.toList())
    }

    @Test fun staleStageAndConflictingFinalNeverOverwriteExistingPayload() {
        val root = root(); val store = store(root)
        val staged = stage(store)
        assertThrows(Exception::class.java) { stage(store, ByteArrayInputStream(byteArrayOf(9))) }
        val sealed = call(store, "seal", staged, byteArrayOf(10))!!
        val target = File(root, "sources/$blob/original.bin").apply { checkNotNull(parentFile).mkdirs(); writeBytes(byteArrayOf(99)) }
        assertThrows(Exception::class.java) { call(store, "move", sealed) }
        assertArrayEquals(byteArrayOf(99), target.readBytes())
        assertArrayEquals(bytes, File(root, "staging/$tx/original.part").readBytes())
    }

    @Test fun changedSealOrPayloadCannotBeRecoveredAsTheAcceptedSource() {
        for (corruptSeal in listOf(false, true)) {
            val root = root(); val store = store(root)
            val sealed = call(store, "seal", stage(store), byteArrayOf(10))!!
            val sealHash = call(sealed, "getSealSha256")
            val target = File(root, "staging/$tx/${if (corruptSeal) "seal.json" else "original.part"}")
            target.appendBytes(byteArrayOf(42))
            assertThrows(Exception::class.java) { call(this.store(root), "recover", tx, sealHash) }
            assertFalse(File(root, "sources/$blob/original.bin").exists())
        }
    }

    @Test fun payloadWithoutSealRemainsUncommittedRatherThanGuessedValid() {
        val root = root(); val store = store(root)
        stage(store)
        assertNull(call(this.store(root), "recover", tx, null))
        assertArrayEquals(bytes, File(root, "staging/$tx/original.part").readBytes())
    }
}
