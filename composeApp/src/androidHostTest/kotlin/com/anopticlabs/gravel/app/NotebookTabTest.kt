package com.anopticlabs.gravel.app

import android.net.Uri
import com.anopticlabs.gravel.notebook.NotebookSettingsStore
import com.anopticlabs.gravel.notebook.backup.ArchiveKey
import com.anopticlabs.gravel.notebook.backup.BackupDocumentOps
import com.anopticlabs.gravel.notebook.backup.BackupFlow
import com.anopticlabs.gravel.notebook.backup.BackupFlowState
import com.anopticlabs.gravel.notebook.backup.BackupOperations
import com.anopticlabs.gravel.notebook.backup.BackupProgress
import com.anopticlabs.gravel.notebook.backup.BackupPurpose
import com.anopticlabs.gravel.notebook.backup.BackupRunRecord
import com.anopticlabs.gravel.notebook.backup.UnfinishedRun
import com.anopticlabs.gravel.notebook.backup.StagedArchive
import com.anopticlabs.gravel.notebook.backup.StagedBackup
import com.anopticlabs.gravel.notebook.pipeline.CaptureSettings
import com.anopticlabs.gravel.notebook.reminders.DEFAULT_HISTORY_RETENTION_DAYS
import com.anopticlabs.gravel.notebook.reminders.DEFAULT_QUICK_REMIND_MINUTES
import com.anopticlabs.gravel.notebook.reminders.DEFAULT_SWIPE_SNOOZE_MINUTES
import com.anopticlabs.gravel.notebook.reminders.MAX_QUICK_REMIND_MINUTES
import com.anopticlabs.gravel.notebook.reminders.MAX_SNOOZE_CHAIN
import com.anopticlabs.gravel.notebook.reminders.MIN_QUICK_REMIND_MINUTES
import com.anopticlabs.gravel.notebook.reminders.PhoneCalendar
import com.anopticlabs.gravel.notebook.reminders.PhoneCalendarChoice
import com.anopticlabs.gravel.notebook.reminders.PhoneCalendarInfo
import com.anopticlabs.gravel.notebook.reminders.PhoneEvent
import com.anopticlabs.gravel.notebook.alarms.ReminderIntents
import androidx.compose.runtime.Composable
import com.anopticlabs.gravel.notebook.backup.AutoBackupStatus
import com.anopticlabs.gravel.notebook.ui.AutoBackupControls
import com.anopticlabs.gravel.notebook.ui.NotebookLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import com.anopticlabs.gravel.notebook.ui.NotebookNavigator
import com.anopticlabs.gravel.ui.NotebookSettingsChoices
import com.anopticlabs.gravel.ui.NotebookAutoBackupValues
import com.anopticlabs.gravel.ui.NotebookSettingsValues
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.InputStream

class NotebookIntentsTest {

    private val token = "0123456789abcdef0123456789abcdef"
    private val navigator = NotebookNavigator()
    private var shown = true
    private var opened = 0
    private var settingsOpened = 0
    private var keyChecksOpened = 0
    private var tokenReads = 0
    private val intents = NotebookIntents(
        tabShown = { shown },
        token = { tokenReads++; token },
        navigator = navigator,
        openTab = { opened++ },
        openBackupSettings = { settingsOpened++ },
        openKeyCheck = { keyChecksOpened++ },
    )

    private fun take(action: String?, extras: Map<String, Any> = emptyMap(), fromHistory: Boolean = false) =
        intents.take(action, { extras[it] as? String }, { extras[it] as? Int }, { extras[it] as? Long }, fromHistory)

    private val withToken = mapOf<String, Any>(ReminderIntents.EXTRA_LINK_TOKEN to token)

    @Test
    fun aTapWithTheTokenOpensTheTabWithItsLink() {
        assertTrue(take(ReminderIntents.ACTION_OPEN_VOICE_COMPLETIONS, withToken))
        assertEquals(NotebookLink.Completions, navigator.pending.value)
        assertEquals(1, opened)
    }

