package com.anopticlabs.gravel.notebook.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.anopticlabs.gravel.notebook.R
import com.anopticlabs.gravel.notebook.backup.AutoBackupSetup
import com.anopticlabs.gravel.notebook.backup.AutoBackupStatus
import com.anopticlabs.gravel.notebook.backup.AutoBackups
import com.anopticlabs.gravel.notebook.backup.KeyCheckState
import com.anopticlabs.gravel.notebook.backup.NewestOpened
import com.anopticlabs.gravel.notebook.backup.RecoveryKeyCheck
import com.anopticlabs.gravel.notebook.backup.SetupPurpose
import com.anopticlabs.gravel.notebook.backup.SetupState
import com.anopticlabs.gravel.notebook.backup.problemText
import com.anopticlabs.gravel.notebook.backup.setupProblemText
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The dialogs and folder picker of automatic backup setup that [setup] runs. Every dialog is a
 * secure window ([notebookDialogProperties]); the typed-back key keeps its text out of saved
 * instance state and is cleared when handed over or closed.
 */
@Composable
fun AutoBackupDialogs(setup: AutoBackupSetup<Uri>) {
    val state by setup.state.collectAsState()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { setup.folderChosen(it?.toString()) }
    // After a frame, so a picker answer delivered to the launcher as it registers lands first.
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { withFrameNanos { }; setup.returned() } }
    LaunchedEffect(state) {
        val s = state
        if (s is SetupState.PickFolder && !s.opened && setup.claimPicker()) {
            try {
                pick.launch(null)
            } catch (e: Exception) {
                setup.pickerUnavailable()
            }
        }
    }
    val lockTitle = stringResource(R.string.notebook_auto_lock_title)
    val lockReasons = SetupPurpose.entries.associateWith { stringResource(lockReason(it)) }
    val confirmLock = rememberScreenLockPrompt()
    LaunchedEffect(state) {
        val s = state
        if (s is SetupState.ConfirmLock && !s.opened) {
            setup.claimLockPrompt()?.let { prompt -> confirmLock(lockTitle, lockReasons.getValue(s.purpose)) { setup.lockAnswered(prompt, it) } }
        }
    }
    val context = LocalContext.current
    val clipLabel = stringResource(R.string.notebook_backup_recovery_key)
    var copied by remember { mutableStateOf(false) }
    // Leaving the key steps takes a copied key off the clipboard, if it is still the clip there.
    LaunchedEffect(state) {
        if (copied && state !is SetupState.ShowKey && state !is SetupState.TypeBack) {
            clearClipboardIfLabeled(context, clipLabel)
            copied = false
        }
    }
    when (val s = state) {
        SetupState.Idle, is SetupState.PickFolder, is SetupState.ConfirmLock -> Unit
        SetupState.Explain -> AlertDialog(
            onDismissRequest = setup::close,
            properties = notebookDialogProperties(dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.notebook_auto_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.notebook_auto_explain))
                    Text(stringResource(R.string.notebook_auto_explain_folder))
                    Text(stringResource(R.string.notebook_auto_explain_key))
                }
            },
            confirmButton = { TextButton(onClick = setup::explained) { Text(stringResource(R.string.notebook_auto_pick_folder)) } },
            dismissButton = { TextButton(onClick = setup::close) { Text(stringResource(R.string.notebook_cancel)) } },
        )
        is SetupState.ShowKey -> {
            AlertDialog(
                onDismissRequest = setup::close,
                properties = notebookDialogProperties(dismissOnClickOutside = false),
                title = { Text(stringResource(R.string.notebook_auto_key_title)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.notebook_auto_key_body))
                        Text(s.display, style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace))
                    }
                },
                confirmButton = { TextButton(onClick = setup::keyNoted) { Text(stringResource(R.string.notebook_auto_key_saved)) } },
                dismissButton = {
                    Row {
                        TextButton(onClick = setup::close) { Text(stringResource(R.string.notebook_cancel)) }
                        TextButton(onClick = setup::askToCopy) { Text(stringResource(R.string.notebook_auto_copy)) }
                    }
                },
            )
            if (s.warning) {
                AlertDialog(
                    onDismissRequest = setup::closeWarning,
                    properties = notebookDialogProperties(dismissOnClickOutside = false),
                    title = { Text(stringResource(R.string.notebook_auto_copy_warning_title)) },
                    text = { Text(stringResource(R.string.notebook_auto_copy_warning_body)) },
                    confirmButton = {
                        TextButton(onClick = { setup.copyAfterWarning { if (copySensitive(context, clipLabel, it)) copied = true } }) {
                            Text(stringResource(R.string.notebook_auto_copy_confirm))
                        }
                    },
                    dismissButton = { TextButton(onClick = setup::closeWarning) { Text(stringResource(R.string.notebook_cancel)) } },
                )
            }
        }
        is SetupState.TypeBack -> TypeBackDialog(s.wrong, onConfirm = setup::typedBack, onShowKey = setup::showKeyAgain, onCancel = setup::close)
        is SetupState.Saving -> Working(
            stringResource(if (s.purpose == SetupPurpose.REPLACE_KEY) R.string.notebook_auto_key_saving_title else R.string.notebook_auto_saving_title),
            stringResource(R.string.notebook_auto_saving_body), 0f, onCancel = null,
        )
        is SetupState.Failed -> AlertDialog(
            onDismissRequest = setup::close,
            properties = notebookDialogProperties(dismissOnClickOutside = false),
            title = { Text(stringResource(if (s.purpose == SetupPurpose.REPLACE_KEY) R.string.notebook_auto_key_failed_title else R.string.notebook_auto_setup_failed_title)) },
            text = { Text(stringResource(if (s.purpose == SetupPurpose.REPLACE_KEY) problemText(s.problem) else setupProblemText(s.problem))) },
            confirmButton = { TextButton(onClick = setup::tryAgain) { Text(stringResource(R.string.notebook_auto_try_again)) } },
            dismissButton = {
                Row {
                    TextButton(onClick = setup::close) { Text(stringResource(R.string.notebook_cancel)) }
                    if (s.purpose != SetupPurpose.REPLACE_KEY) {
                        TextButton(onClick = setup::pickAnother) { Text(stringResource(R.string.notebook_auto_pick_another)) }
                    }
                }
            },
        )
        SetupState.LockFailed -> AlertDialog(
            onDismissRequest = setup::close,
            properties = notebookDialogProperties(dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.notebook_auto_title)) },
            text = { Text(stringResource(R.string.notebook_auto_lock_failed)) },
            confirmButton = { TextButton(onClick = setup::close) { Text(stringResource(R.string.notebook_close)) } },
        )
        SetupState.NoPicker -> AlertDialog(
            onDismissRequest = setup::close,
            properties = notebookDialogProperties(dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.notebook_auto_title)) },
            text = { Text(stringResource(R.string.notebook_auto_no_picker)) },
            confirmButton = { TextButton(onClick = setup::close) { Text(stringResource(R.string.notebook_close)) } },
        )
        is SetupState.Done -> AlertDialog(
            onDismissRequest = setup::close,
            properties = notebookDialogProperties(dismissOnClickOutside = false),
            title = {
                Text(
                    stringResource(
                        when (s.purpose) {
                            SetupPurpose.CHANGE_FOLDER -> R.string.notebook_auto_folder_done_title
                            SetupPurpose.REPLACE_KEY -> R.string.notebook_auto_key_done_title
                            else -> R.string.notebook_auto_done_title
                        },
                    ),
                )
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val body = when (s.purpose) {
                        SetupPurpose.CHANGE_FOLDER -> R.string.notebook_auto_folder_done_body
                        SetupPurpose.REPLACE_KEY -> R.string.notebook_auto_key_done_body
                        else -> R.string.notebook_auto_done_body
                    }
                    Text(stringResource(body, s.entry.name))
                    val c = s.created
                    if (c.notesMissing > 0) Text(pluralStringResource(R.plurals.notebook_backup_missing_notes, c.notesMissing, c.notesMissing))
                    if (c.notesTooLarge > 0) Text(pluralStringResource(R.plurals.notebook_backup_too_large_notes, c.notesTooLarge, c.notesTooLarge))
                    if (c.notesUnlisted > 0) Text(pluralStringResource(R.plurals.notebook_backup_unlisted_notes, c.notesUnlisted, c.notesUnlisted))
                }
            },
            confirmButton = { TextButton(onClick = setup::close) { Text(stringResource(R.string.notebook_done)) } },
        )
    }
}

