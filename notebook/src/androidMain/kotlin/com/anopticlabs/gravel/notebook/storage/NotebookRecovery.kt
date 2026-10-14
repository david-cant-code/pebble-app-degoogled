package com.anopticlabs.gravel.notebook.storage

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.ui.StorageProblem
import com.anopticlabs.gravel.notebook.ui.storageProblemOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** A part of the Notebook kept in a file of its own, which can be damaged while the rest reads. */
enum class NotebookPart {
    NOTE_LIST, FOLDERS, FOLDER_TAGS, RECORDINGS, REMINDERS, REMINDER_HISTORY, COMPLETIONS;

    /** Rebuilt from the notes or recordings that remain; the other parts start again. */
    val rebuilt: Boolean get() = this == NOTE_LIST || this == RECORDINGS

    /** Held by a backup, so a restore can bring it back. */
    val inBackups: Boolean get() = this != COMPLETIONS
}

/** Start over refused because the key fails or could not be looked up and may work again; only a forced start over deletes it. */
class KeyStoreFailureException : RuntimeException("the key fails or could not be looked up, so it was kept")

/** Why the Notebook cannot be used as it is. */
sealed interface StorageTrouble {
    /** No file opened and the key is missing or unusable, or Android reports no key while sealed files remain. */
    data object KeyGone : StorageTrouble

    data class Damaged(val parts: Set<NotebookPart>) : StorageTrouble

    /** A read failed in a way that may pass. */
    data object Unreadable : StorageTrouble
}

/** What goes before a restore merges: the damaged parts, or every file once the key is gone. */
sealed interface RecoveryPlan {
    data class StartFresh(val parts: Set<NotebookPart>) : RecoveryPlan

    data object StartOver : RecoveryPlan
}

/**
 * Finds what is wrong with the Notebook's files and puts it right, setting a damaged part aside
 * under `.quarantine` and deleting the sealed files on a start over. Each step re-reads its part first,
 * so an older plan changes only parts still damaged (and the reminder history with the
 * reminders); the note list step reconciles a healthy index too. [afterReset] runs after a step
 * that changed a store, with true after [startOver].
 */
