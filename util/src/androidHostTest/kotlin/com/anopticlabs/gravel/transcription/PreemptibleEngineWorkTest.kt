package com.anopticlabs.gravel.transcription

import coredevices.whisper.WhisperEngineUnavailableException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Contract tests for [awaitPreemptibleEngineWork]. As for
 * `awaitEngineWork`, the stand-in for the native call is a latch-parked
 * block on a real thread, in real time, because the contract is the
 * interaction between coroutines and a thread that cannot observe them.
 */
class PreemptibleEngineWorkTest {

    private fun engineScope() = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** A call that stops when asked and returns the windows it finished. */
    private class StoppableCall {
        val started = CountDownLatch(1)
        val stop = CountDownLatch(1)
        val cancels = AtomicInteger()

        fun run(): String {
            started.countDown()
            return if (stop.await(20, TimeUnit.SECONDS)) "finished windows" else "ran to the end"
        }

        fun cancel() {
            cancels.incrementAndGet()
            stop.countDown()
        }
    }

    @Test
    fun aPreemptionStopsTheCallAndReturnsWhatItFinished() = runBlocking {
        val call = StoppableCall()
        val worker = engineScope().async { runCatching { call.run() } }
        val preemption = CompletableDeferred<Unit>()
        val result = async(Dispatchers.Default) {
            awaitPreemptibleEngineWork(worker, preemption, call::cancel, 5.seconds) { fail("a cooperative call is not wedged") }
        }
        call.started.await(5, TimeUnit.SECONDS)
        preemption.complete(Unit)
        assertEquals("finished windows", result.await())
        assertEquals(1, call.cancels.get())
    }

    @Test
    fun aCallThatFinishesFirstIsNotCanceled() = runBlocking {
        val call = StoppableCall()
        val worker = engineScope().async { runCatching { "done" } }
        worker.join()
        val preemption = CompletableDeferred(Unit)
        assertEquals("done", awaitPreemptibleEngineWork(worker, preemption, call::cancel, 5.seconds) { fail("not wedged") })
        assertEquals(0, call.cancels.get(), "a finished call was canceled")
    }

    @Test
    fun aCallThatIgnoresItsPreemptionIsReportedWedgedAndUnavailable() = runBlocking {
        val release = CountDownLatch(1)
        val worker = engineScope().async { runCatching { release.await(20, TimeUnit.SECONDS); "late" } }
        val wedged = AtomicBoolean(false)
        try {
            assertFailsWith<WhisperEngineUnavailableException> {
                awaitPreemptibleEngineWork(worker, CompletableDeferred(Unit), cancel = {}, unwindBound = 200.milliseconds) {
                    wedged.set(true)
                }
            }
            assertTrue(wedged.get())
        } finally {
            release.countDown()
        }
    }

    @Test
    fun anEngineFailureAfterAPreemptionIsTheCallersToHandle() = runBlocking {
        val worker = engineScope().async { runCatching<String> { throw RuntimeException("engine text") } }
        worker.join()
        val preemption = CompletableDeferred(Unit)
        val failure = assertFailsWith<RuntimeException> {
            awaitPreemptibleEngineWork(worker, preemption, cancel = {}, unwindBound = 5.seconds) { fail("not wedged") }
        }
        assertEquals("engine text", failure.message)
    }

    /** A preemption watch canceled under a running call still stops the call and waits it out. */
    @Test
    fun aCanceledPreemptionWatchStopsTheCallBeforeFailing() = runBlocking {
        val call = StoppableCall()
        val returned = AtomicBoolean(false)
        val worker = engineScope().async { runCatching { call.run().also { returned.set(true) } } }
        val preemption = CompletableDeferred<Unit>()
        val result = async(Dispatchers.Default) {
            runCatching {
                awaitPreemptibleEngineWork(worker, preemption, call::cancel, 5.seconds) { fail("a cooperative call is not wedged") }
            }
        }
        call.started.await(5, TimeUnit.SECONDS)
        preemption.cancel()
        assertTrue(result.await().exceptionOrNull() is IllegalStateException)
        assertEquals(1, call.cancels.get(), "the running call was not stopped")
        assertTrue(returned.get(), "the wait ended before the call unwound")
    }

    @Test
    fun cancelingTheCallerStopsTheCallWaitsForItAndRethrows() = runBlocking {
        val call = StoppableCall()
        val returned = AtomicBoolean(false)
        val worker = engineScope().async { runCatching { call.run().also { returned.set(true) } } }
        val unwoundFirst = AtomicBoolean(false)
        val caller = launch(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
            try {
                awaitPreemptibleEngineWork(worker, CompletableDeferred(), call::cancel, 5.seconds) { fail("not wedged") }
            } catch (e: CancellationException) {
                unwoundFirst.set(returned.get())
                throw e
            }
        }
        call.started.await(5, TimeUnit.SECONDS)
        caller.cancelAndJoin()
        assertEquals(1, call.cancels.get())
        assertTrue(unwoundFirst.get(), "the caller resumed before the call unwound")
        assertFalse(worker.isActive)
    }
}
