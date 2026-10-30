package com.anopticlabs.gravel.notebook.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.alarms.ReminderAlarms
import com.anopticlabs.gravel.notebook.pipeline.CapturePublisher
import com.anopticlabs.gravel.notebook.reminders.CalendarOccurrence
import com.anopticlabs.gravel.notebook.reminders.CalendarRowKind
import com.anopticlabs.gravel.notebook.reminders.CompletionCandidate
import com.anopticlabs.gravel.notebook.reminders.MAX_REMINDER_MESSAGE_CHARS
import com.anopticlabs.gravel.notebook.reminders.OccurrenceStatus
import com.anopticlabs.gravel.notebook.reminders.PhoneCalendar
import com.anopticlabs.gravel.notebook.reminders.PhoneCalendarChoice
import com.anopticlabs.gravel.notebook.reminders.PhoneEvent
import com.anopticlabs.gravel.notebook.reminders.RecurrenceFrequency
import com.anopticlabs.gravel.notebook.reminders.ReminderEditPlan
import com.anopticlabs.gravel.notebook.reminders.ReminderEditProblem
import com.anopticlabs.gravel.notebook.reminders.ReminderEvent
import com.anopticlabs.gravel.notebook.reminders.ReminderEventKey
import com.anopticlabs.gravel.notebook.reminders.ReminderItem
import com.anopticlabs.gravel.notebook.reminders.ReminderListing
import com.anopticlabs.gravel.notebook.reminders.ReminderRefreshSignal
import com.anopticlabs.gravel.notebook.reminders.ReminderSettings
import com.anopticlabs.gravel.notebook.reminders.VoiceCompletion
import com.anopticlabs.gravel.notebook.reminders.VoiceCompletionAction
import com.anopticlabs.gravel.notebook.reminders.VoiceCompletionWriteTarget
import com.anopticlabs.gravel.notebook.reminders.buildCalendarOccurrences
import com.anopticlabs.gravel.notebook.reminders.calendarGridDays
import com.anopticlabs.gravel.notebook.reminders.calendarMonths
import com.anopticlabs.gravel.notebook.reminders.completionCandidates
import com.anopticlabs.gravel.notebook.reminders.cutToReminderMessage
import com.anopticlabs.gravel.notebook.reminders.deriveVoiceCompletionNoteTitle
import com.anopticlabs.gravel.notebook.reminders.firstOfMonth
import com.anopticlabs.gravel.notebook.reminders.isResolved
import com.anopticlabs.gravel.notebook.reminders.laterRepeatsUntil
import com.anopticlabs.gravel.notebook.reminders.phoneEventsByDay
import com.anopticlabs.gravel.notebook.reminders.phoneQueryWindow
import com.anopticlabs.gravel.notebook.reminders.planReminderEdit
import com.anopticlabs.gravel.notebook.reminders.reminderListing
import com.anopticlabs.gravel.notebook.reminders.retentionCutoffMillis
import com.anopticlabs.gravel.notebook.reminders.statusIn
import com.anopticlabs.gravel.notebook.reminders.voiceCompletionWriteTarget
import com.anopticlabs.gravel.notebook.reminders.withPhoneCalendarMonths
import com.anopticlabs.gravel.notebook.routing.UNCLASSIFIED_FOLDER
import com.anopticlabs.gravel.notebook.search.BodyTokenizer
import com.anopticlabs.gravel.notebook.sources.TypedNoteResult
import com.anopticlabs.gravel.notebook.sources.TypedNotes
import com.anopticlabs.gravel.notebook.storage.ReminderEventStore
import com.anopticlabs.gravel.notebook.storage.ReminderStore
import com.anopticlabs.gravel.notebook.storage.VoiceCompletionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.anopticlabs.gravel.notebook.alarms.Resolution
import com.anopticlabs.gravel.notebook.storage.ReminderIdsExhaustedException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Instant
import kotlin.coroutines.CoroutineContext

/** What the Reminders section lists, read together. */
data class RemindersData(val items: List<ReminderItem>, val events: List<ReminderEvent>)

/** What the editor is for: a new reminder, an edit of [Edit.item], or a reminder from [Claim.claim]. */
sealed interface ReminderFormTarget {
    data object New : ReminderFormTarget

    data class Edit(val item: ReminderItem) : ReminderFormTarget

    data class Claim(val claim: VoiceCompletion) : ReminderFormTarget
}

/** The editor's fields, held in memory only. */
class ReminderForm(val target: ReminderFormTarget, message: String, atMillis: Long, frequency: RecurrenceFrequency?, interval: Int) {
    private val opened = listOf(message, atMillis, frequency, interval.takeIf { frequency != null })
    var message by mutableStateOf(message)
        private set
    var atMillis by mutableStateOf(atMillis)
    var repeats by mutableStateOf(frequency != null)
    var frequency by mutableStateOf(frequency ?: RecurrenceFrequency.WEEKLY)
    var intervalText by mutableStateOf(interval.toString())

