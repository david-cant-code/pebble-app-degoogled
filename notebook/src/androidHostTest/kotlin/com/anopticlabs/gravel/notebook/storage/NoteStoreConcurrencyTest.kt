package com.anopticlabs.gravel.notebook.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Several store instances over one root, as the capture pipeline, receivers and the UI will each construct their own. */
class NoteStoreConcurrencyTest {

    private val root = Files.createTempDirectory("notebook").toFile()
    private val crypto = softwareCrypto()
    private val files = EncryptedFiles(root, crypto)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun assertNothingLeftBehind() {
        assertTrue(files.stagingDir.listFiles().orEmpty().isEmpty())
        assertFalse(files.quarantineDir.exists())
    }

    @Test
    fun lockForReturnsOneMutexPerName() {
        assertSame(NotebookLocks.lockFor("notes_meta"), NotebookLocks.lockFor("notes_meta"))
        assertNotSame(NotebookLocks.lockFor("notes_meta"), NotebookLocks.lockFor("folders_list"))
    }

    @Test
    fun instancesWritingConcurrentlyLoseNoIndexEntryFolderOrTag() = runBlocking {
        val a = NoteStore(root, crypto)
        val b = NoteStore(root, crypto)
        val n = 40
        coroutineScope {
            for (i in 0 until n) {
                launch(Dispatchers.IO) {
                    a.createNote("inbox", "a-$i.md", "A-$i")
                    a.ensureFolder("fa$i")
                    a.updateFolderTags { it + ("fa$i" to listOf("t")) }
                }
                launch(Dispatchers.IO) {
                    b.createNote("inbox", "b-$i.md", "B-$i")
                    b.ensureFolder("fb$i")
                    b.updateFolderTags { it + ("fb$i" to listOf("t")) }
                }
            }
        }
        val keys = a.readMetadata().values.map { "${it.folder}/${it.name}" }.toSet()
        val folders = b.readFolders().toSet()
        val tags = a.readFolderTags().keys
        for (i in 0 until n) {
            assertTrue("inbox/a-$i.md" in keys && "inbox/b-$i.md" in keys, "note $i")
            assertTrue("fa$i" in folders && "fb$i" in folders, "folder $i")
            assertTrue("fa$i" in tags && "fb$i" in tags, "tags $i")
        }
        assertEquals(2 * n, keys.size)
        assertNothingLeftBehind()
    }

    @Test
    fun sameNameInDifferentFoldersKeepsDistinctBodiesUnderConcurrentWritesAndMoves() = runBlocking {
        val a = NoteStore(root, crypto)
        val b = NoteStore(root, crypto)
        val work = a.createNote("work", "note.md", "WORK")
        val home = b.createNote("home", "note.md", "HOME")
        coroutineScope {
            repeat(60) { i ->
                launch(Dispatchers.IO) { a.updateNote(work, "WORK") }
                launch(Dispatchers.IO) { b.updateNote(home, "HOME") }
                launch(Dispatchers.IO) { a.moveNote(work, if (i % 2 == 0) "misc" else "work") }
            }
        }
        val meta = a.readMetadata()
        assertEquals(setOf(work, home), meta.keys)
        assertEquals("WORK", a.readNote(work))
        assertEquals("HOME", b.readNote(home))
        assertEquals("home", meta.getValue(home).folder)
        assertNothingLeftBehind()
    }

    @Test
    fun concurrentCreatesOfOneNameEachGetTheirOwnSlot() = runBlocking {
        val stores = List(3) { NoteStore(root, crypto) }
        coroutineScope {
            for (i in 0 until 30) launch(Dispatchers.IO) { stores[i % 3].createNote("work", "same_05.31.26_1407.md", "body $i") }
        }
        val meta = stores[0].readMetadata()
        assertEquals(30, meta.size)
        assertEquals((0 until 30).map { "body $it" }.toSet(), meta.keys.map { stores[1].readNote(it) }.toSet())
        assertNothingLeftBehind()
    }
}
