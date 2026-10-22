package com.anopticlabs.gravel.llm

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Pins the engine service's containment at the source: the module
 * manifest declares [LlmEngineService] with isolatedProcess="true" and
 * exported="false", in a process of its own. Dropping any attribute
 * changes nothing that compiles, so this is the source layer of the
 * guard; the app build's VerifyIsolatedServices task checks the merged
 * manifest of every variant, and the device suite observes the real uid.
 */
class LlmEngineManifestTest {

    @Test
    fun theEngineServiceIsIsolatedUnexportedAndInItsOwnProcess() {
        val services = parse(manifestFile()).getElementsByTagName("service")
        val engine = (0 until services.length)
            .map { services.item(it) as Element }
            .filter { it.getAttribute("android:name") == "com.anopticlabs.gravel.llm.LlmEngineService" }
        assertEquals(1, engine.size, "expected exactly one LlmEngineService declaration")
        assertEquals("true", engine.single().getAttribute("android:isolatedProcess"), "the engine service must run isolated")
        assertEquals("false", engine.single().getAttribute("android:exported"), "the engine service must not be exported")
        assertEquals(":llm", engine.single().getAttribute("android:process"), "the engine must not share the speech engine's process")
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
            val fromRoot = File(dir, "llm/$relative")
            if (fromRoot.isFile) return fromRoot
            dir = dir.parentFile
        }
        fail("$relative not found above ${System.getProperty("user.dir")}")
    }
}
