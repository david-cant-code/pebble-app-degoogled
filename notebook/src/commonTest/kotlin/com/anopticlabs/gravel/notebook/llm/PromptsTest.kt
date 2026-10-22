package com.anopticlabs.gravel.notebook.llm

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptsTest {

    private val transcript = "um so I was thinking we should like call the dentist"

    @Test
    fun renderIsSinglePass() {
        val out = Prompts.render(
            "{a} then {b} and a literal {\"json\": 1} and {unknown}",
            mapOf("a" to "{b}", "b" to "X"),
        )
        assertEquals("{b} then X and a literal {\"json\": 1} and {unknown}", out)
    }

    @Test
    fun theNotePromptAsksForNoBody() {
        assertFalse(Prompts.NOTE_PROMPT_VERBATIM.contains("content"))
    }

    @Test
    fun theNotePromptSubstitutesTheTranscript() {
        val rendered = Prompts.notePromptVerbatim(transcript)
        assertContains(rendered, transcript)
        assertFalse(rendered.contains("{transcript}"))
    }

    @Test
    fun theReminderPromptSubstitutesTheTranscriptAndAsksForNoTime() {
        val rendered = Prompts.reminderMessagePrompt("remind me at five")
        assertContains(rendered, "<transcript>\nremind me at five\n</transcript>")
        assertTrue(Regex("\\{[a-z_]+}").find(rendered) == null, "placeholder left: $rendered")
        assertFalse(rendered.contains("remind_in_seconds") || rendered.contains("remind_at"))
    }
}