    val interval: Int get() = intervalText.toIntOrNull() ?: 0

    /** Whether saving would change what the editor opened with; the repeat fields count only while Repeat is on. */
    val changed: Boolean get() = listOf(message, atMillis, frequency.takeIf { repeats }, interval.takeIf { repeats }) != opened

    /** Takes [value] when it is within [MAX_REMINDER_MESSAGE_CHARS]; false, with the message kept, otherwise. */
    fun offerMessage(value: String): Boolean {
        if (value.length > MAX_REMINDER_MESSAGE_CHARS) return false
        message = value
        return true
    }
}

enum class ReminderFormError { MESSAGE_MISSING, MESSAGE_TOO_LONG, TIME_PAST, INTERVAL_INVALID, OUT_OF_RANGE, GONE, CLAIM_GONE, NOT_SAVED, IDS_EXHAUSTED }

sealed interface ReminderFormOutcome {
    data object Saved : ReminderFormOutcome

    data class Refused(val error: ReminderFormError) : ReminderFormOutcome
}

/** A row or review action waiting for the user to confirm it (every one does). */
sealed interface ReminderConfirm {
    /** The parked claim this action resolves, taken once its write lands; null for a row action. */
    val resolves: VoiceCompletion? get() = null

    data class Complete(
        val key: ReminderEventKey,
        val message: String,
        val repeats: Boolean,
        override val resolves: VoiceCompletion? = null,
    ) : ReminderConfirm

    /** Marks the occurrence missed (the cancelledAt stamp); [scope] names what else the write moves. */
    data class NotDone(
        val key: ReminderEventKey,
        val message: String,
        val scope: OccurrenceScope,
        override val resolves: VoiceCompletion? = null,
    ) : ReminderConfirm

    data class DeleteReminder(val item: ReminderItem) : ReminderConfirm

    /** Deletes one occurrence for good; [scope] names what else the write moves. */
    data class DeleteOccurrence(val key: ReminderEventKey, val message: String, val scope: OccurrenceScope) : ReminderConfirm

    /** The calendar's switch of an occurrence at rest to completed, or to missed when not [completed]. */
    data class SwitchStatus(val key: ReminderEventKey, val message: String, val completed: Boolean) : ReminderConfirm

    data class ClaimAsNote(val claim: VoiceCompletion) : ReminderConfirm

    /** Takes a claim whose reminder is already marked complete. */
    data class ClaimAlreadyDone(val claim: VoiceCompletion) : ReminderConfirm

    data class DisregardClaim(val claim: VoiceCompletion) : ReminderConfirm
}

/** A parked claim with the reminders it may complete, best match first. */
data class ClaimReview(val claim: VoiceCompletion, val candidates: List<CompletionCandidate>)

/** The confirmation [action] on [candidate] asks for, resolving [claim]; null where [voiceCompletionWriteTarget] offers no write. */
internal fun claimConfirm(claim: VoiceCompletion, candidate: CompletionCandidate, action: VoiceCompletionAction): ReminderConfirm? {
    val match = candidate.match
    return when (voiceCompletionWriteTarget(match.status, action)) {
        null -> null
        VoiceCompletionWriteTarget.UpcomingComplete, VoiceCompletionWriteTarget.OverdueComplete ->
            ReminderConfirm.Complete(candidate.key, match.message, candidate.repeats, claim)
        VoiceCompletionWriteTarget.UpcomingNotDone, VoiceCompletionWriteTarget.OverdueMissed ->
            ReminderConfirm.NotDone(candidate.key, match.message, occurrenceScope(candidate.event != null, candidate.repeats), claim)
    }
}

/** A logged occurrence moves nothing else; a stored reminder's next time removes a one-time reminder or moves a repeating one on. */
enum class OccurrenceScope { OCCURRENCE, ONE_TIME, REPEATING }

internal fun occurrenceScope(logged: Boolean, repeats: Boolean): OccurrenceScope = when {
    logged -> OccurrenceScope.OCCURRENCE
    repeats -> OccurrenceScope.REPEATING
    else -> OccurrenceScope.ONE_TIME
}

enum class RemindersView { LIST, CALENDAR }

/**
 * The calendar as last worked out: [month] (its first day) within the paging bounds [months], the
 * [day] listed under the grid, always in [month], and the rows of every day its grid can show
 * ([calendarGridDays]), with the phone calendar's events on those days once read for [month].
 */
data class CalendarPage(
    val month: LocalDate,
    val day: LocalDate,
    val today: LocalDate,
    val months: ClosedRange<LocalDate>,
    val rows: Map<LocalDate, List<CalendarOccurrence>>,
    val phoneEvents: Map<LocalDate, List<PhoneEvent>>,
    val phoneProblem: PhoneProblem?,
)

