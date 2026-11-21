package app.pristinepage.data.model

import app.pristinepage.core.pipeline.FallbackPolicy
import app.pristinepage.core.pipeline.OutputSpec
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.RenderPurpose
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** export-settings-v1, not Java serialization or enum ordinals: tagged UTF-8, explicit big-endian fields/defaults, semantic page order, and acceptance lookup entries in that same order. This identity contains no snapshot ID, date, lease, backend timing or mutable current pointer. */
object ExportSettingsCodec {
    private const val MAX_BYTES = 524288
    private const val MAX_TEXT_BYTES = 512
    private val tag = "pristinepage-export-settings-v1\u0000".toByteArray(Charsets.UTF_8)
    private val namedTag = "pristinepage-export-settings-v2\u0000".toByteArray(Charsets.UTF_8)

    fun encode(settings: ExportSettings): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            fun text(value: String) {
                val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
                require(encoded.remaining() in 1..MAX_TEXT_BYTES)
                val owned = ByteArray(encoded.remaining()); encoded.get(owned)
                output.writeInt(owned.size); output.write(owned)
            }
            output.write(if (settings.albumScope == null) tag else namedTag); text(settings.format.name); output.writeInt(settings.pages.size)
            for (page in settings.pages) {
                text(page.pageId.value)
                output.writeInt(page.requestedOutput.size.width); output.writeInt(page.requestedOutput.size.height)
                output.writeInt(page.output.size.width); output.writeInt(page.output.size.height)
                text(page.fallbackPolicy.name)
            }
            output.writeBoolean(settings.pdf != null)
            settings.pdf?.let { pdf ->
                text(pdf.pageRule.name); output.writeDouble(pdf.nominalDpi); text(pdf.marginRule.name)
                output.writeDouble(if (pdf.marginPoints == 0.0) 0.0 else pdf.marginPoints)
                text(pdf.writerId); output.writeInt(pdf.writerVersion); output.writeInt(pdf.admissionProfileVersion)
            }
            output.writeBoolean(settings.jpeg != null)
            settings.jpeg?.let { jpeg ->
                output.writeInt(jpeg.quality); text(jpeg.encoderId); output.writeInt(jpeg.encoderVersion)
                text(jpeg.chromaPolicy.name); text(jpeg.expectedSampling.name)
            }
            // Acceptance is a lookup by PageId, not a second independently ordered page list. Normalize it to the already-reviewed semantic page order.
            val accepted = settings.acceptedDegraded.associateBy { it.pageId }
            output.writeInt(accepted.size)
            for (page in settings.pages) accepted[page.pageId]?.let { execution ->
                text(execution.pageId.value); output.writeLong(execution.recipeRevision); text(execution.executionHash); text(execution.renderId.value)
                output.writeInt(execution.actualSize.width); output.writeInt(execution.actualSize.height); text(execution.rasterPixelHash)
            }
            settings.albumScope?.let { album ->
                text(album.title)
                album.positions.forEach(output::writeInt)
            }
        }
        require(bytes.size() <= MAX_BYTES)
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): ExportSettings {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)
        try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val header = ByteArray(tag.size); input.readFully(header)
                val named = header.contentEquals(namedTag)
                require(named || header.contentEquals(tag))
                val reader = Reader(input)
                val format = reader.enum<ExportFormat>()
                val pages = List(reader.count(1)) {
                    val page = PageId(reader.text())
                    val requested = OutputSpec(PixelSize(input.readInt(), input.readInt()), RenderPurpose.EXPORT)
                    val actual = OutputSpec(PixelSize(input.readInt(), input.readInt()), RenderPurpose.EXPORT)
                    PageOutputSettings(page, actual, reader.enum<FallbackPolicy>(), requested)
                }
                val pdf = if (reader.present()) PdfSettings(reader.enum<PdfPageRule>(), input.readDouble(), reader.enum<PdfMarginRule>(), input.readDouble(), reader.text(), input.readInt(), input.readInt()) else null
                val jpeg = if (reader.present()) JpegSettings(input.readInt(), reader.text(), input.readInt(), reader.enum<JpegChromaPolicy>(), reader.enum<JpegSampling>()) else null
                val accepted = List(reader.count(0)) {
                    AcceptedExecution(PageId(reader.text()), input.readLong(), reader.text(), RenderId(reader.text()), PixelSize(input.readInt(), input.readInt()), reader.text())
                }
                val album = if (named) AlbumExportScope(reader.text(), List(pages.size) { input.readInt() }) else null
                require(input.available() == 0)
                return ExportSettings(format, pages, pdf, jpeg, accepted, album)
            }
        } catch (failure: Exception) { throw IllegalArgumentException("Unsupported or invalid export settings", failure) }
    }

    /** The complete byte stream already starts with its own unambiguous versioned domain tag. */
    fun hash(settings: ExportSettings): String = MessageDigest.getInstance("SHA-256").digest(encode(settings)).joinToString("") { "%02x".format(it.toInt() and 255) }

    private class Reader(private val input: DataInputStream) {
        fun text(): String {
            val count = input.readInt()
            require(count in 1..MAX_TEXT_BYTES && count <= input.available())
            val bytes = ByteArray(count); input.readFully(bytes)
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        }
        inline fun <reified T : Enum<T>> enum(): T = enumValueOf(text())
        fun count(minimum: Int): Int = input.readInt().also { require(it in minimum..100) }
        fun present(): Boolean = when (input.readUnsignedByte()) { 0 -> false; 1 -> true; else -> throw IllegalArgumentException("Noncanonical presence flag") }
    }
}