    @Test
    fun aTapWithoutTheTokenIsTakenButNotRouted() {
        assertTrue(take(ReminderIntents.ACTION_OPEN_REMINDERS))
        assertNull(navigator.pending.value)
        assertEquals(0, opened)
        assertEquals(0, tokenReads)
    }

    @Test
    fun aTapWhileTheTabIsHiddenIsTakenButNotRouted() {
        shown = false
        assertTrue(take(ReminderIntents.ACTION_OPEN_REMINDERS, withToken))
        assertNull(navigator.pending.value)
        assertEquals(0, opened)
    }

    @Test
    fun aBackupNoticeOpensSettingsWhetherOrNotTheTabIsShownAndOnlyWithTheToken() {
        shown = false
        assertTrue(take(ReminderIntents.ACTION_OPEN_BACKUP_SETTINGS, withToken))
        assertEquals(1, settingsOpened)
        assertEquals(0, opened)
        assertNull(navigator.pending.value)
        assertTrue(take(ReminderIntents.ACTION_OPEN_BACKUP_SETTINGS))
        assertTrue(take(ReminderIntents.ACTION_OPEN_BACKUP_SETTINGS, withToken, fromHistory = true))
        assertEquals(1, settingsOpened)
    }

    @Test
    fun aKeyReminderOpensSettingsWithTheKeyCheckOnlyWithTheToken() {
        shown = false
        assertTrue(take(ReminderIntents.ACTION_OPEN_KEY_CHECK, withToken))
        assertEquals(1, settingsOpened)
        assertEquals(1, keyChecksOpened)
        assertEquals(0, opened)
        assertTrue(take(ReminderIntents.ACTION_OPEN_KEY_CHECK))
        assertTrue(take(ReminderIntents.ACTION_OPEN_KEY_CHECK, withToken, fromHistory = true))
        assertEquals(1, keyChecksOpened)
    }

    @Test
    fun aRelaunchFromRecentsIsTakenButNotRouted() {
        assertTrue(take(ReminderIntents.ACTION_OPEN_REMINDERS, withToken, fromHistory = true))
        assertEquals(0, opened)
    }

    @Test
    fun anotherIntentIsLeftToTheActivity() {
        assertFalse(take("android.intent.action.VIEW", withToken))
        assertFalse(take(null))
        assertEquals(0, opened)
    }
}

private class FakeStore : NotebookSettingsStore {
    var swipe = 10
    var retention = 90
    var quick = 30
    var capture = CaptureSettings()
    var phone = PhoneCalendarChoice(shown = false, calendars = emptySet())
    override val swipeSnoozeMinutes get() = swipe
    override val historyRetentionDays get() = retention
    override val quickRemindMinutes get() = quick
    override val captureSettings get() = capture
    override fun setSwipeSnoozeMinutes(minutes: Int) { swipe = minutes.coerceAtLeast(1) }
    override fun setHistoryRetentionDays(days: Int) { retention = days }
    override fun setQuickRemindMinutes(minutes: Int) { quick = minutes }
    override fun setCaptureSettings(settings: CaptureSettings) { capture = settings }
    override val phoneCalendar get() = phone
    override fun setPhoneCalendarShown(shown: Boolean) { phone = phone.copy(shown = shown) }
    override fun setPhoneCalendars(calendars: Set<String>) { phone = phone.copy(calendars = calendars) }
    var interrupt = true
    var waitForPower = true
    override val interruptLongCaptures get() = interrupt
    override fun setInterruptLongCaptures(on: Boolean) { interrupt = on }
    override val longCapturesWaitForPower get() = waitForPower
    override fun setLongCapturesWaitForPower(on: Boolean) { waitForPower = on }
}

