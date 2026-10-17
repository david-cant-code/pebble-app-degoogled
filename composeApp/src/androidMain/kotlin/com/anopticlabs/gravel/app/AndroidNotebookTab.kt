package com.anopticlabs.gravel.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.anopticlabs.gravel.notebook.NotebookSettingsStore
import com.anopticlabs.gravel.notebook.reminders.MAX_SNOOZE_CHAIN
import com.anopticlabs.gravel.notebook.reminders.PhoneCalendar
import com.anopticlabs.gravel.notebook.reminders.effectiveTriggerEnabled
import com.anopticlabs.gravel.notebook.reminders.isValidTriggerWord
import com.anopticlabs.gravel.notebook.reminders.triggerWordToStore
import android.net.Uri
import com.anopticlabs.gravel.notebook.backup.AutoBackupStatus
import com.anopticlabs.gravel.notebook.backup.BackupFlow
import com.anopticlabs.gravel.notebook.backup.autoBackupStatusText
import com.anopticlabs.gravel.notebook.backup.recoveryKeyStatusText
import com.anopticlabs.gravel.notebook.storage.NotebookKeystore
import com.anopticlabs.gravel.notebook.ui.AutoBackupControls
import com.anopticlabs.gravel.notebook.ui.keyLocationText
import com.anopticlabs.gravel.notebook.ui.BackupDialogs as NotebookBackupDialogs
import com.anopticlabs.gravel.ui.NotebookAutoBackupValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.koin.android.ext.koin.androidContext
import com.anopticlabs.gravel.notebook.ui.PhoneCalendarsDialog as NotebookPhoneCalendarsDialog
import com.anopticlabs.gravel.notebook.ui.TriggerWordDialog as NotebookTriggerWordDialog
import com.anopticlabs.gravel.ui.NotebookSettingsValues
import com.anopticlabs.gravel.notebook.alarms.NotebookPreferences
import com.anopticlabs.gravel.notebook.pipeline.CaptureCoordinator
import com.anopticlabs.gravel.notebook.isNotebookSupported
import com.anopticlabs.gravel.notebook.ui.NotebookLink
import com.anopticlabs.gravel.notebook.ui.NotebookLinkToken
import com.anopticlabs.gravel.notebook.ui.NotebookNavigator
import com.anopticlabs.gravel.notebook.ui.NotebookScreen
import com.anopticlabs.gravel.notebook.ui.isNotebookLinkAction
import com.anopticlabs.gravel.notebook.ui.notebookLinkFor
import com.anopticlabs.gravel.ui.NotebookTab
import coredevices.pebble.PebbleDeepLinkHandler
import coredevices.pebble.ui.PebbleNavBarRoutes
import coredevices.pebble.ui.Section
import coredevices.pebble.ui.TopLevelType
import coredevices.pebble.ui.TopBarParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.dsl.module