/** The recovery key check that [check] runs, in secure dialogs; the typed key is cleared when handed over or closed. */
@Composable
fun KeyCheckDialogs(check: RecoveryKeyCheck<Uri>) {
    val state by check.state.collectAsState()
    when (val s = state) {
        KeyCheckState.Idle -> Unit
        is KeyCheckState.Enter -> KeyEntryDialog(s, onCheck = check::submit, onNotNow = check::notNow, onNoKey = check::noKey, onCancel = check::close)
        KeyCheckState.Checking -> Working(stringResource(R.string.notebook_auto_checking_title), stringResource(R.string.notebook_auto_checking_body), 0f, onCancel = null)
        is KeyCheckState.Passed -> AlertDialog(
            onDismissRequest = check::close,
            properties = notebookDialogProperties(dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.notebook_auto_check_passed_title)) },
            text = {
                Text(
                    stringResource(
                        when (s.newest) {
                            NewestOpened.OPENED -> R.string.notebook_auto_check_passed_opened
                            NewestOpened.NONE -> R.string.notebook_auto_check_passed_none
                            NewestOpened.NOT_READ -> R.string.notebook_auto_check_passed_not_read
                            NewestOpened.DAMAGED ->
                                if (s.backupsOn) R.string.notebook_auto_check_passed_damaged else R.string.notebook_auto_check_passed_damaged_off
                            NewestOpened.MISSING ->
                                if (s.backupsOn) R.string.notebook_auto_check_passed_missing else R.string.notebook_auto_check_passed_missing_off
                        },
                    ),
                )
            },
            confirmButton = { TextButton(onClick = check::close) { Text(stringResource(R.string.notebook_done)) } },
        )
        is KeyCheckState.NotChecked -> AlertDialog(
            onDismissRequest = check::close,
            properties = notebookDialogProperties(dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.notebook_auto_check_not_checked_title)) },
            text = { Text(stringResource(if (s.settings) R.string.notebook_auto_check_not_checked_settings else R.string.notebook_auto_check_not_checked_key)) },
            confirmButton = { TextButton(onClick = check::close) { Text(stringResource(R.string.notebook_close)) } },
        )
        KeyCheckState.NewKey -> AlertDialog(
            onDismissRequest = check::close,
            properties = notebookDialogProperties(dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.notebook_auto_new_key_title)) },
            text = { Text(stringResource(R.string.notebook_auto_new_key_body)) },
            confirmButton = { TextButton(onClick = check::makeNewKey) { Text(stringResource(R.string.notebook_auto_new_key_confirm)) } },
            dismissButton = { TextButton(onClick = check::close) { Text(stringResource(R.string.notebook_cancel)) } },
        )
    }
}

