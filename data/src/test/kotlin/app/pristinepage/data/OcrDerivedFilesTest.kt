package app.pristinepage.data

import app.pristinepage.data.files.DerivedFileTarget
import app.pristinepage.data.files.DerivedFiles
import app.pristinepage.data.files.DerivedKind
import app.pristinepage.data.files.DirectorySync
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class OcrDerivedFilesTest {
    @Test fun sealsMovesAndReopensAnExactBlankInItsOwnNamespace() {
        val root = root()
        val store = store(root)
        val id = id()
        val tx = id()
        val target = DerivedFileTarget(DerivedKind.OCR_TEXT, id)
        val empty = store.stage(tx, target, 0, {})
        assertEquals(0L, empty.byteCount)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", empty.byteSha256)
        val sealed = store.seal(empty, byteArrayOf(1))
        val final = store.move(sealed)
        assertEquals(File(root, "ocr-text-exports/$id/text.bin"), final)
        assertArrayEquals(byteArrayOf(), final.readBytes())
        val reopened = store(root)
        assertEquals(final, reopened.move(checkNotNull(reopened.recover(tx, sealed.sealSha256))))
        assertNotNull(reopened.inspectBoundFinal(tx, sealed.sealSha256, target, 0, {},
            authenticate = { hash, count, _ ->
                assertEquals(empty.byteSha256, hash)
                assertEquals(0L, count)
            },
            readback = { file, _ -> assertArrayEquals(byteArrayOf(), file.readBytes()) },
        ))
        final.writeBytes(byteArrayOf(0))
        assertThrows(IllegalArgumentException::class.java) { reopened.recover(tx, sealed.sealSha256) }
    }

    @Test fun preservesNonemptyTextBytesAndAllowsNoRepresentationInAnImageSnapshot() {
        val root = root()
        val store = store(root)
        val tx = id()
        val kind = DerivedKind.OCR_TEXT
        assertThrows(IllegalArgumentException::class.java) { DerivedFileTarget(kind, id(), id()) }
        val text = "  École\r\n領収書 · إيصال  \n".encodeToByteArray()
        val stage = store.stage(tx, DerivedFileTarget(kind, id()), 36, { it.write(text) })
        val file = store.move(store.seal(stage, byteArrayOf(1)))
        assertEquals("90b8051d4594a1f0bb3715981105bf56cf5854bd0602e4e483d0520e3f8025f8", stage.byteSha256)
        assertArrayEquals(text, file.readBytes())
        assertFalse(File(root, "exports").exists())
        assertFalse(File(root, "renders").exists())
    }

    @Test fun refusesOversizedByteLimitsBeforeAnyPayloadAndStillRefusesEmptyImageArtifacts() {
        val root = root()
        val store = store(root)
        val text = DerivedFileTarget(DerivedKind.OCR_TEXT, id())
        assertThrows(IllegalArgumentException::class.java) {
            store.stage(id(), text, 393_217, { throw AssertionError("Ineligible writer called") })
        }
        assertFalse(File(root, "staging").exists())
        for (image in listOf(DerivedFileTarget(DerivedKind.RENDER, id()), DerivedFileTarget(DerivedKind.EXPORT, id(), id()))) {
            val tx = id()
            assertThrows(IllegalArgumentException::class.java) { store.stage(tx, image, 1, {}) }
            assertNull(store.inspectSeal(tx, null))
        }
    }

    @Test fun rejectsAnOutOfEnvelopeTextSealBeforeLookingForPayloads() {
        val root = root()
        val tx = id()
        val objectId = id()
        val dir = File(root, "staging/$tx").apply { check(mkdirs()) }
        File(dir, "derived-seal.json").writeText(
            """{"version":1,"transactionId":"$tx","kind":"OCR_TEXT","objectId":"$objectId","snapshotId":null,"byteSha256":"${"0".repeat(64)}","byteCount":393217,"metadataBase64":"AQ=="}""",
        )
        val rejected = assertThrows(IllegalArgumentException::class.java) { store(root).inspectSeal(tx, null) }
        assertEquals("Invalid derived payload extent in seal", rejected.message)
        assertThrows(IllegalArgumentException::class.java) {
            store(root).recoverBound(tx, null, DerivedFileTarget(DerivedKind.OCR_TEXT, objectId), Long.MAX_VALUE, false, {},
                authenticate = { _, _, _ -> error("Oversized record passed the extent fence") },
                readback = { _, _ -> error("Unadmitted payload read") },
            )
        }
    }

    @Test fun admitsTheExactCapButCannotSealAnOverflowingWriter() {
        val root = root()
        val store = store(root)
        val glyph = "領".encodeToByteArray()
        val bytes = ByteArray(393_216) { glyph[it % glyph.size] }
        val target = DerivedFileTarget(DerivedKind.OCR_TEXT, id())
        val full = store.stage(id(), target, 393_216, { it.write(bytes) })
        assertEquals(393_216L, full.byteCount)
        assertArrayEquals(bytes, store.move(store.seal(full, byteArrayOf(1))).readBytes())
        val tx = id()
        assertThrows(java.io.IOException::class.java) {
            store.stage(tx, DerivedFileTarget(DerivedKind.OCR_TEXT, id()), 393_216, {
                it.write(bytes)
                it.write(65)
            })
        }
        assertNull(store.inspectSeal(tx, null))
    }

    @Test fun boundRecoveryDoesNotLetAReaderChangeAuthenticatedSealMetadata() {
        val root = root()
        val store = store(root)
        val tx = id()
        val target = DerivedFileTarget(DerivedKind.OCR_TEXT, id())
        val staged = store.stage(tx, target, 1, { it.write(65) })
        val sealed = store.seal(staged, byteArrayOf(1, 2, 3))
        val recovered = store.recoverBound(tx, sealed.sealSha256, target, 1, false, {},
            authenticate = { _, _, metadata -> metadata.fill(0) },
            readback = { file, metadata ->
                assertArrayEquals(byteArrayOf(65), file.readBytes())
                assertArrayEquals(byteArrayOf(1, 2, 3), metadata)
                metadata.fill(0)
            },
        )
        assertArrayEquals(byteArrayOf(1, 2, 3), checkNotNull(recovered).metadata)
    }

    private fun id() = UUID.randomUUID().toString()
    private fun store(root: File) = DerivedFiles(root, DirectorySync { false })
    private fun root(): File {
        val parent = File("build/test-derived-files").canonicalFile.apply { mkdirs() }
        return Files.createTempDirectory(parent.toPath(), "ocr-text-").toFile()
    }
}
