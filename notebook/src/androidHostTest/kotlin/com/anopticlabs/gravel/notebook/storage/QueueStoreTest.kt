package com.anopticlabs.gravel.notebook.storage

import com.anopticlabs.gravel.notebook.capture.CaptureFailureReason
import com.anopticlabs.gravel.notebook.capture.MAX_QUEUE_ATTEMPTS
import com.anopticlabs.gravel.notebook.capture.QueuedCapture
import com.anopticlabs.gravel.notebook.capture.RecordingBuffer
import com.anopticlabs.gravel.notebook.capture.markQueuedCaptureFailed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueueStoreTest {

    private val root = Files.createTempDirectory("notebook").toFile()
    private val fake = FakeCryptographer(softwareCrypto())
    private var now = 1_700_000_000_000L
    private val store = QueueStore(root, fake) { now }
    private val files = EncryptedFiles(root, fake)
    private val metaFile = File(root, QueueStore.QUEUE_META_FILE)
    private val pendingDir = File(root, QueueStore.PENDING_DIR)
    private val audio = ByteArray(4096) { (it * 7 % 256).toByte() }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun blob(id: String) = File(root, "${QueueStore.QUEUE_DIR}/$id${QueueStore.BLOB_EXTENSION}")

    private fun quarantined() = files.quarantineDir.listFiles().orEmpty().map { it.name }

    private fun pending(id: String, name: String, bytes: ByteArray = audio): File =
        File(pendingDir.apply { mkdirs() }, "$id~$name").apply { writeBytes(bytes) }

    private fun corruptLastByte(file: File) {
        val bytes = file.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        file.writeBytes(bytes)
    }

    private fun sparseFile(file: File, length: Long): File = file.also { RandomAccessFile(it, "rw").use { f -> f.setLength(length) } }

    private suspend fun present(id: String): ByteArray = assertIs<BlobRead.Present>(store.readBlob(id)).bytes

    // Entries and blobs

    @Test
    fun enqueuePersistsTheEntryAndTheBlob() = runBlocking {
        assertTrue(store.enqueue("fg_1", audio, "recording_1.m4a", queuedAtMillis = 42L))

        assertEquals(listOf(QueuedCapture("fg_1", "recording_1.m4a", 42L)), store.readQueue())
        assertContentEquals(audio, present("fg_1"))
        assertEquals("queue/fg_1.bin", files.locationFor(blob("fg_1")).decodeToString())
        assertTrue(files.stagingDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun enqueueOfAQueuedIdKeepsTheEntryAndItsBlobAndWritesTheBlobOnlyWhenMissing() = runBlocking {
        store.enqueue("fg_1", audio, "first.m4a", queuedAtMillis = 1L)
        store.update { markQueuedCaptureFailed(it, "fg_1") }
        val other = byteArrayOf(9, 9, 9)

        assertFalse(store.enqueue("fg_1", other, "second.m4a", 2L))
        assertEquals(listOf(QueuedCapture("fg_1", "first.m4a", 1L, attempts = 1)), store.readQueue())
        assertContentEquals(audio, present("fg_1"))

        assertTrue(blob("fg_1").delete())
        assertTrue(store.enqueue("fg_1", other, "second.m4a", 2L))
        assertEquals(listOf(QueuedCapture("fg_1", "first.m4a", 1L, attempts = 1)), store.readQueue())
        assertContentEquals(other, present("fg_1"))
    }

    @Test
    fun aFailedEntryWriteDeletesTheBlobAndLeavesTheQueueAsItWas() = runBlocking {
        store.enqueue("kept", audio, "kept.m4a", 1L)
        fake.failSealWhenAadContains = QueueStore.QUEUE_META_FILE

        assertFailsWith<TransientCryptoException> { store.enqueue("fg_2", audio, "two.m4a", 2L) }

        assertFalse(blob("fg_2").exists())
        assertTrue(blob("kept").exists())
        fake.failSealWhenAadContains = null
        assertEquals(listOf("kept"), store.readQueue(strict = true).map { it.id })
    }

    @Test
    fun aStrictReadFailureBeforeTheBlobWriteWritesNothing() = runBlocking {
        store.enqueue("kept", audio, "kept.m4a", 1L)
        fake.failOpenWithTransient = true

        assertFailsWith<TransientCryptoException> { store.enqueue("fg_2", audio, "two.m4a", 2L) }
        assertFalse(blob("fg_2").exists())

        fake.failOpenWithTransient = false
        assertEquals(listOf("kept"), store.readQueue(strict = true).map { it.id })
    }

    @Test
    fun removeDropsTheEntryAndTheBlob() = runBlocking {
        store.enqueue("fg_1", audio, "one.m4a", 1L)
        store.enqueue("fg_2", audio, "two.m4a", 2L)

        assertEquals(listOf("fg_2"), store.remove("fg_1").map { it.id })

        assertEquals(listOf("fg_2"), store.readQueue().map { it.id })
        assertEquals(BlobRead.Absent, store.readBlob("fg_1"))
        assertTrue(blob("fg_2").exists())
        assertEquals(listOf("fg_2"), store.remove("missing").map { it.id })
    }

    @Test
    fun updateMarksAFailureInPlaceAndKeepsTheBlob() = runBlocking {
        store.enqueue("fg_1", audio, "one.m4a", 1L)

        val queue = store.update { markQueuedCaptureFailed(it, "fg_1") }

        assertEquals(listOf(QueuedCapture("fg_1", "one.m4a", 1L, attempts = 1)), queue)
        assertEquals(queue, store.readQueue())
        assertTrue(blob("fg_1").exists())
    }

    @Test
    fun updateRefusesAQueueTheReaderWouldRefuse() = runBlocking {
        store.enqueue("fg_1", audio, "one.m4a", 1L)
        val onDisk = metaFile.readBytes()

        assertFailsWith<IllegalArgumentException> { store.update { it + QueuedCapture("a/b", "x.m4a", 2L) } }
        assertFailsWith<IllegalArgumentException> { store.update { it + it.first() } }

        assertContentEquals(onDisk, metaFile.readBytes())
        assertEquals(listOf("fg_1"), store.readQueue(strict = true).map { it.id })
    }

    @Test
    fun aTransientDuringUpdateLeavesTheExistingQueueUntouched() = runBlocking {
        store.enqueue("existing", audio, "e.m4a", 1L)
        fake.failOpenWithTransient = true

        assertFailsWith<TransientCryptoException> { store.update { it + QueuedCapture("new", "n.m4a", 2L) } }
        assertFailsWith<TransientCryptoException> { store.remove("existing") }

        fake.failOpenWithTransient = false
        assertEquals(listOf("existing"), store.readQueue().map { it.id })
        assertTrue(blob("existing").exists())
    }

    // Blob reads

    @Test
    fun readBlobSeparatesPresentAbsentAndCorruptAndQuarantinesACorruptBlob() = runBlocking {
        store.enqueue("fg_1", audio, "one.m4a", 1L)
        assertEquals(BlobRead.Absent, store.readBlob("never"))
        corruptLastByte(blob("fg_1"))

        assertEquals(BlobRead.Corrupt, store.readBlob("fg_1"))

        assertFalse(blob("fg_1").exists())
        assertEquals(listOf("queue__fg_1.bin.$now"), quarantined())
        assertEquals(listOf("fg_1"), store.readQueue(strict = true).map { it.id })
        assertEquals(BlobRead.Absent, store.readBlob("fg_1"))
    }

    // Failed captures

    @Test
    fun aDamagedFailedCaptureGoesWithItsQuarantinedCopiesAndNoOtherCapture() = runBlocking {
        store.enqueue("fg_1", audio, "one.m4a", 1L)
        store.enqueue("fg_10", audio, "ten.m4a", 2L)
        corruptLastByte(blob("fg_1"))
        corruptLastByte(blob("fg_10"))
        assertEquals(BlobRead.Corrupt, store.readBlob("fg_1"))
        assertEquals(BlobRead.Corrupt, store.readBlob("fg_10"))
        File(files.quarantineDir, "queue__fg_1.bin.$now.1").writeBytes(byteArrayOf(1))
        store.update { q -> q.map { it.copy(attempts = MAX_QUEUE_ATTEMPTS, flagged = true, failure = CaptureFailureReason.DAMAGED.code) } }

        assertEquals(QueueStore.FailedRemoval.REMOVED, store.removeFailed("fg_1"))

        assertEquals(listOf("fg_10"), store.readQueue(strict = true).map { it.id })
        assertEquals(listOf("queue__fg_10.bin.$now"), quarantined())
    }

    @Test
    fun onlyAFlaggedEntryIsRemovedAsFailedAndItsQuarantinedCopyGoesWhateverItsReason() = runBlocking {
        store.enqueue("fg_1", audio, "one.m4a", 1L)
        val ownCopy = File(files.quarantineDir.apply { mkdirs() }, "queue__fg_1.bin.1").apply { writeBytes(byteArrayOf(1)) }

        assertEquals(QueueStore.FailedRemoval.NOT_FLAGGED, store.removeFailed("fg_1"))
        assertTrue(blob("fg_1").exists())
        assertTrue(ownCopy.exists())

        store.update { q -> q.map { it.copy(attempts = MAX_QUEUE_ATTEMPTS, flagged = true, failure = CaptureFailureReason.NO_SPEECH.code) } }
        assertEquals(CaptureFailureReason.NO_SPEECH.code, store.readQueue(strict = true).single().failure)
        assertEquals(QueueStore.FailedRemoval.NOT_FLAGGED, store.removeFailed("fg_1") { it == "fg_1" })
        assertTrue(ownCopy.exists())
        assertEquals(QueueStore.FailedRemoval.REMOVED, store.removeFailed("fg_1"))
        assertEquals(QueueStore.FailedRemoval.GONE, store.removeFailed("fg_1"))

        assertTrue(store.readQueue(strict = true).isEmpty())
        assertFalse(blob("fg_1").exists())
        assertFalse(ownCopy.exists())
    }

    @Test
    fun aDamagedEntryOutlivesItsQuarantinedCopyAndIsStillRemovedAsFailed() = runBlocking {
        store.enqueue("fg_1", audio, "one.m4a", 1L)
        corruptLastByte(blob("fg_1"))
        assertEquals(BlobRead.Corrupt, store.readBlob("fg_1"))
        store.update { q -> q.map { it.copy(attempts = MAX_QUEUE_ATTEMPTS, flagged = true, failure = CaptureFailureReason.DAMAGED.code) } }
        assertEquals(listOf("queue__fg_1.bin.$now"), quarantined())

        files.sweepQuarantine(now = now + EncryptedFiles.QUARANTINE_MAX_AGE_MS)

        assertTrue(quarantined().isEmpty())
        assertEquals(listOf("fg_1"), store.readQueue(strict = true).map { it.id })
        assertEquals(QueueStore.FailedRemoval.REMOVED, store.removeFailed("fg_1"))
        assertTrue(store.readQueue(strict = true).isEmpty())
    }

    @Test
    fun aQuarantinedCopyThatCannotBeDeletedKeepsTheEntry() = runBlocking {
        store.enqueue("fg_1", audio, "one.m4a", 1L)
        File(files.quarantineDir, "queue__fg_1.bin.1").apply { mkdirs() }.resolve("inside").writeBytes(byteArrayOf(1))
        store.update { q -> q.map { it.copy(attempts = MAX_QUEUE_ATTEMPTS, flagged = true, failure = CaptureFailureReason.DAMAGED.code) } }

        assertFailsWith<IOException> { store.removeFailed("fg_1") }

        assertEquals(listOf("fg_1"), store.readQueue(strict = true).map { it.id })
        assertTrue(blob("fg_1").exists())
    }

    @Test
    fun aRetryOfFailedCapturesSkipsOneWithoutItsRecordingAndSaysWhetherAnyChanged() = runBlocking {
        store.enqueue(QueuedCapture("fg_1", "one.m4a", 1L, MAX_QUEUE_ATTEMPTS, true, failure = CaptureFailureReason.NO_SPEECH.code), audio)

        store.enqueue(QueuedCapture("gone", "two.m4a", 2L, MAX_QUEUE_ATTEMPTS, true), audio)
        blob("gone").delete()

        assertFalse(store.retryFailed(setOf("fg_1")) { it == "fg_1" })
        assertTrue(store.retryFailed(setOf("fg_1", "gone")))

        assertEquals(listOf(QueuedCapture("fg_1", "one.m4a", 1L), QueuedCapture("gone", "two.m4a", 2L, MAX_QUEUE_ATTEMPTS, true)), store.readQueue(strict = true))
        assertFalse(store.retryFailed(setOf("fg_1", "gone")))
        assertTrue(store.hasBlob("fg_1"))
        assertFalse(store.hasBlob("gone"))
    }

    @Test
    fun readBlobRethrowsATransientOrMissingKeyAndLeavesTheBlob() = runBlocking {
        store.enqueue("fg_1", audio, "one.m4a", 1L)

        fake.failOpenWithTransient = true
        assertFailsWith<TransientCryptoException> { store.readBlob("fg_1") }
        fake.failOpenWithTransient = false
        fake.failOpenWithKeyUnavailable = true
        assertFailsWith<KeyUnavailableException> { store.readBlob("fg_1") }
        fake.failOpenWithKeyUnavailable = false

        assertTrue(blob("fg_1").exists())
        assertTrue(quarantined().isEmpty())
    }

    @Test
    fun aBlobFileOverTheCapIsCorruptWithoutBeingRead() = runBlocking {
        store.enqueue("fg_1", audio, "one.m4a", 1L)
        sparseFile(blob("fg_1"), QueueStore.MAX_BLOB_BYTES + NotebookEnvelope.MIN_LEN + 1L)

        assertEquals(BlobRead.Corrupt, store.readBlob("fg_1"))

        assertFalse(blob("fg_1").exists())
        assertEquals(listOf("queue__fg_1.bin.$now"), quarantined())
    }

    @Test
    fun readBlobWaitsForTheQueueLock() = runBlocking {
        store.enqueue("fg_1", audio, "one.m4a", 1L)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.IO) {
            store.update { entered.complete(Unit); runBlocking { release.await() }; it }
        }
        entered.await()
        val read = async(Dispatchers.IO) { store.readBlob("fg_1") }
        delay(200)
        assertFalse(read.isCompleted)
        release.complete(Unit)
        assertContentEquals(audio, assertIs<BlobRead.Present>(withTimeout(5_000) { read.await() }).bytes)
        holder.join()
    }

    // The queue file

    @Test
    fun aCorruptQueueFileFailsEveryMutatorClosedAndIsNeverQuarantined() = runBlocking {
        store.enqueue("fg_1", audio, "one.m4a", 1L)
        blob("orphan").also { files.write(it, audio); it.setLastModified(now - QueueStore.ORPHAN_SWEEP_MIN_AGE_MS) }
        corruptLastByte(metaFile)
        val onDisk = metaFile.readBytes()

        assertEquals(emptyList(), store.readQueue())
        assertFailsWith<CorruptFileException> { store.readQueue(strict = true) }
        assertFailsWith<CorruptFileException> { store.enqueue("fg_2", audio, "two.m4a", 2L) }
        assertFailsWith<CorruptFileException> { store.update { emptyList() } }
        assertFailsWith<CorruptFileException> { store.remove("fg_1") }
        assertEquals(0, store.sweepOrphanBlobs())

        assertFalse(blob("fg_2").exists())
        assertTrue(blob("fg_1").exists())
        assertTrue(blob("orphan").exists())
        assertContentEquals(onDisk, metaFile.readBytes())
        assertTrue(quarantined().isEmpty())
    }

    @Test
    fun aQueueFileOverTheCapIsCorruptWithoutBeingRead() = runBlocking {
        store.enqueue("fg_1", audio, "one.m4a", 1L)
        sparseFile(metaFile, QueueStore.MAX_QUEUE_META_BYTES + 1)

        assertEquals(emptyList(), store.readQueue())
        assertFailsWith<CorruptFileException> { store.readQueue(strict = true) }
        assertEquals(QueueStore.MAX_QUEUE_META_BYTES + 1, metaFile.length())
        assertTrue(quarantined().isEmpty())
    }

    @Test
    fun anEntryWithAnInvalidOrDuplicatedIdIsAFormatFailure() = runBlocking {
        for (json in listOf(
            """[{"id":"../x","originalFileName":"x.m4a","queuedAtMillis":1}]""",
            """[{"id":"a","originalFileName":"a.m4a","queuedAtMillis":1},{"id":"a","originalFileName":"b.m4a","queuedAtMillis":2}]""",
            """{"not":"a list"}""",
        )) {
            files.writeText(metaFile, json)
            assertEquals(emptyList(), store.readQueue(), json)
            assertFailsWith<PersistedFormatException>(json) { store.readQueue(strict = true) }
            assertFailsWith<PersistedFormatException>(json) { store.enqueue("fg_1", audio, "one.m4a", 1L) }
            assertFalse(blob("fg_1").exists(), json)
        }
    }

    // The orphan sweep

    @Test
    fun theSweepDeletesOnlyOldBlobsWithoutAnEntry() = runBlocking {
        val gate = QueueStore.ORPHAN_SWEEP_MIN_AGE_MS
        store.enqueue("listed", audio, "l.m4a", 1L)
        blob("listed").setLastModified(now - gate - 1_000)
        blob("fresh").also { files.write(it, audio); it.setLastModified(now - gate + 1_000) }
        blob("old").also { files.write(it, audio); it.setLastModified(now - gate) }
        val other = File(blob("x").parentFile, "old.tmp").also { it.writeBytes(audio); it.setLastModified(now - gate - 1_000) }
        val dir = File(blob("x").parentFile, "sub${QueueStore.BLOB_EXTENSION}").also { it.mkdirs(); it.setLastModified(now - gate - 1_000) }

        assertEquals(1, store.sweepOrphanBlobs())

        assertTrue(blob("listed").exists())
        assertTrue(blob("fresh").exists())
        assertFalse(blob("old").exists())
        assertTrue(other.exists())
        assertTrue(dir.isDirectory)
        assertEquals(0, store.sweepOrphanBlobs())
    }

    @Test
    fun theSweepSkipsWhenTheQueueFileIsAbsentOrCannotBeRead() = runBlocking {
        val old = now - QueueStore.ORPHAN_SWEEP_MIN_AGE_MS
        blob("before").also { files.write(it, audio); it.setLastModified(old) }
        assertFalse(metaFile.exists())

        assertEquals(0, store.sweepOrphanBlobs())
        assertTrue(blob("before").exists())

        store.enqueue("listed", audio, "l.m4a", 1L)
        fake.failOpenWithTransient = true
        assertEquals(0, store.sweepOrphanBlobs())
        fake.failOpenWithTransient = false
        fake.failOpenWithKeyUnavailable = true
        assertEquals(0, store.sweepOrphanBlobs())
        fake.failOpenWithKeyUnavailable = false

        assertTrue(blob("before").exists())
        assertTrue(blob("listed").exists())
        assertEquals(1, store.sweepOrphanBlobs())
        assertFalse(blob("before").exists())
    }

    @Test
    fun sweepsRunningBesideEnqueuesNeverDeleteAQueuedBlob() = runBlocking {
        val a = QueueStore(root, fake) { now }
        val b = QueueStore(root, fake) { now }
        val n = 30
        coroutineScope {
            for (i in 0 until n) {
                launch(Dispatchers.IO) { a.enqueue("a-$i", audio, "a.m4a", 1L) }
                launch(Dispatchers.IO) { b.sweepOrphanBlobs(minAgeMillis = 0, now = Long.MAX_VALUE / 2) }
            }
        }
        val queue = a.readQueue(strict = true)
        assertEquals(n, queue.size)
        for (i in 0 until n) assertTrue(blob("a-$i").exists(), "blob a-$i")
        assertTrue(files.stagingDir.listFiles().orEmpty().isEmpty())
    }

    // Ids and caps

    @Test
    fun onlyAPlainIdIsAccepted() = runBlocking {
        for (id in listOf("", "../x", "a/b", "a.b", "a b", "a~b", "x".repeat(129), "é")) {
            assertFailsWith<IllegalArgumentException>(id) { store.enqueue(id, audio, "x.m4a", 1L) }
            assertFailsWith<IllegalArgumentException>(id) { store.readBlob(id) }
            assertFailsWith<IllegalArgumentException>(id) { store.remove(id) }
            assertFailsWith<IllegalArgumentException>(id) { store.retainPending(pending("tmp", "tmp.m4a"), id) }
            assertFalse(File(root, QueueStore.QUEUE_DIR).exists(), id)
        }
        for (id in listOf("a", "x".repeat(128), "7c1e-4b2a_Z")) assertTrue(QueueStore.isValidId(id), id)
        assertEquals(emptyList(), store.readQueue(strict = true))
    }

    @Test
    fun anOverCapCaptureIsRefusedBeforeAnythingIsWritten() = runBlocking {
        assertFailsWith<IllegalArgumentException> { store.enqueue("big", ByteArray(QueueStore.MAX_BLOB_BYTES + 1), "big.m4a", 1L) }
        assertFalse(blob("big").exists())
        assertFalse(metaFile.exists())
    }

    // Pending plaintext captures

    @Test
    fun retainPendingMovesTheFileUnderItsIdAndTheRecordingsName() = runBlocking {
        val cache = Files.createTempDirectory("cache").toFile()
        val first = File(cache, "recording_1.m4a").apply { writeBytes(audio) }

        assertEquals(File(pendingDir, "7c1e~recording_1.m4a"), store.retainPending(first, "7c1e"))

        assertFalse(first.exists())
        assertContentEquals(audio, File(pendingDir, "7c1e~recording_1.m4a").readBytes())
        assertTrue(cache.deleteRecursively())
    }

    @Test
    fun retainPendingRefusesASecondFileUnderAPendingIdAndLeavesItWhereItIs() = runBlocking {
        val cache = Files.createTempDirectory("cache").toFile()
        val first = File(cache, "recording_1.m4a").apply { writeBytes(audio) }
        val second = File(cache, "recording_2.m4a").apply { writeBytes(byteArrayOf(1)) }
        val sameName = File(cache, "recording_1.m4a")
        assertNotNull(store.retainPending(first, "7c1e"))

        assertNull(store.retainPending(second, "7c1e"))
        sameName.writeBytes(byteArrayOf(2))
        assertNull(store.retainPending(sameName, "7c1e"))

        assertTrue(second.exists())
        assertTrue(sameName.exists())
        assertEquals(listOf("7c1e~recording_1.m4a"), pendingDir.list().orEmpty().toList())
        assertContentEquals(audio, File(pendingDir, "7c1e~recording_1.m4a").readBytes())
        assertTrue(cache.deleteRecursively())
    }

    @Test
    fun retainPendingRefusesAnOddNameAnOverCapFileAndAMissingFile() = runBlocking {
        val cache = Files.createTempDirectory("cache").toFile()
        val odd = File(cache, "rec~1.m4a").apply { writeBytes(audio) }
        val dotted = File(cache, ".hidden.m4a").apply { writeBytes(audio) }
        val long = File(cache, "r".repeat(101)).apply { writeBytes(audio) }
        val big = sparseFile(File(cache, "big.m4a"), QueueStore.MAX_BLOB_BYTES + 1L)

        assertNull(store.retainPending(odd, "a"))
        assertNull(store.retainPending(dotted, "b"))
        assertNull(store.retainPending(long, "c"))
        assertNull(store.retainPending(big, "d"))
        assertNull(store.retainPending(File(cache, "gone.m4a"), "e"))

        assertTrue(odd.exists() && dotted.exists() && long.exists() && big.exists())
        assertTrue(pendingDir.list().orEmpty().isEmpty())
        assertNotNull(store.retainPending(File(cache, "r".repeat(100)).apply { writeBytes(audio) }, "f"))
        assertTrue(cache.deleteRecursively())
    }

    @Test
    fun onlyKeptWatchRecordingsCountAsWatchRecordings() = runBlocking {
        pending("id_a", "watch_1000.wav")
        pending("id_b", "recording_2000.wav")
        pending("id_c", "watch_3000.wav", ByteArray(0))
        assertEquals(1, store.retainedWatchCount())
        assertEquals(2, store.retainedCount())
    }

    @Test
    fun salvageDatesARecordingAtTheStartItsNameCarries() = runBlocking {
        pending("id_a", "recording_1699999990000.wav").apply { setLastModified(now - 1_000) }
        store.salvagePending()
        assertEquals(1_699_999_990_000L, store.readQueue(strict = true).single().queuedAtMillis)
    }

    @Test
    fun salvageQueuesEachPendingFileDeletesThePlaintextAndDedupsOnARerun() = runBlocking {
        val recorded = now - 3 * 24 * 3_600_000L
        val a = pending("id_a", "recording_a.m4a").apply { setLastModified(recorded) }
        val b = pending("id_b", "recording_b.pcm", byteArrayOf(5, 6, 7)).apply { setLastModified(recorded + 1) }

        assertEquals(SalvageResult(listOf("id_a", "id_b"), 0), store.salvagePending())

        assertEquals(
            listOf(QueuedCapture("id_a", "recording_a.m4a", recorded), QueuedCapture("id_b", "recording_b.pcm", recorded + 1)),
            store.readQueue(strict = true),
        )
        assertContentEquals(audio, present("id_a"))
        assertContentEquals(byteArrayOf(5, 6, 7), present("id_b"))
        assertFalse(a.exists())
        assertFalse(b.exists())

        pending("id_a", "recording_a.m4a")
        assertEquals(SalvageResult(listOf("id_a"), 0), store.salvagePending())
        assertEquals(listOf("id_a", "id_b"), store.readQueue(strict = true).map { it.id })
        assertTrue(pendingDir.listFiles().orEmpty().isEmpty())
        assertEquals(SalvageResult(emptyList(), 0), store.salvagePending())
    }

    @Test
    fun salvageKeepsAFileWhoseBytesDifferFromTheQueuedBlobOfItsId() = runBlocking {
        store.enqueue("id_a", audio, "first.m4a", 1L)
        val other = pending("id_a", "second.m4a", byteArrayOf(1, 2, 3))

        assertEquals(SalvageResult(emptyList(), 1), store.salvagePending())

        assertTrue(other.exists())
        assertContentEquals(byteArrayOf(1, 2, 3), other.readBytes())
        assertContentEquals(audio, present("id_a"))
        assertEquals(listOf(QueuedCapture("id_a", "first.m4a", 1L)), store.readQueue(strict = true))

        assertTrue(blob("id_a").delete())
        assertEquals(SalvageResult(listOf("id_a"), 0), store.salvagePending())
        assertFalse(other.exists())
        assertContentEquals(byteArrayOf(1, 2, 3), present("id_a"))
    }

    @Test
    fun salvageLeavesAFileWhoseSealStillFailsAndKeepsNoHalfState() = runBlocking {
        val stuck = pending("id_s", "recording_stuck.m4a")
        fake.failSealWhenAadContains = "${QueueStore.QUEUE_DIR}/"

        assertEquals(SalvageResult(emptyList(), 1), store.salvagePending())

        assertTrue(stuck.exists())
        assertContentEquals(audio, stuck.readBytes())
        assertFalse(blob("id_s").exists())
        fake.failSealWhenAadContains = null
        assertEquals(emptyList(), store.readQueue(strict = true))

        fake.failSealWhenAadContains = QueueStore.QUEUE_META_FILE
        assertEquals(SalvageResult(emptyList(), 1), store.salvagePending())
        assertTrue(stuck.exists())
        assertFalse(blob("id_s").exists())
        fake.failSealWhenAadContains = null
        assertEquals(SalvageResult(listOf("id_s"), 0), store.salvagePending())
    }

    @Test
    fun salvageDeletesAnEmptyFileKeepsAnOverCapOneUnreadAndLeavesEverythingItDoesNotRecognize() = runBlocking {
        val empty = pending("id_e", "recording_empty.m4a", ByteArray(0))
        val big = sparseFile(File(pendingDir, "id_big~recording_big.m4a"), QueueStore.MAX_BLOB_BYTES + 1L)
        val noSeparator = File(pendingDir, "recording.m4a").apply { writeBytes(audio) }
        val badId = File(pendingDir, "a.b~recording.m4a").apply { writeBytes(audio) }
        val badName = File(pendingDir, "id_n~rec~ording.m4a").apply { writeBytes(audio) }
        val dir = File(pendingDir, "id_d~subdir").also { it.mkdirs() }
        val target = File(root, "target.m4a").apply { writeBytes(audio) }
        val link = Files.createSymbolicLink(File(pendingDir, "id_l~link.m4a").toPath(), target.toPath()).toFile()

        assertEquals(SalvageResult(emptyList(), 1), store.salvagePending())

        assertFalse(empty.exists())
        assertTrue(big.exists() && noSeparator.exists() && badId.exists() && badName.exists() && dir.isDirectory)
        assertTrue(Files.isSymbolicLink(link.toPath()) && target.exists())
        assertEquals(emptyList(), store.readQueue(strict = true))
        assertFalse(File(root, QueueStore.QUEUE_DIR).exists())
    }

    @Test
    fun retainPendingOfBytesLeavesOnlyTheCompleteFileUnderItsFinalName() = runBlocking {
        assertEquals(File(pendingDir, "7c1e~recording_1.wav"), store.retainPending(audio, "7c1e", "recording_1.wav"))

        assertEquals(listOf("7c1e~recording_1.wav"), pendingDir.list().orEmpty().toList())
        assertContentEquals(audio, File(pendingDir, "7c1e~recording_1.wav").readBytes())
        assertEquals(1, store.retainedCount())
    }

    @Test
    fun retainPendingOfBytesRefusesAPendingIdAnOddNameAndAnOverCapRecording() = runBlocking {
        assertNotNull(store.retainPending(audio, "7c1e", "recording_1.wav"))

        assertNull(store.retainPending(byteArrayOf(1), "7c1e", "recording_2.wav"))
        assertNull(store.retainPending(audio, "a", "rec~1.wav"))
        assertNull(store.retainPending(audio, "b", ".hidden.wav"))
        assertNull(store.retainPending(ByteArray(QueueStore.MAX_BLOB_BYTES + 1), "c", "big.wav"))

        assertEquals(listOf("7c1e~recording_1.wav"), pendingDir.list().orEmpty().toList())
        assertContentEquals(audio, File(pendingDir, "7c1e~recording_1.wav").readBytes())
    }

    @Test
    fun aRecordingKeptFromMemoryIsQueuedByTheNextSalvage() = runBlocking {
        store.retainPending(audio, "7c1e", "recording_1.wav")

        assertEquals(listOf("7c1e"), store.salvagePending().queued)
        assertContentEquals(audio, present("7c1e"))
        assertEquals("recording_1.wav", store.readQueue(strict = true).single().originalFileName)
        assertTrue(pendingDir.list().orEmpty().isEmpty())
    }

    @Test
    fun salvageDeletesAPartialFileOnceItIsOldAndNeverQueuesOrCountsOne() = runBlocking {
        val old = File(pendingDir.apply { mkdirs() }, ".part-old~recording_1.wav").apply {
            writeBytes(audio)
            setLastModified(now - QueueStore.ORPHAN_SWEEP_MIN_AGE_MS)
        }
        val young = File(pendingDir, ".part-young~recording_2.wav").apply {
            writeBytes(audio)
            setLastModified(now - QueueStore.ORPHAN_SWEEP_MIN_AGE_MS + 1_000)
        }

        assertEquals(SalvageResult(emptyList(), 0), store.salvagePending())

        assertFalse(old.exists())
        assertTrue(young.exists())
        assertEquals(0, store.retainedCount())
        assertEquals(emptyList(), store.readQueue(strict = true))
    }

    @Test
    fun salvageStampsNowWhenTheFileTimeIsNotUsable() = runBlocking {
        pending("id_f", "future.m4a").apply { setLastModified(now + 60_000) }
        pending("id_z", "zero.m4a").apply { setLastModified(0) }
        store.salvagePending()
        assertEquals(listOf(now, now), store.readQueue(strict = true).map { it.queuedAtMillis })
    }

    @Test
    fun twoInstancesEnqueueingConcurrentlyLoseNoEntryOrBlob() = runBlocking {
        val a = QueueStore(root, fake) { now }
        val b = QueueStore(root, fake) { now }
        val n = 30
        coroutineScope {
            for (i in 0 until n) {
                launch(Dispatchers.IO) { a.enqueue("a-$i", audio, "a.m4a", 1L) }
                launch(Dispatchers.IO) { b.enqueue("b-$i", audio, "b.m4a", 1L) }
                launch(Dispatchers.IO) { if (i % 3 == 0) a.remove("b-$i") }
            }
        }
        val ids = a.readQueue(strict = true).map { it.id }.toSet()
        for (i in 0 until n) {
            assertTrue("a-$i" in ids, "entry a-$i")
            assertNotNull(store.readBlob("a-$i") as? BlobRead.Present, "blob a-$i")
            assertEquals("b-$i" in ids, blob("b-$i").exists(), "b-$i entry and blob agree")
        }
        assertTrue(files.stagingDir.listFiles().orEmpty().isEmpty())
        assertTrue(quarantined().isEmpty())
    }

    // Rebuilding a damaged queue file

    private val wav = RecordingBuffer().apply { append(ShortArray(16_000), 16_000) }.toWav()

    @Test
    fun anEntryQueuedByIdTakesTheLengthOfAWavFile() = runBlocking<Unit> {
        store.enqueue("w", wav, "w.wav")
        store.enqueue("m", audio, "m.m4a")
        assertEquals(listOf(1_000L, null), store.readQueue(strict = true).map { it.durationMillis })
    }

    @Test
    fun aRebuiltEntryTakesTheLengthOfAWavFile() = runBlocking<Unit> {
        store.enqueue(QueuedCapture("w", "w.wav", now), wav)
        corruptLastByte(metaFile)
        assertEquals(1, store.rebuildIfDamaged())
        assertEquals(1_000L, store.readQueue(strict = true).single().durationMillis)
    }

    @Test
    fun aDamagedQueueFileIsRebuiltFromTheBlobsThatOpenAndACorruptBlobIsSetAside() = runBlocking<Unit> {
        store.enqueue(QueuedCapture("b", "second.m4a", now, attempts = 2, flagged = true), audio)
        store.enqueue(QueuedCapture("a", "first.m4a", now), audio)
        store.enqueue(QueuedCapture("c", "third.m4a", now), audio)
        blob("a").setLastModified(1_000_000L)
        blob("b").setLastModified(2_000_000L)
        corruptLastByte(blob("c"))
        File(root, "${QueueStore.QUEUE_DIR}/not-a-blob.txt").writeText("x")
        corruptLastByte(metaFile)

        assertEquals(2, store.rebuildIfDamaged())

        assertEquals(
            listOf(QueuedCapture("a", QueueStore.RECOVERED_NAME, 1_000_000L), QueuedCapture("b", QueueStore.RECOVERED_NAME, 2_000_000L)),
            store.readQueue(strict = true),
        )
        assertContentEquals(audio, present("a"))
        assertFalse(blob("c").exists())
        assertEquals(2, quarantined().size)
        assertTrue(quarantined().any { it.startsWith(QueueStore.QUEUE_META_FILE + ".") })
    }

    @Test
    fun aRebuiltEntryWithoutAUsableFileTimeIsQueuedNow() = runBlocking<Unit> {
        store.enqueue(QueuedCapture("a", "first.m4a", now), audio)
        store.enqueue(QueuedCapture("b", "second.m4a", now), audio)
        blob("a").setLastModified(0L)
        blob("b").setLastModified(now + 86_400_000L)
        corruptLastByte(metaFile)

        assertEquals(2, store.rebuildIfDamaged())

        assertEquals(listOf(now, now), store.readQueue(strict = true).map { it.queuedAtMillis })
    }

    @Test
    fun aQueueFileThatReadsIsNotRebuilt() = runBlocking<Unit> {
        store.enqueue(QueuedCapture("a", "first.m4a", now), audio)
        val before = metaFile.readBytes()
        assertNull(store.rebuildIfDamaged())
        assertContentEquals(before, metaFile.readBytes())
    }

    @Test
    fun aBlobThatCannotBeReadThisTimeLeavesTheDamagedQueueFileInPlace() = runBlocking<Unit> {
        store.enqueue(QueuedCapture("a", "first.m4a", now), audio)
        files.writeText(metaFile, "not json")
        val damaged = metaFile.readBytes()
        fake.failOpenWhenAadContains = QueueStore.QUEUE_DIR + "/"
        assertFailsWith<TransientCryptoException> { store.rebuildIfDamaged() }
        assertContentEquals(damaged, metaFile.readBytes())
        assertTrue(blob("a").exists())
        assertTrue(quarantined().isEmpty())
    }

    @Test
    fun aDamagedQueueFileWithNoRecordingsDirectoryIsRebuiltEmpty() = runBlocking<Unit> {
        files.writeText(metaFile, "not json")
        assertFalse(File(root, QueueStore.QUEUE_DIR).exists())
        assertEquals(0, store.rebuildIfDamaged())
        assertEquals(emptyList(), store.readQueue(strict = true))

        files.writeText(metaFile, "not json")
        File(root, QueueStore.QUEUE_DIR).writeText("a file where the directory goes")
        assertEquals(0, store.rebuildIfDamaged())
        assertEquals(emptyList(), store.readQueue(strict = true))
    }

    @Test
    fun aRecordingsDirectoryThatCannotBeListedLeavesTheDamagedQueueFileAndTheRecordings() = runBlocking<Unit> {
        store.enqueue(QueuedCapture("a", "first.m4a", now), audio)
        files.writeText(metaFile, "not json")
        val damaged = metaFile.readBytes()
        val dir = File(root, QueueStore.QUEUE_DIR)
        try {
            dir.setReadable(false)
            assumeTrue("needs a user that file permissions bind", dir.listFiles() == null)
            assertFailsWith<TransientCryptoException> { store.rebuildIfDamaged() }
        } finally {
            dir.setReadable(true)
        }
        assertContentEquals(damaged, metaFile.readBytes())
        assertTrue(quarantined().isEmpty())
        assertEquals(0, store.sweepOrphanBlobs(minAgeMillis = 0, now = Long.MAX_VALUE))
        assertTrue(blob("a").exists())
    }
}
