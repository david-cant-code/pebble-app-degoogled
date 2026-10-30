package com.anopticlabs.gravel.notebook.pipeline

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.AppVisibility
import com.anopticlabs.gravel.notebook.capture.CaptureQueueItem
import com.anopticlabs.gravel.notebook.storage.NotebookHousekeeping
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What keeps the process working while captures are processed with no activity resumed. */
interface CaptureKeepAlive {
    /** Starts the foreground service and takes the wake lock; failures are the implementation's to log. */
    fun hold()

    /** Pushes the wake lock's timeout out again. */
    fun extend()

    /** Lets the wake lock go; the service stops itself. */
    fun release()
}

/** What keeps the process alive from a recording's start until the recording is queued. */
interface RecordingKeepAlive {
    fun holdRecording()

    fun releaseRecording()
}

/**
 * Runs the capture drain on a scope of its own, so no screen's lifetime bounds it, one run at a
 * time: the startup housekeeping on the first run, the salvage of retained recordings and the
 * cleanup of set-aside files on every one, then a drain. A hold starts once a run has a capture
 * in flight, or a capture is queued, with no activity resumed, and lasts until the run ends or an
 * activity resumes.
 */
class CaptureCoordinator internal constructor(
    private val work: suspend (atStart: Boolean) -> Unit,
    private val rows: StateFlow<List<CaptureQueueItem>>,
    private val keepAlive: CaptureKeepAlive,
    private val scope: CoroutineScope,
) {
    constructor(drain: CaptureDrain, housekeeping: NotebookHousekeeping, rows: CaptureRows, keepAlive: CaptureKeepAlive) : this(
        work = { atStart ->
            housekeeping.run(atStart)
            drain.drain()?.let { logger.i { "drain: $it" } }
        },
        rows = rows.items,
        keepAlive = keepAlive,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> logger.w(e) { "capture run failed" } }),
    )

    private val lock = Any()
    private val started = AtomicBoolean(false)
    private var ranOnce = false
    private var running = false
    private var again = false
    private var inFlight = false
    private var queuedUnseen = false
    private var resumed = false
    private var holding = false

    private val _working = MutableStateFlow(false)

    /** True while a run is under way. */
    val working: StateFlow<Boolean> = _working.asStateFlow()

    /** Watches the capture rows and [visibility]; a resumed activity after none was also asks for a run. */
    fun start(visibility: AppVisibility) {
        if (!started.compareAndSet(false, true)) return
        scope.launch { rows.collect { items -> onRows(items.any { it.inFlight }) } }
        visibility.addResumedListener(::onResumed)
    }

    /** Starts a run unless one is under way, since that run reads the whole queue. */
    fun request() = startRun(queued = false)

    /**
     * For a capture just queued: starts a run, or makes the run under way go round once more. With
     * no activity resumed the hold is taken before this returns, so a hold its caller has can end.
     */
    fun captureQueued() = startRun(queued = true)

    /**
     * For a change a run under way may have read too early (a new text model): starts a run, or makes the run under
     * way go round once more; unlike [captureQueued] it takes no hold itself.
     */
    fun requestAgain() = startRun(queued = false, goRound = true)

    private fun startRun(queued: Boolean, goRound: Boolean = queued) {
        val atStart = synchronized(lock) {
            if (queued && !resumed) queuedUnseen = true
            if (running) {
                again = again || goRound
                reconcileLocked()
                return
            }
            running = true
            _working.value = true
            reconcileLocked()
            !ranOnce.also { ranOnce = true }
        }
        scope.launch { runUntilDone(atStart) }
    }

    private suspend fun runUntilDone(atStart: Boolean) {
        var first = atStart
        try {
            do {
                work(first)
                first = false
            } while (goAgain())
        } catch (e: Throwable) {
            synchronized(lock) { endRunLocked() }
            throw e
        }
    }

    // Under the lock, so a capture queued as the run ends either makes it go again or finds it over.
    private fun goAgain(): Boolean = synchronized(lock) {
        if (again) {
            again = false
            true
        } else {
            endRunLocked()
            false
        }
    }

    private fun endRunLocked() {
        running = false
        again = false
        queuedUnseen = false
        _working.value = false
        reconcileLocked()
    }

    internal fun onResumed(isResumed: Boolean) {
        synchronized(lock) {
            resumed = isResumed
            reconcileLocked()
        }
        if (isResumed) request()
    }

    private fun onRows(anyInFlight: Boolean) = synchronized(lock) {
        inFlight = anyInFlight
        val wasHolding = holding
        reconcileLocked()
        if (wasHolding && holding) guarded("extend", keepAlive::extend)
    }

    // Under the lock, so a hold and a release decided on different threads reach the keep-alive in order.
    private fun reconcileLocked() {
        val want = running && !resumed && (inFlight || queuedUnseen || holding)
        if (want == holding) return
        holding = want
        if (want) guarded("hold", keepAlive::hold) else guarded("release", keepAlive::release)
    }

    private fun guarded(what: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            logger.w(e) { "keep-alive $what failed" }
        }
    }

    private companion object {
        val logger = Logger.withTag("CaptureCoordinator")
    }
}
