package com.anopticlabs.gravel.notebook.notes

import com.anopticlabs.gravel.notebook.reminders.ReminderMatch
import com.anopticlabs.gravel.notebook.reminders.isValidTriggerWord
import com.anopticlabs.gravel.notebook.search.BodyTokenizer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Filenames, tags and search stems must not depend on the device locale. Under a Turkish
 * default a locale-sensitive lowercase turns "I" into a dotless i, which then vanishes from
 * a slug and changes a stem.
 */
class LocaleIndependenceTest {

    private fun <T> underTurkishLocale(block: () -> T): T {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr"))
        try {
            return block()
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun slugIgnoresTheDefaultLocale() {
        assertEquals("big-idea", underTurkishLocale { NoteSlug.slugify("BIG IDEA") })
    }

    @Test
    fun tagsIgnoreTheDefaultLocale() {
        val tags = underTurkishLocale { NoteSanitizers.sanitizeTags(JsonArray(listOf(JsonPrimitive("IDEA")))) }
        assertEquals(listOf("idea"), tags)
    }

    @Test
    fun stemsIgnoreTheDefaultLocale() {
        assertEquals(setOf("big", "idea"), underTurkishLocale { BodyTokenizer.tokenizeQuery("BIG IDEA") })
    }

    @Test
    fun triggerWordsIgnoreTheDefaultLocale() {
        underTurkishLocale {
            for (w in listOf("FINISH", "TICK", "BINGO", "DISH", "finish", "Tick")) assertEquals(true, isValidTriggerWord(w), w)
            assertEquals("the dishes", ReminderMatch.stripTriggerPrefix("FINISH the dishes", "finish"))
        }
    }
}
