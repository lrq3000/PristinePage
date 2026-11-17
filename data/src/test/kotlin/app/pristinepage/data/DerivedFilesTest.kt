package app.pristinepage.data

import app.pristinepage.data.files.DirectorySync
import java.io.File
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Real closed private files under retained isolated build directories. These are file-protocol witnesses, not Room/journal/codec or power-loss acceptance. */
class DerivedFilesTest {
    private val bytes = byteArrayOf(1,2,3,4)
    private fun root(): File {
        val parent = File(System.getProperty("user.dir"),"build/test-derived-files").apply { mkdirs() }
        return Files.createTempDirectory(parent.toPath(),"case-").toFile()
    }
    private fun type(name: String): Class<*> = try { Class.forName("app.pristinepage.data.files.$name") } catch (_: ClassNotFoundException) { throw AssertionError("Derived checkpoint file protocol is absent") }
    private fun call(value: Any, name: String, vararg args: Any?): Any? = try { value.javaClass.methods.single { it.name == name && it.parameterCount == args.size }.invoke(value,*args) } catch (error: InvocationTargetException) { throw error.targetException }
    private fun store(root: File, sync: DirectorySync = DirectorySync { false }): Any = type("DerivedFiles").getConstructor(File::class.java,DirectorySync::class.java).newInstance(root,sync)
    private fun target(kind: String = "RENDER", objectId: String = UUID.randomUUID().toString(), snapshot: String? = null): Any {
        val enum = type("DerivedKind"); val value = checkNotNull(enum.enumConstants).single { it.toString() == kind }
        return try { type("DerivedFileTarget").getConstructor(enum,String::class.java,String::class.java).newInstance(value,objectId,snapshot) } catch (error: InvocationTargetException) { throw error.targetException }
    }
    private fun stage(store: Any, target: Any, tx: String = UUID.randomUUID().toString(), limit: Long = 4, writer: (OutputStream) -> Unit = { it.write(bytes) }, checkpoint: () -> Unit = {}): Any = call(store,"stage",tx,target,limit,writer,checkpoint)!!

    @Test fun renderAndExportSealsRecoverIdempotentlyToTheirOwnFixedPaths() {
        for (kind in listOf("RENDER","EXPORT")) {
            val root = root(); val store = store(root); val tx = UUID.randomUUID().toString(); val id = UUID.randomUUID().toString(); val snapshot = if (kind == "EXPORT") UUID.randomUUID().toString() else null
            val destination = target(kind,id,snapshot); val staged = stage(store,destination,tx)
            assertEquals(4L,call(staged,"getByteCount")); assertEquals("9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a",call(staged,"getByteSha256"))
            val metadata = byteArrayOf(10,11); val sealed = call(store,"seal",staged,metadata)!!; metadata.fill(0)
            val hash = call(sealed,"getSealSha256")!!; val final = call(store,"move",sealed) as File
            val expected = if (kind == "RENDER") "renders/$id/page.bin" else "exports/$snapshot/$id.bin"
            assertEquals(File(root,expected),final); assertArrayEquals(bytes,final.readBytes())
            repeat(2) { val recovered = call(this.store(root),"recover",tx,hash)!!; assertArrayEquals(byteArrayOf(10,11),call(recovered,"getMetadata") as ByteArray); assertEquals(final,call(store,"move",recovered)) }
            assertFalse(File(root,"staging/$tx/derived.part").exists()); assertFalse(File(root,"sources").exists())
        }
    }

    @Test fun boundedEmptyAndThrowingWritersLeaveOnlyUnsealedPrivateAttempts() {
        for (write in listOf<(OutputStream)->Unit>({ },{ it.write(byteArrayOf(1,2,3,4,5)) },{ it.write(1); throw java.io.IOException("synthetic writer failure") })) {
            val root = root(); val store = store(root); val tx = UUID.randomUUID().toString(); val destination = target()
            assertThrows(Exception::class.java) { stage(store,destination,tx,writer = write) }
            assertNull(call(store,"recover",tx,null)); assertFalse(File(root,"renders").exists()); assertFalse(File(root,"staging/$tx/derived-seal.json").exists())
        }
    }

