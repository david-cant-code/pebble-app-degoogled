package com.anopticlabs.gravel.notebook.backup

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileRunRecordTest {

    private val dir = Files.createTempDirectory("runs").toFile()
    private val file = File(dir, "note")
    private val record = FileRunRecord(file, { "h:$it" }, { it.removePrefix("h:") })

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun everyStageANameOrNoneAndARestoreReadBackAsWritten() {
        assertNull(record.read())
        for (stage in SaveStage.entries) {
            val save = UnfinishedRun.Save("content://docs/a b", "gravel-notebook.gnbk", stage)
            assertTrue(record.write(save))
            assertEquals(save, FileRunRecord(file, { "h:$it" }, { it.removePrefix("h:") }).read())
        }
        assertTrue(record.write(UnfinishedRun.Save("x", null, SaveStage.PICKED)))
        assertEquals(UnfinishedRun.Save("x", null, SaveStage.PICKED), record.read())
        assertTrue(record.write(UnfinishedRun.Save("x", "two\nlines", SaveStage.WRITING)))
        assertEquals(UnfinishedRun.Save("x", "two lines", SaveStage.WRITING), record.read())
        assertTrue(record.write(UnfinishedRun.Restore))
        assertEquals(UnfinishedRun.Restore, record.read())
    }

    @Test
    fun aClearRemovesOnlyTheNoteItExpects() {
        val first = UnfinishedRun.Save("a", "a", SaveStage.WRITING)
        record.write(first)
        record.clear(UnfinishedRun.Save("a", "a", SaveStage.PICKED))
        assertEquals(first, record.read())
        record.clear(first)
        assertNull(record.read())
        assertFalse(file.exists())
    }

    @Test
    fun aNoteWhoseNameTheFileCannotSpellIsClearedAsWrittenAndAsReadBack() {
        for (name in listOf("two\nlines", "two\rlines", "two\r\nlines", "lone\uD800surrogate")) {
            val save = UnfinishedRun.Save("a", name, SaveStage.WRITING)
            assertTrue(record.write(save))
            record.clear(save)
            assertFalse(file.exists(), name)
            assertTrue(record.write(save))
            record.clear(assertNotNull(record.read()))
            assertFalse(file.exists(), name)
        }
    }

    @Test
    fun aNoteThatReadsBackButNotAsWrittenIsClearedAsReadBack() {
        file.writeText("save\nWRITING\nh:a\nname\nextra")
        record.clear(assertNotNull(record.read()))
        assertFalse(file.exists())
    }

    @Test
    fun aMalformedNoteReadsAsNoneAndAWriteThatCannotLandSaysSo() {
        file.writeText("save\nNOT_A_STAGE\nh:a\nname")
        assertNull(record.read())
        file.writeText("something else")
        assertNull(record.read())
        val nowhere = FileRunRecord(File(dir, "missing/note"), { it }, { it })
        assertFalse(nowhere.write(UnfinishedRun.Restore))
        assertNull(nowhere.read())
    }

    @Test
    fun aWriteThatFailsLeavesTheEarlierNoteWhole() {
        val first = UnfinishedRun.Save("a", "a", SaveStage.WRITING)
        record.write(first)
        File(dir, "note.new").mkdir()
        assertFalse(record.write(UnfinishedRun.Save("a", "a", SaveStage.VERIFIED)))
        assertEquals(first, record.read())
    }
}
