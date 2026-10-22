package com.anopticlabs.gravel.whisper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WhisperLongFormReplyTest {

    private class Packed(complete: Boolean) {
        private val bytes = ArrayList<Byte>().apply { add(if (complete) 0 else 1) }

        fun segment(startCentis: Long, endCentis: Long, text: ByteArray, declaredLength: Long = text.size.toLong()) = apply {
            le(startCentis, 8)
            le(endCentis, 8)
            le(declaredLength, 4)
            text.forEach { bytes.add(it) }
        }

        fun raw(vararg extra: Byte) = apply { extra.forEach { bytes.add(it) } }

        fun build(): ByteArray = bytes.toByteArray()

        private fun le(value: Long, n: Int) {
            for (i in 0 until n) bytes.add((value ushr (8 * i)).toByte())
        }
    }

    @Test
    fun segmentsAreReadInMilliseconds() {
        val packed = Packed(complete = true)
            .segment(0, 250, "Buy milk.".encodeToByteArray())
            .segment(250, 3_000, " Call the plumber on Monday.".encodeToByteArray())
            .build()
        val result = parseLongFormReply(packed, audioMillis = 60_000)
        assertTrue(result.complete)
        assertEquals(
            listOf(WhisperSegment(0, 2_500, "Buy milk."), WhisperSegment(2_500, 30_000, " Call the plumber on Monday.")),
            result.segments,
        )
    }

    /** A stopped call is transcribed again from the start, so nothing it finished is kept. */
    @Test
    fun aStoppedCallKeepsNoSegments() {
        val result = parseLongFormReply(Packed(complete = false).segment(0, 250, "a".encodeToByteArray()).build(), 60_000)
        assertFalse(result.complete)
        assertEquals(emptyList(), result.segments)
    }

    @Test
    fun timesPastTheRecordingAreClampedAndAnEndBeforeItsStartIsRaised() {
        val result = parseLongFormReply(
            Packed(complete = true)
                .segment(500, 900, "late".encodeToByteArray())
                .segment(400, 300, "backwards".encodeToByteArray())
                .segment(Long.MAX_VALUE, Long.MAX_VALUE, "huge".encodeToByteArray())
                .build(),
            audioMillis = 5_005,
        )
        assertEquals(WhisperSegment(5_000, 5_005, "late"), result.segments[0])
        assertEquals(WhisperSegment(4_000, 4_000, "backwards"), result.segments[1])
        assertEquals(WhisperSegment(5_005, 5_005, "huge"), result.segments[2])
    }

    @Test
    fun malformedUtf8IsReplacedNotRefused() {
        val result = parseLongFormReply(Packed(complete = true).segment(0, 1, byteArrayOf(0xC3.toByte())).build(), 1_000)
        assertEquals("�", result.segments.single().text)
    }

    @Test
    fun anythingOutsideTheLayoutIsRefused() {
        assertFailsWith<IllegalArgumentException>("empty") { parseLongFormReply(ByteArray(0), 1_000) }
        assertFailsWith<IllegalArgumentException>("unknown flag") { parseLongFormReply(byteArrayOf(2), 1_000) }
        assertFailsWith<IllegalArgumentException>("segment header cut short") {
            parseLongFormReply(Packed(complete = true).raw(1, 2, 3).build(), 1_000)
        }
        assertFailsWith<IllegalArgumentException>("length past the end") {
            parseLongFormReply(Packed(complete = true).segment(0, 1, "ab".encodeToByteArray(), declaredLength = 3).build(), 1_000)
        }
        assertFailsWith<IllegalArgumentException>("length with the top bit set") {
            parseLongFormReply(Packed(complete = true).segment(0, 1, ByteArray(0), declaredLength = 0xFFFF_FFFFL).build(), 1_000)
        }
        assertFailsWith<IllegalArgumentException>("negative start") {
            parseLongFormReply(Packed(complete = true).segment(-1, 1, ByteArray(0)).build(), 1_000)
        }
        assertFailsWith<IllegalArgumentException>("over the reply bound") {
            parseLongFormReply(ByteArray(WhisperLongForm.MAX_REPLY_BYTES + 1), 1_000)
        }
    }

    @Test
    fun theRecordingMustBeInsideItsBound() {
        val ok = Packed(complete = true).build()
        assertFailsWith<IllegalArgumentException> { parseLongFormReply(ok, -1) }
        assertFailsWith<IllegalArgumentException> { parseLongFormReply(ok, WhisperLongForm.MAX_RECORDING_MILLIS + 1L) }
        assertEquals(emptyList(), parseLongFormReply(ok, WhisperLongForm.MAX_RECORDING_MILLIS.toLong()).segments)
    }

    @Test
    fun aReplyAtTheBoundIsRead() {
        val text = ByteArray(
            WhisperLongForm.MAX_REPLY_BYTES - WhisperLongForm.REPLY_HEADER_BYTES - WhisperLongForm.SEGMENT_HEADER_BYTES,
        ) { 'a'.code.toByte() }
        val packed = Packed(complete = true).segment(0, 1, text).build()
        assertEquals(WhisperLongForm.MAX_REPLY_BYTES, packed.size)
        assertEquals(text.size, parseLongFormReply(packed, 1_000).segments.single().text.length)
    }
}
