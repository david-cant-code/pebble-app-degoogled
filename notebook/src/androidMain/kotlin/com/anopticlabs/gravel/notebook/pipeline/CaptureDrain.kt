package com.anopticlabs.gravel.notebook.pipeline

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.capture.CaptureFailureReason
import com.anopticlabs.gravel.notebook.capture.CaptureQueueItem
import com.anopticlabs.gravel.notebook.capture.CaptureStage
import com.anopticlabs.gravel.notebook.capture.MAX_QUEUE_ATTEMPTS
import com.anopticlabs.gravel.notebook.capture.ProcessingRules
import com.anopticlabs.gravel.notebook.capture.QueuedCapture
import com.anopticlabs.gravel.notebook.capture.applyQueueDrainResults
import com.anopticlabs.gravel.notebook.capture.captureZone
import com.anopticlabs.gravel.notebook.capture.flagQueuedCapture
import com.anopticlabs.gravel.notebook.capture.isLong
import com.anopticlabs.gravel.notebook.capture.markPhoneTried
import com.anopticlabs.gravel.notebook.capture.markServerTry
import com.anopticlabs.gravel.notebook.capture.mayStart
import com.anopticlabs.gravel.notebook.capture.needsReview
import com.anopticlabs.gravel.notebook.capture.processingOrder
import com.anopticlabs.gravel.notebook.capture.waitsForPower
import com.anopticlabs.gravel.notebook.notes.captureRouteFolders
import com.anopticlabs.gravel.notebook.storage.BlobRead
import com.anopticlabs.gravel.notebook.storage.CorruptFileException
import com.anopticlabs.gravel.notebook.storage.NoteStore
import com.anopticlabs.gravel.notebook.storage.QueueStore
import com.anopticlabs.gravel.notebook.storage.isKeyUnavailableFailure
import com.anopticlabs.gravel.notebook.storage.isPersistedFormatFailure
import com.anopticlabs.gravel.notebook.storage.isTransientCryptoFailure
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone

/**
 * Why a drain stopped early, or with [waitOnly] why captures it went past wait, and what the user can do about it
 * when the screen can offer it; [waitsForNetwork] when a network change may end it.
 */
data class CaptureHold(
    val reason: String,
    val remedy: CaptureRemedy? = null,
    val waitsForNetwork: Boolean = false,
    val waitOnly: Boolean = false,
)

/**
 * What one drain did; [held] names why it stopped early, with the rest of the queue left as it was, or, when it did
 * not, why the [waited] captures wait.
 */
data class DrainReport(
    val published: Int = 0,
    val retried: Int = 0,
    val flagged: Int = 0,
    val dropped: Int = 0,
    val waited: Int = 0,
    val held: CaptureHold? = null,
)

/** The drain's reading of a failure, [CaptureProcessingException] carrying its own. */
internal fun captureFailureKind(e: Throwable): CaptureFailureKind = when {
    e is CaptureProcessingException -> e.kind
    isKeyUnavailableFailure(e) -> CaptureFailureKind.HOLD
    isTransientCryptoFailure(e) -> CaptureFailureKind.RETRY
    // A store file that fails to open or parse blocks every capture alike until it is restored or reset.
    e is CorruptFileException || isPersistedFormatFailure(e) -> CaptureFailureKind.HOLD
    // The stores' validators refuse with it, and would refuse the same capture again.
    e is IllegalArgumentException -> CaptureFailureKind.FLAG
    else -> CaptureFailureKind.RETRY
}

/** What a flagged failure stores with its entry. */
internal fun captureFailureReason(e: Throwable): CaptureFailureReason = when {
    e is CaptureProcessingException -> e.reason ?: CaptureFailureReason.UNKNOWN
    e is IllegalArgumentException -> CaptureFailureReason.NOT_SAVED
    else -> CaptureFailureReason.UNKNOWN
}

private fun holdFor(e: Exception, fallback: String): CaptureHold =
    (e as? CaptureProcessingException)?.let { CaptureHold(it.message ?: fallback, it.remedy, it.waitsForNetwork) } ?: CaptureHold(fallback)

