package com.anopticlabs.gravel.notebook.ui

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
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
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anopticlabs.gravel.notebook.R
import com.anopticlabs.gravel.notebook.backup.ArchiveFormat
import com.anopticlabs.gravel.notebook.backup.BACKUP_MIME_TYPE
import com.anopticlabs.gravel.notebook.backup.BackupFailure
import com.anopticlabs.gravel.notebook.backup.BackupFlow
import com.anopticlabs.gravel.notebook.backup.BackupFlowState
import com.anopticlabs.gravel.notebook.backup.BackupProblem
import com.anopticlabs.gravel.notebook.backup.BackupPurpose
import com.anopticlabs.gravel.notebook.backup.BackupStage
import com.anopticlabs.gravel.notebook.backup.PasswordProblem
import com.anopticlabs.gravel.notebook.backup.PublishFailure
import com.anopticlabs.gravel.notebook.backup.RestoreStop
import com.anopticlabs.gravel.notebook.backup.backupPasswordProblem
import com.anopticlabs.gravel.notebook.storage.NotebookPart
import com.anopticlabs.gravel.notebook.storage.RecoveryPlan
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The dialogs and system pickers of a manual backup, restore or check that [flow] runs, and the
 * report of a run the last process left unfinished. Every dialog is a secure window
 * ([notebookDialogProperties]). Password fields keep their text out of saved instance state and
 * clear it when handed to [flow] or closed.
 */
