package coredevices.util.transcription

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the model-to-engine language mapping. The load-bearing case: an
 * English-only model must always get "en" no matter what spoken language
 * is configured, because whisper decodes .en models only in English and a
 * mismatched language request degrades output instead of failing loudly.
 */
class WhisperLanguageForTest {

    @Test
    fun englishOnlyModelForcesEnglish() {
        assertEquals("en", whisperLanguageFor(modelMultilingual = false, spokenLanguage = null))
        assertEquals("en", whisperLanguageFor(modelMultilingual = false, spokenLanguage = "de"))
        assertEquals("en", whisperLanguageFor(modelMultilingual = false, spokenLanguage = "en"))
    }

    @Test
    fun multilingualModelPassesTheConfiguredLanguageThrough() {
        assertEquals("de", whisperLanguageFor(modelMultilingual = true, spokenLanguage = "de"))
        assertEquals("en", whisperLanguageFor(modelMultilingual = true, spokenLanguage = "en"))
    }

    @Test
    fun multilingualModelWithNoPreferenceDetectsInEngine() {
        assertNull(whisperLanguageFor(modelMultilingual = true, spokenLanguage = null))
    }

    @Test
    fun unknownModelBehavesLikeMultilingual() {
        // A configured id missing from the catalog (mid-migration states)
        // must not force English on someone dictating another language.
        assertEquals("fr", whisperLanguageFor(modelMultilingual = null, spokenLanguage = "fr"))
        assertNull(whisperLanguageFor(modelMultilingual = null, spokenLanguage = null))
    }
}
