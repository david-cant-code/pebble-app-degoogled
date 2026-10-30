package com.anopticlabs.gravel.notebook.pipeline

import com.anopticlabs.gravel.notebook.AppVisibility
import com.anopticlabs.gravel.notebook.capture.CaptureQueueItem
import com.anopticlabs.gravel.notebook.capture.CaptureStage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureCoordinatorTest {

    private class FakeKeepAlive : CaptureKeepAlive {
        val calls = mutableListOf<String>()
        var failHold = false
        override fun hold() {
            calls += "hold"
            if (failHold) error("refused")
        }
        override fun extend() {
            calls += "extend"
        }
        override fun release() {
            calls += "release"
        }
        fun count(call: String) = calls.count { it == call }
    }

    // Unconfined: runs launch bodies and the rows collector on the calling thread, in order.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, _ -> })
    private val rows = MutableStateFlow<List<CaptureQueueItem>>(emptyList())
    private val keepAlive = FakeKeepAlive()
    private val visibility = AppVisibility()
    private val runs = mutableListOf<Boolean>()
    private var gate = CompletableDeferred<Unit>()
    private val coordinator = CaptureCoordinator({ atStart -> runs += atStart; gate.await() }, rows, keepAlive, scope)

    @AfterTest
    fun cleanUp() = scope.cancel()

    private fun capture(inFlight: Boolean, stage: CaptureStage? = null) {
        val status = if (inFlight) CaptureQueueItem.Status.PROCESSING else CaptureQueueItem.Status.NOTE_CREATED
        rows.value = listOf(CaptureQueueItem("a", 1L, status, stage = stage))
    }

    /** Lets the work waiting now finish; work started after this waits again. */
    private fun finishRuns() {
        val open = gate
        gate = CompletableDeferred()
        open.complete(Unit)
    }

    @Test
    fun nothingIsHeldWhileAnActivityIsResumed() {
        coordinator.start(visibility)
        visibility.onResumed()
        capture(inFlight = true)
        assertTrue(coordinator.working.value)
        capture(inFlight = false)
        finishRuns()
        assertFalse(coordinator.working.value)
        assertTrue(keepAlive.calls.isEmpty())
    }

    @Test
    fun heldWhileNoActivityIsResumedUntilTheRunEnds() {
        coordinator.start(visibility)
        visibility.onResumed()
        capture(inFlight = true)
        visibility.onPaused()
        assertEquals(listOf("hold"), keepAlive.calls)
        visibility.onResumed()
        assertEquals(listOf("hold", "release"), keepAlive.calls)
        visibility.onPaused()
        capture(inFlight = false)
        assertEquals(listOf("hold", "release", "hold", "extend"), keepAlive.calls)
        finishRuns()
        assertEquals(listOf("hold", "release", "hold", "extend", "release"), keepAlive.calls)
    }

    @Test
    fun aSecondPauseHoldsNothingMore() {
        coordinator.start(visibility)
        coordinator.request()
        capture(inFlight = true)
        coordinator.onResumed(false)
        coordinator.onResumed(false)
        assertEquals(1, keepAlive.count("hold"))
    }

    @Test
    fun withNoRunUnderWayNothingIsHeld() {
        coordinator.start(visibility)
        capture(inFlight = true)
        assertTrue(keepAlive.calls.isEmpty())
    }

    @Test
    fun aRunWithNoCaptureInFlightHoldsNothing() {
        coordinator.start(visibility)
        visibility.onResumed()
        visibility.onPaused()
        coordinator.request()
        assertTrue(coordinator.working.value)
        finishRuns()
        assertTrue(keepAlive.calls.isEmpty())
    }

    @Test
    fun aCaptureTakenUpWhileNothingIsResumedIsHeldAtOnce() {
        coordinator.start(visibility)
        coordinator.request()
        capture(inFlight = true)
        assertEquals(listOf("hold"), keepAlive.calls)
    }

    @Test
    fun theHoldLastsBetweenPassesOfARun() {
        coordinator.start(visibility)
        coordinator.request()
        capture(inFlight = true)
        capture(inFlight = false)
        capture(inFlight = true)
        assertEquals(listOf("hold", "extend", "extend"), keepAlive.calls)
        finishRuns()
        assertEquals("release", keepAlive.calls.last())
    }

    @Test
    fun theHoldEndsWithTheRunEvenIfARowIsLeftInFlight() {
        coordinator.start(visibility)
        coordinator.request()
        capture(inFlight = true)
        finishRuns()
        assertEquals(listOf("hold", "release"), keepAlive.calls)
        assertFalse(coordinator.working.value)
    }

    @Test
    fun eachRowChangeWhileHeldExtendsTheWakeLock() {
        coordinator.start(visibility)
        coordinator.request()
        capture(inFlight = true)
        capture(inFlight = true, stage = CaptureStage.THINKING)
        assertEquals(listOf("hold", "extend"), keepAlive.calls)
    }

    @Test
    fun aHoldThatThrowsLeavesTheRowsWatched() {
        keepAlive.failHold = true
        coordinator.start(visibility)
        coordinator.request()
        capture(inFlight = true)
        capture(inFlight = true, stage = CaptureStage.THINKING)
        finishRuns()
        assertEquals(listOf("hold", "extend", "release"), keepAlive.calls)
    }

    @Test
    fun onlyTheFirstRunIsAtStart() {
        coordinator.request()
        finishRuns()
        coordinator.request()
        finishRuns()
        assertEquals(listOf(true, false), runs)
    }

    @Test
    fun requestsDuringARunAreTakenByIt() {
        coordinator.request()
        coordinator.request()
        coordinator.request()
        finishRuns()
        assertEquals(listOf(true), runs)
        assertFalse(coordinator.working.value)
    }

    @Test
    fun aCaptureQueuedDuringARunMakesItGoRoundOnce() {
        coordinator.request()
        coordinator.captureQueued()
        coordinator.captureQueued()
        finishRuns()
        assertEquals(listOf(true, false), runs)
        assertTrue(coordinator.working.value)
        finishRuns()
        assertEquals(2, runs.size)
        assertFalse(coordinator.working.value)
    }

    @Test
    fun aRequestAgainDuringARunMakesItGoRoundOnceAndHoldsNothing() {
        coordinator.start(visibility)
        coordinator.request()
        coordinator.requestAgain()
        coordinator.requestAgain()
        finishRuns()
        assertEquals(listOf(true, false), runs)
        finishRuns()
        assertEquals(2, runs.size)
        assertFalse(coordinator.working.value)
        assertTrue(keepAlive.calls.isEmpty())
    }

    @Test
    fun aRequestAgainWithNoRunStartsOne() {
        coordinator.requestAgain()
        assertEquals(listOf(true), runs)
    }

    @Test
    fun aCaptureQueuedWithNoActivityResumedIsHeldBeforeTheCallReturns() {
        coordinator.start(visibility)
        coordinator.captureQueued()
        assertEquals(listOf("hold"), keepAlive.calls)
        finishRuns()
        assertEquals(listOf("hold", "release"), keepAlive.calls)
    }

    @Test
    fun aCaptureQueuedDuringARunWithNoActivityResumedIsHeldAtOnce() {
        coordinator.start(visibility)
        coordinator.request()
        assertTrue(keepAlive.calls.isEmpty())
        coordinator.captureQueued()
        assertEquals(listOf("hold"), keepAlive.calls)
    }

    @Test
    fun aCaptureQueuedWhileAnActivityIsResumedHoldsNothing() {
        coordinator.start(visibility)
        visibility.onResumed()
        coordinator.captureQueued()
        finishRuns()
        finishRuns()
        assertTrue(keepAlive.calls.isEmpty())
    }

    @Test
    fun aCaptureQueuedWithNoRunStartsOne() {
        coordinator.captureQueued()
        assertEquals(listOf(true), runs)
        assertTrue(coordinator.working.value)
    }

    @Test
    fun anActivityResumedAfterNoneAsksForARun() {
        coordinator.start(visibility)
        assertTrue(runs.isEmpty())
        visibility.onResumed()
        visibility.onResumed()
        assertEquals(1, runs.size)
        finishRuns()
        visibility.onPaused()
        visibility.onPaused()
        visibility.onResumed()
        assertEquals(2, runs.size)
    }

    @Test
    fun startingTwiceWatchesOnce() {
        coordinator.start(visibility)
        coordinator.start(visibility)
        coordinator.request()
        capture(inFlight = true)
        capture(inFlight = true, stage = CaptureStage.THINKING)
        assertEquals(listOf("hold", "extend"), keepAlive.calls)
    }

    @Test
    fun aRunThatThrowsStillEndsAndReleases() {
        val failing = CaptureCoordinator({ gate.await(); error("boom") }, rows, keepAlive, scope)
        failing.start(visibility)
        failing.request()
        capture(inFlight = true)
        finishRuns()
        assertEquals(listOf("hold", "release"), keepAlive.calls)
        assertFalse(failing.working.value)
        failing.request()
        assertTrue(failing.working.value)
    }
}