/** Why the phone calendar, turned on in Settings, is missing from the Calendar section. */
enum class PhoneProblem { NONE_PICKED, NO_ACCESS, NOT_READ }

/** The phone calendar as read for the grid of [month]. */
data class PhoneRead(val month: LocalDate, val days: Map<LocalDate, List<PhoneEvent>>, val problem: PhoneProblem?)

/** A button of a calendar row's details. */
enum class CalendarAction { COMPLETE, COMPLETE_EARLY, MARK_MISSED, MARK_MISSED_AHEAD, TO_COMPLETED, TO_MISSED, DELETE, DELETE_THIS_ONE, EDIT }

/**
 * What a row offers: a completed or missed logged time switches or goes, a pending one is
 * completed, marked missed or goes, a reminder's next time also edits the reminder, and a later
 * time only edits it.
 */
internal fun calendarActions(row: CalendarOccurrence, nowMillis: Long): List<CalendarAction> {
    val early = row.occurrenceMillis > nowMillis
    return when (row.kind) {
        CalendarRowKind.LOGGED -> when (row.status) {
            OccurrenceStatus.COMPLETED -> listOf(CalendarAction.TO_MISSED, CalendarAction.DELETE)
            OccurrenceStatus.MISSED -> listOf(CalendarAction.TO_COMPLETED, CalendarAction.DELETE)
            OccurrenceStatus.PENDING, OccurrenceStatus.OVERDUE -> listOf(CalendarAction.COMPLETE, CalendarAction.MARK_MISSED, CalendarAction.DELETE)
        }
        CalendarRowKind.NEXT -> listOf(
            if (early) CalendarAction.COMPLETE_EARLY else CalendarAction.COMPLETE,
            if (early) CalendarAction.MARK_MISSED_AHEAD else CalendarAction.MARK_MISSED,
            CalendarAction.DELETE_THIS_ONE,
            CalendarAction.EDIT,
        )
        CalendarRowKind.LATER -> listOf(CalendarAction.EDIT)
    }
}

/** The confirmation [action] on [row] asks for; null for [CalendarAction.EDIT], which opens the editor. */
internal fun calendarConfirm(row: CalendarOccurrence, action: CalendarAction): ReminderConfirm? {
    val scope = occurrenceScope(logged = !row.onStored, repeats = row.repeats)
    return when (action) {
        CalendarAction.COMPLETE, CalendarAction.COMPLETE_EARLY -> ReminderConfirm.Complete(row.key, row.message, row.onStored && row.repeats)
        CalendarAction.MARK_MISSED, CalendarAction.MARK_MISSED_AHEAD -> ReminderConfirm.NotDone(row.key, row.message, scope)
        CalendarAction.TO_COMPLETED -> ReminderConfirm.SwitchStatus(row.key, row.message, completed = true)
        CalendarAction.TO_MISSED -> ReminderConfirm.SwitchStatus(row.key, row.message, completed = false)
        CalendarAction.DELETE, CalendarAction.DELETE_THIS_ONE -> ReminderConfirm.DeleteOccurrence(row.key, row.message, scope)
        CalendarAction.EDIT -> null
    }
}

/** A calendar row by identity, looked up again in each new page so its details stay current. */
data class CalendarRowRef(val key: ReminderEventKey, val kind: CalendarRowKind)

/**
 * An occurrence the snooze picker is open for, with its text as the event log holds it;
 * [nextMillis] is its repeating reminder's next time, which a snooze reaching it does not outlast.
 */
data class SnoozeTarget(val key: ReminderEventKey, val message: String, val nextMillis: Long? = null)

internal fun snoozePassesNextTime(target: SnoozeTarget, minutes: Int, nowMillis: Long): Boolean =
    target.nextMillis != null && nowMillis + minutes * 60_000L >= target.nextMillis

sealed interface RemindersMessage {
    data object Saved : RemindersMessage

    data object Done : RemindersMessage

    /** The reminder moved on, but the occurrence could not be recorded in its history. */
    data object DoneNotRecorded : RemindersMessage

    /** The reminder moved past a deleted time, but the time could not be taken out of its history. */
    data object DeletedNotRecorded : RemindersMessage

    data object NotChanged : RemindersMessage

    data object NotRead : RemindersMessage

    data object Gone : RemindersMessage

    data object SavedAsNote : RemindersMessage

    /** A claim's write landed but the claim could not be removed, so it is still up for review. */
    data object DoneClaimKept : RemindersMessage

    data object DoneNotRecordedClaimKept : RemindersMessage

    data object SavedClaimKept : RemindersMessage

    data object SavedAsNoteClaimKept : RemindersMessage

    data object ClaimRemoved : RemindersMessage

    data object ClaimGone : RemindersMessage

    data object CannotSnooze : RemindersMessage

    data object NotSnoozed : RemindersMessage

    data class Snoozed(val minutes: Int) : RemindersMessage
}

