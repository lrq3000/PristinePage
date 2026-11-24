package app.pristinepage.image.decode

import java.io.StringReader
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/** Only interpret the bounded HDR declaration, never resolve a DTD, entity or external resource. */
internal object UltraHdrXmp {
    private const val HEADER = "http://ns.adobe.com/xap/1.0/\u0000"
    private const val GAIN = "http://ns.adobe.com/hdr-gain-map/1.0/"
    private const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"

    fun declaresSdr(payload: ByteArray): Boolean {
        if (payload.size !in (HEADER.length + 1)..65533 || !HEADER.indices.all { payload[it] == HEADER[it].code.toByte() }) return false
        val packet = try { payload.decodeToString(startIndex = HEADER.length, throwOnInvalidSequence = true) }
        catch (_: java.nio.charset.CharacterCodingException) { return false }
        // Parse from an already decoded character stream. Reject declarations before the parser can see them, regardless of its platform defaults.
        if ("<!" in packet.replace("<!--", "").replace("<![CDATA[", "") || '\u0000' in packet) return false
        val handler = Declaration()
        return try {
            SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().parse(InputSource(StringReader(packet)), handler)
            handler.version
        } catch (_: SAXException) { false } catch (_: java.io.IOException) { false }
        catch (_: javax.xml.parsers.ParserConfigurationException) { false }
    }

    private class Declaration : DefaultHandler() {
        private var depth = 0
        private var descriptionDepth = 0
        private var property: String? = null
        private val value = StringBuilder()
        private var baseSeen = false
        var version = false
            private set

        override fun resolveEntity(publicId: String?, systemId: String?): InputSource = throw SAXException()

        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            if (++depth > 32 || property != null) throw SAXException()
            if (uri == RDF && localName == "Description") descriptionDepth = depth
            for (i in 0 until attributes.length) if (attributes.getURI(i) == GAIN &&
                attributes.getLocalName(i) in fields) {
                if (descriptionDepth != depth) throw SAXException()
                accept(attributes.getLocalName(i), attributes.getValue(i))
            }
            if (uri == GAIN && localName in fields) {
                if (descriptionDepth == 0 || depth != descriptionDepth + 1) throw SAXException()
                property = localName
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (property == null) return
            if (length > 64 - value.length) throw SAXException()
            value.append(ch, start, length)
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            if (property != null) {
                accept(checkNotNull(property), value.toString().trim())
                property = null
                value.setLength(0)
            }
            if (descriptionDepth == depth) descriptionDepth = 0
            depth--
        }

        private fun accept(field: String, text: String) {
            when (field) {
                "Version" -> {
                    if (version || text != "1.0") throw SAXException()
                    version = true
                }
                "BaseRenditionIsHDR" -> {
                    if (baseSeen || !text.equals("false", ignoreCase = true)) throw SAXException()
                    baseSeen = true
                }
            }
        }
    }

    private val fields = setOf("Version", "BaseRenditionIsHDR")
}
