package app.pristinepage.data

import app.pristinepage.data.files.SourceFiles
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** Real immutable-original proof; retained isolated files are not actual PNG/decoder fixtures and never leave the store. No deletion/cleanup. */
class ExportSourceProofTest {
    private val bytes = byteArrayOf(1, 2, 3, 4)
    private class Fixture {
        val metadata = RepositoryFixture()
        val root: File = Files.createTempDirectory(File(System.getProperty("user.dir"), "build/test-export-proof").apply { mkdirs() }.toPath(), "case-").toFile()
        val files = SourceFiles(root)
        val source = metadata.metadata.sources.getValue(metadata.source.value)
        val blob = metadata.metadata.blobs.getValue(source.sourceBlobId).copy(byteSha256 = "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a")
        val binding = ExportSourceBinding(source, blob)
        fun proof(): ExportSourceProof {
            val type = try { Class.forName("app.pristinepage.data.repository.FileExportSourceProof") }
            catch (_: ClassNotFoundException) { throw AssertionError("Closed original export proof is absent") }
            return try { type.constructors.single { it.parameterCount == 2 }.newInstance(files, Dispatchers.Unconfined) as ExportSourceProof }
            catch (failure: InvocationTargetException) { throw failure.targetException }
        }
        fun write(payload: ByteArray) = files.finalFile(blob.sourceBlobId).apply { parentFile!!.mkdirs(); writeBytes(payload) }
    }

    @Test fun matchingClosedSourceIsReadOnlyAndRepeatable() = runTest {
        val f = Fixture(); val proof = f.proof(); val file = f.write(bytes)
        repeat(2) { proof.verify(f.binding) }; assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun missingTruncatedAndSameLengthChangedOriginalsNeverBecomeVerified() = runTest {
        for (payload in listOf(null, byteArrayOf(1, 2), byteArrayOf(9, 2, 3, 4))) {
            val f = Fixture(); val proof = f.proof(); val file = payload?.let(f::write)
            val error = try { proof.verify(f.binding); throw AssertionError("Missing/changed source accepted") } catch (failure: RepositoryFailure) { failure.error }
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, error.code); assertEquals(f.source.sourceId, error.affectedId)
            if (file != null) assertArrayEquals(payload, file.readBytes())
        }
    }

    @Test fun foreignSourceBlobOrRecordedPathCannotRedirectProof() = runTest {
        val f = Fixture(); val proof = f.proof(); val file = f.write(bytes)
        val bad = listOf(f.binding.copy(blob = f.blob.copy(relativePath = "../outside")), f.binding.copy(source = f.source.copy(sourceBlobId = java.util.UUID.randomUUID().toString())), f.binding.copy(blob = f.blob.copy(byteCount = Long.MAX_VALUE)))
        for (binding in bad) {
            val error = try { proof.verify(binding); throw AssertionError("Invalid binding accepted") } catch (failure: RepositoryFailure) { failure.error }
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, error.code)
        }
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun knownCancellationCannotBeFlattenedIntoSuccessfulProof() = runTest {
        val f = Fixture(); val proof = f.proof(); f.write(bytes); val job = Job(); job.cancel(); var canceled = false
        try { withContext(job) { proof.verify(f.binding) } } catch (_: kotlinx.coroutines.CancellationException) { canceled = true }
        assertTrue(canceled)
    }
}
