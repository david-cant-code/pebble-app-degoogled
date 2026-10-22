package com.anopticlabs.gravel.notebook.alarms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.anopticlabs.gravel.notebook.alarms.ReminderIntents.putCalendarDay
import com.anopticlabs.gravel.notebook.alarms.ReminderIntents.putOccurrence
import com.anopticlabs.gravel.notebook.reminders.ReminderEvent
import com.anopticlabs.gravel.notebook.reminders.ReminderEventKey
import com.anopticlabs.gravel.notebook.reminders.ReminderItem
import com.anopticlabs.gravel.notebook.reminders.missedWhileOffNotice
import com.anopticlabs.gravel.notebook.reminders.voiceCompletionNotificationText
import com.anopticlabs.gravel.notebook.ui.ReminderTimes

/** The Notebook's notifications; a post reports whether the user can actually see it. */
interface ReminderNotifier {
    /** False when notifications are off for the app or the channel, so a delivery mark is never stamped for a post nobody saw. */
    fun postFire(key: ReminderEventKey, message: String): Boolean
    fun cancelFire(key: ReminderEventKey)

    /** Every occurrence notification of the reminder. */
    fun cancelFires(id: Int)

    /** The occurrences whose notifications are showing. */
    fun postedFires(): List<ReminderEventKey>

    /** One notice without buttons for occurrences that were MISSED before they could be shown, beside any earlier notice. */
    fun postMissedWhileOff(missed: List<ReminderEvent>)

    /**
     * Tells the user when voice reminder [item] is set, its tap opening the editor; a marked [item] gets the form
     * asking to check the time. A post replaces the reminder's notice; of the "Reminder set" ones, the
     * [MAX_SET_NOTICES] newest stay up.
     */
    fun postSetNotice(item: ReminderItem)
    fun cancelSetNotice(id: Int)
    fun postedSetNotices(): List<PostedSetNotice>
    fun postCompletionsToReview(count: Int)
    fun cancelCompletionsToReview()
}

/** A set notice showing for reminder [reminderId]; [toCheck] when it is the one asking to check the time. */
data class PostedSetNotice(val reminderId: Int, val toCheck: Boolean)

/**
 * Android refuses an app's posts once 50 of its notifications are up, a reminder's fire among them, unless the app's
 * notification listener is connected (android16-release, NotificationManagerService.checkDisqualifyingFeatures).
 */
const val MAX_SET_NOTICES = 10

/** Of the other "Reminder set" notices, as (reminder id, post time), the ones past the newest [MAX_SET_NOTICES] - 1. */
internal fun setNoticesPastCap(others: List<Pair<Int, Long>>): List<Int> =
    others.sortedByDescending { it.second }.drop(MAX_SET_NOTICES - 1).map { it.first }

/**
 * Notifications on the Notebook's own channels, each tagged, so their ids stay apart from the
 * untagged ids the rest of the app posts today; an occurrence is keyed by its reminder id under a
 * tag carrying its fire instant, so two occurrences of one reminder show side by side. Taps
 * open the app with an OPEN_* action and [linkToken], which the app checks before routing. The
 * reminder text is on the lock screen by design, in the set notice as in the fire; the completion
 * nudge carries no captured text.
 */
