package com.anopticlabs.gravel.notebook.backup

import co.touchlab.kermit.Logger
import java.io.File

/** How far a save got: the document picked, its first byte about to be written, or its read-back passed. */
enum class SaveStage { PICKED, WRITING, VERIFIED }

/** A save or restore that had not ended when its process did. */
sealed interface UnfinishedRun<out H> {
    data class Save<H>(val handle: H, val fileName: String?, val stage: SaveStage) : UnfinishedRun<H>

    data object Restore : UnfinishedRun<Nothing>
}

/** Where [BackupFlow] notes the run under way, for the next process to find. No call throws. */
interface BackupRunRecord<H> {
    fun read(): UnfinishedRun<H>?

    /** False when the note could not be written. */
    fun write(run: UnfinishedRun<H>): Boolean

    /** Removes the note only while it is still [expected], so a later run's note survives. */
    fun clear(expected: UnfinishedRun<H>)
}

/** [BackupRunRecord] in one small file, written whole through a sibling and a rename; [encode] and [decode] spell a handle. */
class FileRunRecord<H>(
    private val file: File,
    private val encode: (H) -> String,
    private val decode: (String) -> H,
) : BackupRunRecord<H> {
    private val lock = Any()

    override fun read(): UnfinishedRun<H>? = synchronized(lock) {
        try {
            if (file.isFile) parse(file.readLines()) else null
        } catch (e: Exception) {
            logger.w { "unfinished run note unreadable: ${e::class.simpleName}" }
            null
        }
    }

    override fun write(run: UnfinishedRun<H>): Boolean = synchronized(lock) {
        try {
            val next = File(file.parentFile, file.name + ".new")
            next.writeBytes(bytesOf(run))
            next.renameTo(file)
        } catch (e: Exception) {
            logger.w { "unfinished run note not written: ${e::class.simpleName}" }
            false
        }
    }

    override fun clear(expected: UnfinishedRun<H>) = synchronized(lock) {
        // Also compared as written, since a name the file cannot spell does not read back as it was.
        try {
            if (file.isFile && (file.readBytes().contentEquals(bytesOf(expected)) || read() == expected)) file.delete()
        } catch (e: Exception) {
            logger.w { "unfinished run note not cleared: ${e::class.simpleName}" }
        }
        Unit
    }

    private fun bytesOf(run: UnfinishedRun<H>): ByteArray = format(run).encodeToByteArray()

    private fun format(run: UnfinishedRun<H>): String = when (run) {
        UnfinishedRun.Restore -> RESTORE
        is UnfinishedRun.Save -> listOf(SAVE, run.stage.name, encode(run.handle), run.fileName.orEmpty().replace('\n', ' ')).joinToString("\n")
    }

    private fun parse(lines: List<String>): UnfinishedRun<H>? = when (lines.firstOrNull()) {
        RESTORE -> UnfinishedRun.Restore
        SAVE -> UnfinishedRun.Save(decode(lines[2]), lines.getOrNull(3)?.takeIf { it.isNotEmpty() }, SaveStage.valueOf(lines[1]))
        else -> null
    }

    private companion object {
        const val SAVE = "save"
        const val RESTORE = "restore"
        val logger = Logger.withTag("FileRunRecord")
    }
}