@Composable
fun BackupDialogs(flow: BackupFlow<Uri>) {
    LaunchedEffect(flow) { flow.recoverUnfinished() }
    val state by flow.state.collectAsState()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE)) { flow.documentChosen(it) }
    val pickRestore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { flow.fileChosen(BackupPurpose.RESTORE, it) }
    val pickCheck = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { flow.fileChosen(BackupPurpose.CHECK, it) }
    // After a frame, so a picker answer delivered to the launchers as they register lands first.
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { withFrameNanos { }; flow.returned() } }
    val lockTitle = stringResource(R.string.notebook_auto_lock_title)
    val lockReason = stringResource(R.string.notebook_restore_lock_reason)
    val confirmLock = rememberScreenLockPrompt()
    LaunchedEffect(state) {
        val s = state
        if (s is BackupFlowState.ConfirmLock && !s.opened) {
            flow.claimLockPrompt()?.let { prompt -> confirmLock(lockTitle, lockReason) { flow.lockAnswered(prompt, it) } }
        }
    }
    LaunchedEffect(state) {
        val s = state
        try {
            if (s is BackupFlowState.SaveTo && !s.opened && flow.claimPicker()) save.launch(s.suggestedName)
            if (s is BackupFlowState.PickFile && !s.opened && flow.claimPicker()) {
                (if (s.purpose == BackupPurpose.RESTORE) pickRestore else pickCheck).launch(arrayOf("*/*"))
            }
        } catch (e: Exception) {
            flow.pickerUnavailable()
        }
    }
    when (val s = state) {
        BackupFlowState.Idle, is BackupFlowState.SaveTo, is BackupFlowState.PickFile, is BackupFlowState.ConfirmLock -> Unit
        BackupFlowState.NewPassword -> NewPasswordDialog(onConfirm = flow::backUp, onCancel = flow::close)
        is BackupFlowState.Building -> Working(
            stringResource(R.string.notebook_backup_building),
            when (s.stage) {
                BackupStage.CAPTURES -> stringResource(R.string.notebook_backup_stage_captures)
                BackupStage.NOTES -> stringResource(R.string.notebook_backup_stage_notes)
                BackupStage.REMINDERS -> stringResource(R.string.notebook_backup_stage_reminders)
                else -> stringResource(R.string.notebook_backup_stage_preparing)
            },
            s.fraction,
            onCancel = flow::close,
        )
        is BackupFlowState.Saving -> Working(stringResource(R.string.notebook_backup_saving), stringResource(R.string.notebook_backup_saving_body), s.fraction, onCancel = null)
        is BackupFlowState.Saved -> LinesDialog(
            stringResource(if (BackupLines.hasGaps(s.created, s.summary)) R.string.notebook_backup_saved_with_gaps else R.string.notebook_backup_saved),
            BackupLines.saved(s.outcome, s.created, s.summary),
            onDone = flow::close,
        )
        is BackupFlowState.Copying -> Working(titleOf(s.purpose), stringResource(R.string.notebook_backup_opening), 0f, onCancel = flow::close)
        BackupFlowState.CleaningUp -> Working(
            stringResource(R.string.notebook_backup_not_saved), stringResource(R.string.notebook_backup_cleaning_up), 0f,
            onCancel = flow::close, cancelLabel = R.string.notebook_close,
        )
        is BackupFlowState.EnterPassword -> EnterPasswordDialog(s, onOpen = flow::open, onCancel = flow::close)
        is BackupFlowState.Checking -> Working(stringResource(R.string.notebook_backup_checking), stringResource(R.string.notebook_backup_checking_body), s.fraction, onCancel = flow::close)
        is BackupFlowState.Contents -> if (s.purpose == BackupPurpose.RESTORE) {
            LinesDialog(
                stringResource(R.string.notebook_restore_confirm_title),
                BackupLines.contents(s.summary),
                intro = listOfNotNull(
                    s.plan?.let { planText(LocalContext.current.resources, it) },
                    stringResource(R.string.notebook_restore_confirm_body).takeIf { s.plan != RecoveryPlan.StartOver },
                    stringResource(R.string.notebook_restore_confirm_reminders).takeIf { s.plan.keepsReminders() },
                ).joinToString("\n\n"),
                confirm = stringResource(R.string.notebook_restore),
                onDone = flow::restore,
                onCancel = flow::close,
            )
        } else {
            LinesDialog(
                stringResource(if (s.summary.healthy) R.string.notebook_check_ok else R.string.notebook_check_problems),
                BackupLines.contents(s.summary),
                intro = s.fileName?.let { stringResource(R.string.notebook_backup_file, it) },
                onDone = flow::close,
            )
        }
        is BackupFlowState.Restoring -> Working(
            stringResource(R.string.notebook_restoring),
            when (s.stage) {
                BackupStage.CAPTURES -> stringResource(R.string.notebook_restore_stage_captures)
                BackupStage.NOTES -> stringResource(R.string.notebook_restore_stage_notes)
                BackupStage.REMINDERS -> stringResource(R.string.notebook_restore_stage_reminders)
                BackupStage.REARMING -> stringResource(R.string.notebook_restore_stage_rearming)
                else -> stringResource(R.string.notebook_restore_stage_checking)
            } + "\n" + stringResource(R.string.notebook_restoring_body),
            s.fraction,
            onCancel = null,
        )
        is BackupFlowState.Restored -> LinesDialog(
            stringResource(R.string.notebook_restored),
            BackupLines.restored(s.report),
            onDone = flow::close,
            extra = if (s.offerKey) stringResource(R.string.notebook_auto_use_key) to flow::useKeyForAutomaticBackups else null,
        )
        is BackupFlowState.Failed -> FailedDialog(s.failure, onDone = flow::close, onPickFile = if (s.filePlan != null) flow::pickFileInstead else null)
    }
}

@Composable
private fun titleOf(purpose: BackupPurpose) =
    stringResource(if (purpose == BackupPurpose.RESTORE) R.string.notebook_restore_title else R.string.notebook_check_title)

