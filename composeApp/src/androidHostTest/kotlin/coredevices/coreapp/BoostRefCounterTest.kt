package coredevices.coreapp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the boost lifecycle policy: one service instance across
 * overlapping transcriptions, stop only after the count drains, and both
 * start and stop serialized through the main-thread queue (an inline
 * start could be overtaken by a posted stop's re-check and leave a held
 * boost without its service; the upstream-inherited race this fork
 * closes). A start failure (the API 31+ background-start restriction
 * outside the companion-device exemption) degrades quietly instead of
 * taking dictation down with it.
 */
class BoostRefCounterTest {

    private var starts = 0
    private var stops = 0
    private var startThrows = false
    private val mainQueue = mutableListOf<() -> Unit>()

    private val counter = BoostRefCounter(
        start = {
            starts++
            if (startThrows) throw IllegalStateException("background start not allowed")
        },
        stop = { stops++ },
        postToMain = { action -> mainQueue += action },
    )

    private fun drainMain() {
        while (mainQueue.isNotEmpty()) mainQueue.removeAt(0).invoke()
    }

    @Test
    fun startGoesThroughTheMainQueue() {
        counter.acquire()
        assertEquals(0, starts, "start must be queued, not inline: inline starts race a posted stop's re-check")
        drainMain()
        assertEquals(1, starts)
    }

    @Test
    fun outerAcquireStartsExactlyOnce() {
        counter.acquire()
        counter.acquire()
        drainMain()
        assertEquals(1, starts, "nested acquires must share the running service")
    }

    @Test
    fun innerReleaseDoesNotStop() {
        counter.acquire()
        counter.acquire()
        counter.release()
        drainMain()
        assertEquals(0, stops, "the service must survive while any transcription still holds it")
    }

    @Test
    fun drainingToZeroStopsViaTheMainThreadHop() {
        counter.acquire()
        counter.release()
        assertEquals(0, stops, "stop must be queued, not run inline, so the re-check serializes with starts")
        drainMain()
        assertEquals(1, stops)
    }

    @Test
    fun reacquireWhileStopIsPendingSuppressesIt() {
        counter.acquire()
        drainMain()
        counter.release()
        counter.acquire()
        drainMain()
        assertEquals(0, stops, "a transcription that began while the stop was queued keeps the service")
        assertEquals(2, starts, "the queued start refreshes the service behind the suppressed stop")
    }

    @Test
    fun releaseAfterTheSuppressedStopStillStops() {
        counter.acquire()
        drainMain()
        counter.release()
        counter.acquire()
        drainMain()
        counter.release()
        drainMain()
        assertEquals(1, stops, "the final release must still shut the service down")
        assertEquals(2, starts)
    }

    @Test
    fun startFailureDegradesQuietlyAndStaysConsistent() {
        startThrows = true
        counter.acquire()
        counter.acquire()
        counter.release()
        counter.release()
        drainMain()
        assertEquals(1, starts)
        assertEquals(1, stops, "the drain still posts its stop; stopping a never-started service is harmless")
        startThrows = false
        counter.acquire()
        drainMain()
        assertEquals(2, starts, "the next session must retry the boost")
    }
}
