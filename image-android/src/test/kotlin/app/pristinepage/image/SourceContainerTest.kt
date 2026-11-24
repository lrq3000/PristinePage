package app.pristinepage.image

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.image.decode.SourceContainer
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.JpegSampling
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.image.export.PlatformJpegStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.LocalDate
import java.util.zip.CRC32
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

/** Actual container bytes, not a fake decoder. Small synthetic structure vectors intentionally do not establish entropy/pixel decode acceptance. Retain isolated temporary files for manual cleanup. */
class SourceContainerTest {
    private fun read(bytes: ByteArray): Any {
        val cls = try { Class.forName("app.pristinepage.image.decode.SourceContainer") }
        catch (_: ClassNotFoundException) { throw AssertionError("Bounded actual-content inspection is absent") }
        val root = File(System.getProperty("user.dir"), "build/test-source-containers").apply { mkdirs() }
        val file = Files.createTempFile(root.toPath(), "synthetic-", ".not-an-image-extension").toFile().apply { writeBytes(bytes) }
        return try { cls.methods.single { it.name == "read" }.invoke(cls.getField("INSTANCE").get(null), file, {})!! }
        catch (failure: InvocationTargetException) { throw failure.targetException }
    }
    private fun get(value: Any, name: String): Any = value.javaClass.methods.single { it.name == name }.invoke(value)!!
    private fun reject(code: OperationErrorCode, bytes: ByteArray) {
        val error = try { read(bytes); throw AssertionError("Unsafe encoded container was accepted") } catch (failure: RepositoryFailure) { failure.error }
        assertEquals(code, error.code)
    }
    private fun be(vararg values: Int): ByteArray = ByteArrayOutputStream().also { b -> DataOutputStream(b).use { d -> values.forEach(d::writeInt) } }.toByteArray()
    private fun chunk(kind: String, data: ByteArray): ByteArray {
        val body = kind.toByteArray(Charsets.US_ASCII) + data; val crc = CRC32().apply { update(body) }
        return be(data.size) + body + be(crc.value.toInt())
    }
    private fun png(w: Int = 3, h: Int = 2, extra: ByteArray = byteArrayOf(), depth: Int = 8, color: Int = 2): ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) + chunk("IHDR", be(w, h) + byteArrayOf(depth.toByte(), color.toByte(), 0, 0, 0)) + extra + chunk("IDAT", byteArrayOf(0)) + chunk("IEND", byteArrayOf())
    private fun segment(marker: Int, data: ByteArray): ByteArray { val n = data.size + 2; return byteArrayOf(-1, marker.toByte(), (n ushr 8).toByte(), n.toByte()) + data }
    private fun jpeg(components: Int = 3, extra: ByteArray = byteArrayOf(), ySampling: Int = 0x11): ByteArray {
        val sof = byteArrayOf(8, 0, 2, 0, 3, components.toByte()) + ByteArray(components * 3) {
            if (it % 3 == 0) (it / 3 + 1).toByte() else if (it == 1) ySampling.toByte() else 0x11
        }
        return byteArrayOf(-1, -40) + extra + segment(0xc0, sof) + segment(0xda, byteArrayOf(1, 1, 0, 0, 63, 0)) + byteArrayOf(1, 2, 3, -1, 0, 4, -1, -39)
    }

    @Test fun exportJpegAcceptsDeclaredSamplingButRejectsCommentsAndWrongGeometry() {
        val root = File("build/test-source-containers").apply { mkdirs() }
        val file = Files.createTempFile(root.toPath(), "export-jpeg-", ".jpg").toFile()
        fun verify(sampling: JpegSampling, size: PixelSize = PixelSize(3, 2)) =
            SourceContainer.verifyExportJpeg(file, size, sampling) {}
        file.writeBytes(jpeg(ySampling = 0x22))
        verify(JpegSampling.YUV420)
        assertThrows(RepositoryFailure::class.java) { verify(JpegSampling.YUV444) }
        assertThrows(RepositoryFailure::class.java) { verify(JpegSampling.YUV420, PixelSize(2, 3)) }
        file.writeBytes(jpeg(extra = segment(0xfe, "Private label".encodeToByteArray()), ySampling = 0x22))
        assertThrows(RepositoryFailure::class.java) { verify(JpegSampling.YUV420) }
        file.writeBytes(jpeg(extra = segment(0xe0, "JFIF\u0000".encodeToByteArray() + byteArrayOf(1, 1, 0, 0, 1, 0, 1, 0, 0)), ySampling = 0x22))
        verify(JpegSampling.YUV420)
        file.writeBytes(jpeg(extra = segment(0xe0, "JFIF\u0000".encodeToByteArray() + byteArrayOf(1, 1, 0, 0, 1, 0, 1, 1, 1)), ySampling = 0x22))
        assertThrows(RepositoryFailure::class.java) { verify(JpegSampling.YUV420) }
    }

    @Test fun platformSrgbIccIsRemovedBeforeStrictExportReadbackWithoutChangingCompressedBytes() {
        val jfif = segment(0xe0, "JFIF\u0000".encodeToByteArray() + byteArrayOf(1, 1, 0, 0, 1, 0, 1, 0, 0))
        val icc = iccSegment(matrixSrgbProfile())
        val original = jpeg(extra = jfif + icc, ySampling = 0x22)
        val expected = jpeg(extra = jfif, ySampling = 0x22)
        val root = File("build/test-source-containers").apply { mkdirs() }
        val file = Files.createTempFile(root.toPath(), "platform-jpeg-", ".jpg").toFile()
        file.writeBytes(original)
        assertEquals(OperationErrorCode.UNSUPPORTED_FORMAT, assertThrows(RepositoryFailure::class.java) {
            SourceContainer.verifyExportJpeg(file, PixelSize(3, 2), JpegSampling.YUV420) {}
        }.error.code)
        for (chunk in listOf(1, 3, 8, original.size)) {
            val output = ByteArrayOutputStream()
            val stream = PlatformJpegStream(output)
            for (offset in original.indices step chunk) stream.write(original, offset, minOf(chunk, original.size - offset))
            stream.finish()
            assertArrayEquals(expected, output.toByteArray())
            file.writeBytes(output.toByteArray())
            SourceContainer.verifyExportJpeg(file, PixelSize(3, 2), JpegSampling.YUV420) {}
        }
        assertThrows(RepositoryFailure::class.java) {
            PlatformJpegStream(ByteArrayOutputStream()).also { it.write(original.copyOfRange(0, 30)); it.finish() }
        }
        val foreign = jpeg(extra = jfif + segment(0xe2, "MPF\u0000".encodeToByteArray()), ySampling = 0x22)
        assertThrows(RepositoryFailure::class.java) {
            PlatformJpegStream(ByteArrayOutputStream()).also { it.write(foreign); it.finish() }
        }
    }

    @Test fun encoderIccSignatureAloneCannotDiscardDifferentColorOrMalformedProfiles() {
        val valid = matrixSrgbProfile()
        val wrongMatrix = matrixSrgbProfile(redX = .51)
        val wrongCurve = matrixSrgbProfile(gamma = 2.2)
        val unsupported = matrixSrgbProfile(lastTag = "A2B0")
        val badOffset = valid.copyOf().also { ByteBuffer.wrap(it).putInt(136, Int.MAX_VALUE) }
        for (extra in listOf(iccSegment(byteArrayOf(1, 2, 3)), iccSegment(wrongMatrix), iccSegment(wrongCurve),
            iccSegment(unsupported), iccSegment(badOffset), iccSegment(valid, 2), iccSegment(valid) + iccSegment(valid))) {
            val failure = assertThrows(RepositoryFailure::class.java) {
                PlatformJpegStream(ByteArrayOutputStream()).also { it.write(jpeg(extra = extra)); it.finish() }
            }
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, failure.error.code)
        }
    }

    @Test fun noIccPassesThroughIncludingLegalFillAndNonZeroOffsets() {
        val plain = jpeg(ySampling = 0x22)
        val filled = plain.copyOfRange(0, 2) + byteArrayOf(-1, -1) + plain.copyOfRange(2, plain.size)
        val output = ByteArrayOutputStream()
        val stream = PlatformJpegStream(output)
        val padded = byteArrayOf(0, 0) + filled + byteArrayOf(0)
        stream.write(padded, 2, filled.size)
        stream.finish()
        assertArrayEquals(filled, output.toByteArray())
    }

    private fun iccSegment(data: ByteArray, total: Byte = 1) =
        segment(0xe2, "ICC_PROFILE\u0000".encodeToByteArray() + byteArrayOf(1, total) + data)

    @Test fun boundedSrgbProfilesAreRecognizedInOrdinaryJpegAndPng() {
        val profile = matrixSrgbProfile()
        val digest = MessageDigest.getInstance("SHA-256").digest(profile).joinToString("") { "%02x".format(it) }
        val compressed = ByteArrayOutputStream().also { target ->
            java.util.zip.DeflaterOutputStream(target).use { it.write(profile) }
        }.toByteArray()
        for (bytes in listOf(jpeg(extra = iccSegment(profile)), png(extra = chunk("iCCP",
            "sRGB\u0000\u0000".encodeToByteArray() + compressed)))) {
            val parsed = read(bytes) as app.pristinepage.image.decode.EncodedSourceHeader
            assertEquals(PixelSize(3, 2), parsed.size)
            assertEquals(digest, parsed.profileSha256)
        }
    }

    @Test fun ultraHdrPrimaryProjectionAuthenticatesOriginalButNeverHandsGainMapToCodec() {
        val (encoded, primary) = hdrJpeg()
        val root = File("build/test-source-containers").apply { mkdirs() }
        val file = Files.createTempFile(root.toPath(), "synthetic-hdr-", ".jpg").toFile().apply { writeBytes(encoded) }
        val parsed = SourceContainer.read(file) {}
        val view = checkNotNull(parsed.sdrPrimary)
        val digest = MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }
        assertEquals(PixelSize(3, 2), parsed.size)
        assertArrayEquals(primary, view.read(file, digest) {})
        assertArrayEquals(encoded, file.readBytes())
        file.writeBytes(encoded.copyOf().also { it[it.lastIndex - 3] = 99 })
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, assertThrows(RepositoryFailure::class.java) {
            view.read(file, digest) {}
        }.error.code)
    }

    @Test fun genericMpfAndMalformedGainMapOffsetsDoNotBecomeOrdinaryStillImages() {
        reject(OperationErrorCode.UNSUPPORTED_FORMAT, hdrJpeg(declareHdr = false).first)
        reject(OperationErrorCode.UNSUPPORTED_FORMAT, hdrJpeg(offsetDelta = 1).first)
        reject(OperationErrorCode.INVALID_INPUT, hdrJpeg().first.dropLast(2).toByteArray())
    }

    @Test fun xmpMustDeclareAnSdrBaseAndRgbProfilesCannotDescribeGrayscalePng() {
        reject(OperationErrorCode.UNSUPPORTED_FORMAT, hdrJpeg(baseElement = "<hdrgm:BaseRenditionIsHDR>True</hdrgm:BaseRenditionIsHDR>").first)
        reject(OperationErrorCode.UNSUPPORTED_FORMAT, hdrJpeg(commentOnly = true).first)
        reject(OperationErrorCode.UNSUPPORTED_FORMAT, hdrJpeg(secondaryType = 0x020002).first)
        reject(OperationErrorCode.UNSUPPORTED_FORMAT, hdrJpeg(nextIfd = Int.MAX_VALUE).first)
        val compressed = ByteArrayOutputStream().also { target ->
            java.util.zip.DeflaterOutputStream(target).use { it.write(matrixSrgbProfile()) }
        }.toByteArray()
        reject(OperationErrorCode.UNSUPPORTED_FORMAT,
            png(color = 0, extra = chunk("iCCP", "sRGB\u0000\u0000".encodeToByteArray() + compressed)))
    }

    @Test fun dualSignaledUltraHdrAcceptsOnlyAnSdrBaseInBothMetadataFormats() {
        val attributeIfd = ByteBuffer.allocate(26)
            .putInt(0x4d4d002a).putInt(8).putShort(1)
            .putShort(0xb000.toShort()).putShort(7).putInt(4).put("0100".encodeToByteArray()).putInt(0).array()
        val valid = read(hdrJpeg(isoFlags = 0x40, gainMpf = attributeIfd).first) as app.pristinepage.image.decode.EncodedSourceHeader
        assertNotNull(valid.sdrPrimary)
        reject(OperationErrorCode.UNSUPPORTED_FORMAT, hdrJpeg(isoFlags = 0x44, gainMpf = attributeIfd).first)
        reject(OperationErrorCode.UNSUPPORTED_FORMAT, hdrJpeg(isoFlags = 0x40,
            gainMpf = attributeIfd.copyOf().also { ByteBuffer.wrap(it).putInt(22, 500) }).first)
    }

    private fun hdrJpeg(declareHdr: Boolean = true, offsetDelta: Int = 0, baseElement: String = "",
                        commentOnly: Boolean = false, secondaryType: Int = 0, nextIfd: Int = 0,
                        isoFlags: Int? = null, gainMpf: ByteArray? = null): Pair<ByteArray, ByteArray> {
        val signature = "http://ns.adobe.com/xap/1.0/\u0000"
        val description = """<rdf:Description xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0">$baseElement</rdf:Description>"""
        val hdr = """<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">${if (commentOnly) "<!--$description-->" else description}</rdf:RDF>"""
        fun metadata(value: String) = segment(0xe1, (signature + value).encodeToByteArray())
        val xmp = if (declareHdr) metadata(hdr) else byteArrayOf()
        val prefix = "urn:iso:std:iso:ts:21496:-1\u0000".encodeToByteArray()
        val isoHeader = if (isoFlags != null) segment(0xe2, prefix + be(0)) else byteArrayOf()
        val isoGain = if (isoFlags != null) segment(0xe2, prefix + be(0) + byteArrayOf(isoFlags.toByte()) +
            be(0, 1, 2, 1, 0, 1, 2, 1, 1, 1, 0, 1, 0, 1)) else byteArrayOf()
        val gain = jpeg(components = 1, extra = metadata(hdr) + isoGain +
            (gainMpf?.let { segment(0xe2, "MPF\u0000".encodeToByteArray() + it) } ?: byteArrayOf()))
        val icc = iccSegment(matrixSrgbProfile())
        val expected = jpeg()
        val primaryLength = expected.size + icc.size + xmp.size + isoHeader.size + 90
        val tiffStart = 2 + xmp.size + icc.size + isoHeader.size + 8
        val tiff = ByteBuffer.allocate(82)
        tiff.putInt(0x4d4d002a).putInt(8).putShort(3)
        tiff.putShort(0xb000.toShort()).putShort(7).putInt(4).put("0100".encodeToByteArray())
        tiff.putShort(0xb001.toShort()).putShort(4).putInt(1).putInt(2)
        tiff.putShort(0xb002.toShort()).putShort(7).putInt(32).putInt(50).putInt(nextIfd)
        tiff.putInt(0x030000).putInt(primaryLength).putInt(0).putInt(0)
        tiff.putInt(secondaryType).putInt(gain.size).putInt(primaryLength - tiffStart + offsetDelta).putInt(0)
        return (jpeg(extra = xmp + icc + isoHeader + segment(0xe2, "MPF\u0000".encodeToByteArray() + tiff.array())) + gain) to expected
    }

    /** Independently framed ICC v4 matrix/shaper; no Java/Android profile parser generates the oracle. */
    private fun matrixSrgbProfile(redX: Double = .4360747, gamma: Double = 2.4, lastTag: String = "wtpt"): ByteArray {
        fun fixed(x: Double) = (x * 65_536).roundToInt()
        fun xyz(x: Double, y: Double, z: Double) = "XYZ ".encodeToByteArray() + be(0, fixed(x), fixed(y), fixed(z))
        val curve = "para".encodeToByteArray() + be(0, 0x40000, fixed(gamma), fixed(1 / 1.055), fixed(.055 / 1.055),
            fixed(1 / 12.92), fixed(.04045), 0, 0)
        val entries = listOf(
            "rXYZ" to xyz(redX, .2225045, .0139322), "gXYZ" to xyz(.3850649, .7168786, .0971045),
            "bXYZ" to xyz(.1430804, .0606169, .7141733), "rTRC" to curve, "gTRC" to curve, "bTRC" to curve,
            lastTag to xyz(.9642, 1.0, .8249),
        )
        val start = 132 + entries.size * 12
        val buffer = ByteBuffer.allocate(start + entries.sumOf { it.second.size })
        val bytes = buffer.array()
        buffer.putInt(0, bytes.size).putInt(8, 0x04300000).putInt(68, fixed(.9642)).putInt(72, fixed(1.0)).putInt(76, fixed(.8249))
        "mntrRGB XYZ ".encodeToByteArray().copyInto(bytes, 12)
        "acsp".encodeToByteArray().copyInto(bytes, 36)
        buffer.putInt(128, entries.size)
        var offset = start
        for ((i, pair) in entries.withIndex()) {
            val row = 132 + 12 * i
            pair.first.encodeToByteArray().copyInto(bytes, row)
            buffer.putInt(row + 4, offset).putInt(row + 8, pair.second.size)
            pair.second.copyInto(bytes, offset)
            offset += pair.second.size
        }
        return bytes
    }
    private fun webp(extra: ByteArray = byteArrayOf()): ByteArray {
        val data = byteArrayOf(0x2f, 2, 0x40, 0, 0) // VP8L signature,width3,height2; entropy intentionally absent.
        val image = "VP8L".toByteArray() + byteArrayOf(5, 0, 0, 0) + data + byteArrayOf(0)
        val body = "WEBP".toByteArray() + extra + image; val n = body.size
        return "RIFF".toByteArray() + byteArrayOf(n.toByte(), (n ushr 8).toByte(), (n ushr 16).toByte(), (n ushr 24).toByte()) + body
    }

    @Test fun actualMagicSelectsStaticFormatWithoutTrustingAFileExtension() {
        for ((mime, bytes) in listOf("image/png" to png(), "image/jpeg" to jpeg(), "image/webp" to webp())) {
            val header = read(bytes); assertEquals(mime, get(header, "getMime")); assertEquals(PixelSize(3, 2), get(header, "getSize"))
        }
    }

    @Test fun unsupportedMagicAndMultiframeContainersCannotBecomeAnOrdinaryPhoto() {
        reject(OperationErrorCode.UNSUPPORTED_FORMAT, "%PDF-synthetic".toByteArray())
        reject(OperationErrorCode.UNSUPPORTED_FORMAT, png(extra = chunk("acTL", be(2, 0))))
        val vp8x = "VP8X".toByteArray() + byteArrayOf(10, 0, 0, 0, 2, 0, 0, 0, 2, 0, 0, 1, 0, 0)
        reject(OperationErrorCode.UNSUPPORTED_FORMAT, webp(vp8x))
    }

    @Test fun hugeDimensionsRejectBeforeAnyCompressedRasterAllocation() {
        reject(OperationErrorCode.RESOURCE_LIMIT, png(12001, 1))
        reject(OperationErrorCode.RESOURCE_LIMIT, png(8000, 6001))
        reject(OperationErrorCode.INVALID_INPUT, png(0, 2))
    }

    @Test fun pngCrcAndCompleteChunkStructureAreMandatory() {
        val bad = png(); bad[29] = (bad[29].toInt() xor 1).toByte()
        reject(OperationErrorCode.INVALID_INPUT, bad)
        reject(OperationErrorCode.INVALID_INPUT, png().dropLast(1).toByteArray())
        reject(OperationErrorCode.INVALID_INPUT, png() + byteArrayOf(1))
    }

    @Test fun truncatedJpegAndWebpNeverCountAsCompleteContainerStructure() {
        reject(OperationErrorCode.INVALID_INPUT, jpeg().dropLast(2).toByteArray())
        reject(OperationErrorCode.INVALID_INPUT, jpeg() + byteArrayOf(1))
        reject(OperationErrorCode.INVALID_INPUT, webp().dropLast(1).toByteArray())
    }

    @Test fun unsupportedColorDepthProfilesAndHdrRejectInsteadOfGuessingColor() {
        reject(OperationErrorCode.UNSUPPORTED_FORMAT, png(depth = 16))
        reject(OperationErrorCode.UNSUPPORTED_FORMAT, png(extra = chunk("iCCP", byteArrayOf(1, 2, 3))))
        reject(OperationErrorCode.UNSUPPORTED_FORMAT, png(extra = chunk("cICP", byteArrayOf(9, 16, 0, 1))))
        reject(OperationErrorCode.UNSUPPORTED_FORMAT, jpeg(4))
        reject(OperationErrorCode.UNSUPPORTED_FORMAT, jpeg(extra = segment(0xe2, "ICC_PROFILE\u0000synthetic".toByteArray())))
    }

    @Test fun oversizedDeclaredMetadataDoesNotRequestItsClaimedArray() {
        val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        reject(OperationErrorCode.INVALID_INPUT, signature + be(Int.MAX_VALUE) + "IHDR".toByteArray())
        reject(OperationErrorCode.INVALID_INPUT, byteArrayOf(-1, -40, -1, -31, 127, -1, 1))
    }

    @Test fun malformedExifCannotPassContainerPreflightIntoTheGeneralMetadataParser() {
        for ((type, count) in listOf(0 to 1, 3 to 1_000_000_000)) {
            val tiff = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN).put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8).putShort(1).putShort(0x112).putShort(type.toShort()).putInt(count).putInt(8).putInt(0).array()
            reject(OperationErrorCode.INVALID_INPUT, jpeg(extra = segment(0xe1, "Exif\u0000\u0000".toByteArray() + tiff)))
        }
    }

    @Test fun jpegTreatsZeroOrientationAsUnspecified() {
        val exif = ByteBuffer.allocate(26).order(ByteOrder.BIG_ENDIAN).putShort(0x4d4d).putShort(42).putInt(8)
            .putShort(1).putShort(0x112).putShort(3).putInt(1).putInt(0).putInt(0).array()
        val header = read(jpeg(extra = segment(0xe1, "Exif\u0000\u0000".toByteArray() + exif)))
        assertEquals(1, get(header, "getExifOrientation"))
    }

    @Test fun cameraJpegWithAuxiliaryTagsAndOpaqueSuffixKeepsItsPrimaryOrientation() {
        val header = read(compatJpeg(6, ByteArray(5420) { if (it % 5 == 0) 42 else 0 }))
        assertEquals(6, get(header, "getExifOrientation"))
        assertEquals(PixelSize(3, 2), get(header, "getSize"))
    }

    @Test fun dateOptInUsesTheSameCameraJpegAdmissionAndDoesNotEnterTheCodecProjection() {
        val original = compatJpeg(6, byteArrayOf(42, 0, 42), includeDate = true)
        val root = File("build/test-source-containers").apply { mkdirs() }
        val file = Files.createTempFile(root.toPath(), "capture-day-", ".jpg").toFile().apply { writeBytes(original) }
        val header = SourceContainer.readWithCaptureDate(file) {}
        assertEquals(LocalDate.of(2024, 2, 29), header.capturedOn)
        assertEquals(6, header.exifOrientation)
        assertNull(SourceContainer.read(file) {}.capturedOn)
        val hash = MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it.toInt() and 255) }
        val projected = requireNotNull(header.jpegProjection).read(file, hash) {}
        val view = Files.createTempFile(root.toPath(), "capture-day-view-", ".jpg").toFile().apply { writeBytes(projected) }
        assertNull(SourceContainer.readWithCaptureDate(view) {}.capturedOn)
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun pngAndWebpUseOnlyTheirAdmittedExifChunk() {
        val tiff = ByteBuffer.allocate(76).order(ByteOrder.LITTLE_ENDIAN).putShort(0x4949).putShort(42).putInt(8)
            .putShort(2).putShort(0x112).putShort(3).putInt(1).putShort(6).putShort(0)
            .putShort(0x8769.toShort()).putShort(4).putInt(1).putInt(38).putInt(0)
            .putShort(1).putShort(0x9003.toShort()).putShort(2).putInt(20).putInt(56).putInt(0)
            .put("2024:02:29 09:20:01\u0000".encodeToByteArray()).array()
        val root = File("build/test-source-containers").apply { mkdirs() }
        for ((mime, bytes) in listOf("image/png" to png(extra = chunk("eXIf", tiff)),
            "image/webp" to webp("EXIF".encodeToByteArray() + byteArrayOf(76, 0, 0, 0) + tiff))) {
            val file = Files.createTempFile(root.toPath(), "capture-day-", ".bin").toFile().apply { writeBytes(bytes) }
            val header = SourceContainer.readWithCaptureDate(file) {}
            assertEquals(mime, header.mime)
            assertEquals(6, header.exifOrientation)
            assertEquals(LocalDate.of(2024, 2, 29), header.capturedOn)
            assertNull(SourceContainer.read(file) {}.capturedOn)
        }
    }

    @Test fun compatibilityDoesNotAcceptLargeTrailersOrSecondaryImages() {
        reject(OperationErrorCode.RESOURCE_LIMIT, compatJpeg(1, ByteArray(65537)))
        reject(OperationErrorCode.UNSUPPORTED_FORMAT, compatJpeg(1, byteArrayOf(0, -1, -40, -1, -39)))
        reject(OperationErrorCode.UNSUPPORTED_FORMAT, compatJpeg(1, byteArrayOf(0, 0, 0, 24) + "ftyp".toByteArray()))
    }

    @Test fun compatibilityDoesNotTreatAnAppendedRiffContainerAsVendorPadding() {
        val avi = "RIFF".toByteArray() + byteArrayOf(4, 0, 0, 0) + "AVI ".toByteArray()
        reject(OperationErrorCode.UNSUPPORTED_FORMAT, compatJpeg(0, byteArrayOf(0, 0) + avi))
    }

    @Test fun repeatedMakerNotesMustAllUseTheOpaqueUndefinedType() {
        reject(OperationErrorCode.INVALID_INPUT, compatJpeg(6, byteArrayOf(0), firstMakerNoteType = 1))
    }

    @Test fun compatibilityDoesNotSkipDeclaredExifColor() {
        reject(OperationErrorCode.UNSUPPORTED_FORMAT, compatJpeg(0, byteArrayOf(0), color = 2))
    }

    @Test fun codecViewReplacesExifAndAuthenticatesEvenTheUnsubmittedTrailer() {
        val original = compatJpeg(6, byteArrayOf(42, 0, 42))
        val root = File(System.getProperty("user.dir"), "build/test-source-containers").apply { mkdirs() }
        val file = Files.createTempFile(root.toPath(), "jpeg-compat-", ".jpg").toFile().apply { writeBytes(original) }
        val info = SourceContainer.read(file) {}
        val view = requireNotNull(info.jpegProjection)
        val hash = MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it.toInt() and 255) }
        val codecInput = view.read(file, hash) {}
        assertArrayEquals(original, file.readBytes())
        assertArrayEquals(byteArrayOf(-1, -39), codecInput.takeLast(2).toByteArray())
        val syntheticView = Files.createTempFile(root.toPath(), "projected-", ".jpg").toFile().apply { writeBytes(codecInput) }
        val checked = SourceContainer.read(syntheticView) {}
        assertEquals(6, checked.exifOrientation)
        assertNull("The platform must receive ordinary EXIF, never the relaxed input", checked.jpegProjection)
        assertTrue(codecInput.size < original.size)
        val changed = original.clone().apply { this[lastIndex] = 41 }
        file.writeBytes(changed)
        val failure = assertThrows(RepositoryFailure::class.java) { view.read(file, hash) {} }
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, failure.error.code)
    }

    @Test fun codecViewCanDiscardTheLargestLegalApp1WithoutGrowingItsScratchBuffer() {
        val tiff = ByteBuffer.allocate(65527).order(ByteOrder.BIG_ENDIAN).putShort(0x4d4d).putShort(42).putInt(8)
            .putShort(1).putShort(0x112).putShort(3).putInt(1).putInt(0).putInt(0).array()
        val original = jpeg(extra = segment(0xe1, "Exif\u0000\u0000".toByteArray() + tiff))
        val root = File(System.getProperty("user.dir"), "build/test-source-containers").apply { mkdirs() }
        val file = Files.createTempFile(root.toPath(), "jpeg-max-app1-", ".jpg").toFile().apply { writeBytes(original) }
        val hash = MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it.toInt() and 255) }
        val view = requireNotNull(SourceContainer.read(file) {}.jpegProjection)
        assertEquals(1, get(read(view.read(file, hash) {}), "getExifOrientation"))
        assertArrayEquals(original, file.readBytes())
    }

    private fun compatJpeg(orientation: Int, trailer: ByteArray, color: Int = 1, firstMakerNoteType: Int = 7, includeDate: Boolean = false): ByteArray {
        val tiff = ByteBuffer.allocate(if (includeDate) 124 else 92).order(ByteOrder.BIG_ENDIAN).putShort(0x4d4d).putShort(42).putInt(8)
        tiff.putShort(2).putShort(0x112).putShort(3).putInt(1).putShort(orientation.toShort()).putShort(0)
        tiff.putShort(0x8769.toShort()).putShort(4).putInt(1).putInt(38).putInt(0)
        tiff.putShort(if (includeDate) 5 else 4).putShort(0x10d).putShort(7).putInt(0).putInt(0)
        repeat(2) { tiff.putShort(0x927c.toShort()).putShort(if (it == 0) firstMakerNoteType.toShort() else 7).putInt(1).putInt(0x2a000000) }
        tiff.putShort(0xa001.toShort()).putShort(3).putInt(1).putShort(color.toShort()).putShort(0)
        if (includeDate) tiff.putShort(0x9003.toShort()).putShort(2).putInt(20).putInt(104)
        tiff.putInt(0)
        if (includeDate) tiff.put("2024:02:29 09:20:01\u0000".encodeToByteArray())
        return jpeg(extra = segment(0xe1, "Exif\u0000\u0000".toByteArray() + tiff.array())) + trailer
    }

    @Test fun ordinaryLowDepthGrayscaleAndIndexedPngAreAdmittedForStrictDecode() {
        val gray = read(png(depth = 1, color = 0)); assertEquals(PixelSize(3, 2), get(gray, "getSize"))
        val paletteHeader = chunk("IHDR", be(3, 2) + byteArrayOf(2, 3, 0, 0, 0))
        val indexed = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) + paletteHeader + chunk("PLTE", byteArrayOf(0, 0, 0, -1, -1, -1)) + chunk("IDAT", byteArrayOf(0)) + chunk("IEND", byteArrayOf())
        assertEquals(PixelSize(3, 2), get(read(indexed), "getSize"))
    }
}