@Composable
private fun NewPasswordDialog(onConfirm: (CharArray) -> Unit, onCancel: () -> Unit) {
    val first = remember { TextFieldState() }
    val again = remember { TextFieldState() }
    DisposableEffect(Unit) {
        onDispose {
            first.wipe()
            again.wipe()
        }
    }
    var shown by remember { mutableStateOf(false) }
    val problem = backupPasswordProblem(first.text, again.text)
    val min = ArchiveFormat.MIN_PASSWORD_LENGTH
    AlertDialog(
        onDismissRequest = onCancel,
        properties = notebookDialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.notebook_backup_title)) },
        text = {
            NoKeyboardLearning {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.notebook_backup_password_body))
                    val firstError = first.text.isNotEmpty() && (problem == PasswordProblem.TOO_SHORT || problem == PasswordProblem.NOT_TEXT)
                    PasswordField(first, stringResource(R.string.notebook_backup_password), shown, { shown = !shown }, firstError) {
                        Text(
                            when {
                                firstError && problem == PasswordProblem.NOT_TEXT -> stringResource(R.string.notebook_backup_password_not_text)
                                firstError -> stringResource(R.string.notebook_backup_password_too_short, min)
                                else -> stringResource(R.string.notebook_backup_password_hint, min)
                            },
                        )
                    }
                    val againError = again.text.isNotEmpty() && problem == PasswordProblem.DIFFERENT
                    PasswordField(again, stringResource(R.string.notebook_backup_password_again), shown, { shown = !shown }, againError) {
                        if (againError) Text(stringResource(R.string.notebook_backup_password_different))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val chars = first.take()
                    again.wipe()
                    onConfirm(chars)
                },
                enabled = problem == null,
            ) { Text(stringResource(R.string.notebook_backup_back_up)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.notebook_cancel)) } },
    )
}

@Composable
private fun EnterPasswordDialog(state: BackupFlowState.EnterPassword, onOpen: (CharArray) -> Unit, onCancel: () -> Unit) {
    val field = remember(state) { TextFieldState() }
    DisposableEffect(field) { onDispose { field.wipe() } }
    var shown by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        properties = notebookDialogProperties(dismissOnClickOutside = false),
        title = { Text(titleOf(state.purpose)) },
        text = {
            NoKeyboardLearning {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val key = state.recoveryKey
                    Text(stringResource(if (key) R.string.notebook_backup_enter_recovery_key else R.string.notebook_backup_enter_password))
                    state.fileName?.let { Text(stringResource(R.string.notebook_backup_file, it), style = MaterialTheme.typography.bodySmall) }
                    PasswordField(
                        field,
                        stringResource(if (key) R.string.notebook_backup_recovery_key else R.string.notebook_backup_password),
                        shown, { shown = !shown }, state.wrong, recoveryKey = key,
                    ) {
                        if (state.wrong) Text(stringResource(if (key) R.string.notebook_backup_wrong_recovery_key else R.string.notebook_backup_wrong_password))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onOpen(field.take()) }, enabled = field.text.isNotEmpty()) { Text(stringResource(R.string.notebook_backup_open)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.notebook_cancel)) } },
    )
}

/** A secret's field; [recoveryKey] words the show and hide button for a recovery key. */
@Composable
internal fun PasswordField(
    state: TextFieldState,
    label: String,
    shown: Boolean,
    toggle: () -> Unit,
    isError: Boolean,
    recoveryKey: Boolean = false,
    supporting: @Composable () -> Unit,
) {
    OutlinedSecureTextField(
        state = state,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        textObfuscationMode = if (shown) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
        isError = isError,
        supportingText = supporting,
        trailingIcon = {
            IconButton(onClick = toggle) {
                Icon(
                    if (shown) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(
                        when {
                            recoveryKey && shown -> R.string.notebook_backup_recovery_key_hide
                            recoveryKey -> R.string.notebook_backup_recovery_key_show
                            shown -> R.string.notebook_backup_password_hide
                            else -> R.string.notebook_backup_password_show
                        },
                    ),
                )
            }
        },
    )
}

/** The field's text as a new array, which the receiver owns, with the field wiped. */
internal fun TextFieldState.take(): CharArray {
    val chars = CharArray(text.length) { text[it] }
    wipe()
    return chars
}

internal fun TextFieldState.wipe() = clearText()

