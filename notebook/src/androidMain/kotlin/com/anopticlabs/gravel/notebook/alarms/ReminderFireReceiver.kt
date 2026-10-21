package com.anopticlabs.gravel.notebook.alarms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * The fire alarm's target: posts the occurrence, then logs it and settles the item, all off the
 * main thread.
 */
class ReminderFireReceiver : BroadcastReceiver(), KoinComponent {

    private val alarms: ReminderAlarms by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderIntents.ACTION_FIRE) return
        val key = ReminderIntents.occurrenceOf(intent) ?: return
        val message = intent.getStringExtra(ReminderIntents.EXTRA_MESSAGE) ?: return
        runAsync(TAG) {
            alarms.postFire(key, message)
            alarms.recordFire(key, message)
        }
    }

    private companion object {
        const val TAG = "ReminderFireReceiver"
    }
}
