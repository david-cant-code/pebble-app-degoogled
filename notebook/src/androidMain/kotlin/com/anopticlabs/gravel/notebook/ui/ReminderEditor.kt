package com.anopticlabs.gravel.notebook.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.TimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.anopticlabs.gravel.notebook.R
import com.anopticlabs.gravel.notebook.reminders.MAX_EDITOR_INTERVAL
import com.anopticlabs.gravel.notebook.reminders.RecurrenceFrequency
import com.anopticlabs.gravel.notebook.reminders.ReminderEditPlan
import com.anopticlabs.gravel.notebook.reminders.ReminderEditProblem
import com.anopticlabs.gravel.notebook.reminders.planReminderEdit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

/** The reminder editor: text, date, time and repeat, saved through [RemindersViewModel.save]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReminderEditor(form: ReminderForm, times: ReminderTimes, viewModel: RemindersViewModel) {
    var error by remember(form) { mutableStateOf<ReminderFormError?>(null) }
    var saving by remember(form) { mutableStateOf(false) }
    var atLimit by remember(form) { mutableStateOf(false) }
    var confirmDiscard by remember(form) { mutableStateOf(false) }
    var pickDate by remember(form) { mutableStateOf(false) }
    var pickTime by remember(form) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Ticks so a time that passes while the editor is open disables Save.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }
    val plan = planReminderEdit(
        form.message, form.atMillis, form.frequency.takeIf { form.repeats }, form.interval,
        (form.target as? ReminderFormTarget.Edit)?.item, now, TimeZone.currentSystemDefault(),
    )
    val close = {
        if (!saving) {
            if (form.changed) confirmDiscard = true else viewModel.closeForm()
        }
    }
    Dialog(onDismissRequest = close, properties = notebookDialogProperties()) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(
                Modifier.imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(
                        when (form.target) {
                            ReminderFormTarget.New, is ReminderFormTarget.Claim -> R.string.notebook_reminders_new
                            is ReminderFormTarget.Edit -> R.string.notebook_reminders_edit
                        }
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                )
                OutlinedTextField(
                    value = form.message,
                    onValueChange = { atLimit = !form.offerMessage(it) },
                    label = { Text(stringResource(R.string.notebook_reminders_field_message)) },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (atLimit) Text(stringResource(R.string.notebook_reminders_error_message_too_long), color = MaterialTheme.colorScheme.error)
                if ((form.target as? ReminderFormTarget.Edit)?.item?.timeToCheck == true) {
                    Text(stringResource(R.string.notebook_reminders_time_to_check_note), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Field(stringResource(R.string.notebook_reminders_field_date), times.date(form.atMillis)) { pickDate = true }
                Field(stringResource(R.string.notebook_reminders_field_time), times.time(form.atMillis)) { pickTime = true }
                (plan as? ReminderEditPlan.Refused)?.problem?.takeIf { it != ReminderEditProblem.MESSAGE_MISSING }?.let {
                    Text(stringResource(problemText(it)), color = MaterialTheme.colorScheme.error)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.notebook_reminders_field_repeat), modifier = Modifier.weight(1f))
                    Switch(checked = form.repeats, onCheckedChange = { form.repeats = it })
                }
                if (form.repeats) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (frequency in RecurrenceFrequency.entries) {
                            FilterChip(
                                selected = form.frequency == frequency,
                                onClick = { form.frequency = frequency },
                                label = { Text(stringResource(frequencyLabel(frequency))) },
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.notebook_reminders_every))
                        OutlinedTextField(
                            value = form.intervalText,
                            onValueChange = { value -> if (value.length <= 4 && value.all { it.isDigit() }) form.intervalText = value },
                            singleLine = true,
                            isError = form.interval !in 1..MAX_EDITOR_INTERVAL,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(96.dp),
                        )
                        Text(pluralStringResource(unitLabel(form.frequency), form.interval.coerceAtLeast(1)))
                    }
                }
                error?.let { Text(stringResource(errorText(it)), color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = !saving, onClick = close) { Text(stringResource(R.string.notebook_cancel)) }
                    Button(
                        enabled = !saving && plan is ReminderEditPlan.Save,
                        onClick = {
                            saving = true
                            error = null
                            scope.launch {
                                when (val outcome = viewModel.save(form)) {
                                    ReminderFormOutcome.Saved -> Unit
                                    is ReminderFormOutcome.Refused -> error = outcome.error
                                }
                                saving = false
                            }
                        },
                    ) {
                        Text(stringResource(if (form.target is ReminderFormTarget.Edit) R.string.notebook_save else R.string.notebook_reminders_create))
                    }
                }
            }
        }
        if (pickDate) DateDialog(form, onDone = { pickDate = false })
        if (pickTime) TimeDialog(form, times.is24Hour, onDone = { pickTime = false })
        if (confirmDiscard) {
            AlertDialog(
                onDismissRequest = { confirmDiscard = false },
                properties = notebookDialogProperties(),
                text = {
                    Text(stringResource(if (form.target is ReminderFormTarget.Edit) R.string.notebook_editor_discard_changes else R.string.notebook_reminders_discard_new))
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDiscard = false
                        viewModel.closeForm()
                    }) { Text(stringResource(R.string.notebook_editor_discard)) }
                },
                dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.notebook_editor_keep_editing)) } },
            )
        }
    }
}

@Composable
private fun Field(label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

// The pickers' states are built with remember: their remember* factories keep them in saved state.
@Composable
private fun DateDialog(form: ReminderForm, onDone: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val state = remember { DatePickerState(locale, initialSelectedDateMillis = ReminderTimes.pickerDate(form.atMillis), yearRange = PICKER_YEARS) }
    DatePickerDialog(
        onDismissRequest = onDone,
        properties = notebookDialogProperties(fullScreen = true),
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { form.atMillis = ReminderTimes.withDate(form.atMillis, it) }
                onDone()
            }) { Text(stringResource(R.string.notebook_reminders_ok)) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.notebook_cancel)) } },
    ) { DatePicker(state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(form: ReminderForm, is24Hour: Boolean, onDone: () -> Unit) {
    val time = ReminderTimes.localTime(form.atMillis)
    val state = remember { TimePickerState(time.hour, time.minute, is24Hour) }
    TimePickerDialog(
        onDismissRequest = onDone,
        properties = notebookDialogProperties(fullScreen = true),
        title = { Text(stringResource(R.string.notebook_reminders_field_time)) },
        confirmButton = {
            TextButton(onClick = {
                if (state.hour != time.hour || state.minute != time.minute) form.atMillis = ReminderTimes.withTime(form.atMillis, state.hour, state.minute)
                onDone()
            }) { Text(stringResource(R.string.notebook_reminders_ok)) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.notebook_cancel)) } },
    ) { TimePicker(state) }
}

private fun frequencyLabel(frequency: RecurrenceFrequency): Int = when (frequency) {
    RecurrenceFrequency.DAILY -> R.string.notebook_reminders_daily
    RecurrenceFrequency.WEEKLY -> R.string.notebook_reminders_weekly
    RecurrenceFrequency.MONTHLY -> R.string.notebook_reminders_monthly
    RecurrenceFrequency.YEARLY -> R.string.notebook_reminders_yearly
}

private fun unitLabel(frequency: RecurrenceFrequency): Int = when (frequency) {
    RecurrenceFrequency.DAILY -> R.plurals.notebook_reminders_unit_days
    RecurrenceFrequency.WEEKLY -> R.plurals.notebook_reminders_unit_weeks
    RecurrenceFrequency.MONTHLY -> R.plurals.notebook_reminders_unit_months
    RecurrenceFrequency.YEARLY -> R.plurals.notebook_reminders_unit_years
}

private fun problemText(problem: ReminderEditProblem): Int = when (problem) {
    ReminderEditProblem.MESSAGE_MISSING -> R.string.notebook_reminders_error_message_missing
    ReminderEditProblem.MESSAGE_TOO_LONG -> R.string.notebook_reminders_error_message_too_long
    ReminderEditProblem.TIME_PAST -> R.string.notebook_reminders_error_time_past
    ReminderEditProblem.INTERVAL_INVALID -> R.string.notebook_reminders_error_interval
    ReminderEditProblem.OUT_OF_RANGE -> R.string.notebook_reminders_error_out_of_range
}

private fun errorText(error: ReminderFormError): Int = when (error) {
    ReminderFormError.MESSAGE_MISSING -> R.string.notebook_reminders_error_message_missing
    ReminderFormError.MESSAGE_TOO_LONG -> R.string.notebook_reminders_error_message_too_long
    ReminderFormError.TIME_PAST -> R.string.notebook_reminders_error_time_past
    ReminderFormError.INTERVAL_INVALID -> R.string.notebook_reminders_error_interval
    ReminderFormError.OUT_OF_RANGE -> R.string.notebook_reminders_error_out_of_range
    ReminderFormError.GONE -> R.string.notebook_reminders_error_gone
    ReminderFormError.CLAIM_GONE -> R.string.notebook_completions_gone
    ReminderFormError.NOT_SAVED -> R.string.notebook_reminders_error_not_saved
    ReminderFormError.IDS_EXHAUSTED -> R.string.notebook_reminders_error_ids
}

/** The stores' instant window, 1970 to 2200-01-01 UTC, which is still 2200 east of UTC. */
private val PICKER_YEARS = 1970..2200
