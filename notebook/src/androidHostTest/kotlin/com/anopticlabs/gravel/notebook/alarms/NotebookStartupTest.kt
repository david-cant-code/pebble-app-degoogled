package com.anopticlabs.gravel.notebook.alarms

import com.anopticlabs.gravel.notebook.AppVisibility
import com.anopticlabs.gravel.notebook.capture.CaptureQueueItem
import com.anopticlabs.gravel.notebook.pipeline.CaptureCoordinator
import com.anopticlabs.gravel.notebook.pipeline.CaptureKeepAlive
import com.anopticlabs.gravel.notebook.reminders.ReminderItem
import com.anopticlabs.gravel.notebook.reminders.ReminderRefreshSignal
import com.anopticlabs.gravel.notebook.sources.RecorderHarness
import com.anopticlabs.gravel.notebook.storage.FakeCryptographer
import com.anopticlabs.gravel.notebook.storage.ReminderEventStore
import com.anopticlabs.gravel.notebook.storage.ReminderStore
import com.anopticlabs.gravel.notebook.storage.softwareCrypto
import com.anopticlabs.gravel.notebook.ui.NotebookLinkToken
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class NotebookStartupTest {

    private val root = Files.createTempDirectory("notebook").toFile()
    private val crypto = FakeCryptographer(softwareCrypto())
    private val reminders = ReminderStore(root, crypto)
    private val now = 1_790_089_200_000L
    private val scheduler = FakeScheduler()

    /** "backups" when the backups' watch is started, "sweep" at each read of the re-arm's clock. */
    private val order = CopyOnWriteArrayList<String>()
    private val alarms = ReminderAlarms(
        reminders, ReminderEventStore(root, crypto), scheduler, FakeNotifier(), FakeSettings(), ReminderRefreshSignal(),
        { order += "sweep"; now }, { TimeZone.UTC },
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val rows = MutableStateFlow<List<CaptureQueueItem>>(emptyList())
    private val held = mutableListOf<String>()
    private val keepAlive = object : CaptureKeepAlive {
        override fun hold() { held += "hold" }
        override fun extend() = Unit
        override fun release() { held += "release" }
    }
    private val runs = mutableListOf<Boolean>()
    private val gate = CompletableDeferred<Unit>()
    private val coordinator = CaptureCoordinator({ atStart -> runs += atStart; gate.await() }, rows, keepAlive, scope)
    private val visibility = AppVisibility()

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        root.deleteRecursively()
    }

    private val recording = RecorderHarness(chunks = 20)

    private var backupsStarted = 0
    private var chargerStarts = 0
    private var runsAtChargerStart = -1
    private var networkWatchStarts = 0
    private var runsAtNetworkWatchStart = -1
    private var speechWatchStarts = 0
    private var backupChecks = 0

    // The start-up's scope is the test's: its two launches run inside start() up to their first suspension.
    private fun startup(supported: Boolean) = NotebookStartup(
        alarms, NotebookLinkToken(root), coordinator, visibility, recording.recorder,
        startBackups = { backupsStarted++; order += "backups" }, checkBackups = { backupChecks++ },
        startCharger = { chargerStarts++; runsAtChargerStart = runs.size },
        startNetworkWatch = { networkWatchStarts++; runsAtNetworkWatchStart = runs.size }, startSpeechWatch = { speechWatchStarts++ },
        scope = scope,
    ) { supported }

    @Test
    fun aProcessStartRunsAtStartAndStartsWatchingRowsAndVisibility() {
        startup(supported = true).start()
        assertEquals(listOf(true), runs)
        assertEquals(1, backupsStarted)
        assertEquals(1, chargerStarts)
        assertEquals(0, runsAtChargerStart, "the charger watch starts before the first run")
        assertEquals(1, networkWatchStarts)
        assertEquals(0, runsAtNetworkWatchStart, "the network watch starts before the first run")
        assertEquals(1, speechWatchStarts)
        rows.value = listOf(CaptureQueueItem("a", 1L, CaptureQueueItem.Status.PROCESSING))
        assertEquals(listOf("hold"), held)
        gate.complete(Unit)
        assertEquals(0, backupChecks)
        visibility.onResumed()
        assertEquals(listOf(true, false), runs)
        assertEquals(1, backupChecks, "the app coming to the front runs the backup checks")
        visibility.onPaused()
        assertEquals(1, backupChecks)
    }

    @Test
    fun aProcessStartStartsTheBackupsWatchThenRearmsAndLoadsTheLinkToken() = runBlocking<Unit> {
        val stored = reminders.create { ReminderItem(it, now + 3_600_000L, "m", null) }
        startup(supported = true).start()
        // The sweep reads its clock before its first suspension, so it is in the list when start() returns.
        assertEquals(listOf("backups", "sweep"), order.take(2))
        assertTrue(File(root, "notebook_link_token").isFile, "the start did not load the link token")
        withTimeout(5.seconds) { while (scheduler.fires[stored.id] != stored) delay(10) }
    }

    @Test
    fun theRecorderStopsAndKeepsARecordingWhenTheAppLeaves() = runBlocking<Unit> {
        startup(supported = true).start()
        visibility.onStarted()
        visibility.onResumed()
        recording.recorder.record()
        recording.awaitReads(25)
        visibility.onPaused()
        recording.awaitIdle()
        assertEquals(1, recording.ingested.size)
    }

    @Test
    fun belowTheSupportedLevelNothingStarts() {
        startup(supported = false).start()
        visibility.onResumed()
        rows.value = listOf(CaptureQueueItem("a", 1L, CaptureQueueItem.Status.PROCESSING))
        assertTrue(runs.isEmpty())
        assertTrue(held.isEmpty())
        assertEquals(0, backupsStarted)
        assertEquals(0, backupChecks)
        assertEquals(0, chargerStarts)
        assertEquals(0, speechWatchStarts)
    }
}
