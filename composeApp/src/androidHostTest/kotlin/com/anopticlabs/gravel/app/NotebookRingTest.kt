package com.anopticlabs.gravel.app

import com.anopticlabs.gravel.notebook.capture.wavDurationMillis
import com.anopticlabs.gravel.notebook.pipeline.IngestResult
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

class NotebookRingSinkTest {
    private var on = true
    private var queued = 0
    private var result = IngestResult.QUEUED
    private val now = 1_790_000_000_500L
    private val hours = TestTimeSource()
    private val ingested = mutableListOf<Pair<ByteArray, Long>>()

    private val sink = NotebookRingSink(
        notebookOn = { on },
        ringRecordingsQueued = { queued },
        ingest = { wav, at -> ingested += wav to at; result },
        clock = { now },
        marks = hours,
    )

    private fun seconds(rate: Int, seconds: Double) = ShortArray((rate * seconds).toInt()) { (it % 200 - 100).toShort() }

    private suspend fun store(samples: ShortArray, rate: Int = 16_000, at: Long? = 1_780_000_000) = sink.store(at, rate, samples, 7)

    @Test
    fun aRecordingIsStoredAsASixteenKilohertzWavDatedByTheRing() = runTest {
        assertTrue(store(seconds(8_000, 2.0), rate = 8_000))

        val (wav, at) = ingested.single()
        assertEquals(1_780_000_000_000L, at)
        assertEquals(2_000L, wavDurationMillis(wav))
        assertEquals(16_000, ByteBuffer.wrap(wav, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int)
    }

    @Test
    fun aRecordingAtAnyOtherRateBecomesASixteenKilohertzWavOfTheSameLength() = runTest {
        store(seconds(48_000, 2.0), rate = 48_000)
        store(seconds(9_997, 2.0), rate = 9_997)

        for ((wav, _) in ingested) {
            assertEquals(2_000L, wavDurationMillis(wav))
            assertEquals(16_000, ByteBuffer.wrap(wav, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int)
        }
    }

    @Test
    fun aRecordingAtTheNotebooksRateKeepsItsSamples() = runTest {
        val samples = seconds(16_000, 1.5)
        store(samples)

        val pcm = ByteBuffer.wrap(ingested.single().first, 44, samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        assertContentEquals(samples, ShortArray(samples.size).also { pcm.get(it) })
    }

    @Test
    fun aRecordingWithNoTimeATimeBefore2025OrATimeAheadOfThePhoneIsDatedNow() = runTest {
        store(seconds(16_000, 1.0), at = null)
        store(seconds(16_000, 1.0), at = now / 1000 + 60)
        store(seconds(16_000, 1.0), at = 0)
        store(seconds(16_000, 1.0), at = 3_600)
        store(seconds(16_000, 1.0), at = 1_735_689_599)

        assertEquals(List(5) { now }, ingested.map { it.second })
    }

    @Test
    fun aRingTimeFrom2025OnDatesTheRecording() = runTest {
        store(seconds(16_000, 1.0), at = 1_735_689_600)
        store(seconds(16_000, 1.0), at = now / 1000)

        assertEquals(listOf(1_735_689_600_000L, now / 1000 * 1000), ingested.map { it.second })
    }

    @Test
    fun aTapUnderASecondIsDroppedAndTheSyncMovesPastIt() = runTest {
        assertTrue(store(seconds(16_000, 0.999)))
        assertTrue(ingested.isEmpty())
        assertTrue(store(seconds(16_000, 1.0)))
        assertEquals(1, ingested.size)
    }

    @Test
    fun whileTheNotebookIsOffNothingIsStoredAndNothingMoves() = runTest {
        on = false
        assertFalse(store(seconds(16_000, 2.0)))
        assertFalse(store(seconds(16_000, 0.5)), "not even a tap is passed")
        assertTrue(ingested.isEmpty())
    }

    @Test
    fun droppedTapsDoNotCountTowardTheHour() = runTest {
        repeat(MAX_RING_RECORDINGS_PER_HOUR) { assertTrue(store(seconds(16_000, 0.5))) }
        assertTrue(store(seconds(16_000, 1.0)))
        assertEquals(1, ingested.size)
    }

    @Test
    fun recordingsWaitOnTheRingWhileFiftyRingRecordingsAreQueued() = runTest {
        queued = MAX_RING_RECORDINGS_QUEUED
        assertFalse(store(seconds(16_000, 2.0)))
        assertTrue(ingested.isEmpty())
        queued = MAX_RING_RECORDINGS_QUEUED - 1
        assertTrue(store(seconds(16_000, 2.0)))
    }

    @Test
    fun recordingsWaitOnTheRingOnceSixtyWereStoredInTheHour() = runTest {
        repeat(MAX_RING_RECORDINGS_PER_HOUR) { assertTrue(store(seconds(16_000, 1.0))) }
        assertFalse(store(seconds(16_000, 1.0)))

        hours += 59.minutes
        assertFalse(store(seconds(16_000, 1.0)))
        hours += 1.minutes
        assertTrue(store(seconds(16_000, 1.0)))
        assertEquals(MAX_RING_RECORDINGS_PER_HOUR + 1, ingested.size)
    }

    @Test
    fun aRecordingTheNotebookLostStaysOnTheRingAndOneItKeptCounts() = runTest {
        result = IngestResult.LOST
        repeat(MAX_RING_RECORDINGS_PER_HOUR) { assertFalse(store(seconds(16_000, 1.0))) }
        result = IngestResult.KEPT
        assertTrue(store(seconds(16_000, 1.0)), "lost recordings do not count toward the hour")
        result = IngestResult.QUEUED
        repeat(MAX_RING_RECORDINGS_PER_HOUR - 1) { assertTrue(store(seconds(16_000, 1.0))) }
        assertFalse(store(seconds(16_000, 1.0)), "the kept one counts")
    }
}

class RingSyncFilesTest {
    private val dir = createTempDirectory("ring").toFile()
    private val address = "A1:B2:C3:D4:E5:F6"

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun nothingSavedReadsAsNoPosition() = runTest {
        val files = RingSyncFiles.of(dir, address)
        assertNull(files.nextIndex())
        assertNull(files.lastCount())
    }

    @Test
    fun aSavedPositionIsReadBackByTheNextInstanceForTheSameRingOnly() = runTest {
        RingSyncFiles.of(dir, address).apply {
            setNextIndex(0xFFFF)
            setLastCount(0xFF)
        }

        val again = RingSyncFiles.of(dir, address)
        assertEquals(0xFFFF, again.nextIndex())
        assertEquals(0xFF, again.lastCount())
        assertNull(RingSyncFiles.of(dir, "A1:B2:C3:D4:E5:F7").nextIndex())
    }

    @Test
    fun aValueOutOfRangeIsNotWritten() = runTest {
        val files = RingSyncFiles.of(dir, address)
        assertFailsWith<IllegalArgumentException> { files.setNextIndex(0x10000) }
        assertFailsWith<IllegalArgumentException> { files.setLastCount(-1) }
        assertNull(files.nextIndex())
    }

    @Test
    fun aSavedPositionThatDoesNotReadBackStopsTheSyncInsteadOfStartingOver() = runTest {
        val files = RingSyncFiles.of(dir, address)
        val saved = File(dir, "ring_A1B2C3D4E5F6_next")
        for (bad in listOf("70000", "-1", "abc", " ")) {
            saved.writeText(bad)
            assertFailsWith<IOException>(bad) { files.nextIndex() }
        }
        saved.delete()
        saved.mkdir()
        assertFailsWith<IOException> { files.nextIndex() }
    }

    @Test
    fun aCountThatDoesNotReadBackReadsAsNone() = runTest {
        val files = RingSyncFiles.of(dir, address)
        for (bad in listOf("256", "-1", "abc")) {
            File(dir, "ring_A1B2C3D4E5F6_count").writeText(bad)
            assertNull(files.lastCount(), bad)
        }
    }

    @Test
    fun aPositionThatCannotBeSavedThrows() = runTest {
        val notADirectory = File(dir, "file").apply { writeText("x") }
        assertFailsWith<IOException> { RingSyncFiles.of(notADirectory, address).setNextIndex(1) }
    }

    @Test
    fun onlyAnUpperCaseColonSeparatedAddressNamesAFile() {
        for (bad in listOf("a1:b2:c3:d4:e5:f6", "A1B2C3D4E5F6", "A1:B2:C3:D4:E5", "../../B2:C3:D4:E5:F6", "A1:B2:C3:D4:E5:F6\n")) {
            assertFailsWith<IllegalArgumentException>(bad) { RingSyncFiles.of(dir, bad) }
        }
    }
}
