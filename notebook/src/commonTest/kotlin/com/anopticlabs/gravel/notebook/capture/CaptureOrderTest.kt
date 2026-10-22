package com.anopticlabs.gravel.notebook.capture

import com.anopticlabs.gravel.notebook.pipeline.SpeechRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureOrderTest {

    private fun capture(id: String, durationMillis: Long?, at: Long = 0, fromWatch: Boolean = false) =
        QueuedCapture(id, "$id.wav", at, durationMillis = durationMillis, fromWatch = fromWatch)

    private val short = capture("short", 30_000)
    private val long = capture("long", 30_001)
    private val unknown = capture("unknown", null)
    private val watch = capture("watch", null, fromWatch = true)

    @Test
    fun longMeansOverThirtySecondsOrUnknownAndNeverFromTheWatch() {
        assertFalse(short.isLong)
        assertTrue(long.isLong)
        assertTrue(unknown.isLong)
        assertFalse(watch.isLong)
        assertFalse(capture("watch-long", 60_000, fromWatch = true).isLong)
    }

    @Test
    fun pausingAllLetsOnlyRecordingsFromTheWatchStart() {
        val rules = ProcessingRules(pause = ProcessingPause.ALL)
        assertEquals(listOf(watch), listOf(short, long, unknown, watch).filter(rules::mayStart))
    }

    @Test
    fun pausingLongOnesLetsTheShortOnesStart() {
        val rules = ProcessingRules(pause = ProcessingPause.LONG)
        assertEquals(listOf(short, watch), listOf(short, long, unknown, watch).filter(rules::mayStart))
    }

    @Test
    fun theChargerRuleHoldsLongOnesOffPowerUnlessApproved() {
        val unplugged = ProcessingRules(longWaitsForPower = true, pluggedIn = false)
        assertEquals(listOf(short, watch), listOf(short, long, unknown, watch).filter(unplugged::mayStart))
        assertEquals(listOf(long, unknown), listOf(short, long, unknown, watch).filter(unplugged::waitsForPower))
        val approved = unplugged.copy(approved = setOf("long"))
        assertTrue(approved.mayStart(long))
        assertFalse(approved.waitsForPower(long))
        assertFalse(approved.mayStart(unknown))
        assertTrue(unplugged.copy(pluggedIn = true).mayStart(long))
        assertTrue(unplugged.copy(longWaitsForPower = false).mayStart(long))
    }

    @Test
    fun aCaptureGoingToTheServerNeverWaitsForPower() {
        val unplugged = ProcessingRules(longWaitsForPower = true, pluggedIn = false)
        val tried = long.copy(phoneTried = true)
        assertFalse(unplugged.copy(speech = SpeechRoute.SERVER).waitsForPower(long))
        assertTrue(unplugged.copy(speech = SpeechRoute.SERVER).mayStart(long))
        assertTrue(unplugged.copy(speech = SpeechRoute.PHONE_THEN_SERVER).waitsForPower(long))
        assertFalse(unplugged.copy(speech = SpeechRoute.PHONE_THEN_SERVER).waitsForPower(tried))
        assertTrue(unplugged.copy(speech = SpeechRoute.PHONE).waitsForPower(tried))
    }

    @Test
    fun aLongCaptureLeftByTheServerAfterItsTriesWaitsForPower() {
        val unplugged = ProcessingRules(longWaitsForPower = true, pluggedIn = false, speech = SpeechRoute.SERVER_THEN_PHONE)
        assertFalse(unplugged.waitsForPower(long.copy(serverTries = MAX_SERVER_TRIES - 1)))
        assertTrue(unplugged.mayStart(long.copy(serverTries = MAX_SERVER_TRIES - 1)))
        assertTrue(unplugged.waitsForPower(long.copy(serverTries = MAX_SERVER_TRIES)))
        assertFalse(unplugged.mayStart(long.copy(serverTries = MAX_SERVER_TRIES)))
    }

    @Test
    fun aPausedCaptureIsNotCountedAsWaitingForPower() {
        val unplugged = ProcessingRules(longWaitsForPower = true, pluggedIn = false)
        assertFalse(unplugged.copy(pause = ProcessingPause.LONG).waitsForPower(long))
        assertFalse(unplugged.copy(pause = ProcessingPause.ALL).waitsForPower(long))
    }

    @Test
    fun shortOnesGoFirstAndEachGroupOldestFirst() {
        val oldLong = capture("old-long", 60_000, at = 1)
        val newShort = capture("new-short", 5_000, at = 4)
        val oldShort = capture("old-short", 5_000, at = 2)
        val newLong = capture("new-long", null, at = 3)
        assertEquals(
            listOf(oldShort, newShort, oldLong, newLong),
            processingOrder(listOf(oldLong, newShort, newLong, oldShort), ProcessingRules()),
        )
    }

    @Test
    fun theOrderLeavesOutWhatMayNotStart() {
        val rules = ProcessingRules(pause = ProcessingPause.LONG)
        assertEquals(listOf(short), processingOrder(listOf(long, short, unknown), rules))
    }

    private fun recording(seconds: Int): ByteArray {
        val buffer = RecordingBuffer()
        buffer.append(ShortArray(seconds * 16_000), seconds * 16_000)
        return buffer.toWav()
    }

    @Test
    fun theLengthOfARecordingIsReadFromItsWavHeader() {
        assertEquals(3_000, wavDurationMillis(recording(3)))
    }

    /** A RIFF WAVE file of [chunks], each a four-letter id and its body. */
    private fun riff(vararg chunks: Pair<String, ByteArray>): ByteArray {
        val out = ArrayList<Byte>()
        fun ascii(s: String) = s.forEach { out += it.code.toByte() }
        fun int32(v: Int) = repeat(4) { out += (v shr (8 * it)).toByte() }
        ascii("RIFF")
        int32(0)
        ascii("WAVE")
        for ((id, body) in chunks) {
            ascii(id)
            int32(body.size)
            out += body.toList()
            if (body.size % 2 == 1) out += 0
        }
        return out.toByteArray()
    }

    // 16 kHz mono 16-bit: 32,000 bytes a second.
    private val fmt = "fmt " to byteArrayOf(1, 0, 1, 0, 0x80.toByte(), 0x3E, 0, 0, 0, 0x7D, 0, 0, 2, 0, 16, 0)

    @Test
    fun otherChunksBeforeTheDataAreSkippedPaddingIncluded() {
        assertEquals(500, wavDurationMillis(riff(fmt, "LIST" to ByteArray(3), "data" to ByteArray(16_000))))
    }

    @Test
    fun aDataChunkLongerThanTheFileIsCutToWhatIsThere() {
        val bytes = riff(fmt, "data" to ByteArray(32_000))
        assertEquals(250, wavDurationMillis(bytes.copyOf(bytes.size - 24_000)))
    }

    @Test
    fun aDataSizeOfZeroIsReadAsTheRestOfTheFile() {
        val bytes = riff(fmt, "data" to ByteArray(16_000))
        val sizeAt = bytes.size - 16_000 - 4
        repeat(4) { bytes[sizeAt + it] = 0 }
        assertEquals(500, wavDurationMillis(bytes))
    }

    @Test
    fun aChunkSizeNearTwoGigabytesEndsTheWalkWithoutLength() {
        val bytes = riff("junk" to ByteArray(0))
        val sizeAt = bytes.size - 4
        repeat(4) { bytes[sizeAt + it] = (0x7FFFFFE8 shr (8 * it)).toByte() }
        assertNull(wavDurationMillis(bytes))
    }

    @Test
    fun aByteRateThatDisagreesWithTheFormatHasNoLength() {
        val inflated = fmt.second.copyOf().also { it[9] = 0x7E }
        assertNull(wavDurationMillis(riff("fmt " to inflated, "data" to ByteArray(32_000))))
    }

    @Test
    fun anythingButAWellFormedWavHasNoLength() {
        assertNull(wavDurationMillis(byteArrayOf(1, 2, 3)))
        assertNull(wavDurationMillis("milk".encodeToByteArray()))
        assertNull(wavDurationMillis(riff("data" to ByteArray(32_000))), "data before any fmt chunk")
        assertNull(wavDurationMillis(riff(fmt)), "no data chunk")
        val notWave = riff(fmt, "data" to ByteArray(10)).also { "AVI ".forEachIndexed { i, c -> it[8 + i] = c.code.toByte() } }
        assertNull(wavDurationMillis(notWave))
    }
}
