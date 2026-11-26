package app.pristinepage

import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** Reads the actual AGP merged artifacts, not the source manifest: a dependency can add a permission or change application attributes during merging. Device backup behavior remains a separate gate. */
class ManifestPolicyTest {
    @Test
    fun debugMergedManifestCannotGrantNetworkBackupOrBroadStorage() = assertManifest("debug", "app.pristinepage.debug")

    @Test
    fun releaseMergedManifestCannotGrantNetworkBackupOrBroadStorage() = assertManifest("release", "app.pristinepage")

    @Test
    fun legacyBackupRulesExcludeAllPrivateAndExternalDomains() {
        val rules = parse(resourceFile("backup_rules.xml"))
        assertEquals("full-backup-content", rules.documentElement.tagName)
        assertExclusions(rules.documentElement)
    }

    @Test
    fun modernBackupRulesExcludeBothCloudAndDeviceTransfer() {
        val rules = parse(resourceFile("data_extraction_rules.xml"))
        assertEquals("data-extraction-rules", rules.documentElement.tagName)
        for (mode in listOf("cloud-backup", "device-transfer")) {
            val sections = rules.getElementsByTagName(mode)
            assertEquals("Missing or duplicate $mode policy", 1, sections.length)
            assertExclusions(sections.item(0) as Element)
        }
    }

    @Test
    fun exportedActivityWithoutOneCompleteLauncherFilterIsRejected() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        for (filters in listOf("", "<intent-filter><action android:name=\"android.intent.action.MAIN\" /></intent-filter><intent-filter><category android:name=\"android.intent.category.LAUNCHER\" /></intent-filter>")) {
            val activity = factory.newDocumentBuilder().parse(InputSource(StringReader("<activity xmlns:android=\"http://schemas.android.com/apk/res/android\" android:name=\"app.pristinepage.MainActivity\" android:exported=\"true\">$filters</activity>"))).documentElement
            assertFalse("An exported activity alone is not a launchable entry point", isLauncher(activity))
        }
    }

    private fun assertManifest(variant: String, packageName: String) {
        val manifest = parse(inputFile("pristinepage.manifest.$variant"))
        assertEquals(packageName, manifest.documentElement.getAttribute("package"))
        val forbiddenPermissions = setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.READ_MEDIA_IMAGES",
        )
        val declaredPermissions = mutableSetOf<String>()
        for (tag in listOf("uses-permission", "uses-permission-sdk-23")) {
            val permissions = manifest.getElementsByTagName(tag)
            for (index in 0 until permissions.length) {
                declaredPermissions += (permissions.item(index) as Element).android("name")
            }
        }
        assertEquals("$variant must use scoped grants, not broad storage or network access", emptySet<String>(), declaredPermissions intersect forbiddenPermissions)
        val sdk = manifest.getElementsByTagName("uses-sdk").item(0) as Element
        assertEquals("26", sdk.android("minSdkVersion"))
        assertEquals("36", sdk.android("targetSdkVersion"))
        val app = manifest.getElementsByTagName("application").item(0) as Element
        assertEquals("false", app.android("allowBackup"))
        assertEquals("@xml/backup_rules", app.android("fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", app.android("dataExtractionRules"))
        assertEquals("", app.android("backupAgent"))
        if (variant == "release") assertFalse("Release must not be debuggable", app.android("debuggable") == "true")
        val activities = manifest.getElementsByTagName("activity")
        assertTrue("No launcher activity was merged", (0 until activities.length).any { index ->
            val activity = activities.item(index) as Element
            isLauncher(activity)
        })
    }

    private fun isLauncher(activity: Element): Boolean {
        if (activity.android("name") != "app.pristinepage.MainActivity" || activity.android("exported") != "true") return false
        val filters = activity.getElementsByTagName("intent-filter")
        return (0 until filters.length).any { index ->
            val filter = filters.item(index) as Element
            filter.containsNamedElement("action", "android.intent.action.MAIN") && filter.containsNamedElement("category", "android.intent.category.LAUNCHER")
        }
    }

    private fun Element.containsNamedElement(tag: String, name: String): Boolean {
        val elements = getElementsByTagName(tag)
        return (0 until elements.length).any { (elements.item(it) as Element).android("name") == name }
    }

    private fun assertExclusions(section: Element) {
        assertEquals("Backup includes must never override exclusions", 0, section.getElementsByTagName("include").length)
        val exclusions = section.getElementsByTagName("exclude")
        val excludedDomains = (0 until exclusions.length).map { index ->
            val exclusion = exclusions.item(index) as Element
            assertEquals("An exclusion must cover the complete domain", ".", exclusion.getAttribute("path"))
            exclusion.getAttribute("domain")
        }.toSet()
        assertEquals(setOf("root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref"), excludedDomains)
    }

    private fun resourceFile(name: String): File = File(inputFile("pristinepage.policyResourceDir"), name)

    private fun inputFile(property: String): File {
        val path = System.getProperty(property)
        assertNotNull("Build must wire actual policy input: $property", path)
        return File(requireNotNull(path)).also { assertTrue("Missing policy input: $it", it.exists()) }
    }

    private fun parse(file: File): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        return factory.newDocumentBuilder().parse(file)
    }

    private fun Element.android(name: String): String = getAttributeNS("http://schemas.android.com/apk/res/android", name)
}
