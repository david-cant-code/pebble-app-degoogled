package com.anopticlabs.gravel.notebook.backup

import com.anopticlabs.gravel.notebook.storage.FakeCryptographer
import com.anopticlabs.gravel.notebook.storage.softwareCrypto
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [planChecks] over states, and [AutoBackupChecks] over a real store with the notices, scheduler and alarm faked. */
class AutoBackupChecksTest {

    private val t0 = 1_700_000_000_000L
    private val on = AutoBackupState(enabled = true, folder = "tree:a", lastConfirmedAtMillis = t0, key = KeyChecks(createdAtMillis = t0))

    @Test
    fun aRecentBackupWarnsOfNothingAndWakesWhenItWouldBeADayOld() {
        val plan = planChecks(on, t0 + 2 * HOUR)
        assertFalse(plan.warnStale)
        assertFalse(plan.runSoon)
        assertEquals(t0 + DAY, plan.alarmAtMillis)
    }

    @Test
    fun aDayWithoutABackupAsksForARunAndWarnsOnlyAnHourLater() {
        val first = planChecks(on, t0 + DAY + 1)
        assertTrue(first.runSoon)
        assertFalse(first.warnStale)
        assertEquals(t0 + DAY + 1, first.state.staleSinceMillis)
        assertEquals(t0 + DAY + 1 + HOUR, first.alarmAtMillis)

        val inGrace = planChecks(first.state, t0 + DAY + 30 * MINUTE)
        assertFalse(inGrace.warnStale)
        assertFalse(inGrace.runSoon)

        val warned = planChecks(inGrace.state, t0 + DAY + 1 + HOUR)
        assertTrue(warned.warnStale)
        assertTrue(warned.staleActive)
        assertEquals(t0 + DAY + 1 + HOUR + DAY, warned.alarmAtMillis)
    }

    @Test
    fun theWarningComesBackOnceADayWhileNoBackupIsConfirmed() {
        var s = planChecks(on, t0 + DAY).state
        s = planChecks(s, t0 + DAY + HOUR).also { assertTrue(it.warnStale) }.state
        val sameDay = planChecks(s, t0 + DAY + 10 * HOUR)
        assertFalse(sameDay.warnStale)
        assertTrue(sameDay.staleActive)
        assertTrue(planChecks(sameDay.state, t0 + 2 * DAY + HOUR).warnStale)
    }

    @Test
    fun aConfirmedBackupEndsTheStretchAndItsMarksDoNotCarryOver() {
        var s = planChecks(on, t0 + DAY).state
        s = planChecks(s, t0 + DAY + HOUR).state
        s = s.copy(lastConfirmedAtMillis = t0 + DAY + 2 * HOUR)
        val fresh = planChecks(s, t0 + DAY + 3 * HOUR)
        assertFalse(fresh.staleActive)
        assertNull(fresh.state.staleSinceMillis)
        assertNull(fresh.state.staleWarnedAtMillis)
        // Marks from an earlier stretch, as an interrupted check could leave them, restart the grace.
        val leftover = s.copy(staleSinceMillis = t0 + DAY, staleWarnedAtMillis = t0 + DAY + HOUR)
        val next = planChecks(leftover, t0 + 2 * DAY + 2 * HOUR + 1)
        assertFalse(next.warnStale)
        assertTrue(next.runSoon)
    }

    @Test
    fun switchedOffOrNotSetUpNothingWarns() {
        assertFalse(planChecks(on.copy(enabled = false), t0 + 5 * DAY).runSoon)
        assertFalse(planChecks(on.copy(enabled = false), t0 + 5 * DAY).warnStale)
        val notSetUp = planChecks(AutoBackupState(lastConfirmedAtMillis = t0), t0 + 5 * DAY)
        assertFalse(notSetUp.runSoon)
        assertFalse(notSetUp.keyDue)
        assertNull(notSetUp.alarmAtMillis)
    }

    @Test
    fun keyRemindersComeAtDay14ThenThreeMonthsThenEverySixFromSetup() {
        val k = KeyChecks(createdAtMillis = t0)
        assertEquals(listOf(t0 + 14 * DAY, t0 + 105 * DAY, t0 + 287 * DAY, t0 + 469 * DAY), keyCheckPoints(k)!!.take(4).toList())
        val checkedOnce = KeyChecks(createdAtMillis = t0, checkedAtMillis = t0 + 20 * DAY, checks = 1)
        assertEquals(listOf(t0 + 111 * DAY, t0 + 293 * DAY), keyCheckPoints(checkedOnce)!!.take(2).toList())
        val checkedTwice = checkedOnce.copy(checkedAtMillis = t0 + 120 * DAY, checks = 2)
        assertEquals(t0 + 302 * DAY, keyCheckPoints(checkedTwice)!!.first())
        assertNull(keyCheckPoints(KeyChecks()))
    }

