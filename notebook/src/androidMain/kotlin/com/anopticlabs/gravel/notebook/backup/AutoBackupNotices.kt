package com.anopticlabs.gravel.notebook.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.res.Resources
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.anopticlabs.gravel.notebook.R
import com.anopticlabs.gravel.notebook.alarms.ReminderIntents
import com.anopticlabs.gravel.notebook.alarms.notebookOpenIntent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * [AutoBackupNotices] on the Notebook backups channel, and key reminders on a channel of their
 * own so muting them leaves the backup notices on; a tap opens Settings > Notebook, or the key
 * check. The text names no note.
 */
class AndroidAutoBackupNotices(private val context: Context, private val linkToken: () -> String) : AutoBackupNotices {
    private val manager: NotificationManagerCompat get() = NotificationManagerCompat.from(context)

    override fun failed(problem: AutoBackupProblem) {
        val text = context.getString(problemText(problem))
        post(ID_FAILED, context.getString(R.string.notebook_auto_failed_title), text)
    }

    override fun shortfall(created: CreatedBackup) {
        val r = context.resources
        val lines = listOfNotNull(
            created.notesMissing.takeIf { it > 0 }?.let { r.getQuantityString(R.plurals.notebook_backup_missing_notes, it, it) },
            created.notesTooLarge.takeIf { it > 0 }?.let { r.getQuantityString(R.plurals.notebook_backup_too_large_notes, it, it) },
            created.notesUnlisted.takeIf { it > 0 }?.let { r.getQuantityString(R.plurals.notebook_backup_unlisted_notes, it, it) },
        )
        post(ID_SHORTFALL, context.getString(R.string.notebook_auto_gaps_title), lines.joinToString(" "))
    }

    override fun clearShortfall() = manager.cancel(TAG, ID_SHORTFALL)

    override fun clearFailure() = manager.cancel(TAG, ID_FAILED)

    override fun farFewer(before: AutoBackupEntry, after: AutoBackupEntry, notes: Boolean, remindersGone: Int) {
        val r = context.resources
        val lines = listOfNotNull(
            after.notes.takeIf { notes }?.let { r.getQuantityString(R.plurals.notebook_auto_fewer_notes, it, it, before.notes) },
            remindersGone.takeIf { it > 0 }?.let { r.getQuantityString(R.plurals.notebook_auto_fewer_reminders, it, it) },
            r.getString(R.string.notebook_auto_far_fewer_restore, before.name),
        )
        post(ID_FAR_FEWER, r.getString(R.string.notebook_auto_far_fewer_title), lines.joinToString(" "))
    }

    override fun damaged(count: Int, backupsOn: Boolean) = post(
        ID_DAMAGED, context.resources.getQuantityString(R.plurals.notebook_auto_damaged_title, count),
        context.resources.getQuantityString(if (backupsOn) R.plurals.notebook_auto_damaged_body else R.plurals.notebook_auto_damaged_body_off, count, count),
    )

    override fun missing(count: Int, backupsOn: Boolean) = post(
        ID_MISSING, context.resources.getQuantityString(R.plurals.notebook_auto_missing_title, count),
        context.resources.getQuantityString(if (backupsOn) R.plurals.notebook_auto_missing_body else R.plurals.notebook_auto_missing_body_off, count, count),
    )

    // Taken down first: only-alert-once silences only an update of a notice still posted (main, NotificationAttentionHelper, MUTE_REASON_SILENT_UPDATE).
    override fun stale(lastConfirmedAtMillis: Long) {
        manager.cancel(TAG, ID_STALE)
        post(ID_STALE, context.getString(R.string.notebook_auto_stale_title), context.getString(R.string.notebook_auto_stale_body, dateTime(lastConfirmedAtMillis, ZoneId.systemDefault())))
    }

    override fun clearStale() = manager.cancel(TAG, ID_STALE)

    override fun keyCheckDue() {
        manager.cancel(TAG, ID_KEY)
        post(
            ID_KEY, context.getString(R.string.notebook_auto_key_reminder_title), context.getString(R.string.notebook_auto_key_reminder_body),
            KEY_CHANNEL, R.string.notebook_auto_key_channel, ReminderIntents.ACTION_OPEN_KEY_CHECK, R.string.notebook_auto_key_public_title,
        )
    }

    override fun clearKeyCheck() = manager.cancel(TAG, ID_KEY)

    private fun post(
        id: Int,
        title: String,
        text: String,
        channel: String = CHANNEL,
        channelName: Int = R.string.notebook_auto_channel,
        action: String = ReminderIntents.ACTION_OPEN_BACKUP_SETTINGS,
        publicTitle: Int = R.string.notebook_auto_public_title,
    ) {
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel(channel, context.getString(channelName), NotificationManager.IMPORTANCE_DEFAULT))
        val tap = PendingIntent.getActivity(
            context, id, notebookOpenIntent(context, action, linkToken()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(ICON)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            // Private: where the user hides sensitive lock-screen content, SystemUI shows the public version, a title only (main, NotificationLockscreenUserManagerImpl.needsRedaction).
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(context, channel).setSmallIcon(ICON).setContentTitle(context.getString(publicTitle)).build())
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(TAG, id, notification)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        const val CHANNEL = "notebook_backups"
        const val KEY_CHANNEL = "notebook_key_checks"
        const val TAG = "notebook_backup"
        const val ID_FAILED = 1
        const val ID_SHORTFALL = 2
        const val ID_FAR_FEWER = 3
        const val ID_DAMAGED = 4
        const val ID_MISSING = 5
        const val ID_STALE = 6
        const val ID_KEY = 7
        private val ICON = coredevices.util.R.drawable.ic_notification
    }
}