/** Backup operations the tab tests never reach: they only start runs. */
private object UnusedBackups : BackupOperations {
    override suspend fun create(password: CharArray, onProgress: BackupProgress) = error("not used")
    override fun <H> publish(
        ops: BackupDocumentOps<H>,
        target: H,
        backup: StagedBackup,
        password: CharArray,
        onWriting: () -> Unit,
        onVerified: () -> Unit,
        onProgress: BackupProgress,
    ) = error("not used")
    override suspend fun stage(input: InputStream) = error("not used")
    override suspend fun unlock(archive: StagedArchive, password: CharArray) = error("not used")
    override suspend fun inspect(archive: StagedArchive, key: ArchiveKey, onProgress: BackupProgress) = error("not used")
    override suspend fun restore(archive: StagedArchive, key: ArchiveKey, beforeMerge: suspend () -> Unit, onProgress: BackupProgress) = error("not used")
}

private object UnusedDocuments : BackupDocumentOps<Uri> {
    override fun describe(handle: Uri) = error("not used")
    override fun openWrite(handle: Uri) = error("not used")
    override fun openRead(handle: Uri) = error("not used")
    override fun delete(handle: Uri) = error("not used")
}

private object NoRuns : BackupRunRecord<Uri> {
    override fun read(): UnfinishedRun<Uri>? = null
    override fun write(run: UnfinishedRun<Uri>) = true
    override fun clear(expected: UnfinishedRun<Uri>) {}
}

private class FakeAutoBackups(status: AutoBackupStatus = AutoBackupStatus(loaded = true, setUp = true, enabled = true, folderName = "Backups")) : AutoBackupControls {
    var setUpAgainCalls = 0
    override val status: MutableStateFlow<AutoBackupStatus> = MutableStateFlow(status)
    override fun setUp() = error("not used")
    override fun setUpAgain() { setUpAgainCalls++ }
    override fun setEnabled(on: Boolean) = error("not used")
    override fun backUpNow() = error("not used")
    override fun changeFolder() = error("not used")
    override fun checkKey() = error("not used")
    @Composable override fun Dialogs() = Unit
}

private object NoPhoneCalendar : PhoneCalendar {
    override fun calendars(): List<PhoneCalendarInfo>? = null
    override fun events(calendars: Set<String>, window: LongRange): List<PhoneEvent>? = null
}

class AndroidNotebookTabTest {

    private var saved = true
    private val store = FakeStore()
    private val backups = BackupFlow(UnusedBackups, UnusedDocuments, NoRuns, requestDrain = {})
    private val tab = AndroidNotebookTab(supported = true, savedShown = { true }, saveShown = {}, store = store, calendars = NoPhoneCalendar, backups = backups, autoBackups = FakeAutoBackups(), statusText = { "status" })

    @Test
    fun changingTheChargerRuleAsksForARunEitherWay() {
        var drains = 0
        val tab = AndroidNotebookTab(
            supported = true, savedShown = { true }, saveShown = {}, store = store, calendars = NoPhoneCalendar, backups = backups,
            autoBackups = FakeAutoBackups(), statusText = { "status" }, requestDrain = { drains++ },
        )
        tab.setLongRecordingsWaitForPower(false)
        assertFalse(store.longCapturesWaitForPower)
        assertFalse(tab.settings.value.longRecordingsWaitForPower)
        assertEquals(1, drains)
        tab.setLongRecordingsWaitForPower(true)
        assertEquals(2, drains)
        tab.setInterruptLongRecordings(false)
        assertFalse(store.interruptLongCaptures)
        assertFalse(tab.settings.value.interruptLongRecordings)
    }

    @Test
    fun anUnsupportedPhoneNeverShowsTheTab() {
        val tab = AndroidNotebookTab(supported = false, savedShown = { saved }, saveShown = { saved = it }, store = store, calendars = NoPhoneCalendar, backups = backups, autoBackups = FakeAutoBackups(), statusText = { "status" })
        assertFalse(tab.shown.value)
        tab.setShown(true)
        assertFalse(tab.shown.value)
    }

