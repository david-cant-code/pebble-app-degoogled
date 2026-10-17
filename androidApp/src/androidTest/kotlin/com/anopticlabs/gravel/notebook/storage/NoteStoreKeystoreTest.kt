package com.anopticlabs.gravel.notebook.storage

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The note store over the real Keystore codec, in a throwaway directory beside the Notebook root.
 *
 * Run with:
 * adb shell am instrument -w -e class \
 *   com.anopticlabs.gravel.notebook.storage.NoteStoreKeystoreTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class NoteStoreKeystoreTest {

    private val root = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "notebook-store-test-${System.nanoTime()}")
    private val crypto = appKeyCrypto()

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun notesFoldersAndTagsRoundTripThroughTheKeystore() = runBlocking {
        val store = NoteStore(root, crypto)
        assertEquals(CreateFolderResult.Created("work", listOf("work", "budget")), store.createFolder("Work", "budget"))
        val id = store.createNote("work", "plan_05.31.26_1407.md", "---\ntitle: \"Plan\"\ntags: [\"budget\"]\n---\n\nthe budget meeting", title = "Plan")
        val second = store.createNote("work", "plan_05.31.26_1407.md", "other")
        assertEquals("plan-2_05.31.26_1407.md", store.readMetadata().getValue(second).name)

        val fresh = NoteStore(root, crypto)
        assertEquals("the budget meeting", fresh.readNote(id)!!.substringAfterLast("\n\n").trim())
        assertEquals(setOf("budget"), fresh.readStems(strict = true).getValue(id).tags)
        assertEquals("plan_05.31.26_1407.md", fresh.moveNote(id, "archive"))
        assertEquals(listOf(FolderItem("unclassified", 0), FolderItem("work", 1), FolderItem("archive", 1)), fresh.folderItems())
        assertEquals(DeleteFolderResult.Deleted(1), fresh.deleteFolder("work"))
        val placed = fresh.readMetadata().mapValues { "${it.value.folder}/${it.value.name}" }
        assertEquals(mapOf(id to "archive/plan_05.31.26_1407.md", second to "unclassified/plan-2_05.31.26_1407.md"), placed)
        assertFalse(File(root, EncryptedFiles.QUARANTINE_DIR).exists())

        val listed = fresh.readMetadata()
        File(root, NoteStore.META_FILE).delete()
        assertEquals(ReconcileResult(rebuilt = true, listed = 2), fresh.reconcile())
        assertEquals(listed, fresh.readMetadata(strict = true))
    }

    @Test
    fun twoInstancesWritingConcurrentlyLoseNoEntry() = runBlocking {
        val a = NoteStore(root, crypto)
        val b = NoteStore(root, crypto)
        coroutineScope {
            for (i in 0 until 20) {
                launch(Dispatchers.IO) { a.createNote("inbox", "a-$i.md", "A-$i") }
                launch(Dispatchers.IO) { b.createNote("inbox", "b-$i.md", "B-$i") }
            }
        }
        val keys = a.readMetadata().values.map { "${it.folder}/${it.name}" }.toSet()
        for (i in 0 until 20) assertTrue("inbox/a-$i.md" in keys && "inbox/b-$i.md" in keys, "entry $i")
        assertTrue(File(root, EncryptedFiles.STAGING_DIR).listFiles().orEmpty().isEmpty())
    }
}
