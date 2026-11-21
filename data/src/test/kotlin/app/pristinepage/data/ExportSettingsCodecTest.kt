package app.pristinepage.data

import app.pristinepage.core.pipeline.*
import app.pristinepage.data.model.*
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

/** Fixed binary-v1 golden was derived independently with Python struct big-endian fields before the production codec existed. No model/encoder/profile availability follows from a settings round trip. */
class ExportSettingsCodecTest {
    private val a = PageId("9b58d249-0641-4cb7-bbe7-ff805098b21b")
    private val b = PageId("180aab39-4036-4979-922e-e91a1ded30c4")
    private val firstRender = RenderId("39e7bf93-1337-4a3a-8dd0-1b7c3a7b695b")
    private val secondRender = RenderId("7416d7de-4ae5-4b01-87db-c7beee52b81c")
    private val output = OutputSpec(PixelSize(3, 2), RenderPurpose.EXPORT)
    private val other = OutputSpec(PixelSize(2, 3), RenderPurpose.EXPORT)
    private val png = ExportSettings(ExportFormat.PNG, listOf(PageOutputSettings(a, output)), null, null, emptyList())
    private fun call(name: String, arg: Any): Any {
        val type = try { Class.forName("app.pristinepage.data.model.ExportSettingsCodec") }
        catch (_: ClassNotFoundException) { throw AssertionError("Canonical export settings codec is absent") }
        return try { type.methods.single { it.name == name }.invoke(type.getField("INSTANCE").get(null), arg)!! }
        catch (failure: InvocationTargetException) { throw failure.targetException }
    }
    private fun encode(settings: ExportSettings = png) = call("encode", settings) as ByteArray
    private fun decode(bytes: ByteArray) = call("decode", bytes) as ExportSettings
    private fun hash(settings: ExportSettings = png) = call("hash", settings) as String
    private fun accepted(page: PageId, render: RenderId) = AcceptedExecution(page, 1, "a".repeat(64), render, other.size, "b".repeat(64))

    @Test fun canonicalPngHasIndependent113ByteGoldenAndDomainScopedHash() {
        val hex = "7072697374696e65706167652d6578706f72742d73657474696e67732d76310000000003504e47000000010000002439623538643234392d303634312d346362372d626265372d66663830353039386232316200000003000000020000000300000002000000044641494c000000000000"
        val golden = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(113, golden.size); assertArrayEquals(golden, encode())
        assertEquals("7900b978b6bbe00f667497a3dc59778c9258380e54cc7d1d50a65420dcadb3f2", hash())
    }

    @Test fun pageOrderAndAllExplicitPdfJpegValuesSurviveRoundTrip() {
        val pages = listOf(PageOutputSettings(b, other), PageOutputSettings(a, output))
        val pdf = PdfSettings(PdfPageRule.A4_LANDSCAPE, 300.0, PdfMarginRule.UNIFORM_POINTS, 12.0, "android_pdfdocument", 1, 1)
        val jpeg = JpegSettings(92, "android_bitmap", 1, JpegChromaPolicy.PLATFORM_VERIFIED, JpegSampling.YUV420)
        for (settings in listOf(ExportSettings(ExportFormat.PDF, pages, pdf, null, emptyList()), ExportSettings(ExportFormat.JPEG, pages, null, jpeg, emptyList()))) {
            val saved = decode(encode(settings)); assertEquals(settings.format, saved.format); assertEquals(pages, saved.pages)
            assertEquals(settings.pdf, saved.pdf); assertEquals(settings.jpeg, saved.jpeg); assertArrayEquals(encode(settings), encode(saved))
        }
    }

