package com.anopticlabs.gravel.notebook.storage

import com.anopticlabs.gravel.notebook.reminders.ReminderEvent
import com.anopticlabs.gravel.notebook.reminders.ReminderEventKey
import com.anopticlabs.gravel.notebook.reminders.appendFire
import com.anopticlabs.gravel.notebook.reminders.cancelAhead
import com.anopticlabs.gravel.notebook.reminders.clearPendingSnoozes
import com.anopticlabs.gravel.notebook.reminders.completeAhead
import com.anopticlabs.gravel.notebook.reminders.markSnoozeDelivered
import com.anopticlabs.gravel.notebook.reminders.pruneEventsBefore
import com.anopticlabs.gravel.notebook.reminders.recordSnooze
import com.anopticlabs.gravel.notebook.reminders.removeOccurrence
import com.anopticlabs.gravel.notebook.reminders.stampCancelled
import com.anopticlabs.gravel.notebook.reminders.stampCompleted
import com.anopticlabs.gravel.notebook.reminders.switchStatus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.TimeZone
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * The fired-occurrence log in `reminder_events`, one event per [ReminderEventKey]; each mutator
 * applies the matching transition from the reminders package under the file's lock.
 */
class ReminderEventStore internal constructor(root: File, crypto: Cryptographer, private val maxBytes: Long) {

    constructor(root: File, crypto: Cryptographer) : this(root, crypto, EncryptedJsonFile.LOG_FILE_BYTES)

    private val list = EncryptedJsonFile.list(
        EncryptedFiles(root, crypto),
        File(root, FILE_NAME),
        ReminderEvent.serializer(),
        maxBytes,
        ::requireWellFormed,
    )

    /** Counts the writes to the event log. */
    val revision: StateFlow<Long> get() = list.revision

    fun readAll(strict: Boolean = false): List<ReminderEvent> = list.read(strict)

    /** Sets a damaged file aside for an empty one; see [EncryptedJsonFile.setAsideIfDamaged]. */
    suspend fun setAsideIfDamaged(): Boolean = list.setAsideIfDamaged()

    /** Sets the log aside whatever it holds, for when the reminders it numbers are set aside. */
    suspend fun setAside(): Boolean = list.setAside()

    /** Returns whether [event] was added; false means its occurrence is already logged. */
    suspend fun appendFire(event: ReminderEvent): Boolean = list.modify { current ->
        val updated = appendFire(current, event)
        Modified(updated, updated.size > current.size)
    }

    /** False means no occurrence is logged under [key]; a stamp already there stays and still counts as true. */
    suspend fun markCompleted(key: ReminderEventKey, completedAtMillis: Long): Boolean =
        stamp(key) { stampCompleted(it, key, completedAtMillis) }

    suspend fun markCancelled(key: ReminderEventKey, cancelledAtMillis: Long): Boolean =
        stamp(key) { stampCancelled(it, key, cancelledAtMillis) }

    suspend fun markSnoozeDelivered(key: ReminderEventKey, deliveredAtMillis: Long): Boolean =
        stamp(key) { markSnoozeDelivered(it, key, deliveredAtMillis) }

    suspend fun completeAhead(key: ReminderEventKey, message: String, nowMillis: Long) {
        list.update { completeAhead(it, key, message, nowMillis) }
    }

    suspend fun cancelAhead(key: ReminderEventKey, message: String, nowMillis: Long) {
        list.update { cancelAhead(it, key, message, nowMillis) }
    }

    /** The updated event, or null with nothing written when [key] has no open occurrence. */
    suspend fun recordSnooze(key: ReminderEventKey, nextFireAtMillis: Long, bumpChain: Boolean, nowMillis: Long, zone: TimeZone): ReminderEvent? =
        list.modify { current ->
            val recorded = recordSnooze(current, key, nextFireAtMillis, bumpChain, nowMillis, zone)
            Modified(recorded.events, recorded.updated)
        }

    /** Forgets the pending re-fire of every open occurrence of [reminderId]; the keys returned name the alarms to cancel. */
    suspend fun clearPendingSnoozes(reminderId: Int, nowMillis: Long, zone: TimeZone): List<ReminderEventKey> = list.modify { current ->
        val (updated, cleared) = clearPendingSnoozes(current, reminderId, nowMillis, zone)
        Modified(updated, cleared)
    }

    /** The calendar's status switch ([switchStatus]); false, with nothing written, when it does not apply. */
    suspend fun switchStatus(key: ReminderEventKey, completed: Boolean, nowMillis: Long, zone: TimeZone): Boolean = list.modify { current ->
        val switched = switchStatus(current, key, completed, nowMillis, zone)
        Modified(switched ?: current, switched != null)
    }

    /** Removes [key] as [removeOccurrence] does; false when it was not logged. */
    suspend fun removeOccurrence(key: ReminderEventKey, nowMillis: Long, zone: TimeZone): Boolean = list.modify { current ->
        Modified(removeOccurrence(current, key, nowMillis, zone), current.any { it.key == key })
    }

    /** Drops events whose scheduled fire, actual fire and pending snooze all lie before [cutoffMillis]; returns how many. */
    suspend fun pruneBefore(cutoffMillis: Long): Int = list.modify { current ->
        val kept = pruneEventsBefore(current, cutoffMillis)
        Modified(kept, current.size - kept.size)
    }

    /** Appends the events whose keys are new while the file stays under its cap; the keys added are returned. */
    suspend fun addAllSkippingExistingKeys(events: List<ReminderEvent>): BulkAddResult<ReminderEventKey> = list.modify { current ->
        val known = current.map { it.key }.toMutableSet()
        val added = mutableListOf<ReminderEvent>()
        var existing = 0
        var dropped = 0
        var bytes = NotebookJson.encodeToString(ListSerializer(ReminderEvent.serializer()), current).encodeToByteArray().size
        for (event in events) {
            if (event.key in known) {
                existing++
                continue
            }
            val eventBytes = NotebookJson.encodeToString(ReminderEvent.serializer(), event).encodeToByteArray().size + 1
            if (bytes + eventBytes + NotebookEnvelope.MIN_LEN > maxBytes) {
                dropped++
                continue
            }
            known += event.key
            added += event
            bytes += eventBytes
        }
        Modified(current + added, BulkAddResult(added.map { it.key }, existing, dropped))
    }

    private suspend fun stamp(key: ReminderEventKey, transition: (List<ReminderEvent>) -> List<ReminderEvent>): Boolean =
        list.modify { current -> Modified(transition(current), current.any { it.key == key }) }

    companion object {
        const val FILE_NAME = "reminder_events"

        private fun requireWellFormed(events: List<ReminderEvent>) {
            require(events.all { isStorableReminderEvent(it) }) { "event the store refuses" }
            require(events.distinctBy { it.key }.size == events.size) { "events share an occurrence key" }
        }
    }
}
