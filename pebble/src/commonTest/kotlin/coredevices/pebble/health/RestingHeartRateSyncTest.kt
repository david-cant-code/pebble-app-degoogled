package coredevices.pebble.health

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RestingHeartRateSyncTest {
    private val day1 = RestingHeartRateEntry(epochDay = 20000, wakeEpochSec = 1_728_000_000, bpm = 54)
    private val day2 = RestingHeartRateEntry(epochDay = 20001, wakeEpochSec = 1_728_086_400, bpm = 52)

    @Test
    fun firstSync_writesEverything() {
        assertEquals(listOf(day1, day2), restingHrEntriesToWrite(listOf(day1, day2), emptyMap()))
    }

    @Test
    fun unchangedDays_areSkipped() {
        val written = mapOf(day1.epochDay to day1, day2.epochDay to day2)
        assertTrue(restingHrEntriesToWrite(listOf(day1, day2), written).isEmpty())
    }

    @Test
    fun changedBpmOrWake_isRewritten() {
        val written = mapOf(day1.epochDay to day1, day2.epochDay to day2)
        val laterReadings = day1.copy(bpm = 53)          // more HR data arrived from the watch
        val nap = day2.copy(wakeEpochSec = day2.wakeEpochSec + 3600) // sleep extended
        assertEquals(listOf(laterReadings, nap), restingHrEntriesToWrite(listOf(laterReadings, nap), written))
    }

    @Test
    fun encodeDecode_roundTrips() {
        val encoded = encodeRestingHrWritten(listOf(day2, day1))
        assertEquals("20000:1728000000:54,20001:1728086400:52", encoded)
        assertEquals(mapOf(day1.epochDay to day1, day2.epochDay to day2), decodeRestingHrWritten(encoded))
    }

    @Test
    fun decode_ignoresMissingOrCorruptValues() {
        assertTrue(decodeRestingHrWritten(null).isEmpty())
        assertTrue(decodeRestingHrWritten("").isEmpty())
        assertEquals(mapOf(day1.epochDay to day1), decodeRestingHrWritten("garbage,20000:1728000000:54,1:2"))
    }
}
