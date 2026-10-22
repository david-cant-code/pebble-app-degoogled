package com.anopticlabs.gravel.transcription

import coredevices.whisper.WhisperEngineUnavailableException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * `awaitEngineWork` for a call that gives way: when [preemption] completes
 * first, [cancel] is requested and the worker's own result returned once
 * it unwinds; past [unwindBound] [onWedged] runs and the wait fails as
 * [WhisperEngineUnavailableException].
 */
internal suspend fun <T> awaitPreemptibleEngineWork(
    worker: Deferred<Result<T>>,
    preemption: Deferred<Unit>,
    cancel: () -> Unit,
    unwindBound: Duration,
    onWedged: () -> Unit,
): T {
    val preempted = try {
        // Biased to the first clause: a call that finished is not preempted.
        select<Boolean> {
            worker.onAwait { false }
            preemption.onAwait { true }
        }
    } catch (e: CancellationException) {
        // The caller, the worker or the preemption watch was canceled; in
        // every case the call may still be running, so it is stopped and
        // waited out before the engine's lock can be released.
        val callerCanceled = !currentCoroutineContext().isActive
        cancel()
        if (unwound(worker, unwindBound) == null) onWedged()
        if (callerCanceled) throw e
        throw IllegalStateException("Engine worker cancelled unexpectedly", e)
    }
    if (!preempted) return worker.await().getOrThrow()
    cancel()
    val outcome = unwound(worker, unwindBound) ?: run {
        onWedged()
        throw WhisperEngineUnavailableException("the engine did not stop within $unwindBound of a preemption")
    }
    return outcome.getOrThrow()
}

private suspend fun <T> unwound(worker: Deferred<Result<T>>, bound: Duration): Result<T>? =
    withContext(NonCancellable) {
        withTimeoutOrNull(bound) {
            worker.join()
            try {
                worker.await()
            } catch (e: CancellationException) {
                Result.failure(IllegalStateException("Engine worker cancelled unexpectedly", e))
            }
        }
    }
