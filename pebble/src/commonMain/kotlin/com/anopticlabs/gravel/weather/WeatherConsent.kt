package com.anopticlabs.gravel.weather

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigHolder
import coredevices.util.withWeatherOff
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/** What turning weather on sends, for the one-time offer, setup and the Settings switch. */
fun weatherConsentText(interval: Duration): String {
    val often = when {
        interval == 1.hours -> "about once an hour"
        interval.inWholeMinutes % 60 == 0L -> "about every ${interval.inWholeHours} hours"
        else -> "about every ${interval.inWholeMinutes} minutes"
    }
    return "To show weather on your watch, Gravel asks Core Devices' weather server for a forecast " +
        "for each place in your weather list, $often, and at times such as when you open Gravel " +
        "before the first forecast has arrived, tap Refresh, or change a weather, units or sunrise " +
        "setting. Each request carries the place's position, your language and region setting " +
        "(for example en-US) and your units. For Current Location it sends a town's position, " +
        "never your phone's: the largest listed town within 10 km of your phone, with sizes " +
        "rounded to the thousand, or the nearest one if none is that close. Like any internet " +
        "request, it also shows the server your IP address."
}

const val WEATHER_SHARE_LABEL = "Share your weather locations with Core Devices (Current Location as a nearby town)"

/**
 * The one-time offer is due once setup is done and What's New has been seen, while weather is
 * still switched on but not agreed to: an install from before the agreement existed. Setup
 * records the choice before it ends ([applyWeatherChoice]), so a new install is not offered it.
 */
fun weatherOfferDue(onboarded: Boolean, config: CoreConfig, whatsNewVersion: Int): Boolean =
    onboarded && config.fetchWeather && !config.weatherAgreed && config.lastSeenWhatsNewVersion >= whatsNewVersion

/** Records a choice made with [WeatherShareSwitch]: sharing agrees and turns weather on, else weather is off. */
fun CoreConfigHolder.applyWeatherChoice(share: Boolean) {
    if (share) agreeToWeather() else update(config.value.withWeatherOff())
}

/** The colors the weather dialogs ask for; WeatherDialogContrastTest checks these values, not the drawn result. */
data class WeatherDialogColors(
    val surface: Color,
    val title: Color,
    val body: Color,
    val action: Color,
    val onAction: Color,
    val cancel: Color,
    val switchOffOutline: Color,
    val switchOnOutline: Color,
)

fun weatherDialogColors(scheme: ColorScheme) = WeatherDialogColors(
    surface = scheme.surfaceContainerHigh,
    title = scheme.onSurface,
    body = scheme.onSurfaceVariant,
    action = scheme.primary,
    onAction = scheme.onPrimary,
    cancel = scheme.onSurface,
    switchOffOutline = scheme.onSurfaceVariant,
    switchOnOutline = scheme.onSurface,
)

/** Only the Switch takes taps and carries the label for screen readers; the text beside it is hidden from them. */
@Composable
fun WeatherShareSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(WEATHER_SHARE_LABEL, Modifier.weight(1f).clearAndSetSemantics {})
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = WEATHER_SHARE_LABEL },
            colors = visibleSwitchColors(),
        )
    }
}

/** Switch colors whose outline stays visible on the weather dialogs and the setup screen. */
@Composable
fun visibleSwitchColors(): SwitchColors {
    val colors = weatherDialogColors(MaterialTheme.colorScheme)
    return SwitchDefaults.colors(
        uncheckedBorderColor = colors.switchOffOutline,
        uncheckedThumbColor = colors.switchOffOutline,
        checkedBorderColor = colors.switchOnOutline,
    )
}

/** How long Done ignores taps after the update question appears. */
val WEATHER_DONE_DELAY = 1.seconds

/** The one-time question after the update; tapping outside or Back does not close it. */
@Composable
fun WeatherShareDialog(interval: Duration, onDone: (share: Boolean) -> Unit) {
    var share by remember { mutableStateOf(false) }
    var answered by remember { mutableStateOf(false) }
    // It can open under the finger that just closed What's New.
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(WEATHER_DONE_DELAY)
        ready = true
    }
    WeatherDialog(
        title = "Share your location for weather?",
        text = weatherConsentText(interval) +
            "\n\nGravel's own weather shares nothing until you switch this on and tap Done.",
        extra = {
            Spacer(Modifier.height(16.dp))
            WeatherShareSwitch(share, { share = it })
        },
        confirmLabel = "Done",
        confirmEnabled = ready,
        onConfirm = {
            if (!answered) {
                answered = true
                onDone(share)
            }
        },
        dismiss = null,
    )
}

/** Asks before the Settings switch turns weather on; tapping outside or Back does not close it. */
@Composable
fun WeatherConsentDialog(
    title: String,
    interval: Duration,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    WeatherDialog(
        title = title,
        text = weatherConsentText(interval),
        extra = {},
        confirmLabel = confirmLabel,
        confirmEnabled = true,
        onConfirm = onConfirm,
        dismiss = dismissLabel to onDismiss,
    )
}

@Composable
private fun WeatherDialog(
    title: String,
    text: String,
    extra: @Composable () -> Unit,
    confirmLabel: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    dismiss: Pair<String, () -> Unit>?,
) {
    val colors = weatherDialogColors(MaterialTheme.colorScheme)
    AlertDialog(
        onDismissRequest = {},
        containerColor = colors.surface,
        titleContentColor = colors.title,
        textContentColor = colors.body,
        title = { Text(title) },
        // The text scrolls and anything below it stays in view.
        text = {
            Column {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { Text(text) }
                extra()
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = confirmEnabled,
                colors = ButtonDefaults.buttonColors(containerColor = colors.action, contentColor = colors.onAction),
            ) { Text(confirmLabel) }
        },
        dismissButton = dismiss?.let { (label, onClick) ->
            {
                TextButton(onClick = onClick, colors = ButtonDefaults.textButtonColors(contentColor = colors.cancel)) {
                    Text(label)
                }
            }
        },
    )
}
