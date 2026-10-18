package com.anopticlabs.gravel.notebook.reminders

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReminderNoticesTest {

    @Test
    fun theCompletionNudgePostsOnlyWhileBackgroundedWithClaimsBehindIt() {
        assertTrue(shouldPostVoiceCompletionNotification(appBackgrounded = true, pendingCount = 1))
        assertFalse(shouldPostVoiceCompletionNotification(appBackgrounded = true, pendingCount = 0))
        assertFalse(shouldPostVoiceCompletionNotification(appBackgrounded = false, pendingCount = 3))
    }

    @Test
    fun countsBecomeTextWithSingularForOneAndBelow() {
        assertEquals("1 completed task to review", voiceCompletionNotificationText(1))
        assertEquals("1 completed task to review", voiceCompletionNotificationText(0))
        assertEquals("4 completed tasks to review", voiceCompletionNotificationText(4))
    }

    @Test
    fun theMissedWhileOffNoticeGivesOneLinePerReminderOldestFirstWithACountForRepeats() {
        fun missed(id: Int, fire: Long, message: String) = ReminderEvent(id, fire, message, firedAt = 5_000L)
        assertEquals(
            MissedWhileOffNotice("Missed reminder", listOf("call the vet")),
            missedWhileOffNotice(listOf(missed(2, 100L, "call the vet"))),
        )
        assertEquals(
            MissedWhileOffNotice("Missed reminders", listOf("call the vet", "take meds, 3 times")),
            missedWhileOffNotice(listOf(missed(1, 300L, "take meds"), missed(2, 100L, "call the vet"), missed(1, 200L, "take meds"), missed(1, 400L, "take meds"))),
        )
    }
}
