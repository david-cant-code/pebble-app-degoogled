package com.anopticlabs.gravel.notebook.backup

import com.anopticlabs.gravel.notebook.alarms.FakeNotifier
import com.anopticlabs.gravel.notebook.alarms.FakeScheduler
import com.anopticlabs.gravel.notebook.alarms.FakeSettings
import com.anopticlabs.gravel.notebook.alarms.ReminderAlarms
import com.anopticlabs.gravel.notebook.reminders.ReminderItem
import com.anopticlabs.gravel.notebook.reminders.ReminderRefreshSignal
import com.anopticlabs.gravel.notebook.storage.FakeCryptographer
import com.anopticlabs.gravel.notebook.storage.NoteStore
import com.anopticlabs.gravel.notebook.storage.QueueStore
import com.anopticlabs.gravel.notebook.storage.ReminderEventStore
import com.anopticlabs.gravel.notebook.storage.ReminderStore
import com.anopticlabs.gravel.notebook.storage.softwareCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** [AutoBackups] over a real runner and stores, with the scheduler faked. */
class AutoBackupsTest {

    private var now = 1_700_000_000_000L
    private val key = "0123456789abcdefghijklmnopqrstuvwxyz".repeat(2).take(64)
    private val root: File = Files.createTempDirectory("notebook-autos").toFile()
    private val crypto = FakeCryptographer(softwareCrypto())
    private val notes = NoteStore(root, crypto) { now }
    private val reminders = ReminderStore(root, crypto)
    private val events = ReminderEventStore(root, crypto)
    private val refresh = ReminderRefreshSignal()
    private val backup = NotebookBackup(
        notes, QueueStore(root, crypto) { now }, reminders, events,
        ReminderAlarms(reminders, events, FakeScheduler(), FakeNotifier(), FakeSettings(), refresh, { now }, { TimeZone.UTC }),
        refresh, BackupStaging(root), clock = { now },
    )
    private val store = AutoBackupStore(root, crypto)
    private val folder = FakeFolder()
    private val scheduler = FakeAutoScheduler()
    private val gate = BackupGate()
    private val runner = AutoBackupRunner(backup, store, folder, AutoBackupRunNote(File(root, "note")), gate, FakeNotices(), clock = { now }, zone = { TimeZone.UTC })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val picked = PickedFolders(File(root, "picked"))
    private val autos = AutoBackups(store, runner, scheduler, folder, picked, listOf(notes.revision, reminders.revision, events.revision), scope = scope)

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        root.deleteRecursively()
    }

    private suspend fun setUp() {
        assertIs<AutoRunResult.Saved>(runner.firstBackup("tree:a", key.toCharArray(), SetupPurpose.NEW_KEY))
        autos.refresh()
    }

    private suspend fun waitFor(what: String, check: () -> Boolean) = withTimeout(5_000) {
        while (!check()) delay(10)
    }

    @Test
    fun aChangeAsksForNoRunWhileBackupsAreNotSetUp() = runBlocking<Unit> {
        autos.start()
        waitFor("the state read") { autos.status.value.loaded }
        notes.createNote("work", "a.md", "alpha")
        delay(200)
        assertTrue(scheduler.afterChange.isEmpty())
    }

    @Test
    fun aChangeAsksForARunOnceBackupsAreSetUpAfterStart() = runBlocking<Unit> {
        // Keeps the first backup from writing the reminders file, a store the instance watches for changes.
        reminders.identity()
        autos.start()
        waitFor("the state read") { autos.status.value.loaded }
        setUp()
        notes.createNote("work", "a.md", "alpha")
        waitFor("a run after the change") { scheduler.afterChange.isNotEmpty() }
    }

    @Test
    fun aChangeAsksForARunOnlyWhileBackupsAreOn() = runBlocking<Unit> {
        setUp()
        autos.start()
        reminders.create { ReminderItem(it, now + 1, "call") }
        waitFor("a run after the change") { scheduler.afterChange.isNotEmpty() }
        assertTrue(scheduler.afterChange.all { it == null })

        autos.setEnabled(false)
        waitFor("cancelled") { scheduler.cancels == 1 }
        waitFor("off") { !autos.status.value.enabled }
        val before = scheduler.afterChange.size
        notes.createNote("work", "b.md", "beta")
        delay(200)
        assertEquals(before, scheduler.afterChange.size)

        val periodic = scheduler.periodic
        autos.setEnabled(true)
        waitFor("scheduled") { scheduler.periodic > periodic }
    }

    @Test
    fun aChangeDuringARunForTheChangeSlotNamesThatRun() = runBlocking<Unit> {
        setUp()
        autos.start()
        delay(100)
        // Holds the run in its fingerprint, at the reminders file.
        val hold = CountDownLatch(1)
        crypto.beforeOpen = { if (it == ReminderStore.FILE_NAME) hold.await(10, TimeUnit.SECONDS) }
        val run = scope.async { runner.run(AutoBackupRunner.SLOT_CHANGE) }
        waitFor("the run under way") { runner.running.value == AutoBackupRunner.SLOT_CHANGE }
        waitFor("the status showing the run") { autos.status.value.running }
        notes.createNote("work", "a.md", "alpha")
        waitFor("a run after the change") { scheduler.afterChange.isNotEmpty() }
        assertEquals(AutoBackupRunner.SLOT_CHANGE, scheduler.afterChange.first())
        hold.countDown()
        run.await()
    }

    @Test
    fun startSchedulesThePeriodicRunOnlyWhileOn() = runBlocking<Unit> {
        setUp()
        store.updateState { it.copy(enabled = false) }
        val off = AutoBackups(store, runner, scheduler, folder, picked, emptyList(), scope = scope)
        off.start()
        waitFor("read") { off.status.value.loaded }
        assertEquals(0, scheduler.periodic)
        store.updateState { it.copy(enabled = true) }
        autos.start()
        waitFor("periodic") { scheduler.periodic == 1 }
        val status = autos.status.first { it.setUp }
        assertTrue(status.loaded)
        assertTrue(status.enabled)
        assertEquals("Backups", status.folderName)
        assertTrue(status.newest != null)
    }

    @Test
    fun backUpNowWritesANewFileAndTheStatusFollowsTheRun() = runBlocking<Unit> {
        setUp()
        autos.start()
        now += 1_000L
        autos.backUpNow()
        waitFor("the second file") { folder.docs.size == 2 }
        waitFor("status") { autos.status.value.newest?.createdAtMillis == now && !autos.status.value.running }
        assertFalse(autos.status.value.unreadable)
    }

    @Test
    fun backUpNowAskedForTwiceBeforeItRunsWritesOneFile() = runBlocking<Unit> {
        setUp()
        now += 1_000L
        assertTrue(gate.tryLock())
        autos.backUpNow()
        autos.backUpNow()
        delay(100)
        gate.unlock()
        waitFor("the second file") { folder.docs.size == 2 }
        delay(300)
        assertEquals(2, folder.docs.size)
        autos.backUpNow()
        waitFor("a third once the first ended") { folder.docs.size == 3 }
    }

    @Test
    fun aChangeRightAfterStartOrWhileTheStateIsUnreadableAsksForARun() = runBlocking<Unit> {
        assertIs<AutoRunResult.Saved>(runner.firstBackup("tree:a", key.toCharArray(), SetupPurpose.NEW_KEY))
        crypto.failOpenWhenAadContains = AutoBackupStore.STATE_FILE
        // Never read, so nothing in its status says on.
        autos.start()
        notes.createNote("work", "a.md", "alpha")
        waitFor("a run after the change") { scheduler.afterChange.isNotEmpty() }
        waitFor("unreadable") { autos.status.value.unreadable }
        assertFalse(autos.status.value.enabled)
        val before = scheduler.afterChange.size
        reminders.create { ReminderItem(it, now + 1, "call") }
        waitFor("another run") { scheduler.afterChange.size > before }
    }

    @Test
    fun aChangeBeforeTheStateIsReadAsksForARunWhileBackupsAreOff() = runBlocking<Unit> {
        assertIs<AutoRunResult.Saved>(runner.firstBackup("tree:a", key.toCharArray(), SetupPurpose.NEW_KEY))
        store.updateState { it.copy(enabled = false) }
        // Holds start's read of the state at the state file.
        val reading = CountDownLatch(1)
        val hold = CountDownLatch(1)
        crypto.beforeOpen = {
            if (it == AutoBackupStore.STATE_FILE) {
                reading.countDown()
                hold.await(60, TimeUnit.SECONDS)
            }
        }
        try {
            autos.start()
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            notes.createNote("work", "a.md", "alpha")
            waitFor("a run after the change") { scheduler.afterChange.isNotEmpty() }
            assertFalse(autos.status.value.loaded)
        } finally {
            hold.countDown()
        }
        waitFor("the state read") { autos.status.value.loaded }
        assertFalse(autos.status.value.enabled)
    }

    @Test
    fun aChangeWrittenBeforeTheScopeRunsAnythingAsksForARun() = runBlocking<Unit> {
        assertIs<AutoRunResult.Saved>(runner.firstBackup("tree:a", key.toCharArray(), SetupPurpose.NEW_KEY))
        val thread = Executors.newSingleThreadExecutor()
        val busy = CountDownLatch(1)
        thread.execute { busy.await(5, TimeUnit.SECONDS) }
        val lateScope = CoroutineScope(SupervisorJob() + thread.asCoroutineDispatcher())
        try {
            AutoBackups(store, runner, scheduler, folder, picked, listOf(notes.revision), scope = lateScope).start()
            notes.createNote("work", "a.md", "alpha")
            busy.countDown()
            waitFor("a run after the change") { scheduler.afterChange.isNotEmpty() }
        } finally {
            lateScope.cancel()
            thread.shutdown()
        }
    }

    @Test
    fun theSwitchCannotTurnOnWhatIsNotSetUp() = runBlocking<Unit> {
        autos.setEnabled(true)
        waitFor("switched") { scheduler.calls.isNotEmpty() }
        assertEquals(listOf("cancel"), scheduler.calls.toList())
        assertFalse(store.readState(strict = true).enabled)
    }

    @Test
    fun startLetsGoOfFoldersSetupPickedButDidNotKeep() = runBlocking<Unit> {
        setUp()
        picked.add("tree:x")
        picked.add("tree:a")
        autos.start()
        waitFor("released") { folder.released.isNotEmpty() && picked.all().isEmpty() }
        assertEquals(listOf("tree:x"), folder.released.toList())
    }

    @Test
    fun quickSwitchesLeaveTheScheduleMatchingTheStoredSwitch() = runBlocking<Unit> {
        setUp()
        repeat(5) {
            autos.setEnabled(false)
            autos.setEnabled(true)
        }
        autos.setEnabled(false)
        waitFor("eleven switches") { scheduler.calls.size == 11 }
        assertEquals(if (store.readState(strict = true).enabled) "periodic" else "cancel", scheduler.calls.last())
    }

    @Test
    fun aReportedFailureShowsInTheStatusAndAnUnreportedOneDoesNot() = runBlocking<Unit> {
        setUp()
        autos.start()
        store.updateState { it.copy(failure = AutoBackupFailure(AutoBackupProblem.NOT_SAVED, now, tries = 1, reported = false)) }
        assertEquals(AutoBackupProblem.NOT_SAVED, autos.refresh()?.failure?.problem)
        assertEquals(null, autos.status.value.failure)
        store.updateState { it.copy(failure = AutoBackupFailure(AutoBackupProblem.NOT_SAVED, now, tries = 4, reported = true)) }
        waitFor("failure shown") { autos.status.value.failure?.problem == AutoBackupProblem.NOT_SAVED }
    }

    @Test
    fun theStatusShowsAWarnedStretchAndADueKeyCheckAndChecksFollowEachChangeOfState() = runBlocking<Unit> {
        val asked = java.util.concurrent.atomic.AtomicInteger()
        val withChecks = AutoBackups(store, runner, scheduler, folder, picked, emptyList(), requestChecks = { asked.incrementAndGet() }, clock = { now }, scope = scope)
        setUp()
        withChecks.start()
        waitFor("checks after start") { asked.get() == 1 }
        assertFalse(withChecks.status.value.keyDue)
        now += 15 * DAY
        store.updateState { it.copy(staleWarnedAtMillis = it.lastConfirmedAtMillis!! + STALE_AFTER_MILLIS) }
        waitFor("warned stretch shown") { withChecks.status.value.stale }
        assertTrue(withChecks.status.value.keyDue)
        withChecks.setEnabled(false)
        waitFor("checks after the switch") { asked.get() == 2 }
        assertFalse(withChecks.status.value.stale, "switched off shows no warning")
        withChecks.setEnabled(true)
        waitFor("checks after the switch") { asked.get() == 3 }
        withChecks.backUpNow()
        waitFor("checks after Back up now") { asked.get() == 4 }
        val before = store.readState(strict = true).backups
        store.updateState { s -> s.copy(backups = s.backups.mapIndexed { i, e -> if (i == s.backups.lastIndex) e.copy(damaged = true) else e }) }
        assertEquals(before[before.lastIndex - 1].uri, withChecks.refresh()?.let { withChecks.status.value.newest?.uri }, "Settings shows the newest undamaged backup")
    }

    private companion object {
        const val DAY = 24 * 3_600_000L
    }

    @Test
    fun afterTheNotebookStartedOverRunsStopAndOnlyTheFoldersASetupUsesStayHeld() = runBlocking<Unit> {
        setUp()
        folder.held += listOf("tree:a", "tree:old", "tree:picking")
        picked.add("tree:picking")
        File(root, AutoBackupStore.STATE_FILE).renameTo(File(root, "set-aside"))

        autos.afterFilesSetAside()

        assertTrue(scheduler.cancels > 0)
        assertFalse(autos.status.value.setUp)
        assertEquals(setOf("tree:picking"), folder.held.toSet())
    }

    @Test
    fun aFolderASetupPicksAsTheGrantsAreListedStaysHeld() = runBlocking<Unit> {
        setUp()
        folder.onListHeld = {
            picked.add("tree:new")
            folder.held += "tree:new"
        }
        autos.afterFilesSetAside()
        assertTrue("tree:new" in folder.held)
    }

    @Test
    fun afterAStartOverTheStoredFolderStaysHeldWhileAStateReads() = runBlocking<Unit> {
        setUp()
        folder.held += listOf("tree:a", "tree:old")
        autos.afterFilesSetAside()
        assertEquals(setOf("tree:a"), folder.held.toSet())
    }

    @Test
    fun damagedSettingsOfferASetupAgain() = runBlocking<Unit> {
        setUp()
        assertFalse(autos.status.value.setUpAgain)
        File(root, AutoBackupStore.STATE_FILE).writeBytes(ByteArray(96) { 7 })
        autos.refresh()
        assertTrue(autos.status.value.unreadable)
        assertTrue(autos.status.value.setUpAgain)
    }

    @Test
    fun aKeyFileARunFailedOnOffersASetupAgain() = runBlocking<Unit> {
        setUp()
        assertFalse(autos.status.value.setUpAgain)
        val keyFile = File(root, AutoBackupStore.KEY_FILE)
        keyFile.writeBytes(ByteArray(96) { 7 })
        autos.refresh()
        assertFalse(autos.status.value.setUpAgain, "no run has failed on it yet")
        store.updateState { it.copy(failure = AutoBackupFailure(AutoBackupProblem.DAMAGED, now, 1, reported = true)) }
        autos.refresh()
        assertTrue(autos.status.value.setUpAgain)
    }

    @Test
    fun aNewSetupOverDamagedFilesStopsTheRunsAndLetsGoOfTheOldFolder() = runBlocking<Unit> {
        setUp()
        folder.held += listOf("tree:a", "tree:picking")
        picked.add("tree:picking")
        autos.setAsideForNewSetup()
        assertEquals(0, scheduler.cancels)
        assertTrue(store.readState(strict = true).setUp)

        File(root, AutoBackupStore.STATE_FILE).writeBytes(ByteArray(96) { 7 })
        autos.setAsideForNewSetup()
        assertTrue(scheduler.cancels > 0)
        assertEquals(setOf("tree:picking"), folder.held.toSet())
        assertFalse(File(root, AutoBackupStore.KEY_FILE).exists())
    }
}
