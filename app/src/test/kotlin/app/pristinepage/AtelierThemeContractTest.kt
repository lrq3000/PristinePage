package app.pristinepage

import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import app.pristinepage.data.model.AlbumOrder
import app.pristinepage.design.AtelierSettings
import org.junit.Test

class AtelierThemeContractTest {
    @Test fun bothVariantsPointAtTheBrandedLauncherResource() {
        for (variant in listOf("debug", "release")) {
            val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(java.io.File(checkNotNull(System.getProperty("pristinepage.manifest.$variant"))))
            val app = manifest.getElementsByTagName("application").item(0)
            for (attribute in listOf("icon", "roundIcon")) {
                assertEquals(variant, "@mipmap/pristinepage_launcher",
                    app.attributes.getNamedItemNS("http://schemas.android.com/apk/res/android", attribute)?.nodeValue)
            }
        }
    }

    @Test fun storedSettingsRecoverIndependentlyWithoutDefaultingOtherFields() {
        val settings = AtelierSettings.fromStored(mapOf(
            "order" to "unknown", "created" to true, "grid" to false,
        ))
        assertEquals(AtelierSettings(AlbumOrder.MODIFIED, true, false), settings)
    }
}
