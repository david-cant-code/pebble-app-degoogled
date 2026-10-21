package com.anopticlabs.gravel.notebook.alarms

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.anopticlabs.gravel.notebook.alarms.ReminderIntents.putOccurrence
import com.anopticlabs.gravel.notebook.notebookModule
import com.anopticlabs.gravel.notebook.reminders.RecurrenceFrequency
import com.anopticlabs.gravel.notebook.reminders.RecurrenceRule
import com.anopticlabs.gravel.notebook.reminders.ReminderEvent
import com.anopticlabs.gravel.notebook.reminders.ReminderEventKey
import com.anopticlabs.gravel.notebook.reminders.ReminderItem
import com.anopticlabs.gravel.notebook.reminders.ReminderRefreshSignal
import com.anopticlabs.gravel.notebook.reminders.ReminderSettings
import com.anopticlabs.gravel.notebook.storage.ReminderEventStore
import com.anopticlabs.gravel.notebook.storage.ReminderStore
import com.anopticlabs.gravel.notebook.storage.appKeyCrypto
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The alarm stack against the real AlarmManager, NotificationManager and Keystore, over a
 * throwaway root beside the Notebook root, with reminder ids well above any the app has minted.
 * The broadcast tests swap the app's coordinator for this one through Koin for their duration.
 *
 * Run with:
 * adb shell am instrument -w -e class \
 *   com.anopticlabs.gravel.notebook.alarms.ReminderAlarmsDeviceTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class ReminderAlarmsDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.filesDir, "notebook-alarms-test")
    private val crypto = appKeyCrypto()
    private val reminders = ReminderStore(root, crypto)
    private val events = ReminderEventStore(root, crypto)
    private val scheduler = AndroidReminderAlarms(context)
    private val notifier = AndroidReminderNotifier(context) { "test-token" }
    private val settings = object : ReminderSettings {
        override val swipeSnoozeMinutes = 10
        override val historyRetentionDays = 90
    }
    private val alarms = ReminderAlarms(reminders, events, scheduler, notifier, settings, ReminderRefreshSignal())
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val daily = RecurrenceRule(RecurrenceFrequency.DAILY, 1, 1_700_000_000_000L)

    @Before
    fun seedIdsAboveTheAppsOwn() = runBlocking<Unit> {
        root.deleteRecursively()
        reminders.addAllSkippingExistingIds(listOf(ReminderItem(ID_FLOOR, 1L, "floor")))
        reminders.removeByIds(listOf(ID_FLOOR))
    }

    @After
    fun cleanUp() = runBlocking<Unit> {
        for (id in ID_FLOOR..ID_FLOOR + 20) alarms.disarm(id)
        scheduler.cancelLapseSweep()
        for (posted in manager.activeNotifications) {
            if (posted.tag?.startsWith(AndroidReminderNotifier.TAG_SUMMARY) == true) manager.cancel(posted.tag, posted.id)
        }
        notifier.cancelCompletionsToReview()
        loadKoinModules(notebookModule)
        root.deleteRecursively()
    }

    private suspend fun create(at: Long, rule: RecurrenceRule? = null, message: String = "test reminder") =
        reminders.create { ReminderItem(it, at, message, rule) }

    private fun keyOf(item: ReminderItem) = ReminderEventKey(item.id, item.remindAtMillis)

    private fun mine(key: ReminderEventKey): StatusBarNotification? =
        manager.activeNotifications.firstOrNull { it.tag == AndroidReminderNotifier.fireTag(key) && it.id == key.reminderId }

    private fun tagged(tag: String): StatusBarNotification? = manager.activeNotifications.firstOrNull { it.tag == tag }

    private fun <T> await(what: String, probe: () -> T?): T {
        repeat(100) {
            probe()?.let { return it }
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun awaitGone(key: ReminderEventKey) = await("notification ${key.reminderId} gone") { if (mine(key) == null) Unit else null }

    private fun corruptLastByte(name: String) {
        val file = File(root, name)
        val bytes = file.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        file.writeBytes(bytes)
    }

    // NotificationCompat.Action keeps the flag under this extras key (androidx.core NotificationCompat.Action.EXTRA_SHOWS_USER_INTERFACE).
    private fun showsUi(action: Notification.Action): Boolean =
        action.extras.getBoolean("android.support.action.showsUserInterface", true)

    @Test
    fun aFirePostsATaggedNotificationWhoseCompleteAndMarkMissedShowNoUi() = runBlocking<Unit> {
        assumeTrue("notifications disabled", NotificationManagerCompat.from(context).areNotificationsEnabled())
        val item = create(at = System.currentTimeMillis() - 1_000L, message = "take the bins out")
        assertTrue(alarms.postFire(keyOf(item), item.message))
        val posted = await("notification ${item.id}") { mine(keyOf(item)) }
        val notification = posted.notification
        assertEquals(AndroidReminderNotifier.CHANNEL_REMINDERS, notification.channelId)
        assertEquals("take the bins out", notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals(listOf("Complete", "Snooze", "Mark missed"), notification.actions.map { it.title.toString() })
        assertFalse(showsUi(notification.actions[0]))
        assertTrue(showsUi(notification.actions[1]))
        assertFalse(showsUi(notification.actions[2]))
        assertNotNull(notification.deleteIntent)
        assertEquals(0, notification.flags and Notification.FLAG_AUTO_CANCEL)
        assertEquals(Notification.FLAG_ONLY_ALERT_ONCE, notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
    }

    @Test
    fun aRecurringFireArmsTheNextAlarmAndACorruptStoreArmsNothing() = runBlocking<Unit> {
        val recurring = create(at = System.currentTimeMillis() - 1_000L, rule = daily)
        assertFalse(scheduler.hasFireAlarm(recurring.id))
        alarms.recordFire(keyOf(recurring), recurring.message)
        assertTrue(scheduler.hasFireAlarm(recurring.id))
        assertTrue(reminders.readAll(strict = true).single().remindAtMillis > System.currentTimeMillis())

        val broken = create(at = System.currentTimeMillis() - 1_000L, rule = daily)
        corruptLastByte(ReminderStore.FILE_NAME)
        alarms.recordFire(keyOf(broken), broken.message)
        assertFalse(scheduler.hasFireAlarm(broken.id))
        assertNotNull(events.readAll(strict = true).firstOrNull { it.key == keyOf(broken) })
    }

    @Test
    fun anEditAtThePerAppAlarmLimitReplacesTheReminderAlarm() = runBlocking<Unit> {
        val item = create(at = System.currentTimeMillis() + 3_600_000L, message = "call Sam")
        alarms.arm(item)
        AlarmLimitFiller(context).use { filler ->
            filler.fillToLimit()
            val edited = alarms.edit(item.id) { it.copy(remindAtMillis = it.remindAtMillis + 3_600_000L, message = "call Alex") }
            assertEquals("call Alex", edited?.message)
            assertEquals("call Alex", reminders.readAll(strict = true).single().message)
            // The edit's alarm took the place it freed: the limit is met again.
            assertFalse(filler.addOne())
        }
    }

    @Test
    fun completeCancelsThePendingSnoozeAlarmAndDismissesTheNotification() = runBlocking<Unit> {
        assumeTrue("notifications disabled", NotificationManagerCompat.from(context).areNotificationsEnabled())
        val item = create(at = System.currentTimeMillis() - 1_000L)
        alarms.postFire(keyOf(item), item.message)
        alarms.recordFire(keyOf(item), item.message)
        await("notification ${item.id}") { mine(keyOf(item)) }
        alarms.softSnooze(keyOf(item), item.message)
        assertTrue(scheduler.hasSnoozeAlarm(keyOf(item)))
        alarms.complete(keyOf(item), item.message)
        assertFalse(scheduler.hasSnoozeAlarm(keyOf(item)))
        awaitGone(keyOf(item))
        assertNotNull(events.readAll(strict = true).single().completedAt)
    }

    @Test
    fun theReceiversHandleRealBroadcastsThroughTheInjectedCoordinator() = runBlocking<Unit> {
        loadKoinModules(module { single { alarms } })
        val item = create(at = System.currentTimeMillis() - 1_000L, message = "broadcast")
        context.sendBroadcast(
            Intent(context, ReminderFireReceiver::class.java)
                .setAction(ReminderIntents.ACTION_FIRE)
                .putOccurrence(keyOf(item))
                .putExtra(ReminderIntents.EXTRA_MESSAGE, item.message),
        )
        await("fire logged") { events.readAll().firstOrNull { it.key == keyOf(item) } }
        await("one-shot removed") { if (reminders.readAll().isEmpty()) Unit else null }

        context.sendBroadcast(
            Intent(context, ReminderActionReceiver::class.java)
                .setAction(ReminderIntents.ACTION_COMPLETE)
                .putOccurrence(keyOf(item))
                .putExtra(ReminderIntents.EXTRA_MESSAGE, item.message),
        )
        val completedAt = await("occurrence completed") { events.readAll().firstOrNull { it.key == keyOf(item) }?.completedAt }
        assertTrue(completedAt > 0L)
    }

    @Test
    fun theCompleteAndMarkMissedButtonsSendTheirOwnActions() = runBlocking<Unit> {
        assumeTrue("notifications disabled", NotificationManagerCompat.from(context).areNotificationsEnabled())
        loadKoinModules(module { single { alarms } })
        val done = create(at = System.currentTimeMillis() - 2_000L, message = "done by button")
        val missed = create(at = System.currentTimeMillis() - 1_000L, message = "missed by button")
        assertTrue(alarms.postFire(keyOf(done), done.message))
        assertTrue(alarms.postFire(keyOf(missed), missed.message))
        await("notification ${done.id}") { mine(keyOf(done)) }.notification.actions[0].actionIntent.send()
        await("notification ${missed.id}") { mine(keyOf(missed)) }.notification.actions[2].actionIntent.send()
        val doneEvent = await("first completed") { events.readAll().firstOrNull { it.key == keyOf(done) && it.completedAt != null } }
        val missedEvent = await("second marked missed") { events.readAll().firstOrNull { it.key == keyOf(missed) && it.cancelledAt != null } }
        assertNull(doneEvent.cancelledAt)
        assertNull(missedEvent.completedAt)
    }

    @Test
    fun theRearmReceiverIgnoresOtherActionsAndRearmsOnItsThree() = runBlocking<Unit> {
        loadKoinModules(module { single { alarms } })
        val item = create(at = System.currentTimeMillis() + 3_600_000L)
        val receiver = NotebookRearmReceiver()
        receiver.onReceive(context, Intent(ReminderIntents.ACTION_FIRE))
        Thread.sleep(300L)
        assertFalse(scheduler.hasFireAlarm(item.id))
        for (action in NotebookRearmReceiver.ACTIONS) {
            scheduler.cancelFire(item.id)
            receiver.onReceive(context, Intent(action))
            await("alarm re-armed after $action") { if (scheduler.hasFireAlarm(item.id)) Unit else null }
        }
    }

    @Test
    fun aLaterOccurrenceTakesDownTheEarlierOnesNotificationAndSnoozeAlarm() = runBlocking<Unit> {
        assumeTrue("notifications disabled", NotificationManagerCompat.from(context).areNotificationsEnabled())
        val now = System.currentTimeMillis()
        val second = now - 60_000L
        val first = second - 86_400_000L
        val item = create(at = second, rule = RecurrenceRule(RecurrenceFrequency.DAILY, 1, first))
        val firstKey = ReminderEventKey(item.id, first)
        events.appendFire(ReminderEvent(item.id, first, item.message, firedAt = first))
        events.recordSnooze(firstKey, now + 3_600_000L, bumpChain = false, now, TimeZone.currentSystemDefault())
        scheduler.scheduleSnooze(firstKey, item.message, now + 3_600_000L)
        alarms.postFire(firstKey, item.message)
        await("earlier notification") { mine(firstKey) }
        alarms.postFire(keyOf(item), item.message)
        alarms.recordFire(keyOf(item), item.message)
        awaitGone(firstKey)
        assertFalse(scheduler.hasSnoozeAlarm(firstKey))
        assertNotNull(mine(keyOf(item)))
        alarms.complete(keyOf(item), item.message)
        awaitGone(keyOf(item))
    }

    @Test
    fun aFireThatLandsAfterItsCutoffIsShownAsAMissedNoticeWithoutButtons() = runBlocking<Unit> {
        assumeTrue("notifications disabled", NotificationManagerCompat.from(context).areNotificationsEnabled())
        val lapsed = create(at = System.currentTimeMillis() - 2 * 86_400_000L, message = "lapsed")
        alarms.postFire(keyOf(lapsed), lapsed.message)
        alarms.recordFire(keyOf(lapsed), lapsed.message)
        awaitGone(keyOf(lapsed))
        val notice = await("missed notice") { manager.activeNotifications.firstOrNull { it.tag?.startsWith(AndroidReminderNotifier.TAG_SUMMARY) == true } }
        assertEquals("Missed reminder", notice.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals("lapsed", notice.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertTrue(notice.notification.actions.isNullOrEmpty())
        // A notice for another day keeps its own tap, so the earlier notice still opens its own day.
        notifier.postMissedWhileOff(listOf(ReminderEvent(lapsed.id, lapsed.remindAtMillis - 86_400_000L, "lapsed", lapsed.remindAtMillis - 86_400_000L)))
        val other = await("second notice") { manager.activeNotifications.firstOrNull { it.tag?.startsWith(AndroidReminderNotifier.TAG_SUMMARY) == true && it.tag != notice.tag } }
        assertFalse(other.notification.contentIntent == notice.notification.contentIntent)
    }

    @Test
    fun theReceiverSweepTakesDownALapsedNotificationAndKeepsTheSweepForADueOne() = runBlocking<Unit> {
        assumeTrue("notifications disabled", NotificationManagerCompat.from(context).areNotificationsEnabled())
        loadKoinModules(module { single { alarms } })
        val due = create(at = System.currentTimeMillis() - 60_000L, message = "due")
        alarms.recordFire(keyOf(due), due.message)
        assertTrue(scheduler.hasLapseSweep())
        val lapsed = create(at = System.currentTimeMillis() - 2 * 86_400_000L, message = "lapsed")
        events.appendFire(ReminderEvent(lapsed.id, lapsed.remindAtMillis, lapsed.message, firedAt = lapsed.remindAtMillis))
        alarms.postFire(keyOf(lapsed), lapsed.message)
        await("lapsed notification") { mine(keyOf(lapsed)) }
        assertTrue(keyOf(lapsed) in notifier.postedFires())
        scheduler.cancelLapseSweep()
        context.sendBroadcast(Intent(context, ReminderActionReceiver::class.java).setAction(ReminderIntents.ACTION_LAPSE_SWEEP))
        awaitGone(keyOf(lapsed))
        await("sweep re-armed for the due one") { if (scheduler.hasLapseSweep()) Unit else null }
    }

    @Test
    fun theCompletionNudgeCarriesNoCapturedTextAndUpdatesInPlace() = runBlocking<Unit> {
        assumeTrue("notifications disabled", NotificationManagerCompat.from(context).areNotificationsEnabled())
        notifier.postCompletionsToReview(1)
        await("completions nudge") { tagged(AndroidReminderNotifier.TAG_COMPLETIONS) }
        notifier.postCompletionsToReview(4)
        val updated = await("updated count") {
            tagged(AndroidReminderNotifier.TAG_COMPLETIONS)?.takeIf {
                it.notification.extras.getCharSequence(Notification.EXTRA_TITLE) == "4 completed tasks to review"
            }
        }
        assertEquals("Tap to confirm.", updated.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals(1, manager.activeNotifications.count { it.tag == AndroidReminderNotifier.TAG_COMPLETIONS })
        notifier.cancelCompletionsToReview()
        await("completions nudge gone") { if (tagged(AndroidReminderNotifier.TAG_COMPLETIONS) == null) Unit else null }
    }

    @Test
    fun aSetNoticeTakesItsFormFromTheMarkReplacesItselfAndOpensTheEditor() = runBlocking<Unit> {
        assumeTrue("notifications disabled", NotificationManagerCompat.from(context).areNotificationsEnabled())
        val item = create(System.currentTimeMillis() + 3_600_000L, message = "call Sam")
        fun shown() = manager.activeNotifications.filter { it.tag == AndroidReminderNotifier.TAG_SET_NOTICE && it.id == item.id }
        fun text(posted: StatusBarNotification) = posted.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

        notifier.postSetNotice(item)
        val set = await("set notice") { shown().singleOrNull() }
        assertEquals(AndroidReminderNotifier.CHANNEL_SET, set.notification.channelId)
        assertEquals("Reminder set", set.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertTrue(Regex("\"call Sam\" is set for .+\\. Tap to change it\\.").matches(text(set)), text(set))
        assertTrue(set.notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        val editor = PendingIntent.getActivity(context, item.id, notifier.editorIntent(item.id), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        assertEquals(editor, set.notification.contentIntent)
        assertEquals(listOf(PostedSetNotice(item.id, toCheck = false)), notifier.postedSetNotices().filter { it.reminderId == item.id })

        notifier.postSetNotice(item.copy(timeToCheck = true))
        val check = await("check notice") { shown().singleOrNull()?.takeIf { it.notification.channelId == AndroidReminderNotifier.CHANNEL_TIME_CHECK } }
        assertEquals("Check this reminder's time", check.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertTrue(text(check).endsWith(" because Gravel couldn't use the time you said. Tap to change it."), text(check))
        assertEquals(1, shown().size)
        assertEquals(listOf(PostedSetNotice(item.id, toCheck = true)), notifier.postedSetNotices().filter { it.reminderId == item.id })

        notifier.cancelSetNotice(item.id)
        await("set notice gone") { if (shown().isEmpty()) Unit else null }
        assertTrue(notifier.postedSetNotices().none { it.reminderId == item.id })
    }

    @Test
    fun onlyTheNewestReminderSetNoticesStayUp() = runBlocking<Unit> {
        assumeTrue("notifications disabled", NotificationManagerCompat.from(context).areNotificationsEnabled())
        val items = (0..MAX_SET_NOTICES).map { create(System.currentTimeMillis() + 3_600_000L, message = "set $it") }
        fun shown(channel: String) = manager.activeNotifications
            .filter { it.tag == AndroidReminderNotifier.TAG_SET_NOTICE && it.notification.channelId == channel }
            .map { it.id }.toSet()
        val toCheck = create(System.currentTimeMillis() + 3_600_000L, message = "check").copy(timeToCheck = true)
        notifier.postSetNotice(toCheck)
        await("check notice first") { if (toCheck.id in shown(AndroidReminderNotifier.CHANNEL_TIME_CHECK)) Unit else null }
        for (item in items) {
            notifier.postSetNotice(item)
            await("set notice ${item.id}") { if (item.id in shown(AndroidReminderNotifier.CHANNEL_SET)) Unit else null }
            Thread.sleep(20)
        }
        await("oldest taken down") { if (items.first().id !in shown(AndroidReminderNotifier.CHANNEL_SET)) Unit else null }
        assertEquals(items.drop(1).map { it.id }.toSet(), shown(AndroidReminderNotifier.CHANNEL_SET))
        assertEquals(setOf(toCheck.id), shown(AndroidReminderNotifier.CHANNEL_TIME_CHECK))

        notifier.postSetNotice(items.first().copy(timeToCheck = true))
        await("second check notice") { if (items.first().id in shown(AndroidReminderNotifier.CHANNEL_TIME_CHECK)) Unit else null }
        Thread.sleep(500)
        assertEquals(MAX_SET_NOTICES, shown(AndroidReminderNotifier.CHANNEL_SET).size)
    }

    private companion object {
        const val ID_FLOOR = 90_000_000
    }
}
