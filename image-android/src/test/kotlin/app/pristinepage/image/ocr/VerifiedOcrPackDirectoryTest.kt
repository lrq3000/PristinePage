package app.pristinepage.image.ocr

import app.pristinepage.core.ocr.OcrLanguagePack
import app.pristinepage.core.ocr.OcrLanguagePacks
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VerifiedOcrPackDirectoryTest {
    @get:Rule val fixture = TemporaryFolder()
    private val english = OcrLanguagePack("eng", "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824")
    private val french = OcrLanguagePack("fra", "486ea46224d1bb4fb680f34f7c9ad96a8f24ec88be73ea8e5a6c65260e9cb8a7")

    @Test fun resolvesOnlyExactInstalledDefaultsInTheRequestedOrder() {
        val root = installFixture()
        val packs = verifier(root)
        assertEquals(listOf(french, english), packs.resolveOrdered(listOf("fra", "eng"))?.ordered)
        assertNull(packs.resolveOrdered(emptyList()))
        assertNull(packs.resolveOrdered(listOf("fra", "fra")))
        assertNull(packs.resolveOrdered(listOf("eng", "jpn")))
        assertNull(packs.resolveOrdered(listOf("../eng")))
    }

    @Test fun restartRefusesChangedBytesAndDoesNotSubstituteForStoredDigests() {
        val root = installFixture()
        val stored = OcrLanguagePacks(listOf(french, english))
        assertTrue(verifier(root).verifies(stored))
        assertFalse(verifier(root).verifies(OcrLanguagePacks(listOf(english.copy(sha256 = french.sha256)))))

        val path = root.resolve("tessdata/eng.traineddata")
        Files.write(path, "HELLO".encodeToByteArray()) // The length is unchanged; only exact hashing can detect this.
        val reopened = verifier(root)
        assertFalse(reopened.verifies(stored))
        assertNull(reopened.resolveOrdered(listOf("eng", "fra")))
        assertEquals(listOf(french), reopened.resolveOrdered(listOf("fra"))?.ordered)
        assertEquals("HELLO", Files.readAllBytes(path).decodeToString())
    }

    @Test fun missingOrNonRegularPacksAreNotTreatedAsInstalled() {
        val root = fixture.root.toPath().resolve("absent")
        val pack = OcrLanguagePacks(listOf(english))
        assertFalse(verifier(root).verifies(pack))
        assertFalse(Files.exists(root))

        val installed = installFixture()
        Files.createDirectory(installed.resolve("tessdata/jpn.traineddata"))
        val bad = OcrLanguagePack("jpn", english.sha256)
        val pinnedDirectory = VerifiedOcrPackDirectory(installed, listOf(VerifiedOcrPackDirectory.Pin(bad, 5)))
        assertFalse(pinnedDirectory.verifies(OcrLanguagePacks(listOf(bad))))
    }

    @Test fun aTruncatedOrExtendedPackCannotResolve() {
        val root = installFixture()
        val path = root.resolve("tessdata/eng.traineddata")
        for (value in listOf("hell", "hello!")) {
            Files.write(path, value.encodeToByteArray())
            assertNull(verifier(root).resolveOrdered(listOf("eng")))
        }
    }

    @Test fun refusesSymbolicLinksAtEachCheckedPathComponent() {
        val installed = installFixture()
        val parent = fixture.newFolder().toPath()
        val pack = OcrLanguagePacks(listOf(english))
        makeLink(parent.resolve("root"), installed)
        assertFalse(verifier(parent.resolve("root")).verifies(pack))

        val root = Files.createDirectory(parent.resolve("data-alias"))
        makeLink(root.resolve("tessdata"), installed.resolve("tessdata"))
        assertFalse(verifier(root).verifies(pack))

        val content = Files.createDirectory(parent.resolve("pack-alias"))
        val data = Files.createDirectory(content.resolve("tessdata"))
        makeLink(data.resolve("eng.traineddata"), installed.resolve("tessdata/eng.traineddata"))
        assertFalse(verifier(content).verifies(pack))
    }

    @Test fun cancellationAtTheFinalCheckpointCannotReturnVerifiedPacks() {
        val directory = verifier(installFixture())
        val pack = OcrLanguagePacks(listOf(english))
        var checks = 0
        assertThrows(CancellationException::class.java) {
            directory.verifies(pack) {
                // One valid short file consumes the pre-directory, pre-file, read and EOF checkpoints.
                if (++checks == 5) throw CancellationException()
            }
        }
    }

    private fun makeLink(path: Path, target: Path) {
        try { Files.createSymbolicLink(path, target) }
        catch (unsupported: UnsupportedOperationException) { assumeNoException(unsupported) }
        catch (unprivileged: IOException) { assumeNoException(unprivileged) }
    }

    private fun installFixture(): Path {
        val root = fixture.newFolder().toPath()
        val data = Files.createDirectory(root.resolve("tessdata"))
        Files.write(data.resolve("eng.traineddata"), "hello".encodeToByteArray())
        Files.write(data.resolve("fra.traineddata"), "world".encodeToByteArray())
        return root
    }

    private fun verifier(root: Path) = VerifiedOcrPackDirectory(root, listOf(
        VerifiedOcrPackDirectory.Pin(english, 5),
        VerifiedOcrPackDirectory.Pin(french, 5),
    ))
}
