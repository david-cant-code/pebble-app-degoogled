package com.anopticlabs.gravel.notebook.storage

import com.anopticlabs.gravel.notebook.backup.MAX_RESTORED_REMINDER_ID
import com.anopticlabs.gravel.notebook.reminders.ReminderItem
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.security.SecureRandom

/** Every reminder id has been handed out; ids are never reused. */
class ReminderIdsExhaustedException : IllegalStateException("reminder ids exhausted")

/** What a bulk add did: the keys it added, how many were already present, how many it had no room for. */
data class BulkAddResult<K>(val added: List<K>, val existing: Int, val dropped: Int)

/** The active reminders in `reminders`, a [ReminderFile] whose next id [create] takes and advances under the lock. */
class ReminderStore internal constructor(
    root: File,
    crypto: Cryptographer,
    private val maxBytes: Long,
    private val firstId: () -> Int = { FIRST_ID_FROM + random.nextInt(FIRST_ID_SPAN) },
) {

    constructor(root: File, crypto: Cryptographer) : this(root, crypto, EncryptedJsonFile.SMALL_FILE_BYTES)

    private val file = EncryptedJsonFile(
        EncryptedFiles(root, crypto),
        File(root, FILE_NAME),
        ReminderFile.serializer(),
        maxBytes,
        ::ReminderFile,
        ReminderFile::requireWellFormed,
    )

    /** Counts the writes to the reminders file, the next id and adoptions included. */
    val revision: StateFlow<Long> get() = file.revision

    fun readAll(strict: Boolean = false): List<ReminderItem> = file.read(strict).reminders

    fun readFile(strict: Boolean = false): ReminderFile = file.read(strict)

    /** This store's identity, written first if the file has none yet. */
    suspend fun identity(): String = file.modify { stored ->
        val named = started(stored)
        Modified(named, named.storeId)
    }

    /**
     * The local id for each of [origins], recorded so an origin always gets the same one: its own id
     * when it names this store, else an earlier adoption's, else the next from the counter, past this
     * store's own ids among [origins] up to [MAX_RESTORED_REMINDER_ID]. Stops where the file would
     * leave under a sixteenth of its cap free; an origin left out found no room, or names an own id
     * an adoption holds or one past [MAX_RESTORED_REMINDER_ID].
     */
    suspend fun adopt(origins: List<ReminderOrigin>): Map<ReminderOrigin, Int> = modify { current ->
        val byOrigin = current.adopted.entries.associate { (id, origin) -> origin to id }
        val result = LinkedHashMap<ReminderOrigin, Int>()
        val added = LinkedHashMap<Int, ReminderOrigin>()
        val own = origins.filter { it.storeId == current.storeId && it.id <= MAX_RESTORED_REMINDER_ID }
        var next = maxOf(current.nextId, 1 + (own.maxOfOrNull { it.id } ?: 0))
        var bytes = NotebookJson.encodeToString(ReminderFile.serializer(), current).encodeToByteArray().size.toLong()
        val limit = maxBytes - maxBytes / 16
        for (origin in origins) {
            if (origin in result) continue
            if (origin.storeId == current.storeId) {
                if (origin.id <= MAX_RESTORED_REMINDER_ID && origin.id !in current.adopted) result[origin] = origin.id
                continue
            }
            val known = byOrigin[origin]
            if (known != null) {
                result[origin] = known
                continue
            }
            // The entry as written: "<id>":<origin>, with its comma.
            val entryBytes = NotebookJson.encodeToString(ReminderOrigin.serializer(), origin).encodeToByteArray().size + next.toString().length + 4
            if (next > ReminderFile.MAX_REMINDER_ID || bytes + entryBytes > limit) continue
            result[origin] = next
            added[next] = origin
            bytes += entryBytes
            next++
        }
        Modified(if (added.isEmpty()) current else current.copy(nextId = next, adopted = current.adopted + added), result)
    }

    /** Drops the adoptions of ids that are neither a stored reminder nor in [inUse]; returns how many went. */
    suspend fun forgetAdoptions(inUse: Set<Int>): Int = modify { current ->
        val stored = current.reminders.mapTo(HashSet()) { it.id }
        val kept = current.adopted.filterKeys { it in stored || it in inUse }
        Modified(current.copy(adopted = kept), current.adopted.size - kept.size)
    }

    /** [build] runs under the file's lock, so it must not call into a store, and must keep the id it is given. */
    suspend fun create(build: (id: Int) -> ReminderItem): ReminderItem = modify { current ->
        val id = current.nextId
        if (id > ReminderFile.MAX_REMINDER_ID) throw ReminderIdsExhaustedException()
        val item = build(id)
        require(item.id == id) { "reminder built with id ${item.id}, not $id" }
        Modified(current.copy(nextId = id + 1, reminders = current.reminders + item), item)
    }

    /** Replaces the reminder with [item]'s id; false, and nothing written, when it is gone. */
    suspend fun replace(item: ReminderItem): Boolean = modify { current ->
        val index = current.reminders.indexOfFirst { it.id == item.id }
        if (index < 0) {
            Modified(current, false)
        } else {
            Modified(current.copy(reminders = current.reminders.toMutableList().also { it[index] = item }), true)
        }
    }

    /**
     * Applies [transform] to the stored item with [id] under the lock; a null result removes it.
     * Returns what [transform] returned, or null when no item has the id, so a caller arms an
     * alarm only for the item it asked to store. [transform] must not call into a store.
     */
    suspend fun update(id: Int, transform: (ReminderItem) -> ReminderItem?): ReminderItem? = modify { current ->
        val index = current.reminders.indexOfFirst { it.id == id }
        if (index < 0) return@modify Modified(current, null)
        val updated = transform(current.reminders[index])
        require(updated == null || updated.id == id) { "reminder $id transformed into id ${updated?.id}" }
        val reminders = current.reminders.toMutableList().also { if (updated == null) it.removeAt(index) else it[index] = updated }
        Modified(current.copy(reminders = reminders), updated)
    }

    suspend fun remove(id: Int): Boolean = modify { current ->
        val kept = current.reminders.filterNot { it.id == id }
        Modified(current.copy(reminders = kept), kept.size != current.reminders.size)
    }

    /**
     * Appends the items whose ids are new while the file stays under its cap and moves the next id
     * past them; the ids added, the number already present and the number left out are returned.
     */
    suspend fun addAllSkippingExistingIds(items: List<ReminderItem>): BulkAddResult<Int> = modify { current ->
        val known = current.reminders.map { it.id }.toMutableSet()
        val added = mutableListOf<ReminderItem>()
        var existing = 0
        var dropped = 0
        var bytes = NotebookJson.encodeToString(ReminderFile.serializer(), current).encodeToByteArray().size
        for (item in items) {
            if (item.id in known) {
                existing++
                continue
            }
            val itemBytes = NotebookJson.encodeToString(ReminderItem.serializer(), item).encodeToByteArray().size + 1
            if (bytes + itemBytes + NotebookEnvelope.MIN_LEN > maxBytes) {
                dropped++
                continue
            }
            known += item.id
            added += item
            bytes += itemBytes
        }
        val nextId = maxOf(current.nextId, (added.maxOfOrNull { it.id } ?: 0) + 1)
        Modified(current.copy(nextId = nextId, reminders = current.reminders + added), BulkAddResult(added.map { it.id }, existing, dropped))
    }

    suspend fun removeByIds(ids: Collection<Int>): Int = modify { current ->
        val drop = ids.toSet()
        val kept = current.reminders.filterNot { it.id in drop }
        Modified(current.copy(reminders = kept), current.reminders.size - kept.size)
    }

    /** Sets a damaged reminders file aside, so the store starts again as a new one; see [started]. */
    suspend fun startFreshIfDamaged(): Boolean = file.setAsideIfDamaged()

    /** [file]'s modify on the file as [started], so a write carries its identity; a block that changes nothing else writes nothing. */
    private suspend fun <R> modify(block: (ReminderFile) -> Modified<ReminderFile, R>): R = file.modify { stored ->
        val named = started(stored)
        val modified = block(named)
        if (modified.value == named) Modified(stored, modified.result) else modified
    }

    /** A store not yet written takes an identity, and an empty one a first id from [firstId], random in production. */
    private fun started(stored: ReminderFile): ReminderFile = when {
        stored.storeId.isNotEmpty() -> stored
        stored == ReminderFile() -> ReminderFile(nextId = firstId(), storeId = newIdentity())
        else -> stored.copy(storeId = newIdentity())
    }

    private fun newIdentity(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val FILE_NAME = "reminders"

        /** First ids stay at least 2^24 below the id a backup may carry ([MAX_RESTORED_REMINDER_ID]), so a store's own backups restore. */
        const val FIRST_ID_FROM = MAX_RESTORED_REMINDER_ID / 2
        const val FIRST_ID_SPAN = MAX_RESTORED_REMINDER_ID / 2 - (1 shl 24)
        private val random = SecureRandom()
    }
}
