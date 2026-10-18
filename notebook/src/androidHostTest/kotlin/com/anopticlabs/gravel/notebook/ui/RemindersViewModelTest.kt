package com.anopticlabs.gravel.notebook.ui

import com.anopticlabs.gravel.notebook.alarms.FakeNotifier
import com.anopticlabs.gravel.notebook.alarms.FakeScheduler
import com.anopticlabs.gravel.notebook.alarms.FakeSettings
import com.anopticlabs.gravel.notebook.alarms.ReminderAlarms
import com.anopticlabs.gravel.notebook.pipeline.CapturePublisher
import com.anopticlabs.gravel.notebook.reminders.CalendarOccurrence
import com.anopticlabs.gravel.notebook.reminders.CalendarRowKind
import com.anopticlabs.gravel.notebook.reminders.CompletionCandidate
import com.anopticlabs.gravel.notebook.reminders.MAX_REMINDER_MESSAGE_CHARS
import com.anopticlabs.gravel.notebook.reminders.OccurrenceStatus
import com.anopticlabs.gravel.notebook.reminders.PhoneCalendar
import com.anopticlabs.gravel.notebook.reminders.PhoneCalendarChoice
import com.anopticlabs.gravel.notebook.reminders.PhoneCalendarInfo
import com.anopticlabs.gravel.notebook.reminders.PhoneEvent
import com.anopticlabs.gravel.notebook.reminders.RecurrenceFrequency
import com.anopticlabs.gravel.notebook.reminders.RecurrenceRule
import com.anopticlabs.gravel.notebook.reminders.ReminderEvent
import com.anopticlabs.gravel.notebook.reminders.ReminderEventKey
import com.anopticlabs.gravel.notebook.reminders.ReminderItem
import com.anopticlabs.gravel.notebook.reminders.ReminderMatch
import com.anopticlabs.gravel.notebook.reminders.ReminderRefreshSignal
import com.anopticlabs.gravel.notebook.reminders.VoiceCompletion
import com.anopticlabs.gravel.notebook.reminders.VoiceCompletionAction
import com.anopticlabs.gravel.notebook.reminders.calendarGridDays
import com.anopticlabs.gravel.notebook.reminders.completionCandidates
import com.anopticlabs.gravel.notebook.reminders.phoneQueryWindow
import com.anopticlabs.gravel.notebook.routing.UNCLASSIFIED_FOLDER
import com.anopticlabs.gravel.notebook.sources.TypedNotes
import com.anopticlabs.gravel.notebook.storage.FakeCryptographer
import com.anopticlabs.gravel.notebook.storage.NoteStore
import com.anopticlabs.gravel.notebook.storage.ReminderEventStore
import com.anopticlabs.gravel.notebook.storage.ReminderFile
import com.anopticlabs.gravel.notebook.storage.ReminderStore
import com.anopticlabs.gravel.notebook.storage.TransientCryptoException
import com.anopticlabs.gravel.notebook.storage.VoiceCompletionStore
import com.anopticlabs.gravel.notebook.storage.softwareCrypto
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RemindersViewModelTest {

    private val root = Files.createTempDirectory("notebook").toFile()
    private val crypto = FakeCryptographer(softwareCrypto())
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    // On a whole minute, so the minute tick waits a full minute unless a test moves the clock.
    @Volatile private var now = 1_790_089_200_000L
    private val reminders = ReminderStore(root, crypto)
    private val events = ReminderEventStore(root, crypto)
    private val notes = NoteStore(root, crypto)
    private val scheduler = FakeScheduler()
    private val notifier = FakeNotifier()
    private val refresh = ReminderRefreshSignal()
    private val settings = FakeSettings()
    private val alarms = ReminderAlarms(reminders, events, scheduler, notifier, settings, refresh, { now }, { TimeZone.UTC })
    private val navigator = NotebookNavigator()
    private val completions = VoiceCompletionStore(root, crypto)
    private val publisher = CapturePublisher(notes, reminders, completions, alarms, notifier, refresh, { 30 }, { false }, { now })
    private val phone = FakePhoneCalendar()
    @Volatile private var phoneChoice = PhoneCalendarChoice(shown = false, calendars = emptySet())
    @Volatile private var zone: TimeZone = TimeZone.UTC
    private fun newViewModel() = RemindersViewModel(
        reminders, events, alarms, TypedNotes(notes, { now }, { TimeZone.UTC }), publisher, completions,
        navigator, refresh, settings, phoneCalendar = phone, phoneChoice = { phoneChoice },
        clock = { now }, zone = { zone },
    )
    private val viewModel = newViewModel()
    private val state get() = viewModel.state.value

    @AfterTest
    fun cleanUp() {
        watchThread.close()
        root.deleteRecursively()
    }

    // One thread, as on the main dispatcher the section collects on.
    private val watchThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private fun watching(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        val watch = launch(watchThread) { viewModel.watch() }
        try {
            block()
        } finally {
            watch.cancelAndJoin()
        }
    }

    private suspend fun until(check: (RemindersState) -> Boolean): RemindersState {
        var seen = viewModel.state.value
        withTimeout(5.seconds) {
            while (!check(seen)) {
                delay(10)
                seen = viewModel.state.value
            }
        }
        return seen
    }

    /**
     * The state once its data equals a fresh read of the reminders and the event log; a wait on the
     * listing or the calendar alone can see a read older than the last write.
     */
    private suspend fun synced(): RemindersState {
        refresh.emit()
        val stored: Loadable<RemindersData> = Loadable.Ready(
            RemindersData(
                reminders.readAll(strict = true),
                events.readAll(strict = true),
            ),
        )
        withTimeoutOrNull(5.seconds) { while (viewModel.state.value.data != stored) delay(10) }
        assertEquals(stored, state.data)
        return state
    }

    private suspend fun create(at: Long = now + day, message: String = "m", rule: RecurrenceRule? = null): ReminderItem =
        reminders.create { ReminderItem(it, at, message, rule) }

    private fun form(): ReminderForm = assertNotNull(state.form)

    @Test
    fun theListingGroupsWhatIsStoredAndFollowsLaterWrites() = runBlocking {
        val upcoming = create(at = now + day, message = "later")
        val due = create(at = now - hour, message = "due")
        alarms.recordFire(ReminderEventKey(due.id, due.remindAtMillis), "due")
        watching { listingFollowsWrites(upcoming, due) }
    }

    private suspend fun listingFollowsWrites(upcoming: ReminderItem, due: ReminderItem) {
        val first = until { it.listing != null }
        assertEquals(listOf(due.id), first.listing?.dueNow?.map { it.reminderId })
        assertEquals(listOf(upcoming), first.listing?.upcoming)
        val added = create(at = now + hour, message = "sooner")
        refresh.emit()
        assertEquals(listOf(added, upcoming), until { it.listing?.upcoming?.size == 2 }.listing?.upcoming)
    }

    @Test
    fun anOverdueRowLapsesWithoutAnotherRead() {
        // One millisecond before a whole minute, so the minute tick keeps running while the test moves the clock.
        now = LocalDateTime(2026, 9, 22, 20, 0).toInstant(TimeZone.UTC).toEpochMilliseconds() - 1
        runBlocking {
            val due = create(at = now - hour, message = "due")
            alarms.recordFire(ReminderEventKey(due.id, due.remindAtMillis), "due")
        }
        watching {
            until { it.listing?.dueNow?.size == 1 }
            var opens = 0
            crypto.beforeOpen = { opens++ }
            now += 5 * hour
            until { it.listing?.dueNow?.isEmpty() == true }
            assertEquals(0, opens)
        }
    }

    @Test
    fun aNewReminderIsStoredAndArmedAtAWholeMinuteAnHourAhead() = runBlocking<Unit> {
        now += 25_000L
        viewModel.newReminder()
        assertEquals(now - 25_000L + hour, form().atMillis)
        form().offerMessage("water the plants")
        assertEquals(ReminderFormOutcome.Saved, viewModel.save(form()))
        val stored = reminders.readAll(strict = true).single()
        assertEquals(ReminderItem(stored.id, now - 25_000L + hour, "water the plants"), stored)
        assertEquals(stored, scheduler.fires[stored.id])
        assertNull(state.form)
        assertEquals(listOf<RemindersMessage>(RemindersMessage.Saved), state.messages)
        assertTrue(notifier.setNotices.isEmpty())
    }

    @Test
    fun aOneShotInThePastIsRefusedAndNothingIsStored() = runBlocking<Unit> {
        viewModel.newReminder()
        form().offerMessage("too late")
        form().atMillis = now - minute
        assertEquals(ReminderFormOutcome.Refused(ReminderFormError.TIME_PAST), viewModel.save(form()))
        assertTrue(reminders.readAll(strict = true).isEmpty())
        assertNotNull(state.form)
    }

    @Test
    fun anEditOfTheTextKeepsTheRulesAnchorAndRearms() = runBlocking<Unit> {
        val jan31 = now - 60 * day
        val rule = RecurrenceRule(RecurrenceFrequency.MONTHLY, 1, jan31)
        val item = create(at = now + day, message = "rent", rule = rule)
        viewModel.editReminder(item.id).join()
        assertIs<ReminderFormTarget.Edit>(form().target)
        form().offerMessage("pay the rent")
        assertEquals(ReminderFormOutcome.Saved, viewModel.save(form()))
        val stored = reminders.readAll(strict = true).single()
        assertEquals(item.copy(message = "pay the rent"), stored)
        assertEquals(stored, scheduler.fires[item.id])
    }

    @Test
    fun savingTheEditorOnAReminderToCheckClearsItsMarkAndNotice() = runBlocking<Unit> {
        val item = reminders.create { ReminderItem(it, now + day, "call Sam", timeToCheck = true) }.also(notifier::postSetNotice)
        viewModel.editReminder(item.id).join()
        assertEquals(ReminderFormOutcome.Saved, viewModel.save(form()))
        assertEquals(item.copy(timeToCheck = false), reminders.readAll(strict = true).single())
        assertTrue(notifier.setNotices.isEmpty())
    }

    @Test
    fun savingTheEditorOnAVoiceReminderTakesDownItsSetNotice() = runBlocking<Unit> {
        val item = create().also(notifier::postSetNotice)
        viewModel.editReminder(item.id).join()
        form().atMillis = now + 2 * day
        assertEquals(ReminderFormOutcome.Saved, viewModel.save(form()))
        assertTrue(notifier.setNotices.isEmpty())
    }

    @Test
    fun savingANewTimeOnAReminderToCheckClearsItsMark() = runBlocking<Unit> {
        val item = reminders.create { ReminderItem(it, now + day, "call Sam", timeToCheck = true) }.also(notifier::postSetNotice)
        viewModel.editReminder(item.id).join()
        form().atMillis = now + 2 * day
        assertEquals(ReminderFormOutcome.Saved, viewModel.save(form()))
        assertEquals(item.copy(remindAtMillis = now + 2 * day, timeToCheck = false), reminders.readAll(strict = true).single())
        assertTrue(notifier.setNotices.isEmpty())
    }

    @Test
    fun aTimeCheckLinkOpensItsReminderInTheEditor() = watching {
        val item = reminders.create { ReminderItem(it, now + day, "call Sam", timeToCheck = true) }
        navigator.open(NotebookLink.Edit(item.id))
        assertEquals(ReminderFormTarget.Edit(item), until { it.form != null && !it.busy }.form?.target)
    }

    @Test
    fun keepThisTimeClearsTheMarkAndKeepsTheTime() = runBlocking<Unit> {
        val item = reminders.create { ReminderItem(it, now + day, "call Sam", timeToCheck = true) }.also(notifier::postSetNotice)
        viewModel.keepTime(item.id).join()
        assertEquals(item.copy(timeToCheck = false), reminders.readAll(strict = true).single())
        assertTrue(notifier.setNotices.isEmpty())
    }

    @Test
    fun anEditOfAReminderGoneMeanwhileIsRefusedAndNothingIsCreated() = runBlocking<Unit> {
        val item = create()
        viewModel.editReminder(item.id).join()
        reminders.remove(item.id)
        form().offerMessage("changed")
        assertEquals(ReminderFormOutcome.Refused(ReminderFormError.GONE), viewModel.save(form()))
        assertTrue(reminders.readAll(strict = true).isEmpty())
        viewModel.editReminder(item.id).join()
        assertEquals(RemindersMessage.Gone, state.messages.last())
    }

    @Test
    fun confirmedActionsGoThroughTheAlarmStackAndOneThatDoesNotLandIsReported() = runBlocking<Unit> {
        val once = create(message = "once")
        val key = ReminderEventKey(once.id, once.remindAtMillis)
        viewModel.ask(ReminderConfirm.Complete(key, "once", repeats = false))
        viewModel.confirm(ReminderConfirm.Complete(key, "once", repeats = false)).join()
        assertNull(state.confirm)
        assertTrue(reminders.readAll(strict = true).isEmpty())
        assertEquals(now, events.readAll(strict = true).single().completedAt)
        assertEquals(RemindersMessage.Done, state.messages.last())

        val kept = create(message = "kept")
        crypto.failSealWhenAadContains = ReminderStore.FILE_NAME
        viewModel.confirm(ReminderConfirm.DeleteReminder(kept)).join()
        crypto.failSealWhenAadContains = null
        assertEquals(listOf(kept), reminders.readAll(strict = true))
        assertEquals(RemindersMessage.NotChanged, state.messages.last())
    }

    @Test
    fun theSnoozePickerOpensOnlyOnAnOpenOccurrenceAndShowsItsLoggedText() = runBlocking<Unit> {
        val item = create(at = now - minute, message = "stretch")
        val key = ReminderEventKey(item.id, item.remindAtMillis)
        alarms.postFire(key, "stretch")
        alarms.recordFire(key, "stretch")
        viewModel.openSnooze(key).join()
        val target = SnoozeTarget(key, "stretch")
        assertEquals(target, state.snooze)
        viewModel.snooze(target, 10).join()
        assertNull(state.snooze)
        assertEquals(now + 10 * minute, scheduler.snoozes[key])
        assertEquals(RemindersMessage.Snoozed(10), state.messages.last())

        viewModel.openSnooze(ReminderEventKey(99, now)).join()
        assertNull(state.snooze)
        assertEquals(RemindersMessage.CannotSnooze, state.messages.last())
        events.markCompleted(key, now)
        viewModel.openSnooze(key).join()
        assertNull(state.snooze)
    }

    @Test
    fun theSnoozePickerKnowsARepeatingReminderNextTimeAndRefusesAMissedOccurrence() = runBlocking<Unit> {
        val item = create(at = now - minute, message = "meds", rule = RecurrenceRule(RecurrenceFrequency.DAILY, 1, now - minute))
        val key = ReminderEventKey(item.id, item.remindAtMillis)
        alarms.recordFire(key, "meds")
        viewModel.openSnooze(key).join()
        val target = SnoozeTarget(key, "meds", now - minute + day)
        assertEquals(target, state.snooze)
        assertTrue(snoozePassesNextTime(target, 24 * 60, now))
        assertFalse(snoozePassesNextTime(target, 60, now))
        assertTrue(snoozePassesNextTime(target, 24 * 60 - 1, now))
        assertFalse(snoozePassesNextTime(target, 24 * 60 - 2, now))
        assertFalse(snoozePassesNextTime(target.copy(nextMillis = null), 24 * 60, now))
        viewModel.dismissSnooze()
        assertTrue(alarms.snoozeConfirmed(key, "meds", 36 * hour))
        now += day
        alarms.recordFire(ReminderEventKey(item.id, now - minute), "meds")
        viewModel.openSnooze(key).join()
        assertNull(state.snooze)
        assertEquals(RemindersMessage.CannotSnooze, state.messages.last())
    }

    @Test
    fun notificationLinksOpenThePickerOrTheReviewAndAreTaken() = watching {
        val item = create(at = now - minute, message = "stretch")
        val key = ReminderEventKey(item.id, item.remindAtMillis)
        alarms.recordFire(key, "stretch")
        navigator.open(NotebookLink.Occurrence(key, snooze = true))
        assertEquals(SnoozeTarget(key, "stretch"), until { it.snooze != null && !it.busy }.snooze)
        until { navigator.pending.value == null }
        viewModel.dismissSnooze()
        navigator.open(NotebookLink.Completions)
        until { navigator.pending.value == null }
        assertNotNull(state.claims)
    }

    @Test
    fun aLinkOrASlowReadDoesNotReplaceAnOpenEditor() = watching {
        val item = create(message = "gym")
        viewModel.newReminder()
        form().offerMessage("typed")
        navigator.open(NotebookLink.Edit(item.id))
        until { navigator.pending.value == null && !it.busy }
        viewModel.editReminder(item.id).join()
        assertEquals(ReminderFormTarget.New, form().target)
        assertEquals("typed", form().message)
    }

    @Test
    fun aTextOnlyEditOfALateReminderKeepsItsTimeAndArmsItWithTheNewText() = runBlocking<Unit> {
        for (rule in listOf(null, RecurrenceRule(RecurrenceFrequency.DAILY, 1, now - minute))) {
            val late = create(at = now - minute, message = "stretch", rule = rule)
            viewModel.editReminder(late.id).join()
            form().offerMessage("stretch your legs")
            assertEquals(ReminderFormOutcome.Saved, viewModel.save(form()))
            val stored = reminders.readAll(strict = true).single { it.id == late.id }
            assertEquals(late.copy(message = "stretch your legs"), stored)
            assertEquals(stored, scheduler.fires[late.id])
        }
    }

    @Test
    fun aTextOnlyEditKeepsATimeTheReminderMovedToMeanwhile() = runBlocking<Unit> {
        val daily = create(at = now + day, message = "stretch", rule = RecurrenceRule(RecurrenceFrequency.DAILY, 1, now + day))
        viewModel.editReminder(daily.id).join()
        reminders.update(daily.id) { it.copy(remindAtMillis = now + 2 * day) }
        form().offerMessage("stretch your legs")
        assertEquals(ReminderFormOutcome.Saved, viewModel.save(form()))
        assertEquals(daily.copy(remindAtMillis = now + 2 * day, message = "stretch your legs"), reminders.readAll(strict = true).single())
    }

    @Test
    fun anEarlyCompletionWhoseHistoryCannotBeWrittenSaysTheReminderMovedOn() = runBlocking<Unit> {
        val once = create(message = "once")
        crypto.failSealWhenAadContains = ReminderEventStore.FILE_NAME
        viewModel.confirm(ReminderConfirm.Complete(ReminderEventKey(once.id, once.remindAtMillis), "once", repeats = false)).join()
        crypto.failSealWhenAadContains = null
        assertTrue(reminders.readAll(strict = true).isEmpty())
        assertEquals(RemindersMessage.DoneNotRecorded, state.messages.last())
    }

    @Test
    fun notDoneAndDeletingAnOccurrenceGoThroughTheAlarmStack() = runBlocking<Unit> {
        val skipped = create(message = "skip me", rule = RecurrenceRule(RecurrenceFrequency.DAILY, 1, now + day))
        val key = ReminderEventKey(skipped.id, skipped.remindAtMillis)
        viewModel.confirm(ReminderConfirm.NotDone(key, "skip me", OccurrenceScope.REPEATING)).join()
        assertEquals(now + 2 * day, reminders.readAll(strict = true).single().remindAtMillis)
        assertEquals(now, events.readAll(strict = true).single { it.key == key }.cancelledAt)
        val fired = create(at = now - minute, message = "fired")
        val firedKey = ReminderEventKey(fired.id, fired.remindAtMillis)
        alarms.recordFire(firedKey, "fired")
        viewModel.confirm(ReminderConfirm.DeleteOccurrence(firedKey, "fired", OccurrenceScope.OCCURRENCE)).join()
        assertTrue(events.readAll(strict = true).none { it.key == firedKey })
        assertEquals(RemindersMessage.Done, state.messages.last())
    }

    @Test
    fun aFailedReadIsReportedAsSuchAndASnoozeThatCannotBeSetSaysSo() = runBlocking<Unit> {
        val item = create()
        crypto.failOpenWhenAadContains = ReminderStore.FILE_NAME
        viewModel.editReminder(item.id).join()
        crypto.failOpenWhenAadContains = null
        assertNull(state.form)
        assertEquals(RemindersMessage.NotRead, state.messages.last())
        viewModel.snooze(SnoozeTarget(ReminderEventKey(item.id, now - minute), "m"), 0).join()
        assertEquals(RemindersMessage.NotSnoozed, state.messages.last())
        viewModel.snooze(SnoozeTarget(ReminderEventKey(99, now - minute), "m"), 10).join()
        assertEquals(RemindersMessage.NotSnoozed, state.messages.last())
    }

    @Test
    fun aStoreOutOfReminderIdsIsReportedAsSuch() = runBlocking<Unit> {
        reminders.addAllSkippingExistingIds(listOf(ReminderItem(ReminderFile.MAX_REMINDER_ID, now + day, "last")))
        viewModel.newReminder()
        form().offerMessage("one more")
        assertEquals(ReminderFormOutcome.Refused(ReminderFormError.IDS_EXHAUSTED), viewModel.save(form()))
    }

    @Test
    fun aMessageIsTakenUpToTheStoresCapAndNoFurther() {
        val form = ReminderForm(ReminderFormTarget.New, "", now, null, 1)
        val cap = com.anopticlabs.gravel.notebook.reminders.MAX_REMINDER_MESSAGE_CHARS
        assertTrue(form.offerMessage("x".repeat(cap)))
        assertTrue(!form.offerMessage("x".repeat(cap + 1)))
        assertEquals(cap, form.message.length)
    }

    @Test
    fun aFormIsChangedByAnyFieldAndNotByARepeatSwitchedOnAndOffAgain() {
        val form = ReminderForm(ReminderFormTarget.New, "a", now, null, 1)
        assertTrue(!form.changed)
        form.repeats = true
        assertTrue(form.changed)
        form.repeats = false
        form.intervalText = "2"
        assertTrue(!form.changed)
        form.repeats = true
        form.frequency = RecurrenceFrequency.WEEKLY
        assertTrue(form.changed)
    }

    private suspend fun park(id: String = "c1", transcript: String = "fed the dog"): VoiceCompletion =
        VoiceCompletion(id, transcript, now - minute).also { completions.save(it) }

    private fun candidates(claim: VoiceCompletion): List<CompletionCandidate> =
        completionCandidates(claim.transcript, reminders.readAll(strict = true), events.readAll(strict = true), claim.capturedAt, TimeZone.UTC)

    private fun resolving(claim: VoiceCompletion, action: VoiceCompletionAction = VoiceCompletionAction.COMPLETE): ReminderConfirm =
        assertNotNull(claimConfirm(claim, candidates(claim).single(), action))

    @Test
    fun theReviewListsParkedClaimsWithTheirCandidatesAndFollowsLaterParks() = watching {
        val late = create(at = now - hour, message = "feed the dog")
        val fired = create(at = now - 2 * hour, message = "walk the dog")
        alarms.recordFire(ReminderEventKey(fired.id, fired.remindAtMillis), "walk the dog")
        val claim = park()
        until { it.listing != null }
        viewModel.openCompletions()
        val review = until { it.review.isNotEmpty() }.review.single()
        assertEquals(claim, review.claim)
        assertEquals(setOf(late.id, fired.id), review.candidates.map { it.match.reminderId }.toSet())
        park("c2", "took the bins out")
        refresh.emit()
        until { it.review.size == 2 }
        viewModel.closeCompletions()
        assertNull(state.claims)
        assertTrue(state.review.isEmpty())
    }

    @Test
    fun aCompletionsLinkOpensTheReviewAndIsTaken() = watching {
        park()
        navigator.open(NotebookLink.Completions)
        until { it.review.size == 1 }
        withTimeout(5.seconds) { while (navigator.pending.value != null) delay(10) }
    }

    @Test
    fun aReviewClosedDuringItsReadStaysClosed() = watching {
        park()
        until { it.listing != null }
        create(message = "read in the same pass")
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        crypto.beforeOpen = { location ->
            if (location.contains(VoiceCompletionStore.FILE_NAME)) {
                reading.countDown()
                release.await()
            }
        }
        viewModel.openCompletions()
        assertTrue(reading.await(5, TimeUnit.SECONDS))
        viewModel.closeCompletions()
        crypto.beforeOpen = null
        release.countDown()
        // The reminder stored before the read shows once that read has been applied.
        assertNull(until { it.listing?.upcoming?.size == 1 }.claims)
    }

    @Test
    fun aClaimsReadFailureFailsOnlyTheReview() = watching {
        park()
        until { it.listing != null }
        crypto.failOpenWhenAadContains = VoiceCompletionStore.FILE_NAME
        viewModel.openCompletions()
        until { it.claims is Loadable.Failed }
        assertIs<Loadable.Ready<*>>(state.data)
        crypto.failOpenWhenAadContains = null
        viewModel.retry()
        until { it.review.size == 1 }
    }

    @Test
    fun aSnoozedCandidateIsCompletedByItsOccurrenceAndThenItsClaimIsTaken() = runBlocking<Unit> {
        val item = create(at = now - hour, message = "feed the dog")
        val key = ReminderEventKey(item.id, item.remindAtMillis)
        alarms.recordFire(key, "feed the dog")
        assertTrue(alarms.snoozeConfirmed(key, "feed the dog", 10 * minute))
        val claim = park()
        assertEquals(OccurrenceStatus.PENDING, candidates(claim).single().match.status)
        viewModel.confirm(resolving(claim)).join()
        assertEquals(claim.capturedAt, events.readAll(strict = true).single().completedAt)
        assertNull(scheduler.snoozes[key])
        assertTrue(completions.readAll(strict = true).isEmpty())
        assertEquals(RemindersMessage.Done, state.messages.last())
    }

    @Test
    fun aRepeatingReminderMovedOnWithoutItsHistoryStillTakesTheClaim() = runBlocking<Unit> {
        val item = create(at = now + hour, message = "feed the dog", rule = RecurrenceRule(RecurrenceFrequency.DAILY, 1, now + hour))
        val claim = park()
        val confirm = resolving(claim)
        crypto.failSealWhenAadContains = ReminderEventStore.FILE_NAME
        viewModel.confirm(confirm).join()
        crypto.failSealWhenAadContains = null
        assertEquals(item.remindAtMillis + day, reminders.readAll(strict = true).single().remindAtMillis)
        assertTrue(events.readAll(strict = true).isEmpty())
        assertTrue(completions.readAll(strict = true).isEmpty())
        assertEquals(RemindersMessage.DoneNotRecorded, state.messages.last())
    }

    @Test
    fun aCandidateWriteThatDoesNotLandKeepsTheClaim() = runBlocking<Unit> {
        val item = create(at = now + hour, message = "feed the dog")
        val claim = park()
        val confirm = resolving(claim, VoiceCompletionAction.NOT_DONE)
        crypto.failSealWhenAadContains = ReminderStore.FILE_NAME
        viewModel.confirm(confirm).join()
        crypto.failSealWhenAadContains = null
        assertEquals(listOf(item), reminders.readAll(strict = true))
        assertEquals(listOf(claim), completions.readAll(strict = true))
        assertEquals(RemindersMessage.NotChanged, state.messages.last())
    }

    @Test
    fun aClaimThatCannotBeTakenAfterItsWriteLandedStaysAndIsReported() = runBlocking<Unit> {
        create(at = now + hour, message = "feed the dog")
        val claim = park()
        val confirm = resolving(claim)
        crypto.failSealWhenAadContains = VoiceCompletionStore.FILE_NAME
        viewModel.confirm(confirm).join()
        crypto.failSealWhenAadContains = null
        assertTrue(reminders.readAll(strict = true).isEmpty())
        assertEquals(claim.capturedAt, events.readAll(strict = true).single().completedAt)
        assertEquals(listOf(claim), completions.readAll(strict = true))
        assertEquals(RemindersMessage.DoneClaimKept, state.messages.last())
        assertEquals(setOf(claim.completionId), state.settled)
    }

    @Test
    fun aClaimSavedAsANoteKeepsItsWordsInUnclassifiedAndGoes() = runBlocking<Unit> {
        val claim = park(transcript = "fed the dog\nand the cat")
        viewModel.confirm(ReminderConfirm.ClaimAsNote(claim)).join()
        val (id, entry) = notes.readMetadata(strict = true).entries.single()
        assertEquals(UNCLASSIFIED_FOLDER, entry.folder)
        assertEquals("fed the dog", entry.title)
        assertTrue(assertNotNull(notes.readNote(id, strict = true)).contains("and the cat"))
        assertTrue(completions.readAll(strict = true).isEmpty())
        assertEquals(RemindersMessage.SavedAsNote, state.messages.last())
    }

    @Test
    fun disregardingOrClearingAClaimChangesNoReminder() = runBlocking<Unit> {
        val item = create(at = now + hour, message = "feed the dog")
        val first = park("c1")
        val second = park("c2")
        viewModel.confirm(ReminderConfirm.DisregardClaim(first)).join()
        viewModel.confirm(ReminderConfirm.ClaimAlreadyDone(second)).join()
        assertTrue(completions.readAll(strict = true).isEmpty())
        assertEquals(listOf(item), reminders.readAll(strict = true))
        assertTrue(events.readAll(strict = true).isEmpty())
        assertEquals(RemindersMessage.ClaimRemoved, state.messages.last())
    }

    @Test
    fun aNewReminderFromAClaimTakesTheClaimOnlyOnceItIsStored() = runBlocking<Unit> {
        val claim = park(transcript = "x".repeat(MAX_REMINDER_MESSAGE_CHARS + 10))
        viewModel.newReminderFromClaim(claim)
        assertEquals(ReminderFormTarget.Claim(claim), form().target)
        assertEquals(MAX_REMINDER_MESSAGE_CHARS, form().message.length)
        assertEquals(now + hour, form().atMillis)
        assertTrue(form().offerMessage("call the vet"))
        crypto.failSealWhenAadContains = ReminderStore.FILE_NAME
        assertEquals(ReminderFormOutcome.Refused(ReminderFormError.NOT_SAVED), viewModel.save(form()))
        crypto.failSealWhenAadContains = null
        assertEquals(listOf(claim), completions.readAll(strict = true))
        assertEquals(ReminderFormOutcome.Saved, viewModel.save(form()))
        val stored = reminders.readAll(strict = true).single()
        assertEquals(ReminderItem(stored.id, now + hour, "call the vet"), stored)
        assertEquals(stored, scheduler.fires[stored.id])
        assertTrue(completions.readAll(strict = true).isEmpty())
        assertEquals(RemindersMessage.Saved, state.messages.last())
    }

    @Test
    fun candidateActionsFollowTheWriteTargetsAndResolveTheirClaim() {
        val claim = VoiceCompletion("c1", "fed the dog", now)
        val key = ReminderEventKey(1, now - hour)
        val event = ReminderEvent(1, now - hour, "feed the dog", firedAt = now - hour)
        fun candidate(status: OccurrenceStatus, logged: ReminderEvent? = null, repeats: Boolean = false) =
            CompletionCandidate(ReminderMatch.ScoredCandidate(1, now - hour, "feed the dog", status, 1.0), logged, repeats)
        assertEquals(
            ReminderConfirm.Complete(key, "feed the dog", repeats = true, resolves = claim),
            claimConfirm(claim, candidate(OccurrenceStatus.PENDING, repeats = true), VoiceCompletionAction.COMPLETE),
        )
        assertEquals(
            ReminderConfirm.NotDone(key, "feed the dog", OccurrenceScope.OCCURRENCE, resolves = claim),
            claimConfirm(claim, candidate(OccurrenceStatus.OVERDUE, event), VoiceCompletionAction.NOT_DONE),
        )
        assertEquals(
            ReminderConfirm.NotDone(key, "feed the dog", OccurrenceScope.REPEATING, resolves = claim),
            claimConfirm(claim, candidate(OccurrenceStatus.PENDING, repeats = true), VoiceCompletionAction.NOT_DONE),
        )
        assertEquals(
            ReminderConfirm.NotDone(key, "feed the dog", OccurrenceScope.ONE_TIME, resolves = claim),
            claimConfirm(claim, candidate(OccurrenceStatus.PENDING), VoiceCompletionAction.NOT_DONE),
        )
        for (action in VoiceCompletionAction.entries) {
            assertNull(claimConfirm(claim, candidate(OccurrenceStatus.COMPLETED, event), action))
            assertNull(claimConfirm(claim, candidate(OccurrenceStatus.MISSED, event), action))
        }
    }

    @Test
    fun aClaimMovedOnWithoutHistoryAndNotTakenSaysBoth() = runBlocking<Unit> {
        create(at = now + hour, message = "feed the dog", rule = RecurrenceRule(RecurrenceFrequency.DAILY, 1, now + hour))
        val claim = park()
        val confirm = resolving(claim)
        crypto.beforeSeal = { location ->
            if (location.contains(ReminderEventStore.FILE_NAME) || location.contains(VoiceCompletionStore.FILE_NAME)) {
                throw TransientCryptoException("injected", RuntimeException("injected"))
            }
        }
        viewModel.confirm(confirm).join()
        crypto.beforeSeal = null
        assertEquals(listOf(claim), completions.readAll(strict = true))
        assertEquals(RemindersMessage.DoneNotRecordedClaimKept, state.messages.last())
        assertEquals(setOf(claim.completionId), state.settled)
    }

    @Test
    fun aClaimNoteOrReminderWhoseClaimCannotBeTakenIsReportedAndSettled() = runBlocking<Unit> {
        val noted = park("c1")
        crypto.failSealWhenAadContains = VoiceCompletionStore.FILE_NAME
        viewModel.confirm(ReminderConfirm.ClaimAsNote(noted)).join()
        assertEquals(RemindersMessage.SavedAsNoteClaimKept, state.messages.last())
        crypto.failSealWhenAadContains = null
        val reminded = park("c2")
        viewModel.newReminderFromClaim(reminded)
        crypto.failSealWhenAadContains = VoiceCompletionStore.FILE_NAME
        assertEquals(ReminderFormOutcome.Saved, viewModel.save(form()))
        crypto.failSealWhenAadContains = null
        assertEquals(RemindersMessage.SavedClaimKept, state.messages.last())
        assertEquals(setOf("c1", "c2"), state.settled)
        assertEquals(listOf(noted, reminded), completions.readAll(strict = true))
    }

    @Test
    fun aDisregardTheStoreRefusesKeepsTheClaim() = runBlocking<Unit> {
        val claim = park()
        crypto.failSealWhenAadContains = VoiceCompletionStore.FILE_NAME
        viewModel.confirm(ReminderConfirm.DisregardClaim(claim)).join()
        crypto.failSealWhenAadContains = null
        assertEquals(listOf(claim), completions.readAll(strict = true))
        assertEquals(RemindersMessage.NotChanged, state.messages.last())
        assertTrue(state.settled.isEmpty())
    }

    @Test
    fun aTakenClaimLeavesTheOpenReview() = watching {
        val claim = park()
        viewModel.openCompletions()
        until { it.review.size == 1 }
        viewModel.confirm(ReminderConfirm.DisregardClaim(claim))
        until { it.claims is Loadable.Ready && it.review.isEmpty() }
    }

    @Test
    fun anActionOnAClaimAlreadyGoneWritesNothing() = runBlocking<Unit> {
        val item = create(at = now + hour, message = "feed the dog")
        val claim = park()
        val confirm = resolving(claim)
        completions.take(claim.completionId)
        viewModel.confirm(confirm).join()
        assertEquals(listOf(item), reminders.readAll(strict = true))
        assertEquals(RemindersMessage.ClaimGone, state.messages.last())
        viewModel.confirm(ReminderConfirm.ClaimAsNote(claim)).join()
        assertTrue(notes.readMetadata(strict = true).isEmpty())
        viewModel.newReminderFromClaim(claim)
        assertEquals(ReminderFormOutcome.Refused(ReminderFormError.CLAIM_GONE), viewModel.save(form()))
        assertEquals(listOf(item), reminders.readAll(strict = true))
    }

    @Test
    fun aCompletionsLinkWaitsForAnOpenEditor() = watching {
        park()
        viewModel.newReminder()
        navigator.open(NotebookLink.Completions)
        until { it.listing != null }
        assertNull(withTimeoutOrNull(1.seconds) { until { it.claims != null } })
        assertEquals(NotebookLink.Completions, navigator.pending.value)
        viewModel.closeForm()
        until { it.review.size == 1 }
        withTimeout(5.seconds) { while (navigator.pending.value != null) delay(10) }
    }

    @Test
    fun openingTheReviewClearsTheNudge() = watching {
        park()
        notifier.postCompletionsToReview(1)
        viewModel.openCompletions()
        until { it.review.size == 1 }
        assertNull(notifier.completionsCount)
    }

    @Test
    fun aClaimReviewedTheNextDayCompletesTheOccurrenceItWasAbout() = runBlocking<Unit> {
        val item = create(at = now - hour, message = "take out the bins")
        val key = ReminderEventKey(item.id, item.remindAtMillis)
        alarms.recordFire(key, "take out the bins")
        val claim = park(transcript = "took out the bins")
        now += 24 * hour
        val candidate = candidates(claim).single()
        assertEquals(key, candidate.key)
        viewModel.confirm(assertNotNull(claimConfirm(claim, candidate, VoiceCompletionAction.COMPLETE))).join()
        assertEquals(claim.capturedAt, events.readAll(strict = true).single().completedAt)
        assertTrue(completions.readAll(strict = true).isEmpty())
    }

    @Test
    fun theReviewRanksByWhenTheClaimWasSpoken() = watching {
        val day = 24 * hour
        val rule = RecurrenceRule(RecurrenceFrequency.DAILY, 1, now - 2 * day)
        val item = create(at = now + day, message = "take the pills", rule = rule)
        for (at in listOf(now - day, now)) events.appendFire(ReminderEvent(item.id, at, "take the pills", firedAt = at))
        val claim = VoiceCompletion("c1", "took the pills", now - day + 5 * minute).also { completions.save(it) }
        viewModel.openCompletions()
        val first = until { it.review.isNotEmpty() }.review.single().candidates.first()
        assertEquals(ReminderEventKey(item.id, claim.capturedAt - 5 * minute), first.key)
    }

    /** Blocks the first seal of [file] until the ViewModel made by [newViewModel] is cleared, then lets it finish. */
    private suspend fun clearedDuringASealOf(file: String, act: suspend (RemindersViewModel) -> Unit) = coroutineScope {
        val store = ViewModelStore()
        val cleared = ViewModelProvider.create(store, viewModelFactory { initializer { newViewModel() } })[RemindersViewModel::class]
        val sealing = CountDownLatch(1)
        val release = CountDownLatch(1)
        crypto.beforeSeal = { location ->
            if (location.contains(file) && sealing.count > 0) {
                sealing.countDown()
                release.await()
            }
        }
        val running = launch(Dispatchers.Default) { runCatching { act(cleared) } }
        assertTrue(sealing.await(5, TimeUnit.SECONDS))
        store.clear()
        crypto.beforeSeal = null
        release.countDown()
        running.join()
    }

    @Test
    fun aClaimsReminderSavedAsTheViewModelIsClearedStillTakesTheClaim() = runBlocking<Unit> {
        val claim = park()
        clearedDuringASealOf(ReminderStore.FILE_NAME) { vm ->
            vm.newReminderFromClaim(claim)
            val form = assertNotNull(vm.state.value.form)
            form.offerMessage("call the vet")
            vm.save(form)
        }
        assertEquals(1, reminders.readAll(strict = true).size)
        withTimeout(5.seconds) { while (completions.readAll(strict = true).isNotEmpty()) delay(10) }
    }

    @Test
    fun aCandidateConfirmedAsTheViewModelIsClearedStillTakesTheClaim() = runBlocking<Unit> {
        create(at = now + hour, message = "feed the dog")
        val claim = park()
        val confirm = resolving(claim)
        clearedDuringASealOf(ReminderEventStore.FILE_NAME) { vm -> vm.confirm(confirm).join() }
        withTimeout(5.seconds) { while (completions.readAll(strict = true).isNotEmpty()) delay(10) }
        assertEquals(claim.capturedAt, events.readAll(strict = true).single().completedAt)
    }

    @Test
    fun aSnoozePickedAsTheViewModelIsClearedStillSetsItsAlarm() = runBlocking<Unit> {
        val item = create(at = now - minute, message = "stretch")
        val key = ReminderEventKey(item.id, item.remindAtMillis)
        alarms.recordFire(key, "stretch")
        clearedDuringASealOf(ReminderEventStore.FILE_NAME) { vm -> vm.snooze(SnoozeTarget(key, "stretch"), 10).join() }
        assertEquals(now + 10 * minute, scheduler.snoozes[key])
    }

    // Calendar

    private val today = LocalDate(2026, 9, 22)

    @Test
    fun theCalendarIsWorkedOutOnlyWhileShownAndOpensOnTodaysMonth() = watching {
        val item = create(at = now + day, message = "dentist")
        synced()
        assertNull(state.calendar)
        viewModel.showView(RemindersView.CALENDAR)
        val page = assertNotNull(until { it.calendar != null }.calendar)
        assertEquals(LocalDate(2026, 9, 1), page.month)
        assertEquals(today, page.today)
        assertEquals(listOf(item.id), page.rows[LocalDate(2026, 9, 23)]?.map { it.reminderId })
        viewModel.showView(RemindersView.LIST)
        assertNull(state.calendar)
    }

    @Test
    fun pagingStaysWithinItsBoundsAndADayOfAnotherMonthPagesThere() = watching {
        create(at = now + 60 * day, message = "far")
        viewModel.showView(RemindersView.CALENDAR)
        val months = assertNotNull(synced().calendar).months
        assertEquals(LocalDate(2026, 6, 1)..LocalDate(2026, 11, 1), months)
        viewModel.showMonth(LocalDate(2026, 12, 1))
        assertEquals(LocalDate(2026, 11, 1), state.calendar?.month)
        assertEquals(LocalDate(2026, 11, 1), state.calendar?.day)
        viewModel.showMonth(LocalDate(2026, 9, 1))
        assertEquals(today, state.calendar?.day)
        viewModel.selectDay(LocalDate(2026, 10, 3))
        assertEquals(LocalDate(2026, 10, 1), state.calendar?.month)
        assertEquals(LocalDate(2026, 10, 3), state.calendar?.day)
    }

    @Test
    fun theCalendarTabOpensOnTodayAfterPaging() = watching {
        create(at = now + 60 * day, message = "far")
        viewModel.showView(RemindersView.CALENDAR)
        synced()
        viewModel.selectDay(LocalDate(2026, 9, 25))
        viewModel.showCalendarOnToday()
        assertEquals(today, state.calendar?.day)
        viewModel.selectDay(LocalDate(2026, 11, 21))
        assertEquals(LocalDate(2026, 11, 1), state.calendar?.month)
        viewModel.showView(RemindersView.LIST)
        viewModel.showCalendarOnToday()
        assertEquals(LocalDate(2026, 9, 1), state.calendar?.month)
        assertEquals(today, state.calendar?.day)
    }

    @Test
    fun theListedDayStaysInTheShownMonthWhenTheBoundsShrink() = watching {
        val far = create(at = now + 60 * day, message = "far")
        viewModel.showView(RemindersView.CALENDAR)
        synced()
        viewModel.selectDay(LocalDate(2026, 11, 21))
        assertEquals(LocalDate(2026, 11, 21), state.calendar?.day)
        viewModel.confirm(ReminderConfirm.DeleteOccurrence(ReminderEventKey(far.id, far.remindAtMillis), "far", OccurrenceScope.ONE_TIME)).join()
        val page = assertNotNull(until { it.calendar?.months?.endInclusive == LocalDate(2026, 10, 1) }.calendar)
        assertEquals(LocalDate(2026, 10, 1), page.month)
        assertEquals(LocalDate(2026, 10, 1), page.day)
    }

    @Test
    fun aDeleteThatMovedTheReminderWithoutItsHistorySaysSo() = runBlocking<Unit> {
        events.appendFire(ReminderEvent(99, now - day, "other", now - day))
        val once = create(message = "once")
        crypto.failOpenWhenAadContains = ReminderEventStore.FILE_NAME
        viewModel.confirm(ReminderConfirm.DeleteOccurrence(ReminderEventKey(once.id, once.remindAtMillis), "once", OccurrenceScope.ONE_TIME)).join()
        crypto.failOpenWhenAadContains = null
        assertTrue(reminders.readAll(strict = true).isEmpty())
        assertEquals(RemindersMessage.DeletedNotRecorded, state.messages.last())
    }

    @Test
    fun eachCalendarRowOffersWhatItsKindAndStatusAllow() {
        val key = ReminderEventKey(1, now + day)
        fun row(kind: CalendarRowKind, status: OccurrenceStatus = OccurrenceStatus.PENDING, at: Long = now + day, repeats: Boolean = false, onStored: Boolean = false) =
            CalendarOccurrence(1, at, "gym", status, kind, repeats = repeats, onStored = onStored)
        val logged = row(CalendarRowKind.LOGGED, OccurrenceStatus.OVERDUE, at = now + day)
        assertEquals(listOf(CalendarAction.TO_MISSED, CalendarAction.DELETE), calendarActions(row(CalendarRowKind.LOGGED, OccurrenceStatus.COMPLETED), now))
        assertEquals(listOf(CalendarAction.TO_COMPLETED, CalendarAction.DELETE), calendarActions(row(CalendarRowKind.LOGGED, OccurrenceStatus.MISSED), now))
        assertEquals(listOf(CalendarAction.COMPLETE, CalendarAction.MARK_MISSED, CalendarAction.DELETE), calendarActions(logged, now))
        assertEquals(
            listOf(CalendarAction.COMPLETE_EARLY, CalendarAction.MARK_MISSED_AHEAD, CalendarAction.DELETE_THIS_ONE, CalendarAction.EDIT),
            calendarActions(row(CalendarRowKind.NEXT, onStored = true), now),
        )
        assertEquals(
            listOf(CalendarAction.COMPLETE, CalendarAction.MARK_MISSED, CalendarAction.DELETE_THIS_ONE, CalendarAction.EDIT),
            calendarActions(row(CalendarRowKind.NEXT, at = now - minute, onStored = true), now),
        )
        assertEquals(listOf(CalendarAction.EDIT), calendarActions(row(CalendarRowKind.LATER, repeats = true), now))

        assertEquals(ReminderConfirm.DeleteOccurrence(key, "gym", OccurrenceScope.OCCURRENCE), calendarConfirm(logged, CalendarAction.DELETE))
        assertEquals(ReminderConfirm.Complete(key, "gym", repeats = false), calendarConfirm(logged, CalendarAction.COMPLETE))
        assertEquals(ReminderConfirm.SwitchStatus(key, "gym", completed = false), calendarConfirm(logged, CalendarAction.TO_MISSED))
        assertEquals(ReminderConfirm.SwitchStatus(key, "gym", completed = true), calendarConfirm(logged, CalendarAction.TO_COMPLETED))
        // A logged time the stored reminder still sits on moves that reminder, as its next time does.
        val sitsOn = logged.copy(repeats = true, onStored = true)
        assertEquals(ReminderConfirm.DeleteOccurrence(key, "gym", OccurrenceScope.REPEATING), calendarConfirm(sitsOn, CalendarAction.DELETE))
        assertEquals(ReminderConfirm.Complete(key, "gym", repeats = true), calendarConfirm(sitsOn, CalendarAction.COMPLETE))
        val once = row(CalendarRowKind.NEXT, onStored = true)
        assertEquals(ReminderConfirm.DeleteOccurrence(key, "gym", OccurrenceScope.ONE_TIME), calendarConfirm(once, CalendarAction.DELETE_THIS_ONE))
        assertEquals(ReminderConfirm.NotDone(key, "gym", OccurrenceScope.ONE_TIME), calendarConfirm(once, CalendarAction.MARK_MISSED_AHEAD))
        assertEquals(ReminderConfirm.NotDone(key, "gym", OccurrenceScope.REPEATING), calendarConfirm(once.copy(repeats = true), CalendarAction.MARK_MISSED_AHEAD))
        assertNull(calendarConfirm(once, CalendarAction.EDIT))
    }

    @Test
    fun theCalendarLinkWaitsForTheCalendarAndOpensItsDay() = watching {
        until { it.listing != null }
        navigator.open(NotebookLink.Calendar(now - 3 * day))
        assertNull(withTimeoutOrNull(500) { while (navigator.pending.value != null) delay(10) })
        assertNull(state.day)
        viewModel.showView(RemindersView.CALENDAR)
        until { navigator.pending.value == null && it.calendar != null }
        assertEquals(LocalDate(2026, 9, 1), state.month)
        assertEquals(LocalDate(2026, 9, 19), state.day)
    }

    @Test
    fun aNewReminderOnADayStartsAtNineOnALaterDayAndAnHourFromNowOtherwise() {
        viewModel.newReminderOn(LocalDate(2026, 9, 25))
        assertEquals(LocalDateTime(2026, 9, 25, 9, 0).toInstant(TimeZone.UTC).toEpochMilliseconds(), form().atMillis)
        assertEquals(ReminderFormTarget.New, form().target)
        viewModel.closeForm()
        viewModel.newReminderOn(today)
        assertEquals(now + hour, form().atMillis)
        viewModel.closeForm()
        viewModel.newReminderOn(LocalDate(2026, 9, 1))
        assertEquals(now + hour, form().atMillis)
    }

    @Test
    fun aConfirmedCalendarActionClosesTheDetailsAndGoesThroughTheAlarmStack() = watching {
        val item = create(at = now - hour, message = "stretch")
        val key = ReminderEventKey(item.id, item.remindAtMillis)
        alarms.recordFire(key, "stretch")
        alarms.complete(key, "stretch")
        viewModel.showView(RemindersView.CALENDAR)
        val row = assertNotNull(synced().calendar?.rows?.get(today)?.single())
        viewModel.openInfo(row)
        assertEquals(row, state.infoRow)
        val toMissed = ReminderConfirm.SwitchStatus(key, "stretch", completed = false)
        viewModel.ask(toMissed)
        viewModel.confirm(toMissed).join()
        assertNull(state.info)
        assertEquals(RemindersMessage.Done, state.messages.last())
        assertEquals(true, events.readAll(strict = true).single().setInCalendar)
        viewModel.confirm(ReminderConfirm.SwitchStatus(key, "stretch", completed = false)).join()
        assertEquals(RemindersMessage.NotChanged, state.messages.last())
        viewModel.confirm(ReminderConfirm.DeleteOccurrence(key, "stretch", OccurrenceScope.OCCURRENCE)).join()
        assertTrue(events.readAll(strict = true).isEmpty())
        val next = create(at = now + day, message = "gym", rule = RecurrenceRule(RecurrenceFrequency.DAILY, 1, now + day))
        refresh.emit()
        until { s -> s.calendar?.rows?.values?.flatten()?.any { it.reminderId == next.id && it.kind == CalendarRowKind.NEXT } == true }
        viewModel.confirm(ReminderConfirm.DeleteOccurrence(ReminderEventKey(next.id, next.remindAtMillis), "gym", OccurrenceScope.REPEATING)).join()
        assertEquals(now + 2 * day, reminders.readAll(strict = true).single().remindAtMillis)
    }

    // Phone calendar

    private val september = LocalDate(2026, 9, 1)
    private val work = PhoneCalendarChoice(shown = true, calendars = setOf("work"))

    private fun phoneEvent(id: Long, day: LocalDate, title: String = "event $id", hour: Int = 10): PhoneEvent {
        val begin = LocalDateTime(day.year, day.month, day.day, hour, 0).toInstant(TimeZone.UTC).toEpochMilliseconds()
        return PhoneEvent(title, begin, begin + this.hour, allDay = false, calendarName = "Work")
    }

    @Test
    fun thePhoneCalendarIsReadForTheShownMonthOnlyWhileTheCalendarIsShown() = watching {
        phoneChoice = work
        val dentist = phoneEvent(1, LocalDate(2026, 9, 23), "dentist")
        phone.events = mapOf("work" to listOf(dentist), "home" to listOf(phoneEvent(2, LocalDate(2026, 9, 23))))
        synced()
        assertTrue(phone.reads.isEmpty())
        viewModel.showView(RemindersView.CALENDAR)
        val page = assertNotNull(until { it.calendar?.phoneEvents?.isNotEmpty() == true }.calendar)
        assertEquals(mapOf(LocalDate(2026, 9, 23) to listOf(dentist)), page.phoneEvents)
        assertEquals(listOf(setOf("work") to phoneQueryWindow(calendarGridDays(september), TimeZone.UTC)), phone.reads.toList())
    }

    @Test
    fun aPageShowsOnlyTheReadOfItsOwnMonth() = watching {
        phoneChoice = work
        val september23 = phoneEvent(1, LocalDate(2026, 9, 23))
        val october20 = phoneEvent(2, LocalDate(2026, 10, 20))
        phone.events = mapOf("work" to listOf(september23, october20))
        viewModel.showView(RemindersView.CALENDAR)
        synced()
        until { it.calendar?.phoneEvents?.containsKey(LocalDate(2026, 9, 23)) == true }
        val gate = CountDownLatch(1)
        phone.gate = gate
        viewModel.showMonth(LocalDate(2026, 10, 1))
        assertEquals(LocalDate(2026, 10, 1), state.calendar?.month)
        assertEquals(emptyMap(), state.calendar?.phoneEvents)
        gate.countDown()
        val page = assertNotNull(until { it.calendar?.phoneEvents?.isNotEmpty() == true }.calendar)
        assertEquals(listOf(october20), page.phoneEvents[LocalDate(2026, 10, 20)])
        assertEquals(phoneQueryWindow(calendarGridDays(LocalDate(2026, 10, 1)), TimeZone.UTC), phone.reads.last().second)
    }

    @Test
    fun noCalendarAccessAndAFailedReadAreShownOnThePageAndAFailedReadIsRetried() = watching {
        phoneChoice = work
        phone.events = null
        viewModel.showView(RemindersView.CALENDAR)
        synced()
        until { it.calendar?.phoneProblem == PhoneProblem.NO_ACCESS }
        phone.fail = true
        viewModel.showMonth(LocalDate(2026, 10, 1))
        until { it.calendar?.phoneProblem == PhoneProblem.NOT_READ }
        phone.fail = false
        phone.events = mapOf("work" to listOf(phoneEvent(1, LocalDate(2026, 10, 20))))
        viewModel.retry()
        val page = assertNotNull(until { it.calendar?.phoneEvents?.isNotEmpty() == true }.calendar)
        assertNull(page.phoneProblem)
    }

    @Test
    fun aShownPhoneCalendarWithNothingPickedSaysSoWithoutReading() = watching {
        phoneChoice = PhoneCalendarChoice(shown = true, calendars = emptySet())
        viewModel.showView(RemindersView.CALENDAR)
        synced()
        until { it.calendar?.phoneProblem == PhoneProblem.NONE_PICKED }
        assertTrue(phone.reads.isEmpty())
    }

    @Test
    fun theChoiceIsTakenAgainWhenTheSectionStartsAndNothingIsReadWhileItIsOff() {
        phone.events = mapOf("work" to listOf(phoneEvent(1, LocalDate(2026, 9, 23))))
        watching {
            viewModel.showView(RemindersView.CALENDAR)
            synced()
        }
        for (off in listOf(PhoneCalendarChoice(shown = false, calendars = setOf("work")), PhoneCalendarChoice(shown = true, calendars = emptySet()))) {
            phoneChoice = work
            watching { until { it.calendar?.phoneEvents?.isNotEmpty() == true } }
            val reads = phone.reads.size
            phoneChoice = off
            watching { until { it.calendar?.phoneEvents?.isEmpty() == true } }
            assertEquals(reads, phone.reads.size, off.toString())
        }
    }

    @Test
    fun aZoneChangeReadsThePhoneCalendarAgainInTheNewZone() = watching {
        phoneChoice = work
        val late = phoneEvent(1, LocalDate(2026, 9, 23), hour = 23)
        phone.events = mapOf("work" to listOf(late))
        viewModel.showView(RemindersView.CALENDAR)
        synced()
        until { it.calendar?.phoneEvents?.containsKey(LocalDate(2026, 9, 23)) == true }
        zone = TimeZone.of("Asia/Tokyo")
        viewModel.selectDay(LocalDate(2026, 9, 24))
        until { it.calendar?.phoneEvents?.get(LocalDate(2026, 9, 24)) == listOf(late) }
        assertEquals(phoneQueryWindow(calendarGridDays(september), TimeZone.of("Asia/Tokyo")), phone.reads.last().second)
    }

    @Test
    fun theShownPhoneCalendarWidensPagingToAYearEitherSide() = watching {
        viewModel.showView(RemindersView.CALENDAR)
        assertEquals(LocalDate(2026, 6, 1)..LocalDate(2026, 10, 1), assertNotNull(synced().calendar).months)
        viewModel.showMonth(LocalDate(2027, 9, 1))
        assertEquals(LocalDate(2026, 10, 1), state.calendar?.month)
        phoneChoice = work
        viewModel.showMonth(LocalDate(2027, 9, 1))
        assertEquals(LocalDate(2027, 9, 1), state.calendar?.month)
        assertEquals(LocalDate(2025, 9, 1)..LocalDate(2027, 9, 1), state.calendar?.months)
        phoneChoice = PhoneCalendarChoice(shown = true, calendars = emptySet())
        viewModel.showMonth(LocalDate(2027, 9, 1))
        assertEquals(LocalDate(2026, 10, 1), state.calendar?.month)
    }

    @Test
    fun aMonthBeyondTheBoundsIsNotKeptForWhenTheyWiden() = watching {
        viewModel.showView(RemindersView.CALENDAR)
        synced()
        viewModel.selectDay(LocalDate(2026, 11, 5))
        assertEquals(today, state.calendar?.day)
        viewModel.showMonth(LocalDate(2026, 12, 1))
        assertEquals(LocalDate(2026, 10, 1), state.calendar?.month)
        phoneChoice = work
        viewModel.showView(RemindersView.CALENDAR)
        assertEquals(LocalDate(2026, 10, 1), state.calendar?.month)
    }

    @Test
    fun phoneEventsOnDaysOfMonthsBeyondTheBoundsAreLeftOff() = watching {
        phoneChoice = work
        val inside = phoneEvent(1, LocalDate(2027, 9, 28))
        val beyond = phoneEvent(2, LocalDate(2027, 10, 3))
        phone.events = mapOf("work" to listOf(inside, beyond))
        viewModel.showView(RemindersView.CALENDAR)
        synced()
        viewModel.showMonth(LocalDate(2027, 9, 1))
        val page = assertNotNull(until { it.calendar?.month == LocalDate(2027, 9, 1) && it.calendar.phoneEvents.isNotEmpty() }.calendar)
        assertEquals(mapOf(LocalDate(2027, 9, 28) to listOf(inside)), page.phoneEvents)
        viewModel.selectDay(LocalDate(2027, 10, 3))
        assertEquals(LocalDate(2027, 9, 1), state.calendar?.month)
    }
}

private class FakePhoneCalendar : PhoneCalendar {
    @Volatile var events: Map<String, List<PhoneEvent>>? = emptyMap()
    @Volatile var fail = false
    @Volatile var gate: CountDownLatch? = null
    val reads = CopyOnWriteArrayList<Pair<Set<String>, LongRange>>()

    override fun calendars(): List<PhoneCalendarInfo>? = emptyList()

    override fun events(calendars: Set<String>, window: LongRange): List<PhoneEvent>? {
        gate?.await(5, TimeUnit.SECONDS)
        reads += calendars to window
        if (fail) error("provider failed")
        return events?.filterKeys { it in calendars }?.values?.flatten()?.filter { it.beginMillis <= window.last && it.endMillis >= window.first }
    }
}
