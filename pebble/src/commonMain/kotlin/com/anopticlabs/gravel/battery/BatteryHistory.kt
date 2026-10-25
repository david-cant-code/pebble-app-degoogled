package com.anopticlabs.gravel.battery

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** A battery level a watch reported, in percent, and when. */
data class BatterySample(val epochSeconds: Long, val percent: Int)

/**
 * The battery levels each watch reported, kept on the phone so the battery page can estimate
 * battery life without upstream's telemetry relay. The watch reports its level over BLE whenever it
 * changes (`BatteryWatcher`), so only changes are stored: the time between two samples is time
 * spent at the first level.
 */
class BatteryHistory(private val settings: Settings) {
    private val lastPercent = mutableMapOf<String, Int?>()
    private val _revision = MutableStateFlow(0L)

    /** Bumped on every write, so an open screen re-reads. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    // Called on every emission of the watch list, so the stored list is only parsed once per watch.
    fun record(serial: String, percent: Int, now: Instant) {
        if (percent !in 0..100) return
        val previous = lastPercent.getOrPut(serial) { samples(serial).lastOrNull()?.percent }
        if (previous == percent) return
        val cutoff = (now - KEEP).epochSeconds
        val kept = (samples(serial) + BatterySample(now.epochSeconds, percent))
            .filter { it.epochSeconds >= cutoff }
            .takeLast(MAX_SAMPLES)
        settings.putString(key(serial), kept.joinToString(";") { "${it.epochSeconds}:${it.percent}" })
        lastPercent[serial] = percent
        _revision.value++
    }

    fun samples(serial: String): List<BatterySample> =
        settings.getStringOrNull(key(serial)).orEmpty().split(';').mapNotNull { entry ->
            val parts = entry.split(':')
            if (parts.size != 2) return@mapNotNull null
            val time = parts[0].toLongOrNull() ?: return@mapNotNull null
            val percent = parts[1].toIntOrNull()?.takeIf { it in 0..100 } ?: return@mapNotNull null
            BatterySample(time, percent)
        }

    private fun key(serial: String) = "gravel_battery_history_$serial"

    companion object {
        val KEEP = 30.days

        // A charge cycle adds about 200 samples (100 levels down, 100 up), so a month with two
        // charges a week fits.
        const val MAX_SAMPLES = 2000
    }
}
