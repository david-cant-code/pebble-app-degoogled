package com.anopticlabs.gravel.notebook.ui

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.anopticlabs.gravel.notebook.R
import com.anopticlabs.gravel.notebook.reminders.CompletionCandidate
import com.anopticlabs.gravel.notebook.reminders.RecurrenceFrequency
import com.anopticlabs.gravel.notebook.reminders.RecurrenceRule
import com.anopticlabs.gravel.notebook.reminders.ReminderEvent
import com.anopticlabs.gravel.notebook.reminders.ReminderEventKey
import com.anopticlabs.gravel.notebook.reminders.ReminderItem
import com.anopticlabs.gravel.notebook.reminders.ReminderListing
import com.anopticlabs.gravel.notebook.reminders.VoiceCompletion
import com.anopticlabs.gravel.notebook.reminders.VoiceCompletionAction
import com.anopticlabs.gravel.notebook.reminders.hasAlreadyCompletedCandidate
import com.anopticlabs.gravel.notebook.reminders.resolutionActionsFor
import kotlinx.coroutines.launch

/**
 * The Notebook tab's Reminders section (the list: due, snoozed, upcoming) or Calendar section, by
 * [view], which it hands the ViewModel; the completion review and the editors open over either.
 */
@Composable
internal fun RemindersSection(viewModel: RemindersViewModel, state: RemindersState, view: RemindersView, showMessage: (String) -> Unit) {
    val resources = LocalResources.current
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.watch() }
    }
    LaunchedEffect(viewModel, view) { viewModel.showView(view) }
    val reviewing = state.claims != null
    // While the review is open its own window shows the messages; this snackbar would sit under it.
    LaunchedEffect(state.messages, reviewing) {
        if (reviewing) return@LaunchedEffect
        state.messages.firstOrNull()?.let {
            showMessage(remindersMessageText(resources, it))
            viewModel.messageShown(it)
        }
    }
    val times = rememberReminderTimes()
    Box(Modifier.fillMaxSize()) {
        when (val data = state.data) {
            Loadable.Loading -> Centered { CircularProgressIndicator() }
            is Loadable.Failed -> Problem(data.problem, viewModel::retry)
            is Loadable.Ready -> when (view) {
                RemindersView.LIST -> state.listing?.let { ReminderList(it, state.now, times, viewModel) }
                RemindersView.CALENDAR -> state.calendar?.let { ReminderCalendar(it, state.now, times, viewModel) }
            }
        }
        NewReminderButton(viewModel::newReminder, Modifier.align(Alignment.BottomEnd).padding(vertical = 16.dp))
    }
    // Composed first, so the editor and the confirmations opened from the review or a calendar row stack above them; the review's link waits for them.
    if (reviewing) CompletionSheet(state, times, viewModel)
    state.infoRow?.let { OccurrenceInfoSheet(it, state.busy, state.now, times, viewModel) }
    state.form?.let { ReminderEditor(it, times, viewModel) }
    state.confirm?.let { ConfirmDialog(it, state.busy, state.now, times, viewModel) }
    state.snooze?.let { SnoozeSheet(it, state.busy, state.now, times, viewModel) }
}

/** The Reminders tab's New reminder button, named for screen readers. */
@Composable
fun NewReminderButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.notebook_reminders_new)
    // The extended button hides its text from accessibility and takes its label from the icon (androidx-main
    // compose/material3 FloatingActionButton.kt, ExtendedFloatingActionButton; the same in material3-android 1.5.0-alpha08).
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(Icons.Filled.Add, contentDescription = label) },
        text = { Text(label) },
        modifier = modifier,
    )
}

