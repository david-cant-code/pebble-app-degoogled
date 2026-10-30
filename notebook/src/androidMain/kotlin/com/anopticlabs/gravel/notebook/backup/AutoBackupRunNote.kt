package com.anopticlabs.gravel.notebook.backup

import co.touchlab.kermit.Logger
import java.io.File

/** How far an automatic run's file got: about to be made as [Creating.name], made and being written, or checked and about to take its final name. */
sealed interface AutoRunStep {
    data class Creating(val name: String) : AutoRunStep

    data class Writing(val handle: String) : AutoRunStep

    data class Renaming(val handle: String, val name: String) : AutoRunStep
}

/** The step of the automatic run under way, in one small file written whole through a sibling and a rename. No call throws. */
class AutoBackupRunNote(private val file: File) {
    private val lock = Any()

    fun read(): AutoRunStep? = synchronized(lock) {
        try {
            if (!file.isFile) return null
            val lines = file.readLines()
            when (lines.firstOrNull()) {
                CREATING -> AutoRunStep.Creating(lines[1])
                WRITING -> AutoRunStep.Writing(lines[1])
                RENAMING -> AutoRunStep.Renaming(lines[1], lines[2])
                else -> null
            }
        } catch (e: Exception) {
            logger.w { "automatic run note unreadable: ${e::class.simpleName}" }
            null
        }
    }

    /** False when the note could not be written. */
    fun write(step: AutoRunStep): Boolean = synchronized(lock) {
        val lines = when (step) {
            is AutoRunStep.Creating -> listOf(CREATING, step.name)
            is AutoRunStep.Writing -> listOf(WRITING, step.handle)
            is AutoRunStep.Renaming -> listOf(RENAMING, step.handle, step.name)
        }
        try {
            val next = File(file.parentFile, file.name + ".new")
            next.writeText(lines.joinToString("\n") { it.replace('\n', ' ') })
            next.renameTo(file)
        } catch (e: Exception) {
            logger.w { "automatic run note not written: ${e::class.simpleName}" }
            false
        }
    }

    fun clear() {
        synchronized(lock) { file.delete() }
    }

    private companion object {
        const val CREATING = "creating"
        const val WRITING = "writing"
        const val RENAMING = "renaming"
        val logger = Logger.withTag("AutoBackupRunNote")
    }
}
