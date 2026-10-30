package com.anopticlabs.gravel.notebook.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList

/** An in-memory folder app: documents by handle, a new handle on every rename unless [keepIdOnRename], and failures to inject. */
internal class FakeFolder : BackupFolderOps<String> {
    class Doc(var name: String, var bytes: ByteArray, val folder: String)

    val docs = LinkedHashMap<String, Doc>()
    val access = HashMap<String, FolderAccess>()
    val held = HashSet<String>()
    val released = CopyOnWriteArrayList<String>()
    @Volatile var failCreate: AutoBackupProblem? = null
    @Volatile var failRename = false
    @Volatile var keepIdOnRename = false
    @Volatile var holdFails = false
    @Volatile var failDelete = false

    /** Changes the bytes a write leaves, so the read-back fails. */
    @Volatile var corruptWrites = false

    /** Changes a renamed document's bytes, as a folder app that mangles a file would. */
    @Volatile var corruptOnRename = false

    /** Runs after a rename, outside the fake's lock; a test can block there. */
    @Volatile var afterRename: (() -> Unit)? = null
    private var next = 0

    @Synchronized fun named(name: String): List<String> = docs.filterValues { it.name == name }.keys.toList()

    @Synchronized fun put(folder: String, name: String, bytes: ByteArray): String = "doc${next++}".also { docs[it] = Doc(name, bytes, folder) }

    override fun access(folder: String): FolderAccess = access[folder] ?: FolderAccess.OK

    @Synchronized override fun create(folder: String, name: String): String {
        failCreate?.let { throw FolderException(it) }
        return put(folder, unique(folder, name), ByteArray(0))
    }

    override fun rename(handle: String, name: String): String {
        val h = synchronized(this) {
            if (failRename) throw IOException("rename refused")
            val doc = docs.remove(handle) ?: throw FileNotFoundException(handle)
            doc.name = unique(doc.folder, name)
            if (corruptOnRename) doc.bytes = doc.bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
            val h = if (keepIdOnRename) handle else "doc${next++}"
            docs[h] = doc
            h
        }
        afterRename?.invoke()
        return h
    }

    @Synchronized override fun children(folder: String): List<FolderChild<String>> =
        docs.filterValues { it.folder == folder }.map { (h, d) -> FolderChild(h, d.name, d.bytes.size.toLong()) }

    override fun folderName(folder: String): String = "Backups"

    @Synchronized override fun holdFolder(folder: String): Boolean {
        if (holdFails) return false
        held += folder
        return true
    }

    @Synchronized override fun releaseFolder(folder: String) {
        released += folder
        held -= folder
    }

    /** Runs as the grants are listed, for a step that lands at that moment. */
    @Volatile var onListHeld: (() -> Unit)? = null

    @Synchronized override fun heldFolders(): List<String> {
        onListHeld?.invoke()
        return held.toList()
    }

    override fun encode(handle: String): String = handle

    override fun decode(text: String): String = text

    /** Handles the folder app does not describe this time, as one that cannot be reached would not. */
    val failDescribe: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    @Synchronized override fun describe(handle: String): BackupDocumentInfo? =
        if (handle in failDescribe) null else docs[handle]?.let { BackupDocumentInfo(it.name, it.bytes.size.toLong()) }

    @Synchronized override fun openWrite(handle: String): OutputStream {
        val doc = docs[handle] ?: throw FileNotFoundException(handle)
        doc.bytes = ByteArray(0)
        return object : ByteArrayOutputStream() {
            override fun close() {
                val written = toByteArray()
                if (corruptWrites && written.isNotEmpty()) written[written.size / 2] = (written[written.size / 2].toInt() xor 1).toByte()
                synchronized(this@FakeFolder) { doc.bytes = written }
            }
        }
    }

    /** Handles whose reads fail this time, as a folder app that cannot fetch them now would. */
    val failRead: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    @Synchronized override fun openRead(handle: String): InputStream {
        if (handle in failRead) throw IOException("not fetched")
        return ByteArrayInputStream(docs[handle]?.bytes ?: throw FileNotFoundException(handle))
    }

    @Synchronized override fun delete(handle: String): Boolean = !failDelete && docs.remove(handle) != null

    private fun unique(folder: String, name: String): String {
        if (docs.values.none { it.folder == folder && it.name == name }) return name
        var i = 1
        while (docs.values.any { it.folder == folder && it.name == "$name ($i)" }) i++
        return "$name ($i)"
    }
}

internal class FakeNotices : AutoBackupNotices {
    val failed = CopyOnWriteArrayList<AutoBackupProblem>()
    val shortfalls = CopyOnWriteArrayList<CreatedBackup>()
    @Volatile var clears = 0
    @Volatile var shortfallClears = 0

    override fun clearShortfall() {
        shortfallClears++
    }

    override fun failed(problem: AutoBackupProblem) {
        failed += problem
    }

    override fun shortfall(created: CreatedBackup) {
        shortfalls += created
    }

    override fun clearFailure() {
        clears++
    }

    data class FarFewer(val before: AutoBackupEntry, val after: AutoBackupEntry, val notes: Boolean, val remindersGone: Int)

    val farFewer = CopyOnWriteArrayList<FarFewer>()
    val damaged = CopyOnWriteArrayList<Int>()
    val missing = CopyOnWriteArrayList<Int>()
    /** Whether the last damaged or missing notice said a run will write a new backup. */
    @Volatile var backupsOn: Boolean? = null
    val stale = CopyOnWriteArrayList<Long>()
    @Volatile var staleClears = 0
    @Volatile var keyReminders = 0
    @Volatile var keyClears = 0

    override fun farFewer(before: AutoBackupEntry, after: AutoBackupEntry, notes: Boolean, remindersGone: Int) {
        farFewer += FarFewer(before, after, notes, remindersGone)
    }

    override fun damaged(count: Int, backupsOn: Boolean) {
        damaged += count
        this.backupsOn = backupsOn
    }

    override fun missing(count: Int, backupsOn: Boolean) {
        missing += count
        this.backupsOn = backupsOn
    }

    override fun stale(lastConfirmedAtMillis: Long) {
        stale += lastConfirmedAtMillis
    }

    override fun clearStale() {
        staleClears++
    }

    override fun keyCheckDue() {
        keyReminders++
    }

    override fun clearKeyCheck() {
        keyClears++
    }
}

internal class FakeCheckAlarm : CheckAlarm {
    @Volatile var at: Long? = null
    @Volatile var sets = 0
    @Volatile var cancels = 0

    override fun set(atMillis: Long) {
        at = atMillis
        sets++
    }

    override fun cancel() {
        at = null
        cancels++
    }
}

internal class FakeAutoScheduler : AutoBackupScheduler {
    val afterChange = CopyOnWriteArrayList<String?>()
    /** "periodic" and "cancel" in the order they came. */
    val calls = CopyOnWriteArrayList<String>()
    @Volatile var periodic = 0
    @Volatile var cancels = 0

    override fun afterChange(running: String?) {
        afterChange += running
    }

    override fun schedulePeriodic() {
        periodic++
        calls += "periodic"
    }

    @Volatile var soon = 0

    override fun runSoon() {
        soon++
        calls += "soon"
    }

    override fun cancel() {
        cancels++
        calls += "cancel"
    }
}
