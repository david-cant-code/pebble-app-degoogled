package com.anopticlabs.gravel.notebook.ui

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.os.SystemClock
import co.touchlab.kermit.Logger

/**
 * Copies [text] marked sensitive, so the clipboard preview hides it, and sets an inexact alarm to
 * clear the clipboard [CLEAR_AFTER_MILLIS] later ([clearAlarm]), after Signal-Android main,
 * `core/util/.../Util.java`, `copyToClipboardSensitive`. False, with nothing copied, when the
 * alarm cannot be set ([copyWithClearSet]).
 */
internal fun copySensitive(context: Context, label: String, text: String): Boolean {
    val alarms = context.getSystemService(AlarmManager::class.java) ?: return false
    val clip = ClipData.newPlainText(label, text).apply {
        description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    }
    val clear = PendingIntent.getBroadcast(
        context, 0, Intent(context, ClipboardClearReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return copyWithClearSet(
        cancelClear = { alarms.cancel(clear) },
        setClear = {
            val alarm = clearAlarm(SystemClock.elapsedRealtime())
            alarms.setAndAllowWhileIdle(alarm.type, alarm.triggerAtMillis, clear)
        },
        copy = { context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip) },
        clearEarlierCopy = { clearClipboardIfLabeled(context, label) },
    )
}

/**
 * Sets the clear alarm before the copy. When the alarm cannot be set, nothing is copied and
 * [clearEarlierCopy] takes off an earlier copy, whose alarm the cancel removed.
 */
internal fun copyWithClearSet(cancelClear: () -> Unit, setClear: () -> Unit, copy: () -> Unit, clearEarlierCopy: () -> Unit): Boolean {
    // Canceled first for the reason given at AndroidReminderAlarms.set.
    cancelClear()
    try {
        setClear()
    } catch (e: Exception) {
        logger.w(e) { "clipboard clear alarm not set; nothing copied" }
        clearEarlierCopy()
        return false
    }
    copy()
    return true
}

private val logger = Logger.withTag("SensitiveClipboard")

internal data class ClearAlarm(val type: Int, val triggerAtMillis: Long)

/**
 * The alarm [copySensitive] sets, on the elapsed clock: AlarmManagerService re-times an RTC alarm
 * when the wall clock is changed (android16-release, `AlarmManagerService.reevaluateRtcAlarms`).
 */
internal fun clearAlarm(elapsedRealtimeMillis: Long) = ClearAlarm(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedRealtimeMillis + CLEAR_AFTER_MILLIS)

/** Clears the clipboard when its clip carries [label], without reading what the clip holds. */
internal fun clearClipboardIfLabeled(context: Context, label: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val description = try {
        clipboard.primaryClipDescription
    } catch (_: Exception) {
        null
    } ?: return
    if (description.label?.toString() == label) clipboard.clearPrimaryClip()
}

internal const val CLEAR_AFTER_MILLIS = 60_000L

/** Clears the clipboard when [copySensitive]'s alarm goes off; reached only by that pending intent, so unexported. */
class ClipboardClearReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.getSystemService(ClipboardManager::class.java)?.clearPrimaryClip()
    }
}
