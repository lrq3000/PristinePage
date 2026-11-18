package app.pristinepage.image.export

import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.core.image.RgbImage
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.image.EncodedImageFixtures.be
import app.pristinepage.image.EncodedImageFixtures.chunk
import app.pristinepage.image.EncodedImageFixtures.png
import app.pristinepage.image.EncodedImageFixtures.sha
import app.pristinepage.image.decode.ImageAllocationBudget
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Native controls, not an app/storage integration or proof of support on an untested Android build. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28)
class OrdinaryPngExportTest {
    private val ownedDirectory by lazy {
        val files = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        Files.createTempDirectory(files.toPath(), "ordinary-png-oracle-").toFile()
    }

    @Test fun writerRoundTripsExactOpaqueSrgbFromPaddedRenderedPixels() {
        paddedRaster().use { rendered ->
            val file = artifact()
            val writer = OrdinaryPngWriter(ImageAllocationBudget(32L * 1024 * 1024))
            file.outputStream().use { sink -> writer.write(rendered, sink, checkpoint = {}) }
            assertVerified(rendered, file)
        }
    }

    @Test fun independentMatchingRgbPngReturnsDistinctEncodedAndRasterIdentities() {
        // This positive evidence is required before crediting verifier negatives. It does not call the writer.
        val bytes = pngBytes()
        paddedRaster().use { assertVerified(it, artifact(bytes), original = bytes) }
    }

