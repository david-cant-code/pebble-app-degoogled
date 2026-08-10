package coredevices.util.transcription

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Contract tests for [awaitEngineWork], the piece that makes cancelling a
 * transcription actually interrupt the engine. The stand-in for the native
 * call is a latch-parked block on a real thread, and the tests run in real
 * time on purpose: the contract under test is exactly the interaction
 * between coroutine cancellation and a thread that cannot observe it, which
 * a virtual clock cannot exercise.
 */
class AwaitEngineWorkTest {

    // Mirrors production: the worker lives on its own scope, never a child
    // of the waiting coroutine, so cancelling the waiter leaves the "native
    // call" free to finish unwinding.
    private fun engineScope() = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Test
    fun cancellationArmsAbortWaitsForUnwindThenRethrows() = runBlocking<Unit> {
        val scope = engineScope()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val engineReturned = AtomicBoolean(false)
        val abortArmed = AtomicBoolean(false)
        val unwoundBeforeResume = AtomicBoolean(false)
        val sawCancellation = AtomicBoolean(false)

        val worker = scope.async {
            runCatching {
                started.countDown()
                release.await() // the parked "native" call
                engineReturned.set(true)
                "unused"
            }
        }
        // UNDISPATCHED so the caller is deterministically suspended inside
        // worker.await() when cancelAndJoin fires; a default start races the
        // cancel and can kill the coroutine before its body ever runs.
        val caller = launch(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
            try {
                awaitEngineWork(
                    worker = worker,
                    // A cooperative engine: honours the abort by unwinding.
                    setCancel = { armed ->
                        if (armed) {
                            abortArmed.set(true)
                            release.countDown()
                        }
                    },
                    unwindBound = 5.seconds,
                    onWedged = { fail("a cooperative engine must not be reported wedged") },
                )
            } catch (e: CancellationException) {
                // The load-bearing ordering: by the time the caller resumes
                // (and its mutex becomes releasable), the engine call must
                // have fully unwound. Recorded, not asserted, because a
                // throw inside a cancelled coroutine would be swallowed.
                unwoundBeforeResume.set(engineReturned.get())
                sawCancellation.set(true)
                throw e
            }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS), "engine call never started")
        caller.cancelAndJoin()
        assertTrue(abortArmed.get(), "cancellation did not arm the abort flag")
        assertTrue(sawCancellation.get(), "caller did not observe cancellation")
        assertTrue(unwoundBeforeResume.get(), "caller resumed before the engine call unwound")
        scope.cancel()
    }

    @Test
    fun wedgedEngineIsAbandonedWithinBound() = runBlocking<Unit> {
        val scope = engineScope()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val wedgeReported = AtomicInteger(0)
        val worker = scope.async {
            runCatching {
                started.countDown()
                release.await() // ignores the abort flag: a wedged engine
                "unused"
            }
        }
        // UNDISPATCHED for the same reason as the cooperative-engine test.
        val caller = launch(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
            awaitEngineWork(
                worker = worker,
                setCancel = { /* armed but ignored by the wedge */ },
                unwindBound = 200.milliseconds,
                onWedged = { wedgeReported.incrementAndGet() },
            )
        }
        assertTrue(started.await(5, TimeUnit.SECONDS), "engine call never started")
        // Must return despite the parked engine thread; a hang here is the
        // stuck-mutex failure the bound exists to prevent.
        caller.cancelAndJoin()
        assertEquals(1, wedgeReported.get(), "wedge containment did not run exactly once")
        release.countDown() // free the parked thread before tearing down
        scope.cancel()
    }

    @Test
    fun normalCompletionReturnsTheEngineResult() = runBlocking<Unit> {
        val scope = engineScope()
        val abortArmed = AtomicBoolean(false)
        val worker = scope.async { runCatching { "transcribed text" } }
        val result = awaitEngineWork(
            worker = worker,
            setCancel = { if (it) abortArmed.set(true) },
            unwindBound = 5.seconds,
            onWedged = { fail("no wedge on the happy path") },
        )
        assertEquals("transcribed text", result)
        assertFalse(abortArmed.get(), "abort must not be armed without cancellation")
        scope.cancel()
    }

    @Test
    fun engineFailurePropagatesAsItself() = runBlocking<Unit> {
        val scope = engineScope()
        val worker = scope.async { runCatching { error("engine exploded") } }
        val e = assertFailsWith<IllegalStateException> {
            awaitEngineWork(
                worker = worker,
                setCancel = { fail("failure is not cancellation") },
                unwindBound = 5.seconds,
                onWedged = { fail("failure is not a wedge") },
            )
        }
        assertEquals("engine exploded", e.message)
        scope.cancel()
    }

    @Test
    fun workerScopeDeathSurfacesAsFailureNotCancellation() = runBlocking<Unit> {
        val scope = engineScope()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = scope.async {
            runCatching {
                started.countDown()
                release.await()
                "unused"
            }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS), "engine call never started")
        // Kill the worker's scope out from under a live caller, then free
        // the parked block so the cancelled deferred can reach its final
        // state (await only resumes on final states).
        scope.cancel()
        release.countDown()
        assertFailsWith<IllegalStateException> {
            awaitEngineWork(
                worker = worker,
                setCancel = { fail("the caller was never cancelled") },
                unwindBound = 1.seconds,
                onWedged = { fail("the caller was never cancelled") },
            )
        }
    }
}
