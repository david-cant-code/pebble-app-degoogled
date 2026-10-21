package com.anopticlabs.gravel.notebook.alarms

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Pins the receivers' exported flags and the permissions the alarm stack needs, at the source. */
class NotebookManifestTest {

    private val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile())

    @Test
    fun noReceiverIsExportedAndTheRearmReceiverTakesTheThreeSystemBroadcasts() {
        val receivers = elements("receiver").associateBy { it.getAttribute("android:name") }
        assertEquals("false", receivers.getValue("com.anopticlabs.gravel.notebook.alarms.ReminderFireReceiver").getAttribute("android:exported"))
        assertEquals("false", receivers.getValue("com.anopticlabs.gravel.notebook.alarms.ReminderActionReceiver").getAttribute("android:exported"))
        val rearm = receivers.getValue("com.anopticlabs.gravel.notebook.alarms.NotebookRearmReceiver")
        assertEquals("false", rearm.getAttribute("android:exported"))
        val actions = (0 until rearm.getElementsByTagName("action").length)
            .map { (rearm.getElementsByTagName("action").item(it) as Element).getAttribute("android:name") }
        assertEquals(NotebookRearmReceiver.ACTIONS, actions.toSet())
        assertEquals(3, actions.size)
        assertEquals(3, receivers.size)
    }

    @Test
    fun theAlarmStackDeclaresItsPermissions() {
        val permissions = elements("uses-permission").map { it.getAttribute("android:name") }.toSet()
        assertTrue("android.permission.RECEIVE_BOOT_COMPLETED" in permissions)
        assertTrue("android.permission.SCHEDULE_EXACT_ALARM" in permissions)
        assertTrue("android.permission.POST_NOTIFICATIONS" in permissions)
    }

    private fun elements(tag: String): List<Element> {
        val nodes = manifest.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun manifestFile(): File {
        val relative = "src/androidMain/AndroidManifest.xml"
        var dir: File? = File(checkNotNull(System.getProperty("user.dir")))
        while (dir != null) {
            val direct = File(dir, relative)
            if (direct.isFile) return direct
            val fromRoot = File(dir, "notebook/$relative")
            if (fromRoot.isFile) return fromRoot
            dir = dir.parentFile
        }
        fail("$relative not found above ${System.getProperty("user.dir")}")
    }
}
