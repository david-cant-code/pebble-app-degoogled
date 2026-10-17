package com.anopticlabs.gravel.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import coredevices.pebble.ui.TopBarParams
import kotlinx.coroutines.flow.StateFlow
import org.koin.compose.getKoin

/**
 * The Notebook as common code sees it: the tab, Settings > Notebook and the Notebook's part of Settings > Phone
 * Calendar. Only platforms with the Notebook bind it, so common code looks it up with `getOrNull` and treats a
 * missing binding as no tab.
 */
interface NotebookTab {
    /** False where the phone cannot run the Notebook; its Settings category is hidden then. */
    val supported: Boolean

    /** The user's switch, and never true where [supported] is false. */
    val shown: StateFlow<Boolean>

    fun setShown(shown: Boolean)

    /**
     * [openTextModels] opens Settings > AI > LLM, offered while processing waits on the text model, and
     * [openSpeechSettings] Settings > AI > Speech Recognition, while it waits on the speech setting or the server.
     */
    @Composable
    fun Screen(topBarParams: TopBarParams, openTextModels: () -> Unit, openSpeechSettings: () -> Unit)

    /** The Notebook's settings as Settings shows them. */
    val settings: StateFlow<NotebookSettingsValues>

    fun setQuickRemindMinutes(minutes: Int)

    fun setSwipeSnoozeMinutes(minutes: Int)

    /** Zero keeps history forever. */
    fun setHistoryRetentionDays(days: Int)

    fun setInterruptLongRecordings(on: Boolean)

    fun setLongRecordingsWaitForPower(on: Boolean)

    fun turnOffTriggerWord()

    /** Turns the trigger word on, or changes it while it is on; [onDismiss] runs when it closes, a saved word included. */
    @Composable
    fun TriggerWordDialog(onDismiss: () -> Unit)

    /** Whether the Notebook's Calendar shows the calendars picked in [PhoneCalendarsDialog]; turning it off keeps the pick. */
    fun setPhoneCalendarShown(shown: Boolean)

    @Composable
    fun PhoneCalendarsDialog(onDismiss: () -> Unit)

    /** Starts a manual backup, restore or check; [BackupDialogs] shows it. Ignored while another runs. */
    fun startBackup()

    fun startRestore()

    fun startBackupCheck()

    /** Automatic backups as Settings > Notebook shows them. */
    val autoBackup: StateFlow<NotebookAutoBackupValues>

    /** Starts setup, which [BackupDialogs] shows; ignored while it runs. */
    fun setUpAutoBackup()

    /** Starts setup with a new key, whose save sets the damaged automatic backup files aside; see [NotebookAutoBackupValues.setUpAgain]. */
    fun setUpAutoBackupAgain()

    /** Off keeps the key and the folder, so on resumes. */
    fun setAutoBackupEnabled(on: Boolean)

    /** A new automatic backup at once, even when nothing changed. */
    fun backUpNow()

    fun changeBackupFolder()

    /** Opens the recovery key check, which [BackupDialogs] shows. */
    fun checkRecoveryKey()

    /** Where the phone keeps the key the Notebook is encrypted with, as Settings shows it; null before the key exists. */
    val keyLocation: StateFlow<String?>

    /** The dialogs and pickers of the manual run or automatic backup setup in progress, which outlive the screen showing them; the home screen keeps them composed on every tab. */
    @Composable
    fun BackupDialogs()
}

/**
 * [status] is the line Settings shows under Automatic Backups once they are set up, and
 * [keyStatus] the one under Recovery Key; nothing is shown until [known]. [setUpAgain] while
 * their settings are damaged, or a run failed on a damaged or gone key file, so only a new setup brings them back.
 */
data class NotebookAutoBackupValues(
    val known: Boolean = false,
    val setUp: Boolean = false,
    val enabled: Boolean = false,
    val running: Boolean = false,
    val status: String = "",
    val folderName: String? = null,
    val keyStatus: String = "",
    val setUpAgain: Boolean = false,
)

/** [triggerWord] is null while quick reminder completion is off; [swipeSnoozeLimit] is how often a swipe brings one reminder back. */
data class NotebookSettingsValues(
    val triggerWord: String?,
    val quickRemindMinutes: Int,
    val swipeSnoozeMinutes: Int,
    val historyRetentionDays: Int,
    val swipeSnoozeLimit: Int,
    val phoneCalendarShown: Boolean,
    val phoneCalendars: Set<String>,
    val interruptLongRecordings: Boolean,
    val longRecordingsWaitForPower: Boolean,
)

@Composable
fun rememberNotebookTab(): NotebookTab? {
    val koin = getKoin()
    return remember(koin) { koin.getOrNull<NotebookTab>() }
}
