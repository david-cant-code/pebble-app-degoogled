package com.anopticlabs.gravel.notebook.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PorterStemmerTest {

    @Test
    fun referenceStems() {
        val expected = mapOf(
            "caresses" to "caress", "ponies" to "poni", "cats" to "cat",
            "agreed" to "agre", "plastered" to "plaster", "motoring" to "motor",
            "hopping" to "hop", "filing" to "file", "happy" to "happi",
            "relational" to "relat", "conditional" to "condit", "hopefulness" to "hope",
            "triplicate" to "triplic", "formative" to "form", "electrical" to "electr",
            "revival" to "reviv", "allowance" to "allow", "adjustment" to "adjust",
            "adoption" to "adopt", "probate" to "probat", "controll" to "control",
        )
        for ((word, stem) in expected) assertEquals(stem, PorterStemmer.stem(word), word)
    }

    @Test
    fun stemmingIsNotIdempotent() {
        // Step 5a drops a final e once per pass, so a stem can stem further. Anything holding
        // stems must derive them from display text rather than re-stem them.
        val once = PorterStemmer.stem("agreed")
        assertNotEquals(once, PorterStemmer.stem(once))
    }

    @Test
    fun shortWordsAreLeftAlone() {
        assertEquals("as", PorterStemmer.stem("as"))
        assertEquals("", PorterStemmer.stem(""))
    }
}
