package com.anopticlabs.gravel.notebook.capture

/**
 * One row of the in-memory capture list: what the queue is doing and what it produced. [id] is the
 * durable queue entry's id. [stage] is set only while PROCESSING; [resultLabel] holds the terminal
 * text; [noteId] is set on NOTE_CREATED, [reminderId] on REMINDER_SET.
 */
data class CaptureQueueItem(
    val id: String,
    val createdAt: Long,
    val status: Status,
    val kind: Kind? = null,
    val stage: CaptureStage? = null,
    val resultLabel: String? = null,
    val noteId: String? = null,
    val reminderId: Int? = null,
) {
    enum class Status { QUEUED, PROCESSING, NOTE_CREATED, REMINDER_SET, FAILED }

    enum class Kind { NOTE, REMINDER }

    val inFlight: Boolean get() = status == Status.QUEUED || status == Status.PROCESSING
}

/** The "n of n" line: [current] counts up as the batch drains; inactive when nothing is in flight. */
data class CaptureQueueProgress(val current: Int, val total: Int, val stage: CaptureStage?) {
    val active: Boolean get() = total > 0 && current in 1..total
}

/** [batchSubmitted] is raised to the in-flight count, so a stale counter cannot read "1 of 0". */
fun captureQueueProgress(items: List<CaptureQueueItem>, batchSubmitted: Int): CaptureQueueProgress {
    val inFlight = items.count { it.inFlight }
    if (inFlight == 0) return CaptureQueueProgress(0, 0, null)
    val total = batchSubmitted.coerceAtLeast(inFlight)
    val current = (total - inFlight + 1).coerceIn(1, total)
    val processing = items.firstOrNull { it.status == CaptureQueueItem.Status.PROCESSING }
    return CaptureQueueProgress(current, total, processing?.stage)
}

fun captureProgressText(p: CaptureQueueProgress): String {
    if (!p.active) return ""
    val base = "Processing ${p.current} of ${p.total}"
    return p.stage?.let { "$base \u00B7 ${it.label}" } ?: base
}

/** Appends [item] unless a row with its id exists, so a retried enqueue never doubles a row. */
fun addCaptureItem(items: List<CaptureQueueItem>, item: CaptureQueueItem): List<CaptureQueueItem> =
    if (items.any { it.id == item.id }) items else items + item

/** Moves row [id] to PROCESSING at [stage] while it is still in flight; a late tick cannot revive a settled row. */
fun withCaptureItemStage(items: List<CaptureQueueItem>, id: String, stage: CaptureStage): List<CaptureQueueItem> =
    items.map {
        if (it.id == id && it.inFlight) it.copy(status = CaptureQueueItem.Status.PROCESSING, stage = stage) else it
    }

fun withSettledCaptureItem(
    items: List<CaptureQueueItem>,
    id: String,
    status: CaptureQueueItem.Status,
    kind: CaptureQueueItem.Kind?,
    label: String?,
    noteId: String? = null,
    reminderId: Int? = null,
): List<CaptureQueueItem> =
    items.map {
        if (it.id == id) {
            it.copy(status = status, kind = kind, stage = null, resultLabel = label, noteId = noteId, reminderId = reminderId)
        } else {
            it
        }
    }

/** Puts row [id] back to QUEUED while it is still in flight, for a capture stopped to be started again later. */
fun withRequeuedCaptureItem(items: List<CaptureQueueItem>, id: String): List<CaptureQueueItem> =
    items.map { if (it.id == id && it.inFlight) it.copy(status = CaptureQueueItem.Status.QUEUED, stage = null) else it }

fun withoutCaptureItem(items: List<CaptureQueueItem>, id: String): List<CaptureQueueItem> =
    items.filterNot { it.id == id }

/**
 * Drops the reminder id from a REMINDER_SET row whose reminder is no longer stored, since ids are
 * reused. [storedReminderIds] must come from a strict storage read, never a degraded one.
 */
fun reconcileStaleReminderRows(items: List<CaptureQueueItem>, storedReminderIds: Set<Int>): List<CaptureQueueItem> =
    items.map {
        if (it.status == CaptureQueueItem.Status.REMINDER_SET && it.reminderId != null && it.reminderId !in storedReminderIds) {
            it.copy(reminderId = null)
        } else {
            it
        }
    }

/** The batch counter after a settle: back to zero once nothing is in flight. */
fun captureBatchAfterSettle(items: List<CaptureQueueItem>, current: Int): Int =
    if (items.none { it.inFlight }) 0 else current

fun captureQueueRowLabel(item: CaptureQueueItem): String = when (item.status) {
    CaptureQueueItem.Status.QUEUED -> "Waiting to process"
    CaptureQueueItem.Status.PROCESSING -> item.stage?.label ?: "Working"
    CaptureQueueItem.Status.NOTE_CREATED -> item.resultLabel ?: "Note saved"
    CaptureQueueItem.Status.REMINDER_SET -> item.resultLabel ?: "Reminder set"
    CaptureQueueItem.Status.FAILED -> item.resultLabel ?: "Couldn't process this recording"
}