class AndroidReminderNotifier(
    private val context: Context,
    private val linkToken: () -> String,
) : ReminderNotifier {

    private val manager: NotificationManagerCompat get() = NotificationManagerCompat.from(context)

    override fun postFire(key: ReminderEventKey, message: String): Boolean {
        ensureChannels()
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(ICON)
            .setContentTitle("Reminder")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open(ReminderIntents.ACTION_OPEN_REMINDERS, key.reminderId) { putOccurrence(key) })
            // A tap on the body does not dismiss, so only a dismissal reaches the delete intent, which snoozes.
            .setAutoCancel(false)
            // A re-arm and the fire alarm can both post one occurrence when the alarm starts the process.
            .setOnlyAlertOnce(true)
            .setDeleteIntent(action(ReminderIntents.ACTION_SOFT_SNOOZE, key, message))
            .addAction(button("Complete", action(ReminderIntents.ACTION_COMPLETE, key, message), showsUi = false))
            .addAction(button("Snooze", open(ReminderIntents.ACTION_OPEN_SNOOZE_PICKER, key.reminderId) { putOccurrence(key) }, showsUi = true))
            .addAction(button("Mark missed", action(ReminderIntents.ACTION_CANCEL, key, message), showsUi = false))
            .build()
        return post(fireTag(key), key.reminderId, CHANNEL_REMINDERS, notification)
    }

    override fun cancelFire(key: ReminderEventKey) = manager.cancel(fireTag(key), key.reminderId)

    override fun cancelFires(id: Int) {
        for (posted in manager.activeNotifications) {
            if (posted.id == id && posted.tag?.startsWith(TAG_REMINDER_PREFIX) == true) manager.cancel(posted.tag, posted.id)
        }
    }

    override fun postedFires(): List<ReminderEventKey> = manager.activeNotifications.mapNotNull { posted ->
        val fire = posted.tag?.takeIf { it.startsWith(TAG_REMINDER_PREFIX) }?.removePrefix(TAG_REMINDER_PREFIX)?.toLongOrNull()
        fire?.let { ReminderEventKey(posted.id, it) }
    }

    override fun postMissedWhileOff(missed: List<ReminderEvent>) {
        if (missed.isEmpty()) return
        ensureChannels()
        val notice = missedWhileOffNotice(missed)
        val inbox = NotificationCompat.InboxStyle().setBigContentTitle(notice.title)
        for (line in notice.lines) inbox.addLine(line)
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(ICON)
            .setContentTitle(notice.title)
            .setContentText(notice.lines.joinToString(", "))
            .setStyle(inbox)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(activity(MISSED_SUMMARY_ID, missedNoticeIntent(missed)))
            .setAutoCancel(true)
            .build()
        post(TAG_SUMMARY + System.currentTimeMillis(), MISSED_SUMMARY_ID, CHANNEL_REMINDERS, notification)
    }

    override fun postSetNotice(item: ReminderItem) {
        ensureChannels()
        val times = ReminderTimes(context.resources.configuration.locales[0], DateFormat.is24HourFormat(context))
        val at = times.format(item.remindAtMillis, System.currentTimeMillis())
        val channel = if (item.timeToCheck) CHANNEL_TIME_CHECK else CHANNEL_SET
        val title = if (item.timeToCheck) "Check this reminder's time" else "Reminder set"
        val text = if (item.timeToCheck) {
            "\"${item.message}\" is set for $at because Gravel couldn't use the time you said. Tap to change it."
        } else {
            "\"${item.message}\" is set for $at. Tap to change it."
        }
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(ICON)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(activity(item.id, editorIntent(item.id)))
            .setAutoCancel(true)
            .build()
        if (!post(TAG_SET_NOTICE, item.id, channel, notification) || item.timeToCheck) return
        val others = manager.activeNotifications
            .filter { it.tag == TAG_SET_NOTICE && it.id != item.id && it.notification.channelId == CHANNEL_SET }
            .map { it.id to it.postTime }
        for (id in setNoticesPastCap(others)) manager.cancel(TAG_SET_NOTICE, id)
    }

    override fun cancelSetNotice(id: Int) = manager.cancel(TAG_SET_NOTICE, id)

    override fun postedSetNotices(): List<PostedSetNotice> = manager.activeNotifications
        .filter { it.tag == TAG_SET_NOTICE }
        .map { PostedSetNotice(it.id, toCheck = it.notification.channelId == CHANNEL_TIME_CHECK) }

    override fun postCompletionsToReview(count: Int) {
        ensureChannels()
        val notification = NotificationCompat.Builder(context, CHANNEL_COMPLETIONS)
            .setSmallIcon(ICON)
            .setContentTitle(voiceCompletionNotificationText(count))
            .setContentText("Tap to confirm.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setContentIntent(open(ReminderIntents.ACTION_OPEN_VOICE_COMPLETIONS, COMPLETIONS_ID))
            .setAutoCancel(false)
            .build()
        post(TAG_COMPLETIONS, COMPLETIONS_ID, CHANNEL_COMPLETIONS, notification)
    }

    override fun cancelCompletionsToReview() = manager.cancel(TAG_COMPLETIONS, COMPLETIONS_ID)

    private fun post(tag: String, id: Int, channelId: String, notification: Notification): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(channelId)
        if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        return try {
            manager.notify(tag, id, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun ensureChannels() {
        manager.createNotificationChannel(NotificationChannel(CHANNEL_REMINDERS, "Notebook reminders", NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_COMPLETIONS, "Notebook completed tasks", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_TIME_CHECK, "Notebook reminders to check", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_SET, "Notebook reminders set", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun button(title: String, intent: PendingIntent, showsUi: Boolean) =
        NotificationCompat.Action.Builder(0, title, intent).setShowsUserInterface(showsUi).build()

    private fun action(action: String, key: ReminderEventKey, message: String): PendingIntent {
        val intent = Intent(context, ReminderActionReceiver::class.java)
            .setAction(action)
            .putOccurrence(key)
            .putExtra(ReminderIntents.EXTRA_MESSAGE, message)
        return PendingIntent.getBroadcast(context, key.reminderId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun open(action: String, requestCode: Int, extras: Intent.() -> Intent = { this }): PendingIntent =
        activity(requestCode, openIntent(action, extras))

    private fun activity(requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** The set notice's tap: reminder [id]'s editor. */
    fun editorIntent(id: Int): Intent = openIntent(ReminderIntents.ACTION_OPEN_REMINDER_EDITOR) { putExtra(ReminderIntents.EXTRA_REMINDER_ID, id) }

    /** The missed notice's tap: the calendar on the day the latest of [missed] was due. */
    fun missedNoticeIntent(missed: List<ReminderEvent>): Intent =
        openIntent(ReminderIntents.ACTION_OPEN_REMINDER_CALENDAR) { putCalendarDay(missed.maxOf { it.originalFireMillis }) }

    fun openIntent(action: String, extras: Intent.() -> Intent = { this }): Intent = notebookOpenIntent(context, action, linkToken()).extras()

    companion object {
        const val CHANNEL_REMINDERS = "notebook_reminders"
        const val CHANNEL_COMPLETIONS = "notebook_voice_completions"
        const val CHANNEL_TIME_CHECK = "notebook_reminder_time_check"
        const val CHANNEL_SET = "notebook_reminder_set"
        const val TAG_REMINDER_PREFIX = "notebook_reminder:"
        const val TAG_SUMMARY = "notebook_reminder_summary:"
        const val TAG_COMPLETIONS = "notebook_completions"
        const val TAG_SET_NOTICE = "notebook_set_notice"

        /** Above every reminder id, which stays at or below [com.anopticlabs.gravel.notebook.storage.ReminderFile.MAX_REMINDER_ID]. */
        const val MISSED_SUMMARY_ID = Int.MAX_VALUE
        const val COMPLETIONS_ID = 1
        private val ICON = coredevices.util.R.drawable.ic_notification

        fun fireTag(key: ReminderEventKey): String = TAG_REMINDER_PREFIX + key.originalFireMillis
    }
}

/** A tap's intent: the launcher intent, which keeps this module free of the activity class, with an OPEN_* action and the link token. */
internal fun notebookOpenIntent(context: Context, action: String, linkToken: String): Intent {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
    return intent.setAction(action).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(ReminderIntents.EXTRA_LINK_TOKEN, linkToken)
}