/**
 * Processes the capture queue one capture at a time, on the I/O dispatcher, reading the queue and
 * its [processingOrder] under [rules] again before each capture; a pass tries each capture once,
 * a stopped or refused one aside, and ends when nothing more may start. One drain runs at a time: one
 * asked for meanwhile makes the running one go round again, except that a request made while a
 * drain is being canceled waits for the next drain. A capture's attempt is counted before its
 * engines run, so one that ends the process still counts toward its flag, and given back when the
 * drain holds, is canceled, or the capture waits, is rerouted or is [interrupt]ed. The drain removes a capture from the
 * queue only once it is published, or when its recording is gone; see [captureFailureKind] for the rest. Nothing
 * of a transcript or a reply is logged. A rerouted capture goes through [rules] again in the same pass, once; a second
 * reroute makes it wait. [notice] is read after each pass, [serverTried] called after each server try is recorded.
 * [admitted] runs once a capture can be stopped and before it starts, for tests.
 */
class CaptureDrain(
    private val queue: QueueStore,
    private val notes: NoteStore,
    private val backend: CaptureBackend,
    private val publisher: CapturePublisher,
    private val rows: CaptureRows,
    private val settings: () -> CaptureSettings,
    private val zone: () -> TimeZone = TimeZone::currentSystemDefault,
    private val rules: () -> ProcessingRules = { ProcessingRules() },
    private val notice: () -> CaptureNotice? = { null },
    private val serverTried: () -> Unit = {},
    private val admitted: (QueuedCapture) -> Unit = {},
) {
    private val running = Mutex()
    private val requested = AtomicBoolean(false)
    private val _held = MutableStateFlow<CaptureHold?>(null)

    /** Why the last pass stopped early or left captures waiting; null once a pass gets past its checks, until it ends. */
    val held: StateFlow<CaptureHold?> = _held.asStateFlow()

    private val _passes = MutableStateFlow(0)

    /** Counts finished passes, so a screen can re-read what a pass may have changed. */
    val passes: StateFlow<Int> = _passes.asStateFlow()

    private val _waitingForPower = MutableStateFlow(0)

    /** The long captures the last pass left for the charger rule. */
    val waitingForPower: StateFlow<Int> = _waitingForPower.asStateFlow()

    private val _notices = MutableStateFlow<CaptureNotice?>(null)

    /** What the speech step had to say after the last pass, for captures that went on despite it. */
    val notices: StateFlow<CaptureNotice?> = _notices.asStateFlow()

    // Guards [current] and [pendingStop], so a stop either finds the capture registered or is kept for its start.
    private val currentLock = Any()
    private var current: Started? = null
    private var pendingStop: ((QueuedCapture) -> Boolean)? = null

    private class Started(val entry: QueuedCapture, val job: Job) {
        var stage: CaptureStage? = null
        var settled = false

        @Volatile
        var stopped = false
    }

    private sealed interface Outcome {
        object Published : Outcome
        object Dropped : Outcome
        object Retried : Outcome
        object Flagged : Outcome
        class Held(val hold: CaptureHold) : Outcome

        /** Left queued for a later pass, the rest of this one going on. */
        class Waited(val hold: CaptureHold) : Outcome

        /** Stopped by [interrupt]; it stays queued, flagged when its recording was found damaged before the stop took effect. */
        object Interrupted : Outcome

        /** Its next step changed; given back to go through the rules again, or to wait with [hold] when it already did. */
        class Rerouted(val hold: CaptureHold) : Outcome

        /** The rules changed since the pass began and no longer let it start. */
        object NotNow : Outcome
    }

    /**
     * Stops the capture being started or processed when [which] picks it; the pass then reads the
     * queue again. [transcribingOnly] spares one past its transcription, and one whose outcome is
     * decided is not stopped. With no capture registered, a capture the pass picked meanwhile is
     * kept from starting if [which] picks it. True when the stop was sent to a running capture.
     */
    fun interrupt(transcribingOnly: Boolean, which: (QueuedCapture) -> Boolean): Boolean = synchronized(currentLock) {
        val started = current ?: run {
            val earlier = pendingStop
            pendingStop = if (earlier == null) which else { entry -> earlier(entry) || which(entry) }
            return false
        }
        if (started.settled || started.job.isCompleted || !which(started.entry)) return false
        if (transcribingOnly && started.stage.let { it != null && it != CaptureStage.TRANSCRIBING }) return false
        started.stopped = true
        started.job.cancel(CancellationException("capture interrupted"))
        true
    }

    /** Whether [id] is the capture being started or processed. */
    fun isProcessing(id: String): Boolean = synchronized(currentLock) { current?.entry?.id == id }

    /** The report of the last pass this call ran, or null when a drain already running takes the request. */
    suspend fun drain(): DrainReport? = withContext(Dispatchers.IO) {
        requested.set(true)
        var last: DrainReport? = null
        // Re-checked after unlocking: a request made while the running drain unlocked is not lost.
        while (requested.get()) {
            if (!running.tryLock()) return@withContext last
            try {
                while (requested.getAndSet(false)) {
                    try {
                        last = drainOnce().also { _held.value = it.held }
                    } finally {
                        _notices.value = readNotice()
                    }
                    _passes.value += 1
                }
            } finally {
                running.unlock()
            }
        }
        last
    }

    // Captures are taken one at a time from a fresh read of the queue, so one queued or let go meanwhile is ordered in.
    private suspend fun drainOnce(): DrainReport {
        var report = DrainReport()
        var waiting: CaptureHold? = null
        var pending = emptyList<QueuedCapture>()
        var read = false
        val tried = mutableSetOf<String>()
        val rerouted = mutableSetOf<String>()
        val gone = mutableSetOf<String>()
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                // A stop kept from before this read is answered by the read itself.
                synchronized(currentLock) { pendingStop = null }
                pending = try {
                    queue.readQueue(strict = true).filterNot { it.needsReview }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.w { "capture queue unreadable: ${e::class.simpleName}" }
                    read = false
                    dropRowsInFlight()
                    return report.copy(held = CaptureHold("the capture queue could not be read"))
                }
                read = true
                val order = processingOrder(pending.filter { it.id !in tried }, rules())
                val ids = order.mapTo(mutableSetOf()) { it.id }
                rows.items.value.filter { it.inFlight && it.id !in ids }.forEach { rows.remove(it.id) }
                val entry = order.firstOrNull() ?: return report.copy(held = waiting)
                if (tried.isEmpty() && rerouted.isEmpty()) checkBeforeFirst()?.let { hold ->
                    dropRowsInFlight()
                    return report.copy(held = hold)
                }
                order.forEach { rows.queued(it.id, it.queuedAtMillis) }
                // A second reroute in one pass leaves the capture for the next, so a reroute that changes nothing cannot loop.
                val outcome = start(entry).let { if (it is Outcome.Rerouted && !rerouted.add(entry.id)) Outcome.Waited(it.hold) else it }
                if (outcome == Outcome.Published || outcome == Outcome.Dropped || outcome == Outcome.Flagged) gone += entry.id
                if (outcome != Outcome.NotNow && outcome != Outcome.Interrupted && outcome !is Outcome.Rerouted) tried += entry.id
                report = when (outcome) {
                    Outcome.Published -> report.copy(published = report.published + 1)
                    Outcome.Dropped -> report.copy(dropped = report.dropped + 1)
                    Outcome.Retried -> report.copy(retried = report.retried + 1)
                    Outcome.Flagged -> report.copy(flagged = report.flagged + 1)
                    Outcome.NotNow, Outcome.Interrupted, is Outcome.Rerouted -> report
                    is Outcome.Waited -> {
                        // A wait the network may end is kept over a later one it may not, so the network watch still wakes it.
                        if (waiting?.waitsForNetwork != true || outcome.hold.waitsForNetwork) waiting = outcome.hold
                        report.copy(waited = report.waited + 1)
                    }
                    is Outcome.Held -> {
                        dropRowsInFlight()
                        return report.copy(held = outcome.hold)
                    }
                }
            }
        } catch (e: CancellationException) {
            dropRowsInFlight()
            throw e
        } finally {
            // A pass whose last read failed leaves the last count.
            if (read) {
                val now = rules()
                _waitingForPower.value = pending.count { it.id !in gone && now.waitsForPower(it) }
            }
        }
    }

    /** The checks before a pass's first capture: every store file a publish writes, then the engines. */
    private suspend fun checkBeforeFirst(): CaptureHold? {
        try {
            publisher.checkStores()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w { "Notebook storage unreadable (${captureFailureKind(e)}): ${e::class.simpleName}" }
            return CaptureHold("the Notebook's storage could not be read")
        }
        try {
            backend.checkReady()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w { "capture processing not ready (${captureFailureKind(e)}): ${e::class.simpleName}" }
            return holdFor(e, "capture processing is not ready")
        }
        _held.value = null
        return null
    }

    private fun dropRowsInFlight() = rows.items.value.filter { it.inFlight }.forEach { rows.remove(it.id) }

    private fun readNotice(): CaptureNotice? = try {
        notice()
    } catch (e: Exception) {
        logger.w { "capture notice unreadable: ${e::class.simpleName}" }
        null
    }

    // The capture runs as a lazy child registered before it starts, so a stop that lands first keeps it from running at all.
    private suspend fun start(entry: QueuedCapture): Outcome = coroutineScope {
        val job = async(start = CoroutineStart.LAZY) { process(entry) }
        val started = synchronized(currentLock) {
            if (rules().mayStart(entry) && pendingStop?.invoke(entry) != true) Started(entry, job).also { current = it } else null
        }
        if (started == null) {
            job.cancel()
            rows.remove(entry.id)
            return@coroutineScope Outcome.NotNow
        }
        try {
            admitted(entry)
            job.await()
        } catch (e: CancellationException) {
            // Only a stop aimed at this capture is taken here; the drain's own cancellation, or any other, ends the drain.
            if (!isActive || !started.stopped) throw e
            rows.requeue(entry.id)
            Outcome.Interrupted
        } finally {
            synchronized(currentLock) { current = null }
        }
    }

    private fun startedFor(entry: QueuedCapture): Started? = current?.takeIf { it.entry.id == entry.id }

    /** From here a stop is refused; false when one landed before, which the caller then honors. */
    private suspend fun settling(entry: QueuedCapture): Boolean {
        synchronized(currentLock) { startedFor(entry)?.settled = true }
        return currentCoroutineContext().isActive
    }

    private suspend fun process(entry: QueuedCapture): Outcome {
        val counted = entry.attempts + 1
        try {
            setAttempts(entry.id, counted)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return failed(entry, e, counted = entry.attempts)
        }
        try {
            // A read that quarantines must reach the flag below: a stop landing in between would
            // leave an entry the next pass drops.
            val audio = when (val blob = withContext(NonCancellable) { queue.readBlob(entry.id) }) {
                is BlobRead.Present -> blob.bytes
                BlobRead.Absent -> {
                    settling(entry)
                    withContext(NonCancellable) { queue.remove(entry.id) }
                    rows.remove(entry.id)
                    logger.w { "capture ${entry.id} had no recording; entry dropped" }
                    return Outcome.Dropped
                }
                BlobRead.Corrupt -> {
                    synchronized(currentLock) { startedFor(entry)?.settled = true }
                    return flag(entry, CaptureProcessingException(CaptureFailureKind.FLAG, "this recording could not be read", reason = CaptureFailureReason.DAMAGED))
                }
            }
            val folders = captureRouteFolders(notes.readFolders(strict = true))
            val request = CaptureRequest(
                audio = audio,
                capturedAt = Instant.fromEpochMilliseconds(entry.queuedAtMillis),
                zone = captureZone(entry.zoneId) ?: zone(),
                folders = folders,
                folderTags = notes.readFolderTags(strict = true).filterKeys { it in folders },
                settings = settings(),
                id = entry.id,
                phoneTried = entry.phoneTried,
                markPhoneTried = { recordPhoneTried(entry.id) },
                long = entry.isLong,
                fromWatch = entry.fromWatch,
                serverTries = entry.serverTries,
                serverTriedAtMillis = entry.serverTriedAtMillis,
                markServerTry = { at -> recordServerTry(entry.id, at) },
            )
            val built = backend.process(request) { stage ->
                rows.stage(entry.id, stage)
                synchronized(currentLock) { startedFor(entry)?.stage = stage }
            }
            if (!settling(entry)) throw CancellationException("capture interrupted")
            // Once published, the entry must go even if the drain is being canceled.
            val published = withContext(NonCancellable) {
                publisher.publish(built, entry.queuedAtMillis, folders).also { dequeue(entry.id) }
            }
            settle(entry.id, published)
            logger.i { "capture ${entry.id} published as ${published::class.simpleName}" }
            return Outcome.Published
        } catch (e: CancellationException) {
            giveBack(entry)
            throw e
        } catch (e: Exception) {
            return failed(entry, e, counted)
        }
    }

    private suspend fun failed(entry: QueuedCapture, e: Exception, counted: Int): Outcome {
        if (!settling(entry)) {
            giveBack(entry)
            throw CancellationException("capture interrupted")
        }
        val kind = captureFailureKind(e)
        logger.w { "capture ${entry.id} failed ($kind): ${e::class.simpleName}" }
        return when (kind) {
            CaptureFailureKind.HOLD -> {
                giveBack(entry)
                rows.remove(entry.id)
                Outcome.Held(holdFor(e, "the Notebook's storage could not be used"))
            }
            CaptureFailureKind.WAIT -> {
                giveBack(entry)
                rows.remove(entry.id)
                Outcome.Waited(holdFor(e, "this recording has to wait").copy(waitOnly = true))
            }
            CaptureFailureKind.RETRY -> if (counted >= MAX_QUEUE_ATTEMPTS) {
                rows.settle(entry.id, CaptureQueueItem.Status.FAILED, null, "Couldn't process this recording")
                Outcome.Flagged
            } else {
                rows.settle(entry.id, CaptureQueueItem.Status.FAILED, null, "Couldn't process this recording yet; it will be tried again")
                Outcome.Retried
            }
            CaptureFailureKind.FLAG -> flag(entry, e)
            CaptureFailureKind.REROUTE -> {
                giveBack(entry)
                rows.requeue(entry.id)
                Outcome.Rerouted(holdFor(e, "this recording has to wait").copy(waitOnly = true))
            }
        }
    }

    private suspend fun flag(entry: QueuedCapture, e: Exception): Outcome {
        try {
            withContext(NonCancellable) { queue.update { flagQueuedCapture(it, entry.id, captureFailureReason(e)) } }
        } catch (w: Exception) {
            logger.w { "capture ${entry.id} could not be flagged: ${w::class.simpleName}" }
        }
        val reason = (e as? CaptureProcessingException)?.message ?: "couldn't process this recording"
        rows.settle(entry.id, CaptureQueueItem.Status.FAILED, null, reason.replaceFirstChar { it.uppercase() })
        return Outcome.Flagged
    }

    private fun settle(id: String, published: PublishedCapture) = when (published) {
        is PublishedCapture.NoteSaved ->
            rows.settle(id, CaptureQueueItem.Status.NOTE_CREATED, CaptureQueueItem.Kind.NOTE, published.title, noteId = published.noteId)
        is PublishedCapture.ReminderSet ->
            rows.settle(id, CaptureQueueItem.Status.REMINDER_SET, CaptureQueueItem.Kind.REMINDER, published.reminder.message, reminderId = published.reminder.id)
        is PublishedCapture.Parked -> rows.remove(id)
    }

    // A failure here leaves a published capture queued, to be published again by a later drain.
    private suspend fun dequeue(id: String) {
        try {
            queue.remove(id)
        } catch (e: Exception) {
            logger.w { "published capture $id could not be dequeued: ${e::class.simpleName}" }
        }
    }

    // A failure leaves the phone to try the capture again next time.
    private suspend fun recordPhoneTried(id: String) {
        try {
            withContext(NonCancellable) { queue.update { markPhoneTried(it, id) } }
        } catch (e: Exception) {
            logger.w { "capture $id not marked as tried on the phone: ${e::class.simpleName}" }
        }
    }

    private suspend fun recordServerTry(id: String, atMillis: Long): Boolean {
        try {
            withContext(NonCancellable) { queue.update { markServerTry(it, id, atMillis) } }
        } catch (e: Exception) {
            logger.w { "server try of capture $id not recorded: ${e::class.simpleName}" }
            return false
        }
        try {
            serverTried()
        } catch (e: Exception) {
            logger.w(e) { "next server try not scheduled" }
        }
        return true
    }

    private suspend fun setAttempts(id: String, attempts: Int) {
        withContext(NonCancellable) { queue.update { applyQueueDrainResults(it, emptySet(), mapOf(id to attempts)) } }
    }

    /** Takes back the attempt counted for [entry]; a failure leaves it counted. */
    private suspend fun giveBack(entry: QueuedCapture) {
        try {
            setAttempts(entry.id, entry.attempts)
        } catch (e: Exception) {
            logger.w { "attempt of capture ${entry.id} could not be given back: ${e::class.simpleName}" }
        }
    }

    private companion object {
        val logger = Logger.withTag("CaptureDrain")
    }
}
