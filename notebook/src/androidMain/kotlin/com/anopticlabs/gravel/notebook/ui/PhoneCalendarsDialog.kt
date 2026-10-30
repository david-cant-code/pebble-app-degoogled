package com.anopticlabs.gravel.notebook.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.R
import com.anopticlabs.gravel.notebook.reminders.PhoneCalendarInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal sealed interface CalendarListing {
    data object Loading : CalendarListing
    data object NoAccess : CalendarListing
    data object NotRead : CalendarListing
    data class Listed(val calendars: List<PhoneCalendarInfo>) : CalendarListing
}

/** [load] as the picker shows it, a provider failure included. */
internal fun listPhoneCalendars(load: () -> List<PhoneCalendarInfo>?): CalendarListing = try {
    load()?.let(CalendarListing::Listed) ?: CalendarListing.NoAccess
} catch (e: Exception) {
    logger.w { "Phone calendars not listed: ${e::class.simpleName}" }
    CalendarListing.NotRead
}

private val logger = Logger.withTag("PhoneCalendarsDialog")

/**
 * Picks which of the phone's calendars the Notebook's Calendar shows, by account; a tick applies at once. [load]
 * blocks, so it runs off the main thread, and returns null without calendar access.
 */
@Composable
fun PhoneCalendarsDialog(load: () -> List<PhoneCalendarInfo>?, chosen: Set<String>, onChoose: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
    val listing by produceState<CalendarListing>(CalendarListing.Loading) {
        value = withContext(Dispatchers.IO) { listPhoneCalendars(load) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notebook_phone_calendars_title)) },
        text = {
            when (val shown = listing) {
                CalendarListing.Loading -> CircularProgressIndicator()
                CalendarListing.NoAccess -> Text(stringResource(R.string.notebook_phone_calendars_no_access))
                CalendarListing.NotRead -> Text(stringResource(R.string.notebook_phone_calendars_not_read))
                is CalendarListing.Listed -> if (shown.calendars.isEmpty()) {
                    Text(stringResource(R.string.notebook_phone_calendars_none))
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        for ((account, calendars) in shown.calendars.groupBy { it.account }.toSortedMap()) {
                            Text(account, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                            for (calendar in calendars.sortedBy { it.name }) {
                                val on = calendar.key in chosen
                                Row(
                                    Modifier.fillMaxWidth().clickable { onChoose(calendar.key, !on) },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(checked = on, onCheckedChange = { onChoose(calendar.key, it) })
                                    Text(calendar.name)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.notebook_done)) }
        },
    )
}