/** Gravel: the Notebook tab, shown by the user's switch on phones that can run the Notebook, and its settings. */
class AndroidNotebookTab(
    override val supported: Boolean,
    savedShown: () -> Boolean,
    private val saveShown: (Boolean) -> Unit,
    private val store: NotebookSettingsStore,
    private val calendars: PhoneCalendar,
    private val backups: BackupFlow<Uri>,
    private val autoBackups: AutoBackupControls,
    private val statusText: (AutoBackupStatus) -> String,
    private val keyStatusText: (AutoBackupStatus) -> String = { "" },
    private val keyLocationText: () -> String? = { null },
    private val requestDrain: () -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : NotebookTab {
    private val _shown = MutableStateFlow(supported && savedShown())
    override val shown: StateFlow<Boolean> = _shown.asStateFlow()

    override fun setShown(shown: Boolean) {
        saveShown(shown)
        _shown.value = supported && shown
    }

    @Composable
    override fun Screen(topBarParams: TopBarParams, openTextModels: () -> Unit, openSpeechSettings: () -> Unit) {
        NotebookScreen(
            topBarParams,
            openTextModels,
            openSpeechSettings = openSpeechSettings,
        )
    }

    private val _settings by lazy { MutableStateFlow(read()) }
    override val settings: StateFlow<NotebookSettingsValues> by lazy { _settings.asStateFlow() }

    private fun read(): NotebookSettingsValues {
        val capture = store.captureSettings
        return NotebookSettingsValues(
            triggerWord = capture.triggerWord.takeIf { effectiveTriggerEnabled(capture.triggerEnabled, it) },
            quickRemindMinutes = store.quickRemindMinutes,
            swipeSnoozeMinutes = store.swipeSnoozeMinutes,
            historyRetentionDays = store.historyRetentionDays,
            swipeSnoozeLimit = MAX_SNOOZE_CHAIN,
            phoneCalendarShown = store.phoneCalendar.shown,
            phoneCalendars = store.phoneCalendar.calendars,
            interruptLongRecordings = store.interruptLongCaptures,
            longRecordingsWaitForPower = store.longCapturesWaitForPower,
        )
    }

    /** The word quick reminder completion was last on with, while it is off; the dialog that turns it on starts from it. */
    val keptTriggerWord: String
        get() = store.captureSettings.let { c -> c.triggerWord.takeIf { !c.triggerEnabled && isValidTriggerWord(it) } }.orEmpty()

    private fun write(change: NotebookSettingsStore.() -> Unit) {
        store.change()
        _settings.value = read()
    }

    override fun setQuickRemindMinutes(minutes: Int) = write { setQuickRemindMinutes(minutes) }

    override fun setSwipeSnoozeMinutes(minutes: Int) = write { setSwipeSnoozeMinutes(minutes) }

    override fun setHistoryRetentionDays(days: Int) = write { setHistoryRetentionDays(days) }

    override fun setInterruptLongRecordings(on: Boolean) = write { setInterruptLongCaptures(on) }

    // Off lets held captures start; on changes what waits for power.
    override fun setLongRecordingsWaitForPower(on: Boolean) {
        write { setLongCapturesWaitForPower(on) }
        requestDrain()
    }

    override fun turnOffTriggerWord() = write { setCaptureSettings(captureSettings.copy(triggerEnabled = false)) }

    /** Stores [word] as the trigger word and turns the feature on; false, with nothing stored, for a word that does not validate. */
    fun turnOnTriggerWord(word: String): Boolean {
        val stored = triggerWordToStore(word) ?: return false
        write { setCaptureSettings(captureSettings.copy(triggerEnabled = true, triggerWord = stored)) }
        return true
    }

    @Composable
    override fun TriggerWordDialog(onDismiss: () -> Unit) {
        val current by settings.collectAsState()
        NotebookTriggerWordDialog(
            currentWord = current.triggerWord,
            startingWord = current.triggerWord ?: keptTriggerWord,
            onConfirm = { word -> if (turnOnTriggerWord(word)) onDismiss() },
            onDismiss = onDismiss,
        )
    }

    override fun setPhoneCalendarShown(shown: Boolean) = write { setPhoneCalendarShown(shown) }

    fun choosePhoneCalendar(key: String, chosen: Boolean) = write {
        setPhoneCalendars(if (chosen) phoneCalendar.calendars + key else phoneCalendar.calendars - key)
    }

    @Composable
    override fun PhoneCalendarsDialog(onDismiss: () -> Unit) {
        val current by settings.collectAsState()
        NotebookPhoneCalendarsDialog(calendars::calendars, current.phoneCalendars, ::choosePhoneCalendar, onDismiss)
    }

    override fun startBackup() = backups.startBackup()

    override fun startRestore() = backups.startRestore()

    override fun startBackupCheck() = backups.startCheck()

    override val autoBackup: StateFlow<NotebookAutoBackupValues> by lazy {
        autoBackups.status.map(::valuesOf).stateIn(scope, SharingStarted.Eagerly, valuesOf(autoBackups.status.value))
    }

    // Settings offers setup only once the state reads and shows nothing set up; Set Up Again deletes or sets aside damaged files first.
    private fun valuesOf(s: AutoBackupStatus) =
        NotebookAutoBackupValues(s.loaded || s.unreadable, s.setUp || s.unreadable, s.enabled, s.running, statusText(s), s.folderName, keyStatusText(s), s.setUpAgain)

    override fun setUpAutoBackup() = autoBackups.setUp()

    override fun setUpAutoBackupAgain() = autoBackups.setUpAgain()

    override fun setAutoBackupEnabled(on: Boolean) = autoBackups.setEnabled(on)

    override fun backUpNow() = autoBackups.backUpNow()

    override fun changeBackupFolder() = autoBackups.changeFolder()

    override fun checkRecoveryKey() = autoBackups.checkKey()

    // Read again whenever Settings starts showing it, since a Notebook start over makes a new key.
    override val keyLocation: StateFlow<String?> by lazy {
        flow { emit(keyLocationText()) }.flowOn(Dispatchers.IO).stateIn(scope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), null)
    }

    @Composable
    override fun BackupDialogs() {
        NotebookBackupDialogs(backups)
        autoBackups.Dialogs()
    }
}

