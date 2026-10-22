package com.anopticlabs.gravel.transcription

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ServerDictationHoldTest {

    private val hold = ServerDictationHold(bound = 30.seconds)
    private val requests = AtomicInteger()

    private suspend fun until(condition: () -> Boolean) = withTimeout(5.seconds) { while (!condition()) delay(5) }

    private suspend fun long(stillWanted: () -> Boolean = { true }, request: suspend () -> String) =
        withTimeout(10.seconds) { hold.sendRecording(long = true, stillWanted, request) }

    // The first request runs until it is stopped, ending in [stoppedWith] (a cancellation unless given); later ones answer.
    private fun stoppableRequest(stopped: CompletableDeferred<Unit>, stoppedWith: Exception? = null): suspend () -> String = {
        if (requests.incrementAndGet() == 1) {
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                stopped.complete(Unit)
                throw stoppedWith ?: e
            }
        }
        "words"
    }

    @Test
    fun aLongRecordingIsOnTheServerOnlyWhileItsRequestRuns() = runBlocking(Dispatchers.Default) {
        val reply = CompletableDeferred<String>()
        val sent = async { long { reply.await() } }
        until { hold.longRecordingOnServer }
        reply.complete("words")
        assertEquals("words", sent.await())
        assertFalse(hold.longRecordingOnServer)
    }

    @Test
    fun aHoldStopsALongRequestAndItIsSentAgainFromTheStartOnceTheHoldEnds() = runBlocking(Dispatchers.Default) {
        val stopped = CompletableDeferred<Unit>()
        val sent = async { long(request = stoppableRequest(stopped)) }
        until { requests.get() == 1 }
        val dictation = hold.raise()
        withTimeout(5.seconds) { stopped.await() }
        until { !hold.longRecordingOnServer }
        delay(50)
        assertEquals(1, requests.get(), "sent again while the hold was up")
        hold.release(dictation)
        assertEquals("words", sent.await())
        assertEquals(2, requests.get())
    }

    /** A stopped request can end in the transport's own error instead of a cancellation; it is still sent again. */
    @Test
    fun aStoppedRequestThatEndsInAnIoErrorIsSentAgainNotFailed() = runBlocking(Dispatchers.Default) {
        val stopped = CompletableDeferred<Unit>()
        val sent = async { long(request = stoppableRequest(stopped, IOException("Canceled"))) }
        until { requests.get() == 1 }
        val dictation = hold.raise()
        withTimeout(5.seconds) { stopped.await() }
        hold.release(dictation)
        assertEquals("words", sent.await())
        assertEquals(2, requests.get())
    }

    @Test
    fun aRecordingWaitsWhileAHoldIsUp() = runBlocking(Dispatchers.Default) {
        for (isLong in listOf(true, false)) {
            requests.set(0)
            val dictation = hold.supersede()
            val sent = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(10.seconds) { hold.sendRecording(isLong, { true }) { requests.incrementAndGet(); "words" } } }
            delay(50)
            assertEquals(0, requests.get(), "long=$isLong")
            hold.release(dictation)
            assertEquals("words", sent.await())
        }
    }

    @Test
    fun aShortRecordingAlreadySentIsNotStopped() = runBlocking(Dispatchers.Default) {
        val reply = CompletableDeferred<String>()
        val sent = async { withTimeout(10.seconds) { hold.sendRecording(long = false, { true }) { requests.incrementAndGet(); reply.await() } } }
        until { requests.get() == 1 }
        hold.raise()
        delay(50)
        reply.complete("words")
        assertEquals("words", sent.await())
        assertEquals(1, requests.get())
        assertFalse(hold.longRecordingOnServer)
    }

    @Test
    fun aRecordingThatWaitedIsNotSentWhenItNoLongerGoesToTheServer() = runBlocking(Dispatchers.Default) {
        var wanted = true
        val stopped = CompletableDeferred<Unit>()
        val sent = async { long({ wanted }, stoppableRequest(stopped)) }
        until { requests.get() == 1 }
        val dictation = hold.raise()
        withTimeout(5.seconds) { stopped.await() }
        wanted = false
        hold.release(dictation)
        assertNull(sent.await())
        assertEquals(1, requests.get())

        val waiting = hold.supersede()
        // Undispatched, so the recording is waiting on the hold before it is released.
        val short = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(10.seconds) { hold.sendRecording(long = false, { false }) { requests.incrementAndGet(); "words" } } }
        hold.release(waiting)
        assertNull(short.await())
        assertEquals(1, requests.get())
        assertEquals("words", hold.sendRecording(long = false, { false }) { "words" }, "nothing waited, so nothing is asked")
    }

    @Test
    fun aHoldNobodyReleasesEndsAtItsBound() = runBlocking(Dispatchers.Default) {
        val bounded = ServerDictationHold(bound = 100.milliseconds)
        bounded.raise()
        assertEquals("words", withTimeout(5.seconds) { bounded.sendRecording(long = true, { true }) { "words" } })
    }

    @Test
    fun clearEndsEveryHold() = runBlocking(Dispatchers.Default) {
        hold.raise()
        hold.raise()
        hold.clear()
        assertFalse(hold.isHeld)
    }

    @Test
    fun clearingNeverStopsALongRequest() = runBlocking(Dispatchers.Default) {
        val stopped = CompletableDeferred<Unit>()
        val sent = async { long(request = stoppableRequest(stopped)) }
        until { requests.get() == 1 }
        repeat(2_000) {
            hold.clear()
            if (it % 100 == 0) delay(1)
        }
        delay(50)
        assertFalse(stopped.isCompleted)
        assertTrue(hold.longRecordingOnServer)
        sent.cancel()
    }

    @Test
    fun aFailureOfTheRequestItselfIsNotSentAgain() = runBlocking(Dispatchers.Default) {
        assertFailsWith<IOException> { long { requests.incrementAndGet(); throw IOException("reset") } }
        assertEquals(1, requests.get())
        assertFalse(hold.longRecordingOnServer)
    }

    @Test
    fun theCallersCancellationStopsTheRequestWithoutSendingItAgain() = runBlocking(Dispatchers.Default) {
        val sent = async { long { requests.incrementAndGet(); awaitCancellation() } }
        until { requests.get() == 1 }
        sent.cancel()
        sent.join()
        assertTrue(sent.isCancelled)
        assertFalse(hold.longRecordingOnServer)
        assertEquals(1, requests.get())
    }
}
