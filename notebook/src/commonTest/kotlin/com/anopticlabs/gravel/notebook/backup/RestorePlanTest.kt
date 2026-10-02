package com.anopticlabs.gravel.notebook.backup

import com.anopticlabs.gravel.notebook.notes.NoteSanitizers
import com.anopticlabs.gravel.notebook.storage.MAX_STORED_INSTANT_MILLIS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RestorePlanTest {

    private val nameOk = { name: String -> name.endsWith(".m4a") }
    private val id = "0123456789abcdef0123456789abcdef"

    private fun note(id: String = this.id, folder: String = "work", name: String = "a.md", content: String = "body", modifiedAtMillis: Long = 1_700_000_000_000L, title: String? = "T", date: String? = "2026-01-01") =
        BackupNote(id, folder, name, title, date, modifiedAtMillis, content)

    private fun capture(id: String = "c1", name: String = "r.m4a", queuedAtMillis: Long = 1_700_000_000_000L) = BackupCapture(id, name, queuedAtMillis)

    @Test
    fun aNoteIsPlannedWithItsFieldsBoundedAndRefusedOutsideThem() {
        val planned = assertNotNull(RestorePlan.planNote(note(title = " \u0007Title ${"x".repeat(300)}", date = "2026-05-31 ${"9".repeat(40)}")))
        assertEquals(id, planned.id)
        assertEquals("work", planned.folder)
        assertEquals("a.md", planned.name)
        assertEquals(1_700_000_000_000L, planned.modifiedAtMillis)
        assertEquals(RestorePlan.MAX_TITLE_CHARS, planned.title!!.length)
        assertTrue(planned.title!!.startsWith("Title x"))
        assertEquals(RestorePlan.MAX_DATE_CHARS, planned.date!!.length)
        assertNull(assertNotNull(RestorePlan.planNote(note(title = " \u0001 ", date = ""))).title)
        for (bad in listOf(
            note(id = "0123456789ABCDEF0123456789ABCDEF"), note(id = id.dropLast(1)), note(id = "../$id"),
            note(folder = "Work"), note(folder = "../x"), note(folder = ""),
            note(name = "../x.md"), note(name = ".hidden.md"), note(name = "sp ace.md"), note(name = "a/b.md"),
            note(content = "x".repeat(RestorePlan.MAX_NOTE_CHARS + 1)),
            note(modifiedAtMillis = 0L), note(modifiedAtMillis = MAX_STORED_INSTANT_MILLIS + 1),
        )) {
            assertNull(RestorePlan.planNote(bad), bad.toString().take(120))
        }
        assertNotNull(RestorePlan.planNote(note(content = "x".repeat(RestorePlan.MAX_NOTE_CHARS))))
        assertNotNull(RestorePlan.planNote(note(modifiedAtMillis = MAX_STORED_INSTANT_MILLIS)))
    }

    @Test
    fun aCaptureIsRestorableWithARecordingUnderTheCapAndAnEntryTheQueueTakes() {
        fun ok(entry: BackupCapture, bytes: Int = 4) = RestorePlan.isRestorableCapture(entry, bytes, 8, nameOk)
        assertTrue(ok(capture()))
        assertTrue(ok(capture(), bytes = 8))
        assertFalse(ok(capture(), bytes = 0))
        assertFalse(ok(capture(), bytes = 9))
        assertFalse(ok(capture(id = "a/b")))
        assertFalse(ok(capture(name = "x.txt")))
        assertFalse(ok(capture(queuedAtMillis = 0L)))
        assertFalse(ok(capture(queuedAtMillis = MAX_STORED_INSTANT_MILLIS + 1)))
    }

    @Test
    fun folderTagsAreNormalizedCappedAndStrippedOfReservedFolders() {
        val raw = mapOf(
            " Work " to listOf(" Budget\u0007 ", "budget", "MEETING", "", "x".repeat(60)),
            "archive" to listOf("a"),
            "unclassified" to listOf("a"),
            "Bad Name!" to listOf("a"),
            "empty" to listOf(" ", "\u0001"),
            "many" to (1..20).map { "t$it" },
            "work" to listOf("dup"),
        )
        val tags = RestorePlan.sanitizeFolderTags(raw)

        assertEquals(listOf("work", "many"), tags.keys.toList())
        assertEquals(listOf("budget", "meeting", "x".repeat(NoteSanitizers.MAX_TAG_CHARS)), tags["work"])
        assertEquals(NoteSanitizers.MAX_TAGS_PER_FOLDER, tags.getValue("many").size)
        assertEquals(emptyMap(), RestorePlan.sanitizeFolderTags(null))
        assertEquals(mapOf("transcripts" to listOf("a")), RestorePlan.sanitizeFolderTags(mapOf("transcripts" to listOf("a"))))
        val wide = (1..150).associate { "f$it" to listOf("t") }
        assertEquals(NoteSanitizers.MAX_FOLDER_TAGS_ENTRIES, RestorePlan.sanitizeFolderTags(wide).size)
    }

    @Test
    fun theContentsCountEveryItemAndWhatARestoreWouldSkip() {
        val contents = ArchiveContents(8, nameOk)
        contents.meta(BackupMeta(1_700_000_000_000L, STORE))
        contents.folders(listOf("work", "Bad Folder", "home", "work"))
        contents.folderTags(mapOf("work" to listOf("a"), "archive" to listOf("b")))
        for (n in listOf(note(), note(name = "b.md"), note(name = "../x.md"), note(folder = "home"), note(folder = "home", modifiedAtMillis = 0L))) contents.note(n)
        contents.capture(capture("c1"), 4)
        contents.capture(capture("c2"), 0)
        contents.capture(capture("c3"), 9)
        val badOrigin = BackupOrigin("not a store", 1)
        contents.reminders(listOf(BackupReminder(1, 5L, "m"), BackupReminder(2, 5L, "m", BackupRecurrence("never")), BackupReminder(3, 5L, "m", origin = badOrigin)))
        contents.events(
            listOf(BackupReminderEvent(1, 5L, "m", 6L), BackupReminderEvent(1, 5L, "m", 6L, snoozeChainCount = -2), BackupReminderEvent(3, 5L, "m", 6L, reminderOrigin = badOrigin)),
        )

        assertEquals(BackupCounts(notes = 5, folders = 4, folderTags = 2, captures = 3, reminders = 3, events = 3), contents.counts)
        val summary = contents.summary(999L)
        assertEquals(1, summary.formatVersion)
        assertEquals("2023-11-14T22:13:20Z", summary.createdAt)
        assertEquals(999L, summary.fileBytes)
        assertEquals(5, summary.notes)
        assertEquals(mapOf("home" to 1, "work" to 2), summary.notesByFolder)
        assertEquals(2, summary.skippedNotes)
        assertEquals(4, summary.folders)
        assertEquals(1, summary.skippedFolders)
        assertEquals(1, summary.folderTagRules)
        assertEquals(3, summary.captures)
        assertEquals(2, summary.skippedCaptures)
        assertEquals(3, summary.reminders)
        assertEquals(2, summary.skippedReminders)
        assertEquals(3, summary.events)
        assertEquals(2, summary.skippedEvents)
        assertFalse(summary.healthy)

        val clean = ArchiveContents(8, nameOk).apply {
            meta(BackupMeta(1L, STORE))
            note(note())
            capture(capture(), 4)
        }
        assertTrue(clean.summary(1L).healthy)
    }

    @Test
    fun aStructureNoWriterProducesIsRefused() {
        fun damaged(block: ArchiveContents.() -> Unit) =
            assertEquals(BackupProblem.DAMAGED, assertFailsWith<InvalidBackupException> { ArchiveContents(8, nameOk).block() }.problem)
        damaged { note(note()) }
        damaged { folders(emptyList()) }
        damaged { summary(1L) }
        damaged { meta(BackupMeta(1L, "")) }
        damaged { meta(BackupMeta(1L, STORE.uppercase())) }
        damaged { meta(BackupMeta(1L, STORE)); meta(BackupMeta(1L, STORE)) }
        damaged { meta(BackupMeta(1L, STORE)); folders(emptyList()); folders(emptyList()) }
        damaged { meta(BackupMeta(1L, STORE)); folderTags(emptyMap()); folderTags(emptyMap()) }
        damaged { meta(BackupMeta(1L, STORE)); reminders(emptyList()); reminders(emptyList()) }
        damaged { meta(BackupMeta(1L, STORE)); events(emptyList()); events(emptyList()) }
        damaged { meta(BackupMeta(1L, STORE)); repeat(RestorePlan.MAX_ARCHIVE_NOTES + 1) { note(note()) } }
        damaged { meta(BackupMeta(1L, STORE)); repeat(RestorePlan.MAX_ARCHIVE_CAPTURES + 1) { capture(capture(), 4) } }
        ArchiveContents(8, nameOk).apply {
            meta(BackupMeta(1L, STORE))
            repeat(RestorePlan.MAX_ARCHIVE_NOTES) { note(note()) }
            repeat(RestorePlan.MAX_ARCHIVE_CAPTURES) { capture(capture(), 4) }
        }
    }
}

private const val STORE = "0123456789abcdef0123456789abcdef"
