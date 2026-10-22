package com.anopticlabs.gravel.notebook.pipeline

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LongCaptureChargerTest {

    private suspend fun settle() = repeat(5) { yield() }

    @Test
    fun workIsScheduledAfterEachPassThatLeavesCapturesWaitingButNotForTheFirstValue() = runBlocking<Unit> {
        val passes = MutableStateFlow(0)
        var waiting = 2
        var scheduled = 0
        val watch = launch(start = CoroutineStart.UNDISPATCHED) { scheduleWhileWaiting(passes, { waiting }) { scheduled++ } }
        settle()
        assertEquals(0, scheduled)
        passes.value = 1
        settle()
        assertEquals(1, scheduled)
        passes.value = 2
        settle()
        assertEquals(2, scheduled)
        waiting = 0
        passes.value = 3
        settle()
        assertEquals(2, scheduled)
        watch.cancel()
    }

    @Test
    fun theChargingWorkRunsAgainWhileCapturesWaitForPowerUnlessTheRunStopped() {
        assertTrue(chargingWorkRetries(null, 1))
        assertTrue(chargingWorkRetries(CaptureHold("waiting for Wi-Fi", waitOnly = true), 1))
        assertFalse(chargingWorkRetries(CaptureHold("no text model is installed"), 1))
        assertFalse(chargingWorkRetries(null, 0))
    }

    @Test
    fun aFailedScheduleLeavesTheWatchRunning() = runBlocking<Unit> {
        val passes = MutableStateFlow(0)
        var calls = 0
        val watch = launch(start = CoroutineStart.UNDISPATCHED) {
            scheduleWhileWaiting(passes, { 1 }) { if (++calls == 1) throw IllegalStateException("no work manager") }
        }
        passes.value = 1
        settle()
        passes.value = 2
        settle()
        assertEquals(2, calls)
        watch.cancel()
    }
}
