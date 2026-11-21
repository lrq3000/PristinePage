package app.pristinepage.image.export

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** The actual artifact lifecycle with in-memory sinks; Android codec and peak memory are separate native witnesses. */
class PlatformPdfArtifactTest {
    @Test fun successfulWriteReleasesHeavyWriterBeforeReadbackButKeepsTheEvidence() {
        val resource = Fixture()
        resource.artifact.use { artifact ->
            val sink = ByteArrayOutputStream()
            artifact.write(sink) {}
            assertArrayEquals(byteArrayOf(1), sink.toByteArray())
            assertEquals(1, resource.writerCloses)
            assertEquals(0, resource.evidenceCloses)
            artifact.verify(File("unused")) {}
            assertEquals(1, resource.reads)
            assertThrows(IllegalStateException::class.java) { artifact.write(sink) {} }
        }
        assertEquals(1, resource.writerCloses)
        assertEquals(1, resource.evidenceCloses)
    }

    @Test fun failedWriteReleasesWriterAndNeverAllowsReadbackOrASecondWrite() {
        val resource = Fixture(fail = true)
        resource.artifact.use { artifact ->
            val sink = ByteArrayOutputStream()
            assertThrows(IllegalStateException::class.java) { artifact.verify(File("unused")) {} }
            assertThrows(IOException::class.java) { artifact.write(sink) {} }
            assertEquals(1, resource.writerCloses)
            assertThrows(IllegalStateException::class.java) { artifact.verify(File("unused")) {} }
            assertThrows(IllegalStateException::class.java) { artifact.write(sink) {} }
        }
        assertEquals(0, resource.reads)
        assertEquals(1, resource.evidenceCloses)
    }

    @Test fun closingWithoutWritingReleasesBothOwnersOnce() {
        val resource = Fixture()
        resource.artifact.close()
        resource.artifact.close()
        assertEquals(1, resource.writerCloses)
        assertEquals(1, resource.evidenceCloses)
    }

    @Test fun cancelAfterWritingBytesDoesNotAuthorizeReadback() {
        val resource = Fixture()
        resource.artifact.use { artifact ->
            val sink = ByteArrayOutputStream()
            assertThrows(CancellationException::class.java) {
                artifact.write(sink) { if (sink.size() > 0) throw CancellationException() }
            }
            assertArrayEquals(byteArrayOf(1), sink.toByteArray())
            assertEquals(1, resource.writerCloses)
            assertThrows(IllegalStateException::class.java) { artifact.verify(File("unused")) {} }
        }
        assertEquals(1, resource.evidenceCloses)
    }

    @Test fun closeFailureAfterWritingBytesDoesNotAuthorizeReadback() {
        val resource = Fixture(failClose = true)
        resource.artifact.use { artifact ->
            val sink = ByteArrayOutputStream()
            assertThrows(IOException::class.java) { artifact.write(sink) {} }
            assertArrayEquals(byteArrayOf(1), sink.toByteArray())
            assertThrows(IllegalStateException::class.java) { artifact.verify(File("unused")) {} }
        }
        assertEquals(1, resource.writerCloses)
        assertEquals(1, resource.evidenceCloses)
    }

    private class Fixture(fail: Boolean = false, failClose: Boolean = false) {
        var writerCloses = 0
        var evidenceCloses = 0
        var reads = 0
        val artifact = PlatformPdfArtifact(
            writer = AutoCloseable {
                writerCloses++
                if (failClose) throw IOException("synthetic close failure")
            },
            evidence = AutoCloseable { evidenceCloses++ },
            write = { sink, _ -> if (fail) throw IOException("synthetic copy failure") else sink.write(1) },
            readback = { _, _ ->
                check(writerCloses == 1 && evidenceCloses == 0)
                reads++
            },
        )
    }
}
