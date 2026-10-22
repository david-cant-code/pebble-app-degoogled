package com.anopticlabs.gravel.notebook.reminders

/**
 * Decides whether a transcript opens with a request for a reminder, from an explicit cue in its
 * first words. Guards win over cues, and anything ambiguous is a note: a missed reminder is
 * recoverable from the note, a spurious alarm is not.
 */
object ReminderIntent {

    private const val CUE_WINDOW_WORDS = 8

    private val CUE_REGEXES = listOf(
        Regex("""\bremind me\b"""),
        Regex("""\bremind myself\b"""),
        Regex("""\bremember to\b"""),
        // The noun form needs a request verb and a continuation word, so a mention is not a request.
        Regex("""\b(set|give|gimme|make|create|add|put|schedule|want|need)( me| us| myself)? an? (reminder|alarm) (to|for|about|at|on|in|that|when)\b"""),
        Regex("""\b(don'?t|do not) let me forget\b"""),
        Regex("""\b(ping|alert|notify|nudge|wake) me\b"""),
    )

    private val LEADING_REMINDER = Regex("""^reminder\b""")

    private val GUARD_REGEXES = listOf(
        Regex("""\b(that|this) reminds? me\b"""),
        // A recall question; which/where count only when no infinitive follows.
        Regex("""\bremind me\b[,\s]*(of |about |again |just |once |please |exactly )*(what|who|whom|whose|how|why)\b"""),
        Regex("""\bremind me\b[,\s]*(of |about |again |just |once |please |exactly )*(which|where)\b(?!.*\bto\b)"""),
        Regex("""\ban? (reminder|alarm)\b[,\s]*(of |about |again |just |once |please |exactly )*(what|who|whom|whose|how|why)\b"""),
        Regex("""\ban? (reminder|alarm)\b[,\s]*(of |about |again |just |once |please |exactly )*(which|where)\b(?!.*\bto\b)"""),
        Regex("""\bthey remind me\b"""),
        Regex("""\b(said|says|saying|say|tell|told|telling|tells|wrote|writes|writing|texted|emailed|asked|asks)\b.*\bremind(s)? me\b"""),
        Regex("""\b(he|she|it|they|the \w+|my \w+|his \w+|her \w+|their \w+|our \w+|app|phone|calendar|doctor|boss) (will|'?ll|keeps?|kept|tries|tried|trying|gonna|usually|always)\b.{0,15}?\bremind me\b"""),
    )

    fun looksLikeReminder(transcript: String): Boolean {
        val window = leadingWindow(normalize(transcript))
        if (window.isEmpty()) return false
        if (GUARD_REGEXES.any { it.containsMatchIn(window) }) return false
        if (LEADING_REMINDER.containsMatchIn(window)) return true
        return CUE_REGEXES.any { it.containsMatchIn(window) }
    }

    // Right and left single quotes, the modifier apostrophe and prime, the prime, the fullwidth apostrophe.
    private val APOSTROPHE_FAMILY = Regex("[\u2019\u2018\u02BC\u02B9\u2032\uFF07]")

    // No-break, narrow no-break, figure and em spaces, which \s does not cover.
    private val UNICODE_SPACE_FAMILY = Regex("[\u00A0\u202F\u2007\u2003]")

    private const val ZERO_WIDTH_SPACE = "\u200B"

    /** Lowercased, apostrophes and spaces folded to ASCII, zero-width spaces dropped, whitespace collapsed. */
    internal fun normalize(s: String): String =
        s.lowercase()
            .replace(APOSTROPHE_FAMILY, "'")
            .replace(ZERO_WIDTH_SPACE, "")
            .replace(UNICODE_SPACE_FAMILY, " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun leadingWindow(normalized: String): String =
        if (normalized.isEmpty()) ""
        else normalized.split(' ').take(CUE_WINDOW_WORDS).joinToString(" ")
}