@Composable
private fun KeyEntryDialog(s: KeyCheckState.Enter, onCheck: (CharArray) -> Unit, onNotNow: () -> Unit, onNoKey: () -> Unit, onCancel: () -> Unit) {
    val field = remember(s.wrong) { TextFieldState() }
    DisposableEffect(field) { onDispose { field.wipe() } }
    var shown by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        properties = notebookDialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.notebook_auto_check_title)) },
        text = {
            NoKeyboardLearning {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.notebook_auto_check_body))
                    PasswordField(field, stringResource(R.string.notebook_auto_typeback_field), shown, { shown = !shown }, s.wrong, recoveryKey = true) {
                        if (s.wrong) Text(stringResource(R.string.notebook_auto_check_wrong))
                    }
                    TextButton(onClick = onNoKey) { Text(stringResource(R.string.notebook_auto_no_key)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCheck(field.take()) }, enabled = field.text.isNotEmpty()) { Text(stringResource(R.string.notebook_auto_check_confirm)) }
        },
        dismissButton = {
            if (s.notNow) {
                TextButton(onClick = onNotNow) { Text(stringResource(R.string.notebook_auto_not_now)) }
            } else {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.notebook_cancel)) }
            }
        },
    )
}

private fun lockReason(purpose: SetupPurpose): Int = when (purpose) {
    SetupPurpose.NEW_KEY, SetupPurpose.RESTORED_KEY -> R.string.notebook_auto_lock_setup
    SetupPurpose.REPLACE_KEY -> R.string.notebook_auto_lock_new_key
    SetupPurpose.CHANGE_FOLDER -> R.string.notebook_auto_lock_folder
}

@Composable
private fun TypeBackDialog(wrong: Boolean, onConfirm: (CharArray) -> Unit, onShowKey: () -> Unit, onCancel: () -> Unit) {
    val field = remember(wrong) { TextFieldState() }
    DisposableEffect(field) { onDispose { field.wipe() } }
    var shown by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        properties = notebookDialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.notebook_auto_typeback_title)) },
        text = {
            NoKeyboardLearning {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.notebook_auto_typeback_body))
                    PasswordField(field, stringResource(R.string.notebook_auto_typeback_field), shown, { shown = !shown }, wrong, recoveryKey = true) {
                        if (wrong) Text(stringResource(R.string.notebook_auto_typeback_wrong))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(field.take()) }, enabled = field.text.isNotEmpty()) { Text(stringResource(R.string.notebook_auto_confirm)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.notebook_cancel)) }
                TextButton(onClick = onShowKey) { Text(stringResource(R.string.notebook_auto_show_key_again)) }
            }
        },
    )
}

/** What Settings and the home screen use of automatic backups. */
interface AutoBackupControls {
    val status: StateFlow<AutoBackupStatus>

    fun setUp()

    /** Starts setup with a new key when [AutoBackupStatus.setUpAgain]; its save deletes or sets aside the damaged files first. */
    fun setUpAgain()

    fun setEnabled(on: Boolean)

    fun backUpNow()

    fun changeFolder()

    fun checkKey()

    @Composable
    fun Dialogs()
}

class AndroidAutoBackupControls(
    private val backups: AutoBackups<Uri>,
    private val setup: AutoBackupSetup<Uri>,
    private val keyCheck: RecoveryKeyCheck<Uri>,
) : AutoBackupControls {
    override val status: StateFlow<AutoBackupStatus> get() = backups.status

    override fun setUp() = setup.startNew()

    override fun setUpAgain() = setup.startNew()

    override fun setEnabled(on: Boolean) = backups.setEnabled(on)

    override fun backUpNow() = backups.backUpNow()

    override fun changeFolder() = setup.startChangeFolder()

    override fun checkKey() = keyCheck.start()

    @Composable
    override fun Dialogs() {
        AutoBackupDialogs(setup)
        KeyCheckDialogs(keyCheck)
    }
}
