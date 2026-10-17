package com.anopticlabs.gravel.notebook.storage

import java.io.File
import java.nio.file.Files
import org.junit.Assume.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotebookFilesTest {
    private val root = Files.createTempDirectory("notebook-files").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun aMissingOrEmptyRootHoldsNoSealedFiles() {
        assertFalse(NotebookFiles.holdsSealedFiles(File(root, "absent")))
        assertFalse(NotebookFiles.holdsSealedFiles(root))
        File(root, NoteStore.NOTES_DIR).mkdirs()
        File(root, QueueStore.QUEUE_DIR).mkdirs()
        assertFalse(NotebookFiles.holdsSealedFiles(root))
    }

    @Test
    fun filesInTheUnsealedDirectoriesDoNotCount() {
        for (dir in NotebookFiles.UNSEALED_DIRS) File(root, "$dir/nested/file").apply { parentFile!!.mkdirs() }.writeText("x")
        assertFalse(NotebookFiles.holdsSealedFiles(root))
    }

    @Test
    fun anyOtherFileCountsAtAnyDepth() {
        File(root, "${NoteStore.NOTES_DIR}/0123456789abcdef0123456789abcdef").apply { parentFile!!.mkdirs() }.writeText("x")
        assertTrue(NotebookFiles.holdsSealedFiles(root))
        File(root, NoteStore.NOTES_DIR).deleteRecursively()
        File(root, ReminderStore.FILE_NAME).writeText("x")
        assertTrue(NotebookFiles.holdsSealedFiles(root))
    }

    @Test
    fun aDirectoryThatCannotBeListedCountsAsHoldingFiles() {
        val notes = File(root, NoteStore.NOTES_DIR).apply { mkdirs() }
        File(notes, "0123456789abcdef0123456789abcdef").writeText("x")
        try {
            notes.setReadable(false)
            assumeTrue("needs a user that file permissions bind", notes.listFiles() == null)
            assertTrue(NotebookFiles.holdsSealedFiles(root))
            notes.setReadable(true)
            File(notes, "0123456789abcdef0123456789abcdef").delete()
            root.setReadable(false)
            assertTrue(NotebookFiles.holdsSealedFiles(root))
        } finally {
            root.setReadable(true)
            notes.setReadable(true)
        }
    }
}
