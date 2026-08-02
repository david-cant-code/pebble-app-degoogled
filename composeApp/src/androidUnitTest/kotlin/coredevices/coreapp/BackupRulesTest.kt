package coredevices.coreapp

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the semantics of the backup rule files that resource compilation and lint cannot check.
 *
 * The FullBackupContent lint check (fatal, runs in CI) already validates element names, domain
 * values, and paths, but it knows nothing about requireFlags: a typo there is ignored by the
 * platform parser at runtime, which silently turns "back up only when client-side encrypted"
 * into "back up in the clear". It also does not know that a single include element in
 * backup_rules.xml would flip those rules into allowlist mode and silently stop backing up
 * every domain not named. These are exactly the failure modes pinned here, one test per file.
 */
class BackupRulesTest {

    @Test
    fun `api 28 to 30 rules gate every included domain on client-side encryption`() {
        val document = parse(resFile("xml-v28", "full_backup_content.xml"))
        val includes = document.elementsNamed("include")

        // Allowlist mode covers only what is named, so the domain set must track what the app
        // uses; a domain dropped here silently stops being backed up, and a domain added
        // without the flag silently ships plaintext.
        assertEquals(
            setOf("root", "file", "database", "sharedpref", "external"),
            includes.map { it.getAttribute("domain") }.toSet(),
        )
        includes.forEach { include ->
            assertEquals(
                "clientSideEncryption",
                include.getAttribute("requireFlags"),
                "include for domain '${include.getAttribute("domain")}' is not gated",
            )
        }
    }

    @Test
    fun `api 26 and 27 rules are valid there and back up nothing`() {
        val document = parse(resFile("xml", "full_backup_content.xml"))
        val elements = document.elementsNamed("include") + document.elementsNamed("exclude")

        // The 8.x parser throws on any include/exclude with more than two attributes (only
        // domain and path exist there), and the default backup agent reacts to that parse
        // failure by silently backing up nothing. The file must stay inside that limit so the
        // no-backup outcome remains a deliberate rule set, not a swallowed exception.
        elements.forEach { element ->
            val names = (0 until element.attributes.length)
                .map { element.attributes.item(it).nodeName }
            assertTrue(
                names.all { it == "domain" || it == "path" },
                "<${element.tagName}> carries attributes the API 26/27 parser rejects: $names",
            )
        }

        // One include switches Auto Backup into allowlist mode, and the sentinel path never
        // exists, so the allowlist matches nothing. More includes, or an unexpected shape,
        // would start backing data up on devices that cannot client-side encrypt it.
        val includes = document.elementsNamed("include")
        assertEquals(1, includes.size)
        assertEquals("file", includes.single().getAttribute("domain"))
        assertEquals("gravel_no_backup_sentinel", includes.single().getAttribute("path"))
    }

    @Test
    fun `api 31 rules keep the encryption gate and stay exclude-only`() {
        val document = parse(resFile("xml", "backup_rules.xml"))

        val cloudBackup = document.elementsNamed("cloud-backup").single()
        assertEquals("true", cloudBackup.getAttribute("disableIfNoEncryptionCapabilities"))

        // A single include element would flip these rules into allowlist mode and silently
        // stop backing up every domain not named.
        assertEquals(emptyList(), document.elementsNamed("include"))
    }

    private fun parse(file: File) = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(file)

    private fun org.w3c.dom.Document.elementsNamed(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    /** Locates the res file from wherever Gradle happens to set the test working directory. */
    private fun resFile(qualifier: String, name: String): File {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            val res = File(dir, "composeApp/src/androidMain/res")
            if (res.isDirectory) return File(File(res, qualifier), name)
            dir = dir.parentFile
        }
        error("could not locate composeApp/src/androidMain/res from ${System.getProperty("user.dir")}")
    }
}
