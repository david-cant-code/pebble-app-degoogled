package com.anopticlabs.gravel.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.anopticlabs.gravel.battery.BatteryEstimate
import com.anopticlabs.gravel.battery.BatteryEstimator
import com.anopticlabs.gravel.battery.BatteryHistory
import com.anopticlabs.gravel.battery.BatterySample
import coredevices.pebble.ui.TopBarParams
import io.rebble.libpebblecommon.connection.CommonConnectedDevice
import io.rebble.libpebblecommon.connection.KnownPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import org.koin.compose.koinInject
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.time.Clock

private data class WatchBattery(
    val name: String,
    val current: Int?,
    val samples: List<BatterySample>,
    val estimate: BatteryEstimate,
)

/** Gravel's battery page: estimated on the phone from the levels the watch reports while connected. */
@Composable
fun LocalBatteryScreen(topBarParams: TopBarParams) {
    val libPebble = koinInject<LibPebble>()
    val history = koinInject<BatteryHistory>()
    val clock = koinInject<Clock>()
    val watches by libPebble.watches.collectAsState()
    val revision by history.revision.collectAsState()

    LaunchedEffect(Unit) {
        topBarParams.searchAvailable(null)
        topBarParams.title("Battery")
        topBarParams.actions { }
    }

    val nowSeconds = clock.now().epochSeconds
    val batteries = remember(watches, revision) {
        watches.filterIsInstance<KnownPebbleDevice>().mapNotNull { watch ->
            val samples = history.samples(watch.serial)
            // A level only counts as "now" while the watch is connected; an old reading would
            // promise days that may already be gone.
            val current = (watch as? CommonConnectedDevice)?.batteryLevel
            if (samples.isEmpty() && current == null) return@mapNotNull null
            WatchBattery(watch.name, current, samples, BatteryEstimator.estimate(samples, current, clock.now()))
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (batteries.isEmpty()) {
            item {
                Text(
                    "No battery readings yet. They are collected while your watch is connected.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        items(batteries) { WatchBatteryCard(it, nowSeconds) }
        item {
            Text(
                "Estimated on this phone from the levels your watch reports. Nothing leaves the phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WatchBatteryCard(battery: WatchBattery, nowSeconds: Long) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(battery.name, style = MaterialTheme.typography.titleMedium)
            val shown = battery.current ?: battery.samples.lastOrNull()?.percent
            if (shown != null) {
                Text("$shown %", style = MaterialTheme.typography.displaySmall)
            }
            if (battery.current == null) {
                Text("Not connected: last reading", style = MaterialTheme.typography.bodyMedium)
            }
            val estimate = battery.estimate
            estimate.daysLeft?.let { Text(daysLeftText(it), style = MaterialTheme.typography.bodyLarge) }
            estimate.percentPerDay?.let { Text(perDayText(it), style = MaterialTheme.typography.bodyMedium) }
            estimate.daysPerCharge?.let { Text(perChargeText(it), style = MaterialTheme.typography.bodyMedium) }
            if (estimate.percentPerDay == null) {
                Text(
                    "Still learning: an estimate appears after about a day of use.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (battery.samples.size >= 2) {
                BatteryChart(battery.samples, nowSeconds, connected = battery.current != null)
            }
        }
    }
}

/**
 * The level over the estimate's window, drawn as steps: the watch held each level until the next
 * reading. A gap the estimate skips is left open, and the line reaches now only while connected.
 */
@Composable
private fun BatteryChart(samples: List<BatterySample>, nowSeconds: Long, connected: Boolean) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val from = nowSeconds - BatteryEstimator.WINDOW.inWholeSeconds
    val shown = samples.filter { it.epochSeconds >= from }.sortedBy { it.epochSeconds }
    Canvas(
        Modifier.fillMaxWidth().height(120.dp)
            .semantics { contentDescription = "Battery level over the last 14 days" },
    ) {
        fun x(seconds: Long) = size.width * (seconds - from) / (nowSeconds - from).toFloat()
        fun y(percent: Int) = size.height * (1 - percent / 100f)
        listOf(0, 50, 100).forEach { level ->
            drawLine(grid, Offset(0f, y(level)), Offset(size.width, y(level)), strokeWidth = 1.dp.toPx())
        }
        if (shown.isEmpty()) return@Canvas
        val path = Path()
        path.moveTo(x(shown.first().epochSeconds), y(shown.first().percent))
        shown.zipWithNext().forEach { (a, b) ->
            if (b.epochSeconds - a.epochSeconds > BatteryEstimator.MAX_GAP_HOURS * 3600) {
                path.moveTo(x(b.epochSeconds), y(b.percent))
            } else {
                path.lineTo(x(b.epochSeconds), y(a.percent))
                path.lineTo(x(b.epochSeconds), y(b.percent))
            }
        }
        if (connected) path.lineTo(x(nowSeconds), y(shown.last().percent))
        drawPath(path, line, style = Stroke(width = 2.dp.toPx()))
    }
}

// Rounded down: with time left, promising too much is the wrong side to err on.
internal fun daysLeftText(daysLeft: Double): String {
    val days = floor(daysLeft).toInt()
    if (days >= 1) return "About $days ${if (days == 1) "day" else "days"} left"
    val hours = floor(daysLeft * 24).toInt()
    if (hours >= 1) return "About $hours ${if (hours == 1) "hour" else "hours"} left"
    return "Less than an hour left"
}

internal fun perDayText(percentPerDay: Double): String {
    val percent = percentPerDay.roundToInt()
    return if (percent < 1) "Uses less than 1 % a day" else "Uses about $percent % a day"
}

internal fun perChargeText(daysPerCharge: Double): String {
    val days = floor(daysPerCharge).toInt()
    if (days < 1) return "A full charge lasts less than a day"
    return "A full charge lasts about $days ${if (days == 1) "day" else "days"}"
}
