package com.anopticlabs.gravel.notebook.storage

import androidx.test.platform.app.InstrumentationRegistry
import com.anopticlabs.gravel.notebook.capture.markQueuedCaptureFailed
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The queue store over the real Keystore codec, in a throwaway directory beside the Notebook root.
 *
 * Run with:
 * adb shell am instrument -w -e class \
 *   com.anopticlabs.gravel.notebook.storage.QueueStoreKeystoreTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class QueueStoreKeystoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.filesDir, "notebook-queue-test")
    private val crypto = appKeyCrypto()
    private val audio = ByteArray(64 * 1024) { (it * 31 % 256).toByte() }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun entriesAndBlobsRoundTripThroughTheKeystore() = runBlocking {
        val store = QueueStore(root, crypto)
        store.enqueue("fg_1", audio, "recording_1.m4a", queuedAtMillis = 42L)
        store.update { markQueuedCaptureFailed(it, "fg_1") }

        val fresh = QueueStore(root, crypto)
        val entry = fresh.readQueue(strict = true).single()
        assertEquals("fg_1", entry.id)
        assertEquals(1, entry.attempts)
        assertContentEquals(audio, assertIs<BlobRead.Present>(fresh.readBlob("fg_1")).bytes)

        File(root, "${QueueStore.QUEUE_DIR}/orphan${QueueStore.BLOB_EXTENSION}").writeBytes(audio)
        assertEquals(1, fresh.sweepOrphanBlobs(minAgeMillis = 0))
        assertTrue(fresh.remove("fg_1").isEmpty())
        assertEquals(BlobRead.Absent, fresh.readBlob("fg_1"))
        assertFalse(File(root, EncryptedFiles.QUARANTINE_DIR).exists())
    }

    @Test
    fun aRetainedCaptureIsQueuedOnTheNextLaunch() = runBlocking {
        val store = QueueStore(root, crypto)
        val cache = File(context.cacheDir, "notebook-queue-test-recording.m4a").apply { writeBytes(audio) }
        val kept = store.retainPending(cache, "7c1e")
        assertEquals(File(root, "${QueueStore.PENDING_DIR}/7c1e~notebook-queue-test-recording.m4a"), kept)
        assertFalse(cache.exists())

        assertEquals(SalvageResult(listOf("7c1e"), 0), QueueStore(root, crypto).salvagePending())

        assertFalse(kept!!.exists())
        assertEquals("notebook-queue-test-recording.m4a", store.readQueue(strict = true).single().originalFileName)
        assertContentEquals(audio, assertIs<BlobRead.Present>(store.readBlob("7c1e")).bytes)
    }
}
