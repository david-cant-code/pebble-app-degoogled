package com.anopticlabs.gravel.notebook.backup

import com.anopticlabs.gravel.notebook.storage.CorruptFileException
import com.anopticlabs.gravel.notebook.storage.Cryptographer
import com.anopticlabs.gravel.notebook.storage.EncryptedFiles
import com.anopticlabs.gravel.notebook.storage.EncryptedJsonFile
import com.anopticlabs.gravel.notebook.storage.NotebookLocks
import com.anopticlabs.gravel.notebook.storage.PersistedFormatException
import com.anopticlabs.gravel.notebook.storage.TransientCryptoException
import com.anopticlabs.gravel.notebook.storage.isPersistedFormatFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.file.Files

/** Why an automatic backup did not happen. */
enum class AutoBackupProblem {
    /** Gravel no longer holds access to the folder. */
    NO_ACCESS,
    FOLDER_GONE,

    /** The folder's app would not make the file; it may take one later. */
    NOT_CREATED,

    /** The stored key, or the Keystore key that seals it, could not be read. */
    KEY_MISSING,

    /** A Notebook or automatic backup file is damaged. */
    DAMAGED,

    /** Setup with a key of its own found automatic backups already set up. */
    ALREADY_SET_UP,

    /** The Notebook could not be read this time. */
    NOT_READ,
    NO_SPACE,

    /** No checked backup came of the run; a document it made is removed where the folder app allows. */
    NOT_SAVED,

    /** A backup was written and checked but could not be recorded; the file stays in the folder. */
    NOT_RECORDED,
    ;

    /** A retry cannot help until the user acts; reported at once. */
    val permanent: Boolean
        get() = this == NO_ACCESS || this == FOLDER_GONE || this == KEY_MISSING || this == DAMAGED || this == ALREADY_SET_UP
}

/** The last failed run: [tries] counts the failed runs in a row, and [reported] once the user was told. */
@Serializable
data class AutoBackupFailure(val problem: AutoBackupProblem, val atMillis: Long, val tries: Int, val reported: Boolean)

/** A backup file Gravel wrote and checked; [uri] is the folder app's handle for it. */
@Serializable
data class AutoBackupEntry(
    val uri: String,
    val name: String,
    val createdAtMillis: Long,
    val sizeBytes: Long,
    val sha256: String,
    val notes: Int,
    val reminders: Int,
    val events: Int,
    /** [NotebookBackup.fingerprint] when the run started. */
    val fingerprint: String,
    /** [StoredKey.createdAtMillis] of the key it is sealed with. */
    val keyCreatedAtMillis: Long,
    /** When it was last read back, whatever came of it. */
    val checkedAtMillis: Long = createdAtMillis,
    /** It failed a read-back; Gravel keeps it and reads it again a week later. */
    val damaged: Boolean = false,
    /** When a read of it first failed without an answer, since its last whole read; a week of that counts as damage. */
    val unreadSinceMillis: Long? = null,
    /** The last backup before one that held far fewer items; thinning keeps it. */
    val keep: Boolean = false,
    /** The ids of the upcoming reminders it holds, kept on the newest entry only. */
    val upcomingIds: List<Int>? = null,
)

/** The key reminders' record; Not now on [notNowPointMillis] brings one more notice at [notNowUntilMillis]. */
@Serializable
data class KeyChecks(
    val createdAtMillis: Long? = null,
    val checkedAtMillis: Long? = null,
    val checks: Int = 0,
    val noticedPointMillis: Long? = null,
    val notNowPointMillis: Long? = null,
    val notNowUntilMillis: Long? = null,
    val notNowNoticed: Boolean = false,
)

/**
 * Settings and record of automatic backups. [folder] is the handle of the folder the user granted
 * and [backups] lists, oldest first, the files Gravel wrote there.
 */
@Serializable
data class AutoBackupState(
    val enabled: Boolean = false,
    val folder: String? = null,
    val folderName: String? = null,
    val backups: List<AutoBackupEntry> = emptyList(),
    /** The last time a run wrote a backup or read the newest one back as the current Notebook. */
    val lastConfirmedAtMillis: Long? = null,
    val failure: AutoBackupFailure? = null,
    /** Files thinning took off [backups] whose removal from the folder is not yet confirmed. */
    val deleting: List<AutoBackupEntry> = emptyList(),
    /** A kept backup was found damaged or gone, so the next run writes one even when nothing changed. */
    val writeNext: Boolean = false,
    /** When a check first found no confirmed backup for a day; it warns an hour later. */
    val staleSinceMillis: Long? = null,
    val staleWarnedAtMillis: Long? = null,
    val key: KeyChecks = KeyChecks(),
) {
    val setUp: Boolean get() = folder != null
}

/** The stored recovery key, which the caller wipes, and when setup made or took it. */
class StoredKey(val chars: CharArray, val createdAtMillis: Long)

/**
 * The automatic backup key and [AutoBackupState], each in its own file under the Notebook root,
 * sealed like the notes; the key file is written only by setup, so the frequent state writes
 * never rewrite it. The key is stored as its 64 ASCII bytes, so no string copy of it is made
 * here, followed by when setup made or took it, which each backup records so a run can tell
 * which key sealed it.
 */