/**
 * Gravel: the Notebook's notification taps, for MainActivity. A tap is taken whether or not it
 * routes, so its data URI never reaches the link handlers; it routes only with the Notebook's
 * link token (see [notebookLinkFor]), to Settings > Notebook for a backup notice, there with the
 * key check open for a key reminder, and otherwise to the tab while it is shown.
 */
class NotebookIntents(
    private val tabShown: () -> Boolean,
    private val token: () -> String,
    private val navigator: NotebookNavigator,
    private val openTab: () -> Unit,
    private val openBackupSettings: () -> Unit,
    private val openKeyCheck: () -> Unit = {},
) {
    /** True when [action] is a Notebook tap, which the caller then treats as handled. */
    fun take(
        action: String?,
        readString: (String) -> String?,
        readInt: (String) -> Int?,
        readLong: (String) -> Long?,
        launchedFromHistory: Boolean,
    ): Boolean {
        if (!isNotebookLinkAction(action)) return false
        val link: NotebookLink = notebookLinkFor(action, readString, readInt, readLong, launchedFromHistory, token) ?: return true
        if (link == NotebookLink.BackupSettings) {
            openBackupSettings()
        } else if (link == NotebookLink.KeyCheck) {
            openBackupSettings()
            openKeyCheck()
        } else if (tabShown()) {
            navigator.open(link)
            openTab()
        }
        return true
    }
}

val notebookTabModule = module {
    single<NotebookTab> {
        val prefs = get<NotebookPreferences>()
        val resources = androidContext().resources
        AndroidNotebookTab(
            isNotebookSupported, { prefs.tabShown }, prefs::setTabShown, prefs, get(), get(), get(),
            statusText = { autoBackupStatusText(resources, it) },
            keyStatusText = { recoveryKeyStatusText(resources, it) },
            keyLocationText = { NotebookKeystore().placement()?.let { keyLocationText(resources, it) } },
            requestDrain = { get<CaptureCoordinator>().requestAgain() },
        )
    }
    single {
        val tab = get<NotebookTab>()
        val linkToken = get<NotebookLinkToken>()
        val deepLinks = get<PebbleDeepLinkHandler>()
        NotebookIntents(
            tabShown = { tab.shown.value },
            token = { linkToken.value },
            navigator = get(),
            openTab = { deepLinks.navigateToTab(PebbleNavBarRoutes.NotebookRoute) },
            openBackupSettings = {
                deepLinks.navigateToTab(PebbleNavBarRoutes.WatchSettingsCategoryRoute(section = Section.Notebook.name, topLevelType = TopLevelType.Phone.name))
            },
            openKeyCheck = tab::checkRecoveryKey,
        )
    }
}
