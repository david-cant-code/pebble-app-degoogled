package coredevices.coreapp

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import org.w3c.dom.Element

/**
 * Pins the fork's cleartext posture at the source level: the network
 * security config says cleartextTrafficPermitted="false" on base-config and
 * nothing in the file says "true", and the app manifest declares no
 * usesCleartextTraffic attribute while declaring the config. Upstream has the
 * opposite of both (cleartextTrafficPermitted="true" in the config,
 * usesCleartextTraffic="true" in the manifest), so every upstream sync
 * presents re-enabling cleartext as a one-line resolution in files the fork
 * otherwise takes verbatim; without this test that resolution would land with
 * nothing going red. The threat model for keeping cleartext off is a watchapp
 * config page fetched over plain HTTP handing a network-position attacker
 * script injection into the phone WebView.
 *
 * The manifest attribute is checked even though Android ignores it once a
 * network security config is declared: the built manifest is what store
 * scanners and reviewers read, and it should state what is true. The
 * artifact-level counterpart (the attribute absent from the built APK, where
 * a library manifest could merge it back in) lives in the CI workflow.
 *
 * The trust-anchor pin is functional as much as security: user-added CAs are
 * why this file exists at all (PKJS apps talking to self-hosted servers over
 * TLS with a private CA), so losing the user entry in a merge would break
 * that quietly.
 */
class NetworkSecurityConfigTest {

    @Test
    fun baseConfigDeniesCleartextExplicitly() {
        val document = parse(configFile())
        val baseConfigs = document.getElementsByTagName("base-config")
        assertEquals(1, baseConfigs.length, "expected exactly one base-config")
        assertEquals(
            "false",
            (baseConfigs.item(0) as Element).getAttribute("cleartextTrafficPermitted"),
            "base-config must set cleartextTrafficPermitted=\"false\" explicitly",
        )
    }

    @Test
    fun cleartextIsNotPermittedAnywhereInTheConfig() {
        val document = parse(configFile())
        // File-wide, not just base-config: a permissive domain-config or
        // debug-overrides element added in a merge must trip this too.
        val all = document.getElementsByTagName("*")
        (0 until all.length).map { all.item(it) as Element }.forEach { element ->
            assertFalse(
                element.getAttribute("cleartextTrafficPermitted") == "true",
                "<${element.tagName}> sets cleartextTrafficPermitted=\"true\"; the fork keeps cleartext off",
            )
        }
    }

    @Test
    fun manifestDeclaresNoCleartextAttributeAndPointsAtTheConfig() {
        val document = parse(manifestFile())
        val applications = document.getElementsByTagName("application")
        assertEquals(1, applications.length, "expected exactly one <application>")
        val application = applications.item(0) as Element
        assertFalse(
            application.hasAttribute("android:usesCleartextTraffic"),
            "<application> declares android:usesCleartextTraffic; the fork removed it (the " +
                "network security config governs, and the built manifest should not claim cleartext)",
        )
        assertEquals(
            "@xml/network_security_config",
            application.getAttribute("android:networkSecurityConfig"),
            "<application> must declare the network security config that turns cleartext off",
        )
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

    private fun configFile(): File = moduleFile("src/main/res/xml/network_security_config.xml")

    private fun manifestFile(): File = moduleFile("src/main/AndroidManifest.xml")

    // Walk up from the runner's working directory like BackupRulesTest does:
    // Gradle runs unit tests with user.dir at the module directory, but the
    // walk keeps the test independent of that detail.
    private fun moduleFile(relative: String): File {
        var dir: File? = File(checkNotNull(System.getProperty("user.dir")))
        while (dir != null) {
            val direct = File(dir, relative)
            if (direct.isFile) return direct
            val fromRoot = File(dir, "androidApp/$relative")
            if (fromRoot.isFile) return fromRoot
            dir = dir.parentFile
        }
        fail("$relative not found above ${System.getProperty("user.dir")}")
    }
}