    @Test
    fun theSwitchShowsAndHidesTheTabAndIsSaved() {
        val tab = AndroidNotebookTab(supported = true, savedShown = { saved }, saveShown = { saved = it }, store = store, calendars = NoPhoneCalendar, backups = backups, autoBackups = FakeAutoBackups(), statusText = { "status" })
        assertTrue(tab.shown.value)
        tab.setShown(false)
        assertFalse(tab.shown.value)
        assertFalse(saved)
        assertFalse(AndroidNotebookTab(supported = true, savedShown = { saved }, saveShown = { saved = it }, store = store, calendars = NoPhoneCalendar, backups = backups, autoBackups = FakeAutoBackups(), statusText = { "status" }).shown.value)
    }

    @Test
    fun theSettingsReadAndWriteThroughTheStore() {
        assertEquals(NotebookSettingsValues(null, 30, 10, 90, MAX_SNOOZE_CHAIN, false, emptySet(), true, true), tab.settings.value)
        tab.setQuickRemindMinutes(60)
        tab.setSwipeSnoozeMinutes(0)
        tab.setHistoryRetentionDays(0)
        assertEquals(NotebookSettingsValues(null, 60, 1, 0, MAX_SNOOZE_CHAIN, false, emptySet(), true, true), tab.settings.value)
        assertEquals(listOf(60, 1, 0), listOf(store.quickRemindMinutes, store.swipeSnoozeMinutes, store.historyRetentionDays))
    }

    @Test
    fun theTriggerWordTurnsOnOnlyWithAValidWordAndOffKeepingTheWord() {
        assertFalse(tab.turnOnTriggerWord("the"))
        assertEquals(CaptureSettings(), store.captureSettings)
        assertTrue(tab.turnOnTriggerWord(" Done"))
        assertEquals(CaptureSettings(triggerEnabled = true, triggerWord = "done"), store.captureSettings)
        assertEquals("done", tab.settings.value.triggerWord)
        assertEquals("", tab.keptTriggerWord)
        tab.turnOffTriggerWord()
        assertEquals(CaptureSettings(triggerEnabled = false, triggerWord = "done"), store.captureSettings)
        assertNull(tab.settings.value.triggerWord)
        assertEquals("done", tab.keptTriggerWord)
    }

    @Test
    fun theNotebookDefaultsAreAmongTheSettingsChoicesAndTheTimesWithinRange() {
        assertTrue(DEFAULT_SWIPE_SNOOZE_MINUTES in NotebookSettingsChoices.swipeSnoozeMinutes)
        assertTrue(DEFAULT_QUICK_REMIND_MINUTES in NotebookSettingsChoices.quickRemindMinutes)
        assertTrue(DEFAULT_HISTORY_RETENTION_DAYS in NotebookSettingsChoices.historyRetentionDays)
        assertTrue(NotebookSettingsChoices.quickRemindMinutes.all { it in MIN_QUICK_REMIND_MINUTES..MAX_QUICK_REMIND_MINUTES })
        assertTrue(NotebookSettingsChoices.swipeSnoozeMinutes.all { it >= 1 })
        assertTrue(NotebookSettingsChoices.historyRetentionDays.all { it >= 0 })
    }

    @Test
    fun thePhoneCalendarPickIsKeptWhileItIsTurnedOff() {
        tab.setPhoneCalendarShown(true)
        tab.choosePhoneCalendar("home", chosen = true)
        tab.choosePhoneCalendar("work", chosen = true)
        tab.choosePhoneCalendar("home", chosen = false)
        assertEquals(PhoneCalendarChoice(shown = true, calendars = setOf("work")), store.phoneCalendar)
        assertEquals(true to setOf("work"), tab.settings.value.let { it.phoneCalendarShown to it.phoneCalendars })
        tab.setPhoneCalendarShown(false)
        assertEquals(PhoneCalendarChoice(shown = false, calendars = setOf("work")), store.phoneCalendar)
        assertEquals(false to setOf("work"), tab.settings.value.let { it.phoneCalendarShown to it.phoneCalendars })
    }

