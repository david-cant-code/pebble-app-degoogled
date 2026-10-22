package com.anopticlabs.gravel.notebook.alarms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.anopticlabs.gravel.notebook.backup.AutoBackupChecks
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * The fire alarm's target: posts the occurrence, then logs it and settles the item, all off the
 * main thread, then runs the automatic backup checks.
 */
class ReminderFireReceiver : BroadcastReceiver(), KoinComponent {

    private val alarms: ReminderAlarms by inject()
    private val backupChecks: AutoBackupChecks by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderIntents.ACTION_FIRE) return
        val key = ReminderIntents.occurrenceOf(intent) ?: return
        val message = intent.getStringExtra(ReminderIntents.EXTRA_MESSAGE) ?: return
        runAsync(TAG) {
            try {
                alarms.postFire(key, message)
                alarms.recordFire(key, message)
            } finally {
                backupChecks.check()
            }
        }
    }

    private companion object {
        const val TAG = "ReminderFireReceiver"
    }
}