    @Test fun aNamedImageZipRetainsThePositionsAndEveryNamingInputAffectsTheHash() {
        val jpeg = JpegSettings(92, "android_bitmap", 1, JpegChromaPolicy.PLATFORM_VERIFIED, JpegSampling.YUV420)
        fun request(title: String, positions: List<Int>) = ExportSettings(ExportFormat.IMAGE_ZIP,
            listOf(PageOutputSettings(b, output), PageOutputSettings(a, other)), null, jpeg, emptyList(),
            AlbumExportScope(title, positions))
        val request = request("Réunion", listOf(2, 5))
        // Independently packed as big-endian fields with Python struct, including the UTF-8 title and its byte count.
        val v2 = "7072697374696e65706167652d6578706f72742d73657474696e67732d76320000000009494d4147455f5a4950000000020000002431383061616233392d343033362d343937392d393232652d65393161316465643330633400000003000000020000000300000002000000044641494c0000002439623538643234392d303634312d346362372d626265372d66663830353039386232316200000002000000030000000200000003000000044641494c00010000005c0000000e616e64726f69645f6269746d61700000000100000011504c4154464f524d5f564552494649454400000006595556343230000000000000000852c3a9756e696f6e0000000200000005"
        assertArrayEquals(v2.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), encode(request))
        assertEquals("e19d440ff2fd18062cc79dde00fbed91ef30fa254517b5f665c5ed4049058cb5", hash(request))
        val saved = decode(encode(request))
        assertEquals(ExportFormat.IMAGE_ZIP, saved.format)
        assertEquals("Réunion", saved.albumScope?.title)
        assertEquals(listOf(2, 5), saved.albumScope?.positions)
        assertNotEquals(hash(request), hash(request("Other", listOf(2, 5))))
        assertNotEquals(hash(request), hash(request("Réunion", listOf(2, 6))))
        assertThrows(IllegalArgumentException::class.java) { request("Réunion", listOf(5, 2)) }
        assertThrows(IllegalArgumentException::class.java) { request(" Réunion ", listOf(2, 5)) }
        assertThrows(IllegalArgumentException::class.java) {
            ExportSettings(ExportFormat.PNG, png.pages, null, null, emptyList(), AlbumExportScope("Réunion", listOf(2)))
        }
    }

    @Test fun acceptanceLookupOrderIsNotSemanticButPageOrderAlwaysIs() {
        val pages = listOf(PageOutputSettings(a, other, FallbackPolicy.KEEP_STAGE_INPUT, output), PageOutputSettings(b, other, FallbackPolicy.KEEP_STAGE_INPUT, output))
        val approvals = listOf(accepted(a, firstRender), accepted(b, secondRender))
        val first = ExportSettings(ExportFormat.PNG, pages, null, null, approvals)
        val reorderedApprovals = ExportSettings(ExportFormat.PNG, pages, null, null, approvals.reversed())
        assertArrayEquals(encode(first), encode(reorderedApprovals)); assertEquals(hash(first), hash(reorderedApprovals))
        assertNotEquals(hash(first), hash(ExportSettings(ExportFormat.PNG, pages.reversed(), null, null, approvals)))
        assertEquals(approvals, decode(encode(first)).acceptedDegraded)
    }

    @Test fun everyReviewedOutputOrEncoderDecisionChangesPersistedIdentity() {
        val jpeg = JpegSettings(92, "android_bitmap", 1, JpegChromaPolicy.PLATFORM_VERIFIED, JpegSampling.YUV420)
        val settings = listOf(png, ExportSettings(ExportFormat.PNG, listOf(PageOutputSettings(a, other)), null, null, emptyList()), ExportSettings(ExportFormat.JPEG, png.pages, null, jpeg, emptyList()), ExportSettings(ExportFormat.JPEG, png.pages, null, jpeg.copy(quality = 91), emptyList()), ExportSettings(ExportFormat.JPEG, png.pages, null, jpeg.copy(expectedSampling = JpegSampling.YUV422), emptyList()), ExportSettings(ExportFormat.PNG, listOf(PageOutputSettings(a, other, FallbackPolicy.KEEP_STAGE_INPUT, output)), null, null, listOf(accepted(a, firstRender))))
        assertEquals(settings.size, settings.map(::hash).toSet().size)
    }

    @Test fun invalidTagTruncationTrailingBytesAndNoncanonicalBooleanReject() {
        val good = encode()
        val badBoolean = good.copyOf(); badBoolean[badBoolean.size - 6] = 2
        val badTag = good.copyOf(); badTag[0] = 0
        for (bytes in listOf(byteArrayOf(), good.copyOf(1), good.copyOf(good.size - 1), good + byteArrayOf(0), badTag, badBoolean)) assertThrows(IllegalArgumentException::class.java) { decode(bytes) }
    }

    @Test fun oversizedFieldsAndCountsRejectBeforeUnboundedAllocation() {
        val good = encode(); val headerBytes = "pristinepage-export-settings-v1\u0000".toByteArray().size
        val hugeText = good.copyOf(); ByteBuffer.wrap(hugeText).putInt(headerBytes, Int.MAX_VALUE)
        val hugePages = good.copyOf(); ByteBuffer.wrap(hugePages).putInt(headerBytes + 7, Int.MAX_VALUE)
        for (bytes in listOf(hugeText, hugePages, ByteArray(524289))) assertThrows(IllegalArgumentException::class.java) { decode(bytes) }
    }

    @Test fun decodedPurposeAndOrdinaryDimensionsCannotBypassTypedValidation() {
        val good = encode(); val pageIdStart = good.toString(Charsets.ISO_8859_1).indexOf(a.value); assertTrue(pageIdStart >= 0)
        val changedActual = good.copyOf(); ByteBuffer.wrap(changedActual).putInt(pageIdStart + 36 + 8, 2)
        val invalidUtf8 = good.copyOf(); invalidUtf8[pageIdStart] = 0xc3.toByte()
        assertThrows(IllegalArgumentException::class.java) { decode(changedActual) }
        assertThrows(IllegalArgumentException::class.java) { decode(invalidUtf8) }
        assertEquals(RenderPurpose.EXPORT, decode(good).pages.single().output.purpose)
    }

    @Test fun returnedBytesAreOwnedAndDecodeNeverAliasesCallerStorage() {
        val bytes = encode(); val decoded = decode(bytes); bytes.fill(0)
        assertEquals(png.pages, decoded.pages); assertEquals(png.format, decoded.format)
        assertEquals("7900b978b6bbe00f667497a3dc59778c9258380e54cc7d1d50a65420dcadb3f2", hash(decoded))
        val another = encode(); another.fill(0); assertEquals(113, encode().size); assertNotEquals(0, encode()[0].toInt())
    }
}