    @Test fun closesNativeReadbackBeforeRejectingDifferentPngBytesWithTheSameExactPixels() {
        val encoded = matchingIdat()
        check(encoded.size >= 3)
        fun split(at: Int) = envelope(
            chunk("IDAT", encoded.copyOfRange(0, at)),
            chunk("IDAT", encoded.copyOfRange(at, encoded.size)),
        )
        // The same zlib stream and chunk count give equal extent; different IDAT boundaries alone change the SHA-256.
        val original = split(1)
        val alternative = split(2)
        assertEquals(original.size, alternative.size)
        assertNotEquals(sha(original), sha(alternative))
        val target = artifact(original)
        val control = artifact(alternative)
        for (file in listOf(target, control)) {
            assertEquals(1, auditEnvelope(readArtifact(file), width, height))
            assertArrayEquals(pixels, decodeOpaque(file, width, height))
        }
        val budget = ImageAllocationBudget(32L * 1024 * 1024)
        val verifier = OrdinaryPngVerifier(budget)
        var sawNativeCharge = false
        var replaced = false
        paddedRaster().use { rendered ->
            val error = assertThrows(RepositoryFailure::class.java) {
                verifier.verifyClosed(target, rendered, maxBytes) {
                    val used = budget.usedBytes
                    if (used >= 1024L * 1024) sawNativeCharge = true
                    else if (sawNativeCharge && used == 0L && !replaced) {
                        // Controlled mutation of the test’s own fixture after the native reservation closes, not a concurrent race claim.
                        target.outputStream().use { it.write(alternative) }
                        replaced = true
                    }
                }
            }.error
            assertTrue("Observed native reservation and replaced the closed artifact", replaced)
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, error.code)
            assertEquals("export_png_byte_identity_mismatch", error.safeMessageKey)
            assertEquals(0L, budget.usedBytes)
            assertArrayEquals(alternative, readArtifact(target))
        }
    }

    @Test fun benignColorDensityAndSuggestedPaletteWithSplitIdatPreserveExactPixels() {
        val idat = matchingIdat()
        val split = idat.size / 2
        val bytes = envelope(
            gamma, chromaticities, srgb, palette, density,
            chunk("IDAT", idat.copyOfRange(0, split)), chunk("IDAT", idat.copyOfRange(split, idat.size)),
        )
        paddedRaster().use { assertVerified(it, artifact(bytes), original = bytes) }
    }

    @Test fun standaloneIdentityExifOnMinimalRgbPreservesExactPixels() {
        val bytes = pngBytes(extra = chunk("eXIf", tiff(1)))
        paddedRaster().use { assertVerified(it, artifact(bytes), original = bytes) }
    }

    @Test fun rgbaRequiresActualOpaqueSamplesEvenWhenRgbIsUnchanged() {
        val opaque = pngBytes(rgba = true)
        paddedRaster().use { assertVerified(it, artifact(opaque), original = opaque) }
        val translucent = pixels.copyOf().apply { this[4] = 0x800E9B63.toInt() }
        val file = artifact(pngBytes(argb = translucent, rgba = true))
        val reopened = readArtifact(file)
        assertEquals(1, auditEnvelope(reopened, width, height))
        assertEquals(6, reopened.u8(25))
        assertArrayEquals(translucent, decodePixels(file, width, height))
        assertRefused(Refusal.ALPHA, file)
    }

    @Test fun refusesFullyDecodedSameSizeWrongPixelsAtPixelComparison() {
        val changed = pixels.copyOf().apply { this[4] = 0xFF0E9A63.toInt() }
        val bytes = pngBytes(argb = changed)
        assertEquals(1, auditEnvelope(bytes, width, height))
        val file = artifact(bytes)
        assertArrayEquals(changed, decodeOpaque(file, width, height))
        assertRefused(Refusal.PIXELS, file)
    }

    @Test fun refusesFullyDecodedWrongShapeAtDimensionCheck() {
        val bytes = pngBytes(w = 2, h = 3)
        assertEquals(1, auditEnvelope(bytes, 2, 3))
        val file = artifact(bytes)
        assertArrayEquals(pixels, decodeOpaque(file, 2, 3))
        assertRefused(Refusal.DIMENSIONS, file)
    }

    @Test fun refusesFullyDecodedNonPngWithMatchingShapeAndPixelsAtFormatCheck() {
        val file = artifact(rgbBmp())
        assertArrayEquals(pixels, decodeOpaque(file, width, height, setOf("image/bmp", "image/x-ms-bmp")))
        assertRefused(Refusal.FORMAT, file)
    }

    @Test fun refusesBadIdatInAReopenedCompleteEnvelopeWithoutAssumingDecodePhase() {
        val bytes = envelope(chunk("IDAT", byteArrayOf(1, 2, 3, 4)))
        val file = artifact(bytes)
        val reopened = readArtifact(file)
        assertArrayEquals(bytes, reopened)
        assertEquals(1, auditEnvelope(reopened, width, height))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), reopened.copyOfRange(41, 45))
        var strictHeaderApplied = false
        val failure = assertThrows(ImageDecoder.DecodeException::class.java) {
            decodeOpaque(file, width, height, onStrictHeader = { strictHeaderApplied = true })
        }
        assertTrue(
            "Expected malformed/incomplete image data, not a source I/O error (decoder code ${failure.error})",
            failure.error == ImageDecoder.DecodeException.SOURCE_MALFORMED_DATA ||
                failure.error == ImageDecoder.DecodeException.SOURCE_INCOMPLETE,
        )
        Log.i("OrdinaryPngOracle", "corrupt IDAT sha256=${sha(reopened)} strictHeaderApplied=$strictHeaderApplied")
        // When setup did not finish, this is only a malformed-content witness, not pixel-stage evidence.
        assertRefused(if (strictHeaderApplied) Refusal.DECODE_DATA else Refusal.DECODE_HEADER_DATA, file)
    }

    @Test fun refusesTruncatedTerminalChunkDespiteUnchangedIdat() {
        val complete = pngBytes()
        assertEquals(1, auditEnvelope(complete, width, height))
        val iend = chunk("IEND", byteArrayOf())
        assertArrayEquals(iend, complete.copyOfRange(complete.size - iend.size, complete.size))
        for (removed in listOf(1, iend.size)) {
            assertRefused(Refusal.TERMINAL, artifact(complete.copyOf(complete.size - removed)), "terminal minus $removed bytes")
        }
    }

    @Test fun refusesOutOfProfileIhdrMethodsEvenWithCorrectCrcs() {
        val data = chunk("IDAT", matchingIdat())
        for ((offset, value) in listOf(10 to 1, 11 to 1, 12 to 2, 12 to 1)) {
            val header = ihdr().also { it[offset] = value.toByte() }
            assertRefused(Refusal.METHODS, artifact(envelope(data, header = header)), "IHDR byte $offset = $value")
        }
    }

    @Test fun refusesDuplicateOrMisplacedKnownChunks() {
        val data = chunk("IDAT", matchingIdat())
        for ((kind, field) in listOf("sRGB" to srgb, "gAMA" to gamma, "cHRM" to chromaticities, "PLTE" to palette, "pHYs" to density, "eXIf" to chunk("eXIf", tiff(1)))) {
            assertRefused(Refusal.CHUNK_ORDER, artifact(envelope(field, field, data)), "duplicate $kind")
            assertRefused(Refusal.CHUNK_ORDER, artifact(envelope(data, field)), "$kind after IDAT")
        }
        for ((kind, field) in listOf("sRGB" to srgb, "gAMA" to gamma, "cHRM" to chromaticities)) {
            assertRefused(Refusal.CHUNK_ORDER, artifact(envelope(palette, field, data)), "$kind after PLTE")
        }
        val idat = matchingIdat()
        val split = idat.size / 2
        // This violates both the pHYs placement and IDAT contiguity rules; it does not isolate the latter from the former.
        assertRefused(
            Refusal.CHUNK_ORDER,
            artifact(envelope(chunk("IDAT", idat.copyOfRange(0, split)), density, chunk("IDAT", idat.copyOfRange(split, idat.size)))),
            "interrupted IDAT run",
        )
    }

    @Test fun refusesEncodedPrivateMetadataEvenWhenAllDecodedPixelsMatch() {
        val data = chunk("IDAT", matchingIdat())
        val text = "Comment\u0000Synthetic private comment".encodeToByteArray()
        for ((kind, bytes) in listOf(
            "tEXt before IDAT" to envelope(chunk("tEXt", text), data),
            "orientation 1 plus a private TIFF field" to envelope(chunk("eXIf", tiff(1, withDescription = true)), data),
        )) {
            val file = artifact(bytes)
            assertArrayEquals(pixels, decodeOpaque(file, width, height))
            assertRefused(Refusal.METADATA, file, kind)
        }
    }

    @Test fun refusesEncodedNonIdentityOrientationEvenWhenDecodedPixelsMatch() {
        val tag = chunk("eXIf", tiff(2)) // Horizontal reflection; encoded and decoded shape are both 3×2.
        val ordinaryOrder = pngBytes(extra = tag)
        val ordinaryFile = artifact(ordinaryOrder)
        assertEquals(2, auditEnvelope(ordinaryOrder, width, height))
        val ordinaryDecode = decodeOpaque(ordinaryFile, width, height)

        // If the decoder honors PNG eXIf, reverse the pixels within each encoded row so the decoded appearance matches. If it ignores eXIf, ordinary encoded pixels already match.
        val appliesExif = !ordinaryDecode.contentEquals(pixels)
        val matchingAppearance = if (appliesExif) {
            assertArrayEquals("Only the known horizontal reflection is admissible", reflected, ordinaryDecode)
            artifact(pngBytes(argb = reflected, extra = tag))
        } else ordinaryFile
        val encoded = readArtifact(matchingAppearance)
        assertEquals(2, auditEnvelope(encoded, width, height))
        assertArrayEquals(pixels, decodeOpaque(matchingAppearance, width, height))
        Log.i("OrdinaryPngOracle", "PNG eXIf=2 on matching-appearance fixture; decoder reflected=$appliesExif, sha256=${sha(encoded)}")
        assertRefused(Refusal.METADATA, matchingAppearance)
    }

    @Test fun missingClosedArtifactIsStorageFailureNotFormatFailure() {
        val file = File(ownedDirectory, "not-created.bin")
        assertFalse(file.exists())
        assertRefused(Refusal.STORAGE, file)
    }

    @Test fun sharedPressureRefusesNativePhasesWithoutTakingCallerOwnership() {
        val budget = ImageAllocationBudget(32L * 1024 * 1024)
        val bytes = pngBytes()
        val file = artifact(bytes)
        val verifier = OrdinaryPngVerifier(budget)
        val writer = OrdinaryPngWriter(budget)
        paddedRaster().use { rendered ->
            val sink = BorrowedSink()
            // Leave enough for the bounded gate but not the provisional native phase.
            val held = budget.capacityBytes - 256 * 1024
            budget.reserve(held).use {
                assertAllocationRefused { verifier.verifyClosed(file, rendered, maxBytes) {} }
                assertAllocationRefused { writer.write(rendered, sink) {} }
                assertEquals(held, budget.usedBytes)
            }
            assertEquals(0, sink.written)
            assertFalse(sink.closed)
            assertEquals(1, rendered.getUnsigned(0, 0, 0))
            assertArrayEquals(bytes, readArtifact(file))
            assertEquals(0L, budget.usedBytes)
        }
    }

    @Test fun preservesSinkIoAndCancellationEvenAcrossPlatformCompression() {
        val budget = ImageAllocationBudget(32L * 1024 * 1024)
        val writer = OrdinaryPngWriter(budget)
        paddedRaster().use { rendered ->
            val cap = IOException("synthetic owner byte-cap sentinel")
            val canceled = CancellationException("synthetic owner cancellation")
            for (refusal in listOf(cap, canceled)) {
                val sink = BorrowedSink(refusal)
                assertSame(refusal, assertThrows(refusal.javaClass) { writer.write(rendered, sink) {} })
                assertTrue(sink.attempted)
                assertFalse(sink.closed)
                assertEquals(0L, budget.usedBytes)
                assertEquals(1, rendered.getUnsigned(0, 0, 0))
            }
        }
    }

    private fun verifier() = OrdinaryPngVerifier(ImageAllocationBudget(32L * 1024 * 1024))

    private fun assertVerified(rendered: RgbImage, file: File, original: ByteArray? = null) {
        val before = readArtifact(file)
        if (original != null) assertArrayEquals("Closed control equals the constructed bytes", original, before)
        assertEquals("Effective encoded orientation", 1, auditEnvelope(before, width, height))
        if (original == null) Log.i("OrdinaryPngOracle", "writer type=${before.u8(25)} chunks=${chunkInventory(before)} sha256=${sha(before)}")
        assertArrayEquals(pixels, decodeOpaque(file, width, height))
        val receipt = verifier().verifyClosed(file, rendered, maxBytes, checkpoint = {})
        val after = readArtifact(file)
        assertArrayEquals("Verifier must leave the closed bytes unchanged", before, after)
        assertEquals("Byte length of the independently reopened artifact", after.size.toLong(), receipt.encoded.byteLength)
        assertEquals("SHA-256 of the independently reopened artifact", sha(after), receipt.encoded.sha256)
        assertEquals("Domain-separated SHA-256 of the literal active RGB", rasterSha256, receipt.rasterSha256)
        assertNotEquals("Encoded bytes are not the canonical RGB stream", receipt.encoded.sha256, receipt.rasterSha256)
    }

    // Assert the owning boundary: an earlier unrelated refusal cannot satisfy a later-stage witness.
    private enum class Refusal(val code: OperationErrorCode, val key: String) {
        FORMAT(OperationErrorCode.UNSUPPORTED_FORMAT, "export_png_format"),
        DIMENSIONS(OperationErrorCode.INTEGRITY_FAILED, "export_png_dimensions"),
        PIXELS(OperationErrorCode.INTEGRITY_FAILED, "export_png_pixels_mismatch"),
        DECODE_DATA(OperationErrorCode.INTEGRITY_FAILED, "export_png_decode_data"),
        DECODE_HEADER_DATA(OperationErrorCode.INTEGRITY_FAILED, "export_png_decode_header_data"),
        TERMINAL(OperationErrorCode.INTEGRITY_FAILED, "export_png_structure"),
        METHODS(OperationErrorCode.UNSUPPORTED_FORMAT, "export_png_methods"),
        CHUNK_ORDER(OperationErrorCode.INTEGRITY_FAILED, "export_png_structure"),
        METADATA(OperationErrorCode.UNSUPPORTED_FORMAT, "export_png_metadata"),
        ALPHA(OperationErrorCode.UNSUPPORTED_FORMAT, "export_png_alpha"),
        STORAGE(OperationErrorCode.STORAGE_FAILED, "export_png_read_failed"),
    }

    private fun assertRefused(expected: Refusal, file: File, label: String = expected.name) {
        paddedRaster().use { rendered ->
            val verifier = verifier()
            val error = assertThrows(label, RepositoryFailure::class.java) {
                verifier.verifyClosed(file, rendered, maxBytes, checkpoint = {})
            }.error
            assertEquals(label, expected.code, error.code)
            assertEquals(label, expected.key, error.safeMessageKey)
        }
    }

    private fun assertAllocationRefused(call: () -> Unit) {
        val error = assertThrows(RepositoryFailure::class.java) { call() }.error
        assertEquals(OperationErrorCode.RESOURCE_LIMIT, error.code)
        assertEquals("image_allocation_limit", error.safeMessageKey)
    }

    private class BorrowedSink(private val refusal: Exception? = null) : OutputStream() {
        var attempted = false
        var written = 0
        var closed = false
        override fun write(b: Int) = accept(1)
        override fun write(b: ByteArray, off: Int, len: Int) = accept(len)
        private fun accept(count: Int) {
            attempted = true
            refusal?.let { throw it }
            written += count
        }
        override fun close() { closed = true }
    }

    private fun artifact(bytes: ByteArray? = null): File =
        File.createTempFile("page-", ".bin", ownedDirectory).also { file -> if (bytes != null) file.writeBytes(bytes) }

    private fun readArtifact(file: File): ByteArray {
        val size = file.length()
        check(size in 8L..maxBytes) { "Unexpected extent for this bounded synthetic artifact" }
        return DataInputStream(file.inputStream()).use { input ->
            ByteArray(size.toInt()).also { input.readFully(it); check(input.read() == -1) { "Closed fixture extent changed" } }
        }
    }

    private fun decodeOpaque(file: File, w: Int, h: Int, mimes: Set<String> = setOf("image/png"), onStrictHeader: () -> Unit = {}): IntArray =
        decodePixels(file, w, h, mimes, onStrictHeader).also { argb ->
            assertTrue("Every pixel must be opaque", argb.all { Color.alpha(it) == 255 })
        }

    private fun decodePixels(file: File, w: Int, h: Int, mimes: Set<String> = setOf("image/png"), onStrictHeader: () -> Unit = {}): IntArray {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            assertTrue("Actual MIME ${info.mimeType}, expected $mimes", info.mimeType in mimes)
            assertFalse(info.isAnimated)
            assertEquals(w, info.size.width)
            assertEquals(h, info.size.height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isUnpremultipliedRequired = true
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            decoder.setOnPartialImageListener { false }
            onStrictHeader()
        }
        try {
            assertEquals(w, bitmap.width)
            assertEquals(h, bitmap.height)
            assertTrue("Decoded pixels must be sRGB", bitmap.colorSpace?.isSrgb == true)
            return IntArray(w * h).also { argb ->
                bitmap.getPixels(argb, 0, w, 0, 0, w, h)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun pngBytes(w: Int = width, h: Int = height, argb: IntArray = pixels, extra: ByteArray = byteArrayOf(), rgba: Boolean = false): ByteArray {
        check(argb.size == w * h)
        val channels = if (rgba) 4 else 3
        val stride = 1 + channels * w
        val scanlines = ByteArray(h * stride) // Each leading zero is a PNG None filter byte, never raster padding.
        for ((i, pixel) in argb.withIndex()) {
            val at = i / w * stride + 1 + i % w * channels
            putRgb(scanlines, at, pixel)
            if (rgba) scanlines[at + 3] = Color.alpha(pixel).toByte()
        }
        return png(w, h, 8, if (rgba) 6 else 2, scanlines, extra)
    }

    private fun paddedRaster(): RgbImage {
        val stride = 11
        val bytes = ByteArray(stride * height) { 0xA5.toByte() }
        for ((i, pixel) in pixels.withIndex()) putRgb(bytes, i / width * stride + i % width * 3, pixel)
        return RgbImage(width, height, stride, bytes)
    }

    private fun putRgb(bytes: ByteArray, at: Int, argb: Int) {
        bytes[at] = Color.red(argb).toByte()
        bytes[at + 1] = Color.green(argb).toByte()
        bytes[at + 2] = Color.blue(argb).toByte()
    }

    // 24-bit BI_RGB BMP with a 14-byte file header, 40-byte DIB, two bottom-up 12-byte BGR rows. No non-PNG writer or acquired sample is needed.
    private fun rgbBmp(): ByteArray = ByteBuffer.allocate(78).order(ByteOrder.LITTLE_ENDIAN).apply {
        putShort(0x4D42).putInt(78).putInt(0).putInt(54)
        putInt(40).putInt(3).putInt(2).putShort(1).putShort(24).putInt(0).putInt(24)
        repeat(4) { putInt(0) }
        for (y in 1 downTo 0) {
            for (x in 0..2) {
                val p = pixels[y * 3 + x]
                put(Color.blue(p).toByte()).put(Color.green(p).toByte()).put(Color.red(p).toByte())
            }
            put(byteArrayOf(0, 0, 0))
        }
    }.array()

    private fun ihdr() = be(width) + be(height) + byteArrayOf(8, 2, 0, 0, 0)

    private fun envelope(vararg chunks: ByteArray, header: ByteArray = ihdr()): ByteArray =
        chunks.fold(signature + chunk("IHDR", header)) { bytes, field -> bytes + field } + chunk("IEND", byteArrayOf())

    private fun matchingIdat(): ByteArray {
        val simple = pngBytes() // Shared fixture builder with no ancillary chunks has exactly IHDR, IDAT and IEND.
        val start = 8 + 12 + 13 // Signature plus complete IHDR.
        check(simple.ascii(start + 4) == "IDAT")
        val payload = start + 8
        return simple.copyOfRange(payload, payload + simple.u32(start).toInt())
    }

    // A tiny TIFF, not the JPEG APP1 wrapper. Optional ImageDescription is an inline four-byte ASCII value, so no unsafe offset or large count is introduced.
    private fun tiff(orientation: Int, withDescription: Boolean = false): ByteArray =
        ByteBuffer.allocate(if (withDescription) 38 else 26).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x4949).putShort(42).putInt(8).putShort((if (withDescription) 2 else 1).toShort())
            if (withDescription) putShort(0x010E).putShort(2).putInt(4).put("QA!\u0000".encodeToByteArray())
            putShort(0x0112).putShort(3).putInt(1).putShort(orientation.toShort()).putShort(0).putInt(0)
        }.array()

    // Independent test-side audit of this tiny profile, not a general PNG parser or a replacement for strict full pixel decode.
    private fun auditEnvelope(bytes: ByteArray, w: Int, h: Int): Int {
        check(bytes.size.toLong() in 8L..maxBytes && bytes.copyOfRange(0, 8).contentEquals(signature))
        var at = 8
        var orientation = 1
        var data = false
        val singleton = mutableSetOf<String>()
        while (at < bytes.size) {
            check(bytes.size - at >= 12) { "Incomplete PNG chunk" }
            val count = bytes.u32(at).also { check(it <= bytes.size - at - 12L) }.toInt()
            val payload = at + 8
            val kind = bytes.ascii(at + 4)
            check(CRC32().apply { update(bytes, at + 4, count + 4) }.value == bytes.u32(payload + count)) { "Bad $kind CRC" }
            check(at != 8 || kind == "IHDR") { "Expected leading IHDR" }
            if (kind != "IDAT") check(singleton.add(kind)) { "Duplicate $kind" }
            if (data) check(kind == "IDAT" || kind == "IEND") { "Misplaced $kind or interrupted IDAT" }
            when (kind) {
                "IHDR" -> {
                    check(count == 13 && at == 8)
                    assertEquals(w.toLong(), bytes.u32(payload))
                    assertEquals(h.toLong(), bytes.u32(payload + 4))
                    check(bytes.u8(payload + 8) == 8 && bytes.u8(payload + 9) in listOf(2, 6))
                    check((10..12).all { bytes.u8(payload + it) == 0 }) { "Expected compression/filter/interlace 0/0/0" }
                }
                "IDAT" -> data = true
                "IEND" -> {
                    check(data && count == 0 && payload + 4 == bytes.size) { "Expected terminal IEND at EOF" }
                    return orientation
                }
                "sRGB" -> check("PLTE" !in singleton && count == 1 && bytes.u8(payload) in 0..3)
                "gAMA" -> check("PLTE" !in singleton && count == 4 && bytes.u32(payload) == 45455L)
                "cHRM" -> check("PLTE" !in singleton && count == 32 && srgbChroma.indices.all { bytes.u32(payload + 4 * it) == srgbChroma[it].toLong() })
                "PLTE" -> check(count in 3..768 && count % 3 == 0) // At most a suggested palette for direct RGB/RGBA.
                "pHYs" -> check(count == 9 && bytes.u8(payload + 8) in 0..1)
                "eXIf" -> orientation = orientationOnly(bytes, payload, count)
                else -> error("Forbidden or unreviewed PNG field $kind")
            }
            at = payload + count + 4
        }
        error("Missing IEND")
    }

    private fun orientationOnly(bytes: ByteArray, at: Int, count: Int): Int {
        check(count == 26) { "Only an orientation-only TIFF is in profile" }
        val tiff = ByteBuffer.wrap(bytes, at, count).slice()
        tiff.order(when (tiff.getShort(0).toInt()) {
            0x4949 -> ByteOrder.LITTLE_ENDIAN
            0x4D4D -> ByteOrder.BIG_ENDIAN
            else -> error("Invalid TIFF endian marker")
        })
        check(tiff.getShort(2).toInt() == 42 && tiff.getInt(4) == 8 && tiff.getShort(8).toInt() == 1)
        check(tiff.getShort(10).toInt() == 0x0112 && tiff.getShort(12).toInt() == 3 && tiff.getInt(14) == 1)
        check(tiff.getShort(20).toInt() == 0 && tiff.getInt(22) == 0)
        return tiff.getShort(18).toInt().also { check(it in 1..8) }
    }

    private fun ByteArray.u8(at: Int) = this[at].toInt() and 255
    private fun ByteArray.u32(at: Int) =
        (u8(at).toLong() shl 24) or (u8(at + 1).toLong() shl 16) or (u8(at + 2).toLong() shl 8) or u8(at + 3).toLong()

    private fun ByteArray.ascii(at: Int) = String(this, at, 4, Charsets.US_ASCII)

    // Only called after the independent audit has checked the bounded offsets and recognized every field.
    private fun chunkInventory(bytes: ByteArray): String = buildList {
        var at = signature.size
        while (at < bytes.size) {
            val length = bytes.u32(at).toInt()
            add("${bytes.ascii(at + 4)}:$length")
            at += 12 + length
        }
    }.joinToString(",")

    private companion object {
        const val width = 3
        const val height = 2
        const val maxBytes = 64L * 1024
        // Independent SHA-256 over the v1 RGB domain, NUL, BE 3×2 dimensions and 0123fe80ff00c90781fa40130e9b634d0288.
        const val rasterSha256 = "9434457103f2fbf1dd09735edfea2d591e550254ebca19429aa6b1af9224ff83"
        val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        val pixels = intArrayOf(0xFF0123FE.toInt(), 0xFF80FF00.toInt(), 0xFFC90781.toInt(), 0xFFFA4013.toInt(), 0xFF0E9B63.toInt(), 0xFF4D0288.toInt())
        val reflected = intArrayOf(0xFFC90781.toInt(), 0xFF80FF00.toInt(), 0xFF0123FE.toInt(), 0xFF4D0288.toInt(), 0xFF0E9B63.toInt(), 0xFFFA4013.toInt())
        val srgbChroma = intArrayOf(31270, 32900, 64000, 33000, 30000, 60000, 15000, 6000)
        val chromaticities = chunk("cHRM", srgbChroma.fold(byteArrayOf()) { bytes, n -> bytes + be(n) })
        val gamma = chunk("gAMA", be(45455))
        val srgb = chunk("sRGB", byteArrayOf(0))
        val palette = chunk("PLTE", byteArrayOf(0, 0, 0))
        val density = chunk("pHYs", be(3780) + be(3780) + byteArrayOf(1))
    }
}
