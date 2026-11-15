package app.pristinepage.data

import app.pristinepage.data.files.SourceFiles
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** Exercises actual OS file descriptors, not a fake close counter. Unique synthetic files remain under the approved test build tree for manual cleanup. */
class SourceStreamOwnershipTest {
    private val tx = "9b58d249-0641-4cb7-bbe7-ff805098b21b"
    private val blob = "180aab39-4036-4979-922e-e91a1ded30c4"
    private fun case(): File {
        val parent = File(System.getProperty("user.dir"), "build/test-source-files").apply { mkdirs() }
        return Files.createTempDirectory(parent.toPath(), "ownership-").toFile()
    }

    @Test fun invalidAdmissionClosesTheSuppliedDescriptorWithoutChangingOriginalBytes() {
        for (badId in listOf(true, false)) {
            val root = case(); val source = File(root, "synthetic-source.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val input = FileInputStream(source)
            try {
                assertThrows(IllegalArgumentException::class.java) { SourceFiles(File(root, "store")).stage(if (badId) "../escape" else tx, blob, input, if (badId) 3 else 0) }
                assertFalse("Stage owns its input even when validation fails before the first read", input.fd.valid())
                assertArrayEquals(byteArrayOf(1, 2, 3), source.readBytes())
            } finally { input.close() }
        }
    }

    @Test fun stagingDirectoryFailureClosesTheSuppliedDescriptorAndPreservesTheConflict() {
        val root = case(); val store = File(root, "store").apply { mkdirs() }
        val conflict = File(store, "staging").apply { writeBytes(byteArrayOf(99)) }
        val source = File(root, "synthetic-source.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val input = FileInputStream(source)
        try {
            assertThrows(Exception::class.java) { SourceFiles(store).stage(tx, blob, input, 3) }
            assertFalse("Directory setup failure must not leak the open provider descriptor", input.fd.valid())
            assertArrayEquals(byteArrayOf(99), conflict.readBytes())
            assertArrayEquals(byteArrayOf(1, 2, 3), source.readBytes())
        } finally { input.close() }
    }
}
