package com.anopticlabs.gravel.notebook.storage

import android.content.Context
import com.anopticlabs.gravel.notebook.backup.BackupStaging
import java.io.File

/** Where the Notebook keeps its files. */
object NotebookFiles {
    /** Directory under filesDir; the app's backup rules exclude it by this name. */
    const val DIR_NAME = "notebook"

    fun root(context: Context): File = File(context.filesDir, DIR_NAME)

    /** Top-level entries the key guard and Start over leave out: write staging, set-aside files, unsealed recordings, backup staging. */
    val UNSEALED_DIRS = setOf(EncryptedFiles.STAGING_DIR, EncryptedFiles.QUARANTINE_DIR, QueueStore.PENDING_DIR, BackupStaging.DIR)

    /** The codec for files under [root], which makes a key only while [holdsSealedFiles] is false. */
    fun crypto(keystore: NotebookKeystore, root: File): NotebookCrypto = NotebookCrypto(keystore) { !holdsSealedFiles(root) }

    /** Whether any file under [root] outside [UNSEALED_DIRS] remains, which a new key could not open; a directory that cannot be listed counts as one. */
    fun holdsSealedFiles(root: File): Boolean {
        val entries = root.listFiles() ?: return root.exists()
        return entries.any { entry ->
            if (entry.name in UNSEALED_DIRS) return@any false
            var unlisted = false
            entry.walk().onFail { _, _ -> unlisted = true }.any(File::isFile) || unlisted
        }
    }
}
