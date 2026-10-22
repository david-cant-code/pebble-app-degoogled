package com.anopticlabs.gravel.notebook.reminders

import com.anopticlabs.gravel.notebook.search.BodyTokenizer
import kotlin.math.abs
import kotlinx.datetime.TimeZone

/**
 * Ranks the reminders a spoken completion ("done, I fed the dog") might refer to. Rank only: nothing
 * is resolved without a tap, so the matcher has to put the right reminder near the top, not pick it.
 */
object ReminderMatch {

    /** One candidate with its occurrence key; [score] is the share of the utterance's tokens it covers. */
    data class ScoredCandidate(
        val reminderId: Int,
        val originalFireMillis: Long,
        val message: String,
        val status: OccurrenceStatus,
        val score: Double,
    )

    const val MAX_CANDIDATES = 10

    /**
     * The utterance after a leading [triggerWord], or null when it does not start with the word;
     * "" when the utterance is only the trigger. A trigger that normalizes to nothing never matches.
     */
    fun stripTriggerPrefix(utterance: String, triggerWord: String): String? {
        val trigger = ReminderIntent.normalize(triggerWord).trim { !it.isLetterOrDigit() && it != '\'' }
        if (trigger.isEmpty()) return null
        val text = ReminderIntent.normalize(utterance)
        if (text.isEmpty()) return null
        val match = Regex("^" + Regex.escape(trigger) + "\\b").find(text) ?: return null
        return text.substring(match.range.last + 1).dropWhile { !it.isLetterOrDigit() }.trim()
    }

    /**
     * Candidates from the active reminders (PENDING at their next fire) and the fired events (at
     * their own status), best first: coverage, then Jaccard, then distance of the effective fire
     * from now. Reminders sharing no token with the utterance are left out.
     */
    fun rankReminderCandidates(
        utterance: String,
        activeReminders: List<ReminderItem>,
        events: List<ReminderEvent>,
        nowMillis: Long,
        zone: TimeZone,
        maxCandidates: Int = MAX_CANDIDATES,
        messageTokens: (String) -> Set<String> = BodyTokenizer::tokenizeQuery,
    ): List<ScoredCandidate> {
        val utterTokens = BodyTokenizer.tokenizeQuery(foldIrregularPast(utterance))
        if (utterTokens.isEmpty()) return emptyList()

        val scored = ArrayList<Triple<ScoredCandidate, Double, Long>>()
        for (r in activeReminders) {
            val s = scoreOf(utterTokens, messageTokens(r.message)) ?: continue
            val effectiveFire = r.recurrence?.takeIf { Recurrence.isValid(it) }
                ?.let { Recurrence.nextAfter(it, nowMillis - 1, zone) }
                ?: r.remindAtMillis
            scored.add(
                Triple(
                    ScoredCandidate(r.id, r.remindAtMillis, r.message, OccurrenceStatus.PENDING, s.coverage),
                    s.jaccard,
                    abs(effectiveFire - nowMillis),
                ),
            )
        }
        val latestDue = latestDueByReminder(events, nowMillis)
        for (e in events) {
            val s = scoreOf(utterTokens, messageTokens(e.message)) ?: continue
            scored.add(
                Triple(
                    ScoredCandidate(e.reminderId, e.originalFireMillis, e.message, statusFor(e, nowMillis, zone, latestDue[e.reminderId]), s.coverage),
                    s.jaccard,
                    abs(e.originalFireMillis - nowMillis),
                ),
            )
        }
        return scored
            .sortedWith(
                compareByDescending<Triple<ScoredCandidate, Double, Long>> { it.first.score }
                    .thenByDescending { it.second }
                    .thenBy { it.third },
            )
            .take(maxCandidates)
            .map { it.first }
    }

    private class TokenScore(val coverage: Double, val jaccard: Double)

    private fun scoreOf(utter: Set<String>, message: Set<String>): TokenScore? {
        if (message.isEmpty()) return null
        val inter = utter.count { it in message }
        if (inter == 0) return null
        val union = utter.size + message.size - inter
        return TokenScore(coverage = inter.toDouble() / utter.size, jaccard = inter.toDouble() / union)
    }

    // The same character class the tokenizer splits on, so digit and apostrophe words survive the fold.
    private val WORD_SPLIT = Regex("[^\\p{L}\\p{N}']+")

    // Irregular past forms the stemmer cannot fold; homographs (saw, left, found) read as verbs here.
    private val IRREGULAR_PAST: Map<String, String> = mapOf(
        "took" to "take", "taken" to "take",
        "gave" to "give", "given" to "give",
        "fed" to "feed",
        "did" to "do", "done" to "do",
        "made" to "make",
        "paid" to "pay",
        "sent" to "send",
        "bought" to "buy",
        "brought" to "bring",
        "got" to "get", "gotten" to "get",
        "ran" to "run",
        "went" to "go", "gone" to "go",
        "ate" to "eat", "eaten" to "eat",
        "drank" to "drink", "drunk" to "drink",
        "wrote" to "write", "written" to "write",
        "drove" to "drive", "driven" to "drive",
        "threw" to "throw", "thrown" to "throw",
        "swept" to "sweep",
        "woke" to "wake", "woken" to "wake",
        "left" to "leave",
        "met" to "meet",
        "spoke" to "speak", "spoken" to "speak",
        "told" to "tell",
        "held" to "hold",
        "hung" to "hang",
        "swam" to "swim", "swum" to "swim",
        "saw" to "see", "seen" to "see",
        "caught" to "catch",
        "taught" to "teach",
        "built" to "build",
        "lost" to "lose",
        "kept" to "keep",
        "slept" to "sleep",
        "sat" to "sit",
        "stood" to "stand",
        "found" to "find",
        "lent" to "lend",
        "spent" to "spend",
        "won" to "win",
    )

    private fun foldIrregularPast(text: String): String =
        WORD_SPLIT.split(text.lowercase()).joinToString(" ") { word -> IRREGULAR_PAST[word] ?: word }
}
