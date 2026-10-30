package com.anopticlabs.gravel.notebook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.util.LocalePreferences
import com.anopticlabs.gravel.notebook.R
import com.anopticlabs.gravel.notebook.reminders.CalendarOccurrence
import com.anopticlabs.gravel.notebook.reminders.OccurrenceStatus
import com.anopticlabs.gravel.notebook.reminders.PhoneEvent
import com.anopticlabs.gravel.notebook.reminders.monthCells
import java.time.format.TextStyle
import java.util.Locale
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toLocalDateTime

/**
 * The Calendar section: the month grid with a dot per row and phone calendar event (three at most, the rows' first),
 * and the selected day's rows under it, its phone calendar events last.
 */
@Composable
internal fun ReminderCalendar(page: CalendarPage, now: Long, times: ReminderTimes, viewModel: RemindersViewModel) {
    val day = page.day
    val locale = LocalConfiguration.current.locales[0]
    val firstDay = remember(locale) { firstDayOfWeek(locale) }
    val cells = remember(page.month, firstDay) { monthCells(page.month, firstDay) }
    val rows = page.rows[day].orEmpty()
    val phoneEvents = page.phoneEvents[day].orEmpty()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "month") { MonthHeader(page, times, viewModel) }
        item(key = "grid") { MonthGrid(cells, page, day, locale, times, viewModel) }
        page.phoneProblem?.let { problem ->
            item(key = "phone-problem") {
                val text = when (problem) {
                    PhoneProblem.NONE_PICKED -> R.string.notebook_calendar_phone_none_picked
                    PhoneProblem.NO_ACCESS -> R.string.notebook_calendar_phone_no_access
                    PhoneProblem.NOT_READ -> R.string.notebook_calendar_phone_not_read
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    if (problem == PhoneProblem.NOT_READ) {
                        TextButton(onClick = viewModel::retry) { Text(stringResource(R.string.notebook_try_again)) }
                    }
                }
            }
        }
        item(key = "day") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(times.day(day), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { viewModel.newReminderOn(day) }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(stringResource(R.string.notebook_calendar_add))
                }
            }
        }
        if (rows.isEmpty()) {
            item(key = "empty") { Text(stringResource(R.string.notebook_calendar_day_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(rows, key = { "${it.kind}-${it.reminderId}-${it.occurrenceMillis}" }) { row ->
            RowCard(onClick = { viewModel.openInfo(row) }) {
                RowText(row.message, rowDetail(row, day, now, times), firstDueOff(row, day, now, times), Modifier.padding(horizontal = 16.dp))
            }
        }
        if (phoneEvents.isNotEmpty()) {
            item(key = "phone-header") { Text(stringResource(R.string.notebook_calendar_phone_header), style = MaterialTheme.typography.titleSmall) }
        }
        itemsIndexed(phoneEvents, key = { index, _ -> "phone-$index" }) { _, event ->
            RowCard {
                RowText(
                    event.title.ifBlank { stringResource(R.string.notebook_calendar_phone_untitled) },
                    phoneEventDetail(event, day, now, times),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun phoneEventDetail(event: PhoneEvent, day: LocalDate, now: Long, times: ReminderTimes): String {
    val time = when {
        event.allDay -> stringResource(R.string.notebook_calendar_phone_all_day)
        event.endMillis > event.beginMillis ->
            stringResource(R.string.notebook_calendar_phone_span, timeOn(day, event.beginMillis, now, times), timeOn(day, event.endMillis, now, times))
        else -> timeOn(day, event.beginMillis, now, times)
    }
    return if (event.calendarName.isBlank()) time else stringResource(R.string.notebook_calendar_phone_detail, time, event.calendarName)
}

/**
 * The first day of the phone's week: the locale's "fw" preference, else its calendar's first day
 * (androidx.core 1.19.0, `LocalePreferences.getFirstDayOfWeek`).
 */
internal fun firstDayOfWeek(locale: Locale): DayOfWeek = when (LocalePreferences.getFirstDayOfWeek(locale)) {
    LocalePreferences.FirstDayOfWeek.MONDAY -> DayOfWeek.MONDAY
    LocalePreferences.FirstDayOfWeek.TUESDAY -> DayOfWeek.TUESDAY
    LocalePreferences.FirstDayOfWeek.WEDNESDAY -> DayOfWeek.WEDNESDAY
    LocalePreferences.FirstDayOfWeek.THURSDAY -> DayOfWeek.THURSDAY
    LocalePreferences.FirstDayOfWeek.FRIDAY -> DayOfWeek.FRIDAY
    LocalePreferences.FirstDayOfWeek.SATURDAY -> DayOfWeek.SATURDAY
    else -> DayOfWeek.SUNDAY
}

@Composable
private fun MonthHeader(page: CalendarPage, times: ReminderTimes, viewModel: RemindersViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(enabled = page.month > page.months.start, onClick = { viewModel.showMonth(page.month.minus(1, DateTimeUnit.MONTH)) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.notebook_calendar_previous))
        }
        Text(times.month(page.month), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        IconButton(enabled = page.month < page.months.endInclusive, onClick = { viewModel.showMonth(page.month.plus(1, DateTimeUnit.MONTH)) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.notebook_calendar_next))
        }
    }
}

@Composable
private fun MonthGrid(cells: List<LocalDate>, page: CalendarPage, selected: LocalDate, locale: Locale, times: ReminderTimes, viewModel: RemindersViewModel) {
    Column {
        Row(Modifier.clearAndSetSemantics {}) {
            for (weekday in cells.take(7)) {
                Text(
                    weekday.toJavaLocalDate().dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        for (week in cells.chunked(7)) {
            Row {
                for (day in week) {
                    DayCell(
                        day,
                        day.month == page.month.month,
                        day == page.today,
                        day == selected,
                        page.rows[day].orEmpty(),
                        page.phoneEvents[day].orEmpty().size,
                        times,
                        Modifier.weight(1f),
                    ) {
                        viewModel.selectDay(day)
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: LocalDate,
    inMonth: Boolean,
    today: Boolean,
    selected: Boolean,
    rows: List<CalendarOccurrence>,
    phoneEvents: Int,
    times: ReminderTimes,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val label = dayLabel(day, today, rows, phoneEvents, times)
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(shape)
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)) else Modifier)
            .then(if (today && !selected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = label
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.clearAndSetSemantics {}, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                day.day.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (today) FontWeight.Bold else null,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (inMonth) 1f else 0.38f),
            )
            // The reminders' dots come first, so phone calendar events do not crowd them out.
            val dots = rows.map { dotStatus(it.status) }.sortedBy { it.ordinal }.map { dotColor(it) } + List(phoneEvents) { MaterialTheme.colorScheme.outline }
            Row(Modifier.height(8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                for (color in dots.take(3)) Box(Modifier.size(5.dp).background(color, CircleShape))
                if (dots.size > 3) Text("+${dots.size - 3}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** The three statuses at rest a day shows (an overdue row counts as pending), in dot order. */
private enum class DotStatus { PENDING, COMPLETED, MISSED }

private fun dotStatus(status: OccurrenceStatus): DotStatus = when (status) {
    OccurrenceStatus.PENDING, OccurrenceStatus.OVERDUE -> DotStatus.PENDING
    OccurrenceStatus.COMPLETED -> DotStatus.COMPLETED
    OccurrenceStatus.MISSED -> DotStatus.MISSED
}

@Composable
private fun dotColor(status: DotStatus): Color = when (status) {
    DotStatus.PENDING -> MaterialTheme.colorScheme.tertiary
    DotStatus.COMPLETED -> MaterialTheme.colorScheme.primary
    DotStatus.MISSED -> MaterialTheme.colorScheme.error
}

@Composable
private fun dayLabel(day: LocalDate, today: Boolean, rows: List<CalendarOccurrence>, phoneEvents: Int, times: ReminderTimes): String {
    val counts = rows.groupingBy { dotStatus(it.status) }.eachCount()
    return buildString {
        append(times.day(day))
        if (today) append(stringResource(R.string.notebook_calendar_today_suffix))
        counts[DotStatus.PENDING]?.let { append(pluralStringResource(R.plurals.notebook_calendar_pending_suffix, it, it)) }
        counts[DotStatus.COMPLETED]?.let { append(pluralStringResource(R.plurals.notebook_calendar_completed_suffix, it, it)) }
        counts[DotStatus.MISSED]?.let { append(pluralStringResource(R.plurals.notebook_calendar_missed_suffix, it, it)) }
        if (phoneEvents > 0) append(pluralStringResource(R.plurals.notebook_calendar_phone_suffix, phoneEvents, phoneEvents))
    }
}

private fun localDay(millis: Long): LocalDate = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault()).date

/** A time on [day] as a time, any other as a date and time. */
private fun timeOn(day: LocalDate, millis: Long, now: Long, times: ReminderTimes): String =
    if (localDay(millis) == day) times.time(millis) else times.format(millis, now)

@Composable
private fun rowDetail(row: CalendarOccurrence, day: LocalDate, now: Long, times: ReminderTimes): String {
    val event = row.event
    return when {
        row.status == OccurrenceStatus.COMPLETED ->
            stringResource(R.string.notebook_calendar_completed_at, timeOn(day, event?.completedAt ?: row.atMillis, now, times))
        row.status == OccurrenceStatus.MISSED -> stringResource(R.string.notebook_calendar_missed_due, timeOn(day, row.occurrenceMillis, now, times))
        row.status == OccurrenceStatus.OVERDUE ->
            stringResource(R.string.notebook_calendar_went_off, timeOn(day, event?.latestSnoozeFireAt ?: row.occurrenceMillis, now, times))
        event?.latestSnoozeFireAt != null -> stringResource(R.string.notebook_calendar_snoozed_until, timeOn(day, event.latestSnoozeFireAt, now, times))
        row.occurrenceMillis <= now -> stringResource(R.string.notebook_calendar_waiting, timeOn(day, row.occurrenceMillis, now, times))
        else -> stringResource(R.string.notebook_calendar_due, timeOn(day, row.occurrenceMillis, now, times))
    }
}

/** "First due" for a row shown on another day than its due day. */
@Composable
private fun firstDueOff(row: CalendarOccurrence, day: LocalDate, now: Long, times: ReminderTimes): String? =
    if (localDay(row.occurrenceMillis) == day) null else stringResource(R.string.notebook_calendar_first_due, times.format(row.occurrenceMillis, now))

@Composable
private fun statusWord(status: OccurrenceStatus): String = stringResource(
    when (dotStatus(status)) {
        DotStatus.PENDING -> R.string.notebook_calendar_status_pending
        DotStatus.COMPLETED -> R.string.notebook_calendar_status_completed
        DotStatus.MISSED -> R.string.notebook_calendar_status_missed
    },
)

/** A calendar row's details and what can be done with it ([calendarActions]); every action but the edit asks first. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OccurrenceInfoSheet(row: CalendarOccurrence, busy: Boolean, now: Long, times: ReminderTimes, viewModel: RemindersViewModel) {
    val event = row.event
    ModalBottomSheet(onDismissRequest = viewModel::closeInfo, properties = notebookSheetProperties()) {
        Column(
            Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(row.message, style = MaterialTheme.typography.titleMedium, maxLines = 12, overflow = TextOverflow.Ellipsis)
            Text(statusWord(row.status), style = MaterialTheme.typography.labelLarge, color = dotColor(dotStatus(row.status)))
            val detail = MaterialTheme.colorScheme.onSurfaceVariant
            Text(stringResource(R.string.notebook_calendar_first_due, times.format(row.occurrenceMillis, now)), color = detail)
            event?.completedAt?.let { Text(stringResource(R.string.notebook_calendar_completed_at, times.format(it, now)), color = detail) }
            event?.cancelledAt?.let { Text(stringResource(R.string.notebook_calendar_counted_missed_at, times.format(it, now)), color = detail) }
            event?.latestSnoozeFireAt?.let { Text(stringResource(R.string.notebook_calendar_last_snooze, times.format(it, now)), color = detail) }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                for (action in calendarActions(row, now)) {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            val confirm = calendarConfirm(row, action)
                            if (confirm != null) {
                                viewModel.ask(confirm)
                            } else {
                                viewModel.closeInfo()
                                viewModel.editReminder(row.reminderId)
                            }
                        },
                    ) {
                        val destructive = action == CalendarAction.DELETE || action == CalendarAction.DELETE_THIS_ONE
                        Text(stringResource(actionLabel(action)), color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
                    }
                }
            }
        }
    }
}

private fun actionLabel(action: CalendarAction): Int = when (action) {
    CalendarAction.COMPLETE -> R.string.notebook_reminders_mark_complete
    CalendarAction.COMPLETE_EARLY -> R.string.notebook_calendar_complete_early
    CalendarAction.MARK_MISSED -> R.string.notebook_reminders_mark_missed
    CalendarAction.MARK_MISSED_AHEAD -> R.string.notebook_calendar_missed_ahead
    CalendarAction.TO_COMPLETED -> R.string.notebook_calendar_to_completed
    CalendarAction.TO_MISSED -> R.string.notebook_calendar_to_missed
    CalendarAction.DELETE -> R.string.notebook_reminders_delete_one
    CalendarAction.DELETE_THIS_ONE -> R.string.notebook_calendar_delete_this_one
    CalendarAction.EDIT -> R.string.notebook_reminders_edit
}
