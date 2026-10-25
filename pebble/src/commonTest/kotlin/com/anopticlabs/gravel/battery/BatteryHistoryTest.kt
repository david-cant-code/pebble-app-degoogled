package com.anopticlabs.gravel.battery

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class BatteryHistoryTest {
    private val settings = MapSettings()
    private val history = BatteryHistory(settings)
    private val start = Instant.fromEpochSeconds(1_800_000_000)

    private fun levels(serial: String) = history.samples(serial).map { it.percent }

    @Test
    fun recordsOnlyChanges() {
        history.record("A", 80, start)
        history.record("A", 80, start + 1.hours)
        history.record("A", 79, start + 2.hours)
        assertEquals(listOf(80, 79), levels("A"))
        assertEquals((start + 2.hours).epochSeconds, history.samples("A").last().epochSeconds)
    }

    @Test
    fun keepsWatchesApart() {
        history.record("A", 80, start)
        history.record("B", 50, start)
        history.record("A", 79, start + 1.hours)
        assertEquals(listOf(80, 79), levels("A"))
        assertEquals(listOf(50), levels("B"))
    }

    @Test
    fun dropsSamplesOlderThanAMonth() {
        history.record("A", 80, start)
        history.record("A", 70, start + 20.days)
        history.record("A", 60, start + 31.days)
        assertEquals(listOf(70, 60), levels("A"))
    }

    @Test
    fun keepsAtMostTheNewestSamples() {
        repeat(BatteryHistory.MAX_SAMPLES + 5) { i -> history.record("A", 50 + i % 2, start + (i * 60).seconds) }
        val samples = history.samples("A")
        assertEquals(BatteryHistory.MAX_SAMPLES, samples.size)
        assertEquals(start.epochSeconds + (BatteryHistory.MAX_SAMPLES + 4) * 60L, samples.last().epochSeconds)
    }

    @Test
    fun aNewInstanceContinuesTheStoredHistory() {
        history.record("A", 80, start)
        val reopened = BatteryHistory(settings)
        reopened.record("A", 80, start + 1.hours)
        reopened.record("A", 78, start + 2.hours)
        assertEquals(listOf(80, 78), reopened.samples("A").map { it.percent })
    }

    @Test
    fun ignoresWhatItCannotRead() {
        settings.putString("gravel_battery_history_A", "100:50;garbage;200:x;300:101;400:49;")
        assertEquals(listOf(BatterySample(100, 50), BatterySample(400, 49)), history.samples("A"))
    }

    @Test
    fun rejectsLevelsOutsideZeroToHundred() {
        history.record("A", -1, start)
        history.record("A", 101, start)
        assertNull(settings.getStringOrNull("gravel_battery_history_A"))
        assertEquals(0L, history.revision.value)
    }

    @Test
    fun revisionMovesOnlyOnWrites() {
        history.record("A", 80, start)
        val after = history.revision.value
        history.record("A", 80, start + 1.hours)
        assertEquals(after, history.revision.value)
        history.record("A", 79, start + 2.hours)
        assertEquals(after + 1, history.revision.value)
    }
}
