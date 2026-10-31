package com.anopticlabs.gravel.ring.sync

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.ring.protocol.RingAdvertisement
import com.anopticlabs.gravel.ring.protocol.RingCollection
import com.anopticlabs.gravel.ring.protocol.RingExchange
import com.anopticlabs.gravel.ring.protocol.RingOutcome
import com.anopticlabs.gravel.ring.protocol.RingProtocolException
import com.anopticlabs.gravel.ring.protocol.RingRecording
import com.anopticlabs.gravel.ring.protocol.RingRequest
import com.anopticlabs.gravel.ring.protocol.assembleRingRecordings
import com.anopticlabs.gravel.ring.protocol.nextRingIndex
import com.anopticlabs.gravel.ring.protocol.parseRingAdvertisementRead
import com.anopticlabs.gravel.ring.protocol.parseRingCollection
import com.anopticlabs.gravel.ring.protocol.parseRingRange
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where a ring's sync stands, kept across sessions. The [setNextIndex] that follows a stored recording runs to
 * the end even when the sync is canceled, so it must not wait on anything unbounded.
 */
interface RingSyncStore {
    /** The index of the first collection not yet stored or given up on; null before the first sync. */
    suspend fun nextIndex(): Int?

    suspend fun setNextIndex(index: Int)

    /** The collection count the ring advertised in the round that ended its last completed sync. */
    suspend fun lastCount(): Int?

    suspend fun setLastCount(count: Int)
}

/**
 * Takes a finished recording whose first collection is [firstIndex]; false when it could not be stored, so the
 * sync stops and offers it again next time. It runs to the end even when the sync is canceled, so it must not
 * wait on anything unbounded.
 */
fun interface RingRecordingSink {
    suspend fun store(recording: RingRecording, firstIndex: Int): Boolean
}

class RingRefusedException(val request: RingRequest, val status: Long) :
    Exception("the ring refused $request with status 0x${status.toString(16)}")

class RingTimeoutException(val request: RingRequest) : Exception("no answer to $request")

enum class RingSessionEnd { SYNCED, NOT_STORED, STILL_RECORDING }

/**
 * Whether a ring advertising [advertisement] has something to sync, given the count its last sync ended at. A
 * session that did not end [RingSessionEnd.SYNCED] leaves that count behind, so the caller backs off before
 * trying again.
 */
fun shouldSyncRing(advertisement: RingAdvertisement, lastCount: Int?): Boolean =
    advertisement.pairable &&
        (advertisement.collectionCount != lastCount || advertisement.needsServicing || advertisement.recording)

/** The Notebook stores audio at 16 kHz; an unfinished group is also bounded by that many samples per millisecond. */
private const val MAX_SAMPLES_PER_MILLI = 16

/**
 * One sync over a [connection]: sets the ring's clock when it asks, reads every collection from the saved
 * position to the end of the stored range, and hands each finished recording to [sink], reading the range
 * again while the ring is still recording. The position advances past a recording once it is stored, and
 * past groups the assembly rejects, unfinished groups past [maxRecordingMillis] (with their later parts) and
 * collections the parser or the ring refuses; an unfinished recording is read again by the next session,
 * since the ring keeps its collections. A failed exchange (a refusal from the ring, a timeout, bytes outside an
 * exchange or an answer the parser refuses) and the store's or the sink's own exceptions end the session by
 * throwing, after which the caller closes the link.
 */