@Composable
internal fun Working(title: String, body: String, fraction: Float, onCancel: (() -> Unit)?, cancelLabel: Int = R.string.notebook_cancel) {
    AlertDialog(
        onDismissRequest = { onCancel?.invoke() },
        properties = notebookDialogProperties(dismissOnClickOutside = false),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(body)
                if (fraction > 0f) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            if (onCancel != null) TextButton(onClick = onCancel) { Text(stringResource(cancelLabel)) }
        },
    )
}

/** [extra] is a second button's label and action, shown where Cancel would be. */
@Composable
private fun LinesDialog(
    title: String,
    lines: List<BackupLine>,
    onDone: () -> Unit,
    intro: String? = null,
    confirm: String? = null,
    onCancel: (() -> Unit)? = null,
    extra: Pair<String, () -> Unit>? = null,
) {
    AlertDialog(
        onDismissRequest = onCancel ?: onDone,
        properties = notebookDialogProperties(dismissOnClickOutside = false),
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                intro?.let { Text(it) }
                for (line in lines) Text(line.text(), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text(confirm ?: stringResource(R.string.notebook_done)) } },
        dismissButton = when {
            extra != null -> { { TextButton(onClick = extra.second) { Text(extra.first) } } }
            onCancel != null -> { { TextButton(onClick = onCancel) { Text(stringResource(R.string.notebook_cancel)) } } }
            else -> null
        },
    )
}

@Composable
private fun FailedDialog(failure: BackupFailure, onDone: () -> Unit, onPickFile: (() -> Unit)?) {
    val title = when (failure) {
        is BackupFailure.NotRead -> R.string.notebook_backup_not_made
        BackupFailure.NoSpace -> R.string.notebook_backup_no_space_title
        is BackupFailure.Staging -> R.string.notebook_backup_staging_title
        is BackupFailure.NotSaved, is BackupFailure.Interrupted -> R.string.notebook_backup_not_saved
        BackupFailure.FileUnreadable, is BackupFailure.NotOpened -> R.string.notebook_backup_not_opened
        is BackupFailure.RestoreStopped -> R.string.notebook_restore_stopped
        BackupFailure.RestoreInterrupted -> R.string.notebook_restore_interrupted_title
        BackupFailure.NoPicker -> R.string.notebook_backup_no_picker_title
        BackupFailure.LockFailed, BackupFailure.NoAutomaticBackup -> R.string.notebook_restore_not_started
    }
    // An automatic restore picked no file and typed no password; it offers a file instead ([onPickFile]).
    val automatic = onPickFile != null &&
        (failure == BackupFailure.FileUnreadable || (failure is BackupFailure.NotOpened && failure.problem != BackupProblem.NEWER_FORMAT))
    val message = if (automatic) stringResource(R.string.notebook_restore_fail_automatic) else when (failure) {
        is BackupFailure.NotRead -> stringResource(
            when (failure.problem) {
                StorageProblem.KEY_MISSING -> R.string.notebook_backup_fail_key_missing
                StorageProblem.DAMAGED -> R.string.notebook_backup_fail_damaged
                StorageProblem.UNREADABLE -> R.string.notebook_backup_fail_unreadable
            },
        )
        BackupFailure.NoSpace -> stringResource(R.string.notebook_backup_fail_no_space)
        is BackupFailure.Staging -> stringResource(R.string.notebook_backup_fail_staging) + when (failure.removed) {
            null -> ""
            true -> " " + stringResource(R.string.notebook_backup_fail_empty_removed)
            false -> " " + stringResource(R.string.notebook_backup_fail_untouched)
        }
        is BackupFailure.NotSaved -> {
            val what = stringResource(
                when (failure.failure) {
                    PublishFailure.NOT_EMPTY -> R.string.notebook_backup_fail_not_empty
                    PublishFailure.NOT_CHECKED -> R.string.notebook_backup_fail_not_checked
                    PublishFailure.NOT_OPENED -> R.string.notebook_backup_fail_not_opened
                    PublishFailure.NOT_WRITTEN -> R.string.notebook_backup_fail_not_written
                    PublishFailure.NOT_READ_BACK -> R.string.notebook_backup_fail_not_read_back
                    PublishFailure.NOT_VERIFIED -> R.string.notebook_backup_fail_not_verified
                },
            )
            when (failure.removed) {
                null -> what
                true -> what + " " + stringResource(R.string.notebook_backup_fail_removed)
                false -> what + " " + stringResource(R.string.notebook_backup_fail_not_removed)
            }
        }
        is BackupFailure.Interrupted -> stringResource(
            when {
                failure.written && failure.removed -> R.string.notebook_backup_fail_interrupted
                failure.written -> R.string.notebook_backup_fail_interrupted_kept
                failure.removed -> R.string.notebook_backup_fail_interrupted_empty_removed
                else -> R.string.notebook_backup_fail_interrupted_untouched
            },
        )
        BackupFailure.FileUnreadable -> stringResource(R.string.notebook_backup_fail_file_unreadable)
        is BackupFailure.NotOpened -> stringResource(
            when (failure.problem) {
                BackupProblem.NOT_A_BACKUP -> R.string.notebook_backup_fail_not_a_backup
                BackupProblem.NEWER_FORMAT -> R.string.notebook_backup_fail_newer
                BackupProblem.DAMAGED -> R.string.notebook_backup_fail_file_damaged
                BackupProblem.WRONG_PASSWORD_OR_DAMAGED -> R.string.notebook_backup_wrong_password
            },
        )
        is BackupFailure.RestoreStopped -> stringResource(
            when (failure.reason) {
                RestoreStop.FILE -> R.string.notebook_restore_fail_file
                RestoreStop.STAGING -> R.string.notebook_restore_fail_staging
                RestoreStop.KEY_MISSING -> R.string.notebook_restore_fail_key_missing
                RestoreStop.DAMAGED -> R.string.notebook_restore_fail_damaged
                RestoreStop.UNREADABLE -> R.string.notebook_restore_fail_unreadable
                RestoreStop.KEY_STORE -> R.string.notebook_restore_fail_key_store
            },
        )
        BackupFailure.RestoreInterrupted -> stringResource(R.string.notebook_restore_interrupted)
        BackupFailure.NoPicker -> stringResource(R.string.notebook_backup_fail_no_picker)
        BackupFailure.LockFailed -> stringResource(R.string.notebook_restore_fail_lock)
        BackupFailure.NoAutomaticBackup -> stringResource(R.string.notebook_restore_fail_no_automatic)
    }
    AlertDialog(
        onDismissRequest = onDone,
        properties = notebookDialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(message)
                fileNameOf(failure)?.let { Text(stringResource(R.string.notebook_backup_file, it), style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.notebook_close)) } },
        dismissButton = onPickFile?.let { { TextButton(onClick = it) { Text(stringResource(R.string.notebook_restore_pick_file)) } } },
    )
}