    @Test
    fun eachPointPostsOneReminderEvenWhenAnEarlierOneWentUnanswered() {
        val off = on.copy(enabled = false)
        val before = planChecks(off, t0 + 13 * DAY)
        assertFalse(before.remindKey)
        assertEquals(t0 + 14 * DAY, before.alarmAtMillis)
        val due = planChecks(before.state, t0 + 14 * DAY)
        assertTrue(due.remindKey)
        assertTrue(due.keyDue)
        val again = planChecks(due.state, t0 + 20 * DAY)
        assertFalse(again.remindKey)
        assertTrue(again.keyDue)
        assertTrue(planChecks(again.state, t0 + 105 * DAY).remindKey)
    }

    @Test
    fun notNowBringsOneMoreReminderAWeekLater() {
        val due = planChecks(on.copy(enabled = false), t0 + 14 * DAY).state
        val snoozed = due.copy(key = due.key.copy(notNowPointMillis = t0 + 14 * DAY, notNowUntilMillis = t0 + 21 * DAY))
        val waiting = planChecks(snoozed, t0 + 15 * DAY)
        assertFalse(waiting.remindKey)
        assertEquals(t0 + 21 * DAY, waiting.alarmAtMillis)
        val back = planChecks(waiting.state, t0 + 21 * DAY)
        assertTrue(back.remindKey)
        assertFalse(planChecks(back.state, t0 + 30 * DAY).remindKey)
    }

    private val root: File = Files.createTempDirectory("notebook-checks").toFile()
    private val crypto = FakeCryptographer(softwareCrypto())
    private val store = AutoBackupStore(root, crypto)
    private val notices = FakeNotices()
    private val scheduler = FakeAutoScheduler()
    private val alarm = FakeCheckAlarm()
    private var now = t0
    private val checks = AutoBackupChecks(store, notices, scheduler, alarm, clock = { now })

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun aCheckAsksForARunTellsOnceAndSetsTheAlarm() = runBlocking<Unit> {
        store.updateState { on }
        now = t0 + DAY
        checks.check()
        assertEquals(1, scheduler.soon)
        assertTrue(notices.stale.isEmpty())
        assertEquals(t0 + DAY + HOUR, alarm.at)
        now = t0 + DAY + HOUR
        checks.check()
        checks.check()
        assertEquals(listOf(t0), notices.stale, "told once")
        assertEquals(t0 + DAY + HOUR, store.readState(strict = true).staleWarnedAtMillis)
    }

    @Test
    fun notNowWorksOncePerReminderAndARightKeyMovesTheSchedule() = runBlocking<Unit> {
        store.updateState { on.copy(enabled = false) }
        now = t0 + 14 * DAY
        checks.check()
        assertEquals(1, notices.keyReminders)
        checks.notNow()
        assertEquals(t0 + 21 * DAY, store.readState(strict = true).key.notNowUntilMillis)
        assertEquals(t0 + 21 * DAY, alarm.at)
        now = t0 + 16 * DAY
        checks.notNow()
        assertEquals(t0 + 21 * DAY, store.readState(strict = true).key.notNowUntilMillis, "Not now once per reminder")
        val clearsBefore = notices.keyClears
        checks.keyChecked()
        val key = store.readState(strict = true).key
        assertEquals(t0 + 16 * DAY, key.checkedAtMillis)
        assertEquals(1, key.checks)
        assertNull(key.notNowPointMillis)
        assertEquals(t0 + 107 * DAY, alarm.at)
        assertTrue(notices.keyClears > clearsBefore, "the due reminder comes down")
    }

    @Test
    fun aConfirmedBackupTakesTheWarningDownAndNotSetUpCancelsTheAlarm() = runBlocking<Unit> {
        store.updateState { on.copy(staleSinceMillis = t0 + DAY) }
        now = t0 + DAY + HOUR
        checks.check()
        assertEquals(1, notices.stale.size)
        val clears = notices.staleClears
        store.updateState { it.copy(lastConfirmedAtMillis = now) }
        checks.check()
        assertTrue(notices.staleClears > clears)
        store.updateState { AutoBackupState() }
        checks.check()
        assertNull(alarm.at)
        assertTrue(alarm.cancels > 0)
    }

    @Test
    fun aNoticeWhoseWriteFailsIsToldAgainAndTheCheckComesBackInAnHour() = runBlocking<Unit> {
        store.updateState { on.copy(staleSinceMillis = t0 + DAY) }
        now = t0 + DAY + HOUR
        crypto.failSealWhenAadContains = AutoBackupStore.STATE_FILE
        checks.check()
        assertEquals(1, notices.stale.size, "told before the write")
        assertEquals(now + HOUR, alarm.at)
        crypto.failSealWhenAadContains = null
        checks.check()
        assertEquals(2, notices.stale.size, "told again, as nothing recorded it")
        checks.check()
        assertEquals(2, notices.stale.size)
    }

