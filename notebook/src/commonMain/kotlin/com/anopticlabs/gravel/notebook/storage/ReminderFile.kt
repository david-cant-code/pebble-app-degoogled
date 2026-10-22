package com.anopticlabs.gravel.notebook.storage

import com.anopticlabs.gravel.notebook.reminders.MAX_REMINDER_MESSAGE_CHARS
import com.anopticlabs.gravel.notebook.reminders.MAX_SNOOZE_CHAIN
import com.anopticlabs.gravel.notebook.reminders.Recurrence
import com.anopticlabs.gravel.notebook.reminders.ReminderEvent
import com.anopticlabs.gravel.notebook.reminders.ReminderItem
import kotlinx.serialization.Serializable

/**
 * The `reminders` file: the active reminders and the next id to hand out, so a deleted id is never reused. [storeId]
 * names this id space (empty until the first write); [adopted] maps ids given to restored reminders to their origin.
 */
@Serializable
data class ReminderFile(
    val nextId: Int = 1,
    val reminders: List<ReminderItem> = emptyList(),
    val storeId: String = "",
    val adopted: Map<Int, ReminderOrigin> = emptyMap(),
) {
    companion object {
        /** Ids stay far below Int.MAX_VALUE because an id also serves as an alarm and notification id. */
        const val MAX_REMINDER_ID = 1_000_000_000

        fun requireWellFormed(file: ReminderFile) {
            require(file.nextId in 1..MAX_REMINDER_ID + 1) { "next reminder id out of range" }
            require(file.reminders.all { it.id < file.nextId }) { "reminder id at or past the next id" }
            require(file.reminders.distinctBy { it.id }.size == file.reminders.size) { "reminders share an id" }
            require(file.reminders.all { isStorableReminder(it) }) { "reminder the store refuses" }
            require(file.storeId.isEmpty() || isStoreIdentity(file.storeId)) { "store identity malformed" }
            require(file.adopted.keys.all { it in 1 until file.nextId }) { "adopted id out of range" }
            require(file.adopted.values.all { it.isWellFormed() && it.storeId != file.storeId }) { "adopted origin malformed" }
            require(file.adopted.values.toSet().size == file.adopted.size) { "an origin adopted twice" }
        }
    }
}

/** A reminder id as the id space [storeId] numbers it. */
@Serializable
data class ReminderOrigin(val storeId: String, val id: Int) {
    fun isWellFormed() = isStoreIdentity(storeId) && id in 1..ReminderFile.MAX_REMINDER_ID
}

/** 32 lowercase hex digits, 128 random bits. */
fun isStoreIdentity(value: String): Boolean = value.length == 32 && value.all { it in '0'..'9' || it in 'a'..'f' }

/** An item's own rules: the id in range, the fire instant in the stored window, the message capped, the recurrence rule usable. */
fun isStorableReminder(item: ReminderItem): Boolean =
    item.id in 1..ReminderFile.MAX_REMINDER_ID &&
        isStoredInstant(item.remindAtMillis) &&
        item.message.length <= MAX_REMINDER_MESSAGE_CHARS &&
        (item.recurrence == null || Recurrence.isValid(item.recurrence))

/** Every instant in the stored window, the chain within its cap, the message capped. */
fun isStorableReminderEvent(event: ReminderEvent): Boolean =
    event.reminderId in 1..ReminderFile.MAX_REMINDER_ID &&
        isStoredInstant(event.originalFireMillis) &&
        isStoredInstant(event.firedAt) &&
        event.latestSnoozeFireAt.let { it == null || isStoredInstant(it) } &&
        event.completedAt.let { it == null || isStoredInstant(it) } &&
        event.cancelledAt.let { it == null || isStoredInstant(it) } &&
        event.snoozeDeliveredAt.let { it == null || isStoredInstant(it) } &&
        event.lateNightFireAt.let { it == null || isStoredInstant(it) } &&
        event.snoozeChainCount in 0..MAX_SNOOZE_CHAIN &&
        event.message.length <= MAX_REMINDER_MESSAGE_CHARS
