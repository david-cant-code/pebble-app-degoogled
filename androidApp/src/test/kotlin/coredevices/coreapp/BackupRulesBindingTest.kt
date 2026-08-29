package coredevices.coreapp

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.w3c.dom.Element

/**
 * Pins which backup rule files the app manifest binds, at the source level.
 * `BackupRulesTest` pins what the fork's three rule files say; this test pins
 * that the manifest actually points at them, which is the half a sync
 * resolution can lose on its own: upstream's manifest names different files
 * for both attributes, and upstream's `backup_rules.xml` is a
 * `<full-backup-content>` file where the fork's same-named file is a
 * `<data-extraction-rules>` file. Taking upstream's two attribute lines with
 * the fork's files still in the tree would back up under upstream's rules
 * (no client-side-encryption gate) while every file-level test stayed green.
 *
 * Each attribute is also checked against the root element of the file it
 * names, because the two attributes are read by different parsers: a
 * `<data-extraction-rules>` file handed to the pre-31 `fullBackupContent`
 * parser fails to parse and turns into "back up nothing", silently.
 */
class BackupRulesBindingTest {

    @Test
    fun manifestBindsTheForkRuleFilesToTheRightParsers() {
        val application = applicationElement()
        assertEquals(
            "@xml/backup_rules",
            application.getAttribute("android:dataExtractionRules"),
            "android:dataExtractionRules must name the fork's API 31+ rules",
        )
        assertEquals(
            "@xml/full_backup_content",
            application.getAttribute("android:fullBackupContent"),
            "android:fullBackupContent must name the fork's pre-31 rules",
        )
        assertRootElement("backup_rules", "data-extraction-rules")
        assertRootElement("full_backup_content", "full-backup-content")
    }

    private fun assertRootElement(resourceName: String, expectedRoot: String) {
        val files = resourceFiles(resourceName)
        assertTrue(files.isNotEmpty(), "no res/xml*/$resourceName.xml found")
        files.forEach { file ->
            assertEquals(
                expectedRoot,
                parse(file).documentElement.tagName,
                "${file.parentFile.name}/${file.name} must be a <$expectedRoot> file for the attribute that names it",
            )
        }
    }

    private fun applicationElement(): Element {
        val applications = parse(moduleFile("src/main/AndroidManifest.xml")).getElementsByTagName("application")
        assertEquals(1, applications.length, "expected exactly one <application>")
        return applications.item(0) as Element
    }

    // Every qualifier variant counts: the pre-31 file has an xml-v28 sibling.
    private fun resourceFiles(resourceName: String): List<File> {
        val res = moduleFile("src/main/res")
        return res.listFiles { dir -> dir.isDirectory && (dir.name == "xml" || dir.name.startsWith("xml-")) }
            .orEmpty()
            .map { File(it, "$resourceName.xml") }
            .filter { it.isFile }
            .sortedBy { it.path }
    }

    private fun parse(file: File) = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(file)

    // Same walk as NetworkSecurityConfigTest: user.dir is the module directory
    // under Gradle, but the walk keeps the test independent of that.
    private fun moduleFile(relative: String): File {
        var dir: File? = File(checkNotNull(System.getProperty("user.dir")))
        while (dir != null) {
            val direct = File(dir, relative)
            if (direct.exists()) return direct
            val fromRoot = File(dir, "androidApp/$relative")
            if (fromRoot.exists()) return fromRoot
            dir = dir.parentFile
        }
        fail("$relative not found above ${System.getProperty("user.dir")}")
    }
}
