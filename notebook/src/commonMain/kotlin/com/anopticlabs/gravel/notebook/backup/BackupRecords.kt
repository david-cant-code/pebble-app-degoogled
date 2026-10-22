package com.anopticlabs.gravel.notebook.backup

import com.anopticlabs.gravel.notebook.reminders.RecurrenceFrequency
import com.anopticlabs.gravel.notebook.reminders.RecurrenceRule
import com.anopticlabs.gravel.notebook.reminders.ReminderEvent
import com.anopticlabs.gravel.notebook.reminders.ReminderItem
import com.anopticlabs.gravel.notebook.storage.ReminderFile
import com.anopticlabs.gravel.notebook.storage.ReminderOrigin
import com.anopticlabs.gravel.notebook.storage.isStorableReminder
import com.anopticlabs.gravel.notebook.storage.isStorableReminderEvent
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The bodies of the archive's records, JSON in these shapes: META, FOLDERS (a list of names),
 * FOLDER_TAGS (folder to tags), one NOTE per note, one CAPTURE per queued capture (its entry,
 * then its recording, see [BackupRecords.capture]), REMINDERS and EVENTS (lists). Times are epoch
 * millis. Reminder ids are those of the reminder store [BackupMeta.storeId] names, except where an
 * item names the id space it came from ([BackupReminder.origin], [BackupReminderEvent.reminderOrigin]).
 */
@Serializable
data class BackupMeta(val createdAtMillis: Long, val storeId: String)

@Serializable
data class BackupOrigin(val storeId: String, val id: Int)

/** A note under the id it has in the store that made the archive. */
@Serializable
data class BackupNote(
    val id: String,
    val folder: String,
    val name: String,
    val title: String? = null,
    val date: String? = null,
    val modifiedAtMillis: Long,
    val content: String,
)

@Serializable
data class BackupCapture(
    val id: String,
    val originalFileName: String,
    val queuedAtMillis: Long,
    val attempts: Int = 0,
    val flagged: Boolean = false,
    val zoneId: String? = null,
    val fromWatch: Boolean = false,
    val failure: String? = null,
)

@Serializable
data class BackupRecurrence(val frequency: String, val interval: Int = 1, val anchorMillis: Long = 0L)

@Serializable
data class BackupReminder(
    val id: Int,
    val remindAtMillis: Long,
    val message: String,
    val recurrence: BackupRecurrence? = null,
    val origin: BackupOrigin? = null,
    val timeToCheck: Boolean = false,
)

@Serializable
data class BackupReminderEvent(
    val reminderId: Int,
    val originalFireMillis: Long,
    val message: String,
    val firedAt: Long,
    val latestSnoozeFireAt: Long? = null,
    val snoozeChainCount: Int = 0,
    val completedAt: Long? = null,
    val cancelledAt: Long? = null,
    val snoozeDeliveredAt: Long? = null,
    val setInCalendar: Boolean = false,
    val lateNightFireAt: Long? = null,
    val reminderOrigin: BackupOrigin? = null,
)

/** Defaults are written so a reader that does not apply them sees every field. */
val BackupJson: Json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}

object BackupRecords {
    val FOLDERS: KSerializer<List<String>> = ListSerializer(String.serializer())
    val FOLDER_TAGS: KSerializer<Map<String, List<String>>> = MapSerializer(String.serializer(), ListSerializer(String.serializer()))
    val REMINDERS: KSerializer<List<BackupReminder>> = ListSerializer(BackupReminder.serializer())
    val EVENTS: KSerializer<List<BackupReminderEvent>> = ListSerializer(BackupReminderEvent.serializer())

    fun <T> encode(serializer: KSerializer<T>, value: T): ByteArray = BackupJson.encodeToString(serializer, value).encodeToByteArray()

    /** A body that does not decode is damage: the record authenticated, so a well-formed writer never wrote it. */
    fun <T> decode(serializer: KSerializer<T>, body: ByteArray): T = try {
        BackupJson.decodeFromString(serializer, body.decodeToString())
    } catch (e: IllegalArgumentException) {
        throw InvalidBackupException(BackupProblem.DAMAGED, "backup file is damaged: a record does not parse")
    }

