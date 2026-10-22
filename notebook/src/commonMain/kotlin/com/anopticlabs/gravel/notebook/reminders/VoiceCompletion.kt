package com.anopticlabs.gravel.notebook.reminders

import com.anopticlabs.gravel.notebook.search.BodyTokenizer
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable

/** A parked completion claim: the trigger-stripped [transcript], waiting for the user to pick a reminder. */
@Serializable
data class VoiceCompletion(
    val completionId: String,
    val transcript: String,
    val capturedAt: Long,
)

enum class VoiceCompletionAction { COMPLETE, NOT_DONE }

/** The existing reminder write a candidate tap dispatches to. */
sealed interface VoiceCompletionWriteTarget {
    data object UpcomingComplete : VoiceCompletionWriteTarget
    data object UpcomingNotDone : VoiceCompletionWriteTarget
    data object OverdueComplete : VoiceCompletionWriteTarget
    data object OverdueMissed : VoiceCompletionWriteTarget
}

/** Null means no write: an already resolved occurrence offers nothing. Deleting an occurrence is left to the calendar. */
fun voiceCompletionWriteTarget(status: OccurrenceStatus, action: VoiceCompletionAction): VoiceCompletionWriteTarget? =
    when (status) {
        OccurrenceStatus.PENDING -> when (action) {
            VoiceCompletionAction.COMPLETE -> VoiceCompletionWriteTarget.UpcomingComplete
            VoiceCompletionAction.NOT_DONE -> VoiceCompletionWriteTarget.UpcomingNotDone
        }
        OccurrenceStatus.OVERDUE -> when (action) {
            VoiceCompletionAction.COMPLETE -> VoiceCompletionWriteTarget.OverdueComplete
            VoiceCompletionAction.NOT_DONE -> VoiceCompletionWriteTarget.OverdueMissed
        }
        OccurrenceStatus.COMPLETED, OccurrenceStatus.MISSED -> null
    }

/** The actions a candidate row offers, derived from [voiceCompletionWriteTarget] so buttons and writes agree. */
fun resolutionActionsFor(status: OccurrenceStatus): List<VoiceCompletionAction> =
    VoiceCompletionAction.entries.filter { voiceCompletionWriteTarget(status, it) != null }

fun hasAlreadyCompletedCandidate(candidates: List<ReminderMatch.ScoredCandidate>): Boolean =
    candidates.any { it.status == OccurrenceStatus.COMPLETED }

const val VOICE_COMPLETION_MAX_CANDIDATES = 4

/**
 * A reminder a claim may complete: [event] is the logged occurrence it stands for, null for a
 * stored reminder's next time; [repeats] marks such a next time of a repeating reminder.
 */
data class CompletionCandidate(val match: ReminderMatch.ScoredCandidate, val event: ReminderEvent?, val repeats: Boolean) {
    val key: ReminderEventKey get() = ReminderEventKey(match.reminderId, match.originalFireMillis)
}

/**
 * The best candidates for [transcript] among every stored reminder, one whose time passed
 * included, and the logged occurrences, ranked by distance from [capturedAtMillis], when the
 * claim was spoken. A reminder still sitting on an occurrence the log holds is offered as that
 * occurrence only. A logged occurrence nobody acted on stays open however long ago it lapsed:
 * PENDING while its latest fire is after the capture, OVERDUE otherwise. [messageTokens] lets a
 * caller ranking several claims tokenize each message once.
 */
fun completionCandidates(
    transcript: String,
    items: List<ReminderItem>,
    events: List<ReminderEvent>,
    capturedAtMillis: Long,
    zone: TimeZone,
    messageTokens: (String) -> Set<String> = BodyTokenizer::tokenizeQuery,
): List<CompletionCandidate> {
    val logged = events.associateBy { it.key }
    val next = items.filterNot { ReminderEventKey(it.id, it.remindAtMillis) in logged }
    val ranked = ReminderMatch.rankReminderCandidates(transcript, next, events, capturedAtMillis, zone, VOICE_COMPLETION_MAX_CANDIDATES, messageTokens)
    return ranked.map { match ->
        val event = logged[ReminderEventKey(match.reminderId, match.originalFireMillis)]
        val repeats = event == null && next.any { it.id == match.reminderId && it.recurrence != null }
        CompletionCandidate(event?.let { match.copy(status = claimStatus(it, capturedAtMillis)) } ?: match, event, repeats)
    }
}

private fun claimStatus(event: ReminderEvent, capturedAtMillis: Long): OccurrenceStatus = when {
    event.completedAt != null -> OccurrenceStatus.COMPLETED
    event.cancelledAt != null -> OccurrenceStatus.MISSED
    (event.latestSnoozeFireAt ?: event.originalFireMillis) > capturedAtMillis -> OccurrenceStatus.PENDING
    else -> OccurrenceStatus.OVERDUE
}

/** A non-blank note title from a parked transcript: its first non-blank line, collapsed and capped. */
fun deriveVoiceCompletionNoteTitle(transcript: String, maxLen: Int = 60): String {
    val firstLine = transcript.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    val collapsed = firstLine.replace(Regex("\\s+"), " ").trim()
    if (collapsed.isEmpty()) return "Voice note"
    return if (collapsed.length <= maxLen) collapsed else collapsed.take(maxLen).trim()
}

/**
 * The list to show after a park: the store's [onDisk] list when it has [justParked], else the
 * [current] list with [justParked] appended, so a degraded read never blanks the list.
 */
fun mergeParkedForDisplay(
    onDisk: List<VoiceCompletion>,
    current: List<VoiceCompletion>,
    justParked: VoiceCompletion,
): List<VoiceCompletion> {
    val base = onDisk.ifEmpty { current }
    return if (base.any { it.completionId == justParked.completionId }) base else base + justParked
}