class AutoBackupStore(root: File, crypto: Cryptographer) {
    private val files = EncryptedFiles(root, crypto)
    private val keyFile = File(root, KEY_FILE)
    private val keyLock = NotebookLocks.lockFor(KEY_FILE)

    private val stateFile = EncryptedJsonFile(
        files, File(root, STATE_FILE), AutoBackupState.serializer(), EncryptedJsonFile.LOG_FILE_BYTES, ::AutoBackupState,
        { require(it.backups.distinctBy { e -> e.uri }.size == it.backups.size) { "two backups share a handle" } },
    )

    /** Counts the state writes that landed. */
    val revision: StateFlow<Long> get() = stateFile.revision

    fun readState(strict: Boolean = false): AutoBackupState = stateFile.read(strict)

    suspend fun updateState(block: (AutoBackupState) -> AutoBackupState): AutoBackupState = stateFile.update(block)

    /** The stored key; null when none is stored. Throws when one exists but cannot be read or is not a key. */
    fun readKey(): StoredKey? {
        val bytes = files.read(keyFile, strict = true, quarantineCorrupt = false, maxBytes = KEY_BYTES) ?: return null
        try {
            val n = RecoveryKey.LENGTH
            if (bytes.size != n + Long.SIZE_BYTES || (0 until n).any { bytes[it].toInt().toChar() !in RecoveryKey.ALPHABET }) {
                throw PersistedFormatException(KEY_FILE, IllegalStateException("not a recovery key"))
            }
            return StoredKey(CharArray(n) { bytes[it].toInt().toChar() }, ByteBuffer.wrap(bytes, n, Long.SIZE_BYTES).long)
        } finally {
            bytes.fill(0)
        }
    }

    /** The newest backup not found damaged that the stored key sealed, with that key, which the caller wipes; else null. */
    fun newestRestorable(): Pair<AutoBackupEntry, StoredKey>? {
        val (state, key) = try {
            readState(strict = true) to readKey()
        } catch (e: Exception) {
            return null
        }
        if (key == null) return null
        val entry = state.backups.lastOrNull { !it.damaged && it.keyCreatedAtMillis == key.createdAtMillis }.takeIf { state.setUp }
        if (entry == null) key.chars.fill('\u0000')
        return entry?.let { it to key }
    }

    /** When the newest backup not found damaged of the key the state records was made, without reading the key; null when there is none, no key file, or its folder is not among [heldFolders]. */
    fun newestBackupAt(heldFolders: () -> Collection<String>): Long? = try {
        val s = readState(strict = true)
        if (!keyFile.exists()) return null
        s.backups.lastOrNull { !it.damaged && it.keyCreatedAtMillis == s.key.createdAtMillis }
            ?.takeIf { s.setUp && s.folder in heldFolders() }?.createdAtMillis
    } catch (e: Exception) {
        null
    }

    /** Whether only a fresh setup helps: the state file is damaged, or the key file is damaged or gone while set up. */
    fun needsNewSetup(): Boolean = try {
        readState(strict = true).setUp && keyLost()
    } catch (e: Exception) {
        isDamage(e)
    }

    private fun keyLost(): Boolean = try {
        val key = readKey() ?: return true
        key.chars.fill('\u0000')
        false
    } catch (e: Exception) {
        isDamage(e)
    }

    /** Deletes the key file and sets the state file aside when [needsNewSetup], so setup starts as on a phone that never had one; returns whether it did. */
    suspend fun setAsideForNewSetup(): Boolean = withContext(Dispatchers.IO) {
        keyLock.withLock {
            if (!needsNewSetup()) return@withLock false
            // The key first: a state file left alone keeps needsNewSetup true for a retry.
            try {
                Files.deleteIfExists(keyFile.toPath())
            } catch (e: IOException) {
                throw TransientCryptoException("$KEY_FILE could not be deleted", e)
            }
            stateFile.setAside()
            true
        }
    }

    private fun isDamage(e: Exception) = e is CorruptFileException || isPersistedFormatFailure(e)

    /** Whether a key from elsewhere may be stored: nothing is set up and no key file exists. False when the state cannot be read. */
    fun canAdoptKey(): Boolean = try {
        !readState(strict = true).setUp && !keyFile.exists()
    } catch (e: Exception) {
        false
    }

    /** Replaces the stored key with [key], in any form [RecoveryKey.parse] reads, made or taken at [createdAtMillis]. */
    suspend fun saveKey(key: CharArray, createdAtMillis: Long) {
        val parsed = requireNotNull(RecoveryKey.parse(CharBuffer.wrap(key))) { "not a recovery key" }
        val bytes = ByteArray(parsed.size + Long.SIZE_BYTES).also { b ->
            for (i in parsed.indices) b[i] = parsed[i].code.toByte()
            ByteBuffer.wrap(b, parsed.size, Long.SIZE_BYTES).putLong(createdAtMillis)
        }
        parsed.fill('\u0000')
        try {
            withContext(Dispatchers.IO) { keyLock.withLock { files.write(keyFile, bytes) } }
        } finally {
            bytes.fill(0)
        }
    }

    companion object {
        const val KEY_FILE = "auto_backup_key"
        const val STATE_FILE = "auto_backup"
        private const val KEY_BYTES = 4L * 1024
    }
}
