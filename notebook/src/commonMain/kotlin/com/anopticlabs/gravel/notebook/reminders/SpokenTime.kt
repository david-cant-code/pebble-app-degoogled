package com.anopticlabs.gravel.notebook.reminders

import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/** When a reminder's words say it should go off, as [SpokenTimeReader]'s fixed English rules read them. */
sealed interface SpokenTime {
    /** A time after the anchor and at most [MAX_REMIND_SECONDS] past it. */
    data class Read(val atMillis: Long) : SpokenTime

    /** Words that look like a time, with no time read from them that the rules are sure of. */
    data object Unread : SpokenTime

    /** No time words the rules recognize, or only vague ones such as "later" or "soon". */
    data object None : SpokenTime
}

/**
 * Reads a reminder's time from its transcript with fixed rules: durations ("in 2 hours 19 minutes"), clock times
 * ("half past 3", "at 7 in the morning"), days and dates ("next Friday", "October 20th", "end of the month") and
 * parts of the day. Durations count from the anchor, the recording's start; days and clock times are dates and
 * times in [TimeZone] at that moment. Words around a time that could change its meaning ("before the meeting at 3",
 * "last Friday", "the 5:15 train", a stray or corrected number) make the reading [SpokenTime.Unread] rather than a
 * guess.
 */
object SpokenTimeReader {

    /** The hour a day said without a time, or "next week/month/year", goes off. */
    private const val DAY_HOUR = 9

    /** Hours an unmarked clock time skips (00:00 to 05:59) unless "am" or the morning is said. */
    private val OVERNIGHT = 0..5

    fun read(transcript: String, anchor: Instant, zone: TimeZone): SpokenTime {
        val scan = Scanner(tokens(transcript)).scan()
        if (scan.unsure) return SpokenTime.Unread
        if (scan.found == 0) return if (scan.soft) SpokenTime.Unread else SpokenTime.None
        val at = resolve(scan, anchor, zone) ?: return SpokenTime.Unread
        val millis = at.toEpochMilliseconds()
        val ahead = millis - anchor.toEpochMilliseconds()
        return if (ahead > 0 && ahead <= MAX_REMIND_SECONDS * 1000L) SpokenTime.Read(millis) else SpokenTime.Unread
    }

    /**
     * [message] without the time words the rules read from it, punctuation tidied. [message] itself when its
     * reading is unsure, when a cut would fall between two words with only spaces around it and the word after it
     * does not open a phrase ("Watch Friday Night Lights"), or when what is left has no letter or digit or ends on a
     * word that went with the time.
     */
    fun withoutTimeWords(message: String): String {
        val toks = tokens(message)
        val scan = Scanner(toks).scan()
        if (scan.unsure || scan.spans.isEmpty()) return message
        fun gap(from: Int, to: Int) = message.substring(toks[from].end, toks[to].start)
        val cuts = mutableListOf<IntRange>()
        for (span in scan.spans.sortedBy { it.first }) {
            val prev = cuts.lastOrNull()
            if (prev != null && gap(prev.last, span.first).isBlank()) cuts[cuts.lastIndex] = prev.first..span.last else cuts += span
        }
        val midPhrase = cuts.any {
            it.first > 0 && it.last < toks.lastIndex && gap(it.first - 1, it.first).isBlank() && gap(it.last, it.last + 1).isBlank() &&
                toks[it.last + 1].text !in PHRASE_OPENERS
        }
        if (midPhrase) return message
        val out = StringBuilder()
        var at = 0
        for (cut in cuts) {
            out.append(message, at, toks[cut.first].start)
            at = toks[cut.last].end
        }
        out.append(message, at, message.length)
        val tidied = out.toString()
            .replace(EMPTY_BRACKETS, "")
            .replace(SPACES, " ")
            .replace(DOUBLED_DASHES, " - ")
            .replace(SPACE_BEFORE_PUNCTUATION, "$1")
            .replace(REPEATED_PUNCTUATION, "$1")
            .trim()
            .trim(',', ';', ':', '-', ' ')
            .trim()
        if (tidied.none { it.isLetterOrDigit() }) return message
        if (tidied.trimEnd('.', '!', '?').substringAfterLast(' ').lowercase() in DANGLING) return message
        return if (message.first().isUpperCase()) tidied.replaceFirstChar { it.uppercase() } else tidied
    }

    // Empty quotes need a space or an edge before and a space, an edge or punctuation after, so "Call" "Sam" stays.
    private val EMPTY_BRACKETS = Regex("""\(\s*\)|\[\s*]|\{\s*\}|(?<!\S)"\s*"(?![^\s,.!?;:])|(?<!\S)“\s*”(?![^\s,.!?;:])""")
    private val SPACES = Regex("""\s+""")
    private val DOUBLED_DASHES = Regex("""\s-(?:\s+-)+\s""")
    private val SPACE_BEFORE_PUNCTUATION = Regex("""\s+([,.!?;:])""")
    private val REPEATED_PUNCTUATION = Regex("""[,;:]+\s*([,.!?;:])""")

    /** Words that open a phrase, so a cut before one leaves a clean phrase: "Call Sam at 5 about the car". */
    private val PHRASE_OPENERS = setOf(
        "to", "and", "or", "then", "so", "about", "at", "from", "for", "with", "on", "in", "of", "by", "please",
        "if", "when", "because", "unless", "via", "regarding",
    )

    /** Words a cleanly cut message never ends on: they belonged to the time that was cut. */
    private val DANGLING = setOf(
        "for", "until", "till", "before", "after", "by", "every", "each", "at", "on", "in", "to", "from", "of",
        "around", "about", "the", "and", "or", "with", "than", "since", "last", "next", "this", "a", "an",
    )

    private fun resolve(scan: Scan, anchor: Instant, zone: TimeZone): Instant? {
        val local = anchor.toLocalDateTime(zone)
        val clock = scan.clocks.singleOrNull()
        val part = scan.parts.singleOrNull()
        val day = scan.days.singleOrNull()
        scan.durations.singleOrNull()?.let { d ->
            if (day != null || (d.millis != 0L && (clock != null || part != null))) return null
            if (d.months == 0 && d.days == 0) return Instant.fromEpochMilliseconds(anchor.toEpochMilliseconds() + d.millis)
            var date = local.date
            if (d.months != 0) date = date.plus(d.months, DateTimeUnit.MONTH)
            if (d.days != 0) date = date.plus(d.days, DateTimeUnit.DAY)
            if (clock == null && part == null) return Instant.fromEpochMilliseconds(date.atTime(local.time).toInstant(zone).toEpochMilliseconds() + d.millis)
            return firstAfter(listOf(date), clock, part, local)?.toInstant(zone)
        }
        if (part != null && part.todayOnly && day != null && day.kind != DayKind.Offset(0)) return null
        if (clock?.midnight == true && day != null) return null
        val dates = when {
            day != null -> datesFor(day.kind, local.date) ?: return null
            part?.todayOnly == true -> listOf(local.date)
            else -> listOf(local.date, local.date.plus(1, DateTimeUnit.DAY))
        }
        return firstAfter(dates, clock, part, local)?.toInstant(zone)
    }

