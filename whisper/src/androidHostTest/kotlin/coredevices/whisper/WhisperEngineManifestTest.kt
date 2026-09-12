package coredevices.whisper

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Pins the engine service's containment at the source: the module
 * manifest declares [WhisperEngineService] with isolatedProcess="true"
 * and exported="false". Dropping either attribute changes nothing that
 * compiles or that the host suites run, so this is the deterministic
 * guard; the on-device isolation test observes the real uid.
 */
class WhisperEngineManifestTest {

    @Test
    fun theEngineServiceIsIsolatedAndNotExported() {
        val services = parse(manifestFile()).getElementsByTagName("service")
        val engine = (0 until services.length)
            .map { services.item(it) as Element }
            .filter { it.getAttribute("android:name") == "coredevices.whisper.WhisperEngineService" }
        assertEquals(1, engine.size, "expected exactly one WhisperEngineService declaration")
        assertEquals("true", engine.single().getAttribute("android:isolatedProcess"), "the engine service must run isolated")
        assertEquals("false", engine.single().getAttribute("android:exported"), "the engine service must not be exported")
    }

    private fun parse(file: File) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

    // Gradle runs host tests with user.dir at the module directory; the
    // walk keeps the test independent of that detail.
    private fun manifestFile(): File {
        val relative = "src/androidMain/AndroidManifest.xml"
        var dir: File? = File(checkNotNull(System.getProperty("user.dir")))
        while (dir != null) {
            val direct = File(dir, relative)
            if (direct.isFile) return direct
            val fromRoot = File(dir, "whisper/$relative")
            if (fromRoot.isFile) return fromRoot
            dir = dir.parentFile
        }
        fail("$relative not found above ${System.getProperty("user.dir")}")
    }
}