internal fun remindersMessageText(resources: Resources, message: RemindersMessage): String = when (message) {
    RemindersMessage.Saved -> resources.getString(R.string.notebook_reminders_saved)
    RemindersMessage.Done -> resources.getString(R.string.notebook_reminders_done)
    RemindersMessage.DoneNotRecorded -> resources.getString(R.string.notebook_reminders_done_not_recorded)
    RemindersMessage.DeletedNotRecorded -> resources.getString(R.string.notebook_reminders_deleted_not_recorded)
    RemindersMessage.NotChanged -> resources.getString(R.string.notebook_not_changed)
    RemindersMessage.NotRead -> resources.getString(R.string.notebook_reminders_not_read)
    RemindersMessage.NotSnoozed -> resources.getString(R.string.notebook_reminders_not_snoozed)
    RemindersMessage.Gone -> resources.getString(R.string.notebook_reminders_gone)
    RemindersMessage.SavedAsNote -> resources.getString(R.string.notebook_reminders_saved_as_note)
    RemindersMessage.CannotSnooze -> resources.getString(R.string.notebook_reminders_cannot_snooze)
    RemindersMessage.DoneClaimKept -> resources.getString(R.string.notebook_completions_done_kept)
    RemindersMessage.DoneNotRecordedClaimKept -> resources.getString(R.string.notebook_completions_done_not_recorded_kept)
    RemindersMessage.ClaimGone -> resources.getString(R.string.notebook_completions_gone)
    RemindersMessage.SavedClaimKept -> resources.getString(R.string.notebook_completions_saved_kept)
    RemindersMessage.SavedAsNoteClaimKept -> resources.getString(R.string.notebook_completions_saved_as_note_kept)
    RemindersMessage.ClaimRemoved -> resources.getString(R.string.notebook_completions_removed)
    is RemindersMessage.Snoozed -> resources.getQuantityString(R.plurals.notebook_reminders_snoozed_for, message.minutes, message.minutes)
}

@Composable
private fun ReminderList(listing: ReminderListing, now: Long, times: ReminderTimes, viewModel: RemindersViewModel) {
    if (listing.dueNow.isEmpty() && listing.snoozed.isEmpty() && listing.upcoming.isEmpty()) {
        Centered { Text(stringResource(R.string.notebook_reminders_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (listing.dueNow.isNotEmpty()) {
            item(key = "due") { Header(R.string.notebook_reminders_due_now) }
            items(listing.dueNow, key = { "due-${it.reminderId}-${it.originalFireMillis}" }) {
                OccurrenceRow(it, stringResource(R.string.notebook_reminders_due_at, times.format(it.latestSnoozeFireAt ?: it.originalFireMillis, now)), viewModel)
            }
        }
        if (listing.snoozed.isNotEmpty()) {
            item(key = "snoozed") { Header(R.string.notebook_reminders_snoozed) }
            items(listing.snoozed, key = { "snoozed-${it.reminderId}-${it.originalFireMillis}" }) {
                OccurrenceRow(it, stringResource(R.string.notebook_reminders_snoozed_until, times.format(it.latestSnoozeFireAt ?: it.originalFireMillis, now)), viewModel)
            }
        }
        if (listing.upcoming.isNotEmpty()) {
            item(key = "upcoming") { Header(R.string.notebook_reminders_upcoming) }
            items(listing.upcoming, key = { "item-${it.id}" }) { UpcomingRow(it, it.id in listing.wentOff, now, times, viewModel) }
        }
    }
}

@Composable
private fun Header(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
internal fun RowCard(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) { content() }
}

@Composable
internal fun RowText(message: String, detail: String?, extra: String? = null, modifier: Modifier) {
    Column(modifier.padding(vertical = 12.dp)) {
        Text(message, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        extra?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun OccurrenceRow(event: ReminderEvent, detail: String, viewModel: RemindersViewModel) {
    RowCard {
        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            RowText(event.message, detail, modifier = Modifier.weight(1f))
            IconButton(onClick = { viewModel.ask(ReminderConfirm.Complete(event.key, event.message, repeats = false)) }) {
                Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.notebook_reminders_mark_complete), tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = { viewModel.ask(ReminderConfirm.NotDone(event.key, event.message, OccurrenceScope.OCCURRENCE)) }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.notebook_reminders_mark_missed))
            }
        }
    }
}

@Composable
private fun UpcomingRow(item: ReminderItem, wentOff: Boolean, now: Long, times: ReminderTimes, viewModel: RemindersViewModel) {
    val key = ReminderEventKey(item.id, item.remindAtMillis)
    val repeats = item.recurrence != null
    val time = times.format(item.remindAtMillis, now)
    RowCard(onClick = { viewModel.editReminder(item.id) }) {
        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            RowText(
                item.message,
                when {
                    wentOff -> stringResource(R.string.notebook_reminders_went_off, time)
                    item.remindAtMillis <= now -> stringResource(R.string.notebook_reminders_late, time)
                    item.timeToCheck -> stringResource(R.string.notebook_reminders_check_time, time)
                    else -> time
                },
                item.recurrence?.let { repeatText(it) },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { viewModel.ask(ReminderConfirm.Complete(key, item.message, repeats)) }) {
                Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.notebook_reminders_mark_complete), tint = MaterialTheme.colorScheme.primary)
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.notebook_reminders_more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, properties = notebookMenuProperties()) {
                    if (item.timeToCheck) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.notebook_reminders_keep_time)) },
                            onClick = {
                                menu = false
                                viewModel.keepTime(item.id)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.notebook_reminders_mark_missed)) },
                        onClick = {
                            menu = false
                            viewModel.ask(ReminderConfirm.NotDone(key, item.message, occurrenceScope(logged = false, repeats = repeats)))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.notebook_reminders_delete), color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menu = false
                            viewModel.ask(ReminderConfirm.DeleteReminder(item))
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun repeatText(rule: RecurrenceRule): String = pluralStringResource(
    when (rule.frequency) {
        RecurrenceFrequency.DAILY -> R.plurals.notebook_reminders_repeats_daily
        RecurrenceFrequency.WEEKLY -> R.plurals.notebook_reminders_repeats_weekly
        RecurrenceFrequency.MONTHLY -> R.plurals.notebook_reminders_repeats_monthly
        RecurrenceFrequency.YEARLY -> R.plurals.notebook_reminders_repeats_yearly
    },
    rule.interval,
    rule.interval,
)

