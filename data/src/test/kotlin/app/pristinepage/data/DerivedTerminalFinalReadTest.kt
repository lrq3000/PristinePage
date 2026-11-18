package app.pristinepage.data

import app.pristinepage.data.files.DerivedByteLimitExceeded
import app.pristinepage.data.files.DerivedFileTarget
import app.pristinepage.data.files.DerivedFiles
import app.pristinepage.data.files.DerivedKind
import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.files.ObservedDerivedSeal
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** Host file witnesses only. The trusted caller's expected binding and the semantic readback are synthetic; the files, seal and byte scans are real. */
class DerivedTerminalFinalReadTest {
    @Test fun finalOnlyReadAuthenticatesBytesAndMetadataWithoutForceOrSync() {
        val attempt = Attempt()
        val sealBytes = attempt.seal.readBytes()
        val calls = mutableListOf<String>()

        val observed = checkNotNull(attempt.inspect(
            authenticate = { sha, count, metadata ->
                Binding().authenticate(sha, count, metadata)
                calls += "authenticate"
            },
            readback = { file, metadata ->
                attempt.assertFinalReadback(file, metadata)
                calls += "readback"
            },
        ))

        assertEquals(listOf("authenticate", "readback"), calls)
        assertEquals(attempt.sealSha256, observed.sealSha256)
        assertEquals(attempt.transactionId, observed.staged.transactionId)
        assertEquals(attempt.target, observed.staged.target)
        assertEquals(PAYLOAD_SHA, observed.staged.byteSha256)
        assertEquals(BYTE_COUNT, observed.staged.byteCount)
        assertArrayEquals(METADATA, observed.metadata)
        assertArrayEquals(sealBytes, attempt.seal.readBytes())
        assertArrayEquals(PAYLOAD, attempt.final.readBytes())
        assertFalse(attempt.stage.exists())
    }

    @Test fun callbackMetadataMutationsDoNotChangeReadbackOrObservation() {
        val attempt = Attempt()
        val expectedMetadata = METADATA.copyOf()
        val expectedSealSha256 = attempt.sealSha256
        val calls = mutableListOf<String>()

        val observed = checkNotNull(attempt.inspect(
            expectedSealSha256 = expectedSealSha256,
            authenticate = { sha, count, metadata ->
                Binding(metadata = expectedMetadata).authenticate(sha, count, metadata)
                // Deliberately scribble on callback scratch, never on either file.
                metadata.fill(0)
                calls += "authenticate"
            },
            readback = { file, metadata ->
                attempt.assertFinalReadback(file, metadata)
                metadata.fill(-1)
                calls += "readback"
            },
        ))

        assertEquals(listOf("authenticate", "readback"), calls)
        assertArrayEquals(expectedMetadata, observed.metadata)
        assertEquals(expectedSealSha256, observed.sealSha256)
    }

    @Test fun aValidSealedStageAloneIsNotATerminalFinal() {
        val attempt = Attempt(Copies.STAGE_ONLY)
        // The old read-only observation succeeds: the new refusal must be about the missing final, not an invalid fixture.
        val staged = checkNotNull(attempt.reader.inspectSeal(attempt.transactionId, attempt.sealSha256))
        assertEquals(PAYLOAD_SHA, staged.staged.byteSha256)
        assertThrows(IllegalArgumentException::class.java) { attempt.inspect() }
        assertArrayEquals(PAYLOAD, attempt.stage.readBytes())
        assertFalse(attempt.final.exists())
    }

    @Test fun wrongExactSealTargetOrCapRefusesBeforeCallerAuthenticationOrReadback() {
        val attempt = Attempt()
        val alternateTarget = DerivedFileTarget(DerivedKind.RENDER, UUID.randomUUID().toString())
        val unexpectedAuth: (String, Long, ByteArray) -> Unit = { _, _, _ -> throw AssertionError("Unexpected binding authentication") }

        assertThrows(IllegalArgumentException::class.java) {
            attempt.inspect(expectedSealSha256 = OTHER_SHA, authenticate = unexpectedAuth)
        }
        assertThrows(IllegalArgumentException::class.java) {
            attempt.inspect(target = alternateTarget, authenticate = unexpectedAuth)
        }
        // The typed cap refusal must also win over the competing payload-integrity failure.
        Files.write(attempt.final.toPath(), DRIFT, WRITE)
        assertThrows(DerivedByteLimitExceeded::class.java) {
            attempt.inspect(maxBytes = BYTE_COUNT - 1, authenticate = unexpectedAuth)
        }
    }

    @Test fun anExactDigestDoesNotAuthenticateNoncanonicalSealBytes() {
        val attempt = Attempt()
        attempt.seal.appendText(" ")
        // Compute the exact expected digest over the changed seal; hash mismatch alone must not be what refuses it.
        assertThrows(IllegalArgumentException::class.java) {
            attempt.inspect(
                expectedSealSha256 = sha256(attempt.seal.readBytes()),
                authenticate = { _, _, _ -> throw AssertionError("Unexpected authentication of noncanonical seal") },
            )
        }
    }

