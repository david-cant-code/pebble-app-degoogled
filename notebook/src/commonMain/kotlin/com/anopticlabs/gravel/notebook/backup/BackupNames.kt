package com.anopticlabs.gravel.notebook.backup

import com.anopticlabs.gravel.notebook.padTwo
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

const val BACKUP_EXTENSION = "gnbk"

/** The type a backup document is created with (DESIGN_NOTES, Notebook backup). */
const val BACKUP_MIME_TYPE = "application/octet-stream"

/** The file name the save picker starts with, for a backup made at [millis] in [zone]: `gravel-notebook-2026-10-02-1430.gnbk`. */
fun suggestedBackupName(millis: Long, zone: TimeZone): String {
    val t = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone)
    return "gravel-notebook-${t.year}-${t.month.number.padTwo()}-${t.day.padTwo()}-${t.hour.padTwo()}${t.minute.padTwo()}.$BACKUP_EXTENSION"
}

/** An automatic backup's file name, for one made at [millis] in [zone]: `gravel-notebook-auto-2026-10-02-143005.gnbk`. */
fun automaticBackupName(millis: Long, zone: TimeZone): String {
    val t = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone)
    return "gravel-notebook-auto-${t.year}-${t.month.number.padTwo()}-${t.day.padTwo()}-" +
        "${t.hour.padTwo()}${t.minute.padTwo()}${t.second.padTwo()}.$BACKUP_EXTENSION"
}

/** The name an automatic backup is written under until it has been read back and checked. */
const val PARTIAL_SUFFIX = ".part"
