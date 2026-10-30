package com.anopticlabs.gravel.notebook.storage

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceStateFileTest {
    private val dir = Files.createTempDirectory("state").toFile()
    private val file = DeviceStateFile(dir, "state")

    @Test
    fun nothingSavedReadsAsNothing() {
        assertNull(file.read())
    }

    @Test
    fun theLastValueWrittenIsReadBack() {
        assertTrue(file.write("SPEECH"))
        assertTrue(file.write("FINISHED"))
        assertEquals("FINISHED", DeviceStateFile(dir, "state").read())
        assertFalse(File(dir, "state.tmp").exists())
    }

    @Test
    fun clearingRemovesWhatWasSaved() {
        assertTrue(file.clear())
        assertTrue(file.write("SPEECH"))
        assertTrue(file.clear())
        assertNull(file.read())
        assertFalse(File(dir, "state").exists())
    }

    @Test
    fun aMissingDirectoryIsCreated() {
        val nested = DeviceStateFile(File(dir, "a/b"), "state")
        assertTrue(nested.write("BACKUPS"))
        assertEquals("BACKUPS", nested.read())
    }

    @Test
    fun anEmptyOrOversizedFileReadsAsNothing() {
        File(dir, "state").writeText("  ")
        assertNull(file.read())
        File(dir, "state").writeText("X".repeat(65))
        assertNull(file.read())
        assertEquals("X".repeat(65), DeviceStateFile(dir, "state", maxChars = 65).read())
    }

    @Test
    fun aRenameThatFailsReportsFalse() {
        File(dir, "state").mkdir()
        File(dir, "state/inside").writeText("x")
        assertFalse(file.write("BACKUPS"))
    }

    @Test
    fun aWriteThatCannotLandReportsFalseAndKeepsWhatWasSaved() {
        assertTrue(file.write("TEXT_MODEL"))
        File(dir, "state.tmp").mkdir()
        assertFalse(file.write("BACKUPS"))
        assertEquals("TEXT_MODEL", file.read())
    }
}
