package app.pristinepage.data

import app.pristinepage.data.library.LibraryObjectTransfer
import app.pristinepage.data.library.LibraryObjectTransfer.ObjectId
import app.pristinepage.data.library.LibraryObjectTransfer.Requirement
import app.pristinepage.data.library.LibraryObjectTransfer.Store
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryObjectTransferTest {
    @get:Rule val temporary = TemporaryFolder()
    private val abc = ObjectId("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", 3)
    private val hello = ObjectId("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", 5)

    @Test fun preflightIsReadOnlyAndCountsOnlyMissingObjectBytes() {
        val target = folder("target").apply { file(abc).writeText("abc") }
        val source = folder("backup").apply { file(abc).writeText("abc"); file(hello).writeText("hello") }
        val plan = LibraryObjectTransfer(target).prepare(listOf(source.requirement(abc), source.requirement(hello)), 12, 17)

        assertEquals(5L, plan.missingBytes)
        assertEquals(17L, plan.requiredBytes)
        assertEquals(listOf(abc.sha256), target.root.list()!!.toList())
        assertEquals("abc", target.file(abc).readText())
        plan.copyAndVerify()
        assertEquals("hello", target.file(hello).readText())
        assertEquals("hello", source.file(hello).readText())
    }

    @Test fun subsequentTransferReusesVerifiedObjectsWithoutRewritingThem() {
        val target = folder("target").apply { file(abc).writeText("abc") }
        val source = folder("backup").apply { file(abc).writeText("abc") }
        val plan = LibraryObjectTransfer(target).prepare(listOf(source.requirement(abc)), 0, 0)
        assertEquals(0L, plan.missingBytes)
        plan.copyAndVerify() // Fixture storage opens newly created objects with CREATE_NEW; a redundant rewrite would fail.
        assertEquals("abc", target.file(abc).readText())
    }

    @Test fun insufficientSpaceAndArithmeticOverflowRefuseBeforeWriting() {
        val target = folder("target")
        val source = folder("backup").apply { file(hello).writeText("hello") }
        val transfer = LibraryObjectTransfer(target)
        assertThrows(IOException::class.java) { transfer.prepare(listOf(source.requirement(hello)), 12, 16) }
        assertThrows(IllegalArgumentException::class.java) {
            transfer.prepare(listOf(source.requirement(hello)), Long.MAX_VALUE, Long.MAX_VALUE)
        }
        assertEquals(emptyList<String>(), target.root.list()!!.toList())
    }

    @Test fun corruptIncomingOriginalIsRefusedEvenWhenTargetHasTheClaimedObject() {
        val target = folder("target").apply { file(abc).writeText("abc") }
        val source = folder("backup").apply { file(abc).writeText("xyz") }
        assertThrows(IOException::class.java) {
            LibraryObjectTransfer(target).prepare(listOf(source.requirement(abc)), 0, 0)
        }
        assertEquals("abc", target.file(abc).readText())
        assertEquals("xyz", source.file(abc).readText())
    }

    @Test fun aTruncatedOrOversizedObjectCannotSatisfyACatalogReference() {
        val target = folder("target")
        val source = folder("backup")
        for (contents in listOf("ab", "abcd")) {
            source.file(abc).writeText(contents)
            assertThrows(IOException::class.java) { LibraryObjectTransfer(target).prepare(listOf(source.requirement(abc)), 0, 3) }
        }
        assertEquals(emptyList<String>(), target.root.list()!!.toList())
    }

    @Test fun aMissingTargetOnlyObjectIsNotAValidDestination() {
        val target = folder("target")
        assertThrows(IOException::class.java) {
            LibraryObjectTransfer(target).prepare(listOf(Requirement(abc, null)), 0, 100)
        }
    }

    @Test fun oneObjectWithTwoIncomingOwnersIsBudgetedAndCopiedOnce() {
        val target = folder("target")
        val first = folder("first").apply { file(abc).writeText("abc") }
        val second = folder("second").apply { file(abc).writeText("abc") }
        val plan = LibraryObjectTransfer(target).prepare(listOf(first.requirement(abc), second.requirement(abc)), 0, 3)
        assertEquals(3L, plan.missingBytes)
        plan.copyAndVerify()
        assertEquals(listOf(abc.sha256), target.root.list()!!.toList())
        assertEquals("abc", target.file(abc).readText())
    }

    @Test fun conflictingLengthsForOneDigestCannotProduceAPlan() {
        val target = folder("target")
        val source = folder("backup").apply { file(abc).writeText("abc") }
        val wrong = abc.copy(byteCount = 4)
        assertThrows(IOException::class.java) {
            LibraryObjectTransfer(target).prepare(listOf(source.requirement(abc), source.requirement(wrong)), 0, 100)
        }
        assertEquals(emptyList<String>(), target.root.list()!!.toList())
    }

    @Test fun retryAfterAnInterruptedCopyReusesTheCompletedObject() {
        val target = folder("target")
        val source = folder("backup").apply { file(abc).writeText("abc"); file(hello).writeText("hello") }
        val requirements = listOf(source.requirement(abc), source.requirement(hello))
        val interrupted = object : Store by target {
            override fun create(id: ObjectId, write: (OutputStream) -> Unit) {
                if (id == hello) throw IOException("Synthetic interruption between object publications")
                target.create(id, write)
            }
        }
        val first = LibraryObjectTransfer(interrupted).prepare(requirements, 0, 8)
        assertThrows(IOException::class.java) { first.copyAndVerify() }
        assertEquals(listOf(abc.sha256), target.root.list()!!.toList())
        val retry = LibraryObjectTransfer(target).prepare(requirements, 0, 5)
        assertEquals(5L, retry.missingBytes)
        retry.copyAndVerify()
        assertEquals("hello", target.file(hello).readText())
    }

    @Test fun changedSourceAfterPreflightCannotProduceAVerifiedCopy() {
        val target = folder("target")
        val source = folder("backup").apply { file(abc).writeText("abc") }
        val plan = LibraryObjectTransfer(target).prepare(listOf(source.requirement(abc)), 0, 3)
        source.file(abc).writeText("xyz")
        assertThrows(IOException::class.java) { plan.copyAndVerify() }
        assertFalse(target.file(abc).exists())
    }

    @Test fun verifiesClosedDestinationNotJustBytesSentToProvider() {
        val target = folder("target")
        val source = folder("backup").apply { file(abc).writeText("abc") }
        val corruptAfterClose = object : Store by target {
            override fun create(id: ObjectId, write: (OutputStream) -> Unit) {
                target.create(id, write)
                target.file(id).writeText("xyz")
            }
        }
        val plan = LibraryObjectTransfer(corruptAfterClose).prepare(listOf(source.requirement(abc)), 0, 3)
        assertThrows(IOException::class.java) { plan.copyAndVerify() }
        assertEquals("abc", source.file(abc).readText())
    }

    private fun folder(name: String) = Folder(temporary.newFolder(name))

    private class Folder(val root: File) : Store {
        fun file(id: ObjectId) = File(root, id.sha256)
        fun requirement(id: ObjectId) = Requirement(id) { file(id).inputStream() }
        override fun open(id: ObjectId): InputStream? = file(id).takeIf(File::exists)?.inputStream()
        override fun create(id: ObjectId, write: (OutputStream) -> Unit) {
            val stage = Files.createTempFile(root.toPath(), "stage-", ".part")
            try {
                Files.newOutputStream(stage, WRITE).use(write)
                Files.newOutputStream(file(id).toPath(), CREATE_NEW, WRITE).use { Files.copy(stage, it) }
            } finally {
                Files.deleteIfExists(stage) // This path is uniquely created and owned by this disposable test store.
            }
        }
    }
}