    @Test
    fun aStretchWithNoMarkIsPastItsGraceOnceTheBackupIsADayAndAnHourOld() {
        assertFalse(planChecks(on, t0 + DAY).warnUnmarked)
        assertTrue(planChecks(on, t0 + DAY + HOUR).warnUnmarked)
        assertFalse(planChecks(on.copy(staleSinceMillis = t0 + DAY), t0 + DAY + HOUR).warnUnmarked, "a stored mark brings the warning itself")
        assertTrue(planChecks(on.copy(staleSinceMillis = t0 - DAY), t0 + DAY + HOUR).warnUnmarked, "a mark from before the last backup is none")
        assertFalse(planChecks(on.copy(enabled = false), t0 + 2 * DAY).warnUnmarked)
    }

    @Test
    fun aCheckWhoseMarkCannotBeWrittenStillAsksForARunAndWarnsOnceTheGraceIsOver() = runBlocking<Unit> {
        store.updateState { on }
        crypto.failSealWhenAadContains = AutoBackupStore.STATE_FILE
        now = t0 + DAY
        checks.check()
        assertEquals(1, scheduler.soon)
        assertTrue(notices.stale.isEmpty(), "inside the grace")
        assertEquals(now + HOUR, alarm.at)
        now = t0 + DAY + HOUR
        checks.check()
        assertEquals(2, scheduler.soon)
        assertEquals(listOf(t0), notices.stale.toList())
        checks.check()
        assertEquals(2, notices.stale.size, "again at each check, as nothing records it")
        crypto.failSealWhenAadContains = null
        checks.check()
        assertEquals(now, store.readState(strict = true).staleSinceMillis, "the first write that lands starts the stored stretch")
        assertEquals(2, notices.stale.size)
        now += HOUR
        checks.check()
        assertEquals(3, notices.stale.size)
    }

    @Test
    fun aCheckWhoseWriteFailsAsksForARunOnlyWhereItsPlanDid() = runBlocking<Unit> {
        store.updateState { on.copy(staleSinceMillis = t0 + DAY) }
        now = t0 + DAY + HOUR
        crypto.failSealWhenAadContains = AutoBackupStore.STATE_FILE
        checks.check()
        assertEquals(1, scheduler.soon, "the warning's run request")
        assertEquals(1, notices.stale.size)
        crypto.failSealWhenAadContains = null
        store.updateState { on.copy(enabled = false) }
        crypto.failSealWhenAadContains = AutoBackupStore.STATE_FILE
        now = t0 + 14 * DAY
        checks.check()
        assertEquals(1, notices.keyReminders, "the write that failed was the key reminder's")
        assertEquals(1, scheduler.soon)
        assertEquals(1, notices.stale.size)
    }

    @Test
    fun aNoticeThatThrowsLeavesTheAlarmSet() = runBlocking<Unit> {
        val throwing = object : AutoBackupNotices by notices {
            override fun keyCheckDue() = throw IllegalStateException("no notification manager")
        }
        val c = AutoBackupChecks(store, throwing, scheduler, alarm, clock = { now })
        store.updateState { on.copy(enabled = false) }
        now = t0 + 14 * DAY
        c.check()
        assertEquals(t0 + 105 * DAY, alarm.at)
    }

    @Test
    fun timesLaterThanTheClockAreTakenAsNow() {
        val ahead = on.copy(lastConfirmedAtMillis = t0 + 300 * DAY, key = KeyChecks(createdAtMillis = t0 + 300 * DAY))
        val plan = planChecks(ahead, t0)
        assertEquals(t0, plan.state.lastConfirmedAtMillis)
        assertEquals(t0 + DAY, plan.alarmAtMillis, "the 24 hours count from now, before the key's first point")
        assertEquals(t0 + 14 * DAY, keyCheckPointsAround(ahead.key, t0).second)
    }

    @Test
    fun aKeysTimeLaterThanTheClockIsReadAsNowAndStaysStoredAsSetupWroteIt() {
        val plan = planChecks(on.copy(key = KeyChecks(createdAtMillis = t0 + 300 * DAY)), t0)
        assertEquals(t0 + 300 * DAY, plan.state.key.createdAtMillis)
        assertEquals(t0 + 14 * DAY, keyCheckPointsAround(plan.state.key, t0).second)
        assertEquals(t0 + 314 * DAY, keyCheckPointsAround(plan.state.key, t0 + 301 * DAY).second, "it counts once the clock is past it")
    }

    @Test
    fun anUnreadableStateSkipsTheCheckAndBringsItBackInAnHour() = runBlocking<Unit> {
        File(root, AutoBackupStore.STATE_FILE).writeBytes(byteArrayOf(1, 2, 3))
        checks.check()
        assertEquals(0, scheduler.soon)
        assertEquals(now + HOUR, alarm.at)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
