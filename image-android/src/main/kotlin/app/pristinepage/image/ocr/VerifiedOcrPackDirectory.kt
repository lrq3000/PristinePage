package app.pristinepage.image.ocr

import app.pristinepage.core.ocr.OcrLanguagePack
import app.pristinepage.core.ocr.OcrLanguagePacks
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Read-only check of an owner-supplied private directory. The owner must supply a stable ancestor chain and private files with no uncontrolled hard links or concurrent writers, and retain that ownership through native close. A pathname check alone is not a lease. */
internal class VerifiedOcrPackDirectory(root: Path, pins: List<Pin>) {
    data class Pin(val identity: OcrLanguagePack, val bytes: Long) {
        init { require(bytes in 1..MAX_PACK_BYTES) }
    }

    private val root = root.toAbsolutePath().normalize()
    private val data = this.root.resolve("tessdata")
    private val byCode = pins.associateBy { it.identity.code }.also {
        require(pins.size in 1..MAX_CATALOG_PACKS && it.size == pins.size)
    }

    fun resolveOrdered(codes: List<String>, checkpoint: () -> Unit = {}): OcrLanguagePacks? {
        val selected = orderedIdentities(codes) ?: return null
        return selected.takeIf { verifies(it, checkpoint) }
    }

    /** Descriptor lookup only; the owner must authenticate the installed catalog before returning these identities as usable. */
    fun orderedIdentities(codes: List<String>): OcrLanguagePacks? {
        val selected = ArrayList<OcrLanguagePack>()
        val seen = HashSet<String>()
        for (code in codes) {
            if (selected.size == MAX_JOB_PACKS || !seen.add(code)) return null
            selected.add((byCode[code] ?: return null).identity)
        }
        if (selected.isEmpty()) return null
        return OcrLanguagePacks(selected)
    }

    /** Check the original job's pack identities first, never substituting the catalog's current digest. */
    fun verifies(packs: OcrLanguagePacks, checkpoint: () -> Unit = {}): Boolean {
        val selected = packs.ordered.map { requested ->
            (byCode[requested.code] ?: return false).takeIf { it.identity == requested } ?: return false
        }
        return verifiesPins(selected, checkpoint)
    }

    fun verifiesCatalog(checkpoint: () -> Unit): Boolean = verifiesPins(byCode.values, checkpoint)

    private fun verifiesPins(selected: Collection<Pin>, checkpoint: () -> Unit): Boolean {
        checkpoint()
        if (!Files.isDirectory(root, NOFOLLOW_LINKS) || !Files.isDirectory(data, NOFOLLOW_LINKS)) return false
        val buffer = ByteArray(64 * 1024)
        val digest = MessageDigest.getInstance("SHA-256")
        val verified = try { selected.all { verify(it, digest, buffer, checkpoint) } }
        catch (_: IOException) { false }
        checkpoint()
        return verified
    }

    private fun verify(pin: Pin, digest: MessageDigest, buffer: ByteArray, checkpoint: () -> Unit): Boolean {
        checkpoint()
        // A filename comes from the admitted catalog, not from caller-controlled configuration strings.
        val path = data.resolve("${pin.identity.code}.traineddata")
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!attributes.isRegularFile || attributes.size() != pin.bytes) return false
        return Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            var remaining = pin.bytes
            while (remaining > 0) {
                checkpoint()
                val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                if (count <= 0) return@use false
                digest.update(buffer, 0, count)
                remaining -= count
            }
            checkpoint()
            // The extent check is repeated on the stream; metadata alone says nothing about what is actually read.
            input.read() == -1 && digest.digest().toHexString() == pin.identity.sha256
        }
    }

    companion object {
        internal const val MAX_CATALOG_PACKS = 128
        private const val MAX_JOB_PACKS = 32
        private const val MAX_PACK_BYTES = 32L * 1024 * 1024
    }
}
