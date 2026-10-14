package com.anopticlabs.gravel.notebook.pipeline

import com.anopticlabs.gravel.notebook.alarms.FakeNotifier
import com.anopticlabs.gravel.notebook.alarms.FakeScheduler
import com.anopticlabs.gravel.notebook.alarms.FakeSettings
import com.anopticlabs.gravel.notebook.alarms.ReminderAlarms
import com.anopticlabs.gravel.notebook.backup.BackupStaging
import com.anopticlabs.gravel.notebook.capture.CaptureStage
import com.anopticlabs.gravel.notebook.reminders.ReminderRefreshSignal
import com.anopticlabs.gravel.notebook.storage.EncryptedFiles
import com.anopticlabs.gravel.notebook.storage.FakeCryptographer
import com.anopticlabs.gravel.notebook.storage.NoteStore
import com.anopticlabs.gravel.notebook.storage.NotebookHousekeeping
import com.anopticlabs.gravel.notebook.storage.QueueStore
import com.anopticlabs.gravel.notebook.storage.ReminderEventStore
import com.anopticlabs.gravel.notebook.storage.ReminderStore
import com.anopticlabs.gravel.notebook.storage.VoiceCompletionStore
import com.anopticlabs.gravel.notebook.storage.softwareCrypto
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The coordinator as the app builds it: housekeeping, then the drain, on stores in a temporary directory. */
class CaptureCoordinatorRunTest {

    private val root = Files.createTempDirectory("notebook").toFile()
    private val crypto = FakeCryptographer(softwareCrypto())
    private val queue = QueueStore(root, crypto)
    private val notes = NoteStore(root, crypto)
    private val reminders = ReminderStore(root, crypto)
    private val now = 1_790_089_200_000L
    private val publisher = CapturePublisher(
        notes, reminders, VoiceCompletionStore(root, crypto),
        ReminderAlarms(reminders, ReminderEventStore(root, crypto), FakeScheduler(), FakeNotifier(), FakeSettings(), ReminderRefreshSignal(), { now }, { TimeZone.UTC }),
        FakeNotifier(), ReminderRefreshSignal(), { 30 }, { true }, { now }, { "id" },
    )
    private val backend = object : CaptureBackend {
        override suspend fun checkReady() = throw CaptureProcessingException(CaptureFailureKind.HOLD, "not set up")
        override suspend fun process(request: CaptureRequest, onStage: (CaptureStage) -> Unit): BuiltCapture = error("unused")
    }
    private val rows = CaptureRows()
    private val drain = CaptureDrain(queue, notes, backend, publisher, rows, { CaptureSettings() })
    private val keepAlive = object : CaptureKeepAlive {
        override fun hold() = Unit
        override fun extend() = Unit
        override fun release() = Unit
    }
    private val coordinator = CaptureCoordinator(drain, NotebookHousekeeping(notes, queue, BackupStaging(root)) {}, rows, keepAlive)
    private val files = EncryptedFiles(root, crypto)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun staleStagingFile() =
        File(files.stagingDir.apply { mkdirs() }, "x.tmp").apply { writeBytes(byteArrayOf(1)); setLastModified(System.currentTimeMillis() - 600_000L) }

    private suspend fun runOnce() {
        coordinator.request()
        withTimeout(10.seconds) { while (coordinator.working.value || drain.passes.value == 0) delay(10) }
    }

    /** The queue is empty until the salvage, so a drain that ran first would find nothing to hold on. */
    @Test
    fun eachRunDrainsAfterItsHousekeepingAndOnlyTheFirstSweeps() = runBlocking<Unit> {
        File(root, "${QueueStore.PENDING_DIR}/a~a.m4a").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }
        val first = staleStagingFile()
        runOnce()
        assertFalse(first.exists())
        assertEquals(listOf("a"), queue.readQueue(strict = true).map { it.id })
        assertEquals("not set up", drain.held.value?.reason)
        assertEquals(1, drain.passes.value)

        val second = staleStagingFile()
        coordinator.request()
        withTimeout(10.seconds) { while (drain.passes.value < 2) delay(10) }
        withTimeout(10.seconds) { while (coordinator.working.value) delay(10) }
        assertTrue(second.exists())
    }
}