class RingSession(
    private val connection: RingConnection,
    private val store: RingSyncStore,
    private val sink: RingRecordingSink,
    private val nowUnixSeconds: () -> Long,
    private val maxRecordingMillis: Long,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val exchangeTimeout: Duration = 10.seconds,
    private val pollInterval: Duration = 50.milliseconds,
    private val maxDuration: Duration = 10.minutes,
) {
    private val log = Logger.withTag("RingSession")

    suspend fun run(advertisement: RingAdvertisement): RingSessionEnd {
        val started = timeSource.markNow()
        if (advertisement.needsServicing) exchange(RingRequest.SetClock.at(nowUnixSeconds()))
        var open = listOf<Pair<Int, RingCollection>>()
        var dropping: Long? = null
        var next: Int? = null
        while (true) {
            // Read before the range so the count stored at the end is one this round has read up to, as long
            // as the ring counts a collection once it is in the stored range.
            val now = parseRingAdvertisementRead(exchange(RingRequest.ReadAdvertisement))
            val range = parseRingRange(exchange(RingRequest.ReadRange))
            val wanted = next ?: store.nextIndex()
            val at = range.resumeAt(wanted)
            if (at != wanted) {
                open = emptyList()
                store.setNextIndex(at)
            }
            next = at
            while (next != range.end) {
                val index = next!!
                next = nextRingIndex(index)
                val collection = try {
                    readCollection(index)
                } catch (e: RingRefusedException) {
                    // The ring's answer is about this collection, so the sync moves past it and the recording
                    // it belongs to rather than stopping there every time.
                    store.setNextIndex(next)
                    throw e
                }
                if (collection == null) {
                    if (open.isEmpty()) store.setNextIndex(next)
                    continue
                }
                if (collection.startIndex == dropping) {
                    if (collection.final) dropping = null
                    store.setNextIndex(next)
                    continue
                }
                dropping = null
                val read = open + (index to collection)
                val assembly = assembleRingRecordings(read, maxRecordingMillis)
                for ((indexes, reason) in assembly.rejected) log.w { "dropped collections $indexes: $reason" }
                for ((indexes, recording) in assembly.finished) {
                    // Saved with the recording, so a sync canceled while it is stored does not import it again.
                    val stored = withContext(NonCancellable) {
                        sink.store(recording, indexes.first()).also { if (it) store.setNextIndex(nextRingIndex(indexes.last())) }
                    }
                    if (!stored) return RingSessionEnd.NOT_STORED
                }
                open = read.takeLast(assembly.pending.size)
                if (longerThanAllowed(open)) {
                    log.w { "dropped collections ${open.map { it.first }}: past $maxRecordingMillis ms unfinished" }
                    dropping = open.first().second.startIndex
                    open = emptyList()
                }
                store.setNextIndex(open.firstOrNull()?.first ?: next)
            }
            if (!now.recording) {
                store.setLastCount(now.collectionCount)
                return RingSessionEnd.SYNCED
            }
            if (started.elapsedNow() >= maxDuration) return RingSessionEnd.STILL_RECORDING
            delay(pollInterval)
        }
    }

    /** A ring recording is far shorter than the limit (DESIGN_NOTES, ring client), so this means a broken ring. */
    private fun longerThanAllowed(group: List<Pair<Int, RingCollection>>): Boolean {
        val rate = group.firstOrNull()?.second?.audio?.sampleRate ?: return false
        val samples = group.sumOf { it.second.audio.samples.size.toLong() }
        return samples * 1000 > maxRecordingMillis * rate || samples > maxRecordingMillis * MAX_SAMPLES_PER_MILLI
    }

    /** The collection at [index], or null when the parser refuses its bytes. */
    private suspend fun readCollection(index: Int): RingCollection? {
        val bytes = exchange(RingRequest.ReadCollection(index))
        return try {
            parseRingCollection(bytes)
        } catch (e: RingProtocolException) {
            log.w(e) { "skipped collection $index" }
            null
        }
    }

    private suspend fun exchange(request: RingRequest): ByteArray {
        if (connection.notifications.tryReceive().isSuccess) throw RingProtocolException("bytes from the ring between requests")
        val exchange = RingExchange(request)
        val outcome = withTimeoutOrNull(exchangeTimeout) {
            connection.writeControl(request.encode())
            if (request.isWrite) connection.writeData(request.payload)
            var outcome: RingOutcome? = null
            while (outcome == null) {
                outcome = when (val notification = connection.notifications.receive()) {
                    is RingNotification.Control -> exchange.onControl(notification.bytes)
                    is RingNotification.Data -> exchange.onData(notification.bytes)
                }
            }
            outcome
        } ?: throw RingTimeoutException(request)
        return when (outcome) {
            is RingOutcome.Done -> outcome.data
            is RingOutcome.Refused -> throw RingRefusedException(request, outcome.status)
        }
    }
}
