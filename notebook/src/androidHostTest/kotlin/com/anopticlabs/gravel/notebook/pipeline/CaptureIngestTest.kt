package com.anopticlabs.gravel.notebook.pipeline

import com.anopticlabs.gravel.notebook.capture.QueuedCapture
import com.anopticlabs.gravel.notebook.capture.RecordingBuffer
import com.anopticlabs.gravel.notebook.storage.BlobRead
import com.anopticlabs.gravel.notebook.storage.FakeCryptographer
import com.anopticlabs.gravel.notebook.storage.QueueStore
import com.anopticlabs.gravel.notebook.storage.softwareCrypto
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class CaptureIngestTest {

    private val root = Files.createTempDirectory("notebook").toFile()
    private val crypto = FakeCryptographer(softwareCrypto())
    private val queue = QueueStore(root, crypto)
    private val pendingDir = File(root, QueueStore.PENDING_DIR)
    private val now = 1_790_089_200_000L
    private val audio = ByteArray(8_000) { (it * 13 % 256).toByte() }
    private val queuedCalls = CopyOnWriteArrayList<List<QueuedCapture>>()
    private val entriesQueued = CopyOnWriteArrayList<QueuedCapture>()
    private val ingest = CaptureIngest(
        queue,
        captureQueued = { queuedCalls += queue.readQueue() },
        entryQueued = { entriesQueued += it },
        clock = { now },
        zone = { TimeZone.of("Europe/Berlin") },
        newId = { "c-1" },
    )

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun aRecordingIsQueuedWithItsTimeZoneAndNameThenARunIsAskedFor() = runBlocking<Unit> {
        assertEquals(IngestResult.QUEUED, ingest.ingest(audio, "wav"))

        val entry = queue.readQueue(strict = true).single()
        assertEquals(QueuedCapture("c-1", "recording_$now.wav", now, zoneId = "Europe/Berlin"), entry)
        assertContentEquals(audio, assertIs<BlobRead.Present>(queue.readBlob("c-1")).bytes)
        assertEquals(listOf(listOf(entry)), queuedCalls)
        assertEquals(listOf(entry), entriesQueued)
    }

    @Test
    fun aWavRecordingIsQueuedWithItsLength() = runBlocking<Unit> {
        val wav = RecordingBuffer().apply { append(ShortArray(48_000), 48_000) }.toWav()
        assertEquals(IngestResult.QUEUED, ingest.ingest(wav, "wav"))
        assertEquals(3_000L, queue.readQueue(strict = true).single().durationMillis)
        assertEquals(3_000L, entriesQueued.single().durationMillis)
    }

    @Test
    fun aRecordingTheQueueCannotSealIsKeptForALaterRun() = runBlocking<Unit> {
        crypto.failSealWhenAadContains = "queue/c-1"

        assertEquals(IngestResult.KEPT, ingest.ingest(audio, "wav"))

        assertEquals(listOf("c-1~recording_$now.wav"), pendingDir.list().orEmpty().toList())
        assertContentEquals(audio, File(pendingDir, "c-1~recording_$now.wav").readBytes())
        assertEquals(1, queuedCalls.size)
        assertTrue(entriesQueued.isEmpty(), "a kept recording is not in the queue yet")
        crypto.failSealWhenAadContains = null
        assertEquals(listOf("c-1"), queue.salvagePending().queued)
    }

    @Test
    fun aWatchRecordingIsQueuedAsOneAndStaysOneWhenItWasKeptFirst() = runBlocking<Unit> {
        val wav = RecordingBuffer().apply { append(ShortArray(32_000), 32_000) }.toWav()
        val started = now - 20_000
        crypto.failSealWhenAadContains = "queue/c-1"

        assertEquals(IngestResult.KEPT, ingest.ingest(wav, "wav", capturedAtMillis = started, fromWatch = true))
        assertEquals(listOf("c-1~watch_$started.wav"), pendingDir.list().orEmpty().toList())
        assertEquals(1, queue.retainedWatchCount())

        crypto.failSealWhenAadContains = null
        assertEquals(listOf("c-1"), queue.salvagePending().queued)
        val salvaged = queue.readQueue(strict = true).single()
        assertTrue(salvaged.fromWatch, "a kept watch recording is queued as one")
        assertEquals(2_000L, salvaged.durationMillis)
        assertEquals(started, salvaged.queuedAtMillis, "dated at its start, not when the file was written")
        assertEquals(0, queue.retainedWatchCount())

        queue.remove("c-1")
        assertEquals(IngestResult.QUEUED, ingest.ingest(wav, "wav", fromWatch = true))
        assertEquals(0, queue.retainedWatchCount())
        val queued = queue.readQueue(strict = true).single()
        assertEquals("watch_$now.wav", queued.originalFileName)
        assertTrue(queued.fromWatch)
        assertTrue(entriesQueued.single().fromWatch)
    }

    @Test
    fun aNonTransientStoreFailureAlsoKeepsTheRecording() = runBlocking<Unit> {
        crypto.failSealWithErrorWhenAadContains = "queue_meta"

        assertEquals(IngestResult.KEPT, ingest.ingest(audio, "wav"))

        assertContentEquals(audio, File(pendingDir, "c-1~recording_$now.wav").readBytes())
        assertEquals(emptyList(), queue.readQueue())
    }

    @Test
    fun aRecordingNeitherQueuedNorKeptIsReportedLost() = runBlocking<Unit> {
        crypto.failSealWhenAadContains = "queue/c-1"
        pendingDir.parentFile.mkdirs()
        pendingDir.writeText("not a directory")

        assertEquals(IngestResult.LOST, ingest.ingest(audio, "wav"))
        assertEquals(1, queuedCalls.size)
    }

    @Test
    fun aCaptureIsDatedWithTheTimeAndZoneItsSourceGives() = runBlocking<Unit> {
        ingest.ingest(audio, "wav", capturedAtMillis = 1_000L, capturedIn = TimeZone.of("Asia/Tokyo"))

        assertEquals(QueuedCapture("c-1", "recording_1000.wav", 1_000L, zoneId = "Asia/Tokyo"), queue.readQueue(strict = true).single())
    }

    @Test
    fun aRecordingWhoseSealThrowsAnErrorIsStillKept() = runBlocking<Unit> {
        crypto.failSealWithOutOfMemoryWhenAadContains = "queue/c-1"

        assertEquals(IngestResult.KEPT, ingest.ingest(audio, "wav"))

        assertContentEquals(audio, File(pendingDir, "c-1~recording_$now.wav").readBytes())
    }

    @Test
    fun theIngestFinishesWhenItsCallerIsCanceledWhileItWaitsForTheQueue() = runBlocking<Unit> {
        queue.enqueue("earlier", audio, "earlier.wav", 1L)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        crypto.beforeOpen = { location ->
            if (location == QueueStore.QUEUE_META_FILE && entered.count > 0) {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        // Holds the queue lock inside a read, so the ingest waits for the lock.
        val holder = async(Dispatchers.Default) { queue.update { it } }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val job = async(Dispatchers.Default) { ingest.ingest(audio, "wav") }
        delay(300)
        job.cancel()
        release.countDown()
        holder.await()
        withTimeout(10.seconds) { while (queue.readQueue().none { it.id == "c-1" }) delay(10) }
        assertContentEquals(audio, assertIs<BlobRead.Present>(queue.readBlob("c-1")).bytes)
        withTimeout(10.seconds) { while (queuedCalls.isEmpty()) delay(10) }
        assertEquals(1, queuedCalls.size)
    }
}
