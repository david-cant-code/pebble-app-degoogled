package com.anopticlabs.gravel.notebook.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.anopticlabs.gravel.notebook.R
import com.anopticlabs.gravel.notebook.capture.CaptureQueueItem
import com.anopticlabs.gravel.notebook.capture.CaptureQueueProgress
import com.anopticlabs.gravel.notebook.capture.ProcessingPause
import com.anopticlabs.gravel.notebook.capture.captureProgressText
import com.anopticlabs.gravel.notebook.capture.captureQueueProgress
import com.anopticlabs.gravel.notebook.capture.captureQueueRowLabel
import com.anopticlabs.gravel.notebook.pipeline.CaptureHold
import com.anopticlabs.gravel.notebook.pipeline.CaptureNotice
import com.anopticlabs.gravel.notebook.pipeline.CaptureRemedy
import com.anopticlabs.gravel.notebook.sources.TypedNoteResult
import coredevices.pebble.ui.TopBarParams
import coredevices.util.Permission
import coredevices.util.PermissionRequester
import coredevices.util.rememberUiContext
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * The Notebook tab: the capture area above the Notes, Reminders and Calendar sections, or [setupGuide] in their place.
 * With [recordingSupported] false the capture area offers typed notes only, and while it is null no record button yet.
 * [notice] is drawn under the backup reminder line, above the capture area.
 */
@Composable
fun NotebookScreen(
    topBarParams: TopBarParams,
    openTextModels: () -> Unit,
    recordingSupported: Boolean? = true,
    setupGuide: (@Composable () -> Unit)? = null,
    openSpeechSettings: () -> Unit = {},
    notice: (@Composable () -> Unit)? = null,
) {
    val title = stringResource(R.string.notebook_title)
    LaunchedEffect(title) {
        topBarParams.searchAvailable(null)
        topBarParams.actions {}
        topBarParams.title(title)
    }
    NotebookSecureContent {
        if (setupGuide != null) setupGuide() else NotebookContent(topBarParams, HoldActions(openTextModels, openSpeechSettings), recordingSupported, notice)
    }
}

