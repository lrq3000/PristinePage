package app.pristinepage.image.ocr

import app.pristinepage.core.ocr.OcrLanguagePack
import app.pristinepage.core.ocr.OcrLanguagePacks
import java.io.ByteArrayInputStream
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrivateOcrPackOwnerTest {
    @get:Rule val fixture = TemporaryFolder()
    private val english = OcrLanguagePack("eng", "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824")
    private val french = OcrLanguagePack("fra", "486ea46224d1bb4fb680f34f7c9ad96a8f24ec88be73ea8e5a6c65260e9cb8a7")
    private val chosen = OcrLanguagePacks(listOf(french, english))

    @Test fun publishesOnlyTheCompleteSetAndResolvesTheOriginalOrderOnReopen() {
        val root = fixture.newFolder().toPath()
        val pending = owner(root)
        assertNull(pending.resolveOrdered(chosen.ordered.map { it.code }))
        assertTrue(pending.install { code ->
            assertNull(owner(root).acquire(chosen))
            pack(code)
        })
        val reopened = owner(root)
        assertEquals(chosen, reopened.resolveOrdered(listOf("fra", "eng")))
        val ready = checkNotNull(reopened.acquire(chosen))
        assertTrue(ready.startsWith(root) && ready != root)
        assertEquals("hello", Files.readAllBytes(ready.resolve("tessdata/eng.traineddata")).decodeToString())
        assertTrue(reopened.install { error("Verified files must not be redeposited") })
    }

    @Test fun failedOrCanceledStagingDoesNotPublishOrReplaceAValidSet() {
        val root = fixture.newFolder().toPath()
        val owner = owner(root)
        assertFalse(owner.install { code -> if (code == "fra") ByteArrayInputStream("WORLD".encodeToByteArray()) else pack(code) })
        assertNull(owner.acquire(chosen))
        assertThrows(CancellationException::class.java) {
            owner.install { code -> if (code == "fra") throw CancellationException() else pack(code) }
        }
        assertNull(owner(root).acquire(chosen))
        assertTrue(owner.install { pack(it) })
        val ready = checkNotNull(owner.acquire(chosen))
        val damaged = ready.resolve("tessdata/fra.traineddata")
        Files.write(damaged, "WORLD".encodeToByteArray())
        assertFalse(owner(root).install { error("Do not repair or replace an existing set") })
        assertEquals("WORLD", Files.readAllBytes(damaged).decodeToString())
        assertNull(owner.acquire(chosen))
    }

    @Test fun exactHashIsNotSufficientWhenTheNativeLeafFailsOwnershipAdmission() {
        val root = fixture.newFolder().toPath()
        val first = owner(root)
        assertTrue(first.install { pack(it) })
        val ready = checkNotNull(first.acquire(chosen))
        val refused = owner(root) { leaf -> leaf != ready.resolve("tessdata/fra.traineddata") }
        assertNull(refused.acquire(chosen))
        assertNull(refused.resolveOrdered(listOf("fra", "eng")))
        assertFalse(refused.install { error("Do not replace an unowned published file") })
        assertEquals("world", Files.readAllBytes(ready.resolve("tessdata/fra.traineddata")).decodeToString())
    }

    @Test fun borrowedDirectoryMustNotContainACorruptUnselectedPack() {
        val root = fixture.newFolder().toPath()
        val owner = owner(root)
        assertTrue(owner.install { pack(it) })
        val englishOnly = OcrLanguagePacks(listOf(english))
        val ready = checkNotNull(owner.acquire(englishOnly))
        Files.write(ready.resolve("tessdata/fra.traineddata"), "WORLD".encodeToByteArray())
        assertNull(owner.acquire(englishOnly))
        assertNull(owner.resolveOrdered(listOf("eng")))
    }

    @Test fun resolvingAnOrderedSubsetDoesNotTraverseItsPackBytesTwice() {
        val owner = owner(fixture.newFolder().toPath())
        assertTrue(owner.install { pack(it) })
        val englishOnly = OcrLanguagePacks(listOf(english))
        var acquisitionChecks = 0
        assertNotNull(owner.acquire(englishOnly) { acquisitionChecks++ })
        var resolutionChecks = 0
        val resolved = owner.resolveOrdered(listOf("eng")) { resolutionChecks++ }
        assertEquals(englishOnly, resolved)
        // Both paths must authenticate the same two files once; the same real filesystem and checkpoint surround both scans.
        assertEquals(acquisitionChecks, resolutionChecks)
    }

    @Test fun callersCannotSupplyATrustedDirectoryOrOwnershipPredicate() {
        assertTrue(PrivateOcrPackOwner::class.java.declaredConstructors.all { it.isSynthetic || Modifier.isPrivate(it.modifiers) })
    }

    private fun owner(root: Path, owned: (Path) -> Boolean = { true }): PrivateOcrPackOwner {
        val constructor = PrivateOcrPackOwner::class.java.declaredConstructors.single { !it.isSynthetic }
        constructor.isAccessible = true
        val directories: (Path) -> Boolean = { it.startsWith(root) }
        return constructor.newInstance(root, listOf(
            VerifiedOcrPackDirectory.Pin(english, 5),
            VerifiedOcrPackDirectory.Pin(french, 5),
        ), owned, directories) as PrivateOcrPackOwner
    }

    private fun pack(code: String) = ByteArrayInputStream(when (code) {
        "eng" -> "hello"
        "fra" -> "world"
        else -> error("Unknown test code")
    }.encodeToByteArray())
}
