package com.anopticlabs.gravel.notebook.alarms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import co.touchlab.kermit.Logger
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** The notification buttons, the swipe-away, the picker's confirmation, the snooze alarm and the lapse sweep. */
class ReminderActionReceiver : BroadcastReceiver(), KoinComponent {

    private val alarms: ReminderAlarms by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == ReminderIntents.ACTION_LAPSE_SWEEP) {
            runAsync(TAG) { alarms.sweepLapsed() }
            return
        }
        val key = ReminderIntents.occurrenceOf(intent)
        if (key == null) {
            logger.w { "$action without an occurrence; dropped" }
            return
        }
        val message = intent.getStringExtra(ReminderIntents.EXTRA_MESSAGE).orEmpty()
        when (action) {
            ReminderIntents.ACTION_COMPLETE -> runAsync(TAG) { alarms.complete(key, message) }
            ReminderIntents.ACTION_CANCEL -> runAsync(TAG) { alarms.cancel(key, message) }
            ReminderIntents.ACTION_SOFT_SNOOZE -> runAsync(TAG) { alarms.softSnooze(key, message) }
            ReminderIntents.ACTION_SNOOZE_CONFIRMED -> {
                val offset = intent.getLongExtra(ReminderIntents.EXTRA_SNOOZE_MILLIS, -1L)
                if (offset <= 0L) {
                    logger.w { "snooze confirmed without a duration; dropped" }
                    return
                }
                runAsync(TAG) { alarms.snoozeConfirmed(key, message, offset) }
            }
            ReminderIntents.ACTION_SNOOZED_FIRE -> runAsync(TAG) { alarms.snoozedFire(key, message) }
            else -> logger.w { "unexpected action $action; dropped" }
        }
    }

    private companion object {
        const val TAG = "ReminderActionReceiver"
        val logger = Logger.withTag(TAG)
    }
}
