package com.anopticlabs.gravel.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.alarms.ReminderAlarms
import com.anopticlabs.gravel.notebook.pipeline.CaptureCoordinator
import com.anopticlabs.gravel.notebook.reminders.RecurrenceFrequency
import com.anopticlabs.gravel.notebook.reminders.RecurrenceRule
import com.anopticlabs.gravel.notebook.reminders.ReminderItem
import com.anopticlabs.gravel.notebook.reminders.ReminderRefreshSignal
import com.anopticlabs.gravel.notebook.storage.QueueStore
import com.anopticlabs.gravel.notebook.storage.ReminderStore
import com.anopticlabs.gravel.notebook.ui.NotebookWindowSecurity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Gravel, debug builds only: creates and arms a Notebook reminder from adb, so the alarm stack
 * can be driven end to end before any screen creates reminders. Guarded by
 * android.permission.DUMP in the debug manifest like the other adb receivers.
 *
 *     adb shell am broadcast -a com.anopticlabs.gravel.notebook.DEBUG_REMINDER \
 *       -n com.anopticlabs.gravel/com.anopticlabs.gravel.debug.NotebookDebugReceiver \
 *       --es message "'take the bins out'" --ei seconds 60 --ez daily false
 *
 * With `--ei disarm <id>` it instead deletes that reminder with its alarms and notifications;
 * with `--es cancelTag <tag> --ei cancelId <id>` it cancels one of the app's notifications.
 *
 * With `--ez secure false` Notebook screens stop setting FLAG_SECURE until the process ends or
 * `--ez secure true` comes, so they can be captured with `adb exec-out screencap`.
 *
 * With `--es capture <name>` it queues `files/notebook-debug/<name>` as a capture, deletes that
 * copy, and tells the coordinator a capture was queued; `--ez drain true` asks for a run alone.
 * The coordinator logs the drain's report. A recording reaches that directory through run-as, for example
 *
 *     adb shell "cat /data/local/tmp/clip.m4a | run-as com.anopticlabs.gravel \
 *       sh -c 'mkdir -p files/notebook-debug && cat > files/notebook-debug/clip.m4a'"
 */
class NotebookDebugReceiver : BroadcastReceiver(), KoinComponent {

    private val reminders: ReminderStore by inject()
    private val alarms: ReminderAlarms by inject()
    private val refresh: ReminderRefreshSignal by inject()
    private val queue: QueueStore by inject()
    private val coordinator: CaptureCoordinator by inject()
    private val logger = Logger.withTag("NotebookDebugReceiver")

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        if (intent.hasExtra("secure")) {
            NotebookWindowSecurity.liftedForDebug = !intent.getBooleanExtra("secure", true)
            logger.i { "Notebook screen-capture block lifted: ${NotebookWindowSecurity.liftedForDebug}" }
            return
        }
        val capture = intent.getStringExtra("capture")
        if (capture != null || intent.getBooleanExtra("drain", false)) {
            // Sealing a recording can outlast the broadcast timeout, so the queueing runs after
            // the broadcast returns; the app must stay in front, or hold a service, until it ends.
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    if (capture != null) enqueue(context, capture)
                } catch (e: Exception) {
                    logger.w(e) { "capture not queued" }
                } finally {
                    if (capture != null) coordinator.captureQueued() else coordinator.request()
                }
            }
            return
        }
        intent.getStringExtra("cancelTag")?.let { tag ->
            NotificationManagerCompat.from(context).cancel(tag, intent.getIntExtra("cancelId", 0))
            return
        }
        val disarm = intent.getIntExtra("disarm", 0)
        val message = intent.getStringExtra("message") ?: "debug reminder"
        val seconds = intent.getIntExtra("seconds", 60).coerceAtLeast(5)
        val daily = intent.getBooleanExtra("daily", false)
        val at = System.currentTimeMillis() + seconds * 1_000L
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (disarm > 0) {
                    alarms.disarm(disarm)
                    reminders.remove(disarm)
                    logger.i { "disarmed reminder $disarm" }
                    return@launch
                }
                val rule = if (daily) RecurrenceRule(RecurrenceFrequency.DAILY, 1, at) else null
                val item = reminders.create { ReminderItem(it, at, message, rule) }
                alarms.arm(item)
                refresh.emit()
                logger.i { "armed reminder ${item.id} in $seconds s" }
            } catch (e: Exception) {
                logger.w(e) { "reminder not armed" }
            } finally {
                result.finish()
            }
        }
    }

    private suspend fun enqueue(context: Context, name: String) {
        require(QueueStore.isValidOriginalName(name)) { "not a capture file name" }
        val file = File(context.filesDir, "notebook-debug/$name")
        val id = UUID.randomUUID().toString()
        check(queue.enqueue(id, file.readBytes(), name)) { "the store did not keep the capture" }
        file.delete()
        logger.i { "queued capture $id" }
    }

    private companion object {
        const val ACTION = "com.anopticlabs.gravel.notebook.DEBUG_REMINDER"
    }
}
