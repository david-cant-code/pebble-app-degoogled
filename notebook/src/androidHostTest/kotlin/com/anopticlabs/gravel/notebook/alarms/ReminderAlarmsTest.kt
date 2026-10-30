package com.anopticlabs.gravel.notebook.alarms

import com.anopticlabs.gravel.notebook.reminders.MAX_SNOOZE_CHAIN
import com.anopticlabs.gravel.notebook.reminders.RecurrenceFrequency
import com.anopticlabs.gravel.notebook.reminders.RecurrenceRule
import com.anopticlabs.gravel.notebook.reminders.ReminderEvent
import com.anopticlabs.gravel.notebook.reminders.ReminderEventKey
import com.anopticlabs.gravel.notebook.reminders.ReminderItem
import com.anopticlabs.gravel.notebook.reminders.ReminderRefreshSignal
import com.anopticlabs.gravel.notebook.storage.EncryptedFiles
import com.anopticlabs.gravel.notebook.storage.FakeCryptographer
import com.anopticlabs.gravel.notebook.storage.ReminderEventStore
import com.anopticlabs.gravel.notebook.storage.ReminderStore
import com.anopticlabs.gravel.notebook.storage.softwareCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReminderAlarmsTest {

    private val root = Files.createTempDirectory("notebook").toFile()
    private val fake = FakeCryptographer(softwareCrypto())
    private val reminders = ReminderStore(root, fake)
    private val events = ReminderEventStore(root, fake)
    private val scheduler = FakeScheduler()
    private val notifier = FakeNotifier()
    private val settings = FakeSettings()
    private val refresh = ReminderRefreshSignal()
    private var now = 1_700_000_000_000L
    private val day = 86_400_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val daily = RecurrenceRule(RecurrenceFrequency.DAILY, 1, now)
    private val alarms = ReminderAlarms(reminders, events, scheduler, notifier, settings, refresh, { now }, { TimeZone.UTC })

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private suspend fun create(at: Long = now, rule: RecurrenceRule? = null, message: String = "m"): ReminderItem =
        reminders.create { ReminderItem(it, at, message, rule) }

    private fun keyOf(item: ReminderItem) = ReminderEventKey(item.id, item.remindAtMillis)

    private fun event(key: ReminderEventKey) = events.readAll(strict = true).firstOrNull { it.key == key }

    private fun corruptLastByte(name: String) {
        val file = File(root, name)
        val bytes = file.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        file.writeBytes(bytes)
    }

    @Test
    fun aFireLogsTheOccurrenceUnderItsScheduledInstantAndRemovesAOneShot() = runBlocking {
        val item = create(at = now - 500L)
        alarms.arm(item)
        assertTrue(alarms.postFire(keyOf(item), item.message))
        alarms.recordFire(keyOf(item), item.message)
        assertEquals(ReminderEvent(item.id, now - 500L, "m", firedAt = now), event(keyOf(item)))
        assertEquals(emptyList(), reminders.readAll(strict = true))
        assertEquals(listOf(keyOf(item) to "m"), notifier.posted)
    }

    @Test
    fun aFireOfARecurringItemAdvancesItAndArmsTheNextFire() = runBlocking {
        val item = create(rule = daily)
        now += 3 * day + 1
        alarms.recordFire(keyOf(item), item.message)
        val advanced = item.copy(remindAtMillis = item.remindAtMillis + 4 * day)
        assertEquals(listOf(advanced), reminders.readAll(strict = true))
        assertEquals(advanced, scheduler.fires[item.id])
    }

    @Test
    fun anEarlyDeliveryAdvancesFromTheScheduledFire() = runBlocking {
        val item = create(rule = daily)
        now -= 5_000L
        alarms.recordFire(keyOf(item), item.message)
        assertEquals(item.remindAtMillis + day, scheduler.fires[item.id]?.remindAtMillis)
    }

    @Test
    fun aFireAgainstACorruptRemindersFileArmsNothingAndKeepsTheLog() = runBlocking {
        val item = create(rule = daily)
        corruptLastByte(ReminderStore.FILE_NAME)
        alarms.recordFire(keyOf(item), item.message)
        assertNotNull(event(keyOf(item)))
        assertTrue(scheduler.fires.isEmpty())
        assertFalse(File(root, EncryptedFiles.QUARANTINE_DIR).exists())
    }

    @Test
    fun aRecurringItemWhoseFireCouldNotBeLoggedStillAdvances() = runBlocking {
        val item = create(rule = daily)
        fake.failSealWhenAadContains = ReminderEventStore.FILE_NAME
        alarms.recordFire(keyOf(item), item.message)
        fake.failSealWhenAadContains = null
        assertNull(event(keyOf(item)))
        assertEquals(item.remindAtMillis + day, scheduler.fires[item.id]?.remindAtMillis)
    }

    @Test
    fun aOneShotWhoseFireCouldNotBeLoggedStaysStoredUntilAReArmLogsIt() = runBlocking {
        val item = create(at = now - 500L)
        fake.failSealWhenAadContains = ReminderEventStore.FILE_NAME
        alarms.recordFire(keyOf(item), item.message)
        fake.failSealWhenAadContains = null
        assertNull(event(keyOf(item)))
        assertEquals(listOf(item), reminders.readAll(strict = true))
        alarms.rearmAll()
        assertEquals("m", event(keyOf(item))?.message)
        assertEquals(emptyList(), reminders.readAll(strict = true))
        assertEquals(listOf(keyOf(item) to "m"), notifier.posted)
        assertTrue(scheduler.fires.isEmpty())
    }

    @Test
    fun aFireOfAnItemAlreadyGoneLogsTheOccurrenceAndArmsNothing() = runBlocking {
        val key = ReminderEventKey(7, now)
        alarms.recordFire(key, "gone")
        assertEquals("gone", event(key)?.message)
        assertTrue(scheduler.fires.isEmpty())
    }

    @Test
    fun completeStampsTheOccurrenceDismissesItAndCancelsAPendingSnooze() = runBlocking {
        val item = create()
        alarms.postFire(keyOf(item), "m")
        alarms.recordFire(keyOf(item), "m")
        alarms.softSnooze(keyOf(item), "m")
        assertTrue(scheduler.snoozes.containsKey(keyOf(item)))
        now += minute
        alarms.complete(keyOf(item), "m")
        assertEquals(now, event(keyOf(item))?.completedAt)
        assertTrue(notifier.live.isEmpty())
        assertTrue(scheduler.snoozes.isEmpty())
    }

    @Test
    fun completeOrCancelOnAnUnloggedOccurrenceCreatesTheResolvedEvent() = runBlocking {
        val done = ReminderEventKey(3, now - day)
        alarms.complete(done, "done")
        assertEquals(ReminderEvent(3, now - day, "done", firedAt = now, completedAt = now), event(done))
        val dropped = ReminderEventKey(4, now - day)
        alarms.cancel(dropped, "dropped")
        assertEquals(ReminderEvent(4, now - day, "dropped", firedAt = now, cancelledAt = now), event(dropped))
    }

    @Test
    fun cancelStampsCancelledDismissesAndCancelsAPendingSnooze() = runBlocking {
        val item = create()
        alarms.postFire(keyOf(item), "m")
        alarms.recordFire(keyOf(item), "m")
        alarms.softSnooze(keyOf(item), "m")
        alarms.cancel(keyOf(item), "m")
        assertEquals(now, event(keyOf(item))?.cancelledAt)
        assertNull(event(keyOf(item))?.completedAt)
        assertTrue(notifier.live.isEmpty())
        assertTrue(scheduler.snoozes.isEmpty())
    }

    @Test
    fun completingARecurringOccurrenceLeavesTheNextFireArmed() = runBlocking {
        val item = create(rule = daily)
        alarms.recordFire(keyOf(item), "m")
        alarms.complete(keyOf(item), "m")
        assertEquals(item.remindAtMillis + day, scheduler.fires[item.id]?.remindAtMillis)
        assertEquals(1, reminders.readAll(strict = true).size)
    }

    @Test
    fun anUnloggedOccurrenceWhoseItemCannotMoveIsNotStampedAndKeepsItsAlarm() = runBlocking {
        for (at in listOf(now + day, now - minute)) {
            val item = create(at = at)
            alarms.arm(item)
            fake.failSealWhenAadContains = ReminderStore.FILE_NAME
            assertEquals(Resolution.UNCHANGED, alarms.complete(keyOf(item), "m"))
            assertEquals(Resolution.UNCHANGED, alarms.cancel(keyOf(item), "m"))
            fake.failSealWhenAadContains = null
            assertNull(event(keyOf(item)))
            assertEquals(item, scheduler.fires[item.id])
            assertEquals(Resolution.RECORDED, alarms.complete(keyOf(item), "m"))
            assertEquals(now, event(keyOf(item))?.completedAt)
            assertNull(scheduler.fires[item.id])
        }
        assertEquals(emptyList(), reminders.readAll(strict = true))
    }

    @Test
    fun aLoggedOccurrenceIsStampedWhenItsItemCannotMove() = runBlocking {
        val stuck = create(at = now - minute)
        fake.failSealWhenAadContains = ReminderStore.FILE_NAME
        alarms.recordFire(keyOf(stuck), "m")
        assertEquals(Resolution.RECORDED, alarms.complete(keyOf(stuck), "m"))
        fake.failSealWhenAadContains = null
        assertEquals(now, event(keyOf(stuck))?.completedAt)
    }

    @Test
    fun aStampThatFailsKeepsTheNotificationAndTheSnooze() = runBlocking {
        val item = create(at = now - minute)
        alarms.postFire(keyOf(item), "m")
        alarms.recordFire(keyOf(item), "m")
        alarms.snoozeConfirmed(keyOf(item), "m", 5 * minute)
        alarms.postFire(keyOf(item), "m")
        fake.failSealWhenAadContains = ReminderEventStore.FILE_NAME
        assertEquals(Resolution.UNCHANGED, alarms.cancel(keyOf(item), "m"))
        fake.failSealWhenAadContains = null
        assertNull(event(keyOf(item))?.cancelledAt)
        assertEquals(now + 5 * minute, scheduler.snoozes[keyOf(item)])
        assertTrue(keyOf(item) in notifier.live)
    }

    @Test
    fun anEarlyResolveWhoseStampFailsAfterTheItemMovedSaysSoAndTicks() = runBlocking {
        val item = create(at = now + day)
        alarms.arm(item)
        fake.failSealWhenAadContains = ReminderEventStore.FILE_NAME
        tickDuring { assertEquals(Resolution.MOVED_ONLY, alarms.complete(keyOf(item), "m")) }
        fake.failSealWhenAadContains = null
        assertEquals(emptyList(), reminders.readAll(strict = true))
        assertNull(scheduler.fires[item.id])
        assertNull(event(keyOf(item)))
    }

    @Test
    fun anEditAndAFireOfTheSameReminderLeaveTheAlarmOnWhatIsStored() = runBlocking {
        val item = create(at = now, rule = daily)
        val firing = java.util.concurrent.CountDownLatch(1)
        val edited = java.util.concurrent.CountDownLatch(1)
        // Holds the fire path's alarm for its advanced item until the edit is done or half a second passed.
        scheduler.beforeScheduleFire = { armed ->
            if (armed.message == "m") {
                firing.countDown()
                edited.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
        val fire = launch(Dispatchers.IO) { alarms.recordFire(keyOf(item), "m") }
        withContext(Dispatchers.IO) { firing.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        val editing = launch(Dispatchers.IO) {
            alarms.edit(item.id) { it.copy(remindAtMillis = now + 3 * day, message = "edited") }
            edited.countDown()
        }
        fire.join()
        editing.join()
        scheduler.beforeScheduleFire = null
        assertEquals(reminders.readAll(strict = true).single(), scheduler.fires[item.id])
    }

    @Test
    fun aPickerSnoozeRemovesTheNotificationOnlyOnceTheSnoozeIsRecorded() = runBlocking {
        val item = create()
        alarms.postFire(keyOf(item), "m")
        alarms.recordFire(keyOf(item), "m")
        fake.failSealWhenAadContains = ReminderEventStore.FILE_NAME
        assertFalse(alarms.snoozeConfirmed(keyOf(item), "m", 5 * minute))
        fake.failSealWhenAadContains = null
        assertEquals(setOf(keyOf(item)), notifier.live)
        assertTrue(scheduler.snoozes.isEmpty())

        val unlogged = ReminderEventKey(99, now)
        notifier.postFire(unlogged, "x")
        assertFalse(alarms.snoozeConfirmed(unlogged, "x", minute))
        assertTrue(unlogged in notifier.live)

        assertTrue(alarms.snoozeConfirmed(keyOf(item), "m", 5 * minute))
        assertEquals(now + 5 * minute, scheduler.snoozes[keyOf(item)])
        assertFalse(keyOf(item) in notifier.live)
    }

    @Test
    fun deletingAnOccurrenceRemovesItsEventNotificationAndSnoozeOrNothingWhenTheLogRefuses() = runBlocking {
        val item = create()
        alarms.postFire(keyOf(item), "m")
        alarms.recordFire(keyOf(item), "m")
        alarms.softSnooze(keyOf(item), "m")
        fake.failSealWhenAadContains = ReminderEventStore.FILE_NAME
        assertEquals(Resolution.UNCHANGED, alarms.deleteOccurrence(keyOf(item)))
        fake.failSealWhenAadContains = null
        assertNotNull(event(keyOf(item)))
        assertTrue(keyOf(item) in scheduler.snoozes)
        assertTrue(keyOf(item) in notifier.live)
        assertEquals(Resolution.RECORDED, alarms.deleteOccurrence(keyOf(item)))
        assertNull(event(keyOf(item)))
        assertTrue(scheduler.snoozes.isEmpty())
        assertFalse(keyOf(item) in notifier.live)
    }

    @Test
    fun deletingAnOccurrenceTheReminderStillSitsOnMovesItFirstSoNoReArmLogsItAgain() = runBlocking {
        val item = create(at = now - minute, rule = RecurrenceRule(RecurrenceFrequency.DAILY, 1, now - minute))
        fake.failSealWhenAadContains = ReminderStore.FILE_NAME
        alarms.recordFire(keyOf(item), "m")
        assertEquals(Resolution.UNCHANGED, alarms.deleteOccurrence(keyOf(item)))
        fake.failSealWhenAadContains = null
        assertNotNull(event(keyOf(item)))
        assertEquals(listOf(item), reminders.readAll(strict = true))
        assertEquals(Resolution.RECORDED, alarms.deleteOccurrence(keyOf(item)))
        assertNull(event(keyOf(item)))
        val moved = reminders.readAll(strict = true).single()
        assertEquals(item.remindAtMillis + day, moved.remindAtMillis)
        assertEquals(moved, scheduler.fires[item.id])
        alarms.rearmAll()
        assertNull(event(keyOf(item)))
    }

    @Test
    fun deletingANextTimeMovesARepeatingReminderOnAndRemovesAOneShotWithoutARecord() = runBlocking {
        val repeating = create(at = now + hour, rule = RecurrenceRule(RecurrenceFrequency.DAILY, 1, now + hour))
        assertEquals(Resolution.RECORDED, alarms.deleteOccurrence(keyOf(repeating)))
        assertEquals(now + hour + day, reminders.readAll(strict = true).single().remindAtMillis)
        assertEquals(now + hour + day, scheduler.fires[repeating.id]?.remindAtMillis)
        val once = create(at = now + hour)
        alarms.arm(once)
        assertEquals(Resolution.RECORDED, alarms.deleteOccurrence(keyOf(once)))
        assertEquals(listOf(repeating.id), reminders.readAll(strict = true).map { it.id })
        assertNull(scheduler.fires[once.id])
        assertEquals(emptyList(), events.readAll(strict = true))
    }

    @Test
    fun deletingTheOccurrenceThatMadeAnEarlierOneMissedLeavesItMissed() = runBlocking {
        val monday = ReminderEvent(1, now - day, "m", now - day, latestSnoozeFireAt = now + hour)
        val tuesday = ReminderEvent(1, now - minute, "m", now - minute)
        events.appendFire(monday)
        events.appendFire(tuesday)
        // A notification the sweep has not taken down yet goes once the stamp resolves it.
        notifier.live += monday.key
        assertEquals(Resolution.RECORDED, alarms.deleteOccurrence(tuesday.key))
        assertEquals(listOf(monday.copy(cancelledAt = tuesday.originalFireMillis)), events.readAll(strict = true))
        assertFalse(monday.key in notifier.live)
    }

    @Test
    fun aDeleteThatMovedTheReminderButCouldNotReachTheLogSaysSoAndTakesTheNotificationDown() = runBlocking {
        events.appendFire(ReminderEvent(99, now - day, "other", now - day))
        val once = create(at = now + hour)
        alarms.arm(once)
        notifier.live += keyOf(once)
        fake.failOpenWhenAadContains = ReminderEventStore.FILE_NAME
        assertEquals(Resolution.MOVED_ONLY, alarms.deleteOccurrence(keyOf(once)))
        fake.failOpenWhenAadContains = null
        assertEquals(emptyList(), reminders.readAll(strict = true))
        assertNull(scheduler.fires[once.id])
        assertFalse(keyOf(once) in notifier.live)
    }

    @Test
    fun theCalendarSwitchChangesOnlyAnOccurrenceAtRestAndTakesDownWhatIsLeft() = runBlocking {
        val item = create()
        alarms.recordFire(keyOf(item), "m")
        assertFalse(alarms.switchStatus(keyOf(item), completed = true))
        alarms.complete(keyOf(item), "m")
        notifier.live += keyOf(item)
        scheduler.snoozes[keyOf(item)] = now + hour
        fake.failSealWhenAadContains = ReminderEventStore.FILE_NAME
        assertFalse(alarms.switchStatus(keyOf(item), completed = false))
        fake.failSealWhenAadContains = null
        assertTrue(alarms.switchStatus(keyOf(item), completed = false))
        assertEquals(ReminderEvent(item.id, item.remindAtMillis, "m", now, cancelledAt = now, setInCalendar = true), event(keyOf(item)))
        assertFalse(keyOf(item) in notifier.live)
        assertFalse(keyOf(item) in scheduler.snoozes)
    }

    @Test
    fun aSnoozeKeepsThePassedLateNightReFireItReplaces() = runBlocking {
        // 1_700_000_000_000 is 22:13 UTC.
        val item = create()
        alarms.recordFire(keyOf(item), "m")
        alarms.softSnooze(keyOf(item), "m")
        val refire = now + 10 * minute
        now += 11 * minute
        alarms.snoozedFire(keyOf(item), "m")
        assertTrue(alarms.snoozeConfirmed(keyOf(item), "m", hour))
        assertEquals(refire, event(keyOf(item))?.lateNightFireAt)
        assertEquals(now + hour, event(keyOf(item))?.latestSnoozeFireAt)
    }

    @Test
    fun deletingAReminderClearsItsSnoozesBeforeRemovingItAndAFailedWriteLeavesItStored() = runBlocking {
        val item = create(at = now - minute, rule = daily)
        alarms.recordFire(keyOf(item), "m")
        alarms.softSnooze(keyOf(item), "m")
        val next = reminders.readAll(strict = true).single()
        fake.failSealWhenAadContains = ReminderEventStore.FILE_NAME
        assertFalse(alarms.delete(item.id))
        fake.failSealWhenAadContains = null
        assertTrue(keyOf(item) in scheduler.snoozes)
        assertEquals(listOf(next), reminders.readAll(strict = true))
        fake.failSealWhenAadContains = ReminderStore.FILE_NAME
        assertFalse(alarms.delete(item.id))
        fake.failSealWhenAadContains = null
        assertTrue(scheduler.snoozes.isEmpty())
        assertEquals(listOf(next), reminders.readAll(strict = true))
        assertEquals(next, scheduler.fires[item.id])
        assertTrue(alarms.delete(item.id))
        assertNull(scheduler.fires[item.id])
        assertEquals(emptyList(), reminders.readAll(strict = true))
        alarms.rearmAll()
        assertTrue(scheduler.snoozes.isEmpty())
        assertTrue(scheduler.fires.isEmpty())
    }

    @Test
    fun aSwipeSnoozesForTheConfiguredMinutesAndCountsTowardTheCap() = runBlocking {
        settings.swipeSnoozeMinutes = 15
        val item = create()
        alarms.recordFire(keyOf(item), "m")
        alarms.softSnooze(keyOf(item), "m")
        assertEquals(now + 15 * minute, scheduler.snoozes[keyOf(item)])
        assertEquals(now + 15 * minute, event(keyOf(item))?.latestSnoozeFireAt)
        assertEquals(1, event(keyOf(item))?.snoozeChainCount)
    }

    @Test
    fun aSwipeAtTheCapReschedulesNothingWhileThePickerStillDoes() = runBlocking {
        val item = create()
        alarms.recordFire(keyOf(item), "m")
        repeat(MAX_SNOOZE_CHAIN) {
            now += minute
            alarms.softSnooze(keyOf(item), "m")
        }
        val last = scheduler.snoozes[keyOf(item)]
        now += minute
        alarms.softSnooze(keyOf(item), "m")
        assertEquals(last, scheduler.snoozes[keyOf(item)])
        assertEquals(MAX_SNOOZE_CHAIN, event(keyOf(item))?.snoozeChainCount)

        alarms.postFire(keyOf(item), "m")
        alarms.snoozeConfirmed(keyOf(item), "m", 5 * minute)
        assertEquals(now + 5 * minute, scheduler.snoozes[keyOf(item)])
        assertEquals(MAX_SNOOZE_CHAIN, event(keyOf(item))?.snoozeChainCount)
        assertTrue(notifier.live.isEmpty())
    }

    @Test
    fun aSwipeOrPickerOnAnUnknownOrResolvedOccurrenceIsDropped() = runBlocking {
        alarms.softSnooze(ReminderEventKey(9, now), "m")
        val done = create()
        alarms.recordFire(keyOf(done), "m")
        alarms.complete(keyOf(done), "m")
        alarms.softSnooze(keyOf(done), "m")
        alarms.snoozeConfirmed(keyOf(done), "m", minute)
        val dropped = create()
        alarms.recordFire(keyOf(dropped), "m")
        alarms.cancel(keyOf(dropped), "m")
        alarms.softSnooze(keyOf(dropped), "m")
        assertTrue(scheduler.snoozes.isEmpty())
        assertNull(event(keyOf(done))?.latestSnoozeFireAt)
    }

    @Test
    fun aSnoozedFireRepostsAnOpenOccurrenceAndMarksItDeliveredOnlyWhenSeen() = runBlocking {
        val item = create()
        alarms.recordFire(keyOf(item), "m")
        alarms.softSnooze(keyOf(item), "m")
        notifier.deliverable = false
        alarms.snoozedFire(keyOf(item), "m")
        assertNull(event(keyOf(item))?.snoozeDeliveredAt)
        notifier.deliverable = true
        alarms.snoozedFire(keyOf(item), "m")
        assertEquals(now + 10 * minute, event(keyOf(item))?.snoozeDeliveredAt)
        assertEquals(2, notifier.posted.size)
    }

    @Test
    fun aSnoozedFireOnAnOccurrenceTheLogDoesNotHoldPostsItAndLogsItAsSnoozedToNow() = runBlocking {
        val key = ReminderEventKey(9, now - day)
        alarms.snoozedFire(key, "m")
        assertEquals(listOf(key to "m"), notifier.posted)
        assertEquals(ReminderEvent(9, now - day, "m", firedAt = now - day, latestSnoozeFireAt = now, snoozeDeliveredAt = now), event(key))
        alarms.softSnooze(key, "m")
        assertEquals(now + 10 * minute, scheduler.snoozes[key])
        notifier.deliverable = false
        val unseen = ReminderEventKey(8, now - hour)
        alarms.snoozedFire(unseen, "m")
        assertEquals(ReminderEvent(8, now - hour, "m", firedAt = now - hour, latestSnoozeFireAt = now), event(unseen))
    }

    @Test
    fun aSnoozedFireTheLogHoldsNoPendingSnoozeForCountsAsASnoozeToNow() = runBlocking {
        val key = ReminderEventKey(9, now - day)
        alarms.recordFire(key, "m")
        alarms.snoozedFire(key, "m")
        assertEquals(listOf(key to "m"), notifier.posted)
        assertEquals(now to now, event(key)?.let { it.latestSnoozeFireAt to it.snoozeDeliveredAt })
        now += day
        alarms.snoozedFire(key, "m")
        assertEquals(2, notifier.posted.size)
        assertEquals(now to now, event(key)?.let { it.latestSnoozeFireAt to it.snoozeDeliveredAt })
    }

    @Test
    fun aSnoozedFireWhoseLogWriteFailsIsStillPostedAndTicks() = runBlocking {
        val key = ReminderEventKey(9, now - hour)
        fake.failSealWhenAadContains = ReminderEventStore.FILE_NAME
        tickDuring { alarms.snoozedFire(key, "m") }
        fake.failSealWhenAadContains = null
        assertEquals(listOf(key to "m"), notifier.posted)
        assertNull(event(key))
    }

    @Test
    fun aSnoozedFireOnAnOccurrenceWithNoPendingSnoozeThatALaterOneMadeMissedIsDropped() = runBlocking {
        alarms.recordFire(ReminderEventKey(9, now - hour), "later")
        val unlogged = ReminderEventKey(9, now - day)
        alarms.snoozedFire(unlogged, "m")
        assertNull(event(unlogged))
        val logged = ReminderEventKey(9, now - 2 * hour)
        alarms.recordFire(logged, "m")
        alarms.snoozedFire(logged, "m")
        assertTrue(notifier.posted.isEmpty())
        assertNull(event(logged)?.latestSnoozeFireAt)
    }

    @Test
    fun aSnoozedFireOnAResolvedOccurrencePostsNothing() = runBlocking {
        val done = create()
        alarms.recordFire(keyOf(done), "m")
        alarms.softSnooze(keyOf(done), "m")
        alarms.complete(keyOf(done), "m")
        alarms.snoozedFire(keyOf(done), "m")
        val dropped = create()
        alarms.recordFire(keyOf(dropped), "m")
        alarms.softSnooze(keyOf(dropped), "m")
        alarms.cancel(keyOf(dropped), "m")
        alarms.snoozedFire(keyOf(dropped), "m")
        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun anUnreadableLogArmsASwipeUnrecordedAndPostsAReFireUnmarked() = runBlocking {
        val item = create()
        alarms.recordFire(keyOf(item), "m")
        alarms.softSnooze(keyOf(item), "m")
        scheduler.snoozes.clear()
        fake.failOpenWithTransient = true
        now += minute
        alarms.softSnooze(keyOf(item), "m")
        alarms.snoozedFire(keyOf(item), "m")
        fake.failOpenWithTransient = false
        assertEquals(now + 10 * minute, scheduler.snoozes[keyOf(item)])
        assertEquals(listOf(keyOf(item) to "m"), notifier.posted)
        assertEquals(1, event(keyOf(item))?.snoozeChainCount)
        assertNull(event(keyOf(item))?.snoozeDeliveredAt)
    }

    @Test
    fun bootLogsAOneShotThatNeverFiredPostsItWhileItsDayLastsAndRemovesPastOnes() = runBlocking {
        val future = create(at = now + day)
        val lapsed = create(at = now - day, message = "lapsed")
        val today = create(at = now - hour, message = "today")
        val fired = create(at = now - day, message = "fired")
        events.appendFire(ReminderEvent(fired.id, fired.remindAtMillis, "fired", firedAt = now - day))
        assertFailsWith<IllegalArgumentException> { create(at = 0L) }
        alarms.rearmAll()
        assertEquals(listOf(future), reminders.readAll(strict = true))
        assertEquals(setOf(future.id), scheduler.fires.keys)
        assertEquals(listOf(keyOf(today) to "today"), notifier.posted)
        assertEquals(listOf(listOf(ReminderEvent(lapsed.id, lapsed.remindAtMillis, "lapsed", firedAt = lapsed.remindAtMillis))), notifier.missedNotices)
        assertEquals(today.remindAtMillis, event(keyOf(today))?.firedAt)
    }

    @Test
    fun bootLogsEveryPassedCycleOfARecurringItemPostsTheOneStillDueAndNoticesTheRest() = runBlocking {
        val item = create(at = now - 3 * day - 1, rule = daily.copy(anchorMillis = now - 3 * day - 1))
        alarms.rearmAll()
        val next = item.copy(remindAtMillis = item.remindAtMillis + 4 * day)
        assertEquals(listOf(next), reminders.readAll(strict = true))
        assertEquals(next, scheduler.fires[item.id])
        val cycles = List(4) { ReminderEventKey(item.id, item.remindAtMillis + it * day) }
        assertEquals(cycles, events.readAll(strict = true).map { it.key }.sortedBy { it.originalFireMillis })
        assertEquals(listOf(cycles.last() to "m"), notifier.posted)
        assertEquals(listOf(cycles.dropLast(1)), notifier.missedNotices.map { notice -> notice.map { it.key } })
        assertEquals(now - now % day + day + 2 * hour, scheduler.lapseSweepAt)
    }

    @Test
    fun aMissedOccurrenceWhoseReFirePassedUnseenIsNeitherRepostedNorNoticedAtReArm() = runBlocking {
        val item = create(at = now - 3 * hour)
        alarms.recordFire(keyOf(item), "m")
        assertTrue(alarms.snoozeConfirmed(keyOf(item), "m", hour))
        scheduler.snoozes.clear()
        now += 5 * hour
        alarms.rearmAll()
        assertTrue(notifier.posted.isEmpty())
        assertTrue(notifier.missedNotices.isEmpty())
        assertTrue(scheduler.snoozes.isEmpty())
        assertNull(event(keyOf(item))?.snoozeDeliveredAt)
    }

    @Test
    fun bootNoticesSeveralMissedRemindersTogether() = runBlocking {
        val a = create(at = now - day, message = "a")
        val b = create(at = now - 2 * day, message = "b")
        alarms.rearmAll()
        assertEquals(listOf(listOf(keyOf(a), keyOf(b))), notifier.missedNotices.map { notice -> notice.map { it.key } })
        assertTrue(notifier.posted.isEmpty())
        assertEquals(listOf(keyOf(a), keyOf(b)), events.readAll(strict = true).map { it.key })
    }

    @Test
    fun aFireTakesDownTheNotificationAndSnoozeOfItsReminderEarlierOpenOccurrence() = runBlocking {
        val item = create(rule = daily)
        val first = keyOf(item)
        alarms.recordFire(first, "m")
        assertTrue(alarms.snoozeConfirmed(first, "m", 36 * hour))
        notifier.postFire(first, "m")
        now += day
        val other = create(at = now - minute, message = "other")
        alarms.recordFire(keyOf(other), "other")
        assertTrue(alarms.snoozeConfirmed(keyOf(other), "other", hour))
        notifier.postFire(keyOf(other), "other")
        val second = ReminderEventKey(item.id, now)
        notifier.postFire(second, "m")
        alarms.recordFire(second, "m")
        assertEquals(setOf(second, keyOf(other)), notifier.live)
        assertEquals(setOf(keyOf(other)), scheduler.snoozes.keys)
        assertEquals(now - day + 36 * hour, event(first)?.latestSnoozeFireAt)
        assertFalse(alarms.snoozeConfirmed(first, "m", 10 * minute))
    }

    @Test
    fun aFireThatLandsAfterItsCutoffGoesToTheNoticeInsteadOfStayingUp() = runBlocking {
        val item = create(at = now - day - hour)
        notifier.postFire(keyOf(item), "m")
        alarms.recordFire(keyOf(item), "m")
        assertTrue(notifier.live.isEmpty())
        assertEquals(listOf(listOf(keyOf(item))), notifier.missedNotices.map { notice -> notice.map { it.key } })
    }

    @Test
    fun anEarlyDeliveryLeavesTheEarlierOccurrenceUpAndArmsTheSweepForWhenItComesDue() = runBlocking {
        val item = create(at = now - hour, rule = daily.copy(anchorMillis = now - hour))
        alarms.recordFire(keyOf(item), "m")
        assertTrue(alarms.snoozeConfirmed(keyOf(item), "m", day))
        notifier.postFire(keyOf(item), "m")
        val next = reminders.readAll(strict = true).single()
        now = next.remindAtMillis - 5_000L
        alarms.recordFire(keyOf(next), "m")
        assertEquals(setOf(keyOf(item)), notifier.live)
        assertEquals(setOf(keyOf(item)), scheduler.snoozes.keys)
        assertEquals(next.remindAtMillis, scheduler.lapseSweepAt)
    }

    @Test
    fun completingTheNextTimeAheadArmsTheSweepForWhenItComesDue() = runBlocking {
        val item = create(at = now - hour, rule = daily.copy(anchorMillis = now - hour))
        alarms.recordFire(keyOf(item), "m")
        assertTrue(alarms.snoozeConfirmed(keyOf(item), "m", 20 * hour))
        val next = reminders.readAll(strict = true).single()
        assertEquals(Resolution.RECORDED, alarms.complete(keyOf(next), "m"))
        assertEquals(next.remindAtMillis, scheduler.lapseSweepAt)
    }

    @Test
    fun aFullLogKeepsTheNewestPassedCyclesAndTheReminderStillMoves() = runBlocking {
        val cappedRoot = Files.createTempDirectory("notebook").toFile()
        try {
            val cappedReminders = ReminderStore(cappedRoot, fake)
            val capped = ReminderAlarms(cappedReminders, ReminderEventStore(cappedRoot, fake, 4_096L), scheduler, notifier, settings, refresh, { now }, { TimeZone.UTC })
            val item = cappedReminders.create { ReminderItem(it, now - 9 * day - 1, "x".repeat(500), daily.copy(anchorMillis = now - 9 * day - 1)) }
            capped.rearmAll()
            val today = ReminderEventKey(item.id, now - 1)
            assertEquals(listOf(today to "x".repeat(500)), notifier.posted)
            assertEquals(now + day - 1, cappedReminders.readAll(strict = true).single().remindAtMillis)
            assertEquals(now + day - 1, scheduler.fires[item.id]?.remindAtMillis)
        } finally {
            cappedRoot.deleteRecursively()
        }
    }

    @Test
    fun aReminderWriteThatFailsAfterTheLogWriteStillShowsWhatWasLogged() = runBlocking {
        val item = create(at = now - day - 1, rule = daily.copy(anchorMillis = now - day - 1))
        fake.failSealWhenAadContains = ReminderStore.FILE_NAME
        alarms.rearmAll()
        fake.failSealWhenAadContains = null
        assertEquals(listOf(item), reminders.readAll(strict = true))
        assertEquals(listOf(ReminderEventKey(item.id, now - 1) to "m"), notifier.posted)
        alarms.rearmAll()
        assertEquals(now + day - 1, reminders.readAll(strict = true).single().remindAtMillis)
        assertEquals(1, notifier.posted.size)
    }

    @Test
    fun aSnoozeOfAMissedOccurrenceIsRefused() = runBlocking {
        val item = create(at = now - hour)
        alarms.recordFire(keyOf(item), "m")
        now += 3 * hour
        assertFalse(alarms.snoozeConfirmed(keyOf(item), "m", 10 * minute))
        alarms.softSnooze(keyOf(item), "m")
        assertTrue(scheduler.snoozes.isEmpty())
        assertNull(event(keyOf(item))?.latestSnoozeFireAt)
    }

    @Test
    fun aSnoozedReFireOfAnOccurrenceALaterOneMadeMissedIsDropped() = runBlocking {
        val item = create(rule = daily)
        val first = keyOf(item)
        alarms.recordFire(first, "m")
        assertTrue(alarms.snoozeConfirmed(first, "m", 36 * hour))
        now += day
        alarms.recordFire(ReminderEventKey(item.id, now), "m")
        now += 12 * hour
        alarms.snoozedFire(first, "m")
        assertTrue(notifier.posted.none { it.first == first })
    }

    @Test
    fun theLapseSweepTakesDownLapsedNotificationsLeavesUnloggedOnesAndArmsTheNextCutoff() = runBlocking {
        val item = create(at = now - hour)
        val midnight = now - now % day + day
        notifier.postFire(keyOf(item), "m")
        alarms.recordFire(keyOf(item), "m")
        assertEquals(midnight, scheduler.lapseSweepAt)
        now = midnight
        val unlogged = ReminderEventKey(99, now - minute)
        notifier.postFire(unlogged, "x")
        alarms.sweepLapsed()
        assertEquals(setOf(unlogged), notifier.live)
        assertNull(scheduler.lapseSweepAt)
    }

    @Test
    fun bootRearmsAFutureSnoozeAndSkipsResolvedOnes() = runBlocking {
        val open = create()
        alarms.recordFire(keyOf(open), "m")
        alarms.softSnooze(keyOf(open), "m")
        val done = create()
        alarms.recordFire(keyOf(done), "m")
        alarms.softSnooze(keyOf(done), "m")
        events.markCompleted(keyOf(done), now)
        val dropped = create()
        alarms.recordFire(keyOf(dropped), "m")
        alarms.softSnooze(keyOf(dropped), "m")
        events.markCancelled(keyOf(dropped), now)
        scheduler.snoozes.clear()
        alarms.rearmAll()
        assertEquals(mapOf(keyOf(open) to now + 10 * minute), scheduler.snoozes)
        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun bootRepostsAnUnseenPastSnoozeOnceAndLeavesItPendingWhileNotificationsAreOff() = runBlocking {
        val item = create()
        alarms.recordFire(keyOf(item), "m")
        alarms.softSnooze(keyOf(item), "m")
        scheduler.snoozes.clear()
        now += 11 * minute
        notifier.deliverable = false
        alarms.rearmAll()
        assertNull(event(keyOf(item))?.snoozeDeliveredAt)
        notifier.deliverable = true
        alarms.rearmAll()
        assertEquals(now - minute, event(keyOf(item))?.snoozeDeliveredAt)
        alarms.rearmAll()
        assertEquals(2, notifier.posted.size)
        assertTrue(scheduler.snoozes.isEmpty())
    }

    @Test
    fun bootPrunesEventsPastTheRetentionAndKeepsEverythingForZero() = runBlocking {
        val old = ReminderEventKey(1, now - 100 * day)
        val recent = ReminderEventKey(2, now - 10 * day)
        events.appendFire(ReminderEvent(1, old.originalFireMillis, "old", firedAt = old.originalFireMillis))
        events.appendFire(ReminderEvent(2, recent.originalFireMillis, "recent", firedAt = recent.originalFireMillis))
        settings.historyRetentionDays = 0
        alarms.rearmAll()
        assertEquals(2, events.readAll(strict = true).size)
        settings.historyRetentionDays = 90
        alarms.rearmAll()
        assertEquals(listOf(recent), events.readAll(strict = true).map { it.key })
    }

    @Test
    fun bootWithAnUnreadableFileArmsNothingAndKeepsEveryPastDueItemForTheNextSweep() = runBlocking {
        val item = create(at = now - day)
        fake.failOpenWhenAadContains = ReminderStore.FILE_NAME
        alarms.rearmAll()
        assertTrue(scheduler.fires.isEmpty())
        assertTrue(notifier.posted.isEmpty())
        events.appendFire(ReminderEvent(item.id, item.remindAtMillis, "m", firedAt = now - day))
        fake.failOpenWhenAadContains = ReminderEventStore.FILE_NAME
        alarms.rearmAll()
        fake.failOpenWhenAadContains = null
        assertEquals(listOf(item), reminders.readAll(strict = true))
        assertTrue(notifier.posted.isEmpty())
        alarms.rearmAll()
        assertEquals(emptyList(), reminders.readAll(strict = true))
        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun disarmDropsTheFireAlarmEveryPendingSnoozeAndEveryNotificationAndABootRevivesNone() = runBlocking {
        val item = create(rule = daily)
        alarms.arm(item)
        alarms.postFire(keyOf(item), "m")
        alarms.recordFire(keyOf(item), "m")
        alarms.softSnooze(keyOf(item), "m")
        val next = reminders.readAll(strict = true).single()
        alarms.postFire(keyOf(next), "m")
        alarms.recordFire(keyOf(next), "m")
        alarms.softSnooze(keyOf(next), "m")
        assertEquals(setOf(keyOf(item), keyOf(next)), scheduler.snoozes.keys)
        alarms.disarm(item.id)
        reminders.remove(item.id)
        assertTrue(scheduler.fires.isEmpty())
        assertTrue(scheduler.snoozes.isEmpty())
        assertTrue(notifier.live.isEmpty())
        assertTrue(events.readAll(strict = true).all { it.latestSnoozeFireAt == null })
        now += day
        alarms.rearmAll()
        assertTrue(scheduler.snoozes.isEmpty())
        assertEquals(2, notifier.posted.size)
    }

    @Test
    fun aLateFireOfAnOccurrenceTheUserEditedAwayLeavesTheItemAndItsAlarmAlone() = runBlocking {
        val item = create()
        val moved = item.copy(remindAtMillis = now + day, message = "moved")
        assertTrue(reminders.replace(moved))
        alarms.arm(moved)
        alarms.recordFire(keyOf(item), "m")
        assertEquals(listOf(moved), reminders.readAll(strict = true))
        assertEquals(moved, scheduler.fires[item.id])
        assertEquals("m", event(keyOf(item))?.message)
    }

    @Test
    fun bootDoesNotShowALoggedOccurrenceAgainButPostsTheCycleThatPassedSince() = runBlocking {
        val item = create(rule = daily)
        events.appendFire(ReminderEvent(item.id, item.remindAtMillis, "m", firedAt = now))
        events.markCompleted(keyOf(item), now)
        now += day + 1
        alarms.rearmAll()
        assertEquals(listOf(ReminderEventKey(item.id, item.remindAtMillis + day) to "m"), notifier.posted)
        assertEquals(item.remindAtMillis + 2 * day, scheduler.fires[item.id]?.remindAtMillis)
        assertEquals(item.remindAtMillis, event(keyOf(item))?.completedAt)
    }

    @Test
    fun bootLogsAMissedOccurrenceBeforeTouchingItsItemAndKeepsTheItemWhenTheLogRefuses() = runBlocking {
        val item = create(at = now - day)
        fake.failSealWhenAadContains = ReminderEventStore.FILE_NAME
        alarms.rearmAll()
        fake.failSealWhenAadContains = null
        assertEquals(listOf(item), reminders.readAll(strict = true))
        assertTrue(notifier.posted.isEmpty())
        alarms.rearmAll()
        assertEquals(emptyList(), reminders.readAll(strict = true))
        assertEquals(listOf(listOf(keyOf(item))), notifier.missedNotices.map { notice -> notice.map { it.key } })
    }

    @Test
    fun bootMovesAnItemOnlyWhenItStillMatchesTheSnapshotItPlannedFrom() = runBlocking {
        val item = create(at = now - day, rule = daily.copy(anchorMillis = now - day))
        val racing = ReminderStore(root, fake)
        // The clock is read after the snapshot and before the update, so an edit landing there races the plan.
        var edit: (() -> Unit)? = { runBlocking { racing.replace(item.copy(message = "edited")) } }
        val raced = ReminderAlarms(reminders, events, scheduler, notifier, settings, refresh, { edit?.invoke(); edit = null; now }, { TimeZone.UTC })
        raced.rearmAll()
        val stored = reminders.readAll(strict = true).single()
        assertEquals("edited", stored.message)
        assertEquals(now - day, stored.remindAtMillis)
        assertTrue(scheduler.fires.isEmpty())
        val today = ReminderEventKey(item.id, now)
        assertEquals(listOf(today to "m"), notifier.posted)
        assertEquals(listOf(listOf(keyOf(item))), notifier.missedNotices.map { notice -> notice.map { it.key } })
        raced.rearmAll()
        assertEquals(now + day, reminders.readAll(strict = true).single().remindAtMillis)
        assertEquals(listOf(today to "m"), notifier.posted)
        assertEquals(1, notifier.missedNotices.size)
    }

    @Test
    fun bootArmsAFutureItemOnlyWhenItStillMatchesTheSnapshot() = runBlocking {
        val moved = create(at = now + hour)
        val deleted = create(at = now + 2 * hour)
        val kept = create(at = now + 3 * hour)
        val armedBySweep = mutableListOf<ReminderItem>()
        var writes = -1L
        // As above, through the clock: both writers land after the snapshot and before any item's turn.
        var race: (() -> Unit)? = {
            runBlocking {
                alarms.edit(moved.id) { it.copy(remindAtMillis = now + 3 * day) }
                alarms.delete(deleted.id)
            }
            scheduler.beforeScheduleFire = { armedBySweep += it }
            writes = reminders.revision.value
        }
        val raced = ReminderAlarms(reminders, events, scheduler, notifier, settings, refresh, { race?.invoke(); race = null; now }, { TimeZone.UTC })
        raced.rearmAll()
        // Only the unchanged item: a race that landed before the snapshot would have the sweep arm the moved one too.
        assertEquals(listOf(kept), armedBySweep)
        assertEquals(listOf(moved.copy(remindAtMillis = now + 3 * day), kept), reminders.readAll(strict = true))
        assertEquals(reminders.readAll(strict = true), scheduler.fires.values.sortedBy { it.id })
        assertEquals(writes, reminders.revision.value)
    }

    @Test
    fun bootArmsAFutureItemAsItReadItWhenTheStoreCannotBeReadAgain() = runBlocking {
        val item = create(at = now + hour)
        var refusedReads = 0
        var fail: (() -> Unit)? = {
            fake.beforeOpen = { if (it.contains(ReminderStore.FILE_NAME)) refusedReads++ }
            fake.failOpenWithTransient = true
        }
        val raced = ReminderAlarms(reminders, events, scheduler, notifier, settings, refresh, { fail?.invoke(); fail = null; now }, { TimeZone.UTC })
        raced.rearmAll()
        fake.failOpenWithTransient = false
        assertEquals(item, scheduler.fires[item.id])
        assertEquals(1, refusedReads)
    }

    @Test
    fun aFireSettlesUnderTheLockSoAConcurrentEditOfAnotherFieldSurvives() = runBlocking {
        val item = create(rule = daily)
        val editor = ReminderStore(root, fake)
        val edits = launch(Dispatchers.Default) {
            repeat(200) { n ->
                editor.update(item.id) { current -> current.copy(message = "edit $n") }
            }
        }
        repeat(20) {
            val current = reminders.readAll(strict = true).single()
            alarms.recordFire(keyOf(current), current.message)
        }
        edits.join()
        val stored = reminders.readAll(strict = true).single()
        assertEquals("edit 199", stored.message)
        assertEquals(item.remindAtMillis + 20 * day, stored.remindAtMillis)
        assertEquals(stored.remindAtMillis, scheduler.fires[item.id]?.remindAtMillis)
    }

    @Test
    fun aLaterOccurrenceTakesDownTheEarlierOnesNotificationAndSnoozeAndIsActedOnAlone() = runBlocking {
        val item = create(rule = daily)
        alarms.postFire(keyOf(item), "m")
        alarms.recordFire(keyOf(item), "m")
        alarms.softSnooze(keyOf(item), "m")
        val next = reminders.readAll(strict = true).single()
        now += day
        alarms.postFire(keyOf(next), "m")
        alarms.recordFire(keyOf(next), "m")
        assertEquals(setOf(keyOf(next)), notifier.live)
        assertTrue(scheduler.snoozes.isEmpty())
        alarms.complete(keyOf(next), "m")
        assertTrue(notifier.live.isEmpty())
        assertNull(event(keyOf(item))?.completedAt)
    }

    @Test
    fun completingAnOccurrenceTheItemStillSitsOnMovesTheItemPastIt() = runBlocking {
        val once = create(at = now + day)
        alarms.arm(once)
        alarms.complete(keyOf(once), "m")
        assertTrue(scheduler.fires.isEmpty())
        assertEquals(emptyList(), reminders.readAll(strict = true))
        assertEquals(now, event(keyOf(once))?.completedAt)

        val weekly = create(at = now + day, rule = daily.copy(anchorMillis = now + day))
        alarms.arm(weekly)
        alarms.cancel(keyOf(weekly), "m")
        assertEquals(now + 2 * day, scheduler.fires[weekly.id]?.remindAtMillis)
        assertEquals(now + 2 * day, reminders.readAll(strict = true).single().remindAtMillis)
        assertEquals(now, event(keyOf(weekly))?.cancelledAt)
    }

    @Test
    fun rearmAllIsIdempotent() = runBlocking {
        val future = create(at = now + day)
        val missed = create(at = now - day)
        alarms.rearmAll()
        val firesAfterOne = scheduler.fires.toMap()
        val eventsAfterOne = events.readAll(strict = true)
        alarms.rearmAll()
        assertEquals(firesAfterOne, scheduler.fires)
        assertEquals(eventsAfterOne, events.readAll(strict = true))
        assertTrue(notifier.posted.isEmpty())
        assertEquals(listOf(listOf(keyOf(missed))), notifier.missedNotices.map { notice -> notice.map { it.key } })
        assertEquals(listOf(future), reminders.readAll(strict = true))
    }

    @Test
    fun everyWriteTicksTheRefreshSignal() = runBlocking {
        val item = create()
        tickDuring { alarms.recordFire(keyOf(item), "m") }
        tickDuring { alarms.softSnooze(keyOf(item), "m") }
        tickDuring { alarms.snoozedFire(keyOf(item), "m") }
        tickDuring { alarms.snoozeConfirmed(keyOf(item), "m", minute) }
        tickDuring { alarms.complete(keyOf(item), "m") }
        tickDuring { alarms.switchStatus(keyOf(item), completed = false) }
        tickDuring { alarms.deleteOccurrence(keyOf(item)) }
        val other = create(at = now + day)
        tickDuring { alarms.delete(other.id) }
        create(at = now - day)
        tickDuring { alarms.rearmAll() }
    }

    // Subscribed before the block runs, so the tick it waits for cannot be emitted first and dropped.
    private suspend fun CoroutineScope.tickDuring(block: suspend () -> Unit) {
        val tick = launch(start = CoroutineStart.UNDISPATCHED) { refresh.events.first() }
        block()
        withTimeout(5_000L) { tick.join() }
    }

    @Test
    fun theSetNoticeGoesWhenTheMarkIsClearedTheReminderIsDeletedOrItFires() = runBlocking {
        suspend fun marked(at: Long) = reminders.create { ReminderItem(it, at, "m", timeToCheck = true) }.also(notifier::postSetNotice)
        val edited = marked(now + hour)
        alarms.edit(edited.id) { it.copy(message = "still to check") }
        assertEquals(listOf(edited.id), notifier.setNotices.keys.toList())
        alarms.edit(edited.id) { it.copy(timeToCheck = false) }
        assertTrue(notifier.setNotices.isEmpty())
        val deleted = marked(now + hour)
        assertTrue(alarms.delete(deleted.id))
        assertTrue(notifier.setNotices.isEmpty())
        val fired = marked(now - 500L)
        alarms.recordFire(keyOf(fired), fired.message)
        assertTrue(notifier.setNotices.isEmpty())
    }

    @Test
    fun theSetNoticeGoesWhenAMarkedReminderIsCompletedOrMarkedMissedAheadOrDisarmed() = runBlocking {
        suspend fun marked() = reminders.create { ReminderItem(it, now + hour, "m", timeToCheck = true) }.also(notifier::postSetNotice)
        val completed = marked()
        alarms.complete(keyOf(completed), "m")
        assertEquals(emptyList(), reminders.readAll(strict = true).filter { it.id == completed.id })
        assertTrue(completed.id !in notifier.setNotices)
        val disarmed = marked()
        alarms.disarm(disarmed.id)
        assertTrue(disarmed.id !in notifier.setNotices)
        val missed = marked()
        alarms.cancel(keyOf(missed), "m")
        assertTrue(missed.id !in notifier.setNotices)
        val former = marked()
        alarms.disarmFormer(listOf(former.id), emptyList(), emptyList())
        assertTrue(notifier.setNotices.isEmpty())
    }

    @Test
    fun aMarkedReminderTheRearmDropsLosesItsNotice() = runBlocking {
        val passed = reminders.create { ReminderItem(it, now - day, "m", timeToCheck = true) }.also(notifier::postSetNotice)
        alarms.rearmAll()
        assertEquals(emptyList(), reminders.readAll(strict = true))
        assertTrue(passed.id !in notifier.setNotices)
    }

    @Test
    fun aMarkedRepeatingReminderLosesItsNoticeWhenItGoesOff() = runBlocking {
        val item = reminders.create { ReminderItem(it, now - 500L, "m", daily, timeToCheck = true) }.also(notifier::postSetNotice)
        alarms.recordFire(keyOf(item), item.message)
        assertEquals(1, reminders.readAll(strict = true).size)
        assertTrue(notifier.setNotices.isEmpty())
    }

    @Test
    fun aRearmTakesDownNoticesWhoseReminderIsGoneOrWhoseFormNoLongerMatchesItsMark() = runBlocking {
        val kept = reminders.create { ReminderItem(it, now + hour, "kept", timeToCheck = true) }.also(notifier::postSetNotice)
        val set = reminders.create { ReminderItem(it, now + hour, "set") }.also(notifier::postSetNotice)
        val cleared = reminders.create { ReminderItem(it, now + hour, "cleared") }
        notifier.postSetNotice(cleared.copy(timeToCheck = true))
        val marked = reminders.create { ReminderItem(it, now + hour, "marked", timeToCheck = true) }
        notifier.postSetNotice(marked.copy(timeToCheck = false))
        notifier.postSetNotice(ReminderItem(marked.id + 100, now + hour, "gone", timeToCheck = true))
        alarms.rearmAll()
        assertEquals(listOf(kept.id, set.id), notifier.setNotices.keys.toList())
    }

    @Test
    fun aRearmLeavesTheNoticeOfAReminderMadeWhileItRuns() = runBlocking {
        create(now + hour)
        var made: ReminderItem? = null
        scheduler.beforeScheduleFire = {
            if (made == null) {
                made = runBlocking { reminders.create { id -> ReminderItem(id, now + hour, "new", timeToCheck = true) } }.also(notifier::postSetNotice)
            }
        }
        alarms.rearmAll()
        assertEquals(listOf(made!!.id), notifier.setNotices.keys.toList())
    }

    @Test
    fun anEditWhoseAlarmCallFailsStillTakesDownTheNoticeOfAnUnmarkedResult() = runBlocking {
        val item = reminders.create { ReminderItem(it, now + hour, "m", timeToCheck = true) }.also(notifier::postSetNotice)
        scheduler.beforeScheduleFire = { throw IllegalStateException("alarm refused") }
        assertFailsWith<IllegalStateException> { alarms.edit(item.id) { it.copy(timeToCheck = false) } }
        assertFalse(reminders.readAll(strict = true).single().timeToCheck)
        assertTrue(notifier.setNotices.isEmpty())
    }

    @Test
    fun aNoticeCancelOrListingThatFailsLeavesOnlyTheNoticeUp() = runBlocking<Unit> {
        val edited = reminders.create { ReminderItem(it, now + hour, "m", timeToCheck = true) }.also(notifier::postSetNotice)
        val fired = reminders.create { ReminderItem(it, now - 500L, "m") }.also(notifier::postSetNotice)
        val later = create(now + day)
        notifier.cancelSetNoticeThrows = true
        assertFalse(alarms.edit(edited.id) { it.copy(timeToCheck = false) }!!.timeToCheck)
        alarms.recordFire(keyOf(fired), fired.message)
        assertNotNull(event(keyOf(fired)))
        notifier.cancelSetNoticeThrows = false
        notifier.postedSetNoticesThrows = true
        scheduler.fires.clear()
        alarms.rearmAll()
        assertEquals(later, scheduler.fires[later.id])
    }

    @Test
    fun aRearmWhoseNoticeCancelFailsStillTakesDownTheOtherNoticesAndRefreshes() = runBlocking<Unit> {
        val gone = ReminderItem(100, now + hour, "gone")
        notifier.postSetNotice(gone)
        notifier.postSetNotice(ReminderItem(101, now + hour, "also gone"))
        notifier.cancelSetNoticeFailsFor += gone.id
        tickDuring { alarms.rearmAll() }
        assertEquals(listOf(gone.id), notifier.setNotices.keys.toList())
    }

    @Test
    fun aDeleteWhoseNotificationCancelsFailStillDeletes() = runBlocking<Unit> {
        val item = create(now + hour).also(notifier::postSetNotice)
        notifier.cancelFireThrows = true
        tickDuring { assertTrue(alarms.delete(item.id)) }
        assertEquals(emptyList(), reminders.readAll(strict = true))
        assertTrue(notifier.setNotices.isEmpty())
        notifier.cancelFireThrows = false
        val other = create(now + hour).also(notifier::postSetNotice)
        notifier.postFire(keyOf(other), other.message)
        notifier.cancelSetNoticeThrows = true
        tickDuring { assertTrue(alarms.delete(other.id)) }
        assertEquals(emptyList(), reminders.readAll(strict = true))
        assertTrue(notifier.live.isEmpty())
    }

    @Test
    fun aDisarmOfFormerRemindersWhoseNotificationCancelsFailDisarmsTheRest() = runBlocking<Unit> {
        val first = create(now + hour).also(notifier::postSetNotice)
        val second = create(now + day).also(notifier::postSetNotice)
        alarms.arm(first)
        alarms.arm(second)
        scheduler.scheduleSnooze(keyOf(first), "m", now + hour)
        notifier.cancelFireThrows = true
        alarms.disarmFormer(listOf(first.id, second.id), listOf(keyOf(first)), listOf(keyOf(first)))
        assertTrue(scheduler.fires.isEmpty())
        assertTrue(notifier.setNotices.isEmpty())
        assertTrue(scheduler.snoozes.isEmpty())
        notifier.cancelFireThrows = false
        val third = create(now + hour).also(notifier::postSetNotice)
        val fourth = create(now + day)
        alarms.arm(third)
        alarms.arm(fourth)
        notifier.postFire(keyOf(third), third.message)
        notifier.postFire(keyOf(fourth), fourth.message)
        notifier.cancelSetNoticeThrows = true
        alarms.disarmFormer(listOf(third.id, fourth.id), emptyList(), emptyList())
        assertTrue(scheduler.fires.isEmpty())
        assertTrue(notifier.live.isEmpty())
    }

    @Test
    fun aCompletionWhoseNoticeCancelFailsIsStillRecorded() = runBlocking<Unit> {
        val item = reminders.create { ReminderItem(it, now + hour, "m") }.also(notifier::postSetNotice)
        notifier.cancelSetNoticeThrows = true
        assertEquals(Resolution.RECORDED, alarms.complete(keyOf(item), "m"))
        assertEquals(emptyList(), reminders.readAll(strict = true))
        assertNotNull(event(keyOf(item))?.completedAt)
    }
}