    @Test fun escapedWriterAndCloseCannotMutateTheFinishedStagingHash() {
        val root = root(); val store = store(root); val tx = UUID.randomUUID().toString(); var escaped: OutputStream? = null
        val staged = stage(store,target(),tx,writer = { escaped = it; it.write(bytes); it.close() })
        assertThrows(java.io.IOException::class.java) { escaped!!.write(99) }
        assertArrayEquals(bytes,File(root,"staging/$tx/derived.part").readBytes())
        val sealed = call(store,"seal",staged,byteArrayOf(10))!!; assertArrayEquals(bytes,(call(store,"move",sealed) as File).readBytes())
    }

    @Test fun existingStageAndConflictingFinalAreNeverReplaced() {
        val root = root(); val store = store(root); val tx = UUID.randomUUID().toString(); val destination = target()
        val staged = stage(store,destination,tx)
        assertThrows(Exception::class.java) { stage(store,destination,tx) }
        val sealed = call(store,"seal",staged,byteArrayOf(10))!!
        val final = call(store,"finalFile",destination) as File; final.parentFile!!.mkdirs(); final.writeBytes(byteArrayOf(99))
        assertThrows(Exception::class.java) { call(store,"move",sealed) }; assertArrayEquals(byteArrayOf(99),final.readBytes()); assertArrayEquals(bytes,File(root,"staging/$tx/derived.part").readBytes())
    }

    @Test fun changedSealOrPayloadIsNotAValidRecoveredCheckpoint() {
        for (mutateSeal in listOf(false,true)) {
            val root = root(); val store = store(root); val tx = UUID.randomUUID().toString(); val staged = stage(store,target(),tx)
            val sealed = call(store,"seal",staged,byteArrayOf(10))!!; val hash = call(sealed,"getSealSha256")
            File(root,"staging/$tx/${if (mutateSeal) "derived-seal.json" else "derived.part"}").appendBytes(byteArrayOf(42))
            assertThrows(Exception::class.java) { call(this.store(root),"recover",tx,hash) }; assertFalse(File(root,"renders").exists())
        }
    }

    @Test fun invalidTargetsAndTransactionsRefuseBeforeCreatingAnyPayload() {
        val root = root(); val store = store(root)
        for (id in listOf("../escape","","not-an-id")) {
            assertThrows(IllegalArgumentException::class.java) { target(objectId = id) }
            assertThrows(IllegalArgumentException::class.java) { stage(store,target(),tx = id) }
        }
        assertThrows(IllegalArgumentException::class.java) { target("EXPORT") }
        assertThrows(IllegalArgumentException::class.java) { target(snapshot = UUID.randomUUID().toString()) }
        assertEquals(emptyList<File>(),root.walkTopDown().filter(File::isFile).toList())
    }

    @Test fun syncFailureNeverCreatesASealOrFinalFile() {
        val root = root(); val store = store(root,DirectorySync { throw java.io.IOException("synthetic sync failure") }); val tx = UUID.randomUUID().toString()
        assertThrows(java.io.IOException::class.java) { stage(store,target(),tx) }
        assertNull(call(store,"recover",tx,null)); assertFalse(File(root,"renders").exists())
    }

    @Test fun cancellationBeforeDurableCompletionLeavesTheAttemptUnsealed() {
        val root = root(); val store = store(root); val tx = UUID.randomUUID().toString(); var checks = 0
        assertThrows(java.util.concurrent.CancellationException::class.java) { stage(store,target(),tx,checkpoint = { if (++checks == 3) throw java.util.concurrent.CancellationException() }) }
        assertNull(call(store,"recover",tx,null)); assertFalse(File(root,"renders").exists())
    }
}