data class RemindersState(
    val data: Loadable<RemindersData> = Loadable.Loading,
    val listing: ReminderListing? = null,
    val now: Long = 0L,
    val form: ReminderForm? = null,
    val confirm: ReminderConfirm? = null,
    val snooze: SnoozeTarget? = null,
    /** The completion review's parked claims as last read; null with the review closed. */
    val claims: Loadable<List<VoiceCompletion>>? = null,
    /** The read claims with their candidates, worked out with each read. */
    val review: List<ClaimReview> = emptyList(),
    /** Claims whose write landed but which could not be taken; they offer only Disregard. */
    val settled: Set<String> = emptySet(),
    val busy: Boolean = false,
    /** Messages not shown yet, oldest first; they wait while the section is not on screen. */
    val messages: List<RemindersMessage> = emptyList(),
    val view: RemindersView = RemindersView.LIST,
    /** The first day of the month the calendar shows; null for the current one. */
    val month: LocalDate? = null,
    /** The day listed under the grid; null, or a day outside the shown month, for today there or else the 1st. */
    val day: LocalDate? = null,
    /** Worked out only while the calendar is shown. */
    val calendar: CalendarPage? = null,
    val info: CalendarRowRef? = null,
    /** The last read of the phone calendar; a page shows it only for the month it was read for. */
    val phone: PhoneRead? = null,
) {
    /** The row whose details are open, as the current page has it; null when it left the page. */
    val infoRow: CalendarOccurrence?
        get() = info?.let { ref -> calendar?.rows?.values?.firstNotNullOfOrNull { day -> day.firstOrNull { it.key == ref.key && it.kind == ref.kind } } }
}

/**
 * The Reminders and Calendar sections. The stores are read strictly while either is started: at start,
 * on each refresh tick and on retry, the latest read winning, the parked claims too while the
 * completion review is open, whose candidates are worked out with each read; the listing, and the
 * calendar while it is shown, are worked out again each minute from what was read, so overdue rows
 * lapse on time. The phone calendar, while turned on, is read for each month the calendar shows. Actions run one
 * at a time in the ViewModel's own scope, and each reports its outcome in a message.
 */
