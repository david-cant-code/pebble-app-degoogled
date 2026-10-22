package com.anopticlabs.gravel.notebook.ui

import androidx.compose.runtime.Composable
import com.anopticlabs.gravel.notebook.backup.AutoBackupStatus
import com.anopticlabs.gravel.notebook.storage.DeviceStateFile
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BackupReminderTest {
    private val never = AutoBackupStatus(loaded = true)
    private val on = AutoBackupStatus(loaded = true, setUp = true, enabled = true)
    private val off = AutoBackupStatus(loaded = true, setUp = true, enabled = false)
    private val week = BackupReminder.SNOOZE.inWholeMilliseconds

    @Test
    fun eachStateOfBackupsGetsItsOwnAction() {
        assertEquals(BackupReminderAction.SET_UP, backupReminderFor(never, never = false, afterMillis = 0, nowMillis = 1))
        assertEquals(BackupReminderAction.TURN_ON, backupReminderFor(off, never = false, afterMillis = 0, nowMillis = 1))
        assertNull(backupReminderFor(on, never = false, afterMillis = 0, nowMillis = 1))
        assertEquals(BackupReminderAction.SET_UP_AGAIN, backupReminderFor(on.copy(setUpAgain = true), never = false, afterMillis = 0, nowMillis = 1))
    }

    @Test
    fun nothingShowsBeforeTheStateIsReadOrWhileItCannotBeReadUnlessItNeedsANewSetup() {
        assertNull(backupReminderFor(AutoBackupStatus(), never = false, afterMillis = 0, nowMillis = 1))
        assertNull(backupReminderFor(AutoBackupStatus(unreadable = true), never = false, afterMillis = 0, nowMillis = 1))
        assertEquals(
            BackupReminderAction.SET_UP_AGAIN,
            backupReminderFor(AutoBackupStatus(unreadable = true, setUpAgain = true), never = false, afterMillis = 0, nowMillis = 1),
        )
    }

    @Test
    fun aNotNowHoldsTheReminderUntilItsTimeAndNeverHoldsItForGood() {
        assertNull(backupReminderFor(never, never = false, afterMillis = 100, nowMillis = 99))
        assertEquals(BackupReminderAction.SET_UP, backupReminderFor(never, never = false, afterMillis = 100, nowMillis = 100))
        assertNull(backupReminderFor(never, never = true, afterMillis = 0, nowMillis = Long.MAX_VALUE))
        assertNull(backupReminderFor(off.copy(setUpAgain = true), never = true, afterMillis = 0, nowMillis = 1))
    }

    @Test
    fun aSnoozeReachingPastAWeekFromNowCountsAsRunOut() {
        assertNull(backupReminderFor(never, never = false, afterMillis = 1_000 + week, nowMillis = 1_000))
        assertEquals(BackupReminderAction.SET_UP, backupReminderFor(never, never = false, afterMillis = 1_001 + week, nowMillis = 1_000))
    }

    private var now = 1_000L
    private val sleeps = Channel<Long>(Channel.UNLIMITED)
    private val wakes = Channel<Unit>()

    // Unconfined: the read and each write run in the call, so a test sees them at once.
    private fun reminder(store: BackupReminderStore, controls: FakeControls) = BackupReminder(
        store, controls, clock = { now }, io = Dispatchers.Unconfined, scope = CoroutineScope(Dispatchers.Unconfined),
        sleep = { sleeps.send(it); wakes.receive() },
    )

    @Test
    fun notNowWaitsAWeekIsSavedAndTheLineComesBackWhileWatched() = runBlocking {
        val store = FakeStore()
        val reminder = reminder(store, FakeControls(never))
        val seen = Channel<BackupReminderAction?>(Channel.UNLIMITED)
        val watching = launch { reminder.action.collect { seen.send(it) } }
        withTimeout(5_000) {
            assertEquals(BackupReminderAction.SET_UP, seen.receive())
            reminder.notNow()
            assertNull(seen.receive())
            assertEquals(BackupReminderAnswers(afterMillis = 1_000 + week), store.answers)
            assertEquals(week, sleeps.receive())
            now += week
            wakes.send(Unit)
            assertEquals(BackupReminderAction.SET_UP, seen.receive())
        }
        watching.cancel()
    }

    @Test
    fun neverIsSavedHidesTheLineAndALaterNotNowUndoesIt() = runBlocking {
        val store = FakeStore()
        val reminder = reminder(store, FakeControls(off))
        reminder.never()
        assertEquals(true, store.answers.never)
        assertNull(reminder.action.first())
        assertNull(reminder(store, FakeControls(off)).action.first())
        reminder.notNow()
        assertEquals(false, store.answers.never)
    }

    @Test
    fun anAnswerGivenWhileTheFirstReadRunsWinsOverIt() = runBlocking {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = object : BackupReminderStore {
            override fun read(): BackupReminderAnswers {
                reading.countDown()
                release.await()
                return BackupReminderAnswers()
            }
            override fun write(answers: BackupReminderAnswers) = true
        }
        val reminder = BackupReminder(store, FakeControls(never), clock = { now }, io = Dispatchers.IO, scope = CoroutineScope(Dispatchers.Unconfined))
        val first = async(Dispatchers.Default) { reminder.action.first() }
        reading.await()
        reminder.notNow()
        release.countDown()
        assertNull(withTimeout(5_000) { first.await() })
    }

    @Test
    fun writesSaveTheLatestAnswersWhateverOrderTheyRunIn() {
        val queue = QueueDispatcher()
        val store = FakeStore()
        val reminder = BackupReminder(store, FakeControls(never), clock = { now }, io = Dispatchers.Unconfined, scope = CoroutineScope(queue))
        reminder.notNow()
        reminder.never()
        queue.runLastFirst()
        assertEquals(BackupReminderAnswers(never = true, afterMillis = 1_000 + week), store.answers)
    }

    @Test
    fun theReminderFollowsBackupsAsTheyChange() = runBlocking {
        val controls = FakeControls(never)
        val reminder = reminder(FakeStore(), controls)
        assertEquals(BackupReminderAction.SET_UP, reminder.action.first())
        controls.status.value = on
        assertNull(reminder.action.first())
        controls.status.value = off
        assertEquals(BackupReminderAction.TURN_ON, reminder.action.first())
    }

    @Test
    fun eachActionRunsItsOwnControl() {
        val controls = FakeControls(never)
        val reminder = reminder(FakeStore(), controls)
        reminder.act(BackupReminderAction.SET_UP)
        reminder.act(BackupReminderAction.TURN_ON)
        reminder.act(BackupReminderAction.SET_UP_AGAIN)
        assertEquals(listOf("setUp", "enabled true", "setUpAgain"), controls.calls)
    }

    @Test
    fun theFileKeepsBothAnswersAndReadsAnythingElseAsNone() {
        val dir = Files.createTempDirectory("reminder").toFile()
        val file = BackupReminderFile(DeviceStateFile(dir, "reminder"))
        assertEquals(BackupReminderAnswers(), file.read())
        file.write(BackupReminderAnswers(never = true, afterMillis = 42))
        assertEquals(BackupReminderAnswers(never = true, afterMillis = 42), BackupReminderFile(DeviceStateFile(dir, "reminder")).read())
        File(dir, "reminder").writeText("1;soon")
        assertEquals(BackupReminderAnswers(), file.read())
        File(dir, "reminder").writeText("1")
        assertEquals(BackupReminderAnswers(), file.read())
    }

    // Runs queued work newest first, so a test can make writes land in the opposite order to the calls.
    private class QueueDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(tasks) { tasks.addLast(block) }
        }
        fun runLastFirst() {
            while (true) (synchronized(tasks) { tasks.removeLastOrNull() } ?: return).run()
        }
    }

    private class FakeStore(var answers: BackupReminderAnswers = BackupReminderAnswers()) : BackupReminderStore {
        override fun read() = answers
        override fun write(answers: BackupReminderAnswers): Boolean {
            this.answers = answers
            return true
        }
    }

    private class FakeControls(status: AutoBackupStatus) : AutoBackupControls {
        val calls = mutableListOf<String>()
        override val status = MutableStateFlow(status)
        override fun setUp() { calls += "setUp" }
        override fun setUpAgain() { calls += "setUpAgain" }
        override fun setEnabled(on: Boolean) { calls += "enabled $on" }
        override fun backUpNow() = error("not used")
        override fun changeFolder() = error("not used")
        override fun checkKey() = error("not used")
        @Composable override fun Dialogs() = Unit
    }
}