@Composable
private fun NotebookContent(topBarParams: TopBarParams, links: HoldActions, recordingSupported: Boolean?, tabNotice: (@Composable () -> Unit)?) {
    val viewModel = koinViewModel<NotebookViewModel>()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.watch() }
    }
    val items by viewModel.items.collectAsState()
    val batch by viewModel.batchSubmitted.collectAsState()
    val held by viewModel.held.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val status by viewModel.status.collectAsState()
    val pause by viewModel.pause.collectAsState()
    val recoveryState by viewModel.recoveryState.collectAsState()
    val recorderState by viewModel.recorderState.collectAsState()
    val recorderNotice by viewModel.recorderNotice.collectAsState()
    val permissions = koinInject<PermissionRequester>()
    val uiContext = rememberUiContext()
    val scope = rememberCoroutineScope()
    val savedText = stringResource(R.string.notebook_note_saved)
    val notes = koinViewModel<NotesViewModel>()
    val notesState by notes.state.collectAsState()
    val reminders = koinViewModel<RemindersViewModel>()
    val remindersState by reminders.state.collectAsState()
    val noteOpen = viewModel.section == NotebookSection.NOTES && notesState.page is NotesPage.Note
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!noteOpen) {
            // The Stop button lives here, so hiding it (leaving the tab or opening a note) ends a recording the way leaving the app does.
            DisposableEffect(viewModel) {
                onDispose { viewModel.stopRecording() }
            }
            BackupReminderLine()
            tabNotice?.invoke()
            status?.let { StorageTroubleCard(it, recoveryState, viewModel) }
            CaptureBar(
                recordingSupported = recordingSupported,
                state = recorderState,
                notice = recorderNotice,
                micProblem = viewModel.micProblem,
                onRecord = {
                    scope.launch {
                        viewModel.record(
                            hasMic = { permissions.hasPermission(Permission.RecordAudio) },
                            requestMic = uiContext?.let { ctx -> { permissions.requestPermission(Permission.RecordAudio, ctx) } },
                        )
                    }
                },
                onStop = viewModel::stopRecording,
                onNewNote = { viewModel.openDraft() },
                onOpenSettings = { uiContext?.let(permissions::openPermissionsScreen) },
            )
            CaptureStatusLines(
                captureQueueProgress(items, batch), held, notice, status, links.copy(tryServerAgain = viewModel::tryServerAgain),
                ProcessingActions(pause, viewModel::pauseAll, viewModel::pauseLong, viewModel::resume, viewModel::processNow),
                viewModel::openFailed,
            )
            status?.failed?.takeIf { viewModel.failedOpen && (it.isNotEmpty() || viewModel.failedNotice != null) }?.let { FailedCapturesSheet(it, viewModel) }
            status?.completionsToReview?.takeIf { it > 0 }?.let {
                CompletionBanner(it, onReview = {
                    viewModel.select(remindersFrom(viewModel.section))
                    reminders.openCompletions()
                })
            }
            if (items.isNotEmpty()) {
                CaptureResults(
                    items,
                    onOpen = { item ->
                        sectionFor(item)?.let { viewModel.select(if (it == NotebookSection.REMINDERS) remindersFrom(viewModel.section) else it) }
                        when (item.status) {
                            CaptureQueueItem.Status.NOTE_CREATED -> item.noteId?.let(notes::showNote)
                            CaptureQueueItem.Status.REMINDER_SET -> item.reminderId?.let(reminders::editReminder)
                            else -> Unit
                        }
                    },
                    onDismiss = viewModel::dismiss,
                )
            }
            SectionRow(viewModel.section) { section ->
                viewModel.select(section)
                when (section) {
                    NotebookSection.NOTES -> Unit
                    NotebookSection.REMINDERS -> reminders.showView(RemindersView.LIST)
                    NotebookSection.CALENDAR -> reminders.showCalendarOnToday()
                }
            }
        }
        viewModel.draft?.let { draft ->
            NoteEditor(
                draft = draft,
                loadFolders = viewModel::typedNoteFolders,
                save = { typed ->
                    when (val result = viewModel.saveTypedNote(typed.title, typed.body, typed.folder)) {
                        is TypedNoteResult.Saved -> EditorOutcome.Saved(result.folder)
                        TypedNoteResult.TitleMissing -> EditorOutcome.Refused(EditorError.TITLE_MISSING)
                        TypedNoteResult.TooLarge -> EditorOutcome.Refused(EditorError.TOO_LARGE)
                        null -> EditorOutcome.Refused(EditorError.NOT_SAVED)
                    }
                },
                onSaved = {
                    viewModel.closeDraft()
                    topBarParams.showSnackbar(savedText)
                },
                onDiscard = viewModel::closeDraft,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (viewModel.section) {
                NotebookSection.NOTES -> NotesSection(notes, notesState, onNewNote = viewModel::openDraft, showMessage = topBarParams::showSnackbar)
                // One call site for both sections: the Reminders ViewModel's watch runs in it and should not restart on a switch between them.
                NotebookSection.REMINDERS, NotebookSection.CALENDAR -> RemindersSection(
                    reminders,
                    remindersState,
                    view = viewFor(viewModel.section) ?: RemindersView.LIST,
                    showMessage = topBarParams::showSnackbar,
                )
            }
        }
    }
}

private class ProcessingActions(
    val pause: ProcessingPause,
    val pauseAll: () -> Unit,
    val pauseLong: () -> Unit,
    val resume: () -> Unit,
    val processNow: (NotebookStatus) -> Unit,
)

