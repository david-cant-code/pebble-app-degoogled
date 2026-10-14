package com.anopticlabs.gravel.notebook.alarms

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.anopticlabs.gravel.notebook.backup.AutoBackupChecks
import com.anopticlabs.gravel.notebook.isNotebookSupported
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Re-arms every reminder and pending snooze from the stores at a boot, an update of this package
 * and exact alarms being allowed (android16-release
 * `AlarmManagerService.sendScheduleExactAlarmPermissionStateChangedBroadcast`, called only when the
 * SCHEDULE_EXACT_ALARM op turns allowed). Below API 28 the Notebook has no storage, so there is
 * nothing to re-arm. The automatic backup checks
 * run after the re-arm, whether or not it threw, which also sets their alarm again after a boot.
 */
class NotebookRearmReceiver : BroadcastReceiver(), KoinComponent {

    private val alarms: ReminderAlarms by inject()
    private val backupChecks: AutoBackupChecks by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        if (!isNotebookSupported) return
        runAsync(TAG) {
            try {
                alarms.rearmAll()
            } finally {
                backupChecks.check()
            }
        }
    }

    companion object {
        private const val TAG = "NotebookRearmReceiver"

        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}
