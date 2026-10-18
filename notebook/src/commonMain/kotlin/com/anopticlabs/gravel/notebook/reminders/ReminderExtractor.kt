package com.anopticlabs.gravel.notebook.reminders

import com.anopticlabs.gravel.notebook.llm.LlmJson
import com.anopticlabs.gravel.notebook.llm.stringOrNull
import kotlinx.serialization.json.JsonObject

/**
 * Builds a voice reminder's [ReminderProposal]: its message from the reminder prompt's reply, its time from
 * [SpokenTimeReader]. The transcript was already judged a reminder, so this always yields one.
 */
object ReminderExtractor {

    const val FALLBACK_MESSAGE_MAX_CHARS = 200
    const val FALLBACK_MESSAGE = "Reminder"

    fun proposal(raw: String, transcript: String, spoken: SpokenTime): ReminderProposal {
        val message = message(raw, transcript)
        return when (spoken) {
            is SpokenTime.Read -> ReminderProposal(message, spoken.atMillis, ReminderTimeStatus.USABLE)
            SpokenTime.Unread -> ReminderProposal(message, null, ReminderTimeStatus.TO_CHECK)
            SpokenTime.None -> ReminderProposal(message, null, ReminderTimeStatus.NO_TIME)
        }
    }

    /**
     * The reply's message without the time words the rules recognize; an unreadable reply, or one
     * without a message, falls back to a capped slice of [transcript], time words removed too.
     */
    fun message(raw: String, transcript: String): String {
        val obj = LlmJson.extract(LlmJson.cleanThinkAndFences(raw)) as? JsonObject
        val said = obj?.get("message")?.stringOrNull()?.trim()?.ifEmpty { null }
        return said?.let(SpokenTimeReader::withoutTimeWords) ?: fallbackMessage(transcript)
    }

    private fun fallbackMessage(transcript: String): String {
        val collapsed = SpokenTimeReader.withoutTimeWords(transcript.replace(Regex("""\s+"""), " ").trim())
        if (collapsed.isEmpty()) return FALLBACK_MESSAGE
        return if (collapsed.length <= FALLBACK_MESSAGE_MAX_CHARS) collapsed
        else collapsed.substring(0, FALLBACK_MESSAGE_MAX_CHARS).trim()
    }
}
