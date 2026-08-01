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
 */
class BoostRefCounter(
    private val start: () -> Unit,
    private val stop: () -> Unit,
    private val postToMain: (() -> Unit) -> Unit,
) {
    private val refCount = AtomicInteger(0)

    fun acquire() {
        if (refCount.getAndIncrement() == 0) {
            try {
                start()
            } catch (e: Exception) {
                // Load-bearing degrade: on API 31+ a start from the
                // background is only allowed via the companion-device
                // exemption, and setups outside it land here. The failure
                // must not escape, so transcription proceeds unboosted.
                logger.w(e) { "Inference boost unavailable; continuing unboosted" }
            }
        }
    }

    fun release() {
        if (refCount.decrementAndGet() == 0) {
            // Hop to main so the service's own onStartCommand runs before a
            // stop can land, then re-check: a transcription that began
            // while the stop was queued keeps the service alive.
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