class NotebookRecovery(
    root: File,
    private val notes: NoteStore,
    private val queue: QueueStore,
    private val reminders: ReminderStore,
    private val events: ReminderEventStore,
    private val completions: VoiceCompletionStore,
    /** Clears the key so the next write makes a new one: when Android says it does not exist, or, forced, when it fails or cannot be looked up. */
    private val resetKey: (force: Boolean) -> KeyReset,
    private val keyAbsent: () -> Boolean,
    private val afterReset: suspend (startedOver: Boolean) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val root = root.absoluteFile.normalize()
    private val mutex = Mutex()

    /** Each part's strict read, and its file when the read always opens it (the note list may come from memory). */
    private class PartRead(val part: NotebookPart, val file: String?, val read: () -> Unit)

    private val reads = listOf(
        PartRead(NotebookPart.NOTE_LIST, null) { notes.readMetadata(strict = true) },
        PartRead(NotebookPart.FOLDERS, NoteStore.FOLDERS_FILE) { notes.readFolders(strict = true) },
        PartRead(NotebookPart.FOLDER_TAGS, NoteStore.FOLDER_TAGS_FILE) { notes.readFolderTags(strict = true) },
        PartRead(NotebookPart.RECORDINGS, QueueStore.QUEUE_META_FILE) { queue.readQueue(strict = true) },
        PartRead(NotebookPart.REMINDERS, ReminderStore.FILE_NAME) { reminders.readFile(strict = true) },
        PartRead(NotebookPart.REMINDER_HISTORY, ReminderEventStore.FILE_NAME) { events.readAll(strict = true) },
        PartRead(NotebookPart.COMPLETIONS, VoiceCompletionStore.FILE_NAME) { completions.readAll(strict = true) },
    )

    /**
     * Reads every part strictly; null when all of them read and no sealed files sit beyond them without a key. The key counts as gone only when no
     * file read from disk opened with it; a key failure beside a file that opened may pass. Blocks
     * on file and Keystore I/O.
     */
    fun diagnose(): StorageTrouble? {
        var keyMissing = false
        var opened = false
        var unreadable = false
        val damaged = LinkedHashSet<NotebookPart>()
        for (p in reads) {
            val existed = p.file?.let { File(root, it).exists() } == true
            try {
                p.read()
                if (existed) opened = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                when (storageProblemOf(e)) {
                    StorageProblem.KEY_MISSING -> keyMissing = true
                    StorageProblem.DAMAGED -> damaged += p.part
                    StorageProblem.UNREADABLE -> unreadable = true
                }
            }
        }
        return when {
            keyMissing && !opened -> StorageTrouble.KeyGone
            !opened && sealedFilesWithoutKey() -> StorageTrouble.KeyGone
            damaged.isNotEmpty() -> StorageTrouble.Damaged(damaged)
            unreadable || keyMissing -> StorageTrouble.Unreadable
            else -> null
        }
    }

    // The key guard refuses a new key while these files remain, so Start over must be offered even
    // when no part read reached them (an interrupted Start over, or files outside the parts).
    private fun sealedFilesWithoutKey(): Boolean = NotebookFiles.holdsSealedFiles(root) && try {
        keyAbsent()
    } catch (e: Exception) {
        false
    }

    private suspend fun isDamaged(read: () -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            read()
            false
        } catch (e: CorruptFileException) {
            true
        } catch (e: PersistedFormatException) {
            true
        }
    }

    /** Rebuilds the note list and the recordings list where they need it. */
    suspend fun repair() = mutex.withLock {
        val reconciled = notes.reconcile()
        val rebuilt = queue.rebuildIfDamaged()
        if (reconciled.rebuilt || reconciled.listed > 0 || rebuilt != null) afterReset(false)
    }

    /**
     * Rebuilds or sets aside each of [parts] still damaged; the reminders take their history with
     * them, since the store that replaces them numbers anew. Returns the parts it changed.
     */
    suspend fun startFresh(parts: Set<NotebookPart>): Set<NotebookPart> = mutex.withLock {
        val done = LinkedHashSet<NotebookPart>()
        try {
            // Folders before their tags, which are seeded from the folder list.
            for (part in NotebookPart.entries.filter { it in parts }) {
                val changed = when (part) {
                    NotebookPart.NOTE_LIST -> notes.reconcile().rebuilt
                    NotebookPart.RECORDINGS -> queue.rebuildIfDamaged() != null
                    NotebookPart.FOLDERS -> notes.startFoldersFreshIfDamaged()
                    NotebookPart.FOLDER_TAGS -> notes.startFolderTagsFreshIfDamaged()
                    // History first: a retry after a stop in between still finds the reminders damaged.
                    NotebookPart.REMINDERS -> isDamaged { reminders.readFile(strict = true) } && run {
                        events.setAside()
                        reminders.startFreshIfDamaged()
                    }
                    NotebookPart.REMINDER_HISTORY -> events.setAsideIfDamaged()
                    NotebookPart.COMPLETIONS -> completions.setAsideIfDamaged()
                }
                if (changed) done += part
            }
        } finally {
            if (done.isNotEmpty()) afterReset(false)
        }
        done
    }

    /**
     * When a fresh diagnosis still finds the key gone: resets it, then moves every file under the
     * root outside the unsealed directories into `.quarantine/start-over-<millis>/`, so the next
     * write makes a new key. Last, once [afterReset] has returned or thrown, it deletes that folder
     * and any an earlier Start over left. Returns false, changing nothing, when the files read or
     * are damaged; throws when they cannot be read this time, the key works again or cannot be
     * deleted, or when it fails or cannot be looked up and [force] is false
     * ([KeyStoreFailureException]). A move that fails leaves the files not yet moved in place, which
     * the next diagnosis still reports as KeyGone; what is left of the folder then, or after a
     * delete that did not finish, is for [removeStartOverLeftovers].
     */
    suspend fun startOver(force: Boolean = false): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            when (diagnose()) {
                StorageTrouble.KeyGone -> Unit
                StorageTrouble.Unreadable -> throw TransientCryptoException("the Notebook could not be read to start over", IOException())
                else -> return@withContext false
            }
            val entries = root.listFiles() ?: throw TransientCryptoException("the Notebook's files could not be listed", IOException())
            when (resetKey(force)) {
                KeyReset.DELETED -> Unit
                KeyReset.KEY_WORKS -> throw TransientCryptoException("the Notebook key works again", IOException())
                KeyReset.KEPT -> throw KeyStoreFailureException()
                KeyReset.FAILED -> throw TransientCryptoException("the key could not be deleted", IOException())
            }
            val aside = File(root, "${EncryptedFiles.QUARANTINE_DIR}/$START_OVER_PREFIX${clock()}")
            try {
                Files.createDirectories(aside.toPath())
                for (entry in entries) {
                    if (entry.name in NotebookFiles.UNSEALED_DIRS) continue
                    Files.move(entry.toPath(), File(aside, entry.name).toPath())
                }
            } catch (e: IOException) {
                throw TransientCryptoException("the Notebook's files could not all be set aside", e)
            }
            logger.i { "started over" }
            notes.forgetCachedIndex()
            try {
                afterReset(true)
            } finally {
                deleteStartOverFolders()
            }
            true
        }
    }

    /**
     * Deletes the folders a Start over moved files into and did not delete. Does nothing while a
     * recovery step holds the lock, so a folder a Start over is still filling is not emptied under
     * it; a later call finds what is left.
     */
    suspend fun removeStartOverLeftovers() {
        if (!mutex.tryLock()) return
        try {
            withContext(Dispatchers.IO) { deleteStartOverFolders() }
        } finally {
            mutex.unlock()
        }
    }

    private fun deleteStartOverFolders() = File(root, EncryptedFiles.QUARANTINE_DIR).listFiles().orEmpty()
        .filter { it.name.startsWith(START_OVER_PREFIX) }
        .forEach(::deleteStartOverFolder)

    // Without FOLLOW_LINKS the walk reads a link's own attributes and does not enter it, so the
    // link is deleted and its target left alone (android16-release libcore,
    // ojluni/src/main/java/java/nio/file/FileTreeWalker.java, `getAttributes` and `visit`). An
    // entry that cannot be deleted is passed over and the walk goes on.
    private fun deleteStartOverFolder(folder: File) {
        var left = 0
        fun delete(path: Path) {
            try {
                Files.delete(path)
            } catch (e: IOException) {
                left++
            }
        }
        try {
            Files.walkFileTree(folder.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (e: IOException) {
            left++
        }
        if (left > 0) logger.w { "$left entries of a start over folder could not be deleted" }
    }

    /** Runs [plan]'s step: [startFresh] on its parts, or [startOver], or, when the files turn out to read, [startFresh] on what is damaged. */
    suspend fun prepare(plan: RecoveryPlan) {
        when (plan) {
            is RecoveryPlan.StartFresh -> startFresh(plan.parts)
            RecoveryPlan.StartOver -> if (!startOver()) (withContext(Dispatchers.IO) { diagnose() } as? StorageTrouble.Damaged)?.let { startFresh(it.parts) }
        }
    }

    private companion object {
        const val START_OVER_PREFIX = "start-over-"
        val logger = Logger.withTag("NotebookRecovery")
    }
}