private fun fileNameOf(failure: BackupFailure): String? = when (failure) {
    is BackupFailure.NotSaved -> failure.fileName
    is BackupFailure.Interrupted -> failure.fileName
    is BackupFailure.Staging -> failure.fileName
    else -> null
}

@Composable
private fun BackupLine.text(): String {
    val n = count.toInt()
    return when (text) {
        BackupText.MADE_ON -> stringResource(R.string.notebook_backup_made_on, madeOn(names.single()))
        BackupText.NOTES -> pluralStringResource(R.plurals.notebook_backup_notes, n, n)
        BackupText.FOLDERS -> {
            val listed = names.zip(counts).map { (folder, c) -> stringResource(R.string.notebook_backup_folder_count, folderLabel(folder), c) }.joinToString(", ")
            if (n > 0) stringResource(R.string.notebook_backup_folders_more, listed, n) else stringResource(R.string.notebook_backup_folders, listed)
        }
        BackupText.REMINDERS -> pluralStringResource(R.plurals.notebook_backup_reminders, n, n)
        BackupText.HISTORY -> pluralStringResource(R.plurals.notebook_backup_history, n, n)
        BackupText.CAPTURES -> pluralStringResource(R.plurals.notebook_backup_captures, n, n)
        BackupText.FOLDER_TAGS -> pluralStringResource(R.plurals.notebook_backup_folder_tags, n, n)
        BackupText.SIZE -> stringResource(R.string.notebook_backup_size, Formatter.formatShortFileSize(LocalContext.current, count))
        BackupText.SKIP_NOTES -> pluralStringResource(R.plurals.notebook_backup_skip_notes, n, n)
        BackupText.SKIP_FOLDERS -> pluralStringResource(R.plurals.notebook_backup_skip_folders, n, n)
        BackupText.SKIP_CAPTURES -> pluralStringResource(R.plurals.notebook_backup_skip_captures, n, n)
        BackupText.SKIP_REMINDERS -> pluralStringResource(R.plurals.notebook_backup_skip_reminders, n, n)
        BackupText.SKIP_HISTORY -> pluralStringResource(R.plurals.notebook_backup_skip_history, n, n)
        BackupText.SAVED_AS -> stringResource(R.string.notebook_backup_saved_as, names.firstOrNull() ?: stringResource(R.string.notebook_backup_fail_the_file))
        BackupText.MISSING_NOTES -> pluralStringResource(R.plurals.notebook_backup_missing_notes, n, n)
        BackupText.TOO_LARGE_NOTES -> pluralStringResource(R.plurals.notebook_backup_too_large_notes, n, n)
        BackupText.UNLISTED_NOTES -> pluralStringResource(R.plurals.notebook_backup_unlisted_notes, n, n)
        BackupText.MISSING_CAPTURES -> pluralStringResource(R.plurals.notebook_backup_missing_captures, n, n)
        BackupText.RESTORED_NOTHING -> stringResource(R.string.notebook_restored_nothing)
        BackupText.RESTORED_NOTES_WRITTEN -> pluralStringResource(R.plurals.notebook_restored_notes_written, n, n)
        BackupText.RESTORED_NOTES_KEPT -> pluralStringResource(R.plurals.notebook_restored_notes_kept, n, n)
        BackupText.RESTORED_FOLDERS -> pluralStringResource(R.plurals.notebook_restored_folders, n, n)
        BackupText.RESTORED_FOLDER_TAGS -> pluralStringResource(R.plurals.notebook_restored_folder_tags, n, n)
        BackupText.RESTORED_CAPTURES -> pluralStringResource(R.plurals.notebook_restored_captures, n, n)
        BackupText.RESTORED_CAPTURES_KEPT -> pluralStringResource(R.plurals.notebook_restored_captures_kept, n, n)
        BackupText.RESTORED_REMINDERS -> pluralStringResource(R.plurals.notebook_restored_reminders, n, n)
        BackupText.RESTORED_REMINDERS_KEPT -> pluralStringResource(R.plurals.notebook_restored_reminders_kept, n, n)
        BackupText.RESTORED_HISTORY -> pluralStringResource(R.plurals.notebook_restored_history, n, n)
        BackupText.RESTORED_SKIP_NOTES -> pluralStringResource(R.plurals.notebook_restored_skip_notes, n, n)
        BackupText.RESTORED_SKIP_FOLDERS -> pluralStringResource(R.plurals.notebook_restored_skip_folders, n, n)
        BackupText.RESTORED_SKIP_CAPTURES -> pluralStringResource(R.plurals.notebook_restored_skip_captures, n, n)
        BackupText.RESTORED_SKIP_REMINDERS -> pluralStringResource(R.plurals.notebook_restored_skip_reminders, n, n)
        BackupText.RESTORED_SKIP_HISTORY -> pluralStringResource(R.plurals.notebook_restored_skip_history, n, n)
    }
}

private fun madeOn(iso: String): String = try {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(Instant.parse(iso))
} catch (e: Exception) {
    iso
}

/** Whether a restore with this plan leaves the reminders already here, as its confirmation then says. */
internal fun RecoveryPlan?.keepsReminders() = this == null || (this is RecoveryPlan.StartFresh && NotebookPart.REMINDERS !in parts)
