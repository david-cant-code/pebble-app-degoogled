package coredevices.util.models

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Decides whether an in-flight model download job is still alive based on the
 * last heartbeat the running job recorded. A crashed/abandoned job (process
 * death, hung blocking I/O) stops writing heartbeats; treating it as stale lets
 * the JobScheduler guard cancel and re-schedule instead of blocking forever.
 */
object DownloadJobLiveness {
    /** A healthy job writes a heartbeat well within this window. */
    const val DEFAULT_STALE_AFTER_MILLIS: Long = 90_000L

    /** How often a running job should record a heartbeat. */
    const val DEFAULT_HEARTBEAT_INTERVAL_MILLIS: Long = 20_000L

    private const val MIN_RATE_BYTES_PER_SECOND = 128L * 1024L
    val MIN_TIMEOUT: Duration = 45.minutes
    val MAX_TIMEOUT: Duration = 5.hours

    /**
     * The cap stays under the 6 hours the platform guarantees a user-initiated data-transfer job within its
     * timeout quota (android16-release, `JobSchedulerService.getMinJobExecutionGuaranteeMs`).
     */
    fun timeoutFor(sizeBytes: Long): Duration =
        (sizeBytes / MIN_RATE_BYTES_PER_SECOND).seconds.coerceIn(MIN_TIMEOUT, MAX_TIMEOUT)

    fun isStale(
        lastHeartbeatMillis: Long?,
        nowMillis: Long,
        staleAfterMillis: Long = DEFAULT_STALE_AFTER_MILLIS,
    ): Boolean {
        if (lastHeartbeatMillis == null) return true
        // Clock moved backwards (reboot, time change): treat as stale rather than alive forever.
        if (nowMillis < lastHeartbeatMillis) return true
        return nowMillis - lastHeartbeatMillis >= staleAfterMillis
    }
}