@Composable
private fun ConfirmDialog(confirm: ReminderConfirm, busy: Boolean, now: Long, times: ReminderTimes, viewModel: RemindersViewModel) {
    val (title, body, action) = when (confirm) {
        is ReminderConfirm.Complete -> Triple(
            R.string.notebook_reminders_confirm_complete,
            R.string.notebook_reminders_confirm_complete_repeats.takeIf { confirm.repeats },
            R.string.notebook_reminders_mark_complete,
        )
        is ReminderConfirm.NotDone -> Triple(
            R.string.notebook_reminders_confirm_missed,
            when (confirm.scope) {
                OccurrenceScope.OCCURRENCE -> R.string.notebook_reminders_confirm_missed_occurrence
                OccurrenceScope.ONE_TIME -> R.string.notebook_reminders_confirm_missed_once
                OccurrenceScope.REPEATING -> R.string.notebook_reminders_confirm_missed_repeats
            },
            R.string.notebook_reminders_mark_missed,
        )
        is ReminderConfirm.DeleteReminder -> Triple(
            R.string.notebook_reminders_confirm_delete,
            if (confirm.item.recurrence != null) R.string.notebook_reminders_confirm_delete_repeats else R.string.notebook_reminders_confirm_delete_once,
            R.string.notebook_reminders_delete,
        )
        is ReminderConfirm.DeleteOccurrence -> Triple(
            if (confirm.scope == OccurrenceScope.ONE_TIME) R.string.notebook_reminders_confirm_delete else R.string.notebook_reminders_confirm_delete_time,
            when (confirm.scope) {
                OccurrenceScope.OCCURRENCE -> R.string.notebook_reminders_confirm_delete_one_body
                OccurrenceScope.ONE_TIME -> R.string.notebook_reminders_confirm_delete_next_once
                OccurrenceScope.REPEATING -> R.string.notebook_reminders_confirm_delete_next_repeats
            },
            R.string.notebook_reminders_delete_one,
        )
        is ReminderConfirm.SwitchStatus -> Triple(
            if (confirm.completed) R.string.notebook_calendar_confirm_to_completed else R.string.notebook_calendar_confirm_to_missed,
            if (confirm.completed) R.string.notebook_calendar_confirm_to_completed_body else R.string.notebook_calendar_confirm_to_missed_body,
            if (confirm.completed) R.string.notebook_calendar_to_completed else R.string.notebook_calendar_to_missed,
        )
        is ReminderConfirm.ClaimAsNote -> Triple(
            R.string.notebook_reminders_confirm_save_as_note,
            R.string.notebook_completions_confirm_note_body,
            R.string.notebook_reminders_save_as_note,
        )
        is ReminderConfirm.ClaimAlreadyDone -> Triple(
            R.string.notebook_completions_confirm_already_done,
            R.string.notebook_completions_confirm_already_done_body,
            R.string.notebook_completions_remove,
        )
        is ReminderConfirm.DisregardClaim -> Triple(
            R.string.notebook_completions_confirm_disregard,
            R.string.notebook_completions_confirm_disregard_body,
            R.string.notebook_completions_disregard,
        )
    }
    val message = when (confirm) {
        is ReminderConfirm.Complete -> confirm.message
        is ReminderConfirm.NotDone -> confirm.message
        is ReminderConfirm.DeleteReminder -> confirm.item.message
        is ReminderConfirm.DeleteOccurrence -> confirm.message
        is ReminderConfirm.SwitchStatus -> confirm.message
        is ReminderConfirm.ClaimAsNote -> claimText(confirm.claim)
        is ReminderConfirm.ClaimAlreadyDone -> claimText(confirm.claim)
        is ReminderConfirm.DisregardClaim -> claimText(confirm.claim)
    }
    val destructive = confirm is ReminderConfirm.DeleteReminder || confirm is ReminderConfirm.DeleteOccurrence ||
        confirm is ReminderConfirm.DisregardClaim
    AlertDialog(
        onDismissRequest = viewModel::dismissConfirm,
        properties = notebookDialogProperties(),
        title = { Text(stringResource(title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 4, overflow = TextOverflow.Ellipsis)
                val at = when (confirm) {
                    is ReminderConfirm.Complete -> confirm.key.originalFireMillis
                    is ReminderConfirm.NotDone -> confirm.key.originalFireMillis
                    is ReminderConfirm.DeleteOccurrence -> confirm.key.originalFireMillis
                    is ReminderConfirm.SwitchStatus -> confirm.key.originalFireMillis
                    else -> null
                }
                at?.let { Text(times.format(it, now), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                body?.let { Text(stringResource(it)) }
                if (confirm.resolves != null) Text(stringResource(R.string.notebook_completions_confirm_leaves))
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = { viewModel.confirm(confirm) }) {
                Text(stringResource(action), color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = viewModel::dismissConfirm) { Text(stringResource(R.string.notebook_cancel)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SnoozeSheet(target: SnoozeTarget, busy: Boolean, now: Long, times: ReminderTimes, viewModel: RemindersViewModel) {
    var custom by remember(target) { mutableStateOf("") }
    val minutes = custom.toIntOrNull()?.takeIf { it in 1..RemindersViewModel.MAX_SNOOZE_MINUTES }
    ModalBottomSheet(onDismissRequest = viewModel::dismissSnooze, properties = notebookSheetProperties()) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.notebook_reminders_snooze_title), style = MaterialTheme.typography.titleMedium)
            Text(target.message, maxLines = 3, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (preset in SNOOZE_PRESETS) {
                    AssistChip(
                        enabled = !busy,
                        onClick = { viewModel.snooze(target, preset) },
                        label = {
                            Text(if (preset == 60) stringResource(R.string.notebook_reminders_snooze_hour) else stringResource(R.string.notebook_reminders_snooze_preset, preset))
                        },
                    )
                }
            }
            OutlinedTextField(
                value = custom,
                onValueChange = { value -> if (value.length <= 4 && value.all { it.isDigit() }) custom = value },
                label = { Text(stringResource(R.string.notebook_reminders_snooze_minutes)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            if (minutes != null && snoozePassesNextTime(target, minutes, now)) {
                target.nextMillis?.let {
                    Text(stringResource(R.string.notebook_reminders_snooze_past_next, times.format(it, now)), color = MaterialTheme.colorScheme.error)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = viewModel::dismissSnooze) { Text(stringResource(R.string.notebook_cancel)) }
                Button(enabled = !busy && minutes != null, onClick = { minutes?.let { viewModel.snooze(target, it) } }) {
                    Text(stringResource(R.string.notebook_reminders_snooze))
                }
            }
        }
    }
}

private val SNOOZE_PRESETS = listOf(5, 10, 30, 60)

@Composable
private fun claimText(claim: VoiceCompletion): String = claim.transcript.ifBlank { stringResource(R.string.notebook_completions_no_words) }

/** The completion review: each parked claim with its candidates, and the ways out for one that matches none. */
@Composable
private fun CompletionSheet(state: RemindersState, times: ReminderTimes, viewModel: RemindersViewModel) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(state.messages) {
        state.messages.firstOrNull()?.let {
            val text = remindersMessageText(resources, it)
            scope.launch { snackbar.showSnackbar(text) }
            viewModel.messageShown(it)
        }
    }
    ModalBottomSheet(onDismissRequest = viewModel::closeCompletions, sheetState = sheet, properties = notebookSheetProperties()) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.notebook_completions_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { scope.launch { sheet.hide() }.invokeOnCompletion { viewModel.closeCompletions() } }) {
                    Text(stringResource(R.string.notebook_completions_close))
                }
            }
            val claims = state.claims
            val data = state.data
            when {
                claims is Loadable.Failed -> Box(Modifier.heightIn(max = 240.dp)) { Problem(claims.problem, viewModel::retry) }
                data is Loadable.Failed -> Box(Modifier.heightIn(max = 240.dp)) { Problem(data.problem, viewModel::retry) }
                claims !is Loadable.Ready || data !is Loadable.Ready ->
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.review.isEmpty() -> Text(stringResource(R.string.notebook_completions_all_reviewed))
                else -> {
                    Text(stringResource(R.string.notebook_completions_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(state.review, key = { it.claim.completionId }) {
                            ClaimCard(it, it.claim.completionId in state.settled, state.busy, state.now, times, viewModel)
                        }
                    }
                }
            }
            SnackbarHost(snackbar)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClaimCard(review: ClaimReview, settled: Boolean, busy: Boolean, now: Long, times: ReminderTimes, viewModel: RemindersViewModel) {
    val claim = review.claim
    val spoken = claim.transcript.isNotBlank()
    RowCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(claimText(claim), style = MaterialTheme.typography.bodyLarge, maxLines = 6, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.notebook_completions_said_at, times.format(claim.capturedAt, now)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                settled -> Text(stringResource(R.string.notebook_completions_settled))
                review.candidates.isEmpty() -> Text(stringResource(R.string.notebook_completions_no_match))
                else -> for (candidate in review.candidates) CandidateRow(claim, candidate, busy, now, times, viewModel)
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                if (!settled) {
                    if (hasAlreadyCompletedCandidate(review.candidates.map { it.match })) {
                        TextButton(enabled = !busy, onClick = { viewModel.ask(ReminderConfirm.ClaimAlreadyDone(claim)) }) {
                            Text(stringResource(R.string.notebook_completions_already_done))
                        }
                    }
                    if (spoken) {
                        TextButton(enabled = !busy, onClick = { viewModel.ask(ReminderConfirm.ClaimAsNote(claim)) }) {
                            Text(stringResource(R.string.notebook_completions_was_note))
                        }
                        TextButton(enabled = !busy, onClick = { viewModel.newReminderFromClaim(claim) }) { Text(stringResource(R.string.notebook_reminders_new)) }
                    }
                }
                TextButton(enabled = !busy, onClick = { viewModel.ask(ReminderConfirm.DisregardClaim(claim)) }) {
                    Text(stringResource(R.string.notebook_completions_disregard), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(claim: VoiceCompletion, candidate: CompletionCandidate, busy: Boolean, now: Long, times: ReminderTimes, viewModel: RemindersViewModel) {
    val match = candidate.match
    val event = candidate.event
    val due = times.format(match.originalFireMillis, now)
    val snoozedTo = event?.latestSnoozeFireAt?.takeIf { it > now }
    val detail = when {
        event == null -> if (match.originalFireMillis <= now) stringResource(R.string.notebook_reminders_late, due) else due
        event.completedAt != null -> stringResource(R.string.notebook_completions_candidate_complete, due)
        event.cancelledAt != null -> stringResource(R.string.notebook_completions_candidate_missed, due)
        snoozedTo != null -> stringResource(R.string.notebook_reminders_snoozed_until, times.format(snoozedTo, now))
        else -> stringResource(R.string.notebook_reminders_went_off, due)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        RowText(match.message, detail, modifier = Modifier.weight(1f))
        for (action in resolutionActionsFor(match.status)) {
            val confirm = claimConfirm(claim, candidate, action) ?: continue
            IconButton(enabled = !busy, onClick = { viewModel.ask(confirm) }) {
                when (action) {
                    VoiceCompletionAction.COMPLETE ->
                        Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.notebook_reminders_mark_complete), tint = MaterialTheme.colorScheme.primary)
                    VoiceCompletionAction.NOT_DONE ->
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.notebook_reminders_mark_missed))
                }
            }
        }
    }
}
