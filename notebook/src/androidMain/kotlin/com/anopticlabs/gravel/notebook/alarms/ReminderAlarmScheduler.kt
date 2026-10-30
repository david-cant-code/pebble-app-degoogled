package com.anopticlabs.gravel.notebook.alarms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.alarms.ReminderIntents.putOccurrence
import com.anopticlabs.gravel.notebook.reminders.ReminderEventKey
import com.anopticlabs.gravel.notebook.reminders.ReminderItem

/** One fire alarm per reminder, replaced on every arm, one snooze alarm per occurrence, and one lapse sweep. */
interface ReminderAlarmScheduler {
    fun scheduleFire(item: ReminderItem)
    fun cancelFire(id: Int)
    fun scheduleSnooze(key: ReminderEventKey, message: String, fireAtMillis: Long)
    fun cancelSnooze(key: ReminderEventKey)

    /** Replaces the one sweep alarm, which takes down notifications of occurrences that turned MISSED. */
    fun scheduleLapseSweep(atMillis: Long)
    fun cancelLapseSweep()
}

/**
 * AlarmManager-backed scheduler. From API 31 an exact alarm needs the user's grant
 * (android-37.0 api-versions.xml: `AlarmManager.canScheduleExactAlarms` since="31"); without it
 * the alarm is inexact, and the app lists the grant among its required permissions while
 * reminders exist. The fire intent carries no data, so a re-arm replaces the reminder's one fire
 * alarm; a snooze intent carries its occurrence, so each occurrence has its own.
 */
class AndroidReminderAlarms(private val context: Context) : ReminderAlarmScheduler {

    private val alarmManager: AlarmManager get() = context.getSystemService(AlarmManager::class.java)

    override fun scheduleFire(item: ReminderItem) {
        val intent = fireIntent()
            .putExtra(ReminderIntents.EXTRA_REMINDER_ID, item.id)
            .putExtra(ReminderIntents.EXTRA_ORIGINAL_FIRE_MILLIS, item.remindAtMillis)
            .putExtra(ReminderIntents.EXTRA_MESSAGE, item.message)
        set(item.remindAtMillis, pending(item.id, intent))
    }

    override fun cancelFire(id: Int) = cancel(id, fireIntent())

    override fun scheduleSnooze(key: ReminderEventKey, message: String, fireAtMillis: Long) {
        val intent = snoozeIntent(key).putExtra(ReminderIntents.EXTRA_MESSAGE, message)
        set(fireAtMillis, pending(key.reminderId, intent))
    }

    override fun cancelSnooze(key: ReminderEventKey) = cancel(key.reminderId, snoozeIntent(key))

    override fun scheduleLapseSweep(atMillis: Long) = set(atMillis, pending(LAPSE_SWEEP_REQUEST, lapseSweepIntent()))

    override fun cancelLapseSweep() = cancel(LAPSE_SWEEP_REQUEST, lapseSweepIntent())

    fun hasLapseSweep(): Boolean = existing(LAPSE_SWEEP_REQUEST, lapseSweepIntent()) != null

    fun hasFireAlarm(id: Int): Boolean = existing(id, fireIntent()) != null

    fun hasSnoozeAlarm(key: ReminderEventKey): Boolean = existing(key.reminderId, snoozeIntent(key)) != null

    fun exactAlarmsAllowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun set(atMillis: Long, pending: PendingIntent) {
        // android16-release AlarmManagerService.setImpl checks the per-app alarm limit before
        // setImplLocked removes the alarm being replaced, so a replacement at the limit needs it gone first.
        alarmManager.cancel(pending)
        if (exactAlarmsAllowed()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
                return
            } catch (e: SecurityException) {
                logger.w(e) { "exact alarm refused; arming inexact" }
            }
        } else {
            logger.w { "exact alarms not granted; arming inexact" }
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
    }

    private fun cancel(requestCode: Int, intent: Intent) {
        val pending = existing(requestCode, intent) ?: return
        alarmManager.cancel(pending)
        pending.cancel()
    }

    private fun pending(requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun existing(requestCode: Int, intent: Intent): PendingIntent? =
        PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)

    private fun fireIntent() = Intent(context, ReminderFireReceiver::class.java).setAction(ReminderIntents.ACTION_FIRE)

    private fun snoozeIntent(key: ReminderEventKey) =
        Intent(context, ReminderActionReceiver::class.java).setAction(ReminderIntents.ACTION_SNOOZED_FIRE).putOccurrence(key)

    private fun lapseSweepIntent() = Intent(context, ReminderActionReceiver::class.java).setAction(ReminderIntents.ACTION_LAPSE_SWEEP)

    private companion object {
        val logger = Logger.withTag("ReminderAlarms")

        /** Below every reminder id, which starts at 1. */
        const val LAPSE_SWEEP_REQUEST = 0
    }
}