    @Test fun callerRefusesWrongEncodedIdentityOrCanonicalRasterMetadataBeforeScanningPayload() {
        for (wrongBinding in listOf(
            Binding(byteSha256 = OTHER_SHA),
            Binding(byteCount = BYTE_COUNT + 1),
            Binding(metadata = metadataFor(OTHER_SHA)),
        )) {
            val attempt = Attempt()
            // A same-extent corrupt final would fail its byte scan. Only the caller's distinct exception proves it rejected the bound header first.
            Files.write(attempt.final.toPath(), DRIFT, WRITE)
            assertThrows(BindingMismatch::class.java) { attempt.inspect(authenticate = wrongBinding::authenticate) }
        }
    }

    @Test fun matchingFinalCannotHideAnAlreadyCorruptStage() {
        val attempt = Attempt(Copies.BOTH)
        Files.write(attempt.stage.toPath(), DRIFT, WRITE)

        assertThrows(IllegalArgumentException::class.java) { attempt.inspect() }
        assertArrayEquals(DRIFT, attempt.stage.readBytes())
        assertArrayEquals(PAYLOAD, attempt.final.readBytes())
    }

    @Test fun matchingStageCannotHideAnAlreadyCorruptFinal() {
        val attempt = Attempt(Copies.BOTH)
        Files.write(attempt.final.toPath(), DRIFT, WRITE)

        assertThrows(IllegalArgumentException::class.java) { attempt.inspect() }
        assertArrayEquals(PAYLOAD, attempt.stage.readBytes())
        assertArrayEquals(DRIFT, attempt.final.readBytes())
    }

    @Test fun twoMatchingCopiesStillReadBackOnlyTheFinal() {
        val attempt = Attempt(Copies.BOTH)
        var calls = 0
        checkNotNull(attempt.inspect(readback = { file, metadata ->
            attempt.assertFinalReadback(file, metadata)
            calls++
        }))
        assertEquals(1, calls)
        assertArrayEquals(PAYLOAD, attempt.stage.readBytes())
        assertArrayEquals(PAYLOAD, attempt.final.readBytes())
    }

    @Test fun postscanRejectsSameExtentDriftInEitherInitiallyVerifiedCopy() {
        for (mutateStage in listOf(false, true)) {
            val attempt = Attempt(Copies.BOTH)
            val changed = if (mutateStage) attempt.stage else attempt.final
            val unchanged = if (mutateStage) attempt.final else attempt.stage
            val rejection = assertThrows(IllegalArgumentException::class.java) {
                attempt.inspect(readback = { file, metadata ->
                    attempt.assertFinalReadback(file, metadata)
                    // Fault injection deliberately violates the read-only borrow, on this test's own closed file only.
                    Files.write(changed.toPath(), DRIFT, WRITE)
                })
            }
            assertEquals(BYTE_COUNT, changed.length())
            assertArrayEquals("Mutation must have happened before $rejection", DRIFT, changed.readBytes())
            assertArrayEquals(PAYLOAD, unchanged.readBytes())
        }
    }

    @Test fun postscanRejectsAnAddedStageEvenIfItsBytesMatch() {
        val attempt = Attempt()
        assertThrows(IllegalArgumentException::class.java) {
            attempt.inspect(readback = { file, metadata ->
                attempt.assertFinalReadback(file, metadata)
                Files.write(attempt.stage.toPath(), PAYLOAD, CREATE_NEW, WRITE)
            })
        }
        assertArrayEquals(PAYLOAD, attempt.stage.readBytes())
    }

    @Test fun postscanRejectsMissingOrExtendedFinal() {
        for (removeFinal in listOf(true, false)) {
            val attempt = Attempt(Copies.BOTH)
            val retained = File(attempt.final.parentFile, "detached-fixture.bin")
            assertThrows(IllegalArgumentException::class.java) {
                attempt.inspect(readback = { file, metadata ->
                    attempt.assertFinalReadback(file, metadata)
                    if (removeFinal) Files.move(file.toPath(), retained.toPath()) else file.appendBytes(byteArrayOf(9))
                })
            }
            assertArrayEquals(PAYLOAD, attempt.stage.readBytes())
            if (removeFinal) {
                assertFalse(attempt.final.exists())
                assertArrayEquals(PAYLOAD, retained.readBytes())
            } else {
                assertArrayEquals(PAYLOAD + byteArrayOf(9), attempt.final.readBytes())
            }
        }
    }

