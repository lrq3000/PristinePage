package app.pristinepage

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

/** Inspect actual AGP merged variants: fixture byte delivery is internal to debug and cannot become an exported source provider or a release capability through a manifest merge. */
class EditorFixtureIsolationTest {
    @Test fun syntheticJourneyInputIsPrivateToDebugAndExcludedFromTheReleaseManifest() {
        val debug = providers("debug").singleOrNull { it.android("name") == "app.pristinepage.testing.PrivateEditorFixtureProvider" }
        assertNotNull("Private debug editor-journey input is absent",debug)
        val provider = checkNotNull(debug)
        assertEquals("app.pristinepage.debug.editor-fixtures",provider.android("authorities"))
        assertEquals("false",provider.android("exported")); assertEquals("false",provider.android("grantUriPermissions"))
        assertFalse(providers("release").any { it.android("name") == provider.android("name") || it.android("authorities").contains("editor-fixtures") })
    }

    private fun providers(variant: String): List<Element> {
        val path = checkNotNull(System.getProperty("pristinepage.manifest.$variant"))
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; setFeature("http://apache.org/xml/features/disallow-doctype-decl",true) }
        val nodes = factory.newDocumentBuilder().parse(File(path)).getElementsByTagName("provider")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }
    private fun Element.android(name: String) = getAttributeNS("http://schemas.android.com/apk/res/android",name)
}