class RemindersViewModel(
    private val reminders: ReminderStore,
    private val events: ReminderEventStore,
    private val alarms: ReminderAlarms,
    private val typedNotes: TypedNotes,
    private val publisher: CapturePublisher,
    private val completions: VoiceCompletionStore,
    private val navigator: NotebookNavigator,
    private val refresh: ReminderRefreshSignal,
    private val settings: ReminderSettings,
    private val phoneCalendar: PhoneCalendar,
    private val phoneChoice: () -> PhoneCalendarChoice,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> TimeZone = TimeZone::currentSystemDefault,
    private val io: CoroutineContext = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(RemindersState())
    val state: StateFlow<RemindersState> = _state.asStateFlow()

    private val retries = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val acting = AtomicBoolean(false)
    private val saving = Mutex()

    override fun onCleared() {
        scope.cancel()
    }

    /** Reads while the section is started and takes the notification links meant for it; see the class comment. */
    suspend fun watch() = coroutineScope {
        launch {
            // The review opened after an editor, a confirmation or the snooze picker would cover it, so its link waits for them.
            val free = _state.map { it.form == null && it.confirm == null && it.snooze == null }.distinctUntilChanged()
            // The missed notice's link waits for the Calendar section, so the tab's ViewModel has seen it before it is consumed.
            val shown = _state.map { it.view }.distinctUntilChanged()
            combine(navigator.pending, free, shown) { link, idle, view ->
                link?.takeIf { (idle || it != NotebookLink.Completions) && (it !is NotebookLink.Calendar || view == RemindersView.CALENDAR) }
            }
                .distinctUntilChanged()
                .filterNotNull()
                .collect { link ->
                    open(link)
                    navigator.consume(link)
                }
        }
        launch {
            // Read for each month the calendar comes to show, in each zone, and again on retry and each time the section starts.
            val shown = _state.map { state -> state.calendar?.month?.let { it to zone() } }.distinctUntilChanged().filterNotNull()
            merge(shown.map { it.first }, retries.mapNotNull { _state.value.calendar?.month }).collectLatest { readPhone(it) }
        }
        launch {
            while (true) {
                delay(MINUTE - clock().mod(MINUTE))
                _state.update { it.withListing(clock()) }
            }
        }
        // onSubscription: a write landing between the subscription and the first read is not missed.
        merge(refresh.events.onSubscription { emit(Unit) }, retries).collectLatest { load() }
    }

    fun retry() {
        retries.tryEmit(Unit)
    }

    fun messageShown(message: RemindersMessage) = _state.update {
        if (it.messages.firstOrNull() == message) it.copy(messages = it.messages.drop(1)) else it
    }

    private fun post(message: RemindersMessage) = _state.update { it.copy(messages = it.messages + message) }

    private suspend fun load() {
        val reviewing = _state.value.claims != null
        val read = withContext(io) {
            val data = readData()
            val claims = if (reviewing) readClaims() else null
            ReviewRead(data, claims, reviewOf(data, claims))
        }
        // A review closed during the read stays closed; one opened during it gets its own read.
        _state.update {
            when {
                it.claims == null -> it.copy(data = read.data, review = emptyList())
                read.claims == null -> it.copy(data = read.data)
                else -> it.copy(data = read.data, claims = read.claims, review = read.review)
            }.withListing(clock())
        }
    }

    private class ReviewRead(val data: Loadable<RemindersData>, val claims: Loadable<List<VoiceCompletion>>?, val review: List<ClaimReview>)

    /** Each claim's candidates as of its capture, each message tokenized once. */
    private fun reviewOf(data: Loadable<RemindersData>, claims: Loadable<List<VoiceCompletion>>?): List<ClaimReview> {
        val ready = (data as? Loadable.Ready)?.value ?: return emptyList()
        val parked = (claims as? Loadable.Ready)?.value ?: return emptyList()
        val tokens = HashMap<String, Set<String>>()
        val tokenize = { message: String -> tokens.getOrPut(message) { BodyTokenizer.tokenizeQuery(message) } }
        return parked.map { ClaimReview(it, completionCandidates(it.transcript, ready.items, ready.events, it.capturedAt, zone(), tokenize)) }
    }

    private fun readData(): Loadable<RemindersData> = try {
        Loadable.Ready(
            RemindersData(
                reminders.readAll(strict = true),
                events.readAll(strict = true),
            ),
        )
    } catch (e: Exception) {
        logger.w { "Reminders read failed: ${e::class.simpleName}" }
        Loadable.Failed(storageProblemOf(e))
    }

    private suspend fun readClaims(): Loadable<List<VoiceCompletion>> = try {
        Loadable.Ready(publisher.readParked(reviewing = true))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.w { "Parked completions read failed: ${e::class.simpleName}" }
        Loadable.Failed(storageProblemOf(e))
    }

    private suspend fun readPhone(month: LocalDate) {
        val choice = phoneChoice()
        val read = when {
            choice.active -> withContext(io) { phoneRead(month, choice.calendars) }
            choice.shown -> PhoneRead(month, emptyMap(), PhoneProblem.NONE_PICKED)
            else -> PhoneRead(month, emptyMap(), null)
        }
        _state.update { it.copy(phone = read).withListing(clock()) }
    }

    private fun phoneRead(month: LocalDate, calendars: Set<String>): PhoneRead {
        val days = calendarGridDays(month)
        val zone = zone()
        return try {
            val events = phoneCalendar.events(calendars, phoneQueryWindow(days, zone)) ?: return PhoneRead(month, emptyMap(), PhoneProblem.NO_ACCESS)
            PhoneRead(month, phoneEventsByDay(events, days, zone), null)
        } catch (e: Exception) {
            logger.w { "Phone calendar read failed: ${e::class.simpleName}" }
            PhoneRead(month, emptyMap(), PhoneProblem.NOT_READ)
        }
    }

    private fun RemindersState.withListing(now: Long): RemindersState {
        val ready = (data as? Loadable.Ready)?.value ?: return copy(listing = null, calendar = null, now = now)
        val calendar = if (view == RemindersView.CALENDAR) calendarPage(ready, now) else null
        // A picked month is kept as shown, within the bounds, so bounds that widen later do not page on their own.
        val month = if (month != null && calendar != null) calendar.month else month
        return copy(listing = reminderListing(ready.items, ready.events, now, zone()), calendar = calendar, now = now, month = month)
    }

    private fun RemindersState.calendarPage(data: RemindersData, now: Long): CalendarPage {
        val zone = zone()
        val cutoff = retentionCutoffMillis(now, settings.historyRetentionDays)
        val laterUntil = laterRepeatsUntil(now, zone)
        val today = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).date
        val reminderMonths = calendarMonths(data.events, data.items, now, zone, cutoff, laterUntil)
        val months = if (phoneChoice().active) withPhoneCalendarMonths(reminderMonths, today) else reminderMonths
        val shown = (month ?: firstOfMonth(today)).coerceIn(months)
        val rows = buildCalendarOccurrences(data.events, data.items, now, zone, calendarGridDays(shown), cutoff, laterUntil)
        val day = listOfNotNull(this.day, today).firstOrNull { firstOfMonth(it) == shown } ?: shown
        val phone = phone?.takeIf { it.month == shown }
        // A day of a month outside the bounds cannot be listed, so its cell shows no phone events either.
        val phoneEvents = phone?.days.orEmpty().filterKeys { firstOfMonth(it) in months }
        return CalendarPage(shown, day, today, months, rows, phoneEvents, phone?.problem)
    }

    // Links

    /** A notification tap: the snooze button opens the picker, a set notice's tap its editor, the completions nudge the review. */
    fun open(link: NotebookLink) {
        when (link) {
            is NotebookLink.Occurrence -> if (link.snooze) openSnooze(link.key)
            is NotebookLink.Edit -> editReminder(link.reminderId)
            NotebookLink.Completions -> openCompletions()
            NotebookLink.Reminders, NotebookLink.BackupSettings, NotebookLink.KeyCheck -> Unit
            is NotebookLink.Calendar -> {
                val day = Instant.fromEpochMilliseconds(link.atMillis).toLocalDateTime(zone()).date
                _state.update { it.copy(month = firstOfMonth(day), day = day, info = null).withListing(clock()) }
            }
        }
    }

    // Calendar

    fun showView(view: RemindersView) = _state.update { it.copy(view = view, info = null).withListing(clock()) }

    /** The Calendar section as its tab opens it: today's month, today listed. */
    fun showCalendarOnToday() = _state.update {
        it.copy(view = RemindersView.CALENDAR, month = null, day = null, info = null).withListing(clock())
    }

    /** Pages to [month] (its first day; the page keeps it within its bounds), listing today when today is in that month, else the 1st. */
    fun showMonth(month: LocalDate) = _state.update { it.copy(month = month, day = null).withListing(clock()) }

    /** Lists [day] under the grid; a day of another month pages there, and one of a month outside the bounds is ignored. */
    fun selectDay(day: LocalDate) = _state.update { state ->
        if (state.calendar?.months?.contains(firstOfMonth(day)) == false) state else state.copy(day = day, month = firstOfMonth(day)).withListing(clock())
    }

    fun openInfo(row: CalendarOccurrence) = _state.update { it.copy(info = CalendarRowRef(row.key, row.kind)) }

    fun closeInfo() = _state.update { it.copy(info = null) }

    /** The editor for a new reminder on [day]: 09:00 on a day after today, an hour from now otherwise. */
    fun newReminderOn(day: LocalDate) {
        val now = clock()
        val today = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone()).date
        val at = if (day > today) day.atTime(9, 0).toInstant(zone()).toEpochMilliseconds() else wholeMinute(now + HOUR)
        openForm(ReminderForm(ReminderFormTarget.New, "", at, null, 1))
    }

    // Completion review

    /** Opens the completion review; the parked claims are read with the next read. */
    fun openCompletions() {
        _state.update { if (it.claims == null) it.copy(claims = Loadable.Loading) else it }
        retry()
    }

    fun closeCompletions() = _state.update { it.copy(claims = null, review = emptyList()) }

    /** Opens the editor on [claim]'s words an hour from now; saving it takes the claim. */
    fun newReminderFromClaim(claim: VoiceCompletion) =
        openForm(ReminderForm(ReminderFormTarget.Claim(claim), cutToReminderMessage(claim.transcript), wholeMinute(clock() + HOUR), null, 1))

    // Editor

    fun newReminder() = _state.update {
        if (it.form != null) it else it.copy(form = ReminderForm(ReminderFormTarget.New, "", wholeMinute(clock() + HOUR), null, 1))
    }

    /** Opens the editor on stored reminder [id], read strictly; a reminder no longer stored is reported. */
    fun editReminder(id: Int) = act(failed = RemindersMessage.NotRead) {
        val item = withContext(io) { reminders.readAll(strict = true) }.firstOrNull { it.id == id }
        if (item == null) {
            post(RemindersMessage.Gone)
            return@act
        }
        val rule = item.recurrence
        openForm(ReminderForm(ReminderFormTarget.Edit(item), item.message, item.remindAtMillis, rule?.frequency, rule?.interval ?: 1))
    }

    /** An editor already open keeps its place, so a link or a slow read never discards what is typed there. */
    private fun openForm(form: ReminderForm) = _state.update { if (it.form == null) it.copy(form = form) else it }

    fun closeForm() = _state.update { it.copy(form = null) }

    /**
     * Saves [form] in the ViewModel's scope, one save at a time and to its end even when the
     * ViewModel is cleared meanwhile, so the outcome lands even when the editor leaves. A reminder
     * is stored, then armed; a claim is read again before its reminder is stored and removed only
     * after, so a failed save keeps it listed and one already turned into a reminder is not turned
     * into a second one.
     */
    suspend fun save(form: ReminderForm): ReminderFormOutcome =
        scope.async { saving.withLock { withContext(NonCancellable) { saveNow(form) } } }.await()

    private suspend fun saveNow(form: ReminderForm): ReminderFormOutcome {
        val target = form.target
        val plan = planReminderEdit(
            form.message, form.atMillis, form.frequency.takeIf { form.repeats }, form.interval,
            (target as? ReminderFormTarget.Edit)?.item, clock(), zone(),
        )
        val save = when (plan) {
            is ReminderEditPlan.Refused -> return ReminderFormOutcome.Refused(plan.problem.toFormError())
            is ReminderEditPlan.Save -> plan
        }
        val stored = try {
            withContext(io) {
                when (target) {
                    // A save, changed or not, is the user checking the time.
                    is ReminderFormTarget.Edit -> alarms.edit(target.item.id) {
                        if (save.keepsTime) it.copy(message = save.message, timeToCheck = false)
                        else it.copy(remindAtMillis = save.remindAtMillis, message = save.message, recurrence = save.recurrence, timeToCheck = false)
                    }
                    ReminderFormTarget.New -> create(save)
                    is ReminderFormTarget.Claim ->
                        if (completions.get(target.claim.completionId, strict = true) == null) return@withContext null else create(save)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ReminderIdsExhaustedException) {
            logger.w { "Reminder not created: ids exhausted" }
            return ReminderFormOutcome.Refused(ReminderFormError.IDS_EXHAUSTED)
        } catch (e: Exception) {
            logger.w { "Reminder not saved: ${e::class.simpleName}" }
            return ReminderFormOutcome.Refused(ReminderFormError.NOT_SAVED)
        }
        if (stored == null) return ReminderFormOutcome.Refused(if (target is ReminderFormTarget.Claim) ReminderFormError.CLAIM_GONE else ReminderFormError.GONE)
        val message = when {
            target is ReminderFormTarget.Claim && !takeClaim(target.claim, settledIfKept = true) -> RemindersMessage.SavedClaimKept
            else -> RemindersMessage.Saved
        }
        refresh.emit()
        _state.update { if (it.form === form) it.copy(form = null) else it }
        post(message)
        return ReminderFormOutcome.Saved
    }

    private suspend fun create(save: ReminderEditPlan.Save): ReminderItem =
        reminders.create { id -> ReminderItem(id, save.remindAtMillis, save.message, save.recurrence) }.also { item ->
            bestEffort("alarm") { alarms.arm(item) }
        }

    // Row actions

    /** "Keep this time": clears reminder [id]'s mark, its time unchanged. */
    fun keepTime(id: Int) = act {
        val kept = withContext(io) { alarms.edit(id) { it.copy(timeToCheck = false) } }
        refresh.emit()
        if (kept == null) post(RemindersMessage.Gone)
    }

    fun ask(confirm: ReminderConfirm) = _state.update { it.copy(confirm = confirm) }

    fun dismissConfirm() = _state.update { it.copy(confirm = null) }

    /**
     * Runs the confirmed action, to its end even when the ViewModel is cleared meanwhile; one that
     * did not land is reported. An action on a claim first reads the claim again and does nothing
     * when it is gone.
     */
    fun confirm(confirm: ReminderConfirm) = act { withContext(NonCancellable) { confirmNow(confirm) } }

    private suspend fun confirmNow(confirm: ReminderConfirm) {
        _state.update { if (it.confirm == confirm) it.copy(confirm = null, info = null) else it }
        val claimed = confirm.resolves ?: (confirm as? ReminderConfirm.ClaimAsNote)?.claim
        if (claimed != null && withContext(io) { completions.get(claimed.completionId, strict = true) } == null) {
            post(RemindersMessage.ClaimGone)
            return
        }
        fun done(landed: Boolean) = if (landed) RemindersMessage.Done else RemindersMessage.NotChanged
        val message = when (confirm) {
            // A spoken completion counts from when it was said.
            is ReminderConfirm.Complete -> outcome(alarms.complete(confirm.key, confirm.message, confirm.resolves?.capturedAt))
            is ReminderConfirm.NotDone -> outcome(alarms.cancel(confirm.key, confirm.message))
            is ReminderConfirm.DeleteReminder -> done(alarms.delete(confirm.item.id))
            is ReminderConfirm.DeleteOccurrence -> when (alarms.deleteOccurrence(confirm.key)) {
                Resolution.RECORDED -> RemindersMessage.Done
                Resolution.MOVED_ONLY -> RemindersMessage.DeletedNotRecorded
                Resolution.UNCHANGED -> RemindersMessage.NotChanged
            }
            is ReminderConfirm.SwitchStatus -> done(alarms.switchStatus(confirm.key, confirm.completed))
            is ReminderConfirm.ClaimAsNote -> claimAsNote(confirm.claim)
            is ReminderConfirm.ClaimAlreadyDone -> if (takeClaim(confirm.claim)) RemindersMessage.ClaimRemoved else RemindersMessage.NotChanged
            is ReminderConfirm.DisregardClaim -> if (takeClaim(confirm.claim)) RemindersMessage.ClaimRemoved else RemindersMessage.NotChanged
        }
        // The claim goes once the write lands, a reminder moved on without its history record included.
        val claim = confirm.resolves
        val landed = message == RemindersMessage.Done || message == RemindersMessage.DoneNotRecorded
        post(
            when {
                claim == null || !landed || takeClaim(claim, settledIfKept = true) -> message
                message == RemindersMessage.DoneNotRecorded -> RemindersMessage.DoneNotRecordedClaimKept
                else -> RemindersMessage.DoneClaimKept
            },
        )
    }

    private fun outcome(resolution: Resolution) = when (resolution) {
        Resolution.RECORDED -> RemindersMessage.Done
        Resolution.MOVED_ONLY -> RemindersMessage.DoneNotRecorded
        Resolution.UNCHANGED -> RemindersMessage.NotChanged
    }

    /** Saves [claim]'s words as a note in the unclassified folder, then takes the claim. */
    private suspend fun claimAsNote(claim: VoiceCompletion): RemindersMessage =
        when (typedNotes.save(deriveVoiceCompletionNoteTitle(claim.transcript), claim.transcript, UNCLASSIFIED_FOLDER)) {
            is TypedNoteResult.Saved -> if (takeClaim(claim, settledIfKept = true)) RemindersMessage.SavedAsNote else RemindersMessage.SavedAsNoteClaimKept
            TypedNoteResult.TitleMissing, TypedNoteResult.TooLarge -> RemindersMessage.NotChanged
        }

    /**
     * Removes [claim] from the parked claims (already gone counts as removed); false when the store
     * refused, which marks the claim settled when [settledIfKept] (its write has landed).
     */
    private suspend fun takeClaim(claim: VoiceCompletion, settledIfKept: Boolean = false): Boolean {
        val taken = try {
            withContext(io) { completions.take(claim.completionId) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w { "Parked completion not removed: ${e::class.simpleName}" }
            false
        }
        if (!taken && settledIfKept) _state.update { it.copy(settled = it.settled + claim.completionId) }
        refresh.emit()
        return taken
    }

    // Snooze

    /** Opens the picker for [key] when the log holds it unresolved and not MISSED; otherwise says it cannot be snoozed. */
    fun openSnooze(key: ReminderEventKey) = act(failed = RemindersMessage.NotRead) {
        val all = withContext(io) { events.readAll(strict = true) }
        val event = all.firstOrNull { it.key == key }
        if (event == null || event.isResolved || statusIn(all, event, clock(), zone()) == OccurrenceStatus.MISSED) {
            post(RemindersMessage.CannotSnooze)
        } else {
            val next = withContext(io) { reminders.readAll() }
                .firstOrNull { it.id == key.reminderId && it.recurrence != null }?.remindAtMillis?.takeIf { it > key.originalFireMillis }
            _state.update { it.copy(snooze = SnoozeTarget(key, event.message, next)) }
        }
    }

    fun dismissSnooze() = _state.update { it.copy(snooze = null) }

    /** Snoozes [target], to its end even when the ViewModel is cleared meanwhile: the snooze is recorded before its alarm is set. */
    fun snooze(target: SnoozeTarget, minutes: Int) = act {
        withContext(NonCancellable) {
            _state.update { if (it.snooze == target) it.copy(snooze = null) else it }
            val done = minutes in 1..MAX_SNOOZE_MINUTES && alarms.snoozeConfirmed(target.key, target.message, minutes * MINUTE)
            post(if (done) RemindersMessage.Snoozed(minutes) else RemindersMessage.NotSnoozed)
        }
    }

    /** Runs [block] unless another action is running; a failure it lets out is reported as [failed]. */
    private fun act(failed: RemindersMessage = RemindersMessage.NotChanged, block: suspend () -> Unit): Job {
        if (!acting.compareAndSet(false, true)) return CompletableDeferred(Unit)
        _state.update { it.copy(busy = true) }
        return scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w { "Reminders action failed: ${e::class.simpleName}" }
                post(failed)
            } finally {
                _state.update { it.copy(busy = false) }
                acting.set(false)
            }
        }
    }

    private inline fun bestEffort(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w { "$what failed after a reminder write: ${e::class.simpleName}" }
        }
    }

    companion object {
        const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE

        /** The custom field's four digits. */
        const val MAX_SNOOZE_MINUTES = 9999

        private val logger = Logger.withTag("RemindersViewModel")

        fun wholeMinute(millis: Long): Long = millis - millis.mod(MINUTE)

        private fun ReminderEditProblem.toFormError(): ReminderFormError = when (this) {
            ReminderEditProblem.MESSAGE_MISSING -> ReminderFormError.MESSAGE_MISSING
            ReminderEditProblem.MESSAGE_TOO_LONG -> ReminderFormError.MESSAGE_TOO_LONG
            ReminderEditProblem.TIME_PAST -> ReminderFormError.TIME_PAST
            ReminderEditProblem.INTERVAL_INVALID -> ReminderFormError.INTERVAL_INVALID
            ReminderEditProblem.OUT_OF_RANGE -> ReminderFormError.OUT_OF_RANGE
        }
    }
}
