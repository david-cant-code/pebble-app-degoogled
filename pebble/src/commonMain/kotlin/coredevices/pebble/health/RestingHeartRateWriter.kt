package coredevices.pebble.health

import io.rebble.libpebblecommon.connection.AppContext

/**
 * Fork seam. health-kmp has no resting-heart-rate type, so the daily resting HR shown in the
 * Health tab is written to the platform directly. Its permission is requested separately from
 * the health-kmp ones and is optional: the rest of the sync works without it.
 */
interface RestingHeartRateWriter {
    fun isSupported(): Boolean
    suspend fun isGranted(): Boolean
    /** Shows the platform permission dialog. Returns true if granted. */
    suspend fun requestPermission(): Boolean
    /** Inserts or updates one record per [RestingHeartRateEntry.epochDay]. */
    suspend fun write(entries: List<RestingHeartRateEntry>): Result<Unit>
}

/**
 * Resting HR for the sleep that ended on [epochDay] (local date, days since 1970-01-01),
 * timestamped at wake-up.
 */
data class RestingHeartRateEntry(
    val epochDay: Long,
    val wakeEpochSec: Long,
    val bpm: Int,
)

internal expect fun createRestingHeartRateWriter(appContext: AppContext): RestingHeartRateWriter

/** Days of history re-checked on every sync (also the first-sync backfill). */
internal const val RESTING_HR_LOOKBACK_DAYS = 14

/** Entries whose value or wake time differs from what was last written for that day. */
internal fun restingHrEntriesToWrite(
    computed: List<RestingHeartRateEntry>,
    written: Map<Long, RestingHeartRateEntry>,
): List<RestingHeartRateEntry> = computed.filter { written[it.epochDay] != it }

/** Compact persistence of the last-written entries: "epochDay:wake:bpm,...". */
internal fun encodeRestingHrWritten(entries: Collection<RestingHeartRateEntry>): String =
    entries.sortedBy { it.epochDay }.joinToString(",") { "${it.epochDay}:${it.wakeEpochSec}:${it.bpm}" }

internal fun decodeRestingHrWritten(value: String?): Map<Long, RestingHeartRateEntry> =
    value.orEmpty().split(",").mapNotNull { part ->
        val f = part.split(":")
        if (f.size != 3) return@mapNotNull null
        val day = f[0].toLongOrNull() ?: return@mapNotNull null
        val wake = f[1].toLongOrNull() ?: return@mapNotNull null
        val bpm = f[2].toIntOrNull() ?: return@mapNotNull null
        RestingHeartRateEntry(day, wake, bpm)
    }.associateBy { it.epochDay }
