package com.anopticlabs.gravel.notebook.storage

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The app's backup rules must exclude the Notebook directory from every set that backs up the
 * file domain: cloud backup and device transfer on API 31+, and the API 28 to 30 rules. The
 * API 26 and 27 rules back up nothing.
 */
class NotebookBackupExclusionTest {

    @Test
    fun api31RulesExcludeTheNotebookFromCloudBackupAndDeviceTransfer() {
        val document = parse(resFile("xml", "backup_rules.xml"))
        for (section in listOf("cloud-backup", "device-transfer")) {
            val excludes = document.elementsNamed(section).single().childElements("exclude")
            assertTrue(excludes.any { it.excludesNotebook() }, "$section does not exclude files/${NotebookFiles.DIR_NAME}")
        }
    }

    @Test
    fun api28RulesExcludeTheNotebook() {
        val document = parse(resFile("xml-v28", "full_backup_content.xml"))
        assertTrue(document.elementsNamed("exclude").any { it.excludesNotebook() }, "no exclude for files/${NotebookFiles.DIR_NAME}")
    }

    private fun Element.excludesNotebook() =
        getAttribute("domain") == "file" && getAttribute("path") == NotebookFiles.DIR_NAME

    private fun parse(file: File) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

    private fun org.w3c.dom.Document.elementsNamed(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.childElements(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

    // The rules live in the androidApp module; the walk keeps the test independent of user.dir.
    private fun resFile(qualifier: String, name: String): File {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            val res = File(dir, "androidApp/src/main/res")
            if (res.isDirectory) return File(File(res, qualifier), name)
            dir = dir.parentFile
        }
        fail("androidApp/src/main/res not found above ${System.getProperty("user.dir")}")
    }
}