    @Test
    fun aStoredWordThatNoLongerValidatesReadsAsOff() {
        store.capture = CaptureSettings(triggerEnabled = true, triggerWord = "note")
        assertNull(AndroidNotebookTab(true, { true }, {}, store, NoPhoneCalendar, backups, FakeAutoBackups(), { "status" }).settings.value.triggerWord)
    }

    @Test
    fun theBackupEntriesStartTheirRunAndOnlyOneRunsAtATime() {
        tab.startBackup()
        assertEquals(BackupFlowState.NewPassword, backups.state.value)
        tab.startRestore()
        assertEquals(BackupFlowState.NewPassword, backups.state.value)
        backups.close()
        tab.startRestore()
        assertEquals(BackupFlowState.PickFile(BackupPurpose.RESTORE, opened = false), backups.state.value)
        backups.close()
        tab.startBackupCheck()
        assertEquals(BackupFlowState.PickFile(BackupPurpose.CHECK, opened = false), backups.state.value)
    }

    @Test
    fun automaticBackupsReachSettingsWithTheirStatusLine() {
        assertEquals(NotebookAutoBackupValues(known = true, setUp = true, enabled = true, running = false, status = "status", folderName = "Backups"), tab.autoBackup.value)
    }

    @Test
    fun stateThatCannotBeReadShowsAsSetUpSoNoSetupIsOffered() {
        val unreadable = object : AutoBackupControls by FakeAutoBackups() {
            override val status: StateFlow<AutoBackupStatus> = MutableStateFlow(AutoBackupStatus(unreadable = true))
        }
        val tab = AndroidNotebookTab(true, { true }, {}, store, NoPhoneCalendar, backups, unreadable, { "unreadable" })
        assertTrue(tab.autoBackup.value.known)
        assertTrue(tab.autoBackup.value.setUp)
    }

    @Test
    fun nothingIsOfferedBeforeTheStateHasBeenRead() {
        val unread = object : AutoBackupControls by FakeAutoBackups() {
            override val status: StateFlow<AutoBackupStatus> = MutableStateFlow(AutoBackupStatus())
        }
        assertFalse(AndroidNotebookTab(true, { true }, {}, store, NoPhoneCalendar, backups, unread, { "" }).autoBackup.value.known)
    }

    @Test
    fun damagedAutomaticBackupSettingsOfferASetupAgain() {
        val autos = FakeAutoBackups(AutoBackupStatus(loaded = true, unreadable = true, setUpAgain = true))
        val tab = AndroidNotebookTab(supported = true, savedShown = { true }, saveShown = {}, store = store, calendars = NoPhoneCalendar, backups = backups, autoBackups = autos, statusText = { "status" })
        assertTrue(tab.autoBackup.value.setUpAgain)
        tab.setUpAutoBackupAgain()
        assertEquals(1, autos.setUpAgainCalls)
        autos.status.value = AutoBackupStatus(loaded = true, setUp = true)
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(5_000) { tab.autoBackup.first { !it.setUpAgain } } }
    }

    @Test
    fun theKeyLocationIsReadAgainEachTimeSettingsShowsIt() = kotlinx.coroutines.runBlocking<Unit> {
        var reads = 0
        val tab = AndroidNotebookTab(
            supported = true, savedShown = { true }, saveShown = {}, store = store, calendars = NoPhoneCalendar, backups = backups,
            autoBackups = FakeAutoBackups(), statusText = { "status" }, keyLocationText = { "where ${++reads}" },
        )
        assertNull(tab.keyLocation.value)
        assertEquals("where 1", kotlinx.coroutines.withTimeout(5_000) { tab.keyLocation.first { it != null } })
        kotlinx.coroutines.withTimeout(5_000) { while (tab.keyLocation.value != null) kotlinx.coroutines.delay(10) }
        assertEquals("where 2", kotlinx.coroutines.withTimeout(5_000) { tab.keyLocation.first { it != null } })
    }
}