internal fun problemText(problem: AutoBackupProblem): Int = when (problem) {
    AutoBackupProblem.NO_ACCESS -> R.string.notebook_auto_problem_no_access
    AutoBackupProblem.FOLDER_GONE -> R.string.notebook_auto_problem_folder_gone
    AutoBackupProblem.NOT_CREATED -> R.string.notebook_auto_problem_not_created
    AutoBackupProblem.KEY_MISSING -> R.string.notebook_auto_problem_key_missing
    AutoBackupProblem.DAMAGED -> R.string.notebook_auto_problem_damaged
    AutoBackupProblem.ALREADY_SET_UP -> R.string.notebook_auto_problem_already_set_up
    AutoBackupProblem.NOT_READ -> R.string.notebook_auto_problem_not_read
    AutoBackupProblem.NO_SPACE -> R.string.notebook_auto_problem_no_space
    AutoBackupProblem.NOT_SAVED -> R.string.notebook_auto_problem_not_saved
    AutoBackupProblem.NOT_RECORDED -> R.string.notebook_auto_problem_not_recorded
}

/** The text of a failed first backup, where the user has just picked the folder. */
internal fun setupProblemText(problem: AutoBackupProblem): Int = when (problem) {
    AutoBackupProblem.NO_ACCESS -> R.string.notebook_auto_setup_problem_no_access
    AutoBackupProblem.FOLDER_GONE -> R.string.notebook_auto_setup_problem_folder_gone
    AutoBackupProblem.NOT_CREATED -> R.string.notebook_auto_setup_problem_not_created
    else -> problemText(problem)
}

/** What Settings' line under Automatic Backups says, in order of precedence. */
sealed interface AutoBackupLine {
    data object Unreadable : AutoBackupLine
    data object Running : AutoBackupLine
    data class Stopped(val problem: AutoBackupProblem) : AutoBackupLine
    data class LastFailed(val problem: AutoBackupProblem) : AutoBackupLine
    data object Off : AutoBackupLine
    data class Stale(val lastConfirmedAtMillis: Long) : AutoBackupLine
    data object NoBackup : AutoBackupLine
    data class Last(val newest: AutoBackupEntry, val checkedAtMillis: Long?) : AutoBackupLine
}

fun autoBackupLine(status: AutoBackupStatus): AutoBackupLine {
    val newest = status.newest
    val failure = status.failure
    return when {
        status.unreadable -> AutoBackupLine.Unreadable
        status.running -> AutoBackupLine.Running
        failure != null && failure.problem.permanent -> AutoBackupLine.Stopped(failure.problem)
        failure != null -> AutoBackupLine.LastFailed(failure.problem)
        !status.enabled -> AutoBackupLine.Off
        status.stale && status.lastConfirmedAtMillis != null -> AutoBackupLine.Stale(status.lastConfirmedAtMillis)
        newest == null -> AutoBackupLine.NoBackup
        else -> AutoBackupLine.Last(newest, newest.checkedAtMillis.takeIf { it > newest.createdAtMillis })
    }
}

fun autoBackupStatusText(r: Resources, status: AutoBackupStatus, zone: ZoneId = ZoneId.systemDefault()): String =
    when (val line = autoBackupLine(status)) {
        AutoBackupLine.Unreadable -> r.getString(R.string.notebook_auto_status_unreadable)
        AutoBackupLine.Running -> r.getString(R.string.notebook_auto_status_running)
        is AutoBackupLine.Stopped -> r.getString(R.string.notebook_auto_status_stopped, r.getString(problemText(line.problem)))
        is AutoBackupLine.LastFailed -> r.getString(R.string.notebook_auto_status_failed, r.getString(problemText(line.problem)))
        AutoBackupLine.Off -> r.getString(R.string.notebook_auto_status_off)
        is AutoBackupLine.Stale -> r.getString(R.string.notebook_auto_status_stale, dateTime(line.lastConfirmedAtMillis, zone))
        AutoBackupLine.NoBackup -> r.getString(R.string.notebook_auto_status_none)
        is AutoBackupLine.Last -> {
            val n = line.newest
            val text = r.getString(
                R.string.notebook_auto_status_last,
                dateTime(n.createdAtMillis, zone),
                r.getQuantityString(R.plurals.notebook_backup_notes, n.notes, n.notes),
                r.getQuantityString(R.plurals.notebook_backup_reminders, n.reminders, n.reminders),
            )
            line.checkedAtMillis?.let { text + ", " + r.getString(R.string.notebook_auto_status_checked, dateTime(it, zone)) } ?: text
        }
    }

/** What Settings says under Recovery Key: a due check first, then when it was last checked. */
fun recoveryKeyStatusText(r: Resources, status: AutoBackupStatus, zone: ZoneId = ZoneId.systemDefault()): String = when {
    status.keyDue -> r.getString(R.string.notebook_auto_key_status_due)
    status.keyCheckedAtMillis != null -> r.getString(R.string.notebook_auto_key_status_checked, dateTime(status.keyCheckedAtMillis, zone))
    else -> r.getString(R.string.notebook_auto_key_status_never)
}

internal fun dateTime(millis: Long, zone: ZoneId): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(zone).format(Instant.ofEpochMilli(millis))
