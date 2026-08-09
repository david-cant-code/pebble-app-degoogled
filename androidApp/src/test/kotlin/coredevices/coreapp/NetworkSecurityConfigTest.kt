package coredevices.coreapp

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.fail
import org.w3c.dom.Element

/**
 * Pins the fork's network security config, whose entire cleartext control is
 * an ABSENT attribute: with targetSdk 28+ a bare base-config means cleartext
 * off app-wide, and the manifest's usesCleartextTraffic="true" is inert
 * because this config file is declared. Upstream sets
 * cleartextTrafficPermitted="true" here, so every upstream sync presents
 * re-enabling cleartext as an innocuous one-line resolution in a file the
 * fork otherwise takes verbatim; without this test that resolution would land
 * with nothing going red. The threat model for keeping cleartext off is a
 * watchapp config page fetched over plain HTTP handing a network-position
 * attacker script injection into the phone WebView.
 *
 * The trust-anchor pin is functional as much as security: user-added CAs are
 * why this file exists at all (PKJS apps talking to self-hosted servers over
 * TLS with a private CA), so losing the user entry in a merge would break
 * that quietly.
 */
class NetworkSecurityConfigTest {

    @Test
    fun cleartextIsNotPermittedAnywhereInTheConfig() {
        val document = parse(configFile())
        // File-wide, not just base-config: a permissive domain-config or
        // debug-overrides element added in a merge must trip this too.
        val all = document.getElementsByTagName("*")
        (0 until all.length).map { all.item(it) as Element }.forEach { element ->
            assertFalse(
                element.hasAttribute("cleartextTrafficPermitted"),
                "<${element.tagName}> sets cleartextTrafficPermitted; the fork's " +
                    "control is the deliberate absence of this attribute",
            )
        }
    }

    @Test
    fun baseConfigTrustsExactlySystemAndUserStores() {
        val document = parse(configFile())
        val baseConfigs = document.getElementsByTagName("base-config")
        assertEquals(1, baseConfigs.length, "expected exactly one base-config")
        val certificates = (baseConfigs.item(0) as Element).getElementsByTagName("certificates")
        val sources = (0 until certificates.length)
            .map { (certificates.item(it) as Element).getAttribute("src") }
            .toSet()
        assertEquals(setOf("system", "user"), sources)
    }

    private fun parse(file: File) = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(file)

    // Walk up from the runner's working directory like BackupRulesTest does:
    // Gradle runs unit tests with user.dir at the module directory, but the
    // walk keeps the test independent of that detail.
    private fun configFile(): File {
        var dir: File? = File(checkNotNull(System.getProperty("user.dir")))
        while (dir != null) {
            val direct = File(dir, "src/main/res/xml/network_security_config.xml")
            if (direct.isFile) return direct
            val fromRoot = File(dir, "androidApp/src/main/res/xml/network_security_config.xml")
            if (fromRoot.isFile) return fromRoot
            dir = dir.parentFile
        }
        fail("network_security_config.xml not found above ${System.getProperty("user.dir")}")
    }
}
