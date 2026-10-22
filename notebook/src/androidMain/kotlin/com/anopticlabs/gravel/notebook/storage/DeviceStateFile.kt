package com.anopticlabs.gravel.notebook.storage

import co.touchlab.kermit.Logger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * A short value kept in [dir], the no-backup directory, so it stays with this phone: Android leaves that directory out
 * of every full backup, device-to-device transfer included (android16-release, `BackupAgent.onFullBackup`,
 * `getExtraExcludeDirsIfAny`).
 */
class DeviceStateFile(private val dir: File, private val name: String, private val maxChars: Int = 64) {
    private val file get() = File(dir, name)

    /** Null when nothing was saved, or the file is empty, too long or unreadable. */
    fun read(): String? = try {
        file.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() && it.length <= maxChars }
    } catch (e: IOException) {
        logger.w { "$name unreadable (${e::class.simpleName})" }
        null
    }

    /** False when [value] could not be saved; what was saved before stays. */
    fun write(value: String): Boolean = try {
        dir.mkdirs()
        val staging = File(dir, "$name.tmp")
        FileOutputStream(staging).use { out ->
            out.write(value.toByteArray())
            out.fd.sync()
        }
        if (!staging.renameTo(file)) throw IOException("rename failed")
        true
    } catch (e: IOException) {
        logger.w { "$name not saved (${e::class.simpleName})" }
        false
    }

    /** False when what was saved could not be removed. */
    fun clear(): Boolean = !file.exists() || file.delete()

    private companion object {
        val logger = Logger.withTag("DeviceStateFile")
    }
}
