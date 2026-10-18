package com.anopticlabs.gravel.notebook.reminders

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TriggerWordTest {

    @Test
    fun aValidWordIsStoredNormalizedAndAnInvalidOneNotAtAll() {
        assertEquals("done", triggerWordToStore("  Done "))
        assertEquals("finished", triggerWordToStore("FINISHED"))
        assertNull(triggerWordToStore("the"))
        assertNull(triggerWordToStore("two words"))
        assertNull(triggerWordToStore(""))
    }

    @Test
    fun acceptsSingleAsciiTokens() {
        for (w in listOf("done", "finished", "complete", "completed", "yep", "check", "boom", "tick", "b12", "done2", "42", "DONE", "  done  ", "Finished", "fertig")) {
            assertTrue(isValidTriggerWord(w), w)
        }
        assertTrue(isValidTriggerWord("a".repeat(MAX_TRIGGER_WORD_CHARS)))
    }

    @Test
    fun rejectsBlankShortMultiWordPunctuatedAndNonAscii() {
        for (w in listOf(
            "", "   ", "\t \n", "x", "a", "i", "1", "all done", "mark complete",
            "done!", "done.", "done,", "don't", "done'", "re-done", "done_", "caf\u00E9", "na\u00EFve", "don\u2019t",
            "a".repeat(MAX_TRIGGER_WORD_CHARS + 1), "x".repeat(100_000),
        )) {
            assertFalse(isValidTriggerWord(w), w)
        }
    }

    @Test
    fun rejectsTheBlocklistInAnyCase() {
        for (w in TRIGGER_WORD_BLOCKLIST) assertFalse(isValidTriggerWord(w), w)
        assertFalse(isValidTriggerWord("NOTE"))
        assertFalse(isValidTriggerWord("Remind"))
    }

    @Test
    fun effectiveEnablementNeedsTheFlagAndAValidWord() {
        assertTrue(effectiveTriggerEnabled(storedEnabled = true, storedWord = "done"))
        assertFalse(effectiveTriggerEnabled(storedEnabled = false, storedWord = "done"))
        for (w in listOf("", "note", "done!", "x")) assertFalse(effectiveTriggerEnabled(storedEnabled = true, storedWord = w), w)
    }

    @Test
    fun parkGate() {
        assertNull(shouldParkTriggerCompletion(false, "done", "done the dishes"))
        assertEquals("the dishes", shouldParkTriggerCompletion(true, "done", "done the dishes"))
        assertEquals("the dishes", shouldParkTriggerCompletion(true, "done", "Done, the dishes"))
        assertEquals("", shouldParkTriggerCompletion(true, "done", "done"))
        assertNull(shouldParkTriggerCompletion(true, "done", "buy milk tomorrow"))
        assertNull(shouldParkTriggerCompletion(true, "done", "i am done now"))
        assertNull(shouldParkTriggerCompletion(true, "done", "donether dishes"))
        assertNull(shouldParkTriggerCompletion(true, "", "anything at all"))
        assertNull(shouldParkTriggerCompletion(true, "   ", "anything at all"))
        assertNull(shouldParkTriggerCompletion(true, "done", null))
        assertNull(shouldParkTriggerCompletion(true, "done", ""))
    }
}