    @Test fun presentFinalWithoutASealReturnsNoObservationAndCallsNeitherCallback() {
        val attempt = Attempt(sealed = false)
        assertArrayEquals(PAYLOAD, attempt.final.readBytes())
        assertFalse(attempt.seal.exists())

        assertNull(attempt.inspect(expectedSealSha256 = OTHER_SHA, authenticate = { _, _, _ ->
            throw AssertionError("Unexpected authentication without a seal")
        }))
    }

    @Test fun cancellationAfterReadbackCannotReturnAnObservation() {
        val attempt = Attempt()
        val canceled = CancellationException("synthetic cancellation in readback")
        var readBack = false

        assertSame(canceled, assertThrows(CancellationException::class.java) {
            attempt.inspect(
                checkpoint = { if (readBack) throw canceled },
                readback = { file, metadata ->
                    attempt.assertFinalReadback(file, metadata)
                    readBack = true
                },
            )
        })
    }

    @Test fun semanticRefusalCannotReturnAnObservation() {
        val attempt = Attempt()
        val mismatch = BindingMismatch()

        assertSame(mismatch, assertThrows(BindingMismatch::class.java) {
            attempt.inspect(readback = { file, metadata ->
                attempt.assertFinalReadback(file, metadata)
                throw mismatch
            })
        })
    }

    private enum class Copies { FINAL_ONLY, STAGE_ONLY, BOTH }

    private class Attempt(copies: Copies = Copies.FINAL_ONLY, sealed: Boolean = true) {
        private val root = Files.createTempDirectory(
            Files.createDirectories(File(System.getProperty("user.dir"), "build/test-derived-terminal-final").toPath()),
            "case-",
        ).toFile().canonicalFile
        val transactionId = UUID.randomUUID().toString()
        val target = DerivedFileTarget(DerivedKind.RENDER, UUID.randomUUID().toString())
        val stage = File(root, "staging/$transactionId/derived.part")
        val seal = File(root, "staging/$transactionId/derived-seal.json")
        val final = File(root, "renders/${target.objectId}/page.bin")
        private val expectedSealDigest: String?
        val sealSha256: String get() = checkNotNull(expectedSealDigest)

        // Setup uses a separate store; forcing or syncing during the terminal read is an assertion failure.
        val reader = DerivedFiles(root, DirectorySync { throw AssertionError("Terminal read must not sync a directory") }) {
            throw AssertionError("Terminal read must not force the seal")
        }

        init {
            val writer = DerivedFiles(root, DirectorySync { true })
            val staged = writer.stage(transactionId, target, BYTE_COUNT, { it.write(PAYLOAD) })
            expectedSealDigest = if (sealed) {
                writer.seal(staged, METADATA)
                sha256(seal.readBytes())
            } else null
            if (copies != Copies.STAGE_ONLY) {
                Files.createDirectories(checkNotNull(final.parentFile).toPath())
                // Establish only the fixture's on-disk state, not a producer's MOVED or COMMITTED authority.
                if (copies == Copies.BOTH) Files.write(final.toPath(), PAYLOAD, CREATE_NEW, WRITE)
                else Files.move(stage.toPath(), final.toPath())
            }
        }

        fun inspect(
            expectedSealSha256: String = sealSha256,
            target: DerivedFileTarget = this.target,
            maxBytes: Long = BYTE_COUNT,
            checkpoint: () -> Unit = {},
            authenticate: (String, Long, ByteArray) -> Unit = Binding()::authenticate,
            readback: (File, ByteArray) -> Unit = { _, _ -> throw AssertionError("Unexpected terminal readback") },
        ): ObservedDerivedSeal? =
            reader.inspectBoundFinal(transactionId, expectedSealSha256, target, maxBytes, checkpoint, authenticate, readback)

        fun assertFinalReadback(file: File, metadata: ByteArray) {
            assertEquals(final, file)
            assertArrayEquals(METADATA, metadata)
            assertArrayEquals(PAYLOAD, file.readBytes())
        }
    }

    private class Binding(
        private val byteSha256: String = PAYLOAD_SHA,
        private val byteCount: Long = BYTE_COUNT,
        private val metadata: ByteArray = METADATA,
    ) {
        fun authenticate(sha: String, count: Long, canonicalMetadata: ByteArray) {
            if (sha != byteSha256 || count != byteCount || !metadata.contentEquals(canonicalMetadata)) throw BindingMismatch()
        }
    }

    private class BindingMismatch : IllegalArgumentException("Synthetic terminal binding refused")

    private companion object {
        const val BYTE_COUNT = 4L
        const val PAYLOAD_SHA = "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a"
        val PAYLOAD = byteArrayOf(1, 2, 3, 4)
        val DRIFT = byteArrayOf(1, 2, 3, 5)
        val OTHER_SHA = "b".repeat(64)
        val METADATA = metadataFor("a".repeat(64))

        fun metadataFor(rasterSha: String) = "terminal-fixture-v1:raster-sha256=$rasterSha".encodeToByteArray()
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
