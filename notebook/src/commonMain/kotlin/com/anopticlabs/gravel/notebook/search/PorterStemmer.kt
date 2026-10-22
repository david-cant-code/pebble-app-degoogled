package com.anopticlabs.gravel.notebook.search

/**
 * The Porter (1980) stemming algorithm, self-contained so the indexer and every searcher agree
 * on stems. Not idempotent: some stems stem further, so stems are always derived from display
 * text and never re-stemmed.
 */
internal object PorterStemmer {
    private val VOWELS = setOf('a', 'e', 'i', 'o', 'u')

    // Porter's consonant: not a vowel, and a 'y' only when not preceded by a consonant. The
    // parity along a run of 'y's is computed by walking left, never by recursing per letter.
    private fun isCons(s: String, i: Int): Boolean {
        val c = s[i]
        if (c in VOWELS) return false
        if (c != 'y') return true
        var k = i
        while (k > 0 && s[k - 1] == 'y') k--
        if (k == 0) return i % 2 == 0
        val base = s[k - 1] !in VOWELS
        return if ((i - k) % 2 == 0) !base else base
    }

    /** Porter's measure m, the number of VC pairs in [C](VC){m}[V]. */
    private fun measure(s: String): Int {
        if (s.isEmpty()) return 0
        var i = 0
        while (i < s.length && isCons(s, i)) i++
        var m = 0
        while (i < s.length) {
            while (i < s.length && !isCons(s, i)) i++
            if (i == s.length) break
            while (i < s.length && isCons(s, i)) i++
            m++
        }
        return m
    }

    private fun hasVowel(s: String): Boolean = s.indices.any { !isCons(s, it) }

    private fun endsDoubleCons(s: String): Boolean =
        s.length >= 2 && s[s.length - 1] == s[s.length - 2] && isCons(s, s.length - 1)

    /** Ends consonant-vowel-consonant with the last consonant not w, x or y. */
    private fun endsCvc(s: String): Boolean {
        if (s.length < 3) return false
        val n = s.length
        if (!isCons(s, n - 3) || isCons(s, n - 2) || !isCons(s, n - 1)) return false
        val last = s[n - 1]
        return last != 'w' && last != 'x' && last != 'y'
    }

    fun stem(word: String): String {
        if (word.length < 3) return word
        var w = word

        // Step 1a: plurals.
        w = when {
            w.endsWith("sses") -> w.dropLast(2)
            w.endsWith("ies") -> w.dropLast(2)
            w.endsWith("ss") -> w
            w.endsWith("s") -> w.dropLast(1)
            else -> w
        }

        // Step 1b: past tense and progressive.
        var step1bRanRule2or3 = false
        w = when {
            w.endsWith("eed") -> {
                val stem = w.dropLast(3)
                if (measure(stem) > 0) w.dropLast(1) else w
            }
            w.endsWith("ed") && hasVowel(w.dropLast(2)) -> {
                step1bRanRule2or3 = true
                w.dropLast(2)
            }
            w.endsWith("ing") && hasVowel(w.dropLast(3)) -> {
                step1bRanRule2or3 = true
                w.dropLast(3)
            }
            else -> w
        }
        if (step1bRanRule2or3) {
            w = when {
                w.endsWith("at") || w.endsWith("bl") || w.endsWith("iz") -> w + "e"
                endsDoubleCons(w) && w.last() !in setOf('l', 's', 'z') -> w.dropLast(1)
                measure(w) == 1 && endsCvc(w) -> w + "e"
                else -> w
            }
        }

        // Step 1c: y to i when the stem has a vowel.
        if (w.endsWith("y") && hasVowel(w.dropLast(1))) {
            w = w.dropLast(1) + "i"
        }

        w = applyMappings(w, STEP2, minMeasure = 1)
        w = applyMappings(w, STEP3, minMeasure = 1)
        w = applyMappings(w, STEP4, minMeasure = 2)
        // Step 4's "ion" rule applies only after s or t.
        if (w.endsWith("ion")) {
            val stem = w.dropLast(3)
            if (measure(stem) > 1 && (stem.endsWith("s") || stem.endsWith("t"))) {
                w = stem
            }
        }

        // Step 5a: drop a final e when m > 1, or m == 1 and the stem is not cvc.
        if (w.endsWith("e")) {
            val stem = w.dropLast(1)
            val m = measure(stem)
            if (m > 1 || (m == 1 && !endsCvc(stem))) w = stem
        }

        // Step 5b: ll to l when m > 1.
        if (measure(w) > 1 && endsDoubleCons(w) && w.endsWith("l")) {
            w = w.dropLast(1)
        }

        return w
    }

    private fun applyMappings(word: String, mappings: List<Pair<String, String>>, minMeasure: Int): String {
        for ((suf, repl) in mappings) {
            if (word.endsWith(suf)) {
                val stem = word.dropLast(suf.length)
                return if (measure(stem) >= minMeasure) stem + repl else word
            }
        }
        return word
    }

    private val STEP2 = listOf(
        "ational" to "ate", "tional" to "tion", "enci" to "ence", "anci" to "ance",
        "izer" to "ize", "abli" to "able", "alli" to "al", "entli" to "ent",
        "eli" to "e", "ousli" to "ous", "ization" to "ize", "ation" to "ate",
        "ator" to "ate", "alism" to "al", "iveness" to "ive", "fulness" to "ful",
        "ousness" to "ous", "aliti" to "al", "iviti" to "ive", "biliti" to "ble",
    )

    private val STEP3 = listOf(
        "icate" to "ic", "ative" to "", "alize" to "al", "iciti" to "ic",
        "ical" to "ic", "ful" to "", "ness" to "",
    )

    private val STEP4 = listOf(
        "ement" to "", "ment" to "", "ance" to "", "ence" to "", "able" to "",
        "ible" to "", "ant" to "", "ent" to "", "ism" to "", "ate" to "",
        "iti" to "", "ous" to "", "ive" to "", "ize" to "", "al" to "",
        "er" to "", "ic" to "", "ou" to "",
    )
}
