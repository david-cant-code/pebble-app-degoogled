package com.anopticlabs.gravel.notebook.reminders

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReminderExtractorTest {

    private val at = 1_792_278_600_000L

    private fun proposal(raw: String, spoken: SpokenTime, transcript: String = "remind me about something") =
        ReminderExtractor.proposal(raw, transcript, spoken)

    @Test
    fun theTimeComesFromTheRulesAndNotFromTheReply() {
        val reply = """{"remind_in_seconds": 600, "remind_at": "2026-06-14T17:00:00+00:00", "message": "Check the oven"}"""
        assertEquals(ReminderProposal("Check the oven", at, ReminderTimeStatus.USABLE), proposal(reply, SpokenTime.Read(at)))
        assertEquals(ReminderProposal("Check the oven", null, ReminderTimeStatus.TO_CHECK), proposal(reply, SpokenTime.Unread))
        assertEquals(ReminderProposal("Check the oven", null, ReminderTimeStatus.NO_TIME), proposal(reply, SpokenTime.None))
    }

    @Test
    fun timeWordsLeftInTheReplysMessageAreRemoved() {
        val reply = """{"message": "Take out the trash in one hour and five minutes"}"""
        assertEquals("Take out the trash", proposal(reply, SpokenTime.Read(at)).message)
    }

    @Test
    fun reasoningAndFencesAreStripped() {
        val raw = "<think>the user wants the oven</think>\n```json\n{\"message\": \"Oven\"}\n```"
        assertEquals("Oven", proposal(raw, SpokenTime.None).message)
    }

    @Test
    fun anUnreadableReplyOrNoMessageFallsBackToTheTranscriptWithoutItsTimeWords() {
        assertEquals("remind me to buy milk", proposal("uhh maybe i guess", SpokenTime.None, "remind me to buy milk later").message)
        assertEquals("remind me to flip the laundry", proposal("""{"remind_in_seconds": 600}""", SpokenTime.Unread, "remind me in ten minutes to flip the laundry").message)
        assertEquals("buy milk", proposal("""{"message": "   "}""", SpokenTime.None, "buy milk").message)
        assertEquals("Reminder", proposal("not json", SpokenTime.None, "   ").message)
    }

    @Test
    fun fallbackMessageIsSingleLineAndCapped() {
        val long = ("remind me " + "word ".repeat(100)).replace("word word", "word\nword")
        val message = proposal("not json at all", SpokenTime.None, long).message
        assertTrue(message.length <= ReminderExtractor.FALLBACK_MESSAGE_MAX_CHARS)
        assertFalse(message.contains("\n"))
        assertFalse(message.contains("  "))
    }
}
