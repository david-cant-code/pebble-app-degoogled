package com.anopticlabs.gravel.notebook.reminders

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The executable spec of what counts as a reminder cue; the negatives are the anti-false-alarm half. */
class ReminderIntentTest {

    private val reminders = listOf(
        "remind me to call the dentist at 3",
        "please remind me to take the trash out",
        "can you remind me to email Sarah tomorrow",
        "ok so remind me to move the car in an hour",
        "remind myself to water the plants tonight",
        "remember to call the dentist at 3",
        "set a reminder to pay rent on the first",
        "set an alarm for 6am",
        "set me a reminder to pay rent",
        "I want a reminder to take my pills",
        "give me a reminder to call the bank",
        "don't let me forget to call mom",
        "dont let me forget to bring the charger",
        "ping me in 20 minutes",
        "wake me at 6:30",
        "Reminder: take meds at 8",
        "reminder to submit the timesheet",
        "remind me to buy milk",
        "remind me when the timer goes off",
        "remind me which medication to take at noon",
        "remind me where to pick up the kids at 3",
        "don\u2019t let me forget to lock up",
        "don\u02BCt let me forget to lock up",
        "don\u2018t let me forget to lock up",
        "remind\u00A0me to call the bank",
        "REMIND ME to call the bank",
        "remind me to follow up on the meeting notes from today",
        "remind me to check what time the store opens",
        "set a reminder about the dentist appointment tomorrow",
        "give me a reminder about which pill to take at noon",
    )

    private val notes = listOf(
        "I need to buy milk and eggs",
        "the 5pm meeting went really well",
        "take the pasta off in ten minutes",
        "buy groceries this weekend",
        "",
        "   \n  \t ",
        "reminders are stressing me out lately",
        "don't forget the milk is the note here",
        "I remember that trip we took",
        "that reminds me, the wifi was down all morning",
        "this reminds me of our trip last year",
        "remind me what the gate code is",
        "remind me who was at the meeting",
        "remind me how to reset the router",
        "remind me, what is the gate code",
        "remind me, what's the wifi password",
        "remind me which way the printer faces",
        "remind me where i parked the car",
        "give me a reminder about what the gate code is",
        "I need a reminder about who called earlier",
        "set a reminder about which way the printer faces",
        "set a reminder about how to reset the router",
        "they remind me of my sister",
        "got an email that said remind me to renew the domain",
        "the app keeps trying to remind me about updates every day",
        "the doctor will remind me to schedule a follow up",
        "I tried to set a reminder but the app crashed again",
        "forgot to set a reminder yesterday and missed the call",
        "set a reminder app would be a good business idea",
        "set a reminder app i downloaded is actually pretty good",
        "here are the meeting notes from today and a few action items that remind me to follow up",
    )

    @Test
    fun cuesAreReminders() {
        for (s in reminders) assertTrue(ReminderIntent.looksLikeReminder(s), s)
    }

    @Test
    fun notesAndGuardedPhrasesAreNot() {
        for (s in notes) assertFalse(ReminderIntent.looksLikeReminder(s), s)
    }

    @Test
    fun normalizeFoldsApostrophesSpacesAndZeroWidthCharacters() {
        assertTrue(ReminderIntent.normalize("Don\u2019t\u00A0Stop\u200B now  ") == "don't stop now")
    }
}