    /** The first candidate time after [now] on [dates], in order; candidates on one date go morning first. */
    private fun firstAfter(dates: List<LocalDate>, clock: Clock?, part: PartOfDay?, now: LocalDateTime): LocalDateTime? {
        for (date in dates) {
            val times = timesOn(date, clock, part) ?: return null
            times.firstOrNull { it > now }?.let { return it }
        }
        return null
    }

    // Null when the words contradict each other, such as "at 8 pm in the morning".
    private fun timesOn(date: LocalDate, clock: Clock?, part: PartOfDay?): List<LocalDateTime>? {
        if (clock == null) return listOf(date.atTime(part?.pod?.hour ?: DAY_HOUR, 0))
        if (clock.midnight) return listOf(date.plus(1, DateTimeUnit.DAY).atTime(0, 0))
        val h = clock.hour
        val m = clock.minute
        return when (clock.meridiem) {
            Meridiem.H24 -> listOf(date.atTime(h, m)).takeIf { part == null || part.pod.allows(h) }
            Meridiem.AM -> listOf(date.atTime(h % 12, m)).takeIf { part == null || part.pod == Pod.MORNING }
            Meridiem.PM -> listOf(date.atTime(h % 12 + 12, m)).takeIf { part == null || part.pod != Pod.MORNING }
            Meridiem.NONE -> if (part != null) {
                part.pod.hourFor(h)?.let { listOf(date.atTime(it, m)) }
            } else {
                listOf(h % 12, h % 12 + 12).filter { it !in OVERNIGHT }.map { date.atTime(it, m) }
            }
        }
    }

