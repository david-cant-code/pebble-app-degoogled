package com.anopticlabs.gravel.notebook.ui

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.alarms.ReminderIntents
import com.anopticlabs.gravel.notebook.reminders.ReminderEventKey
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where a Notebook notification tap asks the app to go. Ids are store keys to look up, never text to show. */
sealed interface NotebookLink {
    data object Reminders : NotebookLink
    data class Occurrence(val key: ReminderEventKey, val snooze: Boolean) : NotebookLink

    /** The editor on stored reminder [reminderId], from its set notice. */
    data class Edit(val reminderId: Int) : NotebookLink
    data object Completions : NotebookLink

    /** The Calendar section on the local day of [atMillis]. */
    data class Calendar(val atMillis: Long) : NotebookLink

    /** Settings > Notebook, where automatic backups are. */
    data object BackupSettings : NotebookLink

    /** Settings > Notebook with the recovery key check open. */
    data object KeyCheck : NotebookLink
}

private val OPEN_ACTIONS = setOf(
    ReminderIntents.ACTION_OPEN_REMINDERS,
    ReminderIntents.ACTION_OPEN_SNOOZE_PICKER,
    ReminderIntents.ACTION_OPEN_REMINDER_EDITOR,
    ReminderIntents.ACTION_OPEN_VOICE_COMPLETIONS,
    ReminderIntents.ACTION_OPEN_REMINDER_CALENDAR,
    ReminderIntents.ACTION_OPEN_BACKUP_SETTINGS,
    ReminderIntents.ACTION_OPEN_KEY_CHECK,
)

fun isNotebookLinkAction(action: String?): Boolean = action in OPEN_ACTIONS

/**
 * The link a Notebook notification tap carries: only with [expectedToken] and not on a relaunch
 * from Recents, which replays the notification's intent. Otherwise null. The extra readers return
 * null for an absent extra; [expectedToken] is asked for only once the intent carries a token.
 */
fun notebookLinkFor(
    action: String?,
    readString: (String) -> String?,
    readInt: (String) -> Int?,
    readLong: (String) -> Long?,
    launchedFromHistory: Boolean,
    expectedToken: () -> String,
): NotebookLink? {
    if (!isNotebookLinkAction(action) || launchedFromHistory) return null
    return try {
        val token = readString(ReminderIntents.EXTRA_LINK_TOKEN) ?: return null
        if (!MessageDigest.isEqual(token.encodeToByteArray(), expectedToken().encodeToByteArray())) return null
        val occurrence = run {
            val id = readInt(ReminderIntents.EXTRA_REMINDER_ID) ?: return@run null
            val fire = readLong(ReminderIntents.EXTRA_ORIGINAL_FIRE_MILLIS) ?: return@run null
            if (id >= 1 && fire > 0L) ReminderEventKey(id, fire) else null
        }
        when (action) {
            ReminderIntents.ACTION_OPEN_SNOOZE_PICKER ->
                occurrence?.let { NotebookLink.Occurrence(it, snooze = true) } ?: NotebookLink.Reminders
            ReminderIntents.ACTION_OPEN_REMINDER_EDITOR ->
                readInt(ReminderIntents.EXTRA_REMINDER_ID)?.takeIf { it >= 1 }?.let(NotebookLink::Edit) ?: NotebookLink.Reminders
            ReminderIntents.ACTION_OPEN_VOICE_COMPLETIONS -> NotebookLink.Completions
            ReminderIntents.ACTION_OPEN_BACKUP_SETTINGS -> NotebookLink.BackupSettings
            ReminderIntents.ACTION_OPEN_KEY_CHECK -> NotebookLink.KeyCheck
            ReminderIntents.ACTION_OPEN_REMINDER_CALENDAR ->
                readLong(ReminderIntents.EXTRA_ORIGINAL_FIRE_MILLIS)?.takeIf { it > 0L }?.let(NotebookLink::Calendar) ?: NotebookLink.Reminders
            else -> occurrence?.let { NotebookLink.Occurrence(it, snooze = false) } ?: NotebookLink.Reminders
        }
    } catch (e: RuntimeException) {
        // An extra that fails to unparcel throws; the platform source is cited at firmwarePickerRouteForNotification.
        null
    }
}

/**
 * The secret Notebook notification taps carry, kept in a file under [dir] so a notification posted
 * before the process ended still routes. A token that cannot be saved lasts for this process only.
 */
class NotebookLinkToken(private val dir: File) {
    val value: String by lazy { readOrCreate() }

    private fun readOrCreate(): String {
        val file = File(dir, FILE_NAME)
        try {
            file.takeIf { it.isFile }?.readText()?.takeIf { TOKEN.matches(it) }?.let { return it }
        } catch (e: IOException) {
            logger.w { "link token unreadable: ${e::class.simpleName}" }
        }
        val fresh = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        try {
            dir.mkdirs()
            val staging = File(dir, "$FILE_NAME.tmp")
            FileOutputStream(staging).use { out ->
                out.write(fresh.encodeToByteArray())
                out.fd.sync()
            }
            if (!staging.renameTo(file)) throw IOException("rename failed")
        } catch (e: IOException) {
            logger.w { "link token not saved: ${e::class.simpleName}" }
        }
        return fresh
    }

    private companion object {
        const val FILE_NAME = "notebook_link_token"
        val TOKEN = Regex("[0-9a-f]{32}")
        val logger = Logger.withTag("NotebookLinkToken")
    }
}

/** Hands a notification tap's link from the activity to the Notebook screen. */
class NotebookNavigator {
    private val _pending = MutableStateFlow<NotebookLink?>(null)
    val pending: StateFlow<NotebookLink?> = _pending.asStateFlow()

    fun open(link: NotebookLink) {
        _pending.value = link
    }

    fun consume(link: NotebookLink) {
        _pending.compareAndSet(link, null)
    }
}