@Composable
private fun RemedyButtons(remedy: CaptureRemedy?, holdActions: HoldActions) {
    when (remedy) {
        CaptureRemedy.TEXT_MODEL -> TextButton(onClick = holdActions.openTextModels) { Text(stringResource(R.string.notebook_text_model_settings)) }
        CaptureRemedy.SPEECH_SETTINGS -> TextButton(onClick = holdActions.openSpeechSettings) { Text(stringResource(R.string.notebook_speech_settings)) }
        CaptureRemedy.SERVER_CHECK -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = holdActions.openSpeechSettings) { Text(stringResource(R.string.notebook_speech_settings)) }
            TextButton(onClick = holdActions.tryServerAgain) { Text(stringResource(R.string.notebook_server_try_again)) }
        }
        null -> {}
    }
}

/** What the buttons of a hold or notice line open or do. */
private data class HoldActions(
    val openTextModels: () -> Unit,
    val openSpeechSettings: () -> Unit,
    val tryServerAgain: () -> Unit = {},
)

@Composable
private fun CaptureStatusLines(
    progress: CaptureQueueProgress,
    held: CaptureHold?,
    notice: CaptureNotice?,
    status: NotebookStatus?,
    holdActions: HoldActions,
    actions: ProcessingActions,
    openFailed: () -> Unit,
) {
    if (progress.active) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(captureProgressText(progress), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        if (actions.pause != ProcessingPause.ALL) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = actions.pauseAll) { Text(stringResource(R.string.notebook_pause_all)) }
                if (actions.pause == ProcessingPause.NONE && status?.longToRun == true) {
                    TextButton(onClick = actions.pauseLong) { Text(stringResource(R.string.notebook_pause_long)) }
                }
            }
        }
    }
    if (actions.pause != ProcessingPause.NONE) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (actions.pause == ProcessingPause.ALL) R.string.notebook_paused_all else R.string.notebook_paused_long),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = actions.resume) { Text(stringResource(R.string.notebook_resume)) }
        }
    }
    val forPower = status?.waitingForPower.orEmpty().size
    if (forPower > 0 && actions.pause == ProcessingPause.NONE) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                pluralStringResource(R.plurals.notebook_waiting_for_power, forPower, forPower),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { status?.let(actions.processNow) }) { Text(stringResource(R.string.notebook_process_now)) }
        }
    }
    if (held != null && !progress.active) {
        Text(stringResource(if (held.waitOnly) R.string.notebook_held_some else R.string.notebook_held, held.reason), color = MaterialTheme.colorScheme.onSurfaceVariant)
        RemedyButtons(held.remedy, holdActions)
    }
    if (notice != null && !progress.active) {
        Text(notice.reason, color = MaterialTheme.colorScheme.onSurfaceVariant)
        RemedyButtons(notice.remedy, holdActions)
    }
    val waiting = status?.waiting ?: 0
    if (waiting > 0 && !progress.active) {
        Text(pluralStringResource(R.plurals.notebook_waiting, waiting, waiting), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val failed = status?.failed.orEmpty().size
    if (failed > 0) {
        Row(Modifier.clickable(onClick = openFailed), verticalAlignment = Alignment.CenterVertically) {
            Text(pluralStringResource(R.plurals.notebook_flagged, failed, failed), color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
            TextButton(onClick = openFailed) { Text(stringResource(R.string.notebook_failed_review)) }
        }
    }
    val retained = status?.retained ?: 0
    if (retained > 0) {
        Text(pluralStringResource(R.plurals.notebook_retained, retained, retained), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BackupReminderLine() {
    val reminder = koinInject<BackupReminder>()
    // Collected only while the screen is started, so coming back to it reads the clock again.
    val action by reminder.action.collectAsStateWithLifecycle(null)
    val shown = action ?: return
    Column {
        Text(
            stringResource(
                when (shown) {
                    BackupReminderAction.SET_UP -> R.string.notebook_backup_nudge
                    BackupReminderAction.TURN_ON -> R.string.notebook_backup_nudge_off
                    BackupReminderAction.SET_UP_AGAIN -> R.string.notebook_backup_nudge_again
                }
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { reminder.act(shown) }) {
                Text(
                    stringResource(
                        when (shown) {
                            BackupReminderAction.SET_UP -> R.string.notebook_backup_nudge_set_up
                            BackupReminderAction.TURN_ON -> R.string.notebook_backup_nudge_turn_on
                            BackupReminderAction.SET_UP_AGAIN -> R.string.notebook_backup_nudge_set_up_again
                        }
                    )
                )
            }
            TextButton(onClick = reminder::notNow) { Text(stringResource(R.string.notebook_backup_nudge_not_now)) }
            TextButton(onClick = reminder::never) { Text(stringResource(R.string.notebook_backup_nudge_never)) }
        }
    }
}

@Composable
private fun CompletionBanner(count: Int, onReview: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onReview),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(pluralStringResource(R.plurals.notebook_completions, count, count), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.notebook_completions_hint), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** The Reminders ViewModel's view for [section]; null for Notes. */
internal fun viewFor(section: NotebookSection): RemindersView? = when (section) {
    NotebookSection.NOTES -> null
    NotebookSection.REMINDERS -> RemindersView.LIST
    NotebookSection.CALENDAR -> RemindersView.CALENDAR
}

/** The section a settled row's tap opens, or null for a row with nothing to open. */
internal fun sectionFor(item: CaptureQueueItem): NotebookSection? = when (item.status) {
    CaptureQueueItem.Status.NOTE_CREATED -> NotebookSection.NOTES
    CaptureQueueItem.Status.REMINDER_SET -> NotebookSection.REMINDERS
    else -> null
}

@Composable
private fun CaptureResults(items: List<CaptureQueueItem>, onOpen: (CaptureQueueItem) -> Unit, onDismiss: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    Column {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.notebook_results_title, items.size),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = stringResource(if (expanded) R.string.notebook_results_hide else R.string.notebook_results_show),
            )
        }
        if (expanded) {
            // Bounded with its own scroll, so a long batch never pushes the sections off the screen.
            Column(
                Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items.sortedBy { it.createdAt }.forEach { item ->
                    CaptureResultRow(item, onOpen = sectionFor(item)?.let { { onOpen(item) } }, onDismiss = { onDismiss(item.id) })
                }
            }
        }
    }
}

@Composable
private fun CaptureResultRow(item: CaptureQueueItem, onOpen: (() -> Unit)?, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (item.inFlight) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                val (icon, tint) = when (item.status) {
                    CaptureQueueItem.Status.REMINDER_SET -> Icons.Filled.Alarm to MaterialTheme.colorScheme.primary
                    CaptureQueueItem.Status.FAILED -> Icons.Filled.ErrorOutline to MaterialTheme.colorScheme.error
                    else -> Icons.Filled.Description to MaterialTheme.colorScheme.primary
                }
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f).padding(vertical = if (item.inFlight) 8.dp else 0.dp)) {
                Text(
                    stringResource(
                        when {
                            item.inFlight -> R.string.notebook_result_working
                            item.status == CaptureQueueItem.Status.NOTE_CREATED -> R.string.notebook_result_note
                            item.status == CaptureQueueItem.Status.REMINDER_SET -> R.string.notebook_result_reminder
                            else -> R.string.notebook_result_failed
                        }
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(captureQueueRowLabel(item), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (!item.inFlight) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.notebook_result_dismiss))
                }
            }
        }
    }
}

@Composable
private fun SectionRow(selected: NotebookSection, onSelect: (NotebookSection) -> Unit) {
    val sections = NotebookSection.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        sections.forEachIndexed { index, section ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index, sections.size),
                selected = section == selected,
                onClick = { onSelect(section) },
                label = {
                    Text(
                        stringResource(
                            when (section) {
                                NotebookSection.NOTES -> R.string.notebook_section_notes
                                NotebookSection.REMINDERS -> R.string.notebook_section_reminders
                                NotebookSection.CALENDAR -> R.string.notebook_section_calendar
                            }
                        )
                    )
                },
            )
        }
    }
}
