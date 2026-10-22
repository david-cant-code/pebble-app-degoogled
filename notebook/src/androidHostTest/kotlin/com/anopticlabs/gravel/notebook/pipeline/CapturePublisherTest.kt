package com.anopticlabs.gravel.notebook.pipeline

import com.anopticlabs.gravel.notebook.alarms.FakeNotifier
import com.anopticlabs.gravel.notebook.alarms.FakeScheduler
import com.anopticlabs.gravel.notebook.alarms.FakeSettings
import com.anopticlabs.gravel.notebook.alarms.ReminderAlarmScheduler
import com.anopticlabs.gravel.notebook.alarms.ReminderAlarms
import com.anopticlabs.gravel.notebook.notes.BuiltNote
import com.anopticlabs.gravel.notebook.reminders.MAX_REMINDER_MESSAGE_CHARS
import com.anopticlabs.gravel.notebook.reminders.ReminderEventKey
import com.anopticlabs.gravel.notebook.reminders.ReminderItem
import com.anopticlabs.gravel.notebook.reminders.ReminderProposal
import com.anopticlabs.gravel.notebook.reminders.ReminderRefreshSignal
import com.anopticlabs.gravel.notebook.reminders.ReminderTimeStatus
import com.anopticlabs.gravel.notebook.storage.FakeCryptographer
import com.anopticlabs.gravel.notebook.storage.NoteStore
import com.anopticlabs.gravel.notebook.storage.ReminderEventStore
import com.anopticlabs.gravel.notebook.storage.ReminderStore
import com.anopticlabs.gravel.notebook.storage.TransientCryptoException
import com.anopticlabs.gravel.notebook.storage.VoiceCompletionStore
import com.anopticlabs.gravel.notebook.storage.softwareCrypto
import java.nio.file.Files
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapturePublisherTest {

    private val root = Files.createTempDirectory("notebook").toFile()
    private val crypto = FakeCryptographer(softwareCrypto())
    private val notes = NoteStore(root, crypto)
    private val reminders = ReminderStore(root, crypto)
    private val events = ReminderEventStore(root, crypto)
    private val completions = VoiceCompletionStore(root, crypto)
    private val notifier = FakeNotifier()
    private val now = 1_790_089_200_000L
    private val minute = 60_000L
    private var backgrounded = true
    private var ids = 0

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun publisher(
        scheduler: ReminderAlarmScheduler = FakeScheduler(),
        quickRemindMinutes: Int = 30,
        refresh: ReminderRefreshSignal = ReminderRefreshSignal(),
    ) = CapturePublisher(
        notes = notes,
        reminders = reminders,
        completions = completions,
        alarms = ReminderAlarms(reminders, events, scheduler, notifier, FakeSettings(), ReminderRefreshSignal(), { now }, { TimeZone.UTC }),
        notifier = notifier,
        refresh = refresh,
        quickRemindMinutes = { quickRemindMinutes },
        appBackgrounded = { backgrounded },
        now = { now },
        newId = { "id-${++ids}" },
    )

    private fun note(folder: String = "shopping", title: String = "Buy milk", body: String = "get milk") = BuiltCapture.Note(
        BuiltNote(
            title = title,
            tags = listOf("shopping"),
            folder = folder,
            filename = "buy-milk_09.22.26_1700.md",
            markdown = "---\ntitle: $title\n---\n$body\n",
            transcript = body,
            classificationFailed = false,
            date = "2026-09-22",
        ),
    )

    private fun reminder(status: ReminderTimeStatus, at: Long? = null, message: String = "Check the oven") =
        BuiltCapture.Reminder(ReminderProposal(message, at, status))

    @Test
    fun aNoteIsWrittenInItsRoutedFolderWithItsTitleAndDate() = runBlocking<Unit> {
        notes.ensureFolder("shopping")
        val published = assertIs<PublishedCapture.NoteSaved>(publisher().publish(note(), now, listOf("unclassified", "shopping")))
        assertEquals("---\ntitle: Buy milk\n---\nget milk\n", notes.readNote(published.noteId, strict = true))
        val meta = notes.readMetadata(strict = true).getValue(published.noteId)
        assertEquals("shopping", meta.folder)
        assertEquals("buy-milk_09.22.26_1700.md", meta.name)
        assertEquals("Buy milk", meta.title)
        assertEquals("2026-09-22", meta.date)
        assertTrue(reminders.readAll(strict = true).isEmpty())
        assertTrue(completions.readAll(strict = true).isEmpty())
    }

    /** The write gate: only a folder the note was routed among, never an unindexed one. */
    @Test
    fun aNoteRoutedOutsideTheOfferedFoldersLandsInUnclassified() = runBlocking<Unit> {
        val offered = listOf("unclassified", "shopping")
        for (routed in listOf("work", "archive", "../escape")) {
            val published = assertIs<PublishedCapture.NoteSaved>(publisher().publish(note(folder = routed, body = routed), now, offered))
            val folder = notes.readMetadata(strict = true).getValue(published.noteId).folder
            assertEquals("unclassified", folder, "$routed wrote $folder")
        }
    }

    @Test
    fun aNoteRoutedToAFolderDeletedSinceTheRoutingLandsInUnclassifiedAndDoesNotBringItBack() = runBlocking<Unit> {
        val published = assertIs<PublishedCapture.NoteSaved>(publisher().publish(note(), now, listOf("unclassified", "shopping")))
        assertEquals("unclassified", notes.readMetadata(strict = true).getValue(published.noteId).folder)
        assertTrue("shopping" !in notes.readFolders(strict = true))
    }

    /** Two captures that build the same file name in one minute each keep their note. */
    @Test
    fun twoDifferentNotesUnderOneNameAreBothKept() = runBlocking<Unit> {
        val offered = listOf("shopping")
        val first = assertIs<PublishedCapture.NoteSaved>(publisher().publish(note(body = "first"), now, offered))
        val second = assertIs<PublishedCapture.NoteSaved>(publisher().publish(note(body = "second"), now, offered))
        assertTrue(first.noteId != second.noteId)
        assertTrue(notes.readNote(first.noteId, strict = true)!!.contains("first"))
        assertTrue(notes.readNote(second.noteId, strict = true)!!.contains("second"))
    }

    @Test
    fun aUsableTimeArmsExactlyOneReminder() = runBlocking<Unit> {
        val scheduler = FakeScheduler()
        val at = now + 10 * minute
        val published = assertIs<PublishedCapture.ReminderSet>(publisher(scheduler).publish(reminder(ReminderTimeStatus.USABLE, at), now, emptyList()))
        val stored = reminders.readAll(strict = true).single()
        assertEquals(ReminderItem(stored.id, at, "Check the oven"), stored)
        assertEquals(stored, published.reminder)
        assertEquals(stored, scheduler.fires.getValue(stored.id))
        assertTrue(published.scheduled)
        assertFalse(published.defaulted)
        assertEquals(listOf(stored), notifier.setNotices.values.toList())
    }

    @Test
    fun noTimeArmsAtTheQuickRemindDefaultMarkedDefaulted() = runBlocking<Unit> {
        val published = assertIs<PublishedCapture.ReminderSet>(
            publisher(quickRemindMinutes = 45).publish(reminder(ReminderTimeStatus.NO_TIME), now, emptyList()),
        )
        assertEquals(now + 45 * minute, published.reminder.remindAtMillis)
        assertTrue(published.defaulted)
        assertFalse(published.reminder.timeToCheck)
        assertEquals(listOf(published.reminder), notifier.setNotices.values.toList())
    }

    @Test
    fun aTimeToCheckArmsAtTheDefaultMarkedAndAsks() = runBlocking<Unit> {
        val scheduler = FakeScheduler()
        val published = assertIs<PublishedCapture.ReminderSet>(
            publisher(scheduler, quickRemindMinutes = 45).publish(reminder(ReminderTimeStatus.TO_CHECK), now, emptyList()),
        )
        val stored = reminders.readAll(strict = true).single()
        assertEquals(ReminderItem(stored.id, now + 45 * minute, "Check the oven", timeToCheck = true), stored)
        assertEquals(stored, scheduler.fires.getValue(stored.id))
        assertTrue(published.defaulted)
        assertEquals(listOf(stored), notifier.setNotices.values.toList())
    }

    /** A time that was future when spoken but is past by the time the capture is processed arms at the default, to check. */
    @Test
    fun aUsableTimeThatHasPassedArmsAtTheDefaultToCheck() = runBlocking<Unit> {
        val published = assertIs<PublishedCapture.ReminderSet>(
            publisher(quickRemindMinutes = 45).publish(reminder(ReminderTimeStatus.USABLE, now - minute), now, emptyList()),
        )
        assertEquals(now + 45 * minute, published.reminder.remindAtMillis)
        assertTrue(published.reminder.timeToCheck)
        assertEquals(listOf(published.reminder), notifier.setNotices.values.toList())
    }

    @Test
    fun aReminderOrAParkedClaimTicksTheRefreshSignal() = runBlocking<Unit> {
        val refresh = ReminderRefreshSignal()
        val publisher = publisher(refresh = refresh)
        for (built in listOf(reminder(ReminderTimeStatus.NO_TIME), BuiltCapture.Completion("done with the oven"))) {
            val tick = launch(start = CoroutineStart.UNDISPATCHED) { refresh.events.first() }
            publisher.publish(built, now, emptyList())
            withTimeout(5_000L) { tick.join() }
        }
    }

    @Test
    fun aReminderTheStoreCannotKeepFailsThePublishAndArmsNothing() = runBlocking<Unit> {
        val scheduler = FakeScheduler()
        crypto.failSealWhenAadContains = ReminderStore.FILE_NAME
        assertFailsWith<TransientCryptoException> { publisher(scheduler).publish(reminder(ReminderTimeStatus.NO_TIME), now, emptyList()) }
        assertTrue(scheduler.fires.isEmpty())
    }

    /** The stored reminder is the publish; a retry would duplicate it. */
    @Test
    fun anAlarmThatCannotBeScheduledKeepsTheReminderPostsNoNoticeAndDoesNotFail() = runBlocking<Unit> {
        val failing = object : ReminderAlarmScheduler {
            override fun scheduleFire(item: ReminderItem) = throw IllegalStateException("alarm service gone")
            override fun cancelFire(id: Int) = Unit
            override fun scheduleSnooze(key: ReminderEventKey, message: String, fireAtMillis: Long) = Unit
            override fun cancelSnooze(key: ReminderEventKey) = Unit
            override fun scheduleLapseSweep(atMillis: Long) = Unit
            override fun cancelLapseSweep() = Unit
        }
        val refresh = ReminderRefreshSignal()
        for ((i, status) in listOf(ReminderTimeStatus.NO_TIME, ReminderTimeStatus.TO_CHECK).withIndex()) {
            val tick = launch(start = CoroutineStart.UNDISPATCHED) { refresh.events.first() }
            val published = assertIs<PublishedCapture.ReminderSet>(publisher(failing, refresh = refresh).publish(reminder(status), now, emptyList()))
            withTimeout(5_000L) { tick.join() }
            assertFalse(published.scheduled)
            assertEquals(status == ReminderTimeStatus.TO_CHECK, published.reminder.timeToCheck)
            assertEquals(i + 1, reminders.readAll(strict = true).size)
        }
        assertTrue(notifier.setNotices.isEmpty())
    }

    @Test
    fun aMessageCutNeverSplitsASurrogatePair() = runBlocking<Unit> {
        val long = "x".repeat(MAX_REMINDER_MESSAGE_CHARS - 1) + "\uD83D\uDE00" + "tail"
        val published = assertIs<PublishedCapture.ReminderSet>(publisher().publish(reminder(ReminderTimeStatus.NO_TIME, message = long), now, emptyList()))
        assertEquals("x".repeat(MAX_REMINDER_MESSAGE_CHARS - 1), published.reminder.message)
    }

    @Test
    fun aStoreFileThatFailsToOpenFailsTheStoreCheck() = runBlocking<Unit> {
        publisher().publish(reminder(ReminderTimeStatus.NO_TIME), now, emptyList())
        publisher().checkStores()
        val file = java.io.File(root, ReminderStore.FILE_NAME)
        file.writeBytes(file.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })
        assertFailsWith<com.anopticlabs.gravel.notebook.storage.CorruptFileException> { publisher().checkStores() }
    }

    @Test
    fun aMessagePastTheStoreCapIsCut() = runBlocking<Unit> {
        val long = "x".repeat(MAX_REMINDER_MESSAGE_CHARS + 50)
        val published = assertIs<PublishedCapture.ReminderSet>(publisher().publish(reminder(ReminderTimeStatus.NO_TIME, message = long), now, emptyList()))
        assertEquals(MAX_REMINDER_MESSAGE_CHARS, published.reminder.message.length)
    }

    /** A completion claim writes the claim with the recording's time and nothing else. */
    @Test
    fun aCompletionIsParkedWithTheTimeItWasSaidAndWritesNothingElse() = runBlocking<Unit> {
        val spokenAt = now - 3 * 3_600_000L
        val published = assertIs<PublishedCapture.Parked>(publisher().publish(BuiltCapture.Completion("fed the cat"), spokenAt, listOf("unclassified")))
        val parked = completions.readAll(strict = true).single()
        assertEquals(published.completionId, parked.completionId)
        assertEquals("fed the cat", parked.transcript)
        assertEquals(spokenAt, parked.capturedAt)
        assertTrue(notes.readMetadata(strict = true).isEmpty())
        assertTrue(reminders.readAll(strict = true).isEmpty())
    }

    @Test
    fun theCompletionNoticeCountsEveryParkedClaim() = runBlocking<Unit> {
        publisher().publish(BuiltCapture.Completion("one"), now, emptyList())
        publisher().publish(BuiltCapture.Completion("two"), now, emptyList())
        assertEquals(2, notifier.completionsCount)
    }

    @Test
    fun aReviewingReadClearsTheNoticeWithClaimsLeftAndAPlainOneDoesNot() = runBlocking<Unit> {
        publisher().publish(BuiltCapture.Completion("one"), now, emptyList())
        assertEquals(1, publisher().readParked().size)
        assertEquals(1, notifier.completionsCount)
        assertEquals(1, publisher().readParked(reviewing = true).size)
        assertNull(notifier.completionsCount)
    }

    @Test
    fun theCompletionNoticeWaitsWhileTheAppIsOnScreen() = runBlocking<Unit> {
        backgrounded = false
        publisher().publish(BuiltCapture.Completion("one"), now, emptyList())
        assertNull(notifier.completionsCount)
    }

    @Test
    fun aClaimTheStoreCannotKeepFailsThePublish() = runBlocking<Unit> {
        crypto.failSealWhenAadContains = VoiceCompletionStore.FILE_NAME
        assertFailsWith<TransientCryptoException> { publisher().publish(BuiltCapture.Completion("one"), now, emptyList()) }
        assertNull(notifier.completionsCount)
    }

    @Test
    fun aNoticeThatFailsAfterTheWriteDoesNotFailThePublish() = runBlocking<Unit> {
        val throwing = object : com.anopticlabs.gravel.notebook.alarms.ReminderNotifier by notifier {
            override fun postCompletionsToReview(count: Int) = throw SecurityException("notifications refused")
            override fun postSetNotice(item: ReminderItem) = throw SecurityException("notifications refused")
        }
        val publisher = CapturePublisher(
            notes, reminders, completions,
            ReminderAlarms(reminders, events, FakeScheduler(), notifier, FakeSettings(), ReminderRefreshSignal(), { now }, { TimeZone.UTC }),
            throwing, ReminderRefreshSignal(), { 30 }, { true }, { now }, { "id-${++ids}" },
        )
        assertIs<PublishedCapture.Parked>(publisher.publish(BuiltCapture.Completion("one"), now, emptyList()))
        assertIs<PublishedCapture.ReminderSet>(publisher.publish(reminder(ReminderTimeStatus.TO_CHECK), now, emptyList()))
        assertEquals(1, completions.readAll(strict = true).size)
        assertTrue(reminders.readAll(strict = true).single().timeToCheck)
    }
}
