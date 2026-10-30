package com.anopticlabs.gravel.notebook.reminders

/** Openers and fillers a capture often starts with; as a trigger word they would divert real notes. */
val TRIGGER_WORD_BLOCKLIST: Set<String> = setOf(
    "the", "a", "an", "and", "to", "i", "my", "me", "it", "is", "of", "for",
    "ok", "okay", "so", "well", "um", "uh", "hey", "hi", "hello", "please",
    "remind", "reminder", "remember", "note", "new", "add", "create", "make",
)

const val MAX_TRIGGER_WORD_CHARS = 64

private val TRIGGER_WORD_SHAPE = Regex("^[a-z0-9]{2,}$")

/**
 * A single ASCII alphanumeric token of at least two characters, after [ReminderIntent.normalize],
 * and not a blocklisted opener. ASCII only because the matcher's `\b` anchor fires only after an
 * ASCII word character.
 */
fun isValidTriggerWord(word: String): Boolean {
    if (word.length > MAX_TRIGGER_WORD_CHARS) return false
    val normalized = ReminderIntent.normalize(word)
    if (!TRIGGER_WORD_SHAPE.matches(normalized)) return false
    return normalized !in TRIGGER_WORD_BLOCKLIST
}

/** [input] as it is stored once it validates; null when it does not. */
fun triggerWordToStore(input: String): String? = if (isValidTriggerWord(input)) ReminderIntent.normalize(input) else null

/** The feature counts as on only while the stored word still validates. */
fun effectiveTriggerEnabled(storedEnabled: Boolean, storedWord: String): Boolean =
    storedEnabled && isValidTriggerWord(storedWord)

/** The utterance to park (possibly "") when [enabled] and [transcript] opens with [triggerWord]; else null. */
fun shouldParkTriggerCompletion(enabled: Boolean, triggerWord: String, transcript: String?): String? {
    if (!enabled) return null
    return ReminderMatch.stripTriggerPrefix(transcript ?: "", triggerWord)
}