    /** A CAPTURE body: the entry's JSON length (u32 BE), the entry, then the recording. */
    fun capture(entry: BackupCapture, audio: ByteArray): ByteArray {
        val json = encode(BackupCapture.serializer(), entry)
        val n = json.size
        return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + json + audio
    }

    class CaptureBody(val entry: BackupCapture, val audio: ByteArray)

    fun parseCapture(body: ByteArray): CaptureBody {
        val audioAt = audioOffset(body)
        return CaptureBody(decode(BackupCapture.serializer(), body.copyOfRange(4, audioAt)), body.copyOfRange(audioAt, body.size))
    }

    /** The entry of a CAPTURE body and its recording's length, without copying the recording. */
    fun parseCaptureEntry(body: ByteArray): Pair<BackupCapture, Int> {
        val audioAt = audioOffset(body)
        return decode(BackupCapture.serializer(), body.copyOfRange(4, audioAt)) to body.size - audioAt
    }

    private fun audioOffset(body: ByteArray): Int {
        if (body.size < 4) throw InvalidBackupException(BackupProblem.DAMAGED, "backup file is damaged: a capture record is cut short")
        val n = ((body[0].toLong() and 0xff) shl 24) or ((body[1].toLong() and 0xff) shl 16) or
            ((body[2].toLong() and 0xff) shl 8) or (body[3].toLong() and 0xff)
        if (n > body.size - 4) throw InvalidBackupException(BackupProblem.DAMAGED, "backup file is damaged: a capture record is cut short")
        return 4 + n.toInt()
    }
}

private val FREQUENCY_NAMES = RecurrenceFrequency.entries.associateBy { it.name.lowercase() }

/** An archive may not carry an id past this, so an archive of this store, whose ids a restore keeps, leaves room for the device's own. */
const val MAX_RESTORED_REMINDER_ID = ReminderFile.MAX_REMINDER_ID / 10

/** Where the reminder came from: the origin it carries, else its id in the archive's store [storeId]; null when malformed. */
fun BackupReminder.originIn(storeId: String): ReminderOrigin? = originOf(origin, storeId, id)

fun BackupReminderEvent.originIn(storeId: String): ReminderOrigin? = originOf(reminderOrigin, storeId, reminderId)

private fun originOf(carried: BackupOrigin?, storeId: String, id: Int): ReminderOrigin? =
    (carried?.let { ReminderOrigin(it.storeId, it.id) } ?: ReminderOrigin(storeId, id)).takeIf { it.isWellFormed() }

/** The stored item under [localId], or null when the archive's entry is one the store or the restore would refuse. */
fun BackupReminder.toItem(localId: Int = id): ReminderItem? {
    if (id > MAX_RESTORED_REMINDER_ID) return null
    val rule = recurrence?.let { RecurrenceRule(FREQUENCY_NAMES[it.frequency] ?: return null, it.interval, it.anchorMillis) }
    return ReminderItem(localId, remindAtMillis, message, rule, timeToCheck).takeIf { isStorableReminder(it) }
}

fun ReminderItem.toBackup(origin: ReminderOrigin? = null): BackupReminder =
    BackupReminder(
        id, remindAtMillis, message, recurrence?.let { BackupRecurrence(it.frequency.name.lowercase(), it.interval, it.anchorMillis) },
        origin?.let { BackupOrigin(it.storeId, it.id) }, timeToCheck,
    )

/** The stored event under [localId], or null when the archive's entry is one the store would refuse. */
fun BackupReminderEvent.toEvent(localId: Int = reminderId): ReminderEvent? =
    ReminderEvent(
        localId, originalFireMillis, message, firedAt, latestSnoozeFireAt, snoozeChainCount, completedAt, cancelledAt, snoozeDeliveredAt,
        setInCalendar, lateNightFireAt,
    ).takeIf { isStorableReminderEvent(it) && reminderId in 1..ReminderFile.MAX_REMINDER_ID }

fun ReminderEvent.toBackup(origin: ReminderOrigin? = null): BackupReminderEvent =
    BackupReminderEvent(
        reminderId, originalFireMillis, message, firedAt, latestSnoozeFireAt, snoozeChainCount, completedAt, cancelledAt, snoozeDeliveredAt,
        setInCalendar, lateNightFireAt, origin?.let { BackupOrigin(it.storeId, it.id) },
    )