    private fun datesFor(kind: DayKind, today: LocalDate): List<LocalDate>? = when (kind) {
        is DayKind.Offset -> listOf(today.plus(kind.days, DateTimeUnit.DAY))
        is DayKind.Weekday -> {
            val coming = today.plus(daysUntil(kind.day, today), DateTimeUnit.DAY)
            when {
                kind.extraWeeks > 0 -> listOf(coming.plus(kind.extraWeeks * 7, DateTimeUnit.DAY))
                // Said on that weekday, "next" means a week on; "on" means today while the time is still ahead.
                coming == today && kind.next -> listOf(today.plus(7, DateTimeUnit.DAY))
                coming == today -> listOf(today, today.plus(7, DateTimeUnit.DAY))
                else -> listOf(coming)
            }
        }
        is DayKind.Next -> listOf(today.plus(1, kind.unit))
        is DayKind.MonthDay -> monthDays(kind, today)
        DayKind.Weekend -> listOf(if (today.dayOfWeek == DayOfWeek.SUNDAY) today else today.plus(daysUntil(DayOfWeek.SATURDAY, today), DateTimeUnit.DAY))
        DayKind.EndOfWeek -> listOf(today.plus(daysUntil(DayOfWeek.FRIDAY, today), DateTimeUnit.DAY))
        DayKind.EndOfMonth -> listOf(LocalDate(today.year, today.month.number, 1).plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY))
    }

    private fun daysUntil(day: DayOfWeek, today: LocalDate) = (day.isoDayNumber - today.dayOfWeek.isoDayNumber + 7) % 7

    private fun monthDays(kind: DayKind.MonthDay, today: LocalDate): List<LocalDate>? {
        fun dateOrNull(year: Int, month: Int, day: Int) = runCatching { LocalDate(year, month, day) }.getOrNull()
        val firstOfMonth = LocalDate(today.year, today.month.number, 1)
        return when {
            kind.monthOffset != null -> firstOfMonth.plus(kind.monthOffset, DateTimeUnit.MONTH).let { listOfNotNull(dateOrNull(it.year, it.month.number, kind.day)) }
            kind.month != null -> listOfNotNull(dateOrNull(today.year, kind.month, kind.day), dateOrNull(today.year + 1, kind.month, kind.day))
            else -> (0..1).mapNotNull { firstOfMonth.plus(it, DateTimeUnit.MONTH).let { m -> dateOrNull(m.year, m.month.number, kind.day) } }
        }.takeIf { it.isNotEmpty() }
    }

    // ---- words ----

    /** A word and its offsets in the original text; [breakBefore] when punctuation stands between it and the last. */
    private class Tok(val text: String, val start: Int, val end: Int, val breakBefore: Boolean)

    // ASCII digits: on the phone \d also takes other scripts' digits, which toInt reads (SpokenTimeDeviceTest).
    private val TOKEN = Regex("""[0-9]+(?:[:.][0-9]+)?(?:st|nd|rd|th)?|[ap]\.\s?m\.?|[a-z]+(?:'[a-z]+)*""", RegexOption.IGNORE_CASE)
    private val BREAK = Regex("""[,;.!?:()\[\]"]""")

    // Each folds one character to one, so token offsets stay offsets into the original text.
    private val APOSTROPHES = Regex("[’‘ʼ′＇]")

    private fun tokens(s: String): List<Tok> {
        val folded = APOSTROPHES.replace(s, "'")
        var last = 0
        return TOKEN.findAll(folded).map { m ->
            val lower = m.value.lowercase().replace(" ", "")
            val tok = Tok(
                if (lower.first().isLetter()) lower.replace(".", "") else lower,
                m.range.first, m.range.last + 1,
                breakBefore = BREAK.containsMatchIn(folded.substring(last, m.range.first)),
            )
            last = m.range.last + 1
            tok
        }.toList()
    }

    private val SMALL = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    ).withIndex().associate { it.value to it.index }
    private val TENS = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)
    private val ORDINALS = listOf(
        "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth", "eleventh",
        "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth", "seventeenth", "eighteenth", "nineteenth",
    ).withIndex().associate { it.value to it.index + 1 } + mapOf("twentieth" to 20, "thirtieth" to 30)
    private val ORDINAL_DIGITS = Regex("""([0-9]{1,2})(st|nd|rd|th)""")
    private val DECIMAL = Regex("""[0-9]+\.[0-9]+""")
    private val COLON_TIME = Regex("""[0-9]{1,2}:[0-9]{2}""")
    private val CLOCK_DIGITS = Regex("""([0-9]{1,2})([:.])([0-9]{2})""")
    private val WEEKDAYS = mapOf(
        "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY,
    )
    private val MONTHS = listOf(
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december",
    ).withIndex().associate { it.value to it.index + 1 }

    /** Short month names; like every month, read only with a day number next to them. */
    private val MONTH_ABBREVIATIONS = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "jun" to 6, "jul" to 7, "aug" to 8, "sep" to 9, "sept" to 9,
        "oct" to 10, "nov" to 11, "dec" to 12,
    )

    // Month names that are also everyday words or given names: read only next to a day number, and taken for a
    // time word only after "in" or "of".
    private val COMMON_WORD_MONTHS = setOf("may", "march", "august", "april", "june")

    private enum class Measure(val millis: Long) { SECOND(1_000), MINUTE(60_000), HOUR(3_600_000), DAY(0), WEEK(0), MONTH(0), YEAR(0) }
    private val UNITS = mapOf(
        "second" to Measure.SECOND, "seconds" to Measure.SECOND, "sec" to Measure.SECOND, "secs" to Measure.SECOND,
        "minute" to Measure.MINUTE, "minutes" to Measure.MINUTE, "min" to Measure.MINUTE, "mins" to Measure.MINUTE,
        "hour" to Measure.HOUR, "hours" to Measure.HOUR, "hr" to Measure.HOUR, "hrs" to Measure.HOUR,
        "day" to Measure.DAY, "days" to Measure.DAY, "week" to Measure.WEEK, "weeks" to Measure.WEEK,
        "month" to Measure.MONTH, "months" to Measure.MONTH, "year" to Measure.YEAR, "years" to Measure.YEAR,
    )

    /** The unit a word names, "hour's" as "hour". */
    private fun unitOf(word: String?): Measure? = word?.let { UNITS[it.removeSuffix("'s")] }

    /** Words that name a time; one left unread makes the whole reading unsure. */
    private val TIME_WORDS = setOf(
        "o'clock", "noon", "midday", "midnight", "today", "tonight", "tomorrow", "yesterday",
        "weekend", "weekends", "weekday", "weekdays", "fortnight", "mondays", "tuesdays", "wednesdays", "thursdays",
        "fridays", "saturdays", "sundays", "daily", "weekly", "nightly", "monthly", "hourly", "yearly", "annually",
        "fortnightly", "biweekly", "christmas", "thanksgiving", "halloween", "easter",
    ) + WEEKDAYS.keys + (MONTHS.keys - COMMON_WORD_MONTHS)

    /** Time words in the possessive ("tomorrow's meeting"): a time read beside one may be the wrong one. */
    private val POSSESSIVE_TIMES = setOf("today's", "tonight's", "tomorrow's", "yesterday's", "morning's", "evening's", "night's") +
        WEEKDAYS.keys.map { "$it's" } + setOf("week's", "month's", "year's", "weekend's")

    /** Words before a unit ("every day", "next hour") that make it a time rather than a plain word ("my day"). */
    private val UNIT_LEADS = setOf("every", "each", "per", "this", "that", "next", "last", "coming", "few", "couple", "several", "hundred", "dozen", "following")

    /** Words that place a reminder relative to a time without being that time. */
    private val RELATIONS = setOf("before", "after", "by", "until", "till", "til", "than", "once", "following", "when")

    /** Verbs a "by" after them can belong to: "stop by the store tomorrow". */
    private val PASSING_VERBS = setOf(
        "stop", "drop", "come", "swing", "pop", "drive", "pass", "walk", "go", "run",
        "stopping", "dropping", "coming", "swinging", "popping", "driving", "passing", "walking",
    )
    private val BY_MEANS = setOf("card", "cash", "check", "phone", "email", "text", "mail", "post", "bus", "train", "car", "bike", "foot", "hand", "plane")
    private val FOR_LEADS = setOf("early", "late", "ready")
    private val REMINDER_NOUNS = setOf("reminder", "reminders", "alarm", "alarms")

    /** Words before a time that point at a part of it ("late tomorrow"); the first three, before a day, only after the cue or a break. */
    private val SPAN_WORDS = setOf("early", "earlier", "late", "mid", "middle", "during", "through", "throughout")
    private val CUE_WORDS = setOf("me", "us", "myself", "remind")
    private val PERIODS = setOf("week", "month", "year")

    /** Words before a time that make it another thing's time: "the 5:15 train", "that 3pm call", "these Friday meetings". */
    private val DETERMINERS = setOf("the", "a", "an", "my", "our", "your", "his", "their")
    private val POINTERS = setOf("this", "that", "her", "these", "those", "all")
    private val QUANTIFIERS = setOf("these", "those", "all")
    private val COPULAS = setOf(
        "is", "was", "are", "were", "be", "been", "it's", "that's", "what's", "there's", "here's",
        "starts", "begins", "ends", "opens", "closes", "leaves", "arrives", "departs", "lands", "finishes",
    )
    private val ADVERBS = setOf("now", "still", "actually", "also", "really", "probably", "definitely", "due")
    private val WHEN_WORDS = setOf("when", "once", "if", "until", "till")
    private val PLACE_WORDS = setOf("to", "at", "from", "by", "near", "in", "into", "past", "outside", "visit", "visiting")

    private val FILLERS = setOf("uh", "um", "umm", "uhm", "er", "erm", "ah", "oh", "hmm", "well", "like", "or", "and", "maybe")
    private val CORRECTIONS = setOf("no", "nope", "sorry", "actually", "rather", "wait", "oops", "correction", "scratch", "instead")
    private val TIME_HINTS = setOf(
        "half", "quarter", "past", "later", "earlier", "morning", "afternoon", "evening", "night", "noon", "midnight",
        "o'clock", "minute", "minutes", "hour", "hours",
    )

    /** Words before a time that make it another time: a past one, a repeat, or a part of a span. */
    private val QUALIFIERS = setOf("every", "each", "last", "since", "past", "previous", "other")
    private val SPAN_PARTS = setOf("end", "start", "beginning", "middle", "rest")
    private val MEALS = setOf("lunch", "lunchtime", "dinner", "dinnertime", "breakfast", "supper", "brunch", "bedtime")
    private val PLACES = setOf("work", "school", "bed")

    /** Words that make a reminder sound timed, enough to ask when no time is read. */
    private val SOFT = setOf("before", "after", "until", "till", "til", "when", "once", "whenever", "within")
    private val TIME_PREPOSITIONS = setOf("at", "in", "on", "by", "before", "after", "until", "till", "around", "from", "between")
    private val PODS = setOf("morning", "afternoon", "evening", "night")

    /** Words that can follow a time without changing it: function words and other time words. */
    private val CONTINUES = setOf(
        "at", "in", "on", "to", "and", "or", "for", "so", "please", "then", "when", "if", "about", "because", "with",
        "before", "after", "until", "till", "by", "from", "sharp", "exactly", "the", "a", "an", "my", "your", "our",
        "his", "her", "their", "i", "we", "you", "he", "she", "they", "it", "that", "this", "next", "okay", "ok",
        "is", "are", "am", "pm", "o'clock", "tonight", "tomorrow", "today", "of",
    ) + PODS + WEEKDAYS.keys

    // ---- parts ----

    private enum class Meridiem { AM, PM, NONE, H24 }

    private enum class Pod(val hour: Int) {
        MORNING(9), AFTERNOON(14), EVENING(18), NIGHT(20);

        /** The 24-hour hour an unmarked clock hour means in this part of the day, or null if it has none. */
        fun hourFor(h: Int): Int? = when (this) {
            MORNING -> h.takeIf { it in 1..11 }
            AFTERNOON -> when (h) { 12 -> 12; in 1..6 -> h + 12; else -> null }
            EVENING -> (h + 12).takeIf { h in 4..11 }
            NIGHT -> (h + 12).takeIf { h in 6..11 }
        }

        fun allows(hour24: Int): Boolean = when (this) {
            MORNING -> hour24 in 0..11
            AFTERNOON -> hour24 in 12..18
            EVENING -> hour24 in 16..23
            NIGHT -> hour24 in 18..23
        }
    }

    private fun podOf(word: String?) = when (word) {
        "morning" -> Pod.MORNING
        "afternoon" -> Pod.AFTERNOON
        "evening" -> Pod.EVENING
        "night" -> Pod.NIGHT
        else -> null
    }

    private sealed interface DayKind {
        data class Offset(val days: Int) : DayKind
        data class Weekday(val day: DayOfWeek, val extraWeeks: Int, val next: Boolean = false) : DayKind
        data class Next(val unit: DateTimeUnit.DateBased) : DayKind
        data class MonthDay(val month: Int?, val day: Int, val monthOffset: Int?) : DayKind
        data object Weekend : DayKind
        data object EndOfWeek : DayKind
        data object EndOfMonth : DayKind
    }

    private class Duration(val months: Int, val days: Int, val millis: Long)
    private class Clock(val hour: Int, val minute: Int, val meridiem: Meridiem, val midnight: Boolean = false)
    private class Day(val kind: DayKind)
    private class PartOfDay(val pod: Pod, val todayOnly: Boolean)

    private class Scan {
        val durations = mutableListOf<Duration>()
        val clocks = mutableListOf<Clock>()
        val days = mutableListOf<Day>()
        val parts = mutableListOf<PartOfDay>()
        val spans = mutableListOf<IntRange>()

        /** Numbers, ordinals and dotted times no part took, checked against the spans once the scan is done. */
        val loose = mutableListOf<Int>()
        var conflict = false
        var soft = false
        var possessive = false

        val found get() = durations.size + clocks.size + days.size + parts.size
        val unsure get() = conflict || durations.size > 1 || clocks.size > 1 || days.size > 1 || parts.size > 1 || (possessive && found > 0)
    }

    private class Scanner(private val t: List<Tok>) {
        private val scan = Scan()

        private fun w(i: Int): String? = t.getOrNull(i)?.text

        /** True when token [i] exists and no punctuation separates it from the one before. */
        private fun joined(i: Int): Boolean = t.getOrNull(i)?.breakBefore == false

        fun scan(): Scan {
            var i = 0
            while (i < t.size) {
                val end = vague(i) ?: duration(i) ?: clock(i) ?: dayOrDate(i) ?: partOfDay(i)
                if (end != null && end > i) {
                    if (qualified(i, end) || relatedEarlier(i)) scan.conflict = true
                    scan.spans += i until end
                    i = end
                    continue
                }
                leftover(i)
                i++
            }
            for (k in scan.loose) {
                if (scan.spans.any { k == it.first - 1 || k == it.last + 1 }) scan.conflict = true
                if (w(k - 1) == "at" || w(k - 2) == "at") scan.conflict = true
                if (afterFillers(k)) scan.conflict = true
            }
            if (scan.spans.any { describesOther(it) } || corrected()) scan.conflict = true
            return scan
        }

        /** Words around the span at [i] that change what it means ("for Friday", "last Friday", "late tomorrow", "the end of next week"). */
        private fun qualified(i: Int, end: Int): Boolean {
            val before = w(i - 1)
            if (before == "for" && !forTheReminder(i - 1)) return true
            if (before in QUALIFIERS) return true
            if (before == "following" && w(i) in WEEKDAYS.keys) return true
            // "late tomorrow", but "work late tomorrow" says how, not when.
            val cued = i < 2 || !joined(i - 1) || w(i - 2) in CUE_WORDS
            if (before in SPAN_WORDS && (cued || nextPeriod(i) || before !in setOf("early", "earlier", "late"))) return true
            if (joined(end) && w(end) in SPAN_WORDS) return true
            // A vague span beside "next week", "next month" or "next year": "sometime next week", "next week sometime".
            val prev = scan.spans.lastOrNull()
            if (prev != null && prev.last == i - 1 && joined(i)) {
                if (vague(prev.first) != null && nextPeriod(i)) return true
                if (vague(i) != null && nextPeriod(prev.first)) return true
            }
            return before == "of" && (w(i - 2) in SPAN_PARTS || w(i) == "next")
        }

        private fun nextPeriod(i: Int): Boolean = w(i) == "next" && w(i + 1) in PERIODS

        /** The "for" at [f] gives the reminder its own time: "set a reminder for 5 pm", "remind me for tomorrow". */
        private fun forTheReminder(f: Int): Boolean {
            val remindBefore = (0 until f).any { w(it)?.let { s -> s.startsWith("remind") && !s.startsWith("reminder") } == true }
            return when (w(f - 1)) {
                in REMINDER_NOUNS -> !remindBefore
                "it" -> !remindBefore && w(f - 2) in setOf("set", "put", "make", "schedule")
                "in" -> w(f - 2) in REMINDER_NOUNS
                "me", "us" -> w(f - 2)?.startsWith("remind") == true || (w(f - 2) == "for" && w(f - 3) in REMINDER_NOUNS)
                else -> false
            }
        }

        private fun itIs(i: Int): Boolean = w(i) == "it's" || (w(i) == "it" && w(i + 1) == "is")

        /** A relation word before the span at [i] in its clause: "by Friday", "before the meeting at 3", "ahead of the trip on Friday". */
        private fun relatedEarlier(i: Int): Boolean {
            var k = i
            while (k > 0 && joined(k)) {
                k--
                if (isRelation(k, i) && scan.spans.none { k in it }) return true
            }
            return false
        }

        private fun isRelation(k: Int, span: Int): Boolean = when (w(k)) {
            // "stop by the store tomorrow" goes with the verb; "come by 5 pm" is a deadline, "stop by at 5" a visit.
            "by" -> (w(k + 1) == "the" && w(k + 2) == "time") || (w(k + 1) == "check" && w(k + 2) == "in") ||
                (w(k + 1) !in BY_MEANS && (w(k - 1) !in PASSING_VERBS || (k == span - 1 && w(span) != "at" && w(span) != "on")))
            "after" -> w(k - 1) !in setOf("look", "looking", "looks")
            "when" -> !itIs(k + 1)
            "once" -> !itIs(k + 1) && w(k + 1) !in setOf("more", "again", "a", "an")
            "following" -> w(k + 1) != "up" && w(k - 1) != "the"
            "for" -> w(k - 1) in FOR_LEADS || (w(k - 1) == "time" && w(k - 2) in setOf("in", "on", "of", "enough", "good", "with", "ample", "extra"))
            "time" -> w(k - 1) == "in" && w(k + 1) == "to"
            "as" -> w(k + 1) == "soon" && w(k + 2) == "as" && w(k + 3) != "possible"
            in RELATIONS -> true
            "ahead" -> w(k + 1) == "of"
            "prior" -> w(k + 1) == "to"
            "advance" -> w(k + 1) == "of"
            else -> false
        }

        /** A number after a read time with only fillers between: "at 3, uh, 30", "at 3 or maybe 4". */
        private fun afterFillers(k: Int): Boolean {
            var j = k - 1
            var listed = false
            while (j >= 0 && w(j) in FILLERS) {
                if (w(j) == "and" || w(j) == "or") listed = true
                j--
            }
            if (j == k - 1 || scan.spans.none { it.last == j }) return false
            // "tomorrow and 2 loaves", "2 apples, uh, 3 apples": counts, not times.
            return if (listed) continuesTime(k + 1) else !recounts(k)
        }

        /** The number at [k] counts the thing an earlier number does: "2 apples, no, 3 apples". */
        private fun recounts(k: Int): Boolean =
            w(k + 1) != null && !continuesTime(k + 1) && (0 until k).any { isNumber(it) && w(it + 1) == w(k + 1) }

        /** A correction after punctuation or right after a read time, then time words: "at 3, no, make that 4". */
        private fun corrected(): Boolean {
            for (m in t.indices) {
                val end = correctionAt(m) ?: continue
                if (joined(m) && scan.spans.none { it.last == m - 1 }) continue
                if (scan.spans.none { it.last < m }) continue
                // A time hint counts within four words, a time or number to the end of that clause; an aside later does not.
                for (k in end until t.size) {
                    if (k >= end + 4 && !joined(k)) break
                    if (k < end + 4 && timeHint(k)) return true
                    if (scan.spans.any { it.first == k } || ((isNumber(k) || ORDINAL_DIGITS.matches(w(k)!!)) && k in scan.loose && !recounts(k)) || (ordinal(k) != null && k in scan.loose && continuesTime(k + 1))) return true
                }
            }
            return false
        }

        private fun timeHint(k: Int): Boolean = w(k) in TIME_HINTS || w(k) == "pm" || (w(k) == "am" && w(k - 1) != "i") || (w(k) == "day" && (w(k + 1) == "after" || w(k + 1) == "before" || w(k - 1) == "a"))

        private fun correctionAt(m: Int): Int? = when {
            w(m) in CORRECTIONS -> m + 1
            w(m) == "i" && w(m + 1) in setOf("mean", "meant", "said") -> m + 2
            w(m) in setOf("make", "change", "strike") && (w(m + 1) == "that" || w(m + 1) == "it") -> m + 2
            w(m) == "let's" && w(m + 1) == "say" -> m + 2
            w(m) == "never" && w(m + 1) == "mind" -> m + 2
            w(m) == "my" && w(m + 1) == "bad" -> m + 2
            w(m) == "on" && w(m + 1) == "second" && w(m + 2) == "thought" -> m + 3
            w(m) == "or" && w(m + 1) in setOf("better", "perhaps", "rather") -> m + 2
            w(m) == "or" && w(m + 1) == "should" && w(m + 2) == "i" && w(m + 3) == "say" -> m + 4
            else -> null
        }

        /** A span that is another thing's time: "the 5:15 train", "Sam's 3pm call", "the store closes at 9". */
        private fun describesOther(span: IntRange): Boolean {
            val i = span.first
            if (!joined(i)) return false
            val before = w(i - 1) ?: return false
            val clock = isNumber(i) || w(i)?.first() in '0'..'9' || w(i) in setOf("noon", "midday", "midnight", "half", "quarter")
            if (before in DETERMINERS) return true
            // "that 3pm call", "these Friday meetings", but "send that tomorrow", "bring them all tomorrow".
            if (before in POINTERS) return clock || (before in QUANTIFIERS && !continuesTime(span.last + 1))
            if (before.endsWith("'s") && before !in COPULAS && before !in POSSESSIVE_TIMES) {
                val led = w(i - 2) in DETERMINERS || w(i - 2) in POINTERS
                if (w(if (led) i - 3 else i - 2) in PLACE_WORDS && continuesTime(span.last + 1)) return false
                // "the game's tomorrow" is "is"; "call McDonald's tomorrow" names a place.
                return clock || w(i) in TIME_PREPOSITIONS || led || !continuesTime(span.last + 1)
            }
            val c = if (before in ADVERBS && joined(i - 1)) i - 2 else i - 1
            val verb = w(c) ?: return false
            if (verb !in COPULAS && !verb.endsWith("'re")) return false
            return !(verb == "it's" && w(c - 1) in WHEN_WORDS) && !(verb == "is" && w(c - 1) == "it" && w(c - 2) in WHEN_WORDS)
        }

        private fun leftover(i: Int) {
            val word = t[i].text
            val before = w(i - 1)
            when {
                word in TIME_WORDS -> scan.conflict = true
                // "take a second look": a "second" outside a duration is a plain word.
                word != "second" && unitOf(word) != null && (before in UNIT_LEADS || amountAt(i - 1) || (before == "of" && w(i - 2) in UNIT_LEADS)) -> scan.conflict = true
                (word == "am" || word == "pm") && isNumber(i - 1) -> scan.conflict = true
                word in PODS && (before == "every" || before == "each") -> scan.conflict = true
                word in COMMON_WORD_MONTHS && (before == "in" || before == "of") -> scan.conflict = true
                word in MEALS && (before in RELATIONS || before in setOf("at", "around", "during")) -> scan.conflict = true
                word in PLACES && before in RELATIONS -> scan.conflict = true
                isNumber(i) && before in TIME_PREPOSITIONS -> scan.conflict = true
                COLON_TIME.matches(word) -> scan.conflict = true
                word == "half" && isNumber(i + 1) && joined(i + 1) -> scan.conflict = true
                (word == "to" || word == "past") && isNumber(i - 1) && isNumber(i + 1) -> scan.conflict = true
                word == "next" && before == "after" -> scan.conflict = true
                word == "end" && w(i + 1) == "of" && (w(i + 2) == "day" || (w(i + 2) == "the" && w(i + 3) == "day")) -> scan.conflict = true
                // "by the 5th", "the 5th to call Sam"; "the 3rd floor" is a plain phrase.
                ordinal(i) != null && before == "the" && continuesTime(i + 1) -> scan.conflict = true
                word in POSSESSIVE_TIMES -> scan.possessive = true
                word in SOFT -> scan.soft = true
                word == "as" && w(i + 1) == "soon" && w(i + 2) == "as" -> scan.soft = true
            }
            if (isNumber(i) || ordinal(i) != null || DECIMAL.matches(word)) scan.loose += i
            // "on Friday the 30th": an ordinal after a "the" that follows a time.
            if (ordinal(i) != null && before == "the") scan.loose += i - 1
            if (word == "or" && isNumber(i + 1)) scan.loose += i
        }

        /** True at the end, after punctuation, or before a number or a word that can follow a time. */
        private fun continuesTime(i: Int): Boolean = w(i) == null || !joined(i) || w(i) in CONTINUES || isNumber(i)

        // ---- numbers ----

        /** Any whole number, however long, so an over-long one still counts as a number. */
        private fun isNumber(i: Int): Boolean = w(i)?.let { s -> s.isNotEmpty() && s.all { it.isDigit() } } == true || cardinal(i) != null

        private fun amountAt(i: Int): Boolean = i >= 0 && amount(i) != null

        /** A whole number at [i], up to four digits or in words up to ninety-nine, and the index after it. */
        fun cardinal(i: Int): Pair<Int, Int>? {
            val s = w(i) ?: return null
            if (s.all { it.isDigit() }) return if (s.length <= 4) s.toInt() to i + 1 else null
            SMALL[s]?.let { return it to i + 1 }
            val tens = TENS[s] ?: return null
            val unit = w(i + 1)?.takeIf { joined(i + 1) }?.let { SMALL[it] }?.takeIf { it in 1..9 }
            return if (unit != null) tens + unit to i + 2 else tens to i + 1
        }

        private fun ordinal(i: Int): Pair<Int, Int>? {
            val s = w(i) ?: return null
            ORDINAL_DIGITS.matchEntire(s)?.let { return it.groupValues[1].toInt() to i + 1 }
            ORDINALS[s]?.let { return it to i + 1 }
            val tens = TENS[s] ?: return null
            val unit = w(i + 1)?.takeIf { joined(i + 1) }?.let { ORDINALS[it] }?.takeIf { it in 1..9 } ?: return null
            return tens + unit to i + 2
        }

        /** An amount before a unit: a number, a decimal, "a" or "an", with "and a half" or "and a quarter" after it. */
        private fun amount(i: Int): Pair<Double, Int>? {
            val s = w(i) ?: return null
            val (value, next) = when {
                s == "a" || s == "an" -> 1.0 to i + 1
                DECIMAL.matches(s) -> s.toDouble() to i + 1
                else -> cardinal(i)?.let { it.first.toDouble() to it.second } ?: return null
            }
            return fraction(next)?.let { (f, end) -> value + f to end } ?: (value to next)
        }

        /** "and a half" or "and a quarter" at [i], unbroken. */
        private fun fraction(i: Int): Pair<Double, Int>? {
            if (w(i) != "and" || w(i + 1) != "a" || !joined(i + 1) || !joined(i + 2)) return null
            return when (w(i + 2)) {
                "half" -> 0.5 to i + 3
                "quarter" -> 0.25 to i + 3
                else -> null
            }
        }

        // ---- durations ----

        private fun duration(i: Int): Int? {
            val inForm = w(i) == "in" || w(i) == "within"
            var j = if (inForm) i + 1 else i
            var months = 0
            var days = 0
            var millis = 0L
            var last: Term? = null
            val units = mutableSetOf<Measure>()
            var andBefore = false
            var repeatedUnit = false
            while (true) {
                val term = term(j) ?: break
                // "in five minutes, five minutes": said again, the same amount counts once.
                val again = last != null && !andBefore && term.amount == last.amount
                if (last != null && !again) {
                    if (term.unit in units) {
                        repeatedUnit = true
                        break
                    }
                    // Past punctuation, a new amount chains only after "and": "in an hour, and 5 minutes".
                    if (!joined(j)) break
                }
                if (!again) {
                    months += term.months
                    days += term.days
                    millis += term.millis
                    units += term.unit
                }
                last = term
                j = term.end
                andBefore = w(j) == "and" && term(j + 1) != null
                if (andBefore) j++
            }
            if (last == null) return null
            if (w(j) == "time") j++
            if (inForm && w(j) == "from" && w(j + 1) == "now") j += 2
            val fromNow = !inForm && w(j) == "from" && w(j + 1) == "now"
            if (!inForm && !fromNow) return null
            if (fromNow) j += 2
            if (repeatedUnit) scan.conflict = true
            scan.durations += Duration(months, days, millis)
            return j
        }

        private class Term(val months: Int, val days: Int, val millis: Long, val unit: Measure, val end: Int) {
            val amount get() = Triple(months, days, millis)
        }

        private fun term(i: Int): Term? {
            // "half an hour", "a half hour", "half a day".
            val h = if (w(i) == "a" && w(i + 1) == "half") i + 1 else i
            if (w(h) == "half") {
                val u = if (w(h + 1) == "an" || w(h + 1) == "a") h + 2 else h + 1
                if (u != h + 1 || h != i) unitOf(w(u))?.let { return scaled(0.5, it, u + 1) }
            }
            val q = if (w(i) == "a") i + 1 else i
            if (w(q) == "quarter") {
                val end = when {
                    w(q + 1) == "of" && w(q + 2) == "an" && w(q + 3) == "hour" -> q + 4
                    w(q + 1) == "hour" -> q + 2
                    else -> return null
                }
                return Term(0, 0, 15 * Measure.MINUTE.millis, Measure.HOUR, end)
            }
            val (value, next) = amount(i) ?: return null
            if (!joined(next)) return null
            val unit = unitOf(w(next)) ?: return null
            var end = next + 1
            var total = value
            fraction(end)?.let { (f, e) ->
                total += f
                end = e
            }
            return scaled(total, unit, end)
        }

        private fun scaled(value: Double, unit: Measure, end: Int): Term? {
            if (value > MAX_REMIND_SECONDS) return null
            val whole = value.toInt()
            val fraction = value - whole
            return when (unit) {
                Measure.SECOND, Measure.MINUTE, Measure.HOUR -> Term(0, 0, (value * unit.millis).toLong(), unit, end)
                Measure.DAY -> Term(0, whole, (fraction * 24 * Measure.HOUR.millis).toLong(), unit, end)
                Measure.WEEK -> if (fraction == 0.0) Term(0, whole * 7, 0, unit, end) else null
                Measure.MONTH -> if (fraction == 0.0) Term(whole, 0, 0, unit, end) else null
                Measure.YEAR -> if (fraction == 0.0) Term(whole * 12, 0, 0, unit, end) else null
            }
        }

        // ---- clock times ----

        private fun clock(i: Int): Int? {
            var j = i
            val led = w(j) == "at"
            if (led) j++
            if (led && (w(j) == "around" || w(j) == "about" || w(j) == "approximately")) j++
            when (w(j)) {
                "noon", "midday" -> return add(Clock(12, 0, Meridiem.PM), j + 1)
                "midnight" -> return add(Clock(0, 0, Meridiem.H24, midnight = true), j + 1)
            }
            if (w(j) == "12" && w(j + 1) == "noon" && joined(j + 1)) return add(Clock(12, 0, Meridiem.PM), j + 2)
            relative(j, led)?.let { (hour, minute, end) -> return meridiem(hour, minute, end, led) }
            val s = w(j) ?: return null
            CLOCK_DIGITS.matchEntire(s)?.let { m ->
                val hour = m.groupValues[1].toInt()
                val minute = m.groupValues[3].toInt()
                // "1.50 hours" is a duration, so a dotted time needs "at" or am/pm.
                if (m.groupValues[2] == "." && !led && !marked(j + 1)) return null
                return meridiem(hour, minute, j + 1, led = true, h24 = hour >= 13 || hour == 0 || m.groupValues[1].startsWith("0"))
            }
            val (hour, afterHour) = cardinal(j) ?: return null
            var minute = 0
            var end = afterHour
            if (joined(afterHour)) {
                val oh = w(afterHour) == "oh" || w(afterHour) == "o"
                val m = if (oh && joined(afterHour + 1)) cardinal(afterHour + 1) else if (!oh) cardinal(afterHour) else null
                if (m != null && ((oh && m.first in 1..9) || (!oh && m.first in 10..59))) {
                    // Without "at", "seven thirty" is a time only when something marks it as one.
                    if (led || marked(m.second) || podFollows(m.second)) {
                        minute = m.first
                        end = m.second
                    }
                }
            }
            if (!led && !marked(end)) {
                // "take 2 in the morning": without "at", a number before a part of the day is an hour only outside the night.
                val hour24 = podAt(end)?.hourFor(hour)
                if (hour24 == null || hour24 in OVERNIGHT) return null
            }
            return meridiem(hour, minute, end, led)
        }

        private fun marked(i: Int): Boolean = joined(i) && (w(i) == "am" || w(i) == "pm" || w(i) == "o'clock")

        private fun podFollows(i: Int): Boolean = podAt(i) != null

        private fun podAt(i: Int): Pod? = when (w(i)) {
            "tonight" -> Pod.NIGHT
            "in" -> podOf(w(i + 2))?.takeIf { w(i + 1) == "the" && it != Pod.NIGHT }
            "at" -> Pod.NIGHT.takeIf { w(i + 1) == "night" }
            "this" -> podOf(w(i + 1))?.takeIf { it != Pod.NIGHT }
            else -> null
        }

        /**
         * "half past 3", "quarter to 4", "at ten past four", "20 minutes to 6": the hour, minute and index after.
         * A plain number needs "at" or "minutes", so "10 to 12 apples" is not a time.
         */
        private fun relative(i: Int, led: Boolean): Triple<Int, Int, Int>? {
            val numbered = w(i) != "half" && w(i) != "quarter" && w(i) != "a"
            val (minutes, afterAmount) = when (w(i)) {
                "half" -> 30 to i + 1
                "quarter" -> 15 to i + 1
                "a" -> if (w(i + 1) == "quarter") 15 to i + 2 else return null
                else -> cardinal(i)?.takeIf { it.first in 1..29 } ?: return null
            }
            val spelled = w(afterAmount) == "minutes" || w(afterAmount) == "minute"
            if (numbered && !led && !spelled) return null
            val j = if (spelled) afterAmount + 1 else afterAmount
            val toward = when (w(j)) {
                "past", "after" -> false
                "to", "of", "before" -> true
                else -> return null
            }
            if (!joined(j) || !joined(j + 1)) return null
            // "half to 4" and "a quarter to midnight": times the rules do not count out, so they ask.
            if ((minutes == 30 && toward) || w(j + 1) == "noon" || w(j + 1) == "midnight") {
                scan.conflict = true
                return null
            }
            val (hour, end) = cardinal(j + 1)?.takeIf { it.first in 1..12 } ?: return null
            return if (toward) Triple(if (hour == 1) 12 else hour - 1, 60 - minutes, end) else Triple(hour, minutes, end)
        }

        private fun meridiem(hour: Int, minute: Int, end: Int, led: Boolean, h24: Boolean = false): Int? {
            if (minute !in 0..59) return null
            var j = end
            if (w(j) == "o'clock" && joined(j)) j++
            val marked = if (joined(j)) {
                when (w(j)) {
                    "am" -> Meridiem.AM
                    "pm" -> Meridiem.PM
                    else -> null
                }
            } else {
                null
            }
            if (marked != null) {
                if (hour !in 1..12) return null
                return add(Clock(hour, minute, marked), j + 1)
            }
            if (h24) return if (hour in 0..23) add(Clock(hour, minute, Meridiem.H24), j) else null
            if (hour !in 1..12) return null
            // "at 3 apartments": a word after an unmarked hour that is not about time makes the hour unsure.
            if (led && j == end && minute == 0 && !continuesTime(j)) scan.conflict = true
            return add(Clock(hour, minute, Meridiem.NONE), j)
        }

        private fun add(clock: Clock, end: Int): Int {
            scan.clocks += clock
            return end
        }

        // ---- days and dates ----

        private fun dayOrDate(i: Int): Int? {
            var j = i
            when (w(j)) {
                "today" -> return day(DayKind.Offset(0), j + 1)
                "tomorrow" -> return day(DayKind.Offset(1), j + 1)
            }
            val the = if (w(j) == "the") j + 1 else j
            if (w(the) == "day" && w(the + 1) == "after" && w(the + 2) == "tomorrow") return day(DayKind.Offset(2), the + 3)
            if (w(the) == "end" && w(the + 1) == "of" && (w(the + 2) == "the" || w(the + 2) == "this")) {
                when (w(the + 3)) {
                    "week" -> return day(DayKind.EndOfWeek, the + 4)
                    "month" -> return day(DayKind.EndOfMonth, the + 4)
                }
            }
            if (w(j) == "on" && w(j + 1) == "the" && w(j + 2) == "weekend") return day(DayKind.Weekend, j + 3)
            if (w(j) == "over" && w(j + 1) == "the" && w(j + 2) == "weekend") return day(DayKind.Weekend, j + 3)
            if (w(j) == "this" && w(j + 1) == "coming" && w(j + 2) == "weekend") return day(DayKind.Weekend, j + 3)
            if ((w(j) == "this" || w(j) == "the") && w(j + 1) == "weekend") return day(DayKind.Weekend, j + 2)
            if (w(j) == "next" && w(j + 1) in setOf("week", "month", "year")) {
                val unit = when (w(j + 1)) {
                    "week" -> DateTimeUnit.WEEK
                    "month" -> DateTimeUnit.MONTH
                    else -> DateTimeUnit.YEAR
                }
                if (unit == DateTimeUnit.WEEK) {
                    val on = if (w(j + 2) == "on") j + 3 else j + 2
                    WEEKDAYS[w(on)]?.let { return day(DayKind.Weekday(it, 1), on + 1) }
                }
                return day(DayKind.Next(unit), j + 2)
            }
            // "on", "this", "coming" and "this coming" mean the coming one; "next" too, but a week on when said that day.
            if (w(j) == "on") j++
            if (w(j) == "this") j++
            var next = false
            if (w(j) == "coming") {
                j++
            } else if (w(j) == "next") {
                next = true
                j++
            }
            WEEKDAYS[w(j)]?.let { weekday ->
                val k = j + 1
                if (w(k) == "next" && w(k + 1) == "week") return day(DayKind.Weekday(weekday, 1), k + 2)
                if (w(k) == "of" && w(k + 1) == "next" && w(k + 2) == "week") return day(DayKind.Weekday(weekday, 1), k + 3)
                // "Friday week" means a week on in some English; the rules do not read it.
                if (w(k) == "week") scan.conflict = true
                return day(DayKind.Weekday(weekday, 0, next), k)
            }
            return date(i)
        }

        private fun month(word: String?): Int? = word?.let { MONTHS[it] ?: MONTH_ABBREVIATIONS[it] }

        /** "October 20th", "Oct 20", "the 20th of October", "on the 20th", "the first of next month". */
        private fun date(i: Int): Int? {
            var j = i
            if (w(j) == "on") j++
            month(w(j))?.let { m ->
                val k = if (w(j + 1) == "the") j + 2 else j + 1
                val (dayNumber, end) = ordinal(k) ?: cardinal(k)?.takeIf { it.first in 1..31 } ?: return null
                return dated(DayKind.MonthDay(m, dayNumber, null), end)
            }
            if (w(j) != "the") return null
            val (dayNumber, afterDay) = ordinal(j + 1)?.takeIf { it.first in 1..31 } ?: return null
            if (w(afterDay) == "of") {
                month(w(afterDay + 1))?.let { return dated(DayKind.MonthDay(it, dayNumber, null), afterDay + 2) }
                if (w(afterDay + 1) == "next" && w(afterDay + 2) == "month") return dated(DayKind.MonthDay(null, dayNumber, 1), afterDay + 3)
                if ((w(afterDay + 1) == "the" || w(afterDay + 1) == "this") && w(afterDay + 2) == "month") return dated(DayKind.MonthDay(null, dayNumber, 0), afterDay + 3)
                return null
            }
            // A bare "on the 3rd" is a date only where a time can end: "on the 3rd floor" is a plain phrase.
            return if (w(i) == "on" && continuesTime(afterDay)) dated(DayKind.MonthDay(null, dayNumber, null), afterDay) else null
        }

        private fun dated(kind: DayKind, end: Int): Int {
            // A year after the date is not read: the rules always take the next one coming.
            if (w(end)?.let { it.length == 4 && it.all(Char::isDigit) } == true) scan.conflict = true
            return day(kind, end)
        }

        private fun day(kind: DayKind, end: Int): Int {
            scan.days += Day(kind)
            return end
        }

        // ---- parts of the day ----

        private fun partOfDay(i: Int): Int? {
            if (w(i) == "tonight") return part(Pod.NIGHT, todayOnly = true, i + 1)
            if (w(i) == "this") podOf(w(i + 1))?.takeIf { it != Pod.NIGHT }?.let { return part(it, todayOnly = true, i + 2) }
            if (w(i) == "in" && w(i + 1) == "the") podOf(w(i + 2))?.takeIf { it != Pod.NIGHT }?.let { return part(it, todayOnly = false, i + 3) }
            if (w(i) == "at" && w(i + 1) == "night") return part(Pod.NIGHT, todayOnly = false, i + 2)
            // Bare, "morning" is often a plain word ("my morning alarm"), so it counts only after a day.
            podOf(w(i))?.takeIf { w(i - 1) in WEEKDAYS.keys + setOf("tomorrow", "today") }?.let { return part(it, todayOnly = false, i + 1) }
            return null
        }

        private fun part(pod: Pod, todayOnly: Boolean, end: Int): Int {
            scan.parts += PartOfDay(pod, todayOnly)
            return end
        }

        // ---- vague ----

        private fun vague(i: Int): Int? = when {
            // "the later flight", "as soon as I'm home": not a vague time.
            w(i) == "later" && w(i - 1) in setOf("the", "a", "an") -> null
            w(i) == "soon" && w(i - 1) == "as" -> null
            w(i) == "later" && w(i + 1) == "today" -> i + 2
            w(i) == "later" && w(i + 1) == "on" -> i + 2
            w(i) in setOf("later", "soon", "sometime", "eventually") -> i + 1
            w(i) == "some" && w(i + 1) == "time" -> i + 2
            w(i) == "in" && w(i + 1) == "a" && w(i + 2) in setOf("bit", "while") -> i + 3
            w(i) == "in" && w(i + 1) == "a" && w(i + 2) == "little" && w(i + 3) in setOf("bit", "while") -> i + 4
            w(i) == "at" && w(i + 1) == "some" && w(i + 2) == "point" -> i + 3
            else -> null
        }
    }
}
