package com.anopticlabs.gravel.transcription

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class DictationHoldTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun aHoldEndsWhenItsDictationReleasesIt() = runBlocking {
        val holds = DictationHold(scope, bound = 30.seconds)
        val hold = holds.raise()
        assertTrue(holds.isHeld)
        holds.release(hold)
        assertFalse(holds.isHeld)
        withTimeout(1.seconds) { holds.awaitReleased() }
    }

    @Test
    fun releasingAnOlderHoldLeavesANewerOneInPlace() = runBlocking {
        val holds = DictationHold(scope, bound = 30.seconds)
        val sessionOpened = holds.raise()
        val transcribing = holds.raise()
        holds.release(sessionOpened)
        assertTrue(holds.isHeld, "the older release ended the newer hold")
        holds.release(transcribing)
        assertFalse(holds.isHeld)
    }

    /** A dictation refused as busy releases its own hold; the one still on its way to the engine keeps its. */
    @Test
    fun releasingANewerHoldLeavesAnOlderOneInPlace() = runBlocking {
        val holds = DictationHold(scope, bound = 30.seconds)
        val waiting = holds.raise()
        val refused = holds.raise()
        holds.release(refused)
        assertTrue(holds.isHeld, "the newer release ended the older hold")
        holds.release(waiting)
        assertFalse(holds.isHeld)
    }

    /** A session's hold ends with its dictation; one opened during the dictation outlives it. */
    @Test
    fun aDictationsHoldEndsTheHoldsRaisedBeforeIt() = runBlocking {
        val holds = DictationHold(scope, bound = 30.seconds)
        holds.raise()
        val dictation = holds.supersede()
        val nextSession = holds.raise()
        holds.release(dictation)
        assertTrue(holds.isHeld, "the dictation's release ended a session opened after it")
        holds.release(nextSession)
        assertFalse(holds.isHeld, "the session the dictation served is still held")
    }

    @Test
    fun anUnreleasedHoldEndsAtItsBound() = runBlocking {
        val holds = DictationHold(scope, bound = 300.milliseconds)
        val raised = TimeSource.Monotonic.markNow()
        holds.raise()
        withTimeout(5.seconds) { holds.awaitReleased() }
        assertTrue(raised.elapsedNow() >= 300.milliseconds, "ended after ${raised.elapsedNow()}")
    }

    /** The bound of an older raise does not cut a newer hold short. */
    @Test
    fun anOlderBoundDoesNotEndANewerHold() = runBlocking {
        val holds = DictationHold(scope, bound = 300.milliseconds)
        holds.raise()
        delay(200)
        holds.raise()
        delay(200)
        assertTrue(holds.isHeld, "the first raise's bound ended the second hold")
        withTimeout(5.seconds) { holds.awaitReleased() }
    }

    @Test
    fun awaitRaisedReturnsForAHoldAlreadyUp() = runBlocking {
        val holds = DictationHold(scope, bound = 30.seconds)
        holds.raise()
        withTimeout(1.seconds) { holds.awaitRaised() }
    }
}
