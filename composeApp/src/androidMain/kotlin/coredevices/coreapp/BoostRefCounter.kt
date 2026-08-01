package coredevices.coreapp

import co.touchlab.kermit.Logger
import java.util.concurrent.atomic.AtomicInteger

/**
 * Start/stop policy for the inference boost service, mirroring the ref
 * count of the upstream experimental service: overlapping transcriptions
 * share one service, the outermost acquire starts it, the last release
 * stops it. Collaborators are injected as lambdas so the policy runs
 * under plain JVM tests; the Android glue lives in
 * [AndroidInferenceBoost].
 *
 * Fork deviation from the upstream policy: both start and stop decisions
 * run through [postToMain], never inline on the caller's thread. Upstream
 * starts inline, which leaves a race in its posted stop: between the
 * stop's ref-count re-check and the stopService call, a worker thread can
 * acquire and issue an inline start, and the stop then tears down a
 * service a live transcription is holding, with no retry until the next
 * cold acquire. Serializing both sides on one thread closes that; the
 * cost is a main-looper hop before the boost engages, noise against an
 * inference that runs for seconds.
 */
class BoostRefCounter(
    private val start: () -> Unit,
    private val stop: () -> Unit,
    private val postToMain: (() -> Unit) -> Unit,
) {
    private val refCount = AtomicInteger(0)

    fun acquire() {
        if (refCount.getAndIncrement() == 0) {
            postToMain {
                try {
                    start()
                } catch (e: Exception) {
                    // Load-bearing degrade: on API 31+ a start from the
                    // background is only allowed via the companion-device
                    // exemption, and setups outside it land here. The failure
                    // must not escape (least of all on the main thread), so
                    // transcription proceeds unboosted.
                    logger.w(e) { "Inference boost unavailable; continuing unboosted" }
                }
            }
        }
    }

    fun release() {
        if (refCount.decrementAndGet() == 0) {
            // Re-check on the serialized thread: a transcription that began
            // while the stop was queued keeps the service alive (its start,
            // if any, is queued behind this and re-starts the service).
            postToMain {
                if (refCount.get() == 0) {
                    stop()
                }
            }
        }
    }

    private companion object {
        private val logger = Logger.withTag("BoostRefCounter")
    }
}
