package com.anopticlabs.gravel.notebook.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureQueueTest {

    private fun entry(id: String, attempts: Int = 0) = QueuedCapture(id, "$id.m4a", 1_000L, attempts)

    @Test
    fun enqueueAppendsOnceAndKeepsTheExistingEntry() {
        val q = enqueueCapture(emptyList(), "s1", "rec.m4a", 42L)
        assertEquals(listOf(QueuedCapture("s1", "rec.m4a", 42L, 0, false)), q)
        assertEquals(listOf("a", "b", "s1"), enqueueCapture(listOf(entry("a"), entry("b")), "s1", "rec.m4a", 42L).map { it.id })
        val existing = entry("s1", attempts = 2)
        assertEquals(listOf(existing, entry("b")), enqueueCapture(listOf(existing, entry("b")), "s1", "other.m4a", 99L))
    }

    @Test
    fun removeTargetsOneId() {
        assertTrue(removeQueuedCapture(enqueueCapture(emptyList(), "s1", "r.m4a", 1L), "s1").isEmpty())
        assertEquals(listOf("a", "b"), removeQueuedCapture(listOf(entry("a"), entry("s1"), entry("b")), "s1").map { it.id })
        val q = listOf(entry("a"), entry("b"))
        assertEquals(q, removeQueuedCapture(q, "missing"))
    }

    @Test
    fun failureUpdatesInPlaceWithoutDuplicating() {
        val failed = markQueuedCaptureFailed(enqueueCapture(emptyList(), "s1", "r.m4a", 1L), "s1")
        assertEquals(listOf(QueuedCapture("s1", "r.m4a", 1L, 1, false)), failed)
        val out = markQueuedCaptureFailed(listOf(entry("a", attempts = 3), entry("s1")), "s1")
        assertEquals(3, out.first { it.id == "a" }.attempts)
        assertEquals(1, out.first { it.id == "s1" }.attempts)
        val q = listOf(entry("a"), entry("b"))
        assertEquals(q, markQueuedCaptureFailed(q, "missing"))
    }

    @Test
    fun drainRemovesSuccessesBumpsAttemptsAndKeepsNewArrivals() {
        val out = applyQueueDrainResults(listOf(entry("s1", attempts = 2), entry("x")), succeeded = setOf("s1"), bumped = emptyMap())
        assertEquals(listOf(entry("x")), out)
        val bumped = applyQueueDrainResults(listOf(entry("s1", attempts = 2)), succeeded = emptySet(), bumped = mapOf("s1" to 3)).single()
        assertEquals(3, bumped.attempts)
        assertFalse(bumped.flagged)
    }

    @Test
    fun aLongRecordingGetsThreeServerTriesFifteenMinutesApart() {
        assertEquals(3, MAX_SERVER_TRIES)
        assertEquals(15 * 60 * 1000L, SERVER_TRY_SPACING_MILLIS)
    }

    @Test
    fun aServerTryCountsOnTheNamedEntryWithItsTime() {
        val queue = listOf(entry("a"), entry("b").copy(serverTries = 1, serverTriedAtMillis = 1L))
        val once = markServerTry(queue, "b", 50L)
        assertEquals(listOf(entry("a"), entry("b").copy(serverTries = 2, serverTriedAtMillis = 50L)), once)
        assertEquals(once, markServerTry(once, "absent", 60L))
    }

    @Test
    fun flagFlipsAtTheAttemptCap() {
        fun flaggedAt(bumpTo: Int) = applyQueueDrainResults(listOf(entry("s1", attempts = bumpTo - 1)), emptySet(), mapOf("s1" to bumpTo)).single().flagged
        assertFalse(flaggedAt(MAX_QUEUE_ATTEMPTS - 1))
        assertTrue(flaggedAt(MAX_QUEUE_ATTEMPTS))
    }

    @Test
    fun aCaptureNameCarriesItsStartTimeAndOrigin() {
        assertEquals("watch_1700.wav", captureFileName(1_700L, "wav", fromWatch = true))
        assertEquals("recording_1700.m4a", captureFileName(1_700L, "m4a", fromWatch = false))
        assertTrue(isWatchRecordingName("watch_1700.wav"))
        assertFalse(isWatchRecordingName("recording_1700.wav"))
        assertEquals(1_700L, capturedAtFromName("watch_1700.wav"))
        assertEquals(1_700L, capturedAtFromName("recording_1700.m4a"))
        assertNull(capturedAtFromName("recording_a.m4a"))
        assertNull(capturedAtFromName("recovered"))
        assertNull(capturedAtFromName("watch_1700"))
        assertNull(capturedAtFromName("watch_${"9".repeat(16)}.wav"), "too many digits for a time")
    }
}